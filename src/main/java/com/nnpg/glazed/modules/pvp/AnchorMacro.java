package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;  // <-- ADD THIS IMPORT

import java.util.Random;

public class AnchorMacro extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgTimings = settings.createGroup("Timings");
    private final SettingGroup sgHuman = settings.createGroup("Humanization");

    // ============ GENERAL SETTINGS ============
    private final Setting<Integer> totemSlot = sgGeneral.add(new IntSetting.Builder()
        .name("totem-slot")
        .description("Hotbar slot for totem (1-9)")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Auto switch between anchor/glowstone")
        .defaultValue(true)
        .build()
    );

    // ============ TIMINGS ============
    private final Setting<Integer> switchDelay = sgTimings.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay when switching items (ticks)")
        .defaultValue(2)
        .min(0)
        .max(10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> interactDelay = sgTimings.add(new IntSetting.Builder()
        .name("interact-delay")
        .description("Delay when interacting (ticks)")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    // ============ HUMANIZATION ============
    private final Setting<Boolean> humanize = sgHuman.add(new BoolSetting.Builder()
        .name("humanize")
        .description("Add random delays")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> randomDelay = sgHuman.add(new IntSetting.Builder()
        .name("random-delay")
        .description("Extra random delay (ticks)")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .visible(humanize::get)
        .build()
    );

    // ============ STATE ============
    private int timer = 0;
    private boolean charging = false;
    private boolean exploding = false;
    private final Random random = new Random();

    public AnchorMacro() {
        super(GlazedAddon.pvp, "anchor-macro", "Auto charge and explode anchors - undetectable");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;

        if (!mc.world.getBlockState(bhr.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) return;

        // Check if holding right click
        if (!mc.options.useKey.isPressed()) {
            charging = false;
            exploding = false;
            return;
        }

        handleAnchor(bhr);
    }

    private void handleAnchor(BlockHitResult bhr) {
        int chargeLevel = getChargeLevel(bhr.getBlockPos());

        if (chargeLevel < 4) {
            // Needs charging
            if (!charging) {
                startCharging(bhr);
            } else {
                continueCharging(bhr);
            }
        } else {
            // Full - ready to explode
            if (!exploding) {
                startExploding(bhr);
            } else {
                explode(bhr);
            }
        }
    }

    private void startCharging(BlockHitResult bhr) {
        if (autoSwitch.get() && !mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
            InvUtils.findInHotbar(Items.GLOWSTONE).swap(true);
            setDelay(switchDelay.get());
        } else {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            mc.player.swingHand(Hand.MAIN_HAND);
            charging = true;
            setDelay(interactDelay.get());
        }
    }

    private void continueCharging(BlockHitResult bhr) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
        
        if (getChargeLevel(bhr.getBlockPos()) >= 4) {
            charging = false;
            exploding = true;
        }
        
        setDelay(interactDelay.get());
    }

    private void startExploding(BlockHitResult bhr) {
        // Switch to totem
        InvUtils.swap(totemSlot.get() - 1, true);
        setDelay(switchDelay.get());
    }

    private void explode(BlockHitResult bhr) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
        exploding = false;
        setDelay(interactDelay.get());
    }

    private int getChargeLevel(BlockPos pos) {
        return mc.world.getBlockState(pos).get(net.minecraft.block.RespawnAnchorBlock.CHARGES);
    }

    private void setDelay(int base) {
        if (humanize.get() && randomDelay.get() > 0) {
            timer = base + random.nextInt(randomDelay.get() + 1);
        } else {
            timer = base;
        }
    }
}
