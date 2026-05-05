package com.koneko.march.client;

import com.koneko.march.entity.MarchSoldierEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.EquipmentModelData;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import net.minecraft.client.util.math.MatrixStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Player-shaped renderer for March soldiers.
 *
 * Fix2: the armor feature must receive actual armor BipedEntityModel instances.
 * Passing BipedEntityModel.createEquipmentModelData(...) compiles when raw types are used,
 * but crashes at runtime with a ClassCastException inside ArmorFeatureRenderer.
 */
public final class MarchSoldierRenderer extends BipedEntityRenderer<MarchSoldierEntity, MarchSoldierRenderState, BipedEntityModel<MarchSoldierRenderState>> {
    private static final Identifier FALLBACK_TEXTURE = Identifier.of("minecraft", "textures/entity/player/wide/steve.png");

    public MarchSoldierRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER)), 0.5f);
        this.addFeature(new HeldItemFeatureRenderer<>(this));
        EquipmentModelData<BipedEntityModel<MarchSoldierRenderState>> playerArmorModels =
                EquipmentModelData.mapToEntityModel(
                        EntityModelLayers.PLAYER_EQUIPMENT,
                        context.getEntityModels(),
                        BipedEntityModel::new
                );
        this.addFeature(new ArmorFeatureRenderer<>(
                this,
                playerArmorModels,
                context.getEquipmentRenderer()
        ));
    }

    @Override
    public MarchSoldierRenderState createRenderState() {
        return new MarchSoldierRenderState();
    }

    @Override
    public void updateRenderState(MarchSoldierEntity entity, MarchSoldierRenderState state, float tickProgress) {
        super.updateRenderState(entity, state, tickProgress);
        BipedEntityRenderer.updateBipedRenderState(entity, state, tickProgress, this.itemModelResolver);
        Optional<UUID> owner = entity.getTrackedOwnerUuid();
        state.ownerUuid = owner.orElse(null);
        // Ensure the biped model uses its mounted/sitting pose when the soldier is riding a March horse.
        state.hasVehicle = entity.hasVehicle();
    }


    @Override
    public void render(MarchSoldierRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState) {
        matrices.push();
        // Vanilla passenger placement puts this custom player-shaped entity too high on horses.
        // Keep the mounted pose, but lower only the visual model so the rider sits on the saddle.
        if (state.hasVehicle) {
            matrices.translate(0.0D, -0.72D, 0.0D);
        }
        super.render(state, matrices, queue, cameraRenderState);
        matrices.pop();
    }

    @Override
    public Identifier getTexture(MarchSoldierRenderState state) {
        UUID ownerUuid = state.ownerUuid;
        if (ownerUuid == null) {
            return FALLBACK_TEXTURE;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(ownerUuid);
            if (entry != null) {
                SkinTextures textures = entry.getSkinTextures();
                if (textures != null && textures.body() != null) {
                    return textures.body().texturePath();
                }
            }
        }

        SkinTextures fallback = DefaultSkinHelper.getSkinTextures(ownerUuid);
        if (fallback != null && fallback.body() != null) {
            return fallback.body().texturePath();
        }
        return FALLBACK_TEXTURE;
    }
}
