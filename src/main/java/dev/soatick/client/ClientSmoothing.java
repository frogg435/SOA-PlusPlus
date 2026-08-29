package dev.soatick.client;

import dev.soatick.config.SoaConfig;
import dev.soatick.core.ClientSoaStore;
import dev.soatick.core.SoaClientDuck;
import dev.soatick.core.SoaClientDuck.SmoothingState;
import dev.soatick.core.SoaDuck;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

/**
 * 客户端位置平滑（重插值）——消除降频实体的「量子移动」观感。
 *
 * 挂点（都在 EntityMixin，客户端分支才生效）：
 * 1. updateTrackedPositionAndAngles HEAD：记录当前视觉位置/角度；
 * 2. updateTrackedPositionAndAngles TAIL：原版已瞬移到包内目标；
 *    对降频实体撤销瞬移，改为「从视觉位置到目标、div 步重插值」；
 * 3. tick TAIL：每 client tick 推进一步，渲染插值 lerp(prevX→x) 依然平滑。
 *
 * 【分环近似】
 * 客户端不知道服务端对每个实体的分环结果，用「到本地玩家的距离」按
 * 同一套配置阈值近似分环：单人模式玩家=相机，完全精确；多人模式下
 * 近处玩家围观的实体可能被其他玩家判成不同环——最坏情况只是插值
 * 步数不是最优，纯表现层，无正确性问题。
 *
 * 【豁免】
 * 载具/乘客等豁免实体在服务端恒为近环（div=1），客户端同样判近环，
 * 立即退出接管——骑乘坐骑的手感不受任何影响。
 */
public final class ClientSmoothing {

        private ClientSmoothing() {}

        /** Entity.updateTrackedPositionAndAngles HEAD：记录视觉位置 */
        public static void onTrackedUpdateHead(Entity e) {
                if (!e.getWorld().isClient) return;
                if (e instanceof SoaClientDuck d) {
                        SmoothingState sm = d.soatick$getSmoothing();
                        sm.vx = e.getX();
                        sm.vy = e.getY();
                        sm.vz = e.getZ();
                        sm.vYaw = e.getYaw();
                        sm.vPitch = e.getPitch();
                }
        }

        /** Entity.updateTrackedPositionAndAngles TAIL：决定是否接管 */
        public static void onTrackedUpdateTail(Entity e, float yaw, float pitch) {
                if (!e.getWorld().isClient) return;
                SoaConfig cfg = SoaConfig.get();
                if (!cfg.enabled || !cfg.smoothDegrade) return;
                if (!(e instanceof SoaClientDuck d)) return;

                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.player.isRemoved()) return;

                // 按到本地玩家的距离近似分环（复用服务端距离阈值与分母配置）
                double dx = e.getX() - mc.player.getX();
                double dy = e.getY() - mc.player.getY();
                double dz = e.getZ() - mc.player.getZ();
                double dSq = dx * dx + dy * dy + dz * dz;
                int div = cfg.divisorForRing(ringOf(dSq, cfg));
                SmoothingState sm = d.soatick$getSmoothing();

                if (div <= 1) {
                        sm.left = 0;                    // 近环：原版瞬移行为
                        return;
                }

                sm.x0 = sm.vx;
                sm.y0 = sm.vy;
                sm.z0 = sm.vz;
                sm.yaw0 = sm.vYaw;
                sm.pitch0 = sm.vPitch;
                sm.x1 = e.getX();
                sm.y1 = e.getY();
                sm.z1 = e.getZ();
                sm.yaw1 = yaw;
                sm.pitch1 = pitch;
                sm.total = Math.min(div, 16);           // 封顶防卡死观感
                sm.left = sm.total;

                // 撤销原版瞬移，回到视觉位置；渲染插值基于 prev→x 连续推进
                e.setPosition(sm.x0, sm.y0, sm.z0);
                e.setYaw(sm.yaw0);
                e.setPitch(sm.pitch0);
        }

        /** Entity.tick TAIL：推进重插值一步 */
        public static void onClientTickTail(Entity e) {
                if (!e.getWorld().isClient) return;
                if (!(e instanceof SoaClientDuck d)) return;
                SmoothingState sm = d.soatick$getSmoothing();
                if (sm.left <= 0) return;
                sm.left--;
                float t = (float) (sm.total - sm.left) / (float) sm.total;
                e.setPosition(
                                MathHelper.lerp(t, sm.x0, sm.x1),
                                MathHelper.lerp(t, sm.y0, sm.y1),
                                MathHelper.lerp(t, sm.z0, sm.z1));
                e.setYaw(MathHelper.lerpAngleDegrees(t, sm.yaw0, sm.yaw1));
                e.setPitch(MathHelper.lerp(t, sm.pitch0, sm.pitch1));
        }

        /** 与服务端 ringOf 相同的阈值判定（复用配置距离） */
        private static byte ringOf(double dSq, SoaConfig cfg) {
                int r = dSq < cfg.nearDistance * cfg.nearDistance
                                ? 0 : dSq < cfg.midDistance * cfg.midDistance
                                ? 1 : dSq < cfg.farDistance * cfg.farDistance
                                ? 2 : 3;
                return (byte) r;
        }
}
