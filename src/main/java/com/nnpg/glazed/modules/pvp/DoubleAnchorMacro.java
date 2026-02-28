package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
    private final SettingGroup sgGeneral = settings.createGroup("Settings");

    private final Setting<Keybind> activateKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("key")
        .description("Activation key")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between steps (ms)")
        .defaultValue(100)
        .min(60)
        .max(250)
        .sliderRange(60, 200)
        .build()
    );

    private final Setting<Integer> variance = sgGeneral.add(new IntSetting.Builder()
        .name("variance")
        .description("Random variance (ms)")
        .defaultValue(40)
        .min(10)
        .max(100)
        .sliderRange(10, 80)
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
    private BlockPos targetPos = null;
    private final Random random = new Random();

    public DoubleAnchorMacro() {
        super(GlazedAddon.pvp, "double-anchor", "Natural double anchoring - low risk");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();

        if (!activateKey.get().isPressed()) {
            step = 0;
            targetPos = null;
            return;
        }

        if (now < nextActionTime) return;

        if (targetPos == null) {
            if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
            if (!mc.world.getBlockState(bhr.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) return;
            targetPos = bhr.getBlockPos();
            step = 0;
        }

        executeStep(now);
    }

    private void executeStep(long now) {
        if (targetPos == null) return;

        BlockHitResult bhr = new BlockHitResult(
            targetPos.toCenterPos(),
            mc.player.getHorizontalFacing(),
            targetPos,
            false
        );

        switch (step) {
            case 0: // Switch to anchor
                switchToItem(Items.RESPAWN_ANCHOR, now);
                step = 1;
                break;
            case 1: // Place first
                interact(bhr, now);
                step = 2;
                break;
            case 2: // Switch to glowstone
                switchToItem(Items.GLOWSTONE, now);
                step = 3;
                break;
            case 3: // Charge first
                interact(bhr, now);
                step = 4;
                break;
            case 4: // Switch to anchor again
                switchToItem(Items.RESPAWN_ANCHOR, now);
                step = 5;
                break;
            case 5: // Place second
                interact(bhr, now);
                step = 6;
                break;
            case 6: // Switch to glowstone again
                switchToItem(Items.GLOWSTONE, now);
                step = 7;
                break;
            case 7: // Charge second
                interact(bhr, now);
                step = 8;
                break;
            case 8: // Switch to totem
                switchToTotem(now);
                step = 9;
                break;
            case 9: // Explode
                interact(bhr, now);
                step = 0;
                targetPos = null;
                break;
        }
    }

    private void switchToItem(net.minecraft.item.Item item, long now) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                mc.player.getInventory().selectedSlot = i;
                nextActionTime = now + delay.get() + random.nextInt(variance.get());
                break;
            }
        }
    }

    private void switchToTotem(long now) {
        mc.player.getInventory().selectedSlot = totemSlot.get() - 1;
        nextActionTime = now + delay.get() + random.nextInt(variance.get());
    }

    private void interact(BlockHitResult bhr, long now) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
        nextActionTime = now + delay.get() + random.nextInt(variance.get());
    }
}
