package net.mcreator.ffmpeglibextra;

import com.mojang.blaze3d.platform.InputConstants;
import net.mcreator.ffmpeglibextra.client.VideoManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

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
                if (mc.player != null && !(mc.screen instanceof VideoTestScreen)) {
                    Path videoFile = extractVideoFromResources("startvideo.mp4");

                    if (videoFile != null && Files.exists(videoFile)) {
                        String videoId = "start_video";
                        
                        try {
                            VideoManager.stop(videoId);
                            VideoManager.prepareAndLoad(videoId, videoFile.toFile(), 25, 480);
                            
                            try {
                                VideoManager.setLoop(videoId, true);
                            } catch (NoSuchMethodError ignored) {}

                            mc.setScreen(new VideoTestScreen(videoId));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        private static Path extractVideoFromResources(String fileName) {
            try {
                Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config/ffmpeglibextra");
                Files.createDirectories(configDir);
                Path targetPath = configDir.resolve(fileName);

                if (!Files.exists(targetPath) || Files.size(targetPath) == 0) {
                    InputStream is = null;
                    try {
                        Optional<Resource> res = Minecraft.getInstance().getResourceManager()
                                .getResource(ResourceLocation.fromNamespaceAndPath("ffmpeglibextra", "videos/" + fileName));
                        if (res.isPresent()) {
                            is = res.get().open();
                        }
                    } catch (Exception ignored) {
                    }

                    if (is == null) {
                        is = TestVideo.class.getClassLoader().getResourceAsStream("assets/ffmpeglibextra/videos/" + fileName);
                    }

                    if (is != null) {
                        try (InputStream in = is) {
                            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        return null;
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
        private boolean started = false;
        private long lastUpdateTime = 0;

        public VideoTestScreen(String videoId) {
            super(Component.literal("Video Player Screen"));
            this.videoId = videoId;
        }

        @Override
        public void tick() {
            super.tick();
            if (!started) {
                VideoManager.play(videoId);
                started = true;
                lastUpdateTime = System.currentTimeMillis();
            }
            
            try {
                boolean playing = true;
                try {
                    playing = (boolean) VideoManager.class.getMethod("isPlaying", String.class).invoke(null, videoId);
                } catch (Exception ignored) {}

                if (started && !playing) {
                    if (System.currentTimeMillis() - lastUpdateTime > 500) {
                        VideoManager.play(videoId);
                        lastUpdateTime = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            VideoManager.blit(guiGraphics, videoId, 0, 0, this.width, this.height);
        }

        @Override
        public void onClose() {
            VideoManager.stop(videoId);
            super.onClose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}