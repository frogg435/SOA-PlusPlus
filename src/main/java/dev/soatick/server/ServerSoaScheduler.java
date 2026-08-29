package dev.soatick.server;

import dev.soatick.config.SoaConfig;
import dev.soatick.core.ClientSoaStore;
import dev.soatick.core.ServerSoaStore;
import dev.soatick.core.SoaDuck;
import dev.soatick.core.SoaFlags;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * 服务端「距离分环降频」调度器 —— 纯 SoA 决策层。
 *
 * 【工作流程（每 tick、每维度）】
 * 1. 挂在 ServerWorld.tick HEAD：先把本维度玩家坐标镜像进 3 个
 *    double 小数组（玩家通常 ≤ 20 人）；
 * 2. 顺序扫描 SoA 的 occupied 稠密列表：对每个属于本维度的存活实体，
 *    用纯数值运算求「到最近玩家的距离平方」，写入 distSqNearestPlayer[]
 *    并分类出 ring[]（近/中/远/极远）；
 * 3. ServerWorld.tickEntity 的 HEAD 门禁里，shouldSkip() 用一次
 *    位运算 ((globalTick ^ slot) & (div-1)) == 0 判断该实体本 tick
 *    是否轮到 tick —— 不轮到的直接 cancel，跳过整个原版 tick。
 *
 * 【缓存友好性】
 * 步骤 2 全程顺序扫描连续数组（x[]/y[]/z[]/flags[]/dims[]），
 * 唯一的对象交互是玩家坐标——数量少且已提前镜像。
 * 2000 实体 × 20 玩家的距离计算 ≈ 4 万次浮点乘加，在现代 CPU 上
 * 远低于 0.1ms，而原版等价逻辑要沿 2000 个对象指针各自 chase。
 *
 * 【行为兼容性】
 * - 载具+乘客、Boss、拴绳、名牌实体、玩家：默认强制近环满速；
 * - 降频分母是 2 的幂，slot 参与取模做错峰（stagger），
 *   同一环的实体均匀错开 tick，避免「集体卡顿-集体瞬移」；
 * - 降频副作用（远怪移动量子化、掉落物 5 分钟计时变慢）见 README。
 */
public final class ServerSoaScheduler {

        private ServerSoaScheduler() {}

        /** 全局 tick 相位计数器（每维度 tick 各 +1，仅用于取模错峰） */
        private static long phaseTick;

        // 玩家坐标镜像（SoA 精神：内层循环零指针追逐）
        private static final int MAX_PLAYERS = 128;
        private static final double[] px = new double[MAX_PLAYERS];
        private static final double[] py = new double[MAX_PLAYERS];
        private static final double[] pz = new double[MAX_PLAYERS];
        private static int playerCount;

        // ---------- 统计 ----------
        public static long totalSkipped;
        public static long totalTicked;
        private static long passNanosSum;
        private static int passCount;
        public static long lastPassNanos;
        /** 最近一次 Pass 的当前维度分环分布（供 /soa stats 展示） */
        public static final int[] ringCounts = new int[4];
        public static String lastDimName = "-";

        public static double avgPassMicros() {
                return passCount == 0 ? 0.0D : (passNanosSum / passCount) / 1000.0D;
        }

        // =====================================================================
        // Pass 1：距离分环（挂在 ServerWorld.tick HEAD）
        // =====================================================================

        public static void onWorldTickStart(ServerWorld world) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.serverGating) return;

                phaseTick++;
                ServerSoaStore st = ServerSoaStore.get();
                Object dim = world.getRegistryKey();

                // 1) 玩家位置镜像进小数组
                var players = world.getPlayers();
                playerCount = Math.min(players.size(), MAX_PLAYERS);
                for (int i = 0; i < playerCount; i++) {
                        ServerPlayerEntity p = players.get(i);
                        px[i] = p.getX();
                        py[i] = p.getY();
                        pz[i] = p.getZ();
                }

                // 2) 纯数组批量 Pass
                long t0 = System.nanoTime();
                double nearSq = cfg.nearDistance * cfg.nearDistance;
                double midSq = cfg.midDistance * cfg.midDistance;
                double farSq = cfg.farDistance * cfg.farDistance;

                for (int k = 0; k < st.occupiedCount; k++) {
                        int s = st.occupied[k];
                        if ((st.flags[s] & SoaFlags.ALIVE) == 0) continue;  // 死实体不参与
                        if (st.dims[s] != dim) continue;                    // 其它维度另算

                        // 本维度没有玩家 → 整个维度按极远环处理（大幅省 tick）
                        if (playerCount == 0 || isExempt(st.flags[s], cfg)) {
                                st.ring[s] = SoaFlags.RING_NEAR;
                                continue;
                        }

                        double ex = st.x[s], ey = st.y[s], ez = st.z[s];
                        double best = Double.MAX_VALUE;
                        for (int i = 0; i < playerCount; i++) {
                                double dx = px[i] - ex;
                                double dy = py[i] - ey;
                                double dz = pz[i] - ez;
                                double d = dx * dx + dy * dy + dz * dz;
                                if (d < best) best = d;
                        }
                        st.distSqNearestPlayer[s] = (float) best;
                        st.ring[s] = best < nearSq ? SoaFlags.RING_NEAR
                                        : best < midSq ? SoaFlags.RING_MID
                                        : best < farSq ? SoaFlags.RING_FAR
                                        : SoaFlags.RING_BEYOND;
                }

                long dt = System.nanoTime() - t0;
                lastPassNanos = dt;
                passNanosSum += dt;
                passCount++;

                // 3) 刷新分环统计（仅统计刚扫过的维度）
                resetRingCounts();
                lastDimName = dim.toString();
                for (int k = 0; k < st.occupiedCount; k++) {
                        int s = st.occupied[k];
                        if ((st.flags[s] & SoaFlags.ALIVE) == 0) continue;
                        if (st.dims[s] != dim) continue;
                        ringCounts[st.ring[s]]++;
                }
        }

        private static void resetRingCounts() {
                ringCounts[0] = 0;
                ringCounts[1] = 0;
                ringCounts[2] = 0;
                ringCounts[3] = 0;
        }

        /** 豁免判定：命中任一开关即强制近环满速 */
        private static boolean isExempt(int flags, SoaConfig cfg) {
                if ((flags & SoaFlags.PLAYER) != 0) return true;  // 玩家永远满速
                if ((flags & SoaFlags.BOSS) != 0 && cfg.exemptBosses) return true;
                if ((flags & SoaFlags.VEHICLE) != 0 && cfg.exemptVehiclesAndPassengers) return true;
                if ((flags & SoaFlags.PASSENGER) != 0 && cfg.exemptVehiclesAndPassengers) return true;
                if ((flags & SoaFlags.NAMED) != 0 && cfg.exemptNamed) return true;
                if ((flags & SoaFlags.LEASHED) != 0 && cfg.exemptLeashed) return true;
                return false;
        }

        // =====================================================================
        // Pass 2：tick 门禁（挂在 ServerWorld.tickEntity HEAD，可取消）
        // =====================================================================

        /**
         * 返回 true = 本 tick 跳过该实体。
         * 判定全程只做一次数组读 + 位运算，开销 < 2ns。
         */
        public static boolean shouldSkip(Entity entity) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.serverGating) return false;

                int s = ((SoaDuck) entity).soatick$getSlot();
                if (s < 0) return false;                    // 未追踪：原版路径

                byte ring = ServerSoaStore.get().ring[s];
                int div = cfg.divisorForRing(ring);
                if (div <= 1) return false;                 // 近环满速

                // 错峰调度：slot 参与取模，同环实体均匀分散到不同 tick
                boolean skip = ((phaseTick & (div - 1)) != (s & (div - 1)));
                if (skip) totalSkipped++;
                else totalTicked++;
                return skip;
        }

        /** /soa stats 用：客户端侧统计（仅集成服务器可用） */
        public static String clientStatsLine() {
                if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return null;
                ClientSoaStore cs = ClientSoaStore.get();
                return cs.occupiedCount + " / " + cs.capacity;
        }
}
