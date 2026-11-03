package obsidivision.ghostpath.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every concrete override of {@code boolean hurtServer(ServerLevel, DamageSource, float)} in 26.2 (Entity.hurtServer,
 * Entity.java:1931, is abstract, so there is no single place to hook). The list was generated from the genSources
 * output (grep for the override signature, 54 classes); a class that loses its override after a version change fails
 * start-up (defaultRequire 1), and a new override is simply not covered (see DESIGN, undo-log gaps).
 * HEAD: before any damage, health or item change. Undo log: first-touch snapshot (R4). Cause tracking: chain work
 * hurting an entity makes it part of the chain. Running more than once per hurt (an override calling super) is harmless.
 */
@Mixin(targets = {
        "net.minecraft.server.level.ServerPlayer",
        "net.minecraft.world.entity.AreaEffectCloud",
        "net.minecraft.world.entity.Display",
        "net.minecraft.world.entity.ExperienceOrb",
        "net.minecraft.world.entity.Interaction",
        "net.minecraft.world.entity.LightningBolt",
        "net.minecraft.world.entity.LivingEntity",
        "net.minecraft.world.entity.Marker",
        "net.minecraft.world.entity.OminousItemSpawner",
        "net.minecraft.world.entity.ambient.Bat",
        "net.minecraft.world.entity.animal.allay.Allay",
        "net.minecraft.world.entity.animal.armadillo.Armadillo",
        "net.minecraft.world.entity.animal.axolotl.Axolotl",
        "net.minecraft.world.entity.animal.bee.Bee",
        "net.minecraft.world.entity.animal.equine.AbstractHorse",
        "net.minecraft.world.entity.animal.golem.IronGolem",
        "net.minecraft.world.entity.animal.nautilus.AbstractNautilus",
        "net.minecraft.world.entity.animal.panda.Panda",
        "net.minecraft.world.entity.animal.parrot.Parrot",
        "net.minecraft.world.entity.animal.squid.GlowSquid",
        "net.minecraft.world.entity.animal.squid.Squid",
        "net.minecraft.world.entity.animal.wolf.Wolf",
        "net.minecraft.world.entity.boss.enderdragon.EndCrystal",
        "net.minecraft.world.entity.boss.enderdragon.EnderDragon",
        "net.minecraft.world.entity.boss.enderdragon.EnderDragonPart",
        "net.minecraft.world.entity.boss.wither.WitherBoss",
        "net.minecraft.world.entity.decoration.ArmorStand",
        "net.minecraft.world.entity.decoration.BlockAttachedEntity",
        "net.minecraft.world.entity.decoration.ItemFrame",
        "net.minecraft.world.entity.item.FallingBlockEntity",
        "net.minecraft.world.entity.item.ItemEntity",
        "net.minecraft.world.entity.item.PrimedTnt",
        "net.minecraft.world.entity.monster.EnderMan",
        "net.minecraft.world.entity.monster.Ghast",
        "net.minecraft.world.entity.monster.Guardian",
        "net.minecraft.world.entity.monster.Shulker",
        "net.minecraft.world.entity.monster.Silverfish",
        "net.minecraft.world.entity.monster.Zoglin",
        "net.minecraft.world.entity.monster.creaking.Creaking",
        "net.minecraft.world.entity.monster.cubemob.SulfurCube",
        "net.minecraft.world.entity.monster.hoglin.Hoglin",
        "net.minecraft.world.entity.monster.piglin.Piglin",
        "net.minecraft.world.entity.monster.piglin.PiglinBrute",
        "net.minecraft.world.entity.monster.warden.Warden",
        "net.minecraft.world.entity.monster.zombie.Zombie",
        "net.minecraft.world.entity.player.Player",
        "net.minecraft.world.entity.projectile.EvokerFangs",
        "net.minecraft.world.entity.projectile.EyeOfEnder",
        "net.minecraft.world.entity.projectile.Projectile",
        "net.minecraft.world.entity.projectile.ShulkerBullet",
        "net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile",
        "net.minecraft.world.entity.raid.Raider",
        "net.minecraft.world.entity.vehicle.VehicleEntity",
        "net.minecraft.world.entity.vehicle.minecart.MinecartTNT",
})
public abstract class EntityHurtMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void ghostpath$beforeHurt(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onEntityTouched(self);
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onEntityTouched(self, "hurt");
        }
    }
}
