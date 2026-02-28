package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class AimAssist extends Module {
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgVisuals = settings.createGroup("Visuals");

    // ============ SIMPLIFIED AIM SETTINGS ============
    private final Setting<AimMode> aimMode = sgAim.add(new EnumSetting.Builder<AimMode>()
        .name("mode")
        .description("Aim mode")
        .defaultValue(AimMode.SMOOTH)
        .build()
    );

    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Aim speed (lower = smoother)")
        .defaultValue(0.6)
        .min(0.2)
        .max(2.5)
        .sliderRange(0.2, 2.0)
        .build()
    );

    private final Setting<Boolean> bowAimbot = sgAim.add(new BoolSetting.Builder()
        .name("bow-aimbot")
        .description("Auto aim bows at distance")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> bowRange = sgAim.add(new DoubleSetting.Builder()
        .name("bow-range")
        .description("Max bow aim range")
        .defaultValue(50.0)
        .min(10)
        .max(80)
        .sliderRange(10, 60)
        .visible(bowAimbot::get)
        .build()
    );

    private final Setting<Boolean> fullTracking = sgAim.add(new BoolSetting.Builder()
        .name("track-behind")
        .description("Track targets behind you (4 block radius)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> trackRange = sgAim.add(new DoubleSetting.Builder()
        .name("track-range")
        .description("Tracking range")
        .defaultValue(4.5)
        .min(2)
        .max(7)
        .sliderRange(2, 6)
        .visible(fullTracking::get)
        .build()
    );

    // ============ VISUAL SETTINGS ============
    private final Setting<Boolean> showPlayerBox = sgVisuals.add(new BoolSetting.Builder()
        .name("player-box")
        .description("Show [player] style box around target")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> boxColor = sgVisuals.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Box color")
        .defaultValue(new SettingColor(0, 255, 0, 120))
        .visible(showPlayerBox::get)
        .build()
    );

    private final Setting<Double> boxSize = sgVisuals.add(new DoubleSetting.Builder()
        .name("box-size")
        .description("Size of the box brackets")
        .defaultValue(0.3)
        .min(0.1)
        .max(0.8)
        .sliderRange(0.1, 0.5)
        .visible(showPlayerBox::get)
        .build()
    );

    // ============ ENUMS ============
    public enum AimMode {
        SMOOTH("Smooth"),
        FAST("Fast");

        private final String title;
        AimMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    // ============ STATE ============
    private PlayerEntity target;
    private final Random random = new Random();
    private int aimTick = 0;

    public AimAssist() {
        super(GlazedAddon.pvp, "aim-assist", "Smooth, undetectable aim assist with player box");
    }

    @Override
    public void onActivate() {
        target = null;
        aimTick = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        aimTick++;
        updateTarget();

        // Bow aimbot
        if (bowAimbot.get() && isHoldingBow() && target != null) {
            handleBowAim();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!showPlayerBox.get() || target == null) return;

        drawPlayerBox(event, target);
    }

    private void updateTarget() {
        // Find nearest valid target within tracking range
        target = mc.world.getPlayers().stream()
            .filter(p -> p != mc.player)
            .filter(p -> !Friends.get().isFriend(p))
            .filter(p -> p.isAlive())
            .filter(p -> mc.player.distanceTo(p) <= trackRange.get())
            .min((p1, p2) -> Double.compare(
                mc.player.distanceTo(p1),
                mc.player.distanceTo(p2)))
            .orElse(null);
    }

    private boolean isHoldingBow() {
        return mc.player.getMainHandStack().getItem() instanceof BowItem ||
               mc.player.getOffHandStack().getItem() instanceof BowItem;
    }

    private void handleBowAim() {
        if (target == null) return;

        double dist = mc.player.distanceTo(target);
        if (dist > bowRange.get()) return;

        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.75, 0);
        Vec3d playerPos = mc.player.getPos().add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        
        double dx = targetPos.x - playerPos.x;
        double dy = targetPos.y - playerPos.y;
        double dz = targetPos.z - playerPos.z;
        
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        // Simple arrow drop calculation
        double gravity = 0.05;
        double velocity = 3.0;
        double time = horizontalDist / velocity;
        double drop = 0.5 * gravity * time * time;
        
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy - drop, horizontalDist));
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;

        applyAim(targetYaw, targetPitch);
    }

    private void applyAim(float targetYaw, float targetPitch) {
        targetYaw = MathHelper.wrapDegrees(targetYaw);
        targetPitch = MathHelper.wrapDegrees(targetPitch);

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapDegrees(targetPitch - currentPitch);

        // Humanization - occasional micro-adjustments
        if (aimTick % 5 == 0 && random.nextBoolean()) {
            yawDiff += (random.nextFloat() - 0.5f) * 0.2f;
            pitchDiff += (random.nextFloat() - 0.5f) * 0.15f;
        }

        // Speed based on mode
        float speed = aimMode.get() == AimMode.SMOOTH ? 
            aimSpeed.get().floatValue() * 0.4f : 
            aimSpeed.get().floatValue() * 1.2f;

        float yawStep = Math.min(Math.abs(yawDiff), speed);
        float pitchStep = Math.min(Math.abs(pitchDiff), speed * 0.8f);

        // Apply rotation
        if (Math.abs(yawDiff) > 0.3f) {
            mc.player.setYaw(currentYaw + (yawDiff > 0 ? yawStep : -yawStep));
        }
        
        if (Math.abs(pitchDiff) > 0.3f) {
            mc.player.setPitch(currentPitch + (pitchDiff > 0 ? pitchStep : -pitchStep));
        }
    }

    private void drawPlayerBox(Render3DEvent event, PlayerEntity player) {
        Box box = player.getBoundingBox();
        double size = boxSize.get();
        
        double x1 = box.minX;
        double y1 = box.minY;
        double z1 = box.minZ;
        double x2 = box.maxX;
        double y2 = box.maxY;
        double z2 = box.maxZ;
        
        Color color = new Color(boxColor.get());

        // Top bracket [ at head
        event.renderer.line(x1 - size, y2 + 0.1, z1 - size, x1 - size, y2 + 0.3, z1 - size, color);
        event.renderer.line(x1 - size, y2 + 0.3, z1 - size, x1 + size, y2 + 0.3, z1 - size, color);
        
        // Top bracket ] at head
        event.renderer.line(x2 + size, y2 + 0.1, z2 + size, x2 + size, y2 + 0.3, z2 + size, color);
        event.renderer.line(x2 - size, y2 + 0.3, z2 - size, x2 + size, y2 + 0.3, z2 + size, color);

        // Bottom bracket [ at feet
        event.renderer.line(x1 - size, y1 - 0.1, z1 - size, x1 - size, y1 - 0.3, z1 - size, color);
        event.renderer.line(x1 - size, y1 - 0.3, z1 - size, x1 + size, y1 - 0.3, z1 - size, color);

        // Bottom bracket ] at feet
        event.renderer.line(x2 + size, y1 - 0.1, z2 + size, x2 + size, y1 - 0.3, z2 + size, color);
        event.renderer.line(x2 - size, y1 - 0.3, z2 - size, x2 + size, y1 - 0.3, z2 + size, color);

        // Side connectors (optional, makes it look cleaner)
        event.renderer.line(x1 - size, y2 + 0.2, z1 - size, x1 - size, y1 - 0.2, z1 - size, color);
        event.renderer.line(x2 + size, y2 + 0.2, z2 + size, x2 + size, y1 - 0.2, z2 + size, color);
    }

    @Override
    public String getInfoString() {
        return target != null ? "§a" + target.getName().getString() : null;
    }
}
