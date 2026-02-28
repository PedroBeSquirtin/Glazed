package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

public class AnchorMacro extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Settings");

    private final Setting<Integer> reactionTime = sgGeneral.add(new IntSetting.Builder()
        .name("reaction")
        .description("Reaction time (ms)")
        .defaultValue(100)
        .min(60)
        .max(250)
        .sliderRange(60, 200)
        .build()
    );

    private final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Item switch delay (ms)")
        .defaultValue(80)
        .min(50)
        .max(200)
        .sliderRange(50, 150)
        .build()
    );

    private final Setting<Integer> totemSlot = sgGeneral.add(new IntSetting.Builder()
        .name("totem-slot")
        .description("Totem slot (1-9)")
        .defaultValue(1)
        .min(1)
        .max(9)
        .build()
    );

    private int step = 0;
    private long nextActionTime = 0;
    private final Random random = new Random();

    public AnchorMacro() {
        super(GlazedAddon.pvp, "anchor-macro", "Natural anchor usage - low risk");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now < nextActionTime) return;

        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
        if (!mc.world.getBlockState(bhr.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) return;

        // Only activate when holding right click
        if (!mc.options.useKey.isPressed()) {
            step = 0;
            return;
        }

        handleAnchor(bhr, now);
    }

    private void handleAnchor(BlockHitResult bhr, long now) {
        int charge = getChargeLevel(bhr.getBlockPos());

        if (charge < 4) {
            // Charging phase
            if (step == 0) {
                // Switch to glowstone if needed
                if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
                    switchToItem(Items.GLOWSTONE, now);
                    step = 1;
                } else {
                    chargeAnchor(bhr, now);
                }
            } else if (step == 1) {
                chargeAnchor(bhr, now);
            }
        } else {
            // Exploding phase
            if (step == 0) {
                // Switch to totem
                switchToTotem(now);
                step = 1;
            } else if (step == 1) {
                explodeAnchor(bhr, now);
                step = 0;
            }
        }
    }

    private void chargeAnchor(BlockHitResult bhr, long now) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
        nextActionTime = now + reactionTime.get() + random.nextInt(40);
        
        if (getChargeLevel(bhr.getBlockPos()) >= 4) {
            step = 0;
        }
    }

    private void switchToItem(net.minecraft.item.Item item, long now) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                mc.player.getInventory().selectedSlot = i;
                nextActionTime = now + switchDelay.get() + random.nextInt(30);
                break;
            }
        }
    }

    private void switchToTotem(long now) {
        mc.player.getInventory().selectedSlot = totemSlot.get() - 1;
        nextActionTime = now + switchDelay.get() + random.nextInt(30);
    }

    private void explodeAnchor(BlockHitResult bhr, long now) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
        nextActionTime = now + reactionTime.get() + random.nextInt(40);
    }

    private int getChargeLevel(BlockPos pos) {
        return mc.world.getBlockState(pos).get(net.minecraft.block.RespawnAnchorBlock.CHARGES);
    }
}
