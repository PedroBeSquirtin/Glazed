package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Random;

public class CrystalMacro extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgTimings = settings.createGroup("Timings");
    private final SettingGroup sgHuman = settings.createGroup("Humanization");

    // ============ GENERAL SETTINGS ============
    private final Setting<Boolean> placeCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name("place-crystals")
        .description("Auto-place crystals")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name("break-crystals")
        .description("Auto-break crystals")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> placeObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name("place-obsidian")
        .description("Place obsidian if missing")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> activateKey = sgGeneral.add(new IntSetting.Builder()
        .name("activate-key")
        .description("Key to activate (1 = left click, 2 = right click)")
        .defaultValue(1)
        .min(1)
        .max(2)
        .build()
    );

    // ============ TIMINGS ============
    private final Setting<Integer> placeDelay = sgTimings.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placements (ticks)")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Integer> breakDelay = sgTimings.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Delay between breaks (ticks)")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    // ============ HUMANIZATION ============
    private final Setting<Boolean> humanize = sgHuman.add(new BoolSetting.Builder()
        .name("humanize")
        .description("Add random delays to look human")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> missChance = sgHuman.add(new IntSetting.Builder()
        .name("miss-chance")
        .description("Chance to miss a hit (%)")
        .defaultValue(5)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .build()
    );

    // ============ STATE ============
    private int placeTimer = 0;
    private int breakTimer = 0;
    private final Random random = new Random();

    public CrystalMacro() {
        super(GlazedAddon.pvp, "crystal-macro", "Auto crystal place/break - undetectable");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Update timers
        if (placeTimer > 0) placeTimer--;
        if (breakTimer > 0) breakTimer--;

        // Check if key is pressed
        boolean keyPressed = activateKey.get() == 1 ? 
            mc.options.attackKey.isPressed() : mc.options.useKey.isPressed();

        if (!keyPressed) return;

        // Check if holding crystal
        if (!mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) return;

        HitResult target = mc.crosshairTarget;

        if (target instanceof BlockHitResult blockHit && placeCrystals.get()) {
            handleBlockHit(blockHit);
        } else if (target instanceof EntityHitResult entityHit && breakCrystals.get()) {
            handleEntityHit(entityHit);
        }
    }

    private void handleBlockHit(BlockHitResult blockHit) {
        if (placeTimer > 0) return;

        BlockPos pos = blockHit.getBlockPos();

        // Check if we need to place obsidian
        if (placeObsidian.get() && !isValidCrystalBlock(pos)) {
            int obsidianSlot = findItemSlot(Items.OBSIDIAN);
            if (obsidianSlot != -1) {
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(obsidianSlot));
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                
                // Switch back to crystals
                int crystalSlot = findItemSlot(Items.END_CRYSTAL);
                if (crystalSlot != -1) {
                    mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(crystalSlot));
                }
                
                setDelay(true);
            }
            return;
        }

        // Place crystal
        if (isValidCrystalBlock(pos) && isValidCrystalPlacement(pos)) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
            mc.player.swingHand(Hand.MAIN_HAND);
            setDelay(true);
        }
    }

    private void handleEntityHit(EntityHitResult entityHit) {
        if (breakTimer > 0) return;

        Entity entity = entityHit.getEntity();
        if (!(entity instanceof EndCrystalEntity)) return;

        // Chance to miss (looks human)
        if (humanize.get() && random.nextInt(100) < missChance.get()) {
            setDelay(false);
            return;
        }

        mc.interactionManager.attackEntity(mc.player, entity);
        mc.player.swingHand(Hand.MAIN_HAND);
        setDelay(false);
    }

    private boolean isValidCrystalBlock(BlockPos pos) {
        return mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN) ||
               mc.world.getBlockState(pos).isOf(Blocks.BEDROCK);
    }

    private boolean isValidCrystalPlacement(BlockPos pos) {
        BlockPos up = pos.up();
        if (!mc.world.isAir(up)) return false;

        Box box = new Box(up).expand(0.1);
        return mc.world.getOtherEntities(null, box, e -> e instanceof EndCrystalEntity).isEmpty();
    }

    private int findItemSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private void setDelay(boolean isPlace) {
        int baseDelay = isPlace ? placeDelay.get() : breakDelay.get();
        
        if (humanize.get()) {
            // Add random variance
            placeTimer = baseDelay + random.nextInt(3);
            breakTimer = baseDelay + random.nextInt(3);
        } else {
            placeTimer = baseDelay;
            breakTimer = baseDelay;
        }
    }
}
