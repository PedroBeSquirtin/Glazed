package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class Nemesis extends Module {
    private final SettingGroup sgCombat = settings.createGroup("Combat");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgDefense = settings.createGroup("Defense");

    // ============ COMBAT SETTINGS ============
    private final Setting<Boolean> autoShieldBreak = sgCombat.add(new BoolSetting.Builder()
        .name("auto-shield-break")
        .description("Break shields with axe")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoWeapon = sgCombat.add(new BoolSetting.Builder()
        .name("auto-weapon")
        .description("Switch to best weapon")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> attackEnabled = sgCombat.add(new BoolSetting.Builder()
        .name("auto-attack")
        .description("Automatically attack when in range")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> attackDelay = sgCombat.add(new IntSetting.Builder()
        .name("attack-delay")
        .description("Ticks between attacks (0-20)")
        .defaultValue(10)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .visible(attackEnabled::get)
        .build()
    );

    private final Setting<Double> combatRange = sgCombat.add(new DoubleSetting.Builder()
        .name("range")
        .description("Combat engagement range")
        .defaultValue(4.2)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build()
    );

    // ============ MOVEMENT SETTINGS ============
    private final Setting<Boolean> pearlClose = sgMovement.add(new BoolSetting.Builder()
        .name("pearl-close")
        .description("Throw pearls to close distance")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pearlDistance = sgMovement.add(new IntSetting.Builder()
        .name("pearl-distance")
        .description("Min distance to throw pearl")
        .defaultValue(20)
        .min(10)
        .max(50)
        .sliderRange(10, 50)
        .visible(pearlClose::get)
        .build()
    );

    private final Setting<Boolean> cobwebEscape = sgMovement.add(new BoolSetting.Builder()
        .name("cobweb-escape")
        .description("Auto jump out of cobwebs")
        .defaultValue(true)
        .build()
    );

    // ============ DEFENSE SETTINGS ============
    private final Setting<Boolean> waterBucket = sgDefense.add(new BoolSetting.Builder()
        .name("water-bucket")
        .description("Place water when on fire")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pickupWater = sgDefense.add(new BoolSetting.Builder()
        .name("pickup-water")
        .description("Pick water bucket back up")
        .defaultValue(true)
        .visible(waterBucket::get)
        .build()
    );

    // ============ STATE ============
    private PlayerEntity target;
    private int attackTimer = 0;
    private boolean isBreakingShield = false;
    private long lastPearlTime = 0;
    private BlockPos waterPlacedAt = null;
    private int cobwebTimer = 0;
    private final Random random = new Random();

    public Nemesis() {
        super(GlazedAddon.pvp, "nemesis", "Undetectable auto fight - shield break, pearls, water, combat");
    }

    @Override
    public void onActivate() {
        target = null;
        attackTimer = 0;
        waterPlacedAt = null;
        isBreakingShield = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Find target (natural, human-like targeting)
        updateTarget();

        // Cobweb escape (high priority)
        if (cobwebEscape.get() && isInCobweb()) {
            handleCobweb();
            return; // Prioritize escape over combat
        }

        // Water bucket if on fire
        if (waterBucket.get() && mc.player.isOnFire()) {
            handleWaterBucket();
        }

        if (target == null) return;

        double distance = mc.player.distanceTo(target);

        // Pearl closing
        if (pearlClose.get() && distance >= pearlDistance.get() && canThrowPearl()) {
            handlePearlThrow();
            return;
        }

        // Combat logic
        if (distance <= combatRange.get()) {
            handleCombat();
        }

        // Update timers
        if (attackTimer > 0) attackTimer--;
    }

    private void updateTarget() {
        // Natural targeting - don't lock on if in cobweb
        if (cobwebEscape.get() && isInCobweb()) {
            target = null;
            return;
        }

        // Find nearest valid target
        target = mc.world.getPlayers().stream()
            .filter(p -> p != mc.player)
            .filter(p -> !Friends.get().isFriend(p))
            .filter(p -> p.isAlive())
            .filter(p -> mc.player.distanceTo(p) <= combatRange.get() * 2)
            .min((p1, p2) -> Double.compare(
                mc.player.distanceTo(p1),
                mc.player.distanceTo(p2)))
            .orElse(null);
    }

    private boolean isInCobweb() {
        BlockPos pos = mc.player.getBlockPos();
        return mc.world.getBlockState(pos).getBlock() == Blocks.COBWEB ||
               mc.world.getBlockState(pos.down()).getBlock() == Blocks.COBWEB ||
               mc.world.getBlockState(pos.up()).getBlock() == Blocks.COBWEB;
    }

    private void handleCobweb() {
        cobwebTimer++;
        // Jump every other tick to escape
        if (cobwebTimer % 2 == 0) {
            mc.player.jump();
        }
        // Reset target to prevent locking
        target = null;
        // Reset after 20 ticks
        if (cobwebTimer > 20) cobwebTimer = 0;
    }

    private void handleWaterBucket() {
        FindItemResult bucket = InvUtils.findInHotbar(item -> item.getItem() == Items.WATER_BUCKET);
        
        if (bucket.found()) {
            int prevSlot = mc.player.getInventory().selectedSlot;
            InvUtils.swap(bucket.slot(), false);
            
            BlockPos pos = mc.player.getBlockPos();
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, 
                new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
            
            waterPlacedAt = pos;
            
            if (pickupWater.get()) {
                // Schedule pickup after a short delay
                new Thread(() -> {
                    try {
                        Thread.sleep(150 + random.nextInt(50));
                        mc.execute(this::pickupWaterBucket);
                    } catch (InterruptedException e) {}
                }).start();
            }
            
            InvUtils.swap(prevSlot, false);
        }
    }

    private void pickupWaterBucket() {
        if (waterPlacedAt == null) return;
        
        FindItemResult emptyBucket = InvUtils.findInHotbar(item -> item.getItem() == Items.BUCKET);
        if (emptyBucket.found()) {
            int prevSlot = mc.player.getInventory().selectedSlot;
            InvUtils.swap(emptyBucket.slot(), false);
            
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, 
                new BlockHitResult(Vec3d.ofCenter(waterPlacedAt), Direction.UP, waterPlacedAt, false));
            
            InvUtils.swap(prevSlot, false);
            waterPlacedAt = null;
        }
    }

    private boolean canThrowPearl() {
        return System.currentTimeMillis() - lastPearlTime > 2500; // 2.5 second cooldown
    }

    private void handlePearlThrow() {
        FindItemResult pearl = InvUtils.findInHotbar(item -> item.getItem() == Items.ENDER_PEARL);
        if (!pearl.found()) return;
        
        int prevSlot = mc.player.getInventory().selectedSlot;
        InvUtils.swap(pearl.slot(), false);
        
        // Human-like rotation before throw
        Rotations.rotate(mc.player.getYaw(), mc.player.getPitch(), 50 + random.nextInt(30), () -> {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        });
        
        InvUtils.swap(prevSlot, false);
        lastPearlTime = System.currentTimeMillis();
    }

    private void handleCombat() {
        if (target == null) return;

        // Check if target is blocking
        boolean targetBlocking = target.isUsingItem() && 
                                 target.getActiveItem().getItem() == Items.SHIELD;

        // Shield break logic
        if (autoShieldBreak.get() && targetBlocking && !isBreakingShield) {
            handleShieldBreak();
            return;
        }

        // Auto weapon switch
        if (autoWeapon.get() && !isBreakingShield) {
            equipBestWeapon();
        }

        // Auto attack
        if (attackEnabled.get() && attackTimer <= 0 && !isBreakingShield) {
            if (mc.player.distanceTo(target) <= combatRange.get()) {
                attack();
                attackTimer = attackDelay.get();
            }
        }
    }

    private void handleShieldBreak() {
        FindItemResult axe = InvUtils.findInHotbar(item -> item.getItem() instanceof AxeItem);
        if (!axe.found()) return;

        isBreakingShield = true;
        int prevSlot = mc.player.getInventory().selectedSlot;
        
        InvUtils.swap(axe.slot(), false);
        
        // Attack to break shield
        attack();
        
        // Schedule switch back to weapon
        int switchDelay = 80 + random.nextInt(40); // 80-120ms
        new Thread(() -> {
            try {
                Thread.sleep(switchDelay);
                mc.execute(() -> {
                    if (prevSlot != -1) {
                        InvUtils.swap(prevSlot, false);
                    }
                    isBreakingShield = false;
                });
            } catch (InterruptedException e) {}
        }).start();
    }

    private void equipBestWeapon() {
        FindItemResult weapon = InvUtils.findInHotbar(item -> 
            item.getItem() instanceof SwordItem || 
            (item.getItem() instanceof AxeItem && !isBreakingShield)
        );
        
        if (weapon.found() && weapon.slot() != mc.player.getInventory().selectedSlot) {
            // Randomize switch timing to look human
            if (random.nextInt(3) == 0) {
                InvUtils.swap(weapon.slot(), false);
            }
        }
    }

    private void attack() {
        if (target == null || target.isRemoved()) return;
        
        // Randomize attack timing slightly
        if (random.nextInt(5) != 0) {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    @Override
    public String getInfoString() {
        if (isInCobweb()) return "§7Cobweb";
        if (target != null) return "§c" + target.getName().getString();
        return null;
    }
}
