package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Random;

public class CrystalMacro extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Settings");

    private final Setting<Boolean> placeEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("place")
        .description("Place crystals")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("break")
        .description("Break crystals")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> reactionTime = sgGeneral.add(new IntSetting.Builder()
        .name("reaction")
        .description("Reaction time (ms)")
        .defaultValue(80)
        .min(50)
        .max(200)
        .sliderRange(50, 150)
        .build()
    );

    private final Setting<Integer> missChance = sgGeneral.add(new IntSetting.Builder()
        .name("miss-chance")
        .description("Chance to miss (%)")
        .defaultValue(10)
        .min(5)
        .max(30)
        .sliderRange(5, 25)
        .build()
    );

    private long nextActionTime = 0;
    private final Random random = new Random();

    public CrystalMacro() {
        super(GlazedAddon.pvp, "crystal-macro", "Natural crystal placement - low risk");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now < nextActionTime) return;

        // Check if holding crystal
        if (!mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) return;

        HitResult target = mc.crosshairTarget;

        if (target instanceof BlockHitResult blockHit && placeEnabled.get()) {
            handleBlockHit(blockHit, now);
        } else if (target instanceof EntityHitResult entityHit && breakEnabled.get()) {
            handleEntityHit(entityHit, now);
        }
    }

    private void handleBlockHit(BlockHitResult blockHit, long now) {
        BlockPos pos = blockHit.getBlockPos();
        
        if (!isValidCrystalBlock(pos)) return;
        if (!isValidCrystalPlacement(pos)) return;

        // Chance to miss
        if (random.nextInt(100) < missChance.get()) {
            nextActionTime = now + 50 + random.nextInt(50);
            return;
        }

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
        mc.player.swingHand(Hand.MAIN_HAND);
        
        nextActionTime = now + reactionTime.get() + random.nextInt(30);
    }

    private void handleEntityHit(EntityHitResult entityHit, long now) {
        if (!(entityHit.getEntity() instanceof EndCrystalEntity)) return;

        // Chance to miss
        if (random.nextInt(100) < missChance.get()) {
            nextActionTime = now + 50 + random.nextInt(50);
            return;
        }

        mc.interactionManager.attackEntity(mc.player, entityHit.getEntity());
        mc.player.swingHand(Hand.MAIN_HAND);
        
        nextActionTime = now + reactionTime.get() + random.nextInt(30);
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
}
