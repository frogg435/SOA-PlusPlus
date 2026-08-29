package dev.soatick.mixin.client;

import dev.soatick.client.ClientSoaPass;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldRenderer Mixin：每帧渲染前的批量剔除 Pass。
 *
 * WorldRenderer.render(...) 是实体渲染的总入口；在它的 HEAD 处
 * 相机已完成更新、实体渲染尚未开始——此时用相机位置刷新一遍
 * ClientSoaStore 的距离与可见状态，随后 shouldRender 门禁即可
 * 以 O(1) 查表决定每个实体是否参与渲染。
 *
 * 注意：本 Mixin 只做「提前决策」，不修改任何渲染管线数据，
 * 与 Sodium 对 WorldRenderer 的区块渲染优化完全正交。
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void soatick$onRenderFrame(MatrixStack matrices, float tickDelta, long limitTime,
			boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
			LightmapTextureManager lightmapTextureManager, Matrix4f projectionMatrix,
			CallbackInfo ci) {
		ClientSoaPass.onFrame(camera);
	}
}
