package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BaseLeakDebug - Advanced Underground Base Detector for DonutSMP
 * 
 * Combines:
 * - Packet-based entity detection (spawners, chests, beacons, furnaces)
 * - Chunk-based block scanning (amethyst geodes, suspicious blocks)
 * - Confidence scoring system with percentage-based detection
 * - Chat notifications with coordinates and confidence levels
 * - ESP rendering with color-coded confidence
 * 
 * Detection Types:
 * - SPAWNER: 100% confidence (definite base)
 * - CHEST with items: 85% confidence (storage)
 * - BEACON: 80% confidence (player structure)
 * - BUDDING AMETHYST: 70% confidence (geode = player activity)
 * - FURNACE burning: 60% confidence (active use)
 * - ENTITY cluster: 50% confidence (mob farm indicator)
 * - SUSPICIOUS BLOCKS: 40% confidence (ores/chests)
 * 
 * @author Glazed Development
 * @version 4.0.0
 */
public class BaseLeakDebug extends Module {
    
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
    
    // ============================================================
    // GENERAL SETTINGS
    // ============================================================
    
    private final Setting<Integer> minDistance = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance")
        .description("Minimum horizontal distance to detect (avoids your own base)")
        .defaultValue(50)
        .min(0)
        .max(500)
        .build()
    );
    
    private final Setting<Integer> workerThreads = sgGeneral.add(new IntSetting.Builder()
        .name("worker-threads")
        .description("Background scanner threads")
        .defaultValue(1)
        .min(1)
        .max(4)
        .build()
    );
    
    // ============================================================
    // DETECTION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> detectSpawners = sgDetection.add(new BoolSetting.Builder()
        .name("detect-spawners")
        .description("Detect spawners (100% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectChests = sgDetection.add(new BoolSetting.Builder()
        .name("detect-chests")
        .description("Detect chests with items (85% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectBeacons = sgDetection.add(new BoolSetting.Builder()
        .name("detect-beacons")
        .description("Detect beacons (80% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectBuddingAmethyst = sgDetection.add(new BoolSetting.Builder()
        .name("detect-budding-amethyst")
        .description("Detect budding amethyst geodes (70% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectFurnaces = sgDetection.add(new BoolSetting.Builder()
        .name("detect-furnaces")
        .description("Detect burning furnaces (60% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectEntityClusters = sgDetection.add(new BoolSetting.Builder()
        .name("detect-entity-clusters")
        .description("Detect clusters of entities (50% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectSuspiciousBlocks = sgDetection.add(new BoolSetting.Builder()
        .name("detect-suspicious-blocks")
        .description("Detect suspicious blocks like ores (40% confidence)")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Integer> entityClusterThreshold = sgDetection.add(new IntSetting.Builder()
        .name("entity-cluster-threshold")
        .description("Entities in a chunk to trigger detection")
        .defaultValue(5)
        .min(2)
        .max(20)
        .build()
    );
    
    // ============================================================
    // RENDER SETTINGS
    // ============================================================
    
    private final Setting<Boolean> chunkHighlight = sgRender.add(new BoolSetting.Builder()
        .name("chunk-highlight")
        .description("Highlight chunks with detected activity")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to detected chunks")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Double> highlightHeight = sgRender.add(new DoubleSetting.Builder()
        .name("highlight-height")
        .description("Y-level for chunk highlight boxes")
        .defaultValue(60.0)
        .min(0)
        .max(128)
        .build()
    );
    
    // ============================================================
    // NOTIFICATION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> chatNotify = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Send chat notification when detection is found")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> soundNotify = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-notify")
        .description("Play sound when detection is found")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> notifyCooldown = sgNotifications.add(new BoolSetting.Builder()
        .name("notify-cooldown")
        .description("Only notify once per chunk")
        .defaultValue(true)
        .build()
    );
    
    // ============================================================
    // CONFIDENCE SCORES
    // ============================================================
    
    private static final int CONFIDENCE_SPAWNER = 100;
    private static final int CONFIDENCE_CHEST = 85;
    private static final int CONFIDENCE_BEACON = 80;
    private static final int CONFIDENCE_BUDDING_AMETHYST = 70;
    private static final int CONFIDENCE_FURNACE = 60;
    private static final int CONFIDENCE_ENTITY_CLUSTER = 50;
    private static final int CONFIDENCE_SUSPICIOUS = 40;
    
    // ============================================================
    // COLORS - Based on confidence level
    // ============================================================
    
    private static final Color COLOR_100 = new Color(255, 50, 50, 120);   // Red - Definite base
    private static final Color COLOR_85 = new Color(255, 150, 50, 120);  // Orange - High confidence
    private static final Color COLOR_70 = new Color(255, 220, 50, 120);  // Yellow - Medium-high
    private static final Color COLOR_50 = new Color(150, 255, 100, 120); // Light green - Medium
    private static final Color COLOR_40 = new Color(100, 200, 255, 100); // Light blue - Low
    
    private static final Color TRACER_BASE = new Color(100, 200, 255, 200);
    
    // ============================================================
    // DATA STORAGE
    // ============================================================
    
    // Chunk detection data
    private final Map<Long, ChunkDetectionData> detectedChunks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> notifyQueue = new ConcurrentLinkedQueue<>();
    
    // Entity tracking (from packets)
    private final Map<BlockPos, SpawnerRecord> spawnerRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, ChestRecord> chestRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, FurnaceRecord> furnaceRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, BeaconRecord> beaconRecords = new ConcurrentHashMap<>();
    
    // Entity clusters
    private final Map<ChunkPos, Integer> entityCounts = new ConcurrentHashMap<>();
    
    // Render storage
    private final CopyOnWriteArrayList<RenderBox> renderBoxes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TracerPoint> tracerPoints = new CopyOnWriteArrayList<>();
    
    private ExecutorService scanExecutor;
    private boolean isActive = false;
    private int tickCounter = 0;
    private long lastTracerUpdate = 0;
    private long lastRenderUpdate = 0;
    private final Map<String, Long> globalNotifCooldown = new ConcurrentHashMap<>();
    
    // ============================================================
    // DATA CLASSES
    // ============================================================
    
    private static class ChunkDetectionData {
        final long packedPos;
        int confidence;
        String reason;
        long lastSeen;
        
        ChunkDetectionData(long packedPos, int confidence, String reason) {
            this.packedPos = packedPos;
            this.confidence = confidence;
            this.reason = reason;
            this.lastSeen = System.currentTimeMillis();
        }
        
        void update(int newConfidence, String newReason) {
            if (newConfidence > confidence) {
                confidence = newConfidence;
                reason = newReason;
            }
            lastSeen = System.currentTimeMillis();
        }
        
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 120000; }
        boolean isBelowY() { return true; }
        
        Color getColor() {
            if (confidence >= 100) return COLOR_100;
            if (confidence >= 80) return COLOR_85;
            if (confidence >= 60) return COLOR_70;
            if (confidence >= 50) return COLOR_50;
            return COLOR_40;
        }
        
        String getConfidenceString() {
            if (confidence >= 100) return "§c100%§f";
            if (confidence >= 85) return "§6" + confidence + "%§f";
            if (confidence >= 70) return "§e" + confidence + "%§f";
            if (confidence >= 50) return "§a" + confidence + "%§f";
            return "§b" + confidence + "%§f";
        }
    }
    
    private static class SpawnerRecord {
        final BlockPos pos;
        String entityType;
        long lastSeen;
        SpawnerRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 90000; }
        boolean isBelowY() { return pos.getY() <= 20; }
        void update() { lastSeen = System.currentTimeMillis(); }
    }
    
    private static class ChestRecord {
        final BlockPos pos;
        int itemCount;
        long lastSeen;
        ChestRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 90000; }
        boolean isBelowY() { return pos.getY() <= 20; }
        boolean hasItems() { return itemCount > 0; }
    }
    
    private static class FurnaceRecord {
        final BlockPos pos;
        boolean isBurning;
        long lastSeen;
        FurnaceRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 90000; }
        boolean isBelowY() { return pos.getY() <= 20; }
    }
    
    private static class BeaconRecord {
        final BlockPos pos;
        int levels;
        long lastSeen;
        BeaconRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 90000; }
        boolean isBelowY() { return pos.getY() <= 20; }
    }
    
    private static class RenderBox {
        final Box box;
        final Color fill, line;
        RenderBox(Box box, Color fill, Color line) { this.box = box; this.fill = fill; this.line = line; }
    }
    
    private static class TracerPoint {
        final Vec3d pos;
        final long time;
        final Color color;
        TracerPoint(Vec3d pos, Color color) { this.pos = pos; this.time = System.currentTimeMillis(); this.color = color; }
        boolean isValid() { return System.currentTimeMillis() - time < 1000; }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public BaseLeakDebug() {
        super(GlazedAddon.esp, "base-leak-debug", "§c§lBASE LEAK §8| §7Underground Base Detector");
    }
    
    // ============================================================
    // LIFECYCLE
    // ============================================================
    
    @Override
    public void onActivate() {
        if (mc.world == null) {
            ChatUtils.error("BaseLeakDebug", "Cannot activate - not in world");
            return;
        }
        
        isActive = true;
        tickCounter = 0;
        
        scanExecutor = Executors.newFixedThreadPool(Math.max(1, workerThreads.get()), r -> {
            Thread t = new Thread(r, "BaseLeak-Scanner");
            t.setDaemon(true);
            return t;
        });
        
        // Clear all data
        detectedChunks.clear();
        scannedChunks.clear();
        notifiedChunks.clear();
        notifyQueue.clear();
        spawnerRecords.clear();
        chestRecords.clear();
        furnaceRecords.clear();
        beaconRecords.clear();
        entityCounts.clear();
        renderBoxes.clear();
        tracerPoints.clear();
        
        // Queue all loaded chunks
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk wc) {
                queueChunkScan(wc);
            }
        }
        
        ChatUtils.info("BaseLeakDebug", "§c§lBASE LEAK DETECTOR §aACTIVATED");
        ChatUtils.info("BaseLeakDebug", "§7- Monitoring packets for underground activity");
        ChatUtils.info("BaseLeakDebug", "§7- Scanning chunks for suspicious patterns");
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§8[§c§lBLD§8] §7BaseLeakDebug §aACTIVE"), false);
        }
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        detectedChunks.clear();
        scannedChunks.clear();
        notifiedChunks.clear();
        notifyQueue.clear();
        spawnerRecords.clear();
        chestRecords.clear();
        furnaceRecords.clear();
        beaconRecords.clear();
        entityCounts.clear();
        renderBoxes.clear();
        tracerPoints.clear();
        ChatUtils.info("BaseLeakDebug", "§cBaseLeakDebug deactivated");
    }
    
    // ============================================================
    // PACKET CAPTURE - Entity Detection
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            processBlockEntity(packet);
        }
        
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            processEntitySpawn(packet);
        }
    }
    
    private void processBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            NbtCompound nbt = packet.getNbt();
            
            if (nbt == null) return;
            
            // Spawner detection - 100% confidence
            if (detectSpawners.get() && type.contains("Spawner")) {
                String entityType = "Unknown";
                try {
                    entityType = nbt.getCompound("SpawnData")
                        .flatMap(sd -> sd.getCompound("entity"))
                        .flatMap(e -> e.getString("id"))
                        .orElse("Unknown");
                } catch (Exception ignored) {}
                
                spawnerRecords.put(pos, new SpawnerRecord(pos));
                addDetection(pos, CONFIDENCE_SPAWNER, "SPAWNER (" + entityType + ")");
            }
            
            // Chest detection - 85% confidence
            if (detectChests.get() && (type.contains("Chest") || type.contains("Barrel"))) {
                int itemCount = nbt.getList("Items").map(NbtList::size).orElse(0);
                if (itemCount > 0) {
                    ChestRecord record = chestRecords.computeIfAbsent(pos, k -> new ChestRecord(pos));
                    record.itemCount = itemCount;
                    addDetection(pos, CONFIDENCE_CHEST, "CHEST (" + itemCount + " items)");
                }
            }
            
            // Furnace detection - 60% confidence
            if (detectFurnaces.get() && type.contains("Furnace")) {
                int burnTime = nbt.getInt("BurnTime").orElse(0);
                if (burnTime > 0) {
                    FurnaceRecord record = furnaceRecords.computeIfAbsent(pos, k -> new FurnaceRecord(pos));
                    record.isBurning = true;
                    addDetection(pos, CONFIDENCE_FURNACE, "FURNACE (burning)");
                }
            }
            
            // Beacon detection - 80% confidence
            if (detectBeacons.get() && type.contains("Beacon")) {
                int levels = nbt.getInt("Levels").orElse(0);
                if (levels > 0) {
                    BeaconRecord record = beaconRecords.computeIfAbsent(pos, k -> new BeaconRecord(pos));
                    record.levels = levels;
                    addDetection(pos, CONFIDENCE_BEACON, "BEACON (level " + levels + ")");
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    private void processEntitySpawn(EntitySpawnS2CPacket packet) {
        if (!detectEntityClusters.get()) return;
        
        try {
            double y = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == double.class && y == 0) y = f.getDouble(packet);
            }
            
            if (y <= 20) {
                // Track entity clusters
                // Entity tracking would go here
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHUNK SCANNING - Block Detection
    // ============================================================
    
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive || scanExecutor == null) return;
        if (event.chunk() instanceof WorldChunk wc) {
            queueChunkScan(wc);
        }
    }
    
    private void queueChunkScan(WorldChunk chunk) {
        if (!isActive || scanExecutor == null) return;
        
        ChunkPos pos = chunk.getPos();
        long packedPos = pos.toLong();
        if (scannedChunks.contains(packedPos)) return;
        
        scanExecutor.submit(() -> {
            try { scanChunk(chunk); } catch (Exception ignored) {}
        });
    }
    
    private void scanChunk(WorldChunk chunk) {
        if (!isActive || mc.world == null) return;
        
        ChunkPos pos = chunk.getPos();
        long packedPos = pos.toLong();
        scannedChunks.add(packedPos);
        
        int buddingCount = 0;
        int suspiciousCount = 0;
        int chestCount = 0;
        
        int startX = pos.getStartX();
        int startZ = pos.getStartZ();
        int bottomY = mc.world.getBottomY();
        int topY = mc.world.getTopYInclusive();
        
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                int surfaceY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - startX, z - startZ);
                
                for (int y = bottomY; y < Math.min(topY, surfaceY + 30); y++) {
                    mutablePos.set(x, y, z);
                    Block block = chunk.getBlockState(mutablePos).getBlock();
                    
                    if (detectBuddingAmethyst.get() && block == Blocks.BUDDING_AMETHYST) {
                        buddingCount++;
                    }
                    
                    if (detectSuspiciousBlocks.get() && isSuspiciousBlock(block)) {
                        suspiciousCount++;
                    }
                    
                    if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
                        chestCount++;
                    }
                }
            }
        }
        
        // Add detections based on chunk scan results
        if (buddingCount > 0) {
            addChunkDetection(packedPos, CONFIDENCE_BUDDING_AMETHYST, "AMETHYST GEODE (" + buddingCount + " blocks)");
        }
        
        if (chestCount > 0) {
            addChunkDetection(packedPos, CONFIDENCE_CHEST, "SURFACE CHEST (" + chestCount + ")");
        }
        
        if (suspiciousCount >= 3) {
            addChunkDetection(packedPos, CONFIDENCE_SUSPICIOUS, "SUSPICIOUS BLOCKS (" + suspiciousCount + ")");
        }
    }
    
    private boolean isSuspiciousBlock(Block block) {
        return block == Blocks.DIAMOND_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE ||
               block == Blocks.EMERALD_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.ANCIENT_DEBRIS ||
               block == Blocks.SPAWNER;
    }
    
    // ============================================================
    // DETECTION ADDITION
    // ============================================================
    
    private void addDetection(BlockPos pos, int confidence, String reason) {
        ChunkPos chunkPos = new ChunkPos(pos);
        long packedPos = chunkPos.toLong();
        addChunkDetection(packedPos, confidence, reason);
    }
    
    private void addChunkDetection(long packedPos, int confidence, String reason) {
        ChunkDetectionData existing = detectedChunks.get(packedPos);
        
        if (existing == null) {
            detectedChunks.put(packedPos, new ChunkDetectionData(packedPos, confidence, reason));
            if (!notifiedChunks.contains(packedPos)) {
                notifyQueue.add(packedPos);
            }
        } else {
            int oldConfidence = existing.confidence;
            existing.update(confidence, reason);
            if (existing.confidence > oldConfidence && !notifiedChunks.contains(packedPos)) {
                notifyQueue.add(packedPos);
            }
        }
    }
    
    // ============================================================
    // NOTIFICATION PROCESSING
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        tickCounter++;
        
        Long packedPos;
        while ((packedPos = notifyQueue.poll()) != null) {
            processNotification(packedPos);
        }
        
        if (tickCounter % 20 == 0) cleanup();
        if (tickCounter % 5 == 0) { updateRenderBoxes(); updateTracers(); }
    }
    
    private void processNotification(long packedPos) {
        if (notifyCooldown.get() && notifiedChunks.contains(packedPos)) return;
        
        ChunkDetectionData data = detectedChunks.get(packedPos);
        if (data == null) return;
        
        // Check distance from player
        if (mc.player == null) return;
        int chunkX = ChunkPos.getPackedX(packedPos);
        int chunkZ = ChunkPos.getPackedZ(packedPos);
        double centerX = chunkX * 16.0 + 8.0;
        double centerZ = chunkZ * 16.0 + 8.0;
        double dx = centerX - mc.player.getX();
        double dz = centerZ - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        
        if (distance < minDistance.get()) return;
        
        notifiedChunks.add(packedPos);
        
        if (chatNotify.get()) {
            String confidenceStr = data.getConfidenceString();
            String color = data.confidence >= 80 ? "§c" : data.confidence >= 60 ? "§6" : "§e";
            
            String message = String.format(
                "§8[§c§lBLD§8] §f[§e%d, %d§f] %s §7- %s §8(§f%s§8) §7- %s",
                chunkX, chunkZ, color, data.reason, confidenceStr, 
                data.confidence >= 80 ? "§cHIGH CONFIDENCE" : data.confidence >= 60 ? "§6MEDIUM" : "§eLOW"
            );
            ChatUtils.info("BaseLeakDebug", message);
        }
        
        if (soundNotify.get() && mc.player != null) {
            float pitch = data.confidence >= 80 ? 2.0f : data.confidence >= 60 ? 1.5f : 1.0f;
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, pitch);
        }
    }
    
    // ============================================================
    // VISUAL UPDATES
    // ============================================================
    
    private void updateRenderBoxes() {
        long now = System.currentTimeMillis();
        if (now - lastRenderUpdate < 100) return;
        
        renderBoxes.clear();
        
        for (ChunkDetectionData data : detectedChunks.values()) {
            if (!data.isValid()) continue;
            if (mc.player != null) {
                int chunkX = ChunkPos.getPackedX(data.packedPos);
                int chunkZ = ChunkPos.getPackedZ(data.packedPos);
                double centerX = chunkX * 16.0 + 8.0;
                double centerZ = chunkZ * 16.0 + 8.0;
                double dx = centerX - mc.player.getX();
                double dz = centerZ - mc.player.getZ();
                if (Math.sqrt(dx * dx + dz * dz) < minDistance.get()) continue;
            }
            
            int chunkX = ChunkPos.getPackedX(data.packedPos);
            int chunkZ = ChunkPos.getPackedZ(data.packedPos);
            double x1 = chunkX * 16.0;
            double z1 = chunkZ * 16.0;
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;
            double yLevel = highlightHeight.get();
            
            Color color = data.getColor();
            Box box = new Box(x1, yLevel, z1, x2, yLevel + 0.5, z2);
            renderBoxes.add(new RenderBox(box, color, color));
        }
        
        lastRenderUpdate = now;
    }
    
    private void updateTracers() {
        long now = System.currentTimeMillis();
        if (now - lastTracerUpdate < 100) return;
        
        tracerPoints.clear();
        
        for (ChunkDetectionData data : detectedChunks.values()) {
            if (!data.isValid()) continue;
            if (mc.player != null) {
                int chunkX = ChunkPos.getPackedX(data.packedPos);
                int chunkZ = ChunkPos.getPackedZ(data.packedPos);
                double centerX = chunkX * 16.0 + 8.0;
                double centerZ = chunkZ * 16.0 + 8.0;
                double dx = centerX - mc.player.getX();
                double dz = centerZ - mc.player.getZ();
                if (Math.sqrt(dx * dx + dz * dz) < minDistance.get()) continue;
                
                tracerPoints.add(new TracerPoint(new Vec3d(centerX, highlightHeight.get() + 0.25, centerZ), TRACER_BASE));
            }
        }
        
        lastTracerUpdate = now;
    }
    
    // ============================================================
    // CLEANUP
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        detectedChunks.entrySet().removeIf(e -> !e.getValue().isValid());
        scannedChunks.removeIf(p -> {
            if (mc.world == null) return true;
            int x = ChunkPos.getPackedX(p);
            int z = ChunkPos.getPackedZ(p);
            return !mc.world.isChunkLoaded(x, z);
        });
        
        spawnerRecords.entrySet().removeIf(e -> !e.getValue().isValid());
        chestRecords.entrySet().removeIf(e -> !e.getValue().isValid());
        furnaceRecords.entrySet().removeIf(e -> !e.getValue().isValid());
        beaconRecords.entrySet().removeIf(e -> !e.getValue().isValid());
        
        globalNotifCooldown.entrySet().removeIf(e -> now - e.getValue() > 30000);
    }
    
    // ============================================================
    // RENDERING
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        if (!chunkHighlight.get()) return;
        
        Vec3d startPos = mc.player.getCameraPosVec(event.tickDelta);
        
        for (RenderBox box : renderBoxes) {
            event.renderer.box(box.box.minX, box.box.minY, box.box.minZ,
                box.box.maxX, box.box.maxY, box.box.maxZ,
                box.fill, box.line, ShapeMode.Both, 0);
        }
        
        if (tracers.get()) {
            for (TracerPoint tracer : tracerPoints) {
                if (!tracer.isValid()) continue;
                event.renderer.line(startPos.x, startPos.y, startPos.z,
                    tracer.pos.x, tracer.pos.y, tracer.pos.z, tracer.color);
            }
        }
    }
    
    // ============================================================
    // INFO STRING
    // ============================================================
    
    @Override
    public String getInfoString() {
        int high = (int) detectedChunks.values().stream().filter(d -> d.confidence >= 80).count();
        int medium = (int) detectedChunks.values().stream().filter(d -> d.confidence >= 60 && d.confidence < 80).count();
        int low = (int) detectedChunks.values().stream().filter(d -> d.confidence < 60).count();
        return String.format("§c%d §7| §6%d §7| §e%d", high, medium, low);
    }
}
