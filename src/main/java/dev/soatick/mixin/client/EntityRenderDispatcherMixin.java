package dev.soatick.mixin.client;

import dev.soatick.client.ClientSoaPass;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
