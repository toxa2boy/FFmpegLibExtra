package net.mcreator.ffmpeglibextra.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Проигрывает последовательность PNG-кадров (frame_00001.png ...) в одну DynamicTexture.
 * ТОЛЬКО КЛИЕНТ. Все методы вызывать из render-потока (кроме внутренней подгрузки кадров).
 */
public class VideoPlayer implements AutoCloseable {
	private static final int PREFETCH = 8; // сколько кадров вперёд держим в памяти
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ffmpeglibextra-frame-loader");
		t.setDaemon(true);
		return t;
	});

	private final List<File> frames;
	private final int fps;
	private final ResourceLocation location;
	private final DynamicTexture texture;
	private final Map<Integer, NativeImage> cache = new ConcurrentHashMap<>();
	private final Set<Integer> pending = ConcurrentHashMap.newKeySet();

	private volatile boolean closed;
	private boolean playing;
	private boolean loop;
	private long startNanos; // точка отсчёта часов, пока playing
	private long pausedElapsed; // прошедшее время, пока на паузе
	private int shown = -1;

	public VideoPlayer(String id, File framesDir, int fps) throws IOException {
		File[] files = framesDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
		if (files == null || files.length == 0)
			throw new IOException("Нет кадров в " + framesDir);
		Arrays.sort(files);
		this.frames = List.of(files);
		this.fps = Math.max(1, fps);

		NativeImage first;
		try (InputStream in = Files.newInputStream(frames.get(0).toPath())) {
			first = NativeImage.read(in);
		}
		this.texture = new DynamicTexture(first);
		String safe = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		this.location = ResourceLocation.fromNamespaceAndPath("ffmpeglibextra", "video/" + safe);
		Minecraft.getInstance().getTextureManager().register(location, texture);
	}

	// ---------- управление ----------
	public void play() {
		if (!playing) {
			startNanos = System.nanoTime() - pausedElapsed;
			playing = true;
		}
	}

	public void pause() {
		if (playing) {
			pausedElapsed = System.nanoTime() - startNanos;
			playing = false;
		}
	}

	public void stop() {
		playing = false;
		pausedElapsed = 0;
	}

	public void seek(double seconds) {
		long ns = (long) (Math.max(0, seconds) * 1_000_000_000L);
		if (playing)
			startNanos = System.nanoTime() - ns;
		else
			pausedElapsed = ns;
	}

	public void setLoop(boolean loop) {
		this.loop = loop;
	}

	public boolean isPlaying() {
		return playing;
	}

	public double getDurationSeconds() {
		return frames.size() / (double) fps;
	}

	// ---------- обновление и доступ к текстуре ----------
	/** Обновляет текстуру под текущее время и возвращает её ResourceLocation. */
	public ResourceLocation update() {
		int n = frames.size();
		long elapsed = playing ? System.nanoTime() - startNanos : pausedElapsed;
		long idxL = elapsed * fps / 1_000_000_000L;
		if (idxL >= n) {
			if (loop) {
				idxL %= n;
			} else {
				idxL = n - 1;
				if (playing) {
					playing = false;
					pausedElapsed = (long) n * 1_000_000_000L / fps;
				}
			}
		}
		int idx = (int) idxL;

		// окно подгрузки + вытеснение лишнего
		Set<Integer> window = new HashSet<>();
		for (int i = 0; i <= PREFETCH; i++) {
			int k = idx + i;
			if (k >= n) {
				if (!loop)
					break;
				k %= n;
			}
			window.add(k);
			load(k);
		}
		for (Integer key : new ArrayList<>(cache.keySet())) {
			if (!window.contains(key)) {
				NativeImage img = cache.remove(key);
				if (img != null)
					img.close();
			}
		}

		// показываем кадр, если он уже загружен (иначе остаётся предыдущий)
		if (idx != shown) {
			NativeImage img = cache.get(idx);
			NativeImage dst = texture.getPixels();
			if (img != null && dst != null && dst.getWidth() == img.getWidth() && dst.getHeight() == img.getHeight()) {
				dst.copyFrom(img);
				texture.upload();
				shown = idx;
			}
		}
		return location;
	}

	private void load(int k) {
		if (cache.containsKey(k) || !pending.add(k))
			return;
		LOADER.execute(() -> {
			try (InputStream in = Files.newInputStream(frames.get(k).toPath())) {
				NativeImage img = NativeImage.read(in);
				if (closed)
					img.close();
				else
					cache.put(k, img);
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				pending.remove(k);
			}
		});
	}

	@Override
	public void close() {
		closed = true;
		playing = false;
		for (NativeImage img : cache.values())
			img.close();
		cache.clear();
		Minecraft.getInstance().getTextureManager().release(location); // закрывает и DynamicTexture
	}
}