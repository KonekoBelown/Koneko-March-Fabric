package com.koneko.march.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * A single custom combat entity used by the March enchantment.
 *
 * This entity is not a Vindicator and is not a Mannequin. It uses a biped/player-shaped
 * renderer on the client, while the server entity remains a normal ground PathAwareEntity.
 */
public class MarchSoldierEntity extends PathAwareEntity {
    private static final TrackedData<String> TRACKED_OWNER_UUID = DataTracker.registerData(MarchSoldierEntity.class, TrackedDataHandlerRegistry.STRING);

    private static final Identifier[] SPEAR_IDS = new Identifier[] {
            Identifier.of("minecraft", "wooden_spear"),
            Identifier.of("minecraft", "stone_spear"),
            Identifier.of("minecraft", "copper_spear"),
            Identifier.of("minecraft", "iron_spear"),
            Identifier.of("minecraft", "golden_spear"),
            Identifier.of("minecraft", "diamond_spear"),
            Identifier.of("minecraft", "netherite_spear")
    };

    private UUID ownerUuid;
    private UUID assignedTargetUuid;
    private UUID mountUuid;
    private int formationSlot;
    private int rangedCooldown;
    private double formationDistanceSq = Double.MAX_VALUE;
    private boolean sprintHopAllowed;

    public MarchSoldierEntity(EntityType<? extends MarchSoldierEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createMarchSoldierAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 20.0D)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.35D)
                .add(EntityAttributes.ATTACK_DAMAGE, 2.0D)
                .add(EntityAttributes.FOLLOW_RANGE, 32.0D)
                .add(EntityAttributes.ARMOR, 0.0D)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, 0.0D);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(TRACKED_OWNER_UUID, "");
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.15D, true));
        this.goalSelector.add(2, new LookAtEntityGoal(this, LivingEntity.class, 16.0F));
        // No normal target goals. KonekoMarch assigns targets explicitly.
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_PLAYER_DEATH;
    }

    @Override
    public void tick() {
        super.tick();
        this.addCommandTag("konekomarch_march_entity");
        this.addCommandTag("konekomarch_march_ai");
        this.fallDistance = 0.0F;

        if (ownerUuid == null) {
            getTrackedOwnerUuid().ifPresent(uuid -> this.ownerUuid = uuid);
        }

        if (ownerUuid == null) {
            if (this.getEntityWorld() instanceof ServerWorld) {
                discardMountIfPresent();
                this.discard();
            }
            return;
        }

        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            Entity owner = serverWorld.getEntity(ownerUuid);
            if (!(owner instanceof PlayerEntity player) || !player.isAlive()) {
                discardMountIfPresent();
                this.discard();
                return;
            }

            if (mountUuid != null) {
                Entity mount = serverWorld.getEntity(mountUuid);
                if (mount != null) {
                    mount.addCommandTag("konekomarch_march_entity");
                    mount.addCommandTag("konekomarch_march_mount");
                    // If the soldier was forced off the mount, remove the horse too. This avoids leaked mounts.
                    if (this.getVehicle() != mount) {
                        mount.discard();
                        mountUuid = null;
                    }
                } else {
                    mountUuid = null;
                }
            }

            if (assignedTargetUuid != null) {
                Entity target = serverWorld.getEntity(assignedTargetUuid);
                if (target instanceof LivingEntity living && living.isAlive()) {
                    if (this.getTarget() != living && !prefersRangedCombat() && !prefersSpearCombat()) {
                        this.setTarget(living);
                    }
                } else {
                    clearAssignedTarget();
                }
            } else if (this.getTarget() != null) {
                this.setTarget(null);
            }

            tickRunningHop(player);
        }
    }

    private void tickRunningHop(PlayerEntity owner) {
        if (this.getVehicle() != null || !this.isOnGround() || this.isTouchingWater() || this.isInLava()) {
            return;
        }
        if (!this.sprintHopAllowed || this.formationDistanceSq < 6.25D) {
            return;
        }

        Entity ownerMovementHost = owner.getVehicle() != null ? owner.getVehicle() : owner;
        Vec3d ownerVelocity = ownerMovementHost.getVelocity();
        double ownerHorizontalSpeedSq = ownerVelocity.x * ownerVelocity.x + ownerVelocity.z * ownerVelocity.z;
        boolean ownerRunJumping = owner.isSprinting() && (!owner.isOnGround() || ownerHorizontalSpeedSq > 0.095D);
        if (!ownerRunJumping) {
            return;
        }

        Vec3d velocity = this.getVelocity();
        double horizontalSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;
        if (horizontalSpeedSq < 0.025D) {
            return;
        }

        // Only high-speed player movement enables sprint-jump movement. Walking and ordinary running
        // use navigation speed tiers only, so the formation no longer bounces constantly.
        if (this.age % 7 == Math.floorMod(this.formationSlot, 4)) {
            double horizontalSpeed = Math.sqrt(horizontalSpeedSq);
            double maxHorizontal = 0.48D;
            double boost = Math.min(1.16D, maxHorizontal / Math.max(0.001D, horizontalSpeed));
            double boostedX = velocity.x * boost;
            double boostedZ = velocity.z * boost;
            this.setVelocity(boostedX, Math.max(velocity.y, 0.36D), boostedZ);
            this.velocityDirty = true;
        }
    }

    private void discardMountIfPresent() {
        if (this.getEntityWorld() instanceof ServerWorld serverWorld && mountUuid != null) {
            Entity mount = serverWorld.getEntity(mountUuid);
            if (mount != null) {
                mount.discard();
            }
            mountUuid = null;
        }
        Entity vehicle = this.getVehicle();
        if (vehicle != null && vehicle.getCommandTags().contains("konekomarch_march_mount")) {
            vehicle.discard();
        }
    }

    public boolean prefersRangedCombat() {
        return isRangedWeapon(this.getMainHandStack());
    }

    public boolean prefersSpearCombat() {
        return isSpearWeapon(this.getMainHandStack());
    }

    private boolean isRangedWeapon(ItemStack stack) {
        return stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.TRIDENT);
    }

    private boolean isSpearWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Identifier id : SPEAR_IDS) {
            if (stack.isOf(Registries.ITEM.get(id))) {
                return true;
            }
        }
        return false;
    }

    private Entity movementHost() {
        Entity vehicle = this.getVehicle();
        return vehicle != null ? vehicle : this;
    }

    private void startMovingToEntity(Entity target, double speed) {
        Entity host = movementHost();
        if (host instanceof PathAwareEntity pathAware) {
            pathAware.getNavigation().startMovingTo(target, speed);
        } else {
            this.getNavigation().startMovingTo(target, speed);
        }
    }

    private void stopMovingHost() {
        Entity host = movementHost();
        if (host instanceof PathAwareEntity pathAware) {
            pathAware.getNavigation().stop();
        }
        this.getNavigation().stop();
    }

    private double mountedDistanceSq(Entity target) {
        return movementHost().squaredDistanceTo(target);
    }

    public void tickSpearCombat(ServerWorld world, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            clearAssignedTarget();
            return;
        }
        this.assignedTargetUuid = target.getUuid();
        this.setTarget(null);
        this.getLookControl().lookAt(target, 30.0F, 30.0F);

        double distanceSq = mountedDistanceSq(target);
        if (distanceSq > 12.25D) { // roughly 3.5 blocks
            startMovingToEntity(target, 1.34D);
            return;
        }
        stopMovingHost();
        if (rangedCooldown > 0) {
            rangedCooldown--;
            return;
        }
        if (this.canSee(target)) {
            this.tryAttack(world, target);
            rangedCooldown = 18;
        }
    }

    public void tickRangedCombat(ServerWorld world, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            clearAssignedTarget();
            return;
        }

        this.assignedTargetUuid = target.getUuid();
        this.setTarget(null); // Avoid the vanilla melee goal pulling ranged soldiers into melee.
        this.getLookControl().lookAt(target, 30.0F, 30.0F);

        ItemStack weapon = this.getMainHandStack();
        double preferredRange = weapon.isOf(Items.TRIDENT) ? 64.0D : 144.0D; // squared: 8 / 12 blocks
        double tooFar = weapon.isOf(Items.TRIDENT) ? 100.0D : 196.0D;       // squared: 10 / 14 blocks
        double distanceSq = mountedDistanceSq(target);

        if (distanceSq > tooFar) {
            startMovingToEntity(target, 1.28D);
        } else {
            stopMovingHost();
        }

        if (rangedCooldown > 0) {
            rangedCooldown--;
            return;
        }

        if (distanceSq <= preferredRange || this.canSee(target)) {
            if (weapon.isOf(Items.TRIDENT)) {
                throwTrident(world, target, weapon);
                rangedCooldown = 35;
            } else if (weapon.isOf(Items.CROSSBOW)) {
                shootArrowLike(world, target, weapon, 3.15F, 5.0F, 1.2F);
                rangedCooldown = 30;
            } else if (weapon.isOf(Items.BOW)) {
                shootArrowLike(world, target, weapon, 2.2F, 8.0F, 1.0F);
                rangedCooldown = 24;
            }
        }
    }

    private void shootArrowLike(ServerWorld world, LivingEntity target, ItemStack weapon, float power, float divergence, float damageModifier) {
        ItemStack projectileStack = this.getProjectileType(weapon);
        if (projectileStack.isEmpty()) {
            projectileStack = new ItemStack(Items.ARROW);
        }

        PersistentProjectileEntity projectile = ProjectileUtil.createArrowProjectile(this, projectileStack, damageModifier, weapon);
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = (target.getY() + target.getHeight() * 0.60D) - projectile.getY() + horizontal * 0.12D;
        projectile.setVelocity(dx, dy, dz, power, divergence);
        world.spawnEntity(projectile);
    }

    private void throwTrident(ServerWorld world, LivingEntity target, ItemStack weapon) {
        TridentEntity trident = new TridentEntity(EntityType.TRIDENT, world);
        trident.setOwner(this);
        trident.setPosition(this.getX(), this.getEyeY() - 0.10D, this.getZ());

        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = (target.getY() + target.getHeight() * 0.55D) - trident.getY() + horizontal * 0.08D;
        ProjectileEntity.spawnWithVelocity(trident, world, weapon.copyWithCount(1), dx, dy, dz, 2.5F, 1.0F);
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        Entity attacker = source.getAttacker();
        Entity sourceEntity = source.getSource();

        if (isFriendly(attacker) || isFriendly(sourceEntity)) {
            return false;
        }
        boolean result = super.damage(world, source, amount);
        LivingEntity retaliationTarget = attacker instanceof LivingEntity living ? living
                : sourceEntity instanceof LivingEntity living ? living
                : null;
        if (result && retaliationTarget != null && !(retaliationTarget instanceof PlayerEntity) && !isFriendly(retaliationTarget)) {
            assignTarget(retaliationTarget);
        }
        return result;
    }

    private boolean isFriendly(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof MarchSoldierEntity other) {
            return ownerUuid != null && ownerUuid.equals(other.ownerUuid);
        }
        return ownerUuid != null && ownerUuid.equals(entity.getUuid());
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public Optional<UUID> getTrackedOwnerUuid() {
        String raw = this.dataTracker.get(TRACKED_OWNER_UUID);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        this.dataTracker.set(TRACKED_OWNER_UUID, ownerUuid == null ? "" : ownerUuid.toString());
    }

    public UUID getAssignedTargetUuid() {
        return assignedTargetUuid;
    }

    public void assignTarget(LivingEntity target) {
        if (target == null) {
            clearAssignedTarget();
            return;
        }
        this.assignedTargetUuid = target.getUuid();
        if (!prefersRangedCombat() && !prefersSpearCombat()) {
            this.setTarget(target);
        }
    }

    public void clearAssignedTarget() {
        this.assignedTargetUuid = null;
        this.setTarget(null);
        stopMovingHost();
    }

    public void updateFormationMovementState(double formationDistanceSq, boolean sprintHopAllowed) {
        this.formationDistanceSq = formationDistanceSq;
        this.sprintHopAllowed = sprintHopAllowed;
    }

    public int getFormationSlot() {
        return formationSlot;
    }

    public void setFormationSlot(int formationSlot) {
        this.formationSlot = formationSlot;
    }

    public UUID getMountUuid() {
        return mountUuid;
    }

    public void setMountUuid(UUID mountUuid) {
        this.mountUuid = mountUuid;
    }
}
