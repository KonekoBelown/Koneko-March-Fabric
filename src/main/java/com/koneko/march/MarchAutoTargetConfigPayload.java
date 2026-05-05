package com.koneko.march;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record MarchAutoTargetConfigPayload(List<String> entityIds) implements CustomPayload {
    public static final CustomPayload.Id<MarchAutoTargetConfigPayload> ID = new CustomPayload.Id<>(Identifier.of(KonekoMarch.MOD_ID, "auto_target_config"));
    public static final PacketCodec<RegistryByteBuf, MarchAutoTargetConfigPayload> CODEC = PacketCodec.ofStatic(
            MarchAutoTargetConfigPayload::write,
            MarchAutoTargetConfigPayload::read
    );

    public MarchAutoTargetConfigPayload {
        entityIds = List.copyOf(KonekoMarchConfig.sanitizeAutoTargetIdSet(entityIds));
    }

    private static void write(RegistryByteBuf buf, MarchAutoTargetConfigPayload payload) {
        List<String> ids = payload.entityIds();
        int count = Math.min(ids.size(), KonekoMarchConfig.MAX_AUTO_TARGET_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeString(ids.get(i), 128);
        }
    }

    private static MarchAutoTargetConfigPayload read(RegistryByteBuf buf) {
        int count = Math.min(buf.readVarInt(), KonekoMarchConfig.MAX_AUTO_TARGET_ENTRIES);
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readString(128));
        }
        return new MarchAutoTargetConfigPayload(ids);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
