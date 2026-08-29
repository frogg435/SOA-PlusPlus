package dev.soatick.server;

import dev.soatick.config.SoaConfig;
import dev.soatick.core.ClientSoaStore;
import dev.soatick.core.ServerSoaStore;
import dev.soatick.core.SoaDuck;
import dev.soatick.core.SoaFlags;
import dev.soatick.core.SoaStore.DimBucket;
import dev.soatick.mixin.ItemEntityAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * 服务端「距离分环降频」调度器 —— 纯 SoA 决策层。
 *
 * 【工作流程（每 tick、每维度）】
 * 1. 挂在 ServerWorld.tick HEAD：把本维度玩家坐标镜像进 double 小数组，
 *    同时记录本维度「玩家最大位移」（增量环更新的误差界）；
 * 2. 顺序扫描本维度的稠密桶（维度分桶：不碰其它维度的槽位）：
 *    - 实体位置未变 且 三角不等式夹逼证明环号不可能变化 → 直接沿用旧环；
 *    - 否则用纯数值运算求「到最近玩家的距离平方」，写入 distSqNearestPlayer[]
 *      并刷新 ring[]，记录位置快照；
 * 3. ServerWorld.tickEntity 的 HEAD 门禁里，shouldSkip() 用一次
 *    位运算 ((globalTick ^ slot) & (div-1)) == 0 判断该实体本 tick
 *    是否轮到 tick —— 不轮到的直接 cancel，跳过整个原版 tick。
 *
 * 【缓存友好性】
 * 步骤 2 全程顺序扫描连续数组（x[]/y[]/z[]/flags[]），唯一的对象交互
 * 是玩家坐标——数量少且已提前镜像。维度分桶后扫描长度 = 本维度实体数，
 * 多维服务器上主世界不再为下界/末地的实体空转。
 *
 * 【增量环更新的正确性】
 * 设实体位置未动、玩家集合最大位移为 m，旧最近距离为 d：
 *   新最近距离 ∈ [max(0, d-m), d+m]（欧氏距离三角不等式）。
 * 若该区间的环号上下界相同，环号必不变，安全跳过重算。
 * 环带宽度至少 8 格，区间收缩的误判概率为零（保守外扩 0.1% 兜浮点误差）。
 *
 * 【行为兼容性】
 * - 载具+乘客、Boss、拴绳、名牌实体、玩家：默认强制近环满速；
 * - 无玩家的维度整体按极远环处理（出生区块常驻加载的实体不再白烧 CPU）；
 * - 降频分母是 2 的幂，slot 参与取模做错峰（stagger）；
 * - 降频副作用（远怪移动量子化、掉落物 5 分钟计时变慢）见 README。
 */
public final class ServerSoaScheduler {

        private ServerSoaScheduler() {}

        /** 全局 tick 相位计数器（每维度 tick 各 +1，仅用于取模错峰） */
        private static long phaseTick;

        // ---------- 玩家坐标镜像（SoA 精神：内层循环零指针追逐） ----------
        private static final int MAX_PLAYERS = 128;
        private static final double[] px = new double[MAX_PLAYERS];
        private static final double[] py = new double[MAX_PLAYERS];
        private static final double[] pz = new double[MAX_PLAYERS];
        private static int playerCount;

        /**
         * 每维度玩家镜像：记录上一 tick 坐标，推导「玩家最大位移」。
         * 这是增量环更新的误差界来源；IdentityHashMap 零装箱。
         */
        private static final class PlayerMirror {
                final double[] lastX = new double[MAX_PLAYERS];
                final double[] lastY = new double[MAX_PLAYERS];
                final double[] lastZ = new double[MAX_PLAYERS];
                int lastCount;
                double maxMoveSq;
        }

        private static final java.util.IdentityHashMap<Object, PlayerMirror> MIRRORS =
                        new java.util.IdentityHashMap<>();

        // ---------- 统计 ----------
        public static long totalSkipped;
        public static long totalTicked;
        /** 增量环更新跳过的重算次数（观测指标） */
        public static long ringReuses;
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

                // 1) 玩家位置镜像 + 本维度玩家最大位移
                PlayerMirror m = MIRRORS.computeIfAbsent(dim, k -> new PlayerMirror());
                var players = world.getPlayers();
                playerCount = Math.min(players.size(), MAX_PLAYERS);
                double maxMoveSq = 0.0D;
                for (int i = 0; i < playerCount; i++) {
                        ServerPlayerEntity p = players.get(i);
                        px[i] = p.getX();
                        py[i] = p.getY();
                        pz[i] = p.getZ();
                        if (i < m.lastCount) {
                                double dx = px[i] - m.lastX[i];
                                double dy = py[i] - m.lastY[i];
                                double dz = pz[i] - m.lastZ[i];
                                double d2 = dx * dx + dy * dy + dz * dz;
                                if (d2 > maxMoveSq) maxMoveSq = d2;
                        } else {
                                maxMoveSq = Double.MAX_VALUE;   // 新进玩家：保守全量重算
                        }
                        m.lastX[i] = px[i];
                        m.lastY[i] = py[i];
                        m.lastZ[i] = pz[i];
                }
                m.lastCount = playerCount;
                m.maxMoveSq = maxMoveSq;

                // 2) 纯数组批量 Pass（本维度稠密桶）
                long t0 = System.nanoTime();
                double nearSq = cfg.nearDistance * cfg.nearDistance;
                double midSq = cfg.midDistance * cfg.midDistance;
                double farSq = cfg.farDistance * cfg.farDistance;
                double pMove = maxMoveSq == Double.MAX_VALUE
                                ? Double.MAX_VALUE : Math.sqrt(maxMoveSq);

                DimBucket b = st.bucketOf(dim);
                int[] slots = b.slots;
                int n = b.count;

                for (int k = 0; k < n; k++) {
                        int s = slots[k];
                        if ((st.flags[s] & SoaFlags.ALIVE) == 0) continue;  // 死实体不参与

                        // 本维度没有玩家 → 整个维度按极远环处理
                        //（修复：旧版误置 NEAR 使出生区块实体全速空转，与文档相悖）
                        if (playerCount == 0) {
                                st.ring[s] = SoaFlags.RING_BEYOND;
                                continue;
                        }
                        if (isExempt(st.flags[s], cfg)) {
                                st.ring[s] = SoaFlags.RING_NEAR;
                                continue;
                        }

                        float fx = (float) st.x[s];
                        float fy = (float) st.y[s];
                        float fz = (float) st.z[s];

                        // 增量环更新：实体未动时用三角不等式夹逼，环号不变则沿用
                        if (st.ringValid[s]
                                        && fx == st.lastRingX[s]
                                        && fy == st.lastRingY[s]
                                        && fz == st.lastRingZ[s]
                                        && pMove != Double.MAX_VALUE) {
                                double dOld = st.distSqNearestPlayer[s];
                                double rOld = Math.sqrt(dOld);
                                double lo = rOld > pMove ? (rOld - pMove) : 0.0D;
                                double loSq = lo * lo * 0.999D;          // 保守外扩兜浮点误差
                                double hiSq = (rOld + pMove) * (rOld + pMove) * 1.001D;
                                byte rLo = ringOf(loSq, nearSq, midSq, farSq);
                                byte rHi = ringOf(hiSq, nearSq, midSq, farSq);
                                if (rLo == rHi) {
                                        st.ring[s] = rLo;   // 数学上必等于旧值，写回兜底
                                        ringReuses++;
                                        continue;
                                }
                        }

                        // 全量重算：到最近玩家的距离平方
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
                        st.ring[s] = ringOf(best, nearSq, midSq, farSq);
                        st.lastRingX[s] = fx;
                        st.lastRingY[s] = fy;
                        st.lastRingZ[s] = fz;
                        st.ringValid[s] = true;
                }

                long dt = System.nanoTime() - t0;
                lastPassNanos = dt;
                passNanosSum += dt;
                passCount++;

                // 3) 刷新分环统计（只扫本维度桶，O(本维度实体数)）
                resetRingCounts();
                lastDimName = dim.toString();
                for (int k = 0; k < n; k++) {
                        int s = slots[k];
                        if ((st.flags[s] & SoaFlags.ALIVE) == 0) continue;
                        ringCounts[st.ring[s]]++;
                }

                // 4) 远景掉落物合并 Pass（每 100 tick 一次）：
                //    物品硬跳过后远处掉落物不再 tick，原版 0.5 格合并也随之失效，
                //    农场产物会堆积成实体海。这里用 SoA 距离数组做 O(物²) 距离
                //    预筛（仅物品、每 5 秒一次、纯 double 运算），命中才碰
                //    ItemStack 对象，把小堆吸入大堆——直接减少实体数量本身。
                if (cfg.itemMerge && cfg.itemHardSkipFromRing >= 0
                                && world.getTime() % 100L == 0L) {
                        mergeFarItems(st, b, cfg);
                }
        }

        /** 远景掉落物合并：同堆型、半径内、总数量不超上限 → 大堆吸收小堆 */
        private static void mergeFarItems(ServerSoaStore st, DimBucket b, SoaConfig cfg) {
                int threshold = Math.max(cfg.itemHardSkipFromRing, 1);
                double rSq = cfg.itemMergeRadius * cfg.itemMergeRadius;

                // 先收集本桶内的远景掉落物槽位（绝大多数实体非物品，这里只有少数成员）
                int[] items = new int[Math.min(b.count, 1024)];
                int nItems = 0;
                for (int k = 0; k < b.count && nItems < items.length; k++) {
                        int s = b.slots[k];
                        if ((st.flags[s] & SoaFlags.ALIVE) == 0) continue;
                        if (st.category[s] != SoaFlags.CAT_ITEM) continue;
                        if (st.ring[s] < threshold) continue;
                        items[nItems++] = s;
                }
                if (nItems < 2) return;

                for (int i = 0; i < nItems; i++) {
                        int si = items[i];
                        if ((st.flags[si] & SoaFlags.ALIVE) == 0) continue;   // 已被吞并
                        Entity ei = st.entities[si];
                        if (!(ei instanceof ItemEntity big)) continue;
                        ItemStack bs = big.getStack();
                        if (bs.isEmpty()) continue;
                        for (int j = i + 1; j < nItems; j++) {
                                int sj = items[j];
                                if ((st.flags[sj] & SoaFlags.ALIVE) == 0) continue;
                                // SoA 纯数值距离预筛（缓存友好，不碰对象）
                                double dx = st.x[si] - st.x[sj];
                                double dy = st.y[si] - st.y[sj];
                                double dz = st.z[si] - st.z[sj];
                                if (dx * dx + dy * dy + dz * dz > rSq) continue;

                                Entity ej = st.entities[sj];
                                if (!(ej instanceof ItemEntity small)) continue;
                                ItemStack ss = small.getStack();
                                if (ss.isEmpty()) continue;
                                if (!ItemEntity.canMerge(bs, ss)) continue;
                                int max = Math.max(bs.getMaxCount(), ss.getMaxCount());
                                if (bs.getCount() >= max || ss.getCount() > bs.getCount()) continue;

                                int transfer = Math.min(ss.getCount(), max - bs.getCount());
                                if (transfer <= 0) continue;
                                bs.increment(transfer);
                                if (transfer == ss.getCount()) {
                                        small.setStack(ItemStack.EMPTY);
                                        small.discard();        // remove → SoA 槽位 O(1) 回收
                                } else {
                                        ss.decrement(transfer);
                                }
                                totalMerges++;
                                if (transfer == ss.getCount()) break;   // sj 槽位已死，跳到下一个 i
                        }
                }
        }

        private static byte ringOf(double dSq, double nearSq, double midSq, double farSq) {
                return dSq < nearSq ? SoaFlags.RING_NEAR
                                : dSq < midSq ? SoaFlags.RING_MID
                                : dSq < farSq ? SoaFlags.RING_FAR
                                : SoaFlags.RING_BEYOND;
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

        /** 单实体单 tick 的调度决策 */
        public enum Decision {
                /** 满速原版 tick */
                TICK,
                /** 放行 tick 但砍掉 AI（目标选择器/导航/Brain） */
                DEGRADE,
                /** 整个 tick 跳过 */
                SKIP
        }

        /** AI 降级累计次数（观测指标） */
        public static long totalAiDegraded;
        /** 物品硬跳过累计次数（观测指标） */
        public static long totalItemHardSkipped;
        /** 推挤对取消累计次数（观测指标） */
        public static long totalPushSkipped;
        /** 远景掉落物合并累计次数（观测指标） */
        public static long totalMerges;

        /**
         * 三态决策：比旧版 shouldSkip 多出 DEGRADE 档——
         * 远环生物不再「全跳」，而是保留物理/移动、砍掉 AI，减少量子移动观感。
         */
        public static Decision decide(Entity entity) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.serverGating) return Decision.TICK;

                int s = ((SoaDuck) entity).soatick$getSlot();
                if (s < 0) return Decision.TICK;            // 未追踪：原版路径

                ServerSoaStore st = ServerSoaStore.get();
                byte ring = st.ring[s];

                // 每实体类型规则：首次决策解析，缓存进鸭子字段（热路径零查表）
                byte ov = ((SoaDuck) entity).soatick$getRuleOverride();
                if (ov == (byte) -2) {
                        ov = cfg.resolveRule(entity.getType());
                        ((SoaDuck) entity).soatick$setRuleOverride(ov);
                }
                if (ov == 0) return Decision.TICK;          // 类型豁免：恒满速原版

                int div = cfg.applyDivisorCap(cfg.divisorForRing(ring), ov);

                // 物品硬跳过：先于错峰判定；每个被跳过的 tick 快进 1 itemAge，
                // 与原版 tick 内的 ++itemAge 等价——消失计时按真实速率推进
                if (cfg.itemHardSkipFromRing >= 0 && div > 1
                                && ring >= cfg.itemHardSkipFromRing
                                && st.category[s] == SoaFlags.CAT_ITEM
                                && entity instanceof ItemEntity) {
                        ItemEntityAccessor acc = (ItemEntityAccessor) entity;
                        acc.soatick$setItemAge(acc.soatick$getItemAge() + 1);
                        totalItemHardSkipped++;
                        totalSkipped++;
                        return Decision.SKIP;
                }

                if (div <= 1) return Decision.TICK;         // 近环满速

                // 错峰调度：slot 参与取模，同环实体均匀分散到不同 tick
                boolean skip = ((phaseTick & (div - 1)) != (s & (div - 1)));
                if (skip) {
                        totalSkipped++;
                        return Decision.SKIP;
                }
                totalTicked++;

                // AI 降级：本 tick 放行，但砍掉 AI；只对 MobEntity 有意义
                if (cfg.aiDegradeFromRing >= 0
                                && ring >= cfg.aiDegradeFromRing
                                && (st.flags[s] & SoaFlags.MOB) != 0) {
                        totalAiDegraded++;
                        return Decision.DEGRADE;
                }
                return Decision.TICK;
        }

        /**
         * 推挤碰撞跳过：推挤双方任一方在生效环内即取消这对 push。
         * 豁免实体恒为近环，自然不受影响。
         */
        public static boolean shouldSkipPush(Entity a, Entity b) {
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.serverGating) return false;
                int threshold = cfg.pushSkipFromRing;
                if (threshold < 0) return false;

                int sa = ((SoaDuck) a).soatick$getSlot();
                if (sa < 0) return false;
                int sb = ((SoaDuck) b).soatick$getSlot();
                if (sb < 0) return false;

                // 类型豁免：任一方被规则豁免则不取消推挤
                if (((SoaDuck) a).soatick$getRuleOverride() == 0
                                || ((SoaDuck) b).soatick$getRuleOverride() == 0) return false;

                ServerSoaStore st = ServerSoaStore.get();
                boolean skip = st.ring[sa] >= threshold || st.ring[sb] >= threshold;
                if (skip) totalPushSkipped++;
                return skip;
        }

        /** 旧版门禁入口（兼容保留）：SKIP 视为 true */
        public static boolean shouldSkip(Entity entity) {
                return decide(entity) == Decision.SKIP;
        }

        /** /soa stats 用：客户端侧统计（仅集成服务器可用） */
        public static String clientStatsLine() {
                if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return null;
                ClientSoaStore cs = ClientSoaStore.get();
                return cs.occupiedCount + " / " + cs.capacity;
        }
}
