package dev.soatick.mixin;

import dev.soatick.server.AiDegrade;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MobEntity Mixin：AI 降级的落点。
 *
 * tickNewAi() 是目标选择器 / 导航 / 移动控制等 AI 子系统集中更新的地方。
 * 当上游门禁（ServerWorld.tickEntity HEAD）通过 ThreadLocal 标记本实体
 * 处于 AI 降级档时，这里直接 cancel——生物保留物理、移动、碰撞，
 * 但不再「思考」（不寻路、不索敌、不切换目标）。
 *
 * 典型场景：远环的怪群从「整体跳 tick（量子瞬移）」变成
 * 「惯性滑行 + 不思考」，观感大幅平滑，CPU 依然省下 AI 大头。
 */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin {

        @Inject(method = "tickNewAi()V", at = @At("HEAD"), cancellable = true)
        private void soatick$gateNewAi(CallbackInfo ci) {
                if (AiDegrade.active()) {
                        ci.cancel();
                }
        }
}
