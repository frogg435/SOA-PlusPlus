package dev.soatick.mixin.client;

import dev.soatick.client.ClientSoaPass;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * EntityRenderDispatcher Mixin：渲染门禁。
 *
 * shouldRender 是原版决定「这个实体要不要画」的权威入口，
 * WorldRenderer 在画每个实体前都会调用它。在这里把被 SoA
 * 批量 Pass 标记为不可见的实体直接短路：
 *   - 不构造包围盒、不做视锥测试、不取渲染器实例；
 *   - 返回 false 后 WorldRenderer 连 vertex consumer 都不会分配。
 *
 * 兼容性说明：Sodium 不替换 shouldRender 的决策逻辑，本 Mixin
 * 与其协同工作（我们的距离剔除 + 原版/Sodium 的视锥剔除叠加）。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

        @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
        private void soatick$gateShouldRender(Entity entity, Frustum frustum,
                        double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
                if (ClientSoaPass.shouldCull(entity)) {
                        cir.setReturnValue(false);
                }
        }

        /**
         * dispatcher.render HEAD：为 LOD 影子跳过设置「按实体覆写」标志。
         * renderShadow 是本类的 private static，随后被 render 调用——
         * 标志在下一个实体的 render HEAD 被覆写，无 set/clear 泄漏路径。
         */
        @Inject(method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
                        at = @At("HEAD"))
        private void soatick$onEntityRender(Entity entity, double x, double y, double z,
                        float yaw, float tickDelta, CallbackInfo ci) {
                ClientSoaPass.beginEntityRender(entity);
        }

        @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
        private void soatick$lodShadow(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                        Entity entity, float opacity, float tickDelta, WorldView world, float radius,
                        CallbackInfo ci) {
                if (ClientSoaPass.lodSkipShadow()) {
                        ci.cancel();
                }
        }
}
