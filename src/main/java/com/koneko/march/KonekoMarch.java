package com.koneko.march;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import com.koneko.march.entity.MarchSoldierEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class KonekoMarch implements ModInitializer {
    public static final String MOD_ID = "konekomarch";
    public static final Identifier MARCH_ID = Identifier.of("koneko", "march");
    public static final RegistryKey<Enchantment> MARCH_KEY = RegistryKey.of(RegistryKeys.ENCHANTMENT, MARCH_ID);
    public static final Identifier JADE_CHARM_ID = Identifier.of("koneko", "jade_charm");
    public static final RegistryKey<Enchantment> JADE_CHARM_KEY = RegistryKey.of(RegistryKeys.ENCHANTMENT, JADE_CHARM_ID);

    public static final Identifier MARCH_SOLDIER_ID = Identifier.of(MOD_ID, "march_soldier");
    public static final RegistryKey<EntityType<?>> MARCH_SOLDIER_KEY = RegistryKey.of(RegistryKeys.ENTITY_TYPE, MARCH_SOLDIER_ID);
    public static final EntityType<MarchSoldierEntity> MARCH_SOLDIER_TYPE = EntityType.Builder
            .create(MarchSoldierEntity::new, SpawnGroup.MISC)
            .dimensions(0.6F, 1.95F)
            .maxTrackingRange(10)
            .trackingTickInterval(2)
            .build(MARCH_SOLDIER_KEY);

    // Natural enchantment max is still configured in JSON, but command-created items may exceed it.
    // March soldier count intentionally uses the actual stored enchantment level.

    private static final List<Legion> LEGIONS = new ArrayList<>();
    private static final Map<UUID, Set<String>> PLAYER_AUTO_TARGET_CONFIGS = new HashMap<>();
    private static final double AUTO_HOSTILE_TARGET_RANGE = 16.0D;
    private static final int MAX_COMMAND_MARCH_SOLDIERS = 450;
    private static final double FORMATION_ANCHOR_STEP = 1.18D;
    private static final double FORMATION_ANCHOR_STEP_MOUNTED = 2.05D;
    private static final float FORMATION_TURN_STEP_DEGREES = 4.5F;
    private static final int FORMATION_FOLLOW_DELAY_TICKS = 0;
    private static final int FORMATION_FOLLOW_DELAY_TICKS_MOUNTED = 0;
    private static final double NAVIGATION_RETARGET_DISTANCE_SQ = 0.36D;
    private static final double NAVIGATION_SPEED_EPSILON = 0.08D;


    @Override
    public void onInitialize() {
        Registry.register(Registries.ENTITY_TYPE, MARCH_SOLDIER_ID, MARCH_SOLDIER_TYPE);
        FabricDefaultAttributeRegistry.register(MARCH_SOLDIER_TYPE, MarchSoldierEntity.createMarchSoldierAttributes());
        PayloadTypeRegistry.playC2S().register(MarchAutoTargetConfigPayload.ID, MarchAutoTargetConfigPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MarchAutoTargetConfigPayload.ID, (payload, context) ->
                PLAYER_AUTO_TARGET_CONFIGS.put(context.player().getUuid(), KonekoMarchConfig.sanitizeAutoTargetIdSet(payload.entityIds())));
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerWorld serverWorld)) {
                return ActionResult.PASS;
            }
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }

            // March soldiers and protected Koneko summons are friendly. Players should not be able to damage them by accident.
            if (isMarchEntity(entity) || isProtectedKonekoEntity(entity)) {
                return ActionResult.FAIL;
            }

            if (!(entity instanceof LivingEntity target)) {
                return ActionResult.PASS;
            }
            if (target instanceof PlayerEntity || isMarchEntity(target) || isProtectedKonekoEntity(target) || !target.isAlive()) {
                return ActionResult.PASS;
            }

            ItemStack weapon = serverPlayer.getMainHandStack();
            int level = getMarchLevel(serverWorld, weapon);
            if (level <= 0) {
                return ActionResult.PASS;
            }

            handleMarchHit(serverWorld, serverPlayer, target, weapon, level);
            return ActionResult.PASS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity.getEntityWorld() instanceof ServerWorld world)) {
                return true;
            }

            Entity attacker = source.getAttacker();
            Entity sourceEntity = source.getSource();

            // Players cannot hurt March soldiers, and March soldiers cannot hurt players.
            if (isMarchEntity(entity) && (attacker instanceof PlayerEntity || sourceEntity instanceof PlayerEntity)) {
                return false;
            }
            if (entity instanceof PlayerEntity && (isMarchEntity(attacker) || isMarchEntity(sourceEntity))) {
                return false;
            }

            // Cross-mod guard bridge: protected Koneko summons, including Koneko Warden Guard wardens,
            // are not valid targets for players or March entities.
            if (isProtectedKonekoEntity(entity)
                    && (attacker instanceof PlayerEntity || sourceEntity instanceof PlayerEntity
                    || isMarchEntity(attacker) || isMarchEntity(sourceEntity))) {
                return false;
            }
            if ((isProtectedKonekoEntity(attacker) || isProtectedKonekoEntity(sourceEntity))
                    && (entity instanceof PlayerEntity || isMarchEntity(entity) || isProtectedKonekoEntity(entity))) {
                return false;
            }

            // A March soldier may only damage its assigned target. This prevents friendly fire and random hostile AI behavior.
            if (isMarchEntity(attacker) && (isProtectedKonekoEntity(entity) || !isAssignedTarget(attacker, entity))) {
                return false;
            }
            if (isMarchEntity(sourceEntity) && (isProtectedKonekoEntity(entity) || !isAssignedTarget(sourceEntity, entity))) {
                return false;
            }

            if (isFriendlyMarchDamage(entity, attacker) || isFriendlyMarchDamage(entity, sourceEntity)) {
                return false;
            }

            LivingEntity livingAttacker = attacker instanceof LivingEntity living ? living :
                    sourceEntity instanceof LivingEntity living ? living : null;
            if (livingAttacker != null) {
                handleRetaliation(world, entity, livingAttacker);
            }
            return true;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("konekomarch")
                        .then(CommandManager.literal("recall")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    if (player != null) {
                                        boolean result = recallLegion(player);
                                        context.getSource().sendFeedback(() -> Text.translatable(result ? "text.koneko.march.recalled" : "text.koneko.march.no_legion"), false);
                                        return result ? 1 : 0;
                                    }
                                    return 0;
                                }))
                        .then(CommandManager.literal("release")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    if (player != null) {
                                        boolean result = releaseLegion(player);
                                        context.getSource().sendFeedback(() -> Text.translatable(result ? "text.koneko.march.released" : "text.koneko.march.no_march_weapon"), false);
                                        return result ? 1 : 0;
                                    }
                                    return 0;
                                }))
                        .then(CommandManager.literal("cleanup")
                                .executes(context -> {
                                    int count = cleanupLoadedMarchEntities(context.getSource().getServer());
                                    context.getSource().sendFeedback(() -> Text.translatable("text.koneko.march.cleaned", count), true);
                                    return count;
                                }))));

        ServerTickEvents.END_WORLD_TICK.register(KonekoMarch::tickWorld);
    }

    private static boolean isMarchEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof MarchSoldierEntity) {
            return true;
        }
        UUID uuid = entity.getUuid();
        for (Legion legion : LEGIONS) {
            for (SoldierLink soldier : legion.soldiers) {
                if (uuid.equals(soldier.entityUuid)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isProtectedKonekoEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        return entity.getCommandTags().contains("koneko_no_friendly_attack")
                || entity.getCommandTags().contains("koneko_invulnerable_guard")
                || entity.getCommandTags().contains("koneko_warden_guard_entity")
                || entity.getCommandTags().contains("kwg_guard_warden");
    }

    private static boolean isAssignedTarget(Entity soldierEntity, Entity target) {
        if (soldierEntity == null || target == null || isProtectedKonekoEntity(target)) {
            return false;
        }
        SoldierLink soldier = findSoldierLink(soldierEntity);
        if (soldier != null) {
            return soldier.targetUuid != null && soldier.targetUuid.equals(target.getUuid());
        }
        if (soldierEntity instanceof MarchSoldierEntity marchSoldier) {
            UUID assigned = marchSoldier.getAssignedTargetUuid();
            return assigned != null && assigned.equals(target.getUuid());
        }
        return false;
    }

    private static Legion findLegionContaining(Entity entity) {
        if (entity == null) {
            return null;
        }
        UUID uuid = entity.getUuid();
        for (Legion legion : LEGIONS) {
            if (legion.ownerUuid.equals(uuid)) {
                return legion;
            }
            for (SoldierLink soldier : legion.soldiers) {
                if (uuid.equals(soldier.entityUuid)) {
                    return legion;
                }
            }
        }
        return null;
    }

    private static SoldierLink findSoldierLink(Entity entity) {
        if (entity == null) {
            return null;
        }
        UUID uuid = entity.getUuid();
        for (Legion legion : LEGIONS) {
            for (SoldierLink soldier : legion.soldiers) {
                if (uuid.equals(soldier.entityUuid)) {
                    return soldier;
                }
            }
        }
        return null;
    }

    private static boolean isFriendlyMarchDamage(LivingEntity victim, Entity attacker) {
        if (attacker == null) {
            return false;
        }
        Legion victimLegion = findLegionContaining(victim);
        Legion attackerLegion = findLegionContaining(attacker);
        return victimLegion != null && victimLegion == attackerLegion;
    }

    private static void handleRetaliation(ServerWorld world, LivingEntity victim, LivingEntity attacker) {
        if (!attacker.isAlive()) {
            return;
        }
        // Keep PvP/team behavior simple: March soldiers do not auto-retaliate against players or protected Koneko summons.
        if (attacker instanceof PlayerEntity || isMarchEntity(attacker) || isProtectedKonekoEntity(attacker)) {
            return;
        }

        Legion legion = findLegionContaining(victim);
        if (legion == null || !legion.dimension.equals(world.getRegistryKey().getValue())) {
            return;
        }

        if (victim.getUuid().equals(legion.ownerUuid)) {
            assignAvailableDefenders(world, legion, attacker, Math.max(1, legion.soldiers.size()));
            return;
        }

        SoldierLink attackedSoldier = findSoldierLink(victim);
        if (attackedSoldier != null) {
            attackedSoldier.targetUuid = attacker.getUuid();
            attackedSoldier.age = 0;
            assignAvailableDefenders(world, legion, attacker, Math.max(1, legion.soldiers.size() / 3));
        }
    }

    private static void assignAvailableDefenders(ServerWorld world, Legion legion, LivingEntity attacker, int count) {
        int assigned = 0;
        // Prefer idle or invalid-target soldiers first.
        for (SoldierLink soldier : legion.soldiers) {
            if (assigned >= count) {
                return;
            }
            if (soldier.targetUuid == null || !(world.getEntity(soldier.targetUuid) instanceof LivingEntity target) || !target.isAlive()) {
                soldier.targetUuid = attacker.getUuid();
                soldier.age = 0;
                assigned++;
            }
        }
        // If nobody was idle, reinforce with the highest-slot soldiers.
        if (assigned <= 0) {
            List<SoldierLink> candidates = new ArrayList<>(legion.soldiers);
            candidates.sort(Comparator.comparingInt((SoldierLink soldier) -> soldier.slot).reversed());
            for (SoldierLink soldier : candidates) {
                if (assigned >= count) {
                    return;
                }
                soldier.targetUuid = attacker.getUuid();
                soldier.age = 0;
                assigned++;
            }
        }
    }

    public static boolean recallLegion(ServerPlayerEntity owner) {
        ServerWorld world = (ServerWorld) owner.getEntityWorld();
        Legion legion = findLegion(world, owner.getUuid());
        if (legion == null) {
            return false;
        }
        discardLegion(world, legion);
        LEGIONS.remove(legion);
        playCommandEffects(world, owner);
        return true;
    }

    public static boolean releaseLegion(ServerPlayerEntity owner) {
        ServerWorld world = (ServerWorld) owner.getEntityWorld();
        ItemStack weapon = owner.getMainHandStack();
        int level = getMarchLevel(world, weapon);
        if (level <= 0) {
            return false;
        }
        Legion legion = findLegion(world, owner.getUuid());
        int desiredCount = getDesiredSoldierCount(level);
        if (legion == null) {
            legion = new Legion(world.getRegistryKey().getValue(), owner.getUuid(), level);
            LEGIONS.add(legion);
            spawnSoldiersToCount(world, owner, weapon, legion, desiredCount);
            playSummonEffects(world, owner);
        } else {
            legion.level = Math.max(legion.level, level);
            legion.age = 0;
            legion.idleTicks = -1;
            spawnSoldiersToCount(world, owner, weapon, legion, Math.max(desiredCount, legion.soldiers.size()));
            for (SoldierLink soldier : legion.soldiers) {
                soldier.targetUuid = null;
                soldier.age = 0;
                soldier.attackCooldown = 0;
                updateSoldierEquipment(world, owner, soldier, weapon);
            }
            playCommandEffects(world, owner);
        }
        return !legion.soldiers.isEmpty();
    }

    private static int getMarchLevel(ServerWorld world, ItemStack stack) {
        Optional<RegistryEntry.Reference<Enchantment>> entry = world.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getEntry(MARCH_ID);
        return entry.map(enchantment -> EnchantmentHelper.getLevel(enchantment, stack)).orElse(0);
    }

    private static int getDesiredSoldierCount(int level) {
        if (level <= 0) {
            return 0;
        }
        long rawCount = (long)level * 2L;
        return (int)Math.max(1L, Math.min((long)MAX_COMMAND_MARCH_SOLDIERS, rawCount));
    }

    public static int getJadeCharmLevel(ServerWorld world, ItemStack stack) {
        if (!stack.isOf(Items.TOTEM_OF_UNDYING)) {
            return 0;
        }
        Optional<RegistryEntry.Reference<Enchantment>> entry = world.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getEntry(JADE_CHARM_ID);
        return entry.map(enchantment -> EnchantmentHelper.getLevel(enchantment, stack)).orElse(0);
    }

    public static int getJadeCharmMaxUses(int level) {
        return switch (Math.max(0, Math.min(5, level))) {
            case 1 -> 3;
            case 2 -> 9;
            case 3 -> 27;
            case 4 -> 81;
            case 5 -> 243;
            default -> 0;
        };
    }

    public static boolean initializeJadeTotem(ServerWorld world, ItemStack stack) {
        int level = getJadeCharmLevel(world, stack);
        int maxUses = getJadeCharmMaxUses(level);
        if (maxUses <= 0) {
            return false;
        }
        stack.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        stack.set(DataComponentTypes.MAX_DAMAGE, maxUses);
        int damage = stack.getOrDefault(DataComponentTypes.DAMAGE, 0);
        if (damage < 0 || damage >= maxUses) {
            damage = 0;
        }
        stack.set(DataComponentTypes.DAMAGE, damage);
        return true;
    }

    public static boolean useJadeTotem(ServerWorld world, LivingEntity entity, ItemStack stack) {
        if (!initializeJadeTotem(world, stack)) {
            return false;
        }
        ServerPlayerEntity player = entity instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;
        stack.damage(1, world, player, item -> {});
        return true;
    }

    private static void handleMarchHit(ServerWorld world, ServerPlayerEntity owner, LivingEntity target, ItemStack weapon, int level) {
        Legion legion = findLegion(world, owner.getUuid());
        int desiredCount = getDesiredSoldierCount(level);

        if (legion == null) {
            legion = new Legion(world.getRegistryKey().getValue(), owner.getUuid(), level);
            LEGIONS.add(legion);
            spawnSoldiersToCount(world, owner, weapon, legion, desiredCount);
            assignAll(world, owner, legion, target, weapon);
            playSummonEffects(world, owner);
            return;
        }

        legion.level = Math.max(legion.level, level);
        legion.idleTicks = -1;
        legion.age = 0;
        spawnSoldiersToCount(world, owner, weapon, legion, Math.max(desiredCount, legion.soldiers.size()));

        if (!hasActiveTarget(world, legion)) {
            // All soldiers had returned to formation. A new attack sends the whole legion out.
            assignAll(world, owner, legion, target, weapon);
            playCommandEffects(world, owner);
            return;
        }

        if (isAlreadyAssigned(legion, target.getUuid())) {
            // Refresh equipment and aggression on the existing detachment.
            for (SoldierLink soldier : legion.soldiers) {
                if (target.getUuid().equals(soldier.targetUuid)) {
                    updateSoldierEquipment(world, owner, soldier, weapon);
                    soldier.age = 0;
                }
            }
            playCommandEffects(world, owner);
            return;
        }

        // Split part of the existing army to the new target.
        int splitCount = Math.max(1, legion.soldiers.size() / 2);
        List<SoldierLink> detachment = chooseDetachment(world, legion, splitCount);
        for (SoldierLink soldier : detachment) {
            soldier.targetUuid = target.getUuid();
            soldier.age = 0;
            updateSoldierEquipment(world, owner, soldier, weapon);
        }
        playCommandEffects(world, owner);
    }

    private static Legion findLegion(ServerWorld world, UUID ownerUuid) {
        Identifier dim = world.getRegistryKey().getValue();
        for (Legion legion : LEGIONS) {
            if (legion.ownerUuid.equals(ownerUuid) && legion.dimension.equals(dim)) {
                return legion;
            }
        }
        return null;
    }

    private static void spawnSoldiersToCount(ServerWorld world, ServerPlayerEntity owner, ItemStack weapon, Legion legion, int desiredCount) {
        boolean mounted = ownerRidingHorse(owner);
        if (!legion.formationInitialized) {
            initializeFormationAnchor(legion, owner);
        } else if (legion.formationMounted != mounted) {
            convertFormationMode(legion, owner, mounted);
        }
        while (legion.soldiers.size() < desiredCount) {
            int slot = legion.soldiers.size();
            SoldierLink soldier = spawnOneSoldier(world, owner, weapon, legion, slot, desiredCount);
            if (soldier == null) {
                return;
            }
            legion.soldiers.add(soldier);
        }
    }

    private static SoldierLink spawnOneSoldier(ServerWorld world, ServerPlayerEntity owner, ItemStack weapon, Legion legion, int slot, int formationSize) {
        boolean mounted = ownerRidingHorse(owner);
        Entity anchor = followAnchor(owner);
        Vec3d desired = formationPoint(legion, slot, formationSize, mounted);
        Vec3d spawnPos = findSafeStandPositionNear(world, desired.x, desired.z, legion.formationAnchorY, mounted ? 1 : 0, 2.5D, mounted);
        if (spawnPos == null) {
            spawnPos = findSafeStandPositionNear(world, anchor.getX(), anchor.getZ(), anchor.getY(), 4, 3.0D, mounted);
        }
        double x = spawnPos != null ? spawnPos.x : anchor.getX();
        double y = spawnPos != null ? spawnPos.y : anchor.getY();
        double z = spawnPos != null ? spawnPos.z : anchor.getZ();

        MarchSoldierEntity ai = MARCH_SOLDIER_TYPE.create(world, SpawnReason.COMMAND);
        if (ai == null) {
            return null;
        }

        ai.refreshPositionAndAngles(x, y, z, owner.getYaw(), 0.0f);
        ai.setOwnerUuid(owner.getUuid());
        ai.setFormationSlot(slot);
        ai.setCustomName(Text.literal("March Soldier"));
        ai.setCustomNameVisible(false);
        ai.setSilent(false);
        ai.addCommandTag("konekomarch_march_ai");
        ai.addCommandTag("konekomarch_march_entity");
        ai.addCommandTag("konekomarch_march_soldier");
        ai.setNoGravity(false);
        ai.fallDistance = 0.0F;

        equipFromOwner(ai, owner, weapon);

        HorseEntity mount = null;
        if (mounted) {
            mount = createMarchMount(world, x, y, z, owner.getYaw(), owner);
            if (mount != null && !world.spawnEntity(mount)) {
                mount = null;
            }
        }

        if (!world.spawnEntity(ai)) {
            if (mount != null) {
                mount.discard();
            }
            return null;
        }

        SoldierLink link = new SoldierLink(ai.getUuid(), slot);
        if (mount != null) {
            ai.startRiding(mount);
            ai.setMountUuid(mount.getUuid());
            link.mountUuid = mount.getUuid();
        }
        return link;
    }

    private static void assignAll(ServerWorld world, ServerPlayerEntity owner, Legion legion, LivingEntity target, ItemStack weapon) {
        for (SoldierLink soldier : legion.soldiers) {
            soldier.targetUuid = target.getUuid();
            soldier.age = 0;
            updateSoldierEquipment(world, owner, soldier, weapon);
        }
    }

    private static boolean hasActiveTarget(ServerWorld world, Legion legion) {
        for (SoldierLink soldier : legion.soldiers) {
            if (soldier.targetUuid == null) {
                continue;
            }
            Entity target = world.getEntity(soldier.targetUuid);
            if (target instanceof LivingEntity living && living.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlreadyAssigned(Legion legion, UUID targetUuid) {
        for (SoldierLink soldier : legion.soldiers) {
            if (targetUuid.equals(soldier.targetUuid)) {
                return true;
            }
        }
        return false;
    }

    private static List<SoldierLink> chooseDetachment(ServerWorld world, Legion legion, int splitCount) {
        List<SoldierLink> result = new ArrayList<>();

        // Prefer idle soldiers first.
        for (SoldierLink soldier : legion.soldiers) {
            if (result.size() >= splitCount) {
                return result;
            }
            if (soldier.targetUuid == null || !(world.getEntity(soldier.targetUuid) instanceof LivingEntity living) || !living.isAlive()) {
                result.add(soldier);
            }
        }

        if (result.size() >= splitCount) {
            return result;
        }

        // Then take the highest-slot soldiers from existing detachments, leaving the front half on the old target.
        List<SoldierLink> candidates = new ArrayList<>(legion.soldiers);
        candidates.sort(Comparator.comparingInt((SoldierLink soldier) -> soldier.slot).reversed());
        for (SoldierLink soldier : candidates) {
            if (result.size() >= splitCount) {
                break;
            }
            if (!result.contains(soldier)) {
                result.add(soldier);
            }
        }
        return result;
    }

    private static void updateSoldierEquipment(ServerWorld world, ServerPlayerEntity owner, SoldierLink soldier, ItemStack weapon) {
        Entity aiEntity = world.getEntity(soldier.entityUuid);
        if (aiEntity instanceof MarchSoldierEntity ai) {
            equipFromOwner(ai, owner, weapon);
        }
    }

    private static void equipFromOwner(MarchSoldierEntity ai, ServerPlayerEntity owner, ItemStack weapon) {
        ai.equipStack(EquipmentSlot.MAINHAND, weapon.copyWithCount(1));
        ai.equipStack(EquipmentSlot.OFFHAND, owner.getOffHandStack().copy());
        ai.equipStack(EquipmentSlot.HEAD, owner.getEquippedStack(EquipmentSlot.HEAD).copy());
        ai.equipStack(EquipmentSlot.CHEST, owner.getEquippedStack(EquipmentSlot.CHEST).copy());
        ai.equipStack(EquipmentSlot.LEGS, owner.getEquippedStack(EquipmentSlot.LEGS).copy());
        ai.equipStack(EquipmentSlot.FEET, owner.getEquippedStack(EquipmentSlot.FEET).copy());

        ai.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0f);
        ai.setEquipmentDropChance(EquipmentSlot.OFFHAND, 0.0f);
        ai.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0f);
        ai.setEquipmentDropChance(EquipmentSlot.CHEST, 0.0f);
        ai.setEquipmentDropChance(EquipmentSlot.LEGS, 0.0f);
        ai.setEquipmentDropChance(EquipmentSlot.FEET, 0.0f);
    }

    private static void tickWorld(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            initializeJadeTotem(world, player.getMainHandStack());
            initializeJadeTotem(world, player.getOffHandStack());
        }

        Iterator<Legion> legionIterator = LEGIONS.iterator();
        while (legionIterator.hasNext()) {
            Legion legion = legionIterator.next();
            if (!legion.dimension.equals(world.getRegistryKey().getValue())) {
                continue;
            }

            legion.age++;
            Entity ownerEntity = world.getEntity(legion.ownerUuid);
            if (!(ownerEntity instanceof ServerPlayerEntity owner) || !owner.isAlive()) {
                discardLegion(world, legion);
                legionIterator.remove();
                continue;
            }

            boolean anyActive = false;
            updateFormationAnchor(legion, owner);
            boolean ownerMounted = ownerRidingHorse(owner);
            // Mounted legions must keep the same square/parade formation model.
            // Do not collapse cavalry into a single trailing line just because the player's horse is on a narrow path.
            boolean compactTerrain = !ownerMounted && isCompactTerrain(world, owner);
            Iterator<SoldierLink> soldierIterator = legion.soldiers.iterator();
            while (soldierIterator.hasNext()) {
                SoldierLink soldier = soldierIterator.next();
                Entity soldierEntity = world.getEntity(soldier.entityUuid);

                if (!(soldierEntity instanceof MarchSoldierEntity ai)) {
                    soldierIterator.remove();
                    continue;
                }

                ai.fallDistance = 0.0F;
                if (soldier.moveCommandCooldown > 0) {
                    soldier.moveCommandCooldown--;
                }
                if (ownerRidingHorse(owner)) {
                    ensureMountedFollower(world, owner, ai, soldier);
                } else {
                    cleanupDetachedMount(world, ai, soldier);
                }

                if (soldier.targetUuid != null) {
                    Entity targetEntity = world.getEntity(soldier.targetUuid);
                    if (targetEntity instanceof LivingEntity target && target.isAlive() && !isFriendlyMarchDamage(target, ai) && !isProtectedKonekoEntity(target)) {
                        anyActive = true;
                        tickActiveSoldier(world, ai, target, soldier);
                        soldier.age++;
                    } else {
                        soldier.targetUuid = null;
                        soldier.age = 0;
                        soldier.attackCooldown = 0;
                        if (tryAutoTargetIdleSoldier(world, owner, ai, soldier)) {
                            anyActive = true;
                        } else {
                            tickIdleSoldier(world, owner, ai, soldier, legion, compactTerrain);
                        }
                    }
                } else {
                    if (tryAutoTargetIdleSoldier(world, owner, ai, soldier)) {
                        anyActive = true;
                    } else {
                        tickIdleSoldier(world, owner, ai, soldier, legion, compactTerrain);
                    }
                }
            }

            if (legion.soldiers.isEmpty()) {
                legionIterator.remove();
                continue;
            }

            // Idle legions no longer despawn by timer. They remain in formation until the player presses
            // the recall key or runs /konekomarch recall.
            if (anyActive) {
                legion.idleTicks = -1;
            } else if (legion.idleTicks < 0) {
                legion.idleTicks = 0;
            }
        }
    }

    private static boolean tryAutoTargetIdleSoldier(ServerWorld world, ServerPlayerEntity owner, MarchSoldierEntity ai, SoldierLink soldier) {
        LivingEntity target = findNearbyAutoHostileTarget(world, owner, ai);
        if (target == null) {
            return false;
        }
        soldier.targetUuid = target.getUuid();
        soldier.age = 0;
        soldier.attackCooldown = 0;
        tickActiveSoldier(world, ai, target, soldier);
        return true;
    }

    private static LivingEntity findNearbyAutoHostileTarget(ServerWorld world, ServerPlayerEntity owner, MarchSoldierEntity ai) {
        Set<String> configuredIds = PLAYER_AUTO_TARGET_CONFIGS.get(owner.getUuid());
        if (configuredIds == null) {
            configuredIds = KonekoMarchConfig.sanitizeAutoTargetIdSet(KonekoMarchConfig.getAutoTargetEntityIds());
        }
        if (configuredIds.isEmpty()) {
            return null;
        }

        double r = AUTO_HOSTILE_TARGET_RANGE;
        Box box = new Box(owner.getX() - r, owner.getY() - r, owner.getZ() - r,
                owner.getX() + r, owner.getY() + r, owner.getZ() + r);

        Set<String> finalConfiguredIds = configuredIds;
        List<LivingEntity> candidates = world.getEntitiesByClass(LivingEntity.class, box, living -> {
            if (!living.isAlive()
                    || living == owner
                    || living instanceof PlayerEntity
                    || isMarchEntity(living)
                    || isProtectedKonekoEntity(living)
                    || living.getVehicle() == ai
                    || owner.squaredDistanceTo(living) > r * r) {
                return false;
            }
            Identifier id = Registries.ENTITY_TYPE.getId(living.getType());
            return KonekoMarchConfig.matchesEntityId(finalConfiguredIds, id);
        });
        if (candidates.isEmpty()) {
            return null;
        }
        Entity host = movementHost(ai);
        candidates.sort(Comparator.comparingDouble(target -> host.squaredDistanceTo(target)));
        return candidates.get(0);
    }

    private static void tickActiveSoldier(ServerWorld world, MarchSoldierEntity ai, LivingEntity target, SoldierLink soldier) {
        soldier.attackCooldown = 0;
        ai.updateFormationMovementState(Double.MAX_VALUE, false);

        if (ai.prefersRangedCombat()) {
            ai.tickRangedCombat(world, target);
            return;
        }

        if (ai.prefersSpearCombat()) {
            ai.tickSpearCombat(world, target);
            return;
        }

        ai.assignTarget(target);
        double distance = movementHost(ai).squaredDistanceTo(target);
        if (distance > 2.7D) {
            startMoving(ai, target, 1.38D);
        }
    }

    private static void tickIdleSoldier(ServerWorld world, ServerPlayerEntity owner, MarchSoldierEntity ai, SoldierLink soldier, Legion legion, boolean compactTerrain) {
        soldier.attackCooldown = 0;
        ai.clearAssignedTarget();

        Entity host = movementHost(ai);
        boolean mounted = ownerRidingHorse(owner) || host instanceof HorseEntity;
        if (rescueIfUnsafe(world, owner, ai, mounted, soldier, legion)) {
            ai.updateFormationMovementState(Double.MAX_VALUE, false);
            return;
        }

        // Both infantry and cavalry target positions are derived from the legion's own formation anchor,
        // not from the player's head/horse position.  This keeps the army as an independent block that
        // waits in place until the player has actually moved away from it.
        Vec3d desired = formationPoint(legion, soldier.slot, legion.soldiers.size(), mounted, compactTerrain && !mounted);
        Vec3d safeFormationPos = findSafeStandPositionNear(world, desired.x, desired.z, legion.formationAnchorY, mounted ? 1 : 0, mounted ? 2.5D : 1.75D, mounted);

        if (safeFormationPos == null) {
            // Mounted soldiers still keep a square/cavalry block when the exact target column is awkward.
            // The fallback searches around the soldier's own slot, never around the player, so cavalry no longer
            // collapses into a line behind the player's horse.
            safeFormationPos = findSafeStandPositionNear(world, desired.x, desired.z, legion.formationAnchorY, mounted ? 3 : 2, mounted ? 3.0D : 2.25D, mounted);
        }

        if (safeFormationPos == null) {
            if (mounted) {
                // Last resort for cavalry: let pathfinding approach the logical slot itself instead of trailing the player.
                double distanceSq = host.squaredDistanceTo(desired.x, desired.y, desired.z);
                ai.updateFormationMovementState(distanceSq, false);
                if (distanceSq > 5.0D) {
                    startMoving(ai, soldier, desired.x, desired.y, desired.z, followSpeed(owner, distanceSq, movementTierBaseSpeed(owner, true), true));
                } else {
                    stopMoving(ai, soldier);
                }
            } else {
                tickSafeFollowSoldier(world, owner, ai, soldier, false, legion);
            }
            return;
        }

        double distanceSq = host.squaredDistanceTo(safeFormationPos.x, safeFormationPos.y, safeFormationPos.z);
        ai.updateFormationMovementState(distanceSq, shouldAllowFormationSprintHop(owner, distanceSq, mounted));
        double teleportDistanceSq = mounted ? 1600.0D : 900.0D;
        double arrivalDistanceSq = mounted ? 3.0D : 1.0D;
        if (distanceSq > teleportDistanceSq) {
            host.refreshPositionAndAngles(safeFormationPos.x, safeFormationPos.y, safeFormationPos.z, host.getYaw(), host.getPitch());
            stopMoving(ai, soldier);
        } else if (distanceSq > arrivalDistanceSq) {
            startMoving(ai, soldier, safeFormationPos.x, safeFormationPos.y, safeFormationPos.z, followSpeed(owner, distanceSq, movementTierBaseSpeed(owner, mounted), mounted));
        } else {
            stopMoving(ai, soldier);
        }
    }

    private static void tickSafeFollowSoldier(ServerWorld world, ServerPlayerEntity owner, MarchSoldierEntity ai, SoldierLink soldier, boolean mounted, Legion legion) {
        Entity host = movementHost(ai);
        Vec3d desired = formationPoint(legion, soldier.slot, Math.max(1, legion.soldiers.size()), mounted, true);
        Vec3d safeFollowPos = findSafeStandPositionNear(world, desired.x, desired.z, legion.formationAnchorY, mounted ? 1 : 0, mounted ? 2.75D : 2.0D, mounted);
        Vec3d anchorPos = new Vec3d(legion.formationAnchorX, legion.formationAnchorY, legion.formationAnchorZ);
        double anchorDistanceSq = host.squaredDistanceTo(anchorPos.x, anchorPos.y, anchorPos.z);

        if (safeFollowPos == null) {
            safeFollowPos = findSafeStandPositionNear(world, desired.x, desired.z, legion.formationAnchorY, mounted ? 3 : 2, mounted ? 3.0D : 2.5D, mounted);
        }

        if (safeFollowPos == null) {
            double desiredDistanceSq = host.squaredDistanceTo(desired.x, desired.y, desired.z);
            ai.updateFormationMovementState(desiredDistanceSq, shouldAllowFormationSprintHop(owner, desiredDistanceSq, mounted));
            if (desiredDistanceSq > (mounted ? 5.0D : 2.0D)) {
                startMoving(ai, soldier, desired.x, desired.y, desired.z, followSpeed(owner, desiredDistanceSq, movementTierBaseSpeed(owner, mounted), mounted));
            } else {
                stopMoving(ai, soldier);
            }
            return;
        }

        double distanceSq = host.squaredDistanceTo(safeFollowPos.x, safeFollowPos.y, safeFollowPos.z);
        ai.updateFormationMovementState(distanceSq, shouldAllowFormationSprintHop(owner, distanceSq, mounted));
        if (anchorDistanceSq > (mounted ? 1600.0D : 900.0D)) {
            host.refreshPositionAndAngles(safeFollowPos.x, safeFollowPos.y, safeFollowPos.z, host.getYaw(), host.getPitch());
            stopMoving(ai, soldier);
        } else if (distanceSq > (mounted ? 3.5D : 1.25D)) {
            startMoving(ai, soldier, safeFollowPos.x, safeFollowPos.y, safeFollowPos.z, followSpeed(owner, distanceSq, movementTierBaseSpeed(owner, mounted), mounted));
        } else {
            stopMoving(ai, soldier);
        }
    }

    private static boolean shouldAllowFormationSprintHop(ServerPlayerEntity owner, double formationDistanceSq, boolean mounted) {
        if (mounted) {
            return false;
        }
        double ownerHorizontalSpeedSq = ownerHorizontalSpeedSq(owner);
        boolean ownerRunJumping = owner.isSprinting() && (!owner.isOnGround() || ownerHorizontalSpeedSq > 0.095D);
        // Sprint-jump is a catch-up tool only. Once a soldier is close enough to its own slot,
        // it keeps formation by running normally instead of bouncing through neighboring slots.
        return ownerRunJumping && formationDistanceSq > 6.25D;
    }

    private static double followSpeed(ServerPlayerEntity owner, double distanceSq, double baseSpeed, boolean mounted) {
        double speed = baseSpeed;
        double ownerHorizontalSpeedSq = ownerHorizontalSpeedSq(owner);

        // Close escort tiers.  Followers should keep formation while the player is moving,
        // not wait far behind and only catch up after the player stops.
        if (ownerHorizontalSpeedSq > 0.095D || (!owner.isOnGround() && owner.isSprinting())) {
            speed += mounted ? 0.78D : 0.62D;
        } else if (owner.isSprinting() || ownerHorizontalSpeedSq > 0.040D) {
            speed += mounted ? 0.52D : 0.42D;
        } else if (ownerHorizontalSpeedSq > 0.008D) {
            speed += mounted ? 0.32D : 0.26D;
        }

        // Catch-up boost: the farther a slot falls behind its moving target, the harder it runs.
        // This is what prevents the long elastic leash shown during normal walking.
        if (distanceSq > 16.0D) {
            speed += mounted ? 0.25D : 0.18D;
        }
        if (distanceSq > 49.0D) {
            speed += mounted ? 0.45D : 0.34D;
        }
        if (distanceSq > 144.0D) {
            speed += mounted ? 0.58D : 0.46D;
        }
        return Math.min(speed, mounted ? 3.05D : 2.20D);
    }

    private static double movementTierBaseSpeed(ServerPlayerEntity owner, boolean mounted) {
        double ownerHorizontalSpeedSq = ownerHorizontalSpeedSq(owner);
        if (mounted) {
            if (ownerHorizontalSpeedSq > 0.095D || (!owner.isOnGround() && owner.isSprinting())) {
                return 1.72D;
            }
            if (owner.isSprinting() || ownerHorizontalSpeedSq > 0.040D) {
                return 1.52D;
            }
            if (ownerHorizontalSpeedSq > 0.008D) {
                return 1.30D;
            }
            return 1.05D;
        }
        if (ownerHorizontalSpeedSq > 0.095D || (!owner.isOnGround() && owner.isSprinting())) {
            return 1.42D;
        }
        if (owner.isSprinting() || ownerHorizontalSpeedSq > 0.040D) {
            return 1.24D;
        }
        if (ownerHorizontalSpeedSq > 0.008D) {
            return 1.06D;
        }
        return 0.82D;
    }

    private static double ownerHorizontalSpeedSq(ServerPlayerEntity owner) {
        Entity anchor = followAnchor(owner);
        Vec3d velocity = anchor.getVelocity();
        return velocity.x * velocity.x + velocity.z * velocity.z;
    }

    private static Entity movementHost(MarchSoldierEntity ai) {
        Entity vehicle = ai.getVehicle();
        return vehicle != null ? vehicle : ai;
    }

    private static void startMoving(MarchSoldierEntity ai, Entity target, double speed) {
        Entity host = movementHost(ai);
        if (host instanceof PathAwareEntity pathAware) {
            pathAware.getNavigation().startMovingTo(target, speed);
        } else {
            ai.getNavigation().startMovingTo(target, speed);
        }
    }

    private static void startMoving(MarchSoldierEntity ai, SoldierLink soldier, double x, double y, double z, double speed) {
        Entity host = movementHost(ai);
        double deltaSq = Double.isNaN(soldier.lastMoveX)
                ? Double.MAX_VALUE
                : squaredDistance(soldier.lastMoveX, soldier.lastMoveY, soldier.lastMoveZ, x, y, z);
        boolean speedChanged = Math.abs(speed - soldier.lastMoveSpeed) > NAVIGATION_SPEED_EPSILON;
        boolean pathExpired = soldier.moveCommandCooldown <= 0;

        if (!pathExpired && deltaSq < NAVIGATION_RETARGET_DISTANCE_SQ && !speedChanged) {
            return;
        }

        startMoving(ai, x, y, z, speed);
        soldier.lastMoveX = x;
        soldier.lastMoveY = y;
        soldier.lastMoveZ = z;
        soldier.lastMoveSpeed = speed;
        soldier.moveCommandCooldown = host instanceof HorseEntity ? 2 : 2;
    }

    private static void startMoving(MarchSoldierEntity ai, double x, double y, double z, double speed) {
        Entity host = movementHost(ai);
        if (host instanceof PathAwareEntity pathAware) {
            pathAware.getNavigation().startMovingTo(x, y, z, speed);
        } else {
            ai.getNavigation().startMovingTo(x, y, z, speed);
        }
    }

    private static void stopMoving(MarchSoldierEntity ai, SoldierLink soldier) {
        stopMoving(ai);
        soldier.lastMoveX = Double.NaN;
        soldier.lastMoveY = Double.NaN;
        soldier.lastMoveZ = Double.NaN;
        soldier.lastMoveSpeed = 0.0D;
        soldier.moveCommandCooldown = 0;
    }

    private static void stopMoving(MarchSoldierEntity ai) {
        Entity host = movementHost(ai);
        if (host instanceof PathAwareEntity pathAware) {
            pathAware.getNavigation().stop();
        }
        ai.getNavigation().stop();
    }

    private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void cleanupDetachedMount(ServerWorld world, MarchSoldierEntity ai, SoldierLink soldier) {
        Entity vehicle = ai.getVehicle();
        if (vehicle != null && vehicle.getCommandTags().contains("konekomarch_march_mount")) {
            ai.stopRiding();
            vehicle.discard();
        }

        if (soldier.mountUuid != null) {
            Entity mount = world.getEntity(soldier.mountUuid);
            if (mount != null) {
                mount.discard();
            }
        }

        soldier.mountUuid = null;
        ai.setMountUuid(null);
    }

    private static void ensureMountedFollower(ServerWorld world, ServerPlayerEntity owner, MarchSoldierEntity ai, SoldierLink soldier) {
        Entity existing = soldier.mountUuid == null ? null : world.getEntity(soldier.mountUuid);
        if (existing != null && ai.getVehicle() == existing) {
            existing.fallDistance = 0.0F;
            if (existing instanceof HorseEntity horse) {
                configureMarchMount(horse, owner);
            }
            return;
        }
        Entity currentVehicle = ai.getVehicle();
        if (existing == null && currentVehicle instanceof HorseEntity horse && currentVehicle.getCommandTags().contains("konekomarch_march_mount")) {
            configureMarchMount(horse, owner);
            soldier.mountUuid = currentVehicle.getUuid();
            ai.setMountUuid(currentVehicle.getUuid());
            return;
        }
        if (existing != null) {
            existing.discard();
        }
        soldier.mountUuid = null;
        ai.setMountUuid(null);

        Entity host = movementHost(ai);
        HorseEntity mount = createMarchMount(world, host.getX(), host.getY(), host.getZ(), owner.getYaw(), owner);
        if (mount == null) {
            return;
        }
        if (!world.spawnEntity(mount)) {
            return;
        }
        ai.startRiding(mount);
        ai.setMountUuid(mount.getUuid());
        soldier.mountUuid = mount.getUuid();
    }

    private static HorseEntity createMarchMount(ServerWorld world, double x, double y, double z, float yaw, ServerPlayerEntity owner) {
        HorseEntity mount = EntityType.HORSE.create(world, SpawnReason.COMMAND);
        if (mount == null) {
            return null;
        }
        mount.refreshPositionAndAngles(x, y, z, yaw, 0.0F);
        mount.setCustomName(Text.literal("March Horse"));
        mount.setCustomNameVisible(false);
        mount.setPersistent();
        configureMarchMount(mount, owner);
        return mount;
    }

    private static void configureMarchMount(HorseEntity mount, ServerPlayerEntity owner) {
        mount.addCommandTag("konekomarch_march_entity");
        mount.addCommandTag("konekomarch_march_mount");
        mount.fallDistance = 0.0F;

        // Keep the mount in the vanilla controllable horse state.  The soldier remains the
        // passenger, while pathfinding commands are issued to the horse entity itself.
        mount.setTame(true);
        mount.setOwner(owner);
        mount.setTemper(100);
        // Minecraft 1.21.11 stores saddles in the dedicated equipment slot.
        // Using equipStack avoids Yarn-version-specific helper names such as isSaddled()/saddle().
        mount.equipStack(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        mount.setEquipmentDropChance(EquipmentSlot.SADDLE, 0.0F);

        if (mount.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED) != null) {
            mount.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED).setBaseValue(0.60D);
        }
        if (mount.getAttributeInstance(EntityAttributes.MAX_HEALTH) != null) {
            mount.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(30.0D);
        }
        mount.setHealth(Math.max(mount.getHealth(), 30.0F));
    }

    private static void initializeFormationAnchor(Legion legion, ServerPlayerEntity owner) {
        Entity anchor = followAnchor(owner);
        legion.formationInitialized = true;
        legion.formationMounted = ownerRidingHorse(owner);
        legion.formationAnchorX = anchor.getX();
        legion.formationAnchorY = anchor.getY();
        legion.formationAnchorZ = anchor.getZ();
        legion.formationYaw = anchor.getYaw();
        legion.formationFollowDelay = 0;
    }

    private static void convertFormationMode(Legion legion, ServerPlayerEntity owner, boolean mounted) {
        // Switching between infantry and cavalry should not snap the formation back onto the player.
        // Keep the current independent anchor and just update the mode and a reasonable Y target.
        Entity anchor = followAnchor(owner);
        legion.formationMounted = mounted;
        legion.formationAnchorY += clamp(anchor.getY() - legion.formationAnchorY, mounted ? -0.80D : -0.45D, mounted ? 0.80D : 0.45D);
        legion.formationFollowDelay = 0;
    }

    private static void updateFormationAnchor(Legion legion, ServerPlayerEntity owner) {
        Entity anchor = followAnchor(owner);
        boolean mounted = ownerRidingHorse(owner);
        if (!legion.formationInitialized) {
            initializeFormationAnchor(legion, owner);
            return;
        }
        if (legion.formationMounted != mounted) {
            convertFormationMode(legion, owner, mounted);
        }

        double dx = anchor.getX() - legion.formationAnchorX;
        double dz = anchor.getZ() - legion.formationAnchorZ;
        double distanceSq = dx * dx + dz * dz;
        double distance = Math.sqrt(distanceSq);
        if (distance < 1.0E-4D) {
            legion.formationFollowDelay = 0;
            return;
        }

        // Close-escort anchor: measure the player's clearance from the outside edge of the formation.
        // Once the player is outside the edge buffer, the virtual block anchor immediately advances
        // toward the player.  The old delayed/slow catch-up model made the army trail far behind while
        // the player was still walking, then slowly crawl in only after the player stopped.
        double edgeClearance = formationEdgeClearance(legion, anchor.getX(), anchor.getZ(), legion.soldiers.size(), mounted);
        double followBuffer = formationFollowBuffer(mounted);
        if (edgeClearance <= followBuffer) {
            legion.formationFollowDelay = 0;
            return;
        }

        Vec3d velocity = anchor.getVelocity();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double velocityAway = (dx * velocity.x + dz * velocity.z) / distance;
        boolean movingTowardFormation = velocityAway < -(mounted ? 0.055D : 0.040D);

        // Keep the player's ability to step into the formation, but do not let this create a long leash.
        // If the player is already more than a tiny margin outside the buffer, the block still follows.
        if (movingTowardFormation && edgeClearance <= followBuffer + (mounted ? 0.35D : 0.25D)) {
            legion.formationFollowDelay = 0;
            return;
        }

        legion.formationFollowDelay++;
        if (legion.formationFollowDelay < (mounted ? FORMATION_FOLLOW_DELAY_TICKS_MOUNTED : FORMATION_FOLLOW_DELAY_TICKS)) {
            return;
        }

        double baseStep = mounted ? FORMATION_ANCHOR_STEP_MOUNTED : FORMATION_ANCHOR_STEP;
        double speedStep = horizontalSpeed * (mounted ? 4.25D : 3.20D);
        double catchUpStep = Math.max(0.0D, edgeClearance - followBuffer) * (mounted ? 0.38D : 0.32D);
        double step = Math.min(edgeClearance - followBuffer, Math.max(baseStep, speedStep + catchUpStep));
        if (step <= 0.0D) {
            return;
        }

        legion.formationAnchorX += dx / distance * step;
        legion.formationAnchorZ += dz / distance * step;
        legion.formationAnchorY += clamp(anchor.getY() - legion.formationAnchorY, mounted ? -0.85D : -0.55D, mounted ? 0.85D : 0.55D);

        float targetYaw = yawFromDirection(dx, dz);
        legion.formationYaw = stepYawTowards(legion.formationYaw, targetYaw, mounted ? FORMATION_TURN_STEP_DEGREES * 1.20F : FORMATION_TURN_STEP_DEGREES);
    }

    private static boolean isCompactTerrain(ServerWorld world, ServerPlayerEntity owner) {
        if (findSafeStandPositionNear(world, owner.getX(), owner.getZ(), owner.getY(), 0, 1.5D) == null) {
            return true;
        }

        double yawRad = Math.toRadians(owner.getYaw());
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        boolean leftSafe = findSafeStandPositionNear(world, owner.getX() - rightX * 1.35D, owner.getZ() - rightZ * 1.35D, owner.getY(), 0, 1.5D) != null;
        boolean rightSafe = findSafeStandPositionNear(world, owner.getX() + rightX * 1.35D, owner.getZ() + rightZ * 1.35D, owner.getY(), 0, 1.5D) != null;
        if (!leftSafe || !rightSafe) {
            return true;
        }

        return countSafeStandCellsAround(world, owner.getX(), owner.getZ(), owner.getY(), 1, 1.5D) < 6;
    }

    private static boolean rescueIfUnsafe(ServerWorld world, ServerPlayerEntity owner, MarchSoldierEntity ai, boolean mounted, SoldierLink soldier, Legion legion) {
        Entity host = movementHost(ai);
        Entity anchor = followAnchor(owner);
        if (!isInsideCollision(world, host) && host.getY() >= anchor.getY() - 4.0D) {
            return false;
        }

        Vec3d desired = formationPoint(legion, soldier.slot, Math.max(1, legion.soldiers.size()), mounted);
        Vec3d rescuePos = findSafeStandPositionNear(world, desired.x, desired.z, legion.formationAnchorY, mounted ? 2 : 3, 3.0D, mounted);
        if (rescuePos == null) {
            rescuePos = findSafeStandPositionNear(world, anchor.getX(), anchor.getZ(), anchor.getY(), mounted ? 5 : 3, 3.0D, mounted);
        }
        if (rescuePos == null) {
            return false;
        }
        host.refreshPositionAndAngles(rescuePos.x, rescuePos.y, rescuePos.z, host.getYaw(), host.getPitch());
        host.fallDistance = 0.0F;
        ai.fallDistance = 0.0F;
        stopMoving(ai, soldier);
        return true;
    }

    private static boolean isInsideCollision(ServerWorld world, Entity entity) {
        return !world.isSpaceEmpty(entity.getBoundingBox());
    }

    private static Vec3d findSafeStandPositionNear(ServerWorld world, double centerX, double centerZ, double preferredY, int horizontalRadius, double maxVerticalDelta) {
        return findSafeStandPositionNear(world, centerX, centerZ, preferredY, horizontalRadius, maxVerticalDelta, false);
    }

    private static Vec3d findSafeStandPositionNear(ServerWorld world, double centerX, double centerZ, double preferredY, int horizontalRadius, double maxVerticalDelta, boolean mounted) {
        int baseX = (int)Math.floor(centerX);
        int baseZ = (int)Math.floor(centerZ);
        for (int radius = 0; radius <= horizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    double x = radius == 0 ? centerX : baseX + dx + 0.5D;
                    double z = radius == 0 ? centerZ : baseZ + dz + 0.5D;
                    Vec3d candidate = findSafeStandPositionAtColumn(world, x, z, preferredY, maxVerticalDelta, mounted);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static Vec3d findSafeStandPositionAtColumn(ServerWorld world, double x, double z, double preferredY, double maxVerticalDelta) {
        return findSafeStandPositionAtColumn(world, x, z, preferredY, maxVerticalDelta, false);
    }

    private static Vec3d findSafeStandPositionAtColumn(ServerWorld world, double x, double z, double preferredY, double maxVerticalDelta, boolean mounted) {
        double top = preferredY + maxVerticalDelta;
        double bottom = Math.max(world.getBottomY() + 0.5D, preferredY - maxVerticalDelta);
        int maxSteps = (int)Math.ceil(maxVerticalDelta * 2.0D);

        for (int step = 0; step <= maxSteps; step++) {
            double delta = step * 0.5D;
            double upY = preferredY + delta;
            if (upY <= top && isSafeStandPosition(world, x, upY, z, mounted)) {
                return new Vec3d(x, upY, z);
            }
            double downY = preferredY - delta;
            if (step != 0 && downY >= bottom && isSafeStandPosition(world, x, downY, z, mounted)) {
                return new Vec3d(x, downY, z);
            }
        }
        return null;
    }

    private static int countSafeStandCellsAround(ServerWorld world, double centerX, double centerZ, double preferredY, int radius, double maxVerticalDelta) {
        int count = 0;
        int baseX = (int)Math.floor(centerX);
        int baseZ = (int)Math.floor(centerZ);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (findSafeStandPositionAtColumn(world, baseX + dx + 0.5D, baseZ + dz + 0.5D, preferredY, maxVerticalDelta) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isSafeStandPosition(ServerWorld world, double x, double y, double z) {
        return isSafeStandPosition(world, x, y, z, false);
    }

    private static boolean isSafeStandPosition(ServerWorld world, double x, double y, double z, boolean mounted) {
        double halfWidth = mounted ? 0.78D : 0.30D;
        double height = mounted ? 2.35D : 1.95D;
        Box body = new Box(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
        Box floorProbe = new Box(x - halfWidth * 0.85D, y - 0.12D, z - halfWidth * 0.85D, x + halfWidth * 0.85D, y - 0.03D, z + halfWidth * 0.85D);
        BlockPos feet = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
        BlockPos head = new BlockPos((int)Math.floor(x), (int)Math.floor(y + 1.0D), (int)Math.floor(z));
        return world.isSpaceEmpty(body)
                && !world.isSpaceEmpty(floorProbe)
                && world.getFluidState(feet).isEmpty()
                && world.getFluidState(head).isEmpty();
    }

    private static boolean ownerRidingHorse(ServerPlayerEntity owner) {
        return owner.getVehicle() instanceof HorseEntity;
    }

    private static Entity followAnchor(ServerPlayerEntity owner) {
        Entity vehicle = owner.getVehicle();
        return vehicle instanceof HorseEntity ? vehicle : owner;
    }

    private static Vec3d entityPos(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    private static Vec3d formationOffset(ServerPlayerEntity owner, int slot, int formationSize) {
        return formationOffset(followAnchor(owner).getYaw(), slot, formationSize, ownerRidingHorse(owner), false);
    }

    private static Vec3d formationPoint(Legion legion, int slot, int formationSize, boolean mounted) {
        return formationPoint(legion, slot, formationSize, mounted, false);
    }

    private static Vec3d formationPoint(Legion legion, int slot, int formationSize, boolean mounted, boolean compactTerrain) {
        Vec3d offset = formationOffset(legion.formationYaw, slot, formationSize, mounted, compactTerrain);
        return new Vec3d(legion.formationAnchorX + offset.x, legion.formationAnchorY, legion.formationAnchorZ + offset.z);
    }

    private static Vec3d formationOffset(float yaw, int slot, int formationSize, boolean mounted, boolean compactTerrain) {
        if (compactTerrain && !mounted) {
            return trailOffset(yaw, slot, false);
        }

        int columns = formationColumns(formationSize, mounted);
        int rows = Math.max(1, (int)Math.ceil((double)Math.max(1, formationSize) / (double)columns));
        int row = slot / columns;
        int column = slot % columns;

        double lateralSpacing = formationLateralSpacing(mounted);
        double rankSpacing = formationRankSpacing(mounted);
        double lateral = (column - (columns - 1) * 0.5D) * lateralSpacing;
        double forward = ((rows - 1) * 0.5D - row) * rankSpacing;

        double yawRad = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double x = rightX * lateral + forwardX * forward;
        double z = rightZ * lateral + forwardZ * forward;
        return new Vec3d(x, 0.0D, z);
    }

    private static Vec3d trailOffset(float yaw, int slot, boolean mounted) {
        double behind = (mounted ? 5.75D : 3.0D) + slot * (mounted ? 3.25D : 1.55D);
        double lateral = 0.0D;

        double yawRad = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double x = rightX * lateral - forwardX * behind;
        double z = rightZ * lateral - forwardZ * behind;
        return new Vec3d(x, 0.0D, z);
    }

    private static int formationColumns(int formationSize) {
        return formationColumns(formationSize, false);
    }

    private static int formationColumns(int formationSize, boolean mounted) {
        int count = Math.max(1, formationSize);
        int columns = Math.max(2, (int)Math.ceil(Math.sqrt(count)));
        if (mounted) {
            columns = Math.max(2, (int)Math.ceil(Math.sqrt(count * 0.75D)));
        }
        return columns;
    }

    private static double formationLeashRadius(int formationSize, boolean mounted) {
        int columns = formationColumns(formationSize, mounted);
        int rows = Math.max(1, (int)Math.ceil((double)Math.max(1, formationSize) / (double)columns));
        double lateralSpacing = formationLateralSpacing(mounted);
        double rankSpacing = formationRankSpacing(mounted);
        double halfWidth = (columns - 1) * lateralSpacing * 0.5D;
        double halfDepth = (rows - 1) * rankSpacing * 0.5D;
        return Math.max(halfWidth, halfDepth) + formationFollowBuffer(mounted);
    }

    private static double formationEdgeClearance(Legion legion, double x, double z, int formationSize, boolean mounted) {
        int columns = formationColumns(formationSize, mounted);
        int rows = Math.max(1, (int)Math.ceil((double)Math.max(1, formationSize) / (double)columns));
        double halfWidth = (columns - 1) * formationLateralSpacing(mounted) * 0.5D;
        double halfDepth = (rows - 1) * formationRankSpacing(mounted) * 0.5D;

        double dx = x - legion.formationAnchorX;
        double dz = z - legion.formationAnchorZ;
        double yawRad = Math.toRadians(legion.formationYaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double localLateral = dx * rightX + dz * rightZ;
        double localForward = dx * forwardX + dz * forwardZ;
        double outsideLateral = Math.max(0.0D, Math.abs(localLateral) - halfWidth);
        double outsideForward = Math.max(0.0D, Math.abs(localForward) - halfDepth);
        return Math.sqrt(outsideLateral * outsideLateral + outsideForward * outsideForward);
    }

    private static double formationFollowBuffer(boolean mounted) {
        // Distance from the nearest outside edge of the formation to the player before the block advances.
        // Keep this small so the formation stays like a close escort instead of lagging far behind.
        return mounted ? 1.25D : 0.85D;
    }

    private static double formationLateralSpacing(boolean mounted) {
        return mounted ? 3.55D : 2.35D;
    }

    private static double formationRankSpacing(boolean mounted) {
        return mounted ? 3.75D : 2.05D;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float yawFromDirection(double dx, double dz) {
        return (float)Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static float stepYawTowards(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        } else if (delta < -maxStep) {
            delta = -maxStep;
        }
        return current + delta;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static void discardLegion(ServerWorld world, Legion legion) {
        for (SoldierLink soldier : legion.soldiers) {
            discard(world.getEntity(soldier.entityUuid));
            if (soldier.mountUuid != null) {
                discard(world.getEntity(soldier.mountUuid));
            }
        }
        legion.soldiers.clear();
    }

    private static void discard(Entity entity) {
        if (entity != null) {
            entity.discard();
        }
    }

    /**
     * Cleans every March entity tracked by the in-memory legion manager.
     *
     * New spawned soldiers also carry command tags, so admins can additionally run:
     * /kill @e[tag=konekomarch_march_entity]
     *
     * Old v4 leftovers that were created before tags existed may still be removed with:
     * /kill @e[type=minecraft:mannequin,name="March Soldier"]
     * /kill @e[type=minecraft:vindicator,name="March Soldier"]
     */
    private static int cleanupLoadedMarchEntities(MinecraftServer server) {
        int count = 0;
        for (Legion legion : LEGIONS) {
            ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, legion.dimension));
            if (world == null) {
                continue;
            }
            for (SoldierLink soldier : legion.soldiers) {
                Entity soldierEntity = world.getEntity(soldier.entityUuid);
                if (soldierEntity != null) {
                    discard(soldierEntity);
                    count++;
                }
                if (soldier.mountUuid != null) {
                    Entity mount = world.getEntity(soldier.mountUuid);
                    if (mount != null) {
                        discard(mount);
                        count++;
                    }
                }
            }
        }
        LEGIONS.clear();

        // Also clear persisted leftovers after a server restart. These are command-tagged in v5+;
        // named fallbacks cover older experimental builds that spawned mannequin/vindicator pairs.
        runServerCommand(server, "execute in minecraft:overworld run kill @e[tag=konekomarch_march_entity]");
        runServerCommand(server, "execute in minecraft:the_nether run kill @e[tag=konekomarch_march_entity]");
        runServerCommand(server, "execute in minecraft:the_end run kill @e[tag=konekomarch_march_entity]");
        runServerCommand(server, "execute in minecraft:overworld run kill @e[type=minecraft:mannequin,name=\"March Soldier\"]");
        runServerCommand(server, "execute in minecraft:the_nether run kill @e[type=minecraft:mannequin,name=\"March Soldier\"]");
        runServerCommand(server, "execute in minecraft:the_end run kill @e[type=minecraft:mannequin,name=\"March Soldier\"]");
        runServerCommand(server, "execute in minecraft:overworld run kill @e[type=minecraft:vindicator,name=\"March Soldier\"]");
        runServerCommand(server, "execute in minecraft:the_nether run kill @e[type=minecraft:vindicator,name=\"March Soldier\"]");
        runServerCommand(server, "execute in minecraft:the_end run kill @e[type=minecraft:vindicator,name=\"March Soldier\"]");
        runServerCommand(server, "execute in minecraft:overworld run kill @e[type=konekomarch:march_soldier]");
        runServerCommand(server, "execute in minecraft:the_nether run kill @e[type=konekomarch:march_soldier]");
        runServerCommand(server, "execute in minecraft:the_end run kill @e[type=konekomarch:march_soldier]");
        runServerCommand(server, "execute in minecraft:overworld run kill @e[tag=konekomarch_march_mount]");
        runServerCommand(server, "execute in minecraft:the_nether run kill @e[tag=konekomarch_march_mount]");
        runServerCommand(server, "execute in minecraft:the_end run kill @e[tag=konekomarch_march_mount]");
        runServerCommand(server, "execute in minecraft:overworld run kill @e[type=minecraft:horse,name=\"March Horse\"]");
        runServerCommand(server, "execute in minecraft:the_nether run kill @e[type=minecraft:horse,name=\"March Horse\"]");
        runServerCommand(server, "execute in minecraft:the_end run kill @e[type=minecraft:horse,name=\"March Horse\"]");
        return count;
    }

    private static void runServerCommand(MinecraftServer server, String command) {
        try {
            server.getCommandManager().getDispatcher().execute(command, server.getCommandSource());
        } catch (Exception ignored) {
            // Cleanup is best-effort; tracked live legions were already discarded above.
        }
    }

    private static void playSummonEffects(ServerWorld world, ServerPlayerEntity owner) {
        world.playSound(null, owner.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, owner.getSoundCategory(), 0.9f, 1.1f);
    }

    private static void playCommandEffects(ServerWorld world, ServerPlayerEntity owner) {
        world.playSound(null, owner.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, owner.getSoundCategory(), 0.35f, 1.3f);
    }

    private static final class Legion {
        final Identifier dimension;
        final UUID ownerUuid;
        final List<SoldierLink> soldiers = new ArrayList<>();
        int level;
        int age;
        int idleTicks = -1;
        boolean formationInitialized;
        boolean formationMounted;
        double formationAnchorX;
        double formationAnchorY;
        double formationAnchorZ;
        float formationYaw;
        int formationFollowDelay;

        Legion(Identifier dimension, UUID ownerUuid, int level) {
            this.dimension = dimension;
            this.ownerUuid = ownerUuid;
            this.level = level;
            this.age = 0;
        }
    }

    private static final class SoldierLink {
        final UUID entityUuid;
        final int slot;
        UUID targetUuid;
        UUID mountUuid;
        int age;
        int attackCooldown;
        int moveCommandCooldown;
        double lastMoveX = Double.NaN;
        double lastMoveY = Double.NaN;
        double lastMoveZ = Double.NaN;
        double lastMoveSpeed;

        SoldierLink(UUID entityUuid, int slot) {
            this.entityUuid = entityUuid;
            this.slot = slot;
            this.age = 0;
            this.attackCooldown = 0;
        }
    }
}
