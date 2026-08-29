package dev.soatick.mixin;

import dev.soatick.core.SoaDuck;
import dev.soatick.core.SoaWrite;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity 基类 Mixin：SoA 写透 + 槽位字段注入。
 *
 * 钩子清单（全部为 TAIL 旁路写，绝不修改原版逻辑）：
 * - baseTick HEAD        ：懒分配槽位 + 每 tick 快照刷新（唯一分配点）
 * - setPos(DDD) TAIL     ：位置写透（最热路径：移动、重力、传送全走这）
 * - setVelocity(Vec3d) TAIL：速度写透
 * - remove(...) HEAD     ：槽位 O(1) 回收
 *
 * 槽位为 -1（未追踪）时所有钩子都是一次字段读 + 一次比较，
 * 对未优化实体零负担。
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements SoaDuck {

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
}
