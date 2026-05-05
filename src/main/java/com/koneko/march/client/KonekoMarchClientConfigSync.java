package com.koneko.march.client;

import com.koneko.march.KonekoMarchConfig;
import com.koneko.march.MarchAutoTargetConfigPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

public final class KonekoMarchClientConfigSync {
    public static void sendToServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        try {
            ClientPlayNetworking.send(new MarchAutoTargetConfigPayload(KonekoMarchConfig.getAutoTargetEntityIds()));
        } catch (IllegalStateException ignored) {
        }
    }

    private KonekoMarchClientConfigSync() {
    }
}
