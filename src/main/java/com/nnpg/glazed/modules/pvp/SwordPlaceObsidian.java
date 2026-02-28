package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class SwordPlaceObsidian extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Settings");

    private final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enable obsidian placing")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay")
        .description("Minimum delay (ms)")
        .defaultValue(50)
        .min(30)
        .max(200)
        .sliderRange(30, 150)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay")
        .description("Maximum delay (ms)")
        .defaultValue(120)
        .min(50)
        .max(300)
        .sliderRange(50, 200)
        .build()
    );

    private boolean placing = false;
    private int previousSlot = -1;
    private long nextActionTime = 0;
    private final Random random = new Random();

    public SwordPlaceObsidian() {
        super(GlazedAddon.pvp, "sword-obi-place", "Smooth obsidian placement - undetectable");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !enabled.get()) return;

        ItemStack mainHand = mc.player.getMainHandStack();
        if (!isSword(mainHand)) return;

        long now = System.currentTimeMillis();

        // Start placing
        if (mc.options.useKey.isPressed() && !placing) {
            HitResult hit = mc.crosshairTarget;
            if (hit instanceof BlockHitResult bhr) {
                int obsidianSlot = findObsidianSlot();
                if (obsidianSlot != -1) {
                    placing = true;
                    previousSlot = mc.player.getInventory().selectedSlot;
                    nextActionTime = now + getRandomDelay();
                }
            }
        }

        // Handle placing
        if (placing && now >= nextActionTime) {
            HitResult hit = mc.crosshairTarget;
            if (hit instanceof BlockHitResult bhr) {
                int obsidianSlot = findObsidianSlot();
                if (obsidianSlot != -1) {
                    // Switch to obsidian
                    mc.player.getInventory().selectedSlot = obsidianSlot;
                    
                    // Place block
                    Vec3d placePos = bhr.getBlockPos().offset(bhr.getSide()).toCenterPos();
                    BlockHitResult placeTarget = new BlockHitResult(placePos, bhr.getSide(), bhr.getBlockPos(), false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeTarget);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    
                    // Schedule switch back
                    nextActionTime = now + getRandomDelay();
                }
            }
        }

        // Switch back
        if (placing && !mc.options.useKey.isPressed() && now >= nextActionTime) {
            if (previousSlot != -1) {
                mc.player.getInventory().selectedSlot = previousSlot;
            }
            placing = false;
        }
    }

    private boolean isSword(ItemStack stack) {
        return stack.isOf(Items.WOODEN_SWORD) || stack.isOf(Items.STONE_SWORD) ||
            stack.isOf(Items.IRON_SWORD) || stack.isOf(Items.GOLDEN_SWORD) ||
            stack.isOf(Items.DIAMOND_SWORD) || stack.isOf(Items.NETHERITE_SWORD);
    }

    private int findObsidianSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.OBSIDIAN)) return i;
        }
        return -1;
    }

    private int getRandomDelay() {
        return minDelay.get() + random.nextInt(maxDelay.get() - minDelay.get() + 1);
    }
}
