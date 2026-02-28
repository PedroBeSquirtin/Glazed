package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AimAssist extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim Settings");
    private final SettingGroup sgBypass = settings.createGroup("Grim Bypass");
    private final SettingGroup sgVisuals = settings.createGroup("Visuals");

    // ============ GENERAL SETTINGS ============
    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat feedback.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to aim at.")
        .defaultValue(Set.of(EntityType.PLAYER))
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range at which an entity can be targeted.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 10.0)
        .build()
    );

    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Will only aim entities in the FOV.")
        .defaultValue(180.0)
        .min(0.0)
        .max(360.0)
        .sliderRange(0.0, 360.0)
        .build()
    );

    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-walls")
        .description("Whether or not to ignore aiming through walls.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.LowestHealth)
        .build()
    );

    private final Setting<Target> bodyTarget = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("aim-target")
        .description("Which part of the entities body to aim at.")
        .defaultValue(Target.Body)
        .build()
    );

    // ============ AIM SETTINGS ============
    private final Setting<AimMode> aimMode = sgAim.add(new EnumSetting.Builder<AimMode>()
        .name("aim-mode")
        .description("How to aim at targets.")
        .defaultValue(AimMode.SMOOTH)
        .build()
    );

    private final Setting<Double> smoothSpeed = sgAim.add(new DoubleSetting.Builder()
        .name("smooth-speed")
        .description("How fast to aim (lower = smoother).")
        .defaultValue(0.35)
        .min(0.1)
        .max(2.0)
        .sliderRange(0.1, 1.0)
        .visible(() -> aimMode.get() == AimMode.SMOOTH)
        .build()
    );

    private final Setting<Double> acceleration = sgAim.add(new DoubleSetting.Builder()
        .name("acceleration")
        .description("Aim acceleration (0 = constant speed).")
        .defaultValue(0.15)
        .min(0.0)
        .max(0.5)
        .sliderRange(0.0, 0.5)
        .visible(() -> aimMode.get() == AimMode.SMOOTH)
        .build()
    );

    private final Setting<Double> maxTurnSpeed = sgAim.add(new DoubleSetting.Builder()
        .name("max-turn-speed")
        .description("Maximum turn speed per tick.")
        .defaultValue(2.5)
        .min(0.5)
        .max(10.0)
        .sliderRange(0.5, 5.0)
        .build()
    );

    private final Setting<Boolean> instant = sgAim.add(new BoolSetting.Builder()
        .name("instant-look")
        .description("Instantly looks at the entity.")
        .defaultValue(false)
        .visible(() -> aimMode.get() == AimMode.INSTANT)
        .build()
    );

    // ============ GRIM BYPASS SETTINGS ============
    private final Setting<Boolean> randomizeRotation = sgBypass.add(new BoolSetting.Builder()
        .name("randomize-rotation")
        .description("Add random noise to rotations.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> randomNoise = sgBypass.add(new DoubleSetting.Builder()
        .name("random-noise")
        .description("Amount of random noise.")
        .defaultValue(0.25)
        .min(0.0)
        .max(2.0)
        .sliderRange(0.0, 1.0)
        .visible(randomizeRotation::get)
        .build()
    );

    private final Setting<Boolean> microAdjustments = sgBypass.add(new BoolSetting.Builder()
        .name("micro-adjustments")
        .description("Make tiny adjustments to look more human.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> microInterval = sgBypass.add(new IntSetting.Builder()
        .name("micro-interval")
        .description("Ticks between micro-adjustments.")
        .defaultValue(3)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .visible(microAdjustments::get)
        .build()
    );

    private final Setting<Boolean> prediction = sgBypass.add(new BoolSetting.Builder()
        .name("prediction")
        .description("Predict target movement for smoother aim.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> predictionFactor = sgBypass.add(new DoubleSetting.Builder()
        .name("prediction-factor")
        .description("How much to predict target movement.")
        .defaultValue(0.3)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .visible(prediction::get)
        .build()
    );

    // ============ VISUAL SETTINGS ============
    private final Setting<Boolean> showBrackets = sgVisuals.add(new BoolSetting.Builder()
        .name("show-brackets")
        .description("Show [player] style brackets around target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showOnAllPlayers = sgVisuals.add(new BoolSetting.Builder()
        .name("show-on-all")
        .description("Show brackets on all players, not just target.")
        .defaultValue(false)
        .visible(showBrackets::get)
        .build()
    );

    private final Setting<SettingColor> bracketColor = sgVisuals.add(new ColorSetting.Builder()
        .name("bracket-color")
        .description("Color of the player brackets.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(showBrackets::get)
        .build()
    );

    private final Setting<Double> bracketScale = sgVisuals.add(new DoubleSetting.Builder()
        .name("bracket-scale")
        .description("Size of the brackets.")
        .defaultValue(0.5)
        .min(0.2)
        .max(1.5)
        .sliderRange(0.2, 1.5)
        .visible(showBrackets::get)
        .build()
    );

    private final Setting<Double> bracketThickness = sgVisuals.add(new DoubleSetting.Builder()
        .name("bracket-thickness")
        .description("Thickness of bracket lines.")
        .defaultValue(2.0)
        .min(1.0)
        .max(5.0)
        .sliderRange(1.0, 3.0)
        .visible(showBrackets::get)
        .build()
    );

    // ============ ENUMS ============
    public enum AimMode {
        SMOOTH("Smooth"),
        INSTANT("Instant");

        private final String title;
        AimMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    // ============ STATE VARIABLES ============
    private Entity target;
    private final Random random = new Random();
    private final ConcurrentHashMap<PlayerEntity, long[]> lastPositions = new ConcurrentHashMap<>();
    private int microTick = 0;
    private float currentYawSpeed = 0;
    private float currentPitchSpeed = 0;

    // Smoothing variables
    private float targetYaw = 0;
    private float targetPitch = 0;
    private float lastYaw = 0;
    private float lastPitch = 0;

    public AimAssist() {
        super(GlazedAddon.pvp, "aim-assist", "Smooth, undetectable aim assist with player brackets.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            if (notifications.get()) ChatUtils.error("Cannot activate AimAssist: Player or world is null!");
            toggle();
            return;
        }
        target = null;
        lastPositions.clear();
        microTick = 0;
        currentYawSpeed = 0;
        currentPitchSpeed = 0;
        if (notifications.get()) ChatUtils.info("AimAssist activated.");
    }

    @Override
    public void onDeactivate() {
        target = null;
        lastPositions.clear();
        if (notifications.get()) ChatUtils.info("AimAssist deactivated.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        microTick++;
        
        // Find target
        target = TargetUtils.get(entity -> {
            if (!entity.isAlive()) return false;
            if (!PlayerUtils.isWithin(entity, range.get())) return false;
            if (!ignoreWalls.get() && !PlayerUtils.canSeeEntity(entity)) return false;
            if (entity == mc.player || !entities.get().contains(entity.getType())) return false;
            if (entity instanceof PlayerEntity && !Friends.get().shouldAttack((PlayerEntity) entity)) return false;
            return isInFov(entity, fov.get());
        }, priority.get());

        // Store player positions for prediction
        if (prediction.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                long[] positions = lastPositions.computeIfAbsent(player, k -> new long[3]);
                positions[2] = positions[1];
                positions[1] = positions[0];
                positions[0] = Double.doubleToLongBits(player.getX()) + 
                               Double.doubleToLongBits(player.getZ()) * 31;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Draw brackets on players
        if (showBrackets.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                if (!showOnAllPlayers.get() && player != target) continue;
                if (Friends.get().isFriend(player)) continue;
                if (player.distanceTo(mc.player) > range.get() * 1.5) continue;
                
                drawPlayerBrackets(event, player);
            }
        }

        // Aim at target
        if (target != null) {
            aimSmooth(target, event.tickDelta);
        }
    }

    private void aimSmooth(Entity target, float delta) {
        // Get target position with prediction
        Vec3d targetPos = getPredictedTargetPosition(target, delta);
        
        // Adjust for body part
        switch (bodyTarget.get()) {
            case Head -> targetPos = targetPos.add(0, target.getEyeHeight(target.getPose()), 0);
            case Body -> targetPos = targetPos.add(0, target.getEyeHeight(target.getPose()) * 0.6, 0);
            case Feet -> targetPos = targetPos.add(0, 0.1, 0);
        }

        // Calculate angles
        double deltaX = targetPos.x - mc.player.getX();
        double deltaY = targetPos.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double deltaZ = targetPos.z - mc.player.getZ();
        
        double horizontalDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        
        float targetYaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontalDist));

        // Add random noise
        if (randomizeRotation.get()) {
            targetYaw += (random.nextFloat() - 0.5f) * randomNoise.get().floatValue();
            targetPitch += (random.nextFloat() - 0.5f) * randomNoise.get().floatValue();
        }

        // Normalize angles
        targetYaw = MathHelper.wrapDegrees(targetYaw);
        targetPitch = MathHelper.wrapDegrees(targetPitch);

        // Calculate current angles
        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        // Calculate difference
        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapDegrees(targetPitch - currentPitch);

        // Smooth aiming
        if (aimMode.get() == AimMode.SMOOTH) {
            // Acceleration-based smoothing
            float targetYawSpeed = (float) (Math.abs(yawDiff) * smoothSpeed.get());
            float targetPitchSpeed = (float) (Math.abs(pitchDiff) * smoothSpeed.get());
            
            // Apply acceleration
            if (acceleration.get() > 0) {
                if (targetYawSpeed > currentYawSpeed) {
                    currentYawSpeed += acceleration.get().floatValue();
                    if (currentYawSpeed > targetYawSpeed) currentYawSpeed = targetYawSpeed;
                } else {
                    currentYawSpeed -= acceleration.get().floatValue();
                    if (currentYawSpeed < targetYawSpeed) currentYawSpeed = targetYawSpeed;
                }
                
                if (targetPitchSpeed > currentPitchSpeed) {
                    currentPitchSpeed += acceleration.get().floatValue();
                    if (currentPitchSpeed > targetPitchSpeed) currentPitchSpeed = targetPitchSpeed;
                } else {
                    currentPitchSpeed -= acceleration.get().floatValue();
                    if (currentPitchSpeed < targetPitchSpeed) currentPitchSpeed = targetPitchSpeed;
                }
            } else {
                currentYawSpeed = targetYawSpeed;
                currentPitchSpeed = targetPitchSpeed;
            }

            // Apply speed limits
            float yawStep = Math.min(Math.abs(yawDiff), currentYawSpeed);
            float pitchStep = Math.min(Math.abs(pitchDiff), currentPitchSpeed);
            
            yawStep = Math.min(yawStep, maxTurnSpeed.get().floatValue());
            pitchStep = Math.min(pitchStep, maxTurnSpeed.get().floatValue());

            // Apply rotation
            if (Math.abs(yawDiff) > 0.1f) {
                mc.player.setYaw(currentYaw + (yawDiff > 0 ? yawStep : -yawStep));
            }
            
            if (Math.abs(pitchDiff) > 0.1f) {
                mc.player.setPitch(currentPitch + (pitchDiff > 0 ? pitchStep : -pitchStep));
            }

            // Micro-adjustments for humanization
            if (microAdjustments.get() && microTick % microInterval.get() == 0) {
                if (Math.abs(yawDiff) < 1.0f && Math.random() > 0.7) {
                    mc.player.setYaw(currentYaw + (random.nextFloat() - 0.5f) * 0.2f);
                }
                if (Math.abs(pitchDiff) < 1.0f && Math.random() > 0.7) {
                    mc.player.setPitch(currentPitch + (random.nextFloat() - 0.5f) * 0.1f);
                }
            }
        } else {
            // Instant mode
            if (instant.get()) {
                mc.player.setYaw(targetYaw);
                mc.player.setPitch(targetPitch);
            }
        }
    }

    private Vec3d getPredictedTargetPosition(Entity target, float delta) {
        if (!prediction.get() || !(target instanceof PlayerEntity)) {
            return target.getLerpedPos(delta);
        }

        PlayerEntity player = (PlayerEntity) target;
        long[] positions = lastPositions.get(player);
        
        if (positions == null || positions[0] == 0 || positions[1] == 0) {
            return target.getLerpedPos(delta);
        }

        // Calculate velocity from position history
        double x1 = Double.longBitsToDouble(positions[0]);
        double x2 = Double.longBitsToDouble(positions[1]);
        
        if (positions[2] != 0) {
            double x3 = Double.longBitsToDouble(positions[2]);
            double velX = (x1 - x3) / 2.0;
            double predictedX = target.getX() + velX * predictionFactor.get();
            
            return new Vec3d(
                predictedX,
                target.getY(),
                target.getZ()
            );
        }

        return target.getLerpedPos(delta);
    }

    private void drawPlayerBrackets(Render3DEvent event, PlayerEntity player) {
        // Get player bounding box
        Box box = player.getBoundingBox();
        double x = box.minX;
        double y = box.minY;
        double z = box.minZ;
        double width = box.maxX - box.minX;
        double height = box.maxY - box.minY;
        double depth = box.maxZ - box.minZ;

        // Scale brackets
        double scale = bracketScale.get();
        double offset = 0.2 * scale;
        
        // Colors
        Color color = new Color(bracketColor.get());
        
        // Draw top bracket [ at head level
        event.renderer.line(
            x - offset, y + height + 0.1, z - offset,
            x - offset, y + height + 0.3, z - offset,
            color
        );
        event.renderer.line(
            x - offset, y + height + 0.3, z - offset,
            x + offset, y + height + 0.3, z - offset,
            color
        );
        
        // Draw top bracket ] at head level
        event.renderer.line(
            x + width + offset, y + height + 0.1, z + width + offset,
            x + width + offset, y + height + 0.3, z + width + offset,
            color
        );
        event.renderer.line(
            x + width - offset, y + height + 0.3, z + width - offset,
            x + width + offset, y + height + 0.3, z + width + offset,
            color
        );

        // Draw bottom bracket [ at feet level
        event.renderer.line(
            x - offset, y - 0.1, z - offset,
            x - offset, y - 0.3, z - offset,
            color
        );
        event.renderer.line(
            x - offset, y - 0.3, z - offset,
            x + offset, y - 0.3, z - offset,
            color
        );

        // Draw bottom bracket ] at feet level
        event.renderer.line(
            x + width + offset, y - 0.1, z + width + offset,
            x + width + offset, y - 0.3, z + width + offset,
            color
        );
        event.renderer.line(
            x + width - offset, y - 0.3, z + width - offset,
            x + width + offset, y - 0.3, z + width + offset,
            color
        );

        // Draw connecting lines on sides (optional, makes it look cleaner)
        event.renderer.line(
            x - offset, y + height + 0.2, z - offset,
            x - offset, y - 0.2, z - offset,
            color
        );
        event.renderer.line(
            x + width + offset, y + height + 0.2, z + width + offset,
            x + width + offset, y - 0.2, z + width + offset,
            color
        );
    }

    private boolean isInFov(Entity entity, double fov) {
        if (fov >= 360.0) return true;
        
        Vec3d entityPos = entity.getPos();
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = entityPos.subtract(playerPos).normalize();
        
        double yaw = Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
        double pitch = -Math.toDegrees(Math.asin(direction.y));
        
        double yawDiff = MathHelper.wrapDegrees(yaw - mc.player.getYaw());
        double pitchDiff = MathHelper.wrapDegrees(pitch - mc.player.getPitch());
        
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff) <= fov / 2.0;
    }

    @Override
    public String getInfoString() {
        if (target != null) {
            return "§a" + EntityUtils.getName(target);
        }
        return null;
    }
}
