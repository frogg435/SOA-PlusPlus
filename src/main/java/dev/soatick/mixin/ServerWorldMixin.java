package dev.soatick.mixin;

import dev.soatick.server.AiDegrade;
import dev.soatick.server.ServerSoaScheduler;
import dev.soatick.server.ServerSoaScheduler.Decision;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * ServerWorld Mixin：服务端 SoA 决策层的两个挂点。
 *
 * 1. tick(BooleanSupplier) HEAD
 *    → 每维度 tick 前跑一次「距离分环 Pass」（纯数组扫描 + 维度分桶 +
 *      增量环更新），为本维度所有存活实体分类分环。
 *
 * 2. tickEntity(Entity) HEAD（可取消）
 *    → 三态门禁：
 *      SKIP    ：cancel 整个原版 tick（AI/移动/碰撞全不跑）；
 *      DEGRADE ：放行 tick 但沿 ThreadLocal 通知下游 Mixin 砍掉 AI；
 *      TICK    ：完全原版。
 *      乘客不经过 tickEntity（由载具带动），载具+乘客默认满速豁免，
 *      因此不存在「载具停了乘客还在动」的撕裂。
 *      ThreadLocal 采用「每次 HEAD 覆写」而非 set/clear 配对，
 *      无泄漏路径（见 AiDegrade 注释）。
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

        @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
        private void soatick$onWorldTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
                ServerSoaScheduler.onWorldTickStart((ServerWorld) (Object) this);
        }

        @Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
        private void soatick$gateEntityTick(Entity entity, CallbackInfo ci) {
                Decision d = ServerSoaScheduler.decide(entity);
                AiDegrade.set(d == Decision.DEGRADE);
                if (d == Decision.SKIP) {
                        ci.cancel();
                }
        }
}
