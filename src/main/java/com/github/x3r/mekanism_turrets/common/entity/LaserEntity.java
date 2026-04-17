package com.github.x3r.mekanism_turrets.common.entity;

import com.github.x3r.mekanism_turrets.common.block.LaserTurretBlock;
import com.github.x3r.mekanism_turrets.common.registry.DamageTypeRegistry;
import com.github.x3r.mekanism_turrets.common.registry.EntityRegistry;
import mekanism.common.item.gear.ItemMekaSuitArmor;
import mekanism.common.registries.MekanismModules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

import java.awt.geom.QuadCurve2D;
import java.util.Objects;

import static net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel;

public class LaserEntity extends Projectile implements net.minecraftforge.entity.IEntityAdditionalSpawnData {
    private int lifeTime = 0;
    private double damage = 1.0F;
    private DamageSource laserSource = null;
    private Vec3 absoluteWorldVelocity = Vec3.ZERO;
    public LaserEntity(EntityType<? extends Projectile> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
        this.noPhysics = true;
    }

    public LaserEntity(Level pLevel, Vec3 pos, double damage,Entity pOwner) {
        this(EntityRegistry.LASER.get(), pLevel);
        this.setPos(pos);
        this.damage = damage;
        this.setOwner(pOwner);

    }
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket() {
        return net.minecraftforge.network.NetworkHooks.getEntitySpawningPacket(this);
    }
    @Override
    public void writeSpawnData(net.minecraft.network.FriendlyByteBuf buffer) {
        buffer.writeDouble(this.absoluteWorldVelocity.x);
        buffer.writeDouble(this.absoluteWorldVelocity.y);
        buffer.writeDouble(this.absoluteWorldVelocity.z);
    }
    @Override
    public void readSpawnData(net.minecraft.network.FriendlyByteBuf buffer) {
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        this.absoluteWorldVelocity = new Vec3(x, y, z);
    }


    @Override
    public void tick() {
        // prefix velocity
        try {
            Ship ship = VSGameUtilsKt.getShipManaging(this);

            if (ship != null) {
                org.joml.Vector3dc linearVel = ship.getVelocity();
                org.joml.Vector3dc angularVel = ship.getOmega();

                org.joml.Vector3dc comWorld = ship.getTransform().getPositionInWorld();
                org.joml.Vector3d laserShipPos = new org.joml.Vector3d(this.getX(), this.getY(), this.getZ());
                org.joml.Vector3d laserWorldPos = ship.getTransform().getShipToWorld().transformPosition(laserShipPos, new org.joml.Vector3d());

                org.joml.Vector3d r = laserWorldPos.sub(comWorld, new org.joml.Vector3d());

                org.joml.Vector3d tangentialVel = angularVel.cross(r, new org.joml.Vector3d());

                org.joml.Vector3d trueShipVel = linearVel.add(tangentialVel, new org.joml.Vector3d());

                double tickScale = 0.05;
                Vec3 shipVel = new Vec3(trueShipVel.x * tickScale, trueShipVel.y * tickScale, trueShipVel.z * tickScale);
                Vec3 relVelWorld = this.absoluteWorldVelocity.subtract(shipVel);
                org.joml.Vector3d relVelShip = new org.joml.Vector3d(relVelWorld.x, relVelWorld.y, relVelWorld.z);
                ship.getTransform().getWorldToShip().transformDirection(relVelShip);

                this.setDeltaMovement(VectorConversionsMCKt.toMinecraft(relVelShip));
            } else {
                this.setDeltaMovement(this.absoluteWorldVelocity);
            }
        } catch (NoClassDefFoundError e) {
            this.setDeltaMovement(this.absoluteWorldVelocity);
        }
        // after tick
        super.tick();
        if (laserSource == null){
            laserSource = new DamageTypeRegistry(level().registryAccess(), this, this.getOwner()).laser();
        }
        if(!level().isClientSide()) {
            HitResult hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            if(hitResult.getType().equals(HitResult.Type.BLOCK)) {
                onHitBlock((BlockHitResult) hitResult);
            }
            Vec3 movement = this.getDeltaMovement();
            net.minecraft.world.phys.AABB sweptBox = this.getBoundingBox().expandTowards(movement).inflate(0.75);
            level().getEntities(this, sweptBox).forEach(entity -> {
                boolean isPlayer = entity.getType().equals(EntityType.PLAYER);
                boolean isImmuneToLaser = false;
                isImmuneToLaser = (entity.getType().equals(EntityType.ITEM)) || (entity.getType().equals(EntityType.EXPERIENCE_ORB));

                try {
                    if (isPlayer) {
                        isImmuneToLaser = ((Player) entity).isCreative();
                        if (!isImmuneToLaser) {
                            double finaldamage = 0;
                            float armor = 0;
                            float toughness = 0;
                            float protectEnchantReduction = (float) (0.01 * Math.max(80, 4 * getEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, (LivingEntity) entity)));
                            for (ItemStack armorStack : entity.getArmorSlots()) {
                                if (armorStack.getItem() instanceof ArmorItem armorItem) {
                                    if (armorItem instanceof ItemMekaSuitArmor) {
                                        ItemMekaSuitArmor mekasuit = (ItemMekaSuitArmor) armorItem;
                                        isImmuneToLaser = mekasuit.isModuleEnabled(armorStack, MekanismModules.LASER_DISSIPATION_UNIT);
                                        if (isImmuneToLaser) {
                                            break;
                                        }
                                    }
                                    armor = armor + armorItem.getDefense();
                                    toughness = toughness + armorItem.getToughness();
                                }
                            }
                            float damageReductionRate = (float) (Math.min(20, Math.max(armor / 5, armor - ((4 * this.damage) / (Math.min(toughness, 20) + 8)))) / 25);
                            finaldamage = this.damage * (1 - damageReductionRate) * (1 - protectEnchantReduction);
                            int durabilityLoss = (int) Math.floor(this.damage - finaldamage);
                            if (!isImmuneToLaser) {
                                for (ItemStack armorStack : entity.getArmorSlots()) {
                                    if (armorStack.getDamageValue() < armorStack.getMaxDamage()) {
                                        armorStack.hurtAndBreak(durabilityLoss, (LivingEntity) entity,
                                                (player) -> player.broadcastBreakEvent(armorStack.getEquipmentSlot()));
                                    } else {
                                        armorStack.setDamageValue(armorStack.getMaxDamage());
                                    }

                                }
                            }
                            this.damage = finaldamage;
                        }
                    }
                }
                catch (Exception ignored){
                    ;
                }


                try {
                    entity.tickCount = 100;
                    ((LivingEntity) entity).setLastHurtByPlayer((Player) this.getOwner());
                } catch (Exception ignored) {
                    ;
                }
                if (!isImmuneToLaser) {
                    try {
                        LivingEntity livingTarget = (LivingEntity) entity;
                        float currentHealth = livingTarget.getHealth();
                        float trueDamage = (float) this.damage;

                        if (currentHealth > trueDamage) {
                            livingTarget.setHealth(currentHealth - trueDamage);
                            livingTarget.hurt(laserSource, 0.001F);
                        } else {
                            livingTarget.setHealth(0.001F);
                            livingTarget.hurt(laserSource, 1000000000F);
                        }
                    }
                    catch (ClassCastException e){
                        ;
                    }
                }
            });
            lifeTime++;
            if (lifeTime > 10 * 20) {
                this.discard();
            }
        }
        this.setPos(this.position().add(this.getDeltaMovement()));

    }

    public void shootWithAbsoluteVelocity(Vec3 absoluteVelocity) {
        this.absoluteWorldVelocity = absoluteVelocity;
        this.setDeltaMovement(absoluteVelocity);
    }

    @Override
    protected void onHitBlock(BlockHitResult pResult) {
        super.onHitBlock(pResult);
        BlockState state = (level().getBlockState(pResult.getBlockPos()));
        if(!(state.getBlock() instanceof LaserTurretBlock) && state.isCollisionShapeFullBlock(level(), pResult.getBlockPos())) {
            this.discard();
        }
    }

    @Override
    protected void defineSynchedData() {

    }
}
