package com.koneko.march.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import com.koneko.march.KonekoMarch;
import com.koneko.march.KonekoMarchConfig;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class KonekoMarchClient implements ClientModInitializer {
    private static final KeyBinding.Category MARCH_CATEGORY = KeyBinding.Category.create(Identifier.of("koneko", "march"));

    private static KeyBinding recallKey;
    private static KeyBinding releaseKey;

    @Override
    public void onInitializeClient() {
        KonekoMarchConfig.get();
        EntityRendererRegistry.register(KonekoMarch.MARCH_SOLDIER_TYPE, MarchSoldierRenderer::new);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(KonekoMarchClientConfigSync::sendToServer));

        recallKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.koneko.march.recall",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                MARCH_CATEGORY
        ));

        releaseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.koneko.march.release",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                MARCH_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (recallKey.wasPressed()) {
                if (client.player != null && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("konekomarch recall");
                }
            }
            while (releaseKey.wasPressed()) {
                if (client.player != null && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("konekomarch release");
                }
            }
        });
    }
}
