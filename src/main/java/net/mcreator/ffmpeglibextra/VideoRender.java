package net.mcreator.ffmpeglibextra.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Рисует кадр видео прямоугольником в мире (для BlockEntityRenderer "телевизора").
 * Прямоугольник лежит в плоскости XY от (0,0) до (w,h), лицом к +Z.
 * Поворот под сторону блока и сдвиг делайте на PoseStack до вызова.
 */
public final class VideoRender {
	private VideoRender() {
	}

	public static void drawQuad(PoseStack ps, MultiBufferSource buffers, String videoId, float w, float h) {
		drawQuad(ps, buffers, videoId, w, h, LightTexture.FULL_BRIGHT);
	}

	public static void drawQuad(PoseStack ps, MultiBufferSource buffers, String videoId, float w, float h, int light) {
		ResourceLocation tex = VideoManager.texture(videoId);
		if (tex == null)
			return;
		VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(tex));
		PoseStack.Pose pose = ps.last();
		vertex(vc, pose, 0, 0, 0, 1, light);
		vertex(vc, pose, w, 0, 1, 1, light);
		vertex(vc, pose, w, h, 1, 0, light);
		vertex(vc, pose, 0, h, 0, 0, light);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float u, float v, int light) {
		vc.addVertex(pose, x, y, 0f).setColor(255, 255, 255, 255).setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, 1f);
	}
}