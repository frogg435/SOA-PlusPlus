package dev.soatick.mixin;

import dev.soatick.server.ServerSoaScheduler;
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
 *    → 每维度 tick 前跑一次「距离分环 Pass」（纯数组扫描），
 *      为本维度所有存活实体计算到最近玩家的距离并分环。
 *
 * 2. tickEntity(Entity) HEAD（可取消）
 *    → 原 版 tick 的总入口：shouldSkip() 返回 true 就 cancel，
 *      该实体本 tick 完整跳过（AI/移动/碰撞全都不跑）。
 *      乘客不经过 tickEntity（由载具带动），载具+乘客默认满速豁免，
 *      因此不存在「载具停了乘客还在动」的撕裂。
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
	private void soatick$onWorldTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		ServerSoaScheduler.onWorldTickStart((ServerWorld) (Object) this);
	}

	@Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void soatick$gateEntityTick(Entity entity, CallbackInfo ci) {
		if (ServerSoaScheduler.shouldSkip(entity)) {
			ci.cancel();
		}
	}
}
