package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class SwordPlaceObsidian extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgHuman = settings.createGroup("Humanization");

    // ============ GENERAL SETTINGS ============
    private final Setting<Boolean> autoPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-place")
        .description("Automatically place obsidian when right-clicking with sword")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between placing (0-5)")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> returnToSword = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-sword")
        .description("Switch back to sword after placing")
        .defaultValue(true)
        .build()
    );

    // ============ HUMANIZATION ============
    private final Setting<Boolean> humanize = sgHuman.add(new BoolSetting.Builder()
        .name("humanize")
        .description("Add random delays to look human")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDelay = sgHuman.add(new IntSetting.Builder()
        .name("min-delay")
        .description("Minimum delay (ms)")
        .defaultValue(30)
        .min(10)
        .max(100)
        .sliderRange(10, 100)
        .visible(humanize::get)
        .build()
    );

    private final Setting<Integer> maxDelay = sgHuman.add(new IntSetting.Builder()
        .name("max-delay")
        .description("Maximum delay (ms)")
        .defaultValue(80)
        .min(20)
        .max(200)
        .sliderRange(20, 200)
        .visible(humanize::get)
        .build()
    );

    // ============ STATE ============
    private boolean placing = false;
    private int previousSlot = -1;
    private int delayTimer = 0;
    private final Random random = new Random();

    public SwordPlaceObsidian() {
        super(GlazedAddon.pvp, "sword-obi-place", "Place obsidian while holding sword - undetectable");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        ItemStack mainHand = mc.player.getMainHandStack();
        if (!isSword(mainHand)) return;

        if (autoPlace.get() && mc.options.useKey.isPressed() && !placing) {
            startPlacing();
        }

        if (placing) {
            handlePlacing();
        }

        if (placing && !mc.options.useKey.isPressed()) {
            finishPlacing();
        }

        if (delayTimer > 0) delayTimer--;
    }

    private void startPlacing() {
        int obsidianSlot = findObsidianSlot();
        if (obsidianSlot == -1) return;

        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr)) return;

        placing = true;
        previousSlot = mc.player.getInventory().selectedSlot;

        // Human-like delay before switching
        if (humanize.get()) {
            delayTimer = random.nextInt(maxDelay.get() - minDelay.get() + 1) + minDelay.get();
        } else {
            delayTimer = placeDelay.get();
        }
    }

    private void handlePlacing() {
        if (delayTimer > 0) return;

        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr)) {
            placing = false;
            return;
        }

        int obsidianSlot = findObsidianSlot();
        if (obsidianSlot == -1) {
            placing = false;
            return;
        }

        // Switch to obsidian
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(obsidianSlot));

        // Place with natural timing
        Vec3d placePos = bhr.getBlockPos().offset(bhr.getSide()).toCenterPos();
        BlockHitResult placeTarget = new BlockHitResult(placePos, bhr.getSide(), bhr.getBlockPos(), false);

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeTarget);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void finishPlacing() {
        if (returnToSword.get() && previousSlot != -1) {
            // Human-like delay before switching back
            if (humanize.get()) {
                try {
                    Thread.sleep(random.nextInt(50) + 30);
                } catch (InterruptedException e) {}
            }
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(previousSlot));
        }
        placing = false;
        previousSlot = -1;
    }

    private boolean isSword(ItemStack stack) {
        return stack.isOf(Items.WOODEN_SWORD) || stack.isOf(Items.STONE_SWORD) ||
            stack.isOf(Items.IRON_SWORD) || stack.isOf(Items.GOLDEN_SWORD) ||
            stack.isOf(Items.DIAMOND_SWORD) || stack.isOf(Items.NETHERITE_SWORD);
    }

    private int findObsidianSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.OBSIDIAN)) return i;
        }
        return -1;
    }
}
