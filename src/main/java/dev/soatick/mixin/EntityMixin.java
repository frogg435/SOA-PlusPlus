package dev.soatick.mixin;

import dev.soatick.client.ClientSmoothing;
import dev.soatick.core.SoaClientDuck;
import dev.soatick.core.SoaClientDuck.SmoothingState;
import dev.soatick.core.SoaDuck;
import dev.soatick.core.SoaWrite;
import dev.soatick.server.ServerSoaScheduler;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity 基类 Mixin：SoA 写透 + 槽位字段注入 + 客户端位置平滑。
 *
 * 钩子清单：
 * - baseTick HEAD        ：懒分配槽位 + 每 tick 快照刷新（唯一分配点）
 * - setPos(DDD) TAIL     ：位置写透（最热路径：移动、重力、传送全走这）
 * - setVelocity TAIL     ：速度写透
 * - remove HEAD          ：槽位 O(1) 回收
 * - pushAwayFrom HEAD    ：推挤碰撞跳过（服务端）
 * - updateTrackedPositionAndAngles HEAD/TAIL：客户端位置平滑接管
 * - tick TAIL            ：客户端重插值推进
 *
 * 槽位为 -1（未追踪）时所有钩子都是一次字段读 + 一次比较，
 * 对未优化实体零负担。
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements SoaDuck, SoaClientDuck {

        /** SoA 槽位号，-1 = 未追踪 */
        @Unique
        private int soatick$slot = -1;

        @Override
        public int soatick$getSlot() {
                return this.soatick$slot;
        }

        @Override
        public void soatick$setSlot(int slot) {
                this.soatick$slot = slot;
        }

        /** 实体类型规则覆盖缓存（-2 未解析），见 SoaDuck 注释 */
        @Unique
        private byte soatick$ruleOverride = (byte) -2;

        @Override
        public byte soatick$getRuleOverride() {
                return this.soatick$ruleOverride;
        }

        @Override
        public void soatick$setRuleOverride(byte v) {
                this.soatick$ruleOverride = v;
        }

        /** 客户端重插值状态（懒创建；服务端分支从不调用，恒为 null） */
        @Unique
        private SmoothingState soatick$smoothing;

        @Override
        public SmoothingState soatick$getSmoothing() {
                if (this.soatick$smoothing == null) {
                        this.soatick$smoothing = new SmoothingState();
                }
                return this.soatick$smoothing;
        }

        @Inject(method = "baseTick()V", at = @At("HEAD"))
        private void soatick$onBaseTick(CallbackInfo ci) {
                SoaWrite.onBaseTick((Entity) (Object) this);
        }

        @Inject(method = "setPos(DDD)V", at = @At("TAIL"))
        private void soatick$onSetPos(double x, double y, double z, CallbackInfo ci) {
                SoaWrite.writePos((Entity) (Object) this, x, y, z);
        }

        @Inject(method = "setVelocity(Lnet/minecraft/util/math/Vec3d;)V", at = @At("TAIL"))
        private void soatick$onSetVelocity(Vec3d velocity, CallbackInfo ci) {
                SoaWrite.writeVel((Entity) (Object) this, velocity);
        }

        @Inject(method = "remove(Lnet/minecraft/entity/Entity$RemovalReason;)V", at = @At("HEAD"))
        private void soatick$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
                SoaWrite.onRemoved((Entity) (Object) this);
        }

        /**
         * 推挤碰撞门禁：pushAwayFrom 是实体间相互推挤的单对实现。
         * 推挤双方任一方处于「推挤跳过环」内（默认远环及更远），
         * 这对 push 直接取消——大型怪堆里成对推挤是 CPU 大头之一，
         * 而远处实体间的推挤对玩家体验毫无影响。
         * 豁免实体恒为近环，自然不受影响。
         */
        @Inject(method = "pushAwayFrom(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
        private void soatick$gatePushAway(Entity other, CallbackInfo ci) {
                if (((Entity) (Object) this).getWorld().isClient) return;   // 客户端推挤不干预
                if (ServerSoaScheduler.shouldSkipPush((Entity) (Object) this, other)) {
                        ci.cancel();
                }
        }

        // ===================== 客户端位置平滑 =====================

        /** 仅客户端环境才允许触碰 ClientSmoothing（它引用仅客户端类，
         *  专用服务器上提前加载会 NoClassDefFoundError；分支不执行则永不解析） */
        @Unique
        private static boolean soatick$isClient() {
                return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        }

        @Inject(method = "updateTrackedPositionAndAngles(DDDFFIZ)V", at = @At("HEAD"))
        private void soatick$onTrackedUpdateHead(double x, double y, double z, float yaw, float pitch,
                        int steps, boolean rotate, CallbackInfo ci) {
                if (!soatick$isClient()) return;
                ClientSmoothing.onTrackedUpdateHead((Entity) (Object) this);
        }

        @Inject(method = "updateTrackedPositionAndAngles(DDDFFIZ)V", at = @At("TAIL"))
        private void soatick$onTrackedUpdateTail(double x, double y, double z, float yaw, float pitch,
                        int steps, boolean rotate, CallbackInfo ci) {
                if (!soatick$isClient()) return;
                ClientSmoothing.onTrackedUpdateTail((Entity) (Object) this, yaw, pitch);
        }

        @Inject(method = "tick()V", at = @At("TAIL"))
        private void soatick$onTickTail(CallbackInfo ci) {
                if (!soatick$isClient()) return;
                ClientSmoothing.onClientTickTail((Entity) (Object) this);
        }
}
