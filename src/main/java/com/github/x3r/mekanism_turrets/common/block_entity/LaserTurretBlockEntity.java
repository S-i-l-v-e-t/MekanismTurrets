package com.github.x3r.mekanism_turrets.common.block_entity;

import com.github.x3r.mekanism_turrets.MekanismTurretsConfig;
import com.github.x3r.mekanism_turrets.common.entity.LaserEntity;
import com.github.x3r.mekanism_turrets.common.registry.SoundRegistry;
import com.github.x3r.mekanism_turrets.common.scheduler.Scheduler;
import mekanism.api.*;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.frequency.FrequencyType;
import mekanism.common.lib.security.SecurityFrequency;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.upgrade.IUpgradeData;
import mekanism.common.util.NBTUtils;
import mekanism.common.util.SecurityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.network.SerializableDataTicket;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import org.valkyrienskies.mod.common.VSGameUtilsKt;


public class LaserTurretBlockEntity extends TileEntityMekanism implements GeoBlockEntity {

    @WrappingComputerMethod(wrapper = SpecialComputerMethodWrapper.ComputerIInventorySlotWrapper.class, methodNames = "getEnergyItem", docPlaceholder = "energy slot")
    EnergyInventorySlot energySlot;
    public static SerializableDataTicket<Boolean> HAS_TARGET;
    public static SerializableDataTicket<Double> TARGET_POS_X;
    public static SerializableDataTicket<Double> TARGET_POS_Y;
    public static SerializableDataTicket<Double> TARGET_POS_Z;
    private static final RawAnimation SHOOT_ANIMATION = RawAnimation.begin().then("shoot", Animation.LoopType.PLAY_ONCE);
    private final AABB targetBox = AABB.ofSize(getBlockPos().getCenter(), getTier().getRange()*2, getTier().getRange()*2, getTier().getRange()*2);
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private LaserTurretTier tier;
    private MachineEnergyContainer<LaserTurretBlockEntity> energyContainer;
    private boolean targetsHostile = true;
    private boolean targetsPassive = false;
    private boolean targetsPlayers = false;
    private boolean targetsTrusted = true;
    private @Nullable LivingEntity target;
    private List<Vec3> targetPreVelocity = new ArrayList<Vec3>();
    private Vec3 lastSelfWorldPos = null;
    private Vec3 lastTargetWorldPos = null;
    public float xRot0 = 0;
    public float yRot0 = 0;
    private int coolDown = 0;
    private int idleTicks = 0;

    public LaserTurretBlockEntity(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected @Nullable IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSide(this::getDirection);
        builder.addContainer(energyContainer = MachineEnergyContainer.input(this, listener));

        return builder.build();
    }

    public MachineEnergyContainer<LaserTurretBlockEntity> getEnergyContainer() {
        return energyContainer;
    }

    @Override
    protected @Nullable IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSide(this::getDirection);
        builder.addSlot(energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener, 143, 35), RelativeSide.BACK);
        return builder.build();
    }

    public LaserTurretTier getTier() {
        return tier;
    }

    public boolean targetsHostile() {
        return targetsHostile;
    }

    public void setTargetsHostile(boolean targetsHostile) {
        this.targetsHostile = targetsHostile;
    }

    public boolean targetsPassive() {
        return targetsPassive;
    }

    public void setTargetsPassive(boolean targetsPassive) {
        this.targetsPassive = targetsPassive;
    }

    public boolean targetsPlayers() {
        return targetsPlayers;
    }

    public void setTargetsPlayers(boolean targetsPlayers) {
        this.targetsPlayers = targetsPlayers;
    }

    public boolean targetsTrusted() {
        return targetsTrusted;
    }

    public void setTargetsTrusted(boolean targetsTrusted) {
        this.targetsTrusted = targetsTrusted;
    }

    private double getCoolDownMultiplier(int x){
        //1.00, 1.81, 2.72, 4.24, 6.85, 9.45, 10.97, 11.88, 12.69
        //https://www.geogebra.org/calculator/dzemv5sh
        double A = 11.7 / 4.4;
        double COEFFICIENT = (5.0 / 2.0) * Math.sqrt(Math.PI) * (1/(Math.sqrt(2) * Math.PI));
        double erfPart = COEFFICIENT * Erf.erf(0.5 * Math.sqrt(2) * (x - 4));
        double linearPart = (3.0 / 10.0) * x + 1;
        return (A * (erfPart + linearPart))+1;
    }
    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        energySlot.fillContainerOrConvert();
        tryInvalidateTarget();
        tryFindTarget();
        energyContainer.setEnergyPerTick(FloatingLong.create(laserShotEnergy()));
        if (target != null) {
            // 维持 20 tick 的滑动窗口
            if (targetPreVelocity.size() >= 20) targetPreVelocity.remove(0);
            Vec3 currentTargetWorldPos = target.position();

            if (lastTargetWorldPos != null) {
                targetPreVelocity.add(currentTargetWorldPos.subtract(lastTargetWorldPos));
            } else {
                targetPreVelocity.add(Vec3.ZERO);
            }
            lastTargetWorldPos = currentTargetWorldPos;


            Vec3 targetPos = getShootLocation(target,targetPreVelocity,level,worldPosition);
            setAnimData(TARGET_POS_X, targetPos.x);
            setAnimData(TARGET_POS_Y, targetPos.y);
            setAnimData(TARGET_POS_Z, targetPos.z);
            setAnimData(HAS_TARGET, target != null);
            if(coolDown == 0) {
                //v1 : x part following
                //float x = 10*((float) upgradeComponent.getUpgrades(Upgrade.SPEED) /8);
                //v1 : make the speed upgrade function properly as the tip with function \frac{(x+1)\ln{x+1}-x}{16} 's curve
                //v2 : use mooore complex function and get mooore effect with low count upgrades
                coolDown = (int) Math.max(0,tier.getCooldown() / getCoolDownMultiplier(upgradeComponent.getUpgrades(Upgrade.SPEED)));
                //coolDown = Math.max(0,(int) Math.floor((tier.getCooldown()*(1-(0.9*(((x+1)*Math.log(x+1)-x)/16))))));
                //coolDown = Math.max(0, tier.getCooldown()-(2*upgradeComponent.getUpgrades(Upgrade.SPEED)));
                if(energyContainer.getEnergy().greaterOrEqual(FloatingLong.create(laserShotEnergy()))) {
                    shootLaser();
                    if(tier.equals(LaserTurretTier.ULTIMATE)) {
                        Scheduler.schedule(this::shootLaser, 10);
                    }
                }
            } else {
                coolDown--;
            }
        }
    }
    private static Vec3 getRecentAcceleration(List<Vec3> velocities) {
        if (velocities.size() < 2) return Vec3.ZERO;

        Vec3 totalAcc = Vec3.ZERO;
        int count = 0;

        int startIndex = Math.max(1, velocities.size() - 4);

        for (int i = startIndex; i < velocities.size(); i++) {
            Vec3 vCurrent = velocities.get(i);
            Vec3 vPrev = velocities.get(i - 1);
            totalAcc = totalAcc.add(vCurrent.subtract(vPrev));
            count++;
        }

        return totalAcc.scale(1.0 / count);
    }

    private static Vec3 getShootLocation(LivingEntity entity, List<Vec3> preV, Level lv, BlockPos shooterPos) {
        // Target Avg Speed Calculation
        Vec3 avgTargetVel = Vec3.ZERO;
        if (!preV.isEmpty()) {
            for (Vec3 v : preV) {
                avgTargetVel = avgTargetVel.add(v);
            }
            avgTargetVel = avgTargetVel.scale(1.0 / preV.size());
        }

        Vec3 targetRecentAcc = getRecentAcceleration(preV);
        Vec3 currentWorldPos = entity.position();
        Vec3 shooterWorldPos;
        try {
            LoadedShip ship = VSGameUtilsKt.getShipObjectManagingPos(lv, shooterPos);
            if (ship != null) {
                org.joml.Vector3d localPos = new org.joml.Vector3d(shooterPos.getX() + 0.5, shooterPos.getY() + 0.5, shooterPos.getZ() + 0.5);
                org.joml.Vector3d worldPosJOML = ship.getTransform().getShipToWorld().transformPosition(localPos);
                shooterWorldPos = new Vec3(worldPosJOML.x, worldPosJOML.y, worldPosJOML.z);
            } else {
                shooterWorldPos = Vec3.atCenterOf(shooterPos);
            }
        } catch (NoClassDefFoundError e) {
            shooterWorldPos = Vec3.atCenterOf(shooterPos);
        }

        double laserSpeed = 3.0;
        int maxTicks = 20;
        for (int t = 1; t <= maxTicks; t++) {

            if (t <= 2) {
                avgTargetVel = avgTargetVel.add(targetRecentAcc);
            }

            currentWorldPos = currentWorldPos.add(avgTargetVel);

            double distanceToTarget = shooterWorldPos.distanceTo(currentWorldPos);
            if (distanceToTarget <= laserSpeed * t || t == maxTicks) {
                break;
            }
        }

        Vec3 finalWorldPos = currentWorldPos.add(0, entity.getBbHeight() * 0.75, -0.07);

        try {
            LoadedShip ship = VSGameUtilsKt.getShipObjectManagingPos(lv, shooterPos);
            if (ship != null) {
                return VectorConversionsMCKt.toMinecraft(
                        ship.getTransform().getWorldToShip().transformPosition(
                                VectorConversionsMCKt.toJOML(finalWorldPos)
                        )
                );
            }
        } catch (NoClassDefFoundError ignored) {
        }

        return finalWorldPos;
    }

    private void shootLaser() {
        if(target != null) { // Needed for scheduled shot
            int mufflerCount = getComponent().getUpgrades(Upgrade.MUFFLING);
            float volume = 1.0F - (mufflerCount / (float) Upgrade.MUFFLING.getMax());
            level.playSound(null, getBlockPos(), SoundRegistry.TURRET_SHOOT.get(), SoundSource.BLOCKS, volume, 1.0F);

            triggerAnim("controller", "shoot");

            Vec3 center = getBlockPos().getCenter();
            Vec3 targetPos = getShootLocation(target,targetPreVelocity,level,worldPosition);
            LaserEntity laser;
            if(this.getOwnerUUID() != null){
                laser = new LaserEntity(level, center.add(0, -0.15, 0), tier.getDamage(),((ServerLevel)this.level).getServer().getPlayerList().getPlayer(getOwnerUUID()));
            }
            else{
                laser = new LaserEntity(level, center.add(0, -0.15, 0), tier.getDamage(),null);
            }
            Vec3 AbsoluteVelocity = center.vectorTo(targetPos).normalize().scale(3.0F);
            try {
                LoadedShip ship = VSGameUtilsKt.getShipObjectManagingPos(this.level, this.getBlockPos());
                if (ship != null) {
                    org.joml.Vector3d wDir = ship.getTransform().getShipToWorld().transformDirection(
                            VectorConversionsMCKt.toJOML(AbsoluteVelocity), new org.joml.Vector3d()
                    );
                    AbsoluteVelocity = VectorConversionsMCKt.toMinecraft(wDir);
                }
            } catch (NoClassDefFoundError ignored) {}
            laser.shootWithAbsoluteVelocity(AbsoluteVelocity);

            level.addFreshEntity(laser);
            energyContainer.extract(FloatingLong.create(laserShotEnergy()), Action.EXECUTE, AutomationType.INTERNAL);
        }
    }


    private int laserShotEnergy() {
        return 1000*(tier.ordinal()+1)*Mth.square(upgradeComponent.getUpgrades(Upgrade.SPEED)+1);
    }

    public void tryInvalidateTarget() {
        if(!isValidTarget(target)) {
            setAnimData(HAS_TARGET, false);
            target = null;
            targetPreVelocity.clear();
            lastTargetWorldPos = null;
        }
    }

    private void tryFindTarget() {
        if(idleTicks-- > 0) {
            return;
        }
        if(target == null && (level.getGameTime()+this.hashCode()) % 3 == 0) {
            Optional<LivingEntity> optional = level.getEntitiesOfClass(LivingEntity.class, targetBox, this::isValidTarget).stream()
                    .min((o1, o2) -> Double.compare(
                            o1.distanceToSqr(this.getBlockPos().getCenter()),
                            o2.distanceToSqr(this.getBlockPos().getCenter())
                    ));
            if(optional.isPresent()) {
                this.target = optional.get();
                setAnimData(HAS_TARGET, true);
            } else {
                idleTicks = 20 * 4;
            }
        }
    }

    private boolean isValidTarget(LivingEntity e) {
        if(e == null) {
            return false;
        }
        if(!e.canBeSeenAsEnemy()) {
            return false;
        }
        if(e.distanceToSqr(this.getBlockPos().getCenter()) > getTier().getRange()*getTier().getRange()) {
            return false;
        }
        if(MekanismTurretsConfig.blacklistedEntities == null) {
            return false;
        }
        if(MekanismTurretsConfig.blacklistedEntities.get().stream().map(s -> ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(s))).anyMatch(entityType -> e.getType().equals(entityType))) {
            return false;
        }
        if(!turretFlagsAllowTargeting(e)) {
            return false;
        }
        if(!canSeeTarget(e)) {
            return false;
        }
        return true;
    }

    private boolean turretFlagsAllowTargeting(LivingEntity e) {
        MobCategory category = e.getType().getCategory();
        if(this.targetsHostile && !category.isFriendly()) {
            return true;
        }
        if(this.targetsPassive && category.isFriendly() && !category.equals(MobCategory.MISC)) {
            return true;
        }
        UUID owner = SecurityUtils.get().getOwnerUUID(this);
        if(this.targetsPlayers && e instanceof Player player) {
            if(!player.getUUID().equals(owner)) {
                if(this.targetsTrusted) {
                    // turret targets ALL players
                    return true;
                } else {
                    SecurityFrequency frequency = FrequencyType.SECURITY.getManager(null).getFrequency(owner);
                    if(frequency == null) {
                        // if frequency is null, the owner has not "trusted" any players, return true
                        return true;
                    }
                    if(!frequency.getTrustedUUIDs().contains(player.getUUID())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean canSeeTarget(LivingEntity e) {
        Vec3 center;
        try{
            if (VSGameUtilsKt.getShipObjectManagingPos(level,getBlockPos())!=null){
                LoadedShip ship = VSGameUtilsKt.getShipObjectManagingPos(level,worldPosition);
                center = VectorConversionsMCKt.toMinecraft(ship.getTransform().getShipToWorld().transformPosition(VectorConversionsMCKt.toJOML(getBlockPos().getCenter())));
            }
            else{center = getBlockPos().getCenter();}
        }
        catch (NoClassDefFoundError err){
            center = getBlockPos().getCenter();
        }

        Vec3 targetPos = e.position().add(0, (e.getBbHeight()*0.75), 0);
        Vec3 lookVec = center.vectorTo(targetPos).normalize().scale(0.75F);
        ClipContext ctx = new ClipContext(center.add(lookVec), targetPos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, null);
        return level.clip(ctx).getType().equals(HitResult.Type.MISS);
    }

    @Override
    public void parseUpgradeData(@NotNull IUpgradeData data) {
        if(data instanceof LaserTurretUpgradeData upgradeData) {
            this.targetsHostile = upgradeData.targetsHostile();
            this.targetsPassive = upgradeData.targetsPassive();
            this.targetsPlayers = upgradeData.targetsPlayers();
            this.targetsTrusted = upgradeData.targetsTrusted();
            for (ITileComponent component : getComponents()) {
                component.read(upgradeData.components());
            }
        } else {
            super.parseUpgradeData(data);
        }
    }

    @Override
    public @Nullable IUpgradeData getUpgradeData() {
        return new LaserTurretUpgradeData(targetsHostile, targetsPassive, targetsPlayers, targetsTrusted, getComponents());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        markUpdated();
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("targetsHostile", targetsHostile);
        tag.putBoolean("targetsPassive", targetsPassive);
        tag.putBoolean("targetsPlayers", targetsPlayers);
        tag.putBoolean("targetsTrusted", targetsTrusted);
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        NBTUtils.setBooleanIfPresent(tag, "targetsHostile", value -> targetsHostile = value);
        NBTUtils.setBooleanIfPresent(tag, "targetsPassive", value -> targetsPassive = value);
        NBTUtils.setBooleanIfPresent(tag, "targetsPlayers", value -> targetsPlayers = value);
        NBTUtils.setBooleanIfPresent(tag, "targetsTrusted", value -> targetsTrusted = value);
    }

    @Override
    public @NotNull CompoundTag getReducedUpdateTag() {
        CompoundTag tag = super.getReducedUpdateTag();
        tag.putBoolean("targetsHostile", targetsHostile);
        tag.putBoolean("targetsPassive", targetsPassive);
        tag.putBoolean("targetsPlayers", targetsPlayers);
        tag.putBoolean("targetsTrusted", targetsTrusted);
        return tag;
    }

    @Override
    public void handleUpdateTag(@NotNull CompoundTag tag) {
        super.handleUpdateTag(tag);
        NBTUtils.setBooleanIfPresent(tag, "targetsHostile", value -> targetsHostile = value);
        NBTUtils.setBooleanIfPresent(tag, "targetsPassive", value -> targetsPassive = value);
        NBTUtils.setBooleanIfPresent(tag, "targetsPlayers", value -> targetsPlayers = value);
        NBTUtils.setBooleanIfPresent(tag, "targetsTrusted", value -> targetsTrusted = value);
    }

    public void markUpdated() {
        this.setChanged();
        this.getLevel().sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        if(!this.level.isClientSide()) sendUpdatePacket();
    }

    @Override
    protected void presetVariables() {
        super.presetVariables();
        tier = Attribute.getTier(getBlockType(), LaserTurretTier.class);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, state -> PlayState.CONTINUE)
                .triggerableAnim("shoot", SHOOT_ANIMATION));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
