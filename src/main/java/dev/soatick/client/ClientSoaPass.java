package dev.soatick.client;

import dev.soatick.config.SoaConfig;
import dev.soatick.core.ClientSoaStore;
import dev.soatick.core.SoaDuck;
import dev.soatick.core.SoaFlags;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * 客户端「分类距离 + 迟滞」渲染剔除 Pass —— 纯 SoA 决策层。
 *
 * 【工作流程（每帧）】
 * 1. 挂在 WorldRenderer.render HEAD（相机已就位、实体渲染未开始）；
 * 2. 顺序扫描 ClientSoaStore 的 occupied 稠密列表，对每个存活实体：
 *    - 纯数值算出到相机的距离平方 → distSqToCamera[]；
 *    - 按 category[] 查配置得到该类实体的渲染距离上限；
 *    - 迟滞更新 visible[]（1=渲染，0=剔除）。
 * 3. EntityRenderDispatcher.shouldRender HEAD 门禁读 visible[]，
 *    被剔除的实体直接返回 false——连包围盒构造、视锥测试都不用做。
 *
 * 【迟滞防闪烁（Hysteresis）】
 * 直接「超过 R 就剔除」会在临界距离反复横跳：玩家前后移动半格，
 * 远处的牛就忽隐忽现。所以：
 *   - 可见 → 剔除：需要 dist > limit（严格超出才隐藏）
 *   - 剔除 → 可见：需要 dist < limit - hysteresis（回到缓冲带内才恢复）
 * 在 (limit - hysteresis, limit) 区间内维持原状态，彻底消除闪烁。
 *
 * 【与 Sodium 的关系】
 * Sodium 优化的是区块（chunk）渲染管线与部分实体顶点路径；
 * 本 Mod 只在 shouldRender 入口加「距离级」剔除，与 Sodium 的
 * 视锥/遮挡剔除是互补关系，两者叠加生效、互不冲突。
 */
public final class ClientSoaPass {

        private ClientSoaPass() {}

        // ---------- 统计（/soa stats / F3 调试参考） ----------
        public static int lastCulled;
        public static int lastTotal;
        public static long lastPassNanos;

        // =====================================================================
        // 每帧批量 Pass（挂在 WorldRenderer.render HEAD）
        // =====================================================================

        public static void onFrame(Camera camera) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.renderCulling) {
                        lastCulled = 0;
                        lastTotal = 0;
                        return;
                }

                ClientSoaStore st = ClientSoaStore.get();
                Vec3d cam = camera.getPos();
                double cx = cam.x, cy = cam.y, cz = cam.z;
                double hyst = cfg.hysteresisBlocks;

                // 相机快照供遮挡剔除工作线程使用 + 惰性清缓存
                OcclusionWorker.updateCamera(cx, cy, cz);
                OcclusionWorker.evictStale();

                long t0 = System.nanoTime();
                int culled = 0;
                int total = 0;
                double occlMinSq = cfg.occlusionMinDistance * cfg.occlusionMinDistance;

                for (int k = 0; k < st.occupiedCount; k++) {
                        int s = st.occupied[k];
                        if ((st.flags[s] & SoaFlags.ALIVE) == 0) continue;
                        total++;

                        // 纯数值距离计算（顺序扫描，缓存友好）
                        double dx = st.x[s] - cx;
                        double dy = st.y[s] - cy;
                        double dz = st.z[s] - cz;
                        double d2 = dx * dx + dy * dy + dz * dz;
                        st.distSqToCamera[s] = (float) d2;

                        double limit = cfg.renderLimitForCategory(st.category[s]);
                        if (limit <= 0.0D) {          // 0 = 该类不限制（如玩家、Boss）
                                st.visible[s] = 1;
                                continue;
                        }

                        if (st.visible[s] == 1) {
                                // 当前可见：严格超出上限才剔除
                                if (d2 > limit * limit) {
                                        st.visible[s] = 0;
                                        culled++;
                                }
                        } else {
                                // 当前已剔除：退回缓冲带内才恢复
                                double restore = limit - hyst;
                                if (restore <= 0.0D || d2 < restore * restore) {
                                        st.visible[s] = 1;
                                } else {
                                        culled++;
                                }
                        }

                        // 遮挡剔除请求：距离内但超出近距阈值、非玩家/Boss 的实体
                        if (cfg.occlusionCulling && st.visible[s] == 1
                                        && d2 > occlMinSq
                                        && st.category[s] != SoaFlags.CAT_PLAYER
                                        && st.category[s] != SoaFlags.CAT_BOSS
                                        && st.entities[s] != null) {
                                OcclusionWorker.request(st.entities[s]);
                        }
                }

                lastPassNanos = System.nanoTime() - t0;
                lastCulled = culled;
                lastTotal = total;
        }

        // =====================================================================
        // 门禁（挂在 EntityRenderDispatcher.shouldRender HEAD）
        // =====================================================================

        /** 返回 true = 本帧不渲染该实体。单次数组读，开销可忽略。 */
        public static boolean shouldCull(Entity entity) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.renderCulling) return false;

                int s = ((SoaDuck) entity).soatick$getSlot();
                if (s < 0) return false;              // 未追踪：原版渲染路径
                if (ClientSoaStore.get().visible[s] == 0) return true;
                // 遮挡剔除：距离内但被墙挡住（缓存查询，未知的 fail-open）
                return OcclusionWorker.isOccluded(entity);
        }

        // =====================================================================
        // LOD 降级渲染（远处实体省掉名牌与影子）
        // =====================================================================

        /** 影子跳过的 ThreadLocal（dispatcher.render HEAD 按实体覆写） */
        private static final ThreadLocal<Boolean> SKIP_SHADOW =
                        ThreadLocal.withInitial(() -> Boolean.FALSE);

        /** dispatcher.render HEAD：该实体是否跳过影子（覆写式，无泄漏） */
        public static void beginEntityRender(Entity entity) {
                SoaConfig cfg = SoaConfig.get();
                boolean skip = false;
                if (cfg.enabled && cfg.lodShadows) {
                        int s = ((SoaDuck) entity).soatick$getSlot();
                        if (s >= 0) {
                                float d2 = ClientSoaStore.get().distSqToCamera[s];
                                skip = d2 > 48.0F * 48.0F;
                        }
                }
                SKIP_SHADOW.set(skip);
        }

        /** renderShadow HEAD：读覆写标志 */
        public static boolean lodSkipShadow() {
                return SKIP_SHADOW.get();
        }

        /** hasLabel HEAD：远处实体不画名牌 */
        public static boolean lodSkipLabel(Entity entity) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.lodNametags) return false;
                int s = ((SoaDuck) entity).soatick$getSlot();
                if (s < 0) return false;
                float d2 = ClientSoaStore.get().distSqToCamera[s];
                return d2 > 48.0F * 48.0F;
        }
}
