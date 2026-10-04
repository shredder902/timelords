package me.example.timelord;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class TimeLord implements ModInitializer {

    private static final double RADIUS = 2.5;          // зона 5x5x5
    private static final int FREEZE_TICKS = 5 * 20;    // 5 секунд
    private static final int PASSIVE_TICKS = 20;       // 1 секунда
    private static final int COOLDOWN_TICKS = 30 * 20; // перезарядка

    private static final Map<UUID, Integer> FROZEN_UNTIL = new HashMap<>();
    private static final Map<UUID, Vec3d> FROZEN_POS = new HashMap<>();

    @Override
    public void onInitialize() {

        // ===== ПАССИВКА + замороженный не может наносить урон =====
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Entity attacker = source.getAttacker();

            if (attacker != null && FROZEN_UNTIL.containsKey(attacker.getUuid())) {
                return false;
            }

            if (entity instanceof PlayerEntity mage && hasClock(mage)
                    && attacker instanceof LivingEntity victim) {
                victim.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.SLOWNESS, PASSIVE_TICKS, 1)); // уровень II
            }
            return true;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (FROZEN_UNTIL.containsKey(player.getUuid())) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        // ===== АКТИВКА (ПКМ часами) =====
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient() || !isClock(stack)) return ActionResult.PASS;

            if (player.getItemCooldownManager().isCoolingDown(stack)) {
                return ActionResult.FAIL;
            }

            LivingEntity target = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class,
                    player.getBoundingBox().expand(RADIUS), le -> le != player && le.isAlive())) {
                double d = e.squaredDistanceTo(player);
                if (d < best) { best = d; target = e; }
            }

            if (target == null) {
                player.sendMessage(Text.literal("§7Рядом нет целей."), true);
                return ActionResult.FAIL;
            }

            freeze(target, (ServerWorld) world);
            player.getItemCooldownManager().set(stack, COOLDOWN_TICKS);
            player.sendMessage(Text.literal("§6Время остановлено!"), true);
            return ActionResult.SUCCESS;
        });

        // ===== Удержание замороженных на месте =====
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now = server.getTicks();
            Iterator<Map.Entry<UUID, Integer>> it = FROZEN_UNTIL.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<UUID, Integer> en = it.next();
                UUID id = en.getKey();
                Entity e = null;
                for (ServerWorld w : server.getWorlds()) {
                    e = w.getEntity(id);
                    if (e != null) break;
                }

                boolean expired = now >= en.getValue() || e == null || !e.isAlive();
                if (expired) {
                    if (e instanceof MobEntity mob) mob.setAiDisabled(false);
                    FROZEN_POS.remove(id);
                    it.remove();
                    continue;
                }

                Vec3d pos = FROZEN_POS.get(id);
                if (e instanceof ServerPlayerEntity p) {
                    // не двигается и не прыгает, но может вертеть головой
                    boolean moved = Math.abs(p.getX() - pos.x) > 0.01
                            || Math.abs(p.getZ() - pos.z) > 0.01
                            || p.getY() > pos.y + 0.05;
                    if (moved) {
                        p.networkHandler.requestTeleport(pos.x, Math.min(p.getY(), pos.y), pos.z,
                                p.getYaw(), p.getPitch());
                    }
                }
            }
        });
    }

    private static void freeze(LivingEntity target, ServerWorld world) {
        FROZEN_UNTIL.put(target.getUuid(), world.getServer().getTicks() + FREEZE_TICKS);
        FROZEN_POS.put(target.getUuid(), new Vec3d(target.getX(), target.getY(), target.getZ()));

        if (target instanceof MobEntity mob) mob.setAiDisabled(true);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, FREEZE_TICKS, 6));

        world.spawnParticles(ParticleTypes.END_ROD,
                target.getX(), target.getY() + 1, target.getZ(), 40, 0.4, 0.8, 0.4, 0.02);
        world.playSound(null, target.getBlockPos(), SoundEvents.BLOCK_BEACON_DEACTIVATE,
                SoundCategory.PLAYERS, 1f, 0.8f);
    }

    private static boolean isClock(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().contains("time_lord");
    }

    private static boolean hasClock(PlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (isClock(p.getInventory().getStack(i))) return true;
        }
        return false;
    }
}
