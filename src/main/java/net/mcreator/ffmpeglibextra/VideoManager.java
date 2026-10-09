package net.mcreator.ffmpeglibextra.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.mcreator.ffmpeglib.FFmpegProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.joml.Matrix4f;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoManager {
    private static final Map<String, VideoPlayer> PLAYERS = new ConcurrentHashMap<>();
    private static final Map<String, Clip> AUDIO_CLIPS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> IS_PLAYING = new ConcurrentHashMap<>();
    private static final Map<String, BlockOverlay> OVERLAYS = new ConcurrentHashMap<>();

    public record BlockOverlay(String id, BlockPos pos, Direction face, float maxAudioDistance) {}

    private VideoManager() {
    }

    public static File framesDir(String id) {
        return new File(FMLPaths.GAMEDIR.get().toFile(), "config/ffmpeglibextra/frames/" + id);
    }

    public static File audioFile(String id) {
        return new File(FMLPaths.GAMEDIR.get().toFile(), "config/ffmpeglibextra/audio/" + id + ".wav");
    }

    public static void registerOverlay(String id, BlockPos pos, Direction face, float maxAudioDistance) {
        OVERLAYS.put(id, new BlockOverlay(id, pos, face, maxAudioDistance));
    }

    public static void unregisterOverlay(String id) {
        OVERLAYS.remove(id);
    }

    public static CompletableFuture<Void> prepare(String id, File video, int fps, int width) {
        return CompletableFuture.runAsync(() -> {
            File dir = framesDir(id);
            File audioOut = audioFile(id);
            File done = new File(dir, ".done");
            if (done.exists() && audioOut.exists() && audioOut.length() > 0)
                return;
            try {
                dir.mkdirs();
                if (audioOut.getParentFile() != null) {
                    audioOut.getParentFile().mkdirs();
                }

                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        f.delete();
                    }
                }

                ProcessBuilder pbFrames = new ProcessBuilder(
                        FFmpegProvider.getExecutable().getAbsolutePath(),
                        "-y", "-i", video.getAbsolutePath(),
                        "-vf", "fps=" + fps + ",scale=" + width + ":-2",
                        "-compression_level", "1",
                        new File(dir, "frame_%05d.png").getAbsolutePath());
                pbFrames.redirectErrorStream(true);
                Process p1 = pbFrames.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p1.getInputStream()))) {
                    while (r.readLine() != null) {}
                }
                if (p1.waitFor() != 0) {
                    throw new java.io.IOException("FFmpeg frame extraction failed");
                }

                ProcessBuilder pbAudio = new ProcessBuilder(
                        FFmpegProvider.getExecutable().getAbsolutePath(),
                        "-y", "-i", video.getAbsolutePath(),
                        "-vn",
                        "-acodec", "pcm_s16le",
                        "-ar", "44100",
                        "-ac", "2",
                        audioOut.getAbsolutePath());
                pbAudio.redirectErrorStream(true);
                Process p2 = pbAudio.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p2.getInputStream()))) {
                    while (r.readLine() != null) {}
                }
                p2.waitFor();

                done.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }

    public static CompletableFuture<Void> load(String id, int fps) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                VideoPlayer oldPlayer = PLAYERS.remove(id);
                if (oldPlayer != null) {
                    oldPlayer.close();
                }

                Clip oldClip = AUDIO_CLIPS.remove(id);
                if (oldClip != null) {
                    oldClip.close();
                }

                PLAYERS.put(id, new VideoPlayer(id, framesDir(id), fps));
                IS_PLAYING.put(id, false);

                File audio = audioFile(id);
                if (audio.exists() && audio.length() > 0) {
                    try {
                        AudioInputStream ais = AudioSystem.getAudioInputStream(audio);
                        Clip clip = AudioSystem.getClip();
                        clip.open(ais);
                        AUDIO_CLIPS.put(id, clip);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                result.complete(null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public static CompletableFuture<Void> prepareAndLoad(String id, File video, int fps, int width) {
        return prepare(id, video, fps, width).thenCompose(v -> load(id, fps));
    }

    public static void play(String id) {
        VideoPlayer p = PLAYERS.get(id);
        if (p != null) {
            p.play();
        }
        Clip clip = AUDIO_CLIPS.get(id);
        if (clip != null) {
            if (clip.getFramePosition() >= clip.getFrameLength()) {
                clip.setFramePosition(0);
            }
            clip.start();
        }
        IS_PLAYING.put(id, true);
    }

    public static void pause(String id) {
        VideoPlayer p = PLAYERS.get(id);
        if (p != null) {
            p.pause();
        }
        Clip clip = AUDIO_CLIPS.get(id);
        if (clip != null && clip.isRunning()) {
            clip.stop();
        }
        IS_PLAYING.put(id, false);
    }

    public static void stop(String id) {
        VideoPlayer p = PLAYERS.get(id);
        if (p != null) {
            p.stop();
        }
        Clip clip = AUDIO_CLIPS.get(id);
        if (clip != null) {
            clip.stop();
            clip.setFramePosition(0);
        }
        IS_PLAYING.put(id, false);
    }

    public static boolean isPlaying(String id) {
        return IS_PLAYING.getOrDefault(id, false);
    }

    public static void setLoop(String id, boolean loop) {
        VideoPlayer p = PLAYERS.get(id);
        if (p != null) {
            p.setLoop(loop);
        }
        Clip clip = AUDIO_CLIPS.get(id);
        if (clip != null) {
            if (loop) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            } else {
                clip.loop(0);
            }
        }
    }

    public static void updateAudioVolume(String id, double x, double y, double z, float maxDistance) {
        Clip clip = AUDIO_CLIPS.get(id);
        if (clip != null && clip.isRunning()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double dist = Math.sqrt(mc.player.distanceToSqr(x, y, z));
                float volume = Math.max(0.0f, 1.0f - (float) (dist / maxDistance));
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float dB = (volume <= 0.0001f) ? -80.0f : (float) (Math.log10(volume) * 20.0);
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
                }
            }
        }
    }

    public static void tickAllOverlays() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        for (BlockOverlay overlay : OVERLAYS.values()) {
            if (mc.level.getBlockState(overlay.pos).isAir()) {
                if (isPlaying(overlay.id)) {
                    pause(overlay.id);
                }
                continue;
            }

            tickPlaybackState(overlay.id);
            updateAudioVolume(overlay.id, overlay.pos.getX() + 0.5, overlay.pos.getY() + 0.5, overlay.pos.getZ() + 0.5, overlay.maxAudioDistance);
        }
    }

    public static void handleRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        BlockPos clickedPos = event.getPos();
        Direction clickedFace = event.getHitVec().getDirection();

        for (BlockOverlay overlay : OVERLAYS.values()) {
            if (overlay.pos.equals(clickedPos) && overlay.face == clickedFace) {
                if (isPlaying(overlay.id)) {
                    pause(overlay.id);
                } else {
                    play(overlay.id);
                }
                break;
            }
        }
    }

    public static void renderAllOverlays(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();

        for (BlockOverlay overlay : OVERLAYS.values()) {
            if (mc.level.getBlockState(overlay.pos).isAir()) {
                continue;
            }

            ResourceLocation texture = texture(overlay.id);
            if (texture == null) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(overlay.pos.getX() - cam.x, overlay.pos.getY() - cam.y, overlay.pos.getZ() - cam.z);

            MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
            VertexConsumer builder = buffer.getBuffer(RenderType.entitySolid(texture));
            Matrix4f matrix = poseStack.last().pose();

            renderFace(builder, matrix, overlay.face);

            poseStack.popPose();
        }
    }

    private static void renderFace(VertexConsumer builder, Matrix4f matrix, Direction face) {
        switch (face) {
            case SOUTH -> {
                builder.addVertex(matrix, 0.0f, 1.0f, 1.001f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
                builder.addVertex(matrix, 0.0f, 0.0f, 1.001f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
                builder.addVertex(matrix, 1.0f, 0.0f, 1.001f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
                builder.addVertex(matrix, 1.0f, 1.0f, 1.001f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
            }
            case NORTH -> {
                builder.addVertex(matrix, 1.0f, 1.0f, -0.001f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, -1);
                builder.addVertex(matrix, 1.0f, 0.0f, -0.001f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, -1);
                builder.addVertex(matrix, 0.0f, 0.0f, -0.001f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, -1);
                builder.addVertex(matrix, 0.0f, 1.0f, -0.001f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, -1);
            }
            case EAST -> {
                builder.addVertex(matrix, 1.001f, 1.0f, 1.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(1, 0, 0);
                builder.addVertex(matrix, 1.001f, 0.0f, 1.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(1, 0, 0);
                builder.addVertex(matrix, 1.001f, 0.0f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(1, 0, 0);
                builder.addVertex(matrix, 1.001f, 1.0f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(1, 0, 0);
            }
            case WEST -> {
                builder.addVertex(matrix, -0.001f, 1.0f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(-1, 0, 0);
                builder.addVertex(matrix, -0.001f, 0.0f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(-1, 0, 0);
                builder.addVertex(matrix, -0.001f, 0.0f, 1.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(-1, 0, 0);
                builder.addVertex(matrix, -0.001f, 1.0f, 1.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(-1, 0, 0);
            }
            case UP -> {
                builder.addVertex(matrix, 0.0f, 1.001f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
                builder.addVertex(matrix, 0.0f, 1.001f, 1.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
                builder.addVertex(matrix, 1.0f, 1.001f, 1.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
                builder.addVertex(matrix, 1.0f, 1.001f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
            }
            case DOWN -> {
                builder.addVertex(matrix, 0.0f, -0.001f, 1.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, -1, 0);
                builder.addVertex(matrix, 0.0f, -0.001f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, -1, 0);
                builder.addVertex(matrix, 1.0f, -0.001f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, -1, 0);
                builder.addVertex(matrix, 1.0f, -0.001f, 1.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, -1, 0);
            }
        }
    }

    public static void tickPlaybackState(String id) {
        Clip clip = AUDIO_CLIPS.get(id);
        if (isPlaying(id) && clip != null) {
            if (!clip.isRunning() && clip.getFramePosition() >= clip.getFrameLength()) {
                stop(id);
            }
        }
    }

    public static void unload(String id) {
        VideoPlayer p = PLAYERS.remove(id);
        if (p != null) {
            p.close();
        }
        Clip clip = AUDIO_CLIPS.remove(id);
        if (clip != null) {
            if (clip.isRunning()) {
                clip.stop();
            }
            clip.close();
        }
        IS_PLAYING.remove(id);
        OVERLAYS.remove(id);
    }

    public static ResourceLocation texture(String id) {
        VideoPlayer p = PLAYERS.get(id);
        return p == null ? null : p.update();
    }

    public static void blit(GuiGraphics g, String id, int x, int y, int w, int h) {
        ResourceLocation tex = texture(id);
        if (tex != null) {
            g.blit(tex, x, y, w, h, 0f, 0f, 1, 1, 1, 1);
        }
    }
}