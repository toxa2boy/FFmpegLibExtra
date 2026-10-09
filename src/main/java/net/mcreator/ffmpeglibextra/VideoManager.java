package net.mcreator.ffmpeglibextra.client;

import net.mcreator.ffmpeglib.AdvancedMediaProcessor;
import net.mcreator.ffmpeglib.FFmpegProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Простой статический API для процедур MCreator и своего кода.
 * Использует FFmpegLib (FFmpegProvider / AdvancedMediaProcessor). ТОЛЬКО КЛИЕНТ.
 *
 * Пример:
 *   VideoManager.prepareAndLoad("tv1", new File("videos/clip.mp4"), 15, 320)
 *       .thenRun(() -> { VideoManager.setLoop("tv1", true); VideoManager.play("tv1"); });
 *   // в Screen.render:  VideoManager.blit(guiGraphics, "tv1", 0, 0, width, height);
 */
public final class VideoManager {
	private static final Map<String, VideoPlayer> PLAYERS = new ConcurrentHashMap<>();

	private VideoManager() {
	}

	public static File framesDir(String id) {
		return new File(FMLPaths.GAMEDIR.get().toFile(), "config/ffmpeglibextra/frames/" + id);
	}

	/** Нарезает видео в PNG (с уменьшением до width px по ширине). Если уже нарезано - ничего не делает. */
	public static CompletableFuture<Void> prepare(String id, File video, int fps, int width) {
		return CompletableFuture.runAsync(() -> {
			File dir = framesDir(id);
			File done = new File(dir, ".done");
			if (done.exists())
				return;
			try {
				dir.mkdirs();
				File[] old = dir.listFiles();
				if (old != null)
					for (File f : old)
						f.delete();
				ProcessBuilder pb = new ProcessBuilder(
						FFmpegProvider.getExecutable().getAbsolutePath(),
						"-y", "-i", video.getAbsolutePath(),
						"-vf", "fps=" + fps + ",scale=" + width + ":-2",
						new File(dir, "frame_%05d.png").getAbsolutePath());
				pb.redirectErrorStream(true);
				Process p = pb.start();
				try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
					while (r.readLine() != null) {
					}
				}
				if (p.waitFor() != 0)
					throw new java.io.IOException("ffmpeg завершился с ошибкой");
				done.createNewFile();
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		});
	}

	/** Создаёт плеер из уже нарезанных кадров (выполняется в клиентском потоке). */
	public static CompletableFuture<Void> load(String id, int fps) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				VideoPlayer old = PLAYERS.remove(id);
				if (old != null)
					old.close();
				PLAYERS.put(id, new VideoPlayer(id, framesDir(id), fps));
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

	/** Достаёт звук в .ogg через FFmpegLib (воспроизведение звука - отдельный шаг). */
	public static CompletableFuture<File> extractAudio(String id, File video) {
		File out = new File(FMLPaths.GAMEDIR.get().toFile(), "config/ffmpeglibextra/audio/" + id);
		return AdvancedMediaProcessor.extractAudioCustom(FFmpegProvider.getExecutable(), video, out, "ogg");
	}

	// ---------- управление ----------
	public static void play(String id) {
		VideoPlayer p = PLAYERS.get(id);
		if (p != null)
			p.play();
	}

	public static void pause(String id) {
		VideoPlayer p = PLAYERS.get(id);
		if (p != null)
			p.pause();
	}

	public static void stop(String id) {
		VideoPlayer p = PLAYERS.get(id);
		if (p != null)
			p.stop();
	}

	public static void setLoop(String id, boolean loop) {
		VideoPlayer p = PLAYERS.get(id);
		if (p != null)
			p.setLoop(loop);
	}

	public static void unload(String id) {
		VideoPlayer p = PLAYERS.remove(id);
		if (p != null)
			p.close();
	}

	/** Текстура текущего кадра или null, если видео не загружено. Вызывать из render-потока. */
	public static ResourceLocation texture(String id) {
		VideoPlayer p = PLAYERS.get(id);
		return p == null ? null : p.update();
	}

	/** Рисует текущий кадр в GUI/на фоне экрана. */
	public static void blit(GuiGraphics g, String id, int x, int y, int w, int h) {
		ResourceLocation tex = texture(id);
		if (tex != null)
			g.blit(tex, x, y, w, h, 0f, 0f, 1, 1, 1, 1);
	}
}