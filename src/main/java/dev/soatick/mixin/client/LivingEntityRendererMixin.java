package dev.soatick.mixin.client;

import dev.soatick.client.ClientSoaPass;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LivingEntityRenderer Mixin：LOD 名牌降级。
 *
 * hasLabel 决定是否为该实体渲染悬浮名牌（文本 billboard + 字体渲染）。
 * 超过 LOD 距离（默认 48 格）的实体名牌直接跳过——远处根本看不清，
 * 字体渲染却是逐顶点开销，Pojav 上群怪场景收益明显。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

        @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z",
                        at = @At("HEAD"), cancellable = true)
        private void soatick$lodLabel(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
                if (ClientSoaPass.lodSkipLabel(entity)) {
                        cir.setReturnValue(false);
                }
        }
}
