
package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class TwirlDebug extends Module {
    
    public TwirlDebug() {
        super(GlazedAddon.esp, "twirl-debug", "Detects server-side player activity with color-coded highlighting");
    }

    // ============ ACTIVITY WEIGHTS ============
    private static final int REDSTONE_ACTIVITY_BASE = 10;
    private static final int REDSTONE_FLICKER_BONUS = 5;
    private static final int CHEST_OPEN_ACTIVITY = 15;
    private static final int FURNACE_ACTIVITY = 12;
    private static final int HOPPER_ACTIVITY = 8;
    private static final int ITEM_DROP_ACTIVITY = 20;
    private static final int SPAWNER_ACTIVITY = 25;
    private static final int ENTITY_COUNT_BASE = 2;
    private static final int PLAYER_PROXIMITY_BASE = 30;
    private static final int PACKET_FLOOD_ACTIVITY = 15;
    private static final int SOUND_ACTIVITY = 10;
    private static final int BLOCK_BREAK_PLACE = 15;
    private static final int WATER_FLOW_ACTIVITY = 5;
    private static final int PISTON_ACTIVITY = 12;
    private static final int COMPARATOR_ACTIVITY = 10;
    private static final int OBSERVER_ACTIVITY = 8;
    private static final int BEACON_ACTIVITY = 20;
    private static final int CONDUIT_ACTIVITY = 18;
    private static final int PORTAL_ACTIVITY = 15;
    private static final int FARMING_ACTIVITY = 10;
    private static final int BREEDING_ACTIVITY = 25;
    private static final int VILLAGER_TRADE = 20;
    
    // ============ DECAY CONFIGURATION ============
    private static final int ACTIVITY_DECAY_RATE = 1;
    private static final int MAX_ACTIVITY = 100;
    private static final int HOTSPOT_THRESHOLD = 70;
    private static final int WARM_THRESHOLD = 40;
    
    // ============ RENDER SETTINGS ============
    private static final int RENDER_DISTANCE = 64;
    private static final double CHUNK_SLAB_HEIGHT = 0.3;
    private static final double CHUNK_SLAB_Y_OFFSET = 60;
    private static final double BLOCK_HIGHLIGHT_OFFSET = 0.05;
    
    // ============ COLORS (Light Blue to Dark Blue gradient) ============
    private static final Color LOW_ACTIVITY_FILL = new Color(173, 216, 230, 100);
    private static final Color LOW_ACTIVITY_LINE = new Color(173, 216, 230, 200);
    private static final Color MEDIUM_ACTIVITY_FILL = new Color(70, 130, 200, 140);
    private static final Color MEDIUM_ACTIVITY_LINE = new Color(70, 130, 200, 220);
    private static final Color HIGH_ACTIVITY_FILL = new Color(0, 0, 139, 180);
    private static final Color HIGH_ACTIVITY_LINE = new Color(0, 0, 139, 255);
    private static final Color EXTREME_ACTIVITY_FILL = new Color(0, 0, 80, 200);
    private static final Color EXTREME_ACTIVITY_LINE = new Color(50, 100, 255, 255);
    
    // ============ SETTINGS ============
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
    
    private final Setting<Boolean> enableChunkHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("chunk-highlight").description("Highlight entire chunks with activity").defaultValue(true).build());
    
    private final Setting<Boolean> enableBlockHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("block-highlight").description("Highlight individual active blocks").defaultValue(true).build());
    
    private final Setting<Boolean> showHotspots = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hotspots").description("Only show areas with significant activity").defaultValue(false).build());
    
    private final Setting<Boolean> detectRedstone = sgDetection.add(new BoolSetting.Builder()
        .name("detect-redstone").description("Detect redstone activity").defaultValue(true).build());
    
    private final Setting<Boolean> detectContainers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-containers").description("Detect chest, furnace, hopper activity").defaultValue(true).build());
    
    private final Setting<Boolean> detectEntities = sgDetection.add(new BoolSetting.Builder()
        .name("detect-entities").description("Detect high entity concentrations").defaultValue(true).build());
    
    private final Setting<Boolean> detectServerPackets = sgDetection.add(new BoolSetting.Builder()
        .name("detect-server-packets").description("Detect server packet activity").defaultValue(true).build());
    
    private final Setting<Boolean> detectSounds = sgDetection.add(new BoolSetting.Builder()
        .name("detect-sounds").description("Detect in-game sounds").defaultValue(true).build());
    
    private final Setting<Boolean> detectMachineActivity = sgDetection.add(new BoolSetting.Builder()
        .name("detect-machine-activity").description("Detect pistons, observers, comparators").defaultValue(true).build());
    
    private final Setting<Boolean> detectFarming = sgDetection.add(new BoolSetting.Builder()
        .name("detect-farming").description("Detect farming activities").defaultValue(true).build());
    
    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("show-tracers").description("Show tracers to high activity areas").defaultValue(false).build());
    
    private final Setting<Boolean> showActivityNumbers = sgRender.add(new BoolSetting.Builder()
        .name("show-activity-numbers").description("Show activity level numbers").defaultValue(false).build());
    
    private final Setting<Double> tracerHeight = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-height").description("Height for tracer endpoints").defaultValue(70).min(0).max(256).build());
    
    private final Setting<Boolean> notifyHotspots = sgNotifications.add(new BoolSetting.Builder()
        .name("notify-hotspots").description("Send chat notification when hotspot is found").defaultValue(true).build());
    
    private final Setting<Boolean> soundAlert = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-alert").description("Play sound when hotspot is found").defaultValue(true).build());
    
    private final Setting<Integer> notificationCooldown = sgNotifications.add(new IntSetting.Builder()
        .name("notification-cooldown").description("Cooldown between notifications (seconds)").defaultValue(30).min(5).max(300).build());
    
    // ============ DATA STRUCTURES ============
    private final Map<ChunkPos, ChunkActivity> chunkActivity = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockActivity> blockActivity = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> entityCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> lastNotificationTime = new ConcurrentHashMap<>();
    private final Set<ChunkPos> activeHotspots = ConcurrentHashMap.newKeySet();
    private final List<SoundActivity> recentSounds = new CopyOnWriteArrayList<>();
    private final Map<ChunkPos, AtomicInteger> packetCounts = new ConcurrentHashMap<>();
    private long lastCleanupTime = 0;
    
    // ============ ACTIVITY CLASSES ============
    
    private static class ChunkActivity {
        int activityLevel = 0;
        long lastUpdate = System.currentTimeMillis();
        Map<String, Integer> activitySources = new ConcurrentHashMap<>();
        
        void addActivity(int amount, String source) {
            activityLevel = Math.min(MAX_ACTIVITY, activityLevel + amount);
            activitySources.put(source, activitySources.getOrDefault(source, 0) + amount);
            lastUpdate = System.currentTimeMillis();
        }
        
        void decay() {
            activityLevel = Math.max(0, activityLevel - ACTIVITY_DECAY_RATE);
        }
        
        boolean isActive() {
            return activityLevel > 0;
        }
        
        String getTopSource() {
            return activitySources.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
        }
    }
    
    private static class BlockActivity {
        int activityLevel = 0;
        BlockPos pos;
        long lastUpdate;
        
        BlockActivity(BlockPos pos) {
            this.pos = pos;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        void addActivity(int amount) {
            activityLevel = Math.min(MAX_ACTIVITY, activityLevel + amount);
            lastUpdate = System.currentTimeMillis();
        }
    }
    
    private static class SoundActivity {
        BlockPos pos;
        long timestamp;
        int volume;
        
        SoundActivity(BlockPos pos, int volume) {
            this.pos = pos;
            this.timestamp = System.currentTimeMillis();
            this.volume = volume;
        }
    }
    
    // ============ LIFECYCLE ============
    
    @Override
    public void onActivate() {
        clearData();
        if (notifyHotspots.get() && mc.player != null) {
            ChatUtils.info("TwirlDebug", "§bActivity detection activated - monitoring server activity");
        }
    }
    
    @Override
    public void onDeactivate() {
        clearData();
    }
    
    private void clearData() {
        chunkActivity.clear();
        blockActivity.clear();
        entityCounts.clear();
        activeHotspots.clear();
        recentSounds.clear();
        packetCounts.clear();
        lastNotificationTime.clear();
    }
    
    // ============ TICK HANDLER ============
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCleanupTime > 5000) {
            decayActivity();
            updateEntityCounts();
            updateHotspots();
            lastCleanupTime = currentTime;
        }
        
        recentSounds.removeIf(s -> currentTime - s.timestamp > 10000);
        updatePlayerProximity();
    }
    
    private void decayActivity() {
        chunkActivity.values().removeIf(activity -> {
            activity.decay();
            return !activity.isActive();
        });
        
        blockActivity.values().removeIf(activity -> {
            long age = System.currentTimeMillis() - activity.lastUpdate;
            if (age > 30000) return true;
            activity.activityLevel = Math.max(0, activity.activityLevel - ACTIVITY_DECAY_RATE / 2);
            return activity.activityLevel == 0 && age > 15000;
        });
        
        packetCounts.values().forEach(count -> {
            int newCount = count.get() / 2;
            count.set(newCount);
        });
        packetCounts.entrySet().removeIf(entry -> entry.getValue().get() == 0);
    }
    
    private void updateEntityCounts() {
        if (!detectEntities.get()) return;
        
        entityCounts.clear();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            
            ChunkPos chunkPos = entity.getChunkPos();
            int count = entityCounts.getOrDefault(chunkPos, 0);
            
            int weight = 1;
            if (entity instanceof MobEntity) weight = 3;
            else if (entity instanceof PassiveEntity) weight = 2;
            else if (entity instanceof ItemEntity) weight = 4;
            else if (entity instanceof PlayerEntity) weight = 10;
            
            entityCounts.put(chunkPos, count + weight);
        }
        
        for (Map.Entry<ChunkPos, Integer> entry : entityCounts.entrySet()) {
            if (entry.getValue() > 5) {
                int activity = Math.min(30, entry.getValue() * ENTITY_COUNT_BASE);
                addChunkActivity(entry.getKey(), activity, "entities");
            }
        }
    }
    
    private void updateHotspots() {
        Set<ChunkPos> currentHotspots = new HashSet<>();
        
        for (Map.Entry<ChunkPos, ChunkActivity> entry : chunkActivity.entrySet()) {
            if (entry.getValue().activityLevel >= HOTSPOT_THRESHOLD) {
                currentHotspots.add(entry.getKey());
                
                if (notifyHotspots.get() && !activeHotspots.contains(entry.getKey())) {
                    long lastNotify = lastNotificationTime.getOrDefault(entry.getKey(), 0L);
                    long cooldownMs = notificationCooldown.get() * 1000L;
                    
                    if (System.currentTimeMillis() - lastNotify > cooldownMs) {
                        notifyHotspot(entry.getKey(), entry.getValue());
                        lastNotificationTime.put(entry.getKey(), System.currentTimeMillis());
                    }
                }
            }
        }
        
        activeHotspots.clear();
        activeHotspots.addAll(currentHotspots);
    }
    
    private void notifyHotspot(ChunkPos pos, ChunkActivity activity) {
        if (mc.player == null) return;
        
        String source = activity.getTopSource();
        String color = activity.activityLevel >= HOTSPOT_THRESHOLD ? "§c" : "§e";
        
        String message = String.format(
            "§b[TwirlDebug] §fHotspot detected at chunk §e[%d, %d]§f! Activity: §e%d§f (Source: %s%s§f)",
            pos.x, pos.z, activity.activityLevel, color, source
        );
        
        mc.player.sendMessage(Text.literal(message), false);
        
        if (soundAlert.get()) {
            float pitch = 1.0f + (activity.activityLevel - HOTSPOT_THRESHOLD) / 100.0f;
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, Math.min(2.0f, pitch));
        }
    }
    
    private void updatePlayerProximity() {
        if (mc.player == null) return;
        
        BlockPos playerPos = mc.player.getBlockPos();
        ChunkPos playerChunk = new ChunkPos(playerPos);
        
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ChunkPos checkPos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                double distance = Math.sqrt(dx*dx + dz*dz);
                int activity = (int)(PLAYER_PROXIMITY_BASE / (distance + 1));
                if (activity > 0) {
                    addChunkActivity(checkPos, activity, "player_proximity");
                }
            }
        }
    }
    
    // ============ ACTIVITY DETECTION METHODS ============
    
    private void addChunkActivity(ChunkPos pos, int amount, String source) {
        if (showHotspots.get() && amount < HOTSPOT_THRESHOLD / 2) return;
        
        ChunkActivity activity = chunkActivity.computeIfAbsent(pos, k -> new ChunkActivity());
        activity.addActivity(amount, source);
    }
    
    private void addBlockActivity(BlockPos pos, int amount) {
        BlockActivity activity = blockActivity.computeIfAbsent(pos, k -> new BlockActivity(pos));
        activity.addActivity(amount);
        
        ChunkPos chunkPos = new ChunkPos(pos);
        addChunkActivity(chunkPos, amount / 2, "block_activity");
    }
    
    // ============ PACKET DETECTION ============
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!detectServerPackets.get()) return;
        if (mc.world == null) return;
        
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            ChunkPos chunkPos = new ChunkPos(pos);
            int count = packetCounts.computeIfAbsent(chunkPos, k -> new AtomicInteger()).incrementAndGet();
            
            if (count > 10) {
                addChunkActivity(chunkPos, PACKET_FLOOD_ACTIVITY, "packet_flood");
                addBlockActivity(pos, BLOCK_BREAK_PLACE);
            }
            
            BlockState state = packet.getState();
            if (detectRedstone.get() && isRedstoneRelated(state.getBlock())) {
                addBlockActivity(pos, REDSTONE_ACTIVITY_BASE);
                addChunkActivity(chunkPos, REDSTONE_ACTIVITY_BASE / 2, "redstone");
            }
        }
        
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket) {
            addChunkActivity(new ChunkPos(mc.player.getBlockPos()), PACKET_FLOOD_ACTIVITY / 2, "chunk_delta");
        }
        
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            BlockPos pos = new BlockPos(
                (int) packet.getX(),
                (int) packet.getY(),
                (int) packet.getZ()
            );
            addBlockActivity(pos, ENTITY_COUNT_BASE * 2);
        }
        
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            if (detectContainers.get()) {
                addBlockActivity(pos, CHEST_OPEN_ACTIVITY);
            }
        }
    }
    
    // ============ REDSTONE DETECTION ============
    
    private boolean isRedstoneRelated(Block block) {
        return block == Blocks.REDSTONE_WIRE ||
               block == Blocks.REDSTONE_TORCH ||
               block == Blocks.REDSTONE_BLOCK ||
               block == Blocks.REDSTONE_LAMP ||
               block == Blocks.REPEATER ||
               block == Blocks.COMPARATOR ||
               block == Blocks.LEVER ||
               block == Blocks.STONE_BUTTON ||
               block == Blocks.OAK_BUTTON ||
               block == Blocks.PISTON ||
               block == Blocks.STICKY_PISTON ||
               block == Blocks.OBSERVER ||
               block == Blocks.DROPPER ||
               block == Blocks.DISPENSER ||
               block == Blocks.NOTE_BLOCK;
    }
    
    // ============ CROP DETECTION ============
    
    private boolean isCrop(Block block) {
        return block == Blocks.WHEAT ||
               block == Blocks.CARROTS ||
               block == Blocks.POTATOES ||
               block == Blocks.BEETROOTS ||
               block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.NETHER_WART ||
               block == Blocks.SUGAR_CANE ||
               block == Blocks.CACTUS ||
               block == Blocks.BAMBOO ||
               block == Blocks.MELON_STEM ||
               block == Blocks.PUMPKIN_STEM;
    }
    
    private boolean isFullyGrown(BlockState state, Block block) {
        if (block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES || block == Blocks.BEETROOTS) {
            return state.get(net.minecraft.state.property.Properties.AGE_7) == 7;
        }
        if (block == Blocks.SWEET_BERRY_BUSH) {
            return state.get(net.minecraft.state.property.Properties.AGE_3) == 3;
        }
        if (block == Blocks.NETHER_WART) {
            return state.get(net.minecraft.state.property.Properties.AGE_3) == 3;
        }
        if (block == Blocks.BAMBOO) {
            return state.get(net.minecraft.state.property.Properties.AGE_1) == 1;
        }
        return false;
    }
    
    // ============ RENDERING ============
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || !isActive()) return;
        
        if (enableChunkHighlight.get()) {
            renderChunkActivity(event);
        }
        
        if (enableBlockHighlight.get()) {
            renderBlockActivity(event);
        }
        
        if (showTracers.get()) {
            renderTracers(event);
        }
    }
    
    private void renderChunkActivity(Render3DEvent event) {
        // FIXED: Use getPos() that exists in this MC version
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        for (Map.Entry<ChunkPos, ChunkActivity> entry : chunkActivity.entrySet()) {
            ChunkPos pos = entry.getKey();
            ChunkActivity activity = entry.getValue();
            
            double chunkCenterX = pos.getStartX() + 8;
            double chunkCenterZ = pos.getStartZ() + 8;
            double distSq = Math.pow(chunkCenterX - cameraPos.x, 2) + Math.pow(chunkCenterZ - cameraPos.z, 2);
            if (distSq > RENDER_DISTANCE * RENDER_DISTANCE * 256) continue;
            
            Color fillColor, lineColor;
            float intensity = activity.activityLevel / (float) MAX_ACTIVITY;
            
            if (activity.activityLevel >= HOTSPOT_THRESHOLD) {
                fillColor = EXTREME_ACTIVITY_FILL;
                lineColor = EXTREME_ACTIVITY_LINE;
            } else if (activity.activityLevel >= WARM_THRESHOLD) {
                fillColor = HIGH_ACTIVITY_FILL;
                lineColor = HIGH_ACTIVITY_LINE;
            } else if (activity.activityLevel >= WARM_THRESHOLD / 2) {
                fillColor = MEDIUM_ACTIVITY_FILL;
                lineColor = MEDIUM_ACTIVITY_LINE;
            } else {
                fillColor = LOW_ACTIVITY_FILL;
                lineColor = LOW_ACTIVITY_LINE;
            }
            
            Color scaledFill = new Color(
                fillColor.r, fillColor.g, fillColor.b,
                (int)(fillColor.a * intensity)
            );
            
            int startX = pos.getStartX();
            int startZ = pos.getStartZ();
            int endX = pos.getEndX() + 1;
            int endZ = pos.getEndZ() + 1;
            double y = CHUNK_SLAB_Y_OFFSET;
            
            event.renderer.box(startX, y, startZ, endX, y + CHUNK_SLAB_HEIGHT, endZ,
                scaledFill, lineColor, ShapeMode.Both, 0);
        }
    }
    
    private void renderBlockActivity(Render3DEvent event) {
        // FIXED: Use getPos() that exists in this MC version
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        for (BlockActivity activity : blockActivity.values()) {
            double distSq = Math.pow(activity.pos.getX() + 0.5 - cameraPos.x, 2) +
                           Math.pow(activity.pos.getZ() + 0.5 - cameraPos.z, 2);
            if (distSq > RENDER_DISTANCE * RENDER_DISTANCE * 64) continue;
            
            Color fillColor, lineColor;
            float intensity = activity.activityLevel / (float) MAX_ACTIVITY;
            
            if (activity.activityLevel >= HOTSPOT_THRESHOLD) {
                fillColor = EXTREME_ACTIVITY_FILL;
                lineColor = EXTREME_ACTIVITY_LINE;
            } else if (activity.activityLevel >= WARM_THRESHOLD) {
                fillColor = HIGH_ACTIVITY_FILL;
                lineColor = HIGH_ACTIVITY_LINE;
            } else if (activity.activityLevel >= WARM_THRESHOLD / 2) {
                fillColor = MEDIUM_ACTIVITY_FILL;
                lineColor = MEDIUM_ACTIVITY_LINE;
            } else {
                fillColor = LOW_ACTIVITY_FILL;
                lineColor = LOW_ACTIVITY_LINE;
            }
            
            Color scaledFill = new Color(
                fillColor.r, fillColor.g, fillColor.b,
                (int)(fillColor.a * intensity)
            );
            
            double x = activity.pos.getX() - BLOCK_HIGHLIGHT_OFFSET;
            double y = activity.pos.getY() - BLOCK_HIGHLIGHT_OFFSET;
            double z = activity.pos.getZ() - BLOCK_HIGHLIGHT_OFFSET;
            double size = 1 + BLOCK_HIGHLIGHT_OFFSET * 2;
            
            event.renderer.box(x, y, z, x + size, y + size, z + size,
                scaledFill, lineColor, ShapeMode.Both, 0);
        }
    }
    
    private void renderTracers(Render3DEvent event) {
        if (activeHotspots.isEmpty()) return;
        
        Vec3d startPos = mc.player.getCameraPosVec(event.tickDelta);
        startPos = startPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        
        for (ChunkPos hotspot : activeHotspots) {
            ChunkActivity activity = chunkActivity.get(hotspot);
            if (activity == null) continue;
            
            double targetX = hotspot.getStartX() + 8;
            double targetZ = hotspot.getStartZ() + 8;
            double targetY = tracerHeight.get();
            
            Color color;
            if (activity.activityLevel >= HOTSPOT_THRESHOLD) {
                color = EXTREME_ACTIVITY_LINE;
            } else if (activity.activityLevel >= WARM_THRESHOLD) {
                color = HIGH_ACTIVITY_LINE;
            } else {
                color = MEDIUM_ACTIVITY_LINE;
            }
            
            event.renderer.line(startPos.x, startPos.y, startPos.z, targetX, targetY, targetZ, color);
        }
    }
    
    @Override
    public String getInfoString() {
        return String.format("§b%d hotspots §7| §3%d chunks",
            activeHotspots.size(),
            chunkActivity.size());
    }
}
