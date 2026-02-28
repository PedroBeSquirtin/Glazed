package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

public class ShieldBreaker extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgHumanization = settings.createGroup("Humanization");
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");

    // ============ GENERAL SETTINGS ============
    private final Setting<Boolean> autoBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-break")
        .description("Automatically break shields")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> returnToPrevSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-prev-slot")
        .description("Return to previous slot after breaking")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> weaponSlot = sgGeneral.add(new IntSetting.Builder()
        .name("weapon-slot")
        .description("Hotbar slot to return to (1-9)")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(() -> !returnToPrevSlot.get())
        .build()
    );

    private final Setting<Boolean> killSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("kill-switch")
        .description("Attack after breaking shield")
        .defaultValue(true)
        .build()
    );

    // ============ HUMANIZATION SETTINGS ============
    private final Setting<HumanizationLevel> humanization = sgHumanization.add(new EnumSetting.Builder<HumanizationLevel>()
        .name("humanization")
        .description("How human-like the behavior should be")
        .defaultValue(HumanizationLevel.MEDIUM)
        .build()
    );

    private final Setting<Integer> minDelay = sgHumanization.add(new IntSetting.Builder()
        .name("min-delay")
        .description("Minimum delay between actions (ms)")
        .defaultValue(30)
        .min(10)
        .max(100)
        .sliderRange(10, 100)
        .visible(() -> humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    private final Setting<Integer> maxDelay = sgHumanization.add(new IntSetting.Builder()
        .name("max-delay")
        .description("Maximum delay between actions (ms)")
        .defaultValue(80)
        .min(20)
        .max(200)
        .sliderRange(20, 200)
        .visible(() -> humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    private final Setting<Double> reactionVariance = sgHumanization.add(new DoubleSetting.Builder()
        .name("reaction-variance")
        .description("How much reaction time varies (0 = consistent, 1 = very random)")
        .defaultValue(0.4)
        .min(0)
        .max(1)
        .sliderRange(0, 1)
        .visible(() -> humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    private final Setting<Boolean> missChance = sgHumanization.add(new BoolSetting.Builder()
        .name("miss-chance")
        .description("Occasionally miss the first hit (realistic)")
        .defaultValue(true)
        .visible(() -> humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    private final Setting<Double> missPercentage = sgHumanization.add(new DoubleSetting.Builder()
        .name("miss-percentage")
        .description("Chance to miss the first hit (%)")
        .defaultValue(5.0)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .visible(() -> missChance.get() && humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    private final Setting<Boolean> randomSwing = sgHumanization.add(new BoolSetting.Builder()
        .name("random-swing")
        .description("Randomize swing timing slightly")
        .defaultValue(true)
        .visible(() -> humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    private final Setting<Boolean> mimicFatigue = sgHumanization.add(new BoolSetting.Builder()
        .name("mimic-fatigue")
        .description("Slow down after multiple breaks")
        .defaultValue(true)
        .visible(() -> humanization.get() != HumanizationLevel.NONE)
        .build()
    );

    // ============ TARGETING SETTINGS ============
    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum range")
        .defaultValue(4.2)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> onlyPlayers = sgTargeting.add(new BoolSetting.Builder()
        .name("only-players")
        .description("Only target players")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreBehind = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-behind")
        .description("Don't break shields from behind")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireLooking = sgTargeting.add(new BoolSetting.Builder()
        .name("require-looking")
        .description("Must be looking at target")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fov = sgTargeting.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Field of view to consider 'looking'")
        .defaultValue(60.0)
        .min(10)
        .max(180)
        .sliderRange(10, 180)
        .visible(requireLooking::get)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgTargeting.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Show info in chat")
        .defaultValue(false)
        .build()
    );

    // ============ ENUMS ============
    public enum HumanizationLevel {
        NONE("None (Robot fast)"),
        LOW("Low (Slightly human)"),
        MEDIUM("Medium (Realistic)"),
        HIGH("High (Slow/casual)");

        private final String title;
        HumanizationLevel(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    // ============ STATE ============
    private PlayerEntity target = null;
    private int originalSlot = -1;
    private long lastActionTime = 0;
    private long nextActionTime = 0;
    private Phase phase = Phase.IDLE;
    private int fatigueCounter = 0;
    private Random random = new Random();
    private boolean missedThisBreak = false;

    private enum Phase {
        IDLE,
        DETECTED,
        SWITCHING_TO_AXE,
        ATTACKING,
        SWITCHING_BACK,
        DONE
    }

    public ShieldBreaker() {
        super(GlazedAddon.pvp, "shield-breaker", "Undetectable shield breaker with human-like behavior");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Update target
        updateTarget();

        // Humanized timing
        long now = System.currentTimeMillis();
        
        // Check if we should act
        if (now < nextActionTime) return;

        // State machine
        switch (phase) {
            case IDLE:
                if (shouldBreakShield()) {
                    phase = Phase.DETECTED;
                    nextActionTime = now + getHumanDelay();
                    if (chatInfo.get()) info("§7Shield detected");
                }
                break;

            case DETECTED:
                startShieldBreak();
                break;

            case SWITCHING_TO_AXE:
                // Already switched, move to attacking
                phase = Phase.ATTACKING;
                nextActionTime = now + getHumanDelay();
                break;

            case ATTACKING:
                performAttack();
                break;

            case SWITCHING_BACK:
                switchBack();
                phase = Phase.DONE;
                nextActionTime = now + getHumanDelay();
                break;

            case DONE:
                complete();
                break;
        }
    }

    private void updateTarget() {
        // Reset if no target
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.ENTITY) {
            target = null;
            return;
        }

        EntityHitResult hit = (EntityHitResult) mc.crosshairTarget;
        
        // Check if entity is valid
        if (onlyPlayers.get() && !(hit.getEntity() instanceof PlayerEntity)) {
            target = null;
            return;
        }

        if (hit.getEntity() instanceof PlayerEntity player) {
            // Check range
            double dist = mc.player.distanceTo(player);
            if (dist > range.get()) {
                target = null;
                return;
            }

            // Check if looking at target
            if (requireLooking.get() && !isLookingAt(player)) {
                target = null;
                return;
            }

            target = player;
        } else {
            target = null;
        }
    }

    private boolean isLookingAt(PlayerEntity target) {
        // Calculate angle between player's look direction and vector to target
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double dy = target.getEyeY() - mc.player.getEyeY();
        
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float requiredYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float requiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
        
        float yawDiff = Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - requiredYaw));
        float pitchDiff = Math.abs(mc.player.getPitch() - requiredPitch);
        
        return yawDiff < fov.get() / 2 && pitchDiff < fov.get() / 2;
    }

    private boolean shouldBreakShield() {
        if (target == null) return false;
        
        // Check if target is using shield
        boolean shieldActive = isShieldActive(target);
        if (!shieldActive) return false;

        // Check if behind (ineffective)
        if (ignoreBehind.get() && isBehind(target)) return false;

        return true;
    }

    private boolean isShieldActive(PlayerEntity player) {
        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();

        return (mainHand.getItem() == Items.SHIELD && player.isUsingItem() && player.getActiveHand() == Hand.MAIN_HAND) ||
               (offHand.getItem() == Items.SHIELD && player.isUsingItem() && player.getActiveHand() == Hand.OFF_HAND);
    }

    private boolean isBehind(PlayerEntity target) {
        double dx = mc.player.getX() - target.getX();
        double dz = mc.player.getZ() - target.getZ();
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        float targetYaw = target.getYaw() % 360;
        if (targetYaw < 0) targetYaw += 360;
        if (angle < 0) angle += 360;
        double diff = Math.abs(angle - targetYaw);
        return diff < 60 || diff > 300;
    }

    private void startShieldBreak() {
        // Store original slot
        originalSlot = mc.player.getInventory().selectedSlot;

        // Find axe
        FindItemResult axe = findAxe();
        if (!axe.found()) {
            if (chatInfo.get()) error("No axe found");
            phase = Phase.IDLE;
            return;
        }

        // Switch to axe
        InvUtils.swap(axe.slot(), false);
        
        // Decide if we'll miss this hit
        if (missChance.get() && humanization.get() != HumanizationLevel.NONE) {
            missedThisBreak = random.nextDouble() * 100 < missPercentage.get();
        } else {
            missedThisBreak = false;
        }

        phase = Phase.SWITCHING_TO_AXE;
        lastActionTime = System.currentTimeMillis();
        
        if (chatInfo.get()) info("§7Switching to axe...");
    }

    private FindItemResult findAxe() {
        return InvUtils.findInHotbar(itemStack -> itemStack.getItem() instanceof AxeItem);
    }

    private void performAttack() {
        if (target == null || target.isRemoved()) {
            phase = Phase.IDLE;
            return;
        }

        // Attack
        if (!missedThisBreak) {
            mc.interactionManager.attackEntity(mc.player, target);
        }
        
        // Randomize swing timing
        if (randomSwing.get() && humanization.get() != HumanizationLevel.NONE) {
            try {
                Thread.sleep(random.nextInt(20));
            } catch (InterruptedException e) {}
        }
        
        mc.player.swingHand(Hand.MAIN_HAND);
        
        // Increase fatigue counter
        if (mimicFatigue.get() && humanization.get() != HumanizationLevel.NONE) {
            fatigueCounter++;
        }

        // Determine next phase
        if (killSwitch.get() && !missedThisBreak) {
            phase = Phase.SWITCHING_BACK;
        } else {
            phase = Phase.DONE;
        }

        lastActionTime = System.currentTimeMillis();
        
        if (chatInfo.get()) {
            if (missedThisBreak) {
                info("§cMissed!");
            } else {
                info("§aShield broken!");
            }
        }
    }

    private void switchBack() {
        if (returnToPrevSlot.get() && originalSlot != -1) {
            InvUtils.swap(originalSlot, false);
        } else {
            int slot = weaponSlot.get() - 1;
            InvUtils.swap(slot, false);
        }
        
        // Attack with weapon if kill switch enabled
        if (killSwitch.get() && target != null && !target.isRemoved()) {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void complete() {
        phase = Phase.IDLE;
        nextActionTime = System.currentTimeMillis() + getCooldownDelay();
    }

    private int getHumanDelay() {
        if (humanization.get() == HumanizationLevel.NONE) {
            return 0;
        }

        // Base delay based on humanization level
        int baseMin = minDelay.get();
        int baseMax = maxDelay.get();
        
        switch (humanization.get()) {
            case LOW:
                baseMin = Math.max(10, baseMin / 2);
                baseMax = Math.max(20, baseMax / 2);
                break;
            case MEDIUM:
                // Keep as set
                break;
            case HIGH:
                baseMin = baseMin * 2;
                baseMax = baseMax * 2;
                break;
            default:
                break;
        }

        // Add variance
        int range = baseMax - baseMin;
        double varianceFactor = 1.0 + (random.nextDouble() - 0.5) * reactionVariance.get() * 2;
        int delay = (int) (baseMin + range * random.nextDouble() * varianceFactor);
        
        // Add fatigue if enabled
        if (mimicFatigue.get() && fatigueCounter > 0) {
            delay += Math.min(50, fatigueCounter * 5);
        }

        return Math.max(0, delay);
    }

    private int getCooldownDelay() {
        if (humanization.get() == HumanizationLevel.NONE) {
            return 0;
        }

        // Random cooldown between breaks
        int baseCooldown = switch (humanization.get()) {
            case LOW -> 100;
            case MEDIUM -> 200;
            case HIGH -> 400;
            default -> 0;
        };

        // Add variance
        return (int) (baseCooldown * (0.8 + random.nextDouble() * 0.4));
    }

    private void reset() {
        target = null;
        originalSlot = -1;
        phase = Phase.IDLE;
        fatigueCounter = 0;
        missedThisBreak = false;
        lastActionTime = 0;
        nextActionTime = 0;
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        return phase == Phase.IDLE ? "§aTarget" : "§6Breaking";
    }
}
