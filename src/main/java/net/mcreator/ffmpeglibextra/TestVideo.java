package net.mcreator.ffmpeglibextra;

import com.mojang.blaze3d.platform.InputConstants;
import net.mcreator.ffmpeglibextra.client.VideoManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class TestVideo {

    public static final KeyMapping PLAY_VIDEO_KEY = new KeyMapping(
            "key.ffmpeglibextra.play_video",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.ffmpeglibextra.general"
    );

    @SubscribeEvent
    public static void init(FMLCommonSetupEvent event) {}

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void registerBindings(RegisterKeyMappingsEvent event) {
        event.register(PLAY_VIDEO_KEY);
    }

    @OnlyIn(Dist.CLIENT)
    @EventBusSubscriber(value = Dist.CLIENT)
    public static class ClientForgeEvents {

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            while (PLAY_VIDEO_KEY.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    System.out.println("[FFmpegLibExtra] Нажата клавиша G. Проверяем видеофайл...");
                    Path videoFile = extractVideoFromResources("startvideo.mp4");

                    if (videoFile != null && Files.exists(videoFile)) {
                        System.out.println("[FFmpegLibExtra] Видео найдено: " + videoFile);
                        String videoId = "start_video";
                        
                        try {
                            VideoManager.prepareAndLoad(videoId, videoFile.toFile(), 25, 480);
                            VideoManager.play(videoId);
                            
                            // Включаем воспроизведение по кругу (зацикливание)
                            VideoManager.setLoop(videoId, true);

                            mc.setScreen(new VideoTestScreen(videoId));
                            System.out.println("[FFmpegLibExtra] Плеер успешно запущен в цикличном режиме!");
                        } catch (Exception e) {
                            System.err.println("[FFmpegLibExtra] ОШИБКА при запуске видео через VideoManager:");
                            e.printStackTrace();
                        }
                    } else {
                        System.err.println("[FFmpegLibExtra] Ошибка: видеофайл не найден на диске!");
                    }
                }
            }
        }

        private static Path extractVideoFromResources(String fileName) {
            try {
                Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config/ffmpeglibextra");
                Files.createDirectories(configDir);
                Path targetPath = configDir.resolve(fileName);

                if (!Files.exists(targetPath)) {
                    System.out.println("[FFmpegLibExtra] Копируем startvideo.mp4 из ресурсов мода в config...");
                    try (InputStream is = TestVideo.class.getResourceAsStream("/assets/ffmpeglibextra/videos/" + fileName)) {
                        if (is != null) {
                            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("[FFmpegLibExtra] Успешно скопировано в: " + targetPath);
                        } else {
                            System.err.println("[FFmpegLibExtra] Ошибка: stream равен нулю! Проверь путь к ресурсу в assets.");
                            return null;
                        }
                    }
                }
                return targetPath;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    public static class VideoTestScreen extends Screen {
        private final String videoId;

        public VideoTestScreen(String videoId) {
            super(Component.literal("Video Player Screen"));
            this.videoId = videoId;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            int width = 480;
            int height = 270;
            int x = (this.width - width) / 2;
            int y = (this.height - height) / 2;

            VideoManager.blit(guiGraphics, videoId, x, y, width, height);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}