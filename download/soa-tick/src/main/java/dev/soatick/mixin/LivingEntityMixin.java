package dev.soatick.mixin;

import dev.soatick.core.SoaWrite;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntity Mixin：血量写透。
 * 伤害、回血、治疗药水都会走 setHealth，TAIL 时机保证镜像值
 * 与实体真实血量始终一致。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	@Inject(method = "setHealth(F)V", at = @At("TAIL"))
	private void soatick$onSetHealth(float health, CallbackInfo ci) {
		SoaWrite.writeHealth((LivingEntity) (Object) this, health);
	}
}
