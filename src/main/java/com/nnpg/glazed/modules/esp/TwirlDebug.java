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
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TwirlDebug extends Module {
    
    public TwirlDebug() {
        super(GlazedAddon.esp, "twirl-debug", Finding bases");
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
    // Low activity - Light Blue
    private static final Color LOW_ACTIVITY_FILL = new Color(173, 216, 230, 100);  // Light Blue
    private static final Color LOW_ACTIVITY_LINE = new Color(173, 216, 230, 200);
    
    // Medium activity - Medium Blue
    private static final Color MEDIUM_ACTIVITY_FILL = new Color(70, 130, 200, 140);
    private static final Color MEDIUM_ACTIVITY_LINE = new Color(70, 130, 200, 220);
    
    // High activity - Dark Blue
    private static final Color HIGH_ACTIVITY_FILL = new Color(0, 0, 139, 180);
    private static final Color HIGH_ACTIVITY_LINE = new Color(0, 0, 139, 255);
    
    // Extreme activity - Deep Blue
    private static final Color EXTREME_ACTIVITY_FILL = new Color(0, 0, 80, 200);
    private static final Color EXTREME_ACTIVITY_LINE = new Color(50, 100, 255, 255);
    
    // ============ SETTINGS ============
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
    
    // General settings
    private final Setting<Boolean> enableChunkHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("chunk-highlight")
        .description("Highlight entire chunks with activity")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> enableBlockHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("block-highlight")
        .description("Highlight individual active blocks")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> showHotspots = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hotspots")
        .description("Only show areas with significant activity")
        .defaultValue(false)
        .build()
    );
    
    // Detection settings
    private final Setting<Boolean> detectRedstone = sgDetection.add(new BoolSetting.Builder()
        .name("detect-redstone")
        .description("Detect redstone activity")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectContainers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-containers")
        .description("Detect chest, furnace, hopper activity")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectEntities = sgDetection.add(new BoolSetting.Builder()
        .name("detect-entities")
        .description("Detect high entity concentrations")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectServerPackets = sgDetection.add(new BoolSetting.Builder()
        .name("detect-server-packets")
        .description("Detect server packet activity")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectSounds = sgDetection.add(new BoolSetting.Builder()
        .name("detect-sounds")
        .description("Detect in-game sounds (shows activity area)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectMachineActivity = sgDetection.add(new BoolSetting.Builder()
        .name("detect-machine-activity")
        .description("Detect pistons, observers, comparators")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectFarming = sgDetection.add(new BoolSetting.Builder()
        .name("detect-farming")
        .description("Detect farming activities (crops, animals)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectVillagers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-villagers")
        .description("Detect villager trading and breeding")
        .defaultValue(true)
        .build()
    );
    
    // Render settings
    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("show-tracers")
        .description("Show tracers to high activity areas")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Boolean> showActivityNumbers = sgRender.add(new BoolSetting.Builder()
        .name("show-activity-numbers")
        .description("Show activity level numbers")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Double> tracerHeight = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-height")
        .description("Height for tracer endpoints")
        .defaultValue(70)
        .min(0)
        .max(256)
        .build()
    );
    
    // Notification settings
    private final Setting<Boolean> notifyHotspots = sgNotifications.add(new BoolSetting.Builder()
        .name("notify-hotspots")
        .description("Send chat notification when hotspot is found")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> soundAlert = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-alert")
        .description("Play sound when hotspot is found")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Integer> notificationCooldown = sgNotifications.add(new IntSetting.Builder()
        .name("notification-cooldown")
        .description("Cooldown between notifications (seconds)")
        .defaultValue(30)
        .min(5)
        .max(300)
        .build()
    );
    
    // ============ DATA STRUCTURES ============
    private final Map<ChunkPos, ChunkActivity> chunkActivity = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockActivity> blockActivity = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> entityCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> lastNotificationTime = new ConcurrentHashMap<>();
    private final Set<ChunkPos> activeHotspots = ConcurrentHashMap.newKeySet();
    private final List<SoundActivity> recentSounds = new CopyOnWriteArrayList<>();
    
    // Packet tracking
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
        
        // Decay activity every 5 seconds
        if (currentTime - lastCleanupTime > 5000) {
            decayActivity();
            updateEntityCounts();
            updateHotspots();
            lastCleanupTime = currentTime;
        }
        
        // Cleanup old sounds (older than 10 seconds)
        recentSounds.removeIf(s -> currentTime - s.timestamp > 10000);
        
        // Update player proximity detection
        updatePlayerProximity();
    }
    
    private void decayActivity() {
        // Decay chunk activity
        chunkActivity.values().removeIf(activity -> {
            activity.decay();
            return !activity.isActive();
        });
        
        // Decay block activity
        blockActivity.values().removeIf(activity -> {
            long age = System.currentTimeMillis() - activity.lastUpdate;
            if (age > 30000) return true;
            activity.activityLevel = Math.max(0, activity.activityLevel - ACTIVITY_DECAY_RATE / 2);
            return activity.activityLevel == 0 && age > 15000;
        });
        
        // Decay packet counts
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
            
            // Weight different entity types
            int weight = 1;
            if (entity instanceof MobEntity) weight = 3;
            else if (entity instanceof PassiveEntity) weight = 2;
            else if (entity instanceof ItemEntity) weight = 4; // Items on ground = high suspicion
            else if (entity instanceof PlayerEntity) weight = 10; // Players = highest suspicion
            
            entityCounts.put(chunkPos, count + weight);
        }
        
        // Add activity based on entity counts
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
                
                // Check if we should notify about new hotspot
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
        
        // Check nearby chunks for player activity
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
        
        // Also add to chunk activity
        ChunkPos chunkPos = new ChunkPos(pos);
        addChunkActivity(chunkPos, amount / 2, "block_activity");
    }
    
    // ============ PACKET DETECTION ============
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!detectServerPackets.get()) return;
        if (mc.world == null) return;
        
        // Track packet frequency per chunk
        String packetName = event.packet.getClass().getSimpleName();
        
        // Block updates
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            ChunkPos chunkPos = new ChunkPos(pos);
            int count = packetCounts.computeIfAbsent(chunkPos, k -> new AtomicInteger()).incrementAndGet();
            
            if (count > 10) {
                addChunkActivity(chunkPos, PACKET_FLOOD_ACTIVITY, "packet_flood");
                addBlockActivity(pos, BLOCK_BREAK_PLACE);
            }
            
            // Check what kind of block is being updated
            BlockState state = packet.getState();
            if (detectRedstone.get() && isRedstoneRelated(state.getBlock())) {
                addBlockActivity(pos, REDSTONE_ACTIVITY_BASE);
                addChunkActivity(chunkPos, REDSTONE_ACTIVITY_BASE / 2, "redstone");
            }
        }
        
        // Chunk delta updates (multiple block changes)
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            // Use reflection to get the updated blocks
            try {
                var field = packet.getClass().getDeclaredField("updates");
                field.setAccessible(true);
                Object updates = field.get(packet);
                if (updates != null) {
                    addChunkActivity(new ChunkPos(mc.player.getBlockPos()), PACKET_FLOOD_ACTIVITY / 2, "chunk_delta");
                }
            } catch (Exception ignored) {}
        }
        
        // Entity spawns (could be player activity)
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            try {
                var entityTypeField = packet.getClass().getDeclaredField("entityTypeId");
                entityTypeField.setAccessible(true);
                int entityType = (int) entityTypeField.get(packet);
                
                BlockPos pos = new BlockPos(
                    (int) packet.getX(),
                    (int) packet.getY(),
                    (int) packet.getZ()
                );
                addBlockActivity(pos, ENTITY_COUNT_BASE * 2);
            } catch (Exception ignored) {}
        }
        
        // Block entity updates (chests, furnaces, etc.)
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
               block == Blocks.REDSTONE_REPEATER ||
               block == Blocks.REDSTONE_COMPARATOR ||
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
    
    // ============ CHUNK SCANNING ============
    
    private void scanChunkForActivity(WorldChunk chunk) {
        if (mc.world == null) return;
        
        ChunkPos pos = chunk.getPos();
        
        // Scan blocks in chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = mc.world.getBottomY(); y <= mc.world.getTopYInclusive(); y++) {
                    BlockPos blockPos = new BlockPos(pos.getStartX() + x, y, pos.getStartZ() + z);
                    BlockState state = mc.world.getBlockState(blockPos);
                    Block block = state.getBlock();
                    
                    // Detect redstone components
                    if (detectRedstone.get() && isRedstoneRelated(block)) {
                        // Check if redstone is powered
                        if (block == Blocks.REDSTONE_WIRE) {
                            int power = state.get(RedstoneWireBlock.POWER);
                            if (power > 0) {
                                int activity = REDSTONE_ACTIVITY_BASE + (power * REDSTONE_FLICKER_BONUS / 15);
                                addBlockActivity(blockPos, activity);
                            }
                        } else {
                            addBlockActivity(blockPos, REDSTONE_ACTIVITY_BASE);
                        }
                    }
                    
                    // Detect containers
                    if (detectContainers.get()) {
                        BlockEntity blockEntity = mc.world.getBlockEntity(blockPos);
                        if (blockEntity instanceof ChestBlockEntity ||
                            blockEntity instanceof FurnaceBlockEntity ||
                            blockEntity instanceof HopperBlockEntity) {
                            addBlockActivity(blockPos, CHEST_OPEN_ACTIVITY / 2);
                        }
                    }
                    
                    // Detect machine activity
                    if (detectMachineActivity.get()) {
                        if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) {
                            addBlockActivity(blockPos, PISTON_ACTIVITY);
                        }
                        if (block == Blocks.OBSERVER) {
                            addBlockActivity(blockPos, OBSERVER_ACTIVITY);
                        }
                        if (block == Blocks.COMPARATOR) {
                            addBlockActivity(blockPos, COMPARATOR_ACTIVITY);
                        }
                    }
                    
                    // Detect farming
                    if (detectFarming.get()) {
                        if (isCrop(block)) {
                            // Check if crop is fully grown
                            if (isFullyGrown(state, block)) {
                                addBlockActivity(blockPos, FARMING_ACTIVITY);
                            }
                        }
                    }
                    
                    // Detect high-value blocks
                    if (block == Blocks.BEACON) {
                        addBlockActivity(blockPos, BEACON_ACTIVITY);
                    }
                    if (block == Blocks.CONDUIT) {
                        addBlockActivity(blockPos, CONDUIT_ACTIVITY);
                    }
                    if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL) {
                        addBlockActivity(blockPos, PORTAL_ACTIVITY);
                    }
                    if (block == Blocks.SPAWNER) {
                        addBlockActivity(blockPos, SPAWNER_ACTIVITY);
                    }
                }
            }
        }
    }
    
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
    
    // ============ SOUND DETECTION ============
    
    @EventHandler
    private void onSoundPlayed(net.minecraft.client.sound.SoundInstance sound) {
        if (!detectSounds.get()) return;
        if (mc.player == null) return;
        
        // Try to get position of sound
        try {
            var posField = sound.getClass().getDeclaredField("x");
            posField.setAccessible(true);
            double x = (double) posField.get(sound);
            double y = (double) sound.getClass().getDeclaredField("y").get(sound);
            double z = (double) sound.getClass().getDeclaredField("z").get(sound);
            
            BlockPos soundPos = new BlockPos((int) x, (int) y, (int) z);
            float volume = sound.getVolume();
            int activity = SOUND_ACTIVITY + (int)(volume * 10);
            
            // Add sound activity
            recentSounds.add(new SoundActivity(soundPos, activity));
            addBlockActivity(soundPos, activity);
            
            // Check for suspicious sounds (explosions, anvil, etc.)
            String soundId = sound.getId().toString();
            if (soundId.contains("explosion") || soundId.contains("tnt")) {
                addBlockActivity(soundPos, 50); // Explosions = high suspicion
            }
            if (soundId.contains("anvil")) {
                addBlockActivity(soundPos, 20); // Anvil use
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============ RENDERING ============
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || !isActive()) return;
        
        // Render chunk highlights
        if (enableChunkHighlight.get()) {
            renderChunkActivity(event);
        }
        
        // Render block highlights
        if (enableBlockHighlight.get()) {
            renderBlockActivity(event);
        }
        
        // Render tracers
        if (showTracers.get()) {
            renderTracers(event);
        }
        
        // Render activity numbers
        if (showActivityNumbers.get()) {
            renderActivityNumbers(event);
        }
    }
    
    private void renderChunkActivity(Render3DEvent event) {
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        for (Map.Entry<ChunkPos, ChunkActivity> entry : chunkActivity.entrySet()) {
            ChunkPos pos = entry.getKey();
            ChunkActivity activity = entry.getValue();
            
            // Check render distance
            double chunkCenterX = pos.getStartX() + 8;
            double chunkCenterZ = pos.getStartZ() + 8;
            double distSq = Math.pow(chunkCenterX - cameraPos.x, 2) + Math.pow(chunkCenterZ - cameraPos.z, 2);
            if (distSq > RENDER_DISTANCE * RENDER_DISTANCE * 256) continue;
            
            // Get color based on activity level
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
            
            // Apply intensity scaling to alpha
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
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        for (BlockActivity activity : blockActivity.values()) {
            // Check render distance
            double distSq = Math.pow(activity.pos.getX() + 0.5 - cameraPos.x, 2) +
                           Math.pow(activity.pos.getZ() + 0.5 - cameraPos.z, 2);
            if (distSq > RENDER_DISTANCE * RENDER_DISTANCE * 64) continue;
            
            // Get color based on activity level
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
    
    private void renderActivityNumbers(Render3DEvent event) {
        if (!showActivityNumbers.get()) return;
        
        for (Map.Entry<ChunkPos, ChunkActivity> entry : chunkActivity.entrySet()) {
            ChunkPos pos = entry.getKey();
            ChunkActivity activity = entry.getValue();
            
            if (activity.activityLevel < WARM_THRESHOLD / 2) continue;
            
            double x = pos.getStartX() + 8;
            double z = pos.getStartZ() + 8;
            double y = CHUNK_SLAB_Y_OFFSET + CHUNK_SLAB_HEIGHT + 0.5;
            
            String text = String.valueOf(activity.activityLevel);
            Color color = activity.activityLevel >= HOTSPOT_THRESHOLD ? 
                EXTREME_ACTIVITY_LINE : HIGH_ACTIVITY_LINE;
            
            event.renderer.text(text, x, y, z, true, color);
        }
    }
    
    @Override
    public String getInfoString() {
        return String.format("§b%d hotspots §7| §3%d chunks",
            activeHotspots.size(),
            chunkActivity.size());
    }
}
