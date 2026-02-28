package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

public class DoubleAnchorMacro extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgTimings = settings.createGroup("Timings");
    private final SettingGroup sgHuman = settings.createGroup("Humanization");

    // ============ GENERAL SETTINGS ============
    private final Setting<Keybind> activateKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("activate-key")
        .description("Key to start double anchoring")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> totemSlot = sgGeneral.add(new IntSetting.Builder()
        .name("totem-slot")
        .description("Hotbar slot for totem (1-9)")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Boolean> stopOnMiss = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-miss")
        .description("Stop if target block is no longer anchor")
        .defaultValue(true)
        .build()
    );

    // ============ TIMINGS ============
    private final Setting<Integer> switchDelay = sgTimings.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay between item switches (ticks)")
        .defaultValue(2)
        .min(0)
        .max(10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> placeDelay = sgTimings.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placements (ticks)")
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

    private final Setting<Integer> variance = sgHuman.add(new IntSetting.Builder()
        .name("variance")
        .description("Random delay variance (ticks)")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .visible(humanize::get)
        .build()
    );

    // ============ STATE ============
    private int step = 0;
    private int delayCounter = 0;
    private boolean running = false;
    private BlockPos targetPos = null;
    private final Random random = new Random();
    private boolean wasPressed = false;

    public DoubleAnchorMacro() {
        super(GlazedAddon.pvp, "double-anchor", "Place and charge 2 anchors - undetectable");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        boolean keyPressed = activateKey.get().isPressed();

        if (!running && keyPressed && !wasPressed) {
            startAnchoring();
        }

        wasPressed = keyPressed;

        if (!running) return;

        if (stopOnMiss.get() && !isValidTarget()) {
            stopAnchoring();
            return;
        }

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        executeStep();
    }

    private void startAnchoring() {
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;

        targetPos = bhr.getBlockPos();
        if (!mc.world.getBlockState(targetPos).isOf(Blocks.RESPAWN_ANCHOR)) return;

        running = true;
        step = 0;
        delayCounter = 0;
    }

    private boolean isValidTarget() {
        if (targetPos == null) return false;
        return mc.world.getBlockState(targetPos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    private void executeStep() {
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) {
            stopAnchoring();
            return;
        }

        switch (step) {
            case 0: // Switch to anchor
                InvUtils.findInHotbar(Items.RESPAWN_ANCHOR).swap(true);
                setDelay();
                break;

            case 1: // Place first anchor
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);
                setDelay();
                break;

            case 2: // Switch to glowstone
                InvUtils.findInHotbar(Items.GLOWSTONE).swap(true);
                setDelay();
                break;

            case 3: // Charge first anchor
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);
                setDelay();
                break;

            case 4: // Switch to anchor again
                InvUtils.findInHotbar(Items.RESPAWN_ANCHOR).swap(true);
                setDelay();
                break;

            case 5: // Place second anchor
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);
                setDelay();
                break;

            case 6: // Switch to glowstone again
                InvUtils.findInHotbar(Items.GLOWSTONE).swap(true);
                setDelay();
                break;

            case 7: // Charge second anchor
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);
                setDelay();
                break;

            case 8: // Switch to totem
                InvUtils.swap(totemSlot.get() - 1, true);
                setDelay();
                break;

            case 9: // Explode
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);
                stopAnchoring();
                return;
        }

        step++;
    }

    private void setDelay() {
        int baseDelay = (step % 2 == 0) ? switchDelay.get() : placeDelay.get();
        if (humanize.get() && variance.get() > 0) {
            delayCounter = baseDelay + random.nextInt(variance.get() + 1);
        } else {
            delayCounter = baseDelay;
        }
    }

    private void stopAnchoring() {
        running = false;
        step = 0;
        targetPos = null;
    }
}
