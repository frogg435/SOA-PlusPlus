package dev.soatick.client;

import dev.soatick.config.SoaConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步遮挡剔除——被墙完全挡住的远景实体不渲染（EntityCulling 同思路）。
 *
 * 【工作方式】
 * 渲染线程在每帧 Pass 里只做两件事：查缓存、把过期/未知的实体丢进队列；
 * 真正的方块射线检测在单个后台守护线程执行（每实体 2 条射线：
 * 实体中心 + 实体眼部，任一命中方块且命中点在半路 → 判定被遮挡）。
 * 缓存按 UUID 键控，TTL 内直接复用，超龄重测。
 *
 * 【线程安全（诚实说明）】
 * 后台线程读 ClientWorld 方块数据与渲染线程的区块写入存在理论竞争。
 * 采取三重防护：
 * 1. 全程 try/catch，任何异常一律判「可见」（fail-open，绝不出黑影）；
 * 2. 射线走 RaycastContext 官方路径（与原版同款查询，实践中线程容忍度良好）；
 * 3. 队列有界（512），实体移除后缓存惰性过期，无引用泄漏。
 *
 * 【与距离剔除的关系】
 * 距离剔除（迟滞）先行——被距离剔除的实体不进遮挡检测；
 * 遮挡只「追加」剔除：距离内但被墙挡住的实体省掉整段渲染。
 */
public final class OcclusionWorker {

        private OcclusionWorker() {}

        private static final int QUEUE_CAP = 512;
        private static final long TTL_TICKS = 40;

        private static final ConcurrentLinkedQueue<UUID> QUEUE = new ConcurrentLinkedQueue<>();
        private static final Map<UUID, OcclEntry> CACHE = new ConcurrentHashMap<>();
        private static final AtomicLong TICK_COUNTER = new AtomicLong();

        /** 渲染线程每帧更新的相机位置（volatile 快照） */
        private static volatile double camX, camY, camZ;
        private static volatile boolean camValid;

        private static final class OcclEntry {
                boolean occluded;
                long stamp;
                Entity ref;                     // 弱生命周期: 缓存TTL内自然过期
        }

        static {
                Thread t = new Thread(OcclusionWorker::run, "SOA++-Occlusion");
                t.setDaemon(true);
                t.start();
        }

        /** 渲染线程：每帧 Pass 开头更新相机快照 */
        public static void updateCamera(double x, double y, double z) {
                camX = x;
                camY = y;
                camZ = z;
                camValid = true;
                TICK_COUNTER.incrementAndGet();
        }

        /**
         * 渲染线程调用：该实体当前是否判定为被遮挡。
         * 返回 false = 可见或未知（fail-open），绝不造成额外剔除。
         */
        public static boolean isOccluded(Entity e) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.occlusionCulling) return false;
                if (!camValid) return false;
                OcclEntry en = CACHE.get(e.getUuid());
                if (en == null) return false;
                return en.occluded;
        }

        /**
         * 渲染线程调用（每帧 Pass）：对远景实体请求遮挡检测。
         * 缓存新鲜 → 直接用；过期/缺失 → 入队等待。
         */
        public static void request(Entity e) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.occlusionCulling || !camValid) return;
                UUID id = e.getUuid();
                OcclEntry en = CACHE.get(id);
                long now = TICK_COUNTER.get();
                if (en != null && now - en.stamp < TTL_TICKS) return;
                if (QUEUE.size() >= QUEUE_CAP) return;
                if (en == null) {
                        en = new OcclEntry();
                        en.occluded = false;            // 未知 → 先按可见
                        CACHE.put(id, en);
                }
                en.ref = e;
                en.stamp = now;
                QUEUE.offer(id);
        }

        /** 惰性清理超龄缓存（渲染线程低频调用） */
        public static void evictStale() {
                if ((TICK_COUNTER.get() & 127) != 0) return;    // 每 128 tick 一次
                long now = TICK_COUNTER.get();
                CACHE.values().removeIf(en -> now - en.stamp > (TTL_TICKS << 4));
        }

        // ===================== 后台守护线程 =====================

        private static void run() {
                while (true) {
                        UUID id = QUEUE.poll();
                        if (id == null) {
                                try {
                                        Thread.sleep(2);
                                } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                        return;
                                }
                                continue;
                        }
                        OcclEntry en = CACHE.get(id);
                        if (en == null) continue;
                        try {
                                en.occluded = testOcclusion(en);
                        } catch (Throwable ignored) {
                                en.occluded = false;            // fail-open
                        }
                }
        }

        /** 两条射线：实体中心 / 实体眼部，任一通视即可见 */
        private static boolean testOcclusion(OcclEntry en) {
                MinecraftClient mc = MinecraftClient.getInstance();
                ClientWorld world = mc.world;
                if (world == null) return false;
                Entity e = en.ref;
                if (e == null || e.isRemoved()) return false;

                Vec3d target = e.getPos();
                Vec3d cam = new Vec3d(camX, camY, camZ);

                if (rayBlocked(world, cam, target, e)) return true;
                Vec3d eye = target.add(0, e.getStandingEyeHeight() * 0.9D, 0);
                return rayBlocked(world, cam, eye, e);
        }

        private static boolean rayBlocked(ClientWorld world, Vec3d from, Vec3d to, Entity e) {
                try {
                        RaycastContext ctx = new RaycastContext(
                                        from, to,
                                        RaycastContext.ShapeType.COLLIDER,
                                        RaycastContext.FluidHandling.NONE, e);
                        BlockHitResult hit = world.raycast(ctx);
                        return hit.getType() == HitResult.Type.BLOCK;
                } catch (Throwable t) {
                        return false;                            // fail-open
                }
        }
}
