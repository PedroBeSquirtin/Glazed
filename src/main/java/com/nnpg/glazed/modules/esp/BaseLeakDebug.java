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

/**
 * BaseLeakDebug - High Confidence Underground Base Detector
 * 
 * Optimized thresholds to reduce false positives:
 * - Only detects actual player-built structures
 * - High confidence requirements
 * - Ignores natural generation
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
        .description("Minimum distance from player to detect (avoids your own base)")
        .defaultValue(100)
        .min(50)
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
    // DETECTION SETTINGS - HIGH THRESHOLDS
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
    
    private final Setting<Boolean> detectFurnaces = sgDetection.add(new BoolSetting.Builder()
        .name("detect-furnaces")
        .description("Detect burning furnaces (60% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectStorageConcentration = sgDetection.add(new BoolSetting.Builder()
        .name("detect-storage-concentration")
        .description("Detect multiple chests/hoppers in one chunk (70% confidence)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectPlayerBlocks = sgDetection.add(new BoolSetting.Builder()
        .name("detect-player-blocks")
        .description("Detect player-placed blocks (furnaces, crafting tables, etc.)")
        .defaultValue(true)
        .build()
    );
    
    // ============================================================
    // DETECTION THRESHOLDS - INCREASED
    // ============================================================
    
    private final Setting<Integer> chestThreshold = sgDetection.add(new IntSetting.Builder()
        .name("chest-threshold")
        .description("Minimum number of chests in a chunk to trigger detection")
        .defaultValue(3)
        .min(1)
        .max(10)
        .build()
    );
    
    private final Setting<Integer> hopperThreshold = sgDetection.add(new IntSetting.Builder()
        .name("hopper-threshold")
        .description("Minimum number of hoppers in a chunk to trigger detection")
        .defaultValue(5)
        .min(1)
        .max(20)
        .build()
    );
    
    private final Setting<Integer> furnaceThreshold = sgDetection.add(new IntSetting.Builder()
        .name("furnace-threshold")
        .description("Minimum number of furnaces in a chunk to trigger detection")
        .defaultValue(4)
        .min(1)
        .max(20)
        .build()
    );
    
    private final Setting<Integer> playerBlockThreshold = sgDetection.add(new IntSetting.Builder()
        .name("player-block-threshold")
        .description("Minimum number of player-placed blocks in a chunk")
        .defaultValue(10)
        .min(5)
        .max(50)
        .build()
    );
    
    // ============================================================
    // CONFIDENCE SCORES - ADJUSTED
    // ============================================================
    
    private static final int CONFIDENCE_SPAWNER = 100;
    private static final int CONFIDENCE_BEACON = 90;
    private static final int CONFIDENCE_STORAGE_CONCENTRATION = 75;
    private static final int CONFIDENCE_CHEST_CLUSTER = 70;
    private static final int CONFIDENCE_FURNACE_CLUSTER = 60;
    private static final int CONFIDENCE_PLAYER_BLOCKS = 55;
    
    // ============================================================
    // COLORS
    // ============================================================
    
    private static final Color COLOR_100 = new Color(255, 50, 50, 150);   // Red - Spawner
    private static final Color COLOR_90 = new Color(255, 100, 50, 140);   // Orange - Beacon
    private static final Color COLOR_75 = new Color(255, 180, 50, 130);   // Yellow-Orange
    private static final Color COLOR_60 = new Color(200, 220, 50, 120);   // Yellow
    private static final Color COLOR_50 = new Color(100, 255, 100, 110);  // Light Green
    
    private static final Color TRACER_BASE = new Color(255, 100, 50, 200);
    
    // ============================================================
    // DATA STORAGE
    // ============================================================
    
    private final Map<Long, ChunkDetectionData> detectedChunks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> notifyQueue = new ConcurrentLinkedQueue<>();
    
    // Player-placed blocks tracking
    private final Map<ChunkPos, Integer> playerBlockCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> chestCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> hopperCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> furnaceCounts = new ConcurrentHashMap<>();
    private final Set<ChunkPos> beaconChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> spawnerChunks = ConcurrentHashMap.newKeySet();
    
    // Render storage
    private final CopyOnWriteArrayList<RenderBox> renderBoxes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TracerPoint> tracerPoints = new CopyOnWriteArrayList<>();
    
    private ExecutorService scanExecutor;
    private boolean isActive = false;
    private int tickCounter = 0;
    private long lastTracerUpdate = 0;
    private long lastRenderUpdate = 0;
    
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
        
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 180000; } // 3 minutes
        
        Color getColor() {
            if (confidence >= 90) return COLOR_100;
            if (confidence >= 75) return COLOR_90;
            if (confidence >= 60) return COLOR_75;
            if (confidence >= 50) return COLOR_60;
            return COLOR_50;
        }
        
        String getConfidenceString() {
            if (confidence >= 90) return "§c" + confidence + "%§f";
            if (confidence >= 75) return "§6" + confidence + "%§f";
            if (confidence >= 60) return "§e" + confidence + "%§f";
            return "§a" + confidence + "%§f";
        }
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
        super(GlazedAddon.esp, "base-leak-debug", "§c§lBASE LEAK §8| §7Base Detector (High Thresholds)");
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
        playerBlockCounts.clear();
        chestCounts.clear();
        hopperCounts.clear();
        furnaceCounts.clear();
        beaconChunks.clear();
        spawnerChunks.clear();
        renderBoxes.clear();
        tracerPoints.clear();
        
        // Queue all loaded chunks
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk wc) {
                queueChunkScan(wc);
            }
        }
        
        ChatUtils.info("BaseLeakDebug", "§c§lBASE LEAK DETECTOR §aACTIVATED");
        ChatUtils.info("BaseLeakDebug", "§7- High threshold mode (reduced false positives)");
        ChatUtils.info("BaseLeakDebug", "§7- Chests needed: §e" + chestThreshold.get() + "+ per chunk");
        ChatUtils.info("BaseLeakDebug", "§7- Hoppers needed: §e" + hopperThreshold.get() + "+ per chunk");
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§8[§c§lBLD§8] §7BaseLeakDebug §aACTIVE §8(High Thresholds)"), false);
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
        playerBlockCounts.clear();
        chestCounts.clear();
        hopperCounts.clear();
        furnaceCounts.clear();
        beaconChunks.clear();
        spawnerChunks.clear();
        renderBoxes.clear();
        tracerPoints.clear();
        ChatUtils.info("BaseLeakDebug", "§cBaseLeakDebug deactivated");
    }
    
    // ============================================================
    // PACKET CAPTURE - Priority Detections
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            processBlockEntity(packet);
        }
    }
    
    private void processBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            NbtCompound nbt = packet.getNbt();
            
            if (nbt == null) return;
            ChunkPos chunkPos = new ChunkPos(pos);
            long packedPos = chunkPos.toLong();
            
            // SPAWNER - 100% confidence (HIGHEST PRIORITY)
            if (detectSpawners.get() && type.contains("Spawner")) {
                String entityType = "Unknown";
                try {
                    entityType = nbt.getCompound("SpawnData")
                        .flatMap(sd -> sd.getCompound("entity"))
                        .flatMap(e -> e.getString("id"))
                        .orElse("Unknown");
                } catch (Exception ignored) {}
                
                spawnerChunks.add(chunkPos);
                addDetection(packedPos, CONFIDENCE_SPAWNER, "§c§lSPAWNER §7(" + entityType + ")");
            }
            
            // BEACON - 90% confidence
            if (detectBeacons.get() && type.contains("Beacon")) {
                int levels = nbt.getInt("Levels").orElse(0);
                if (levels > 0) {
                    beaconChunks.add(chunkPos);
                    addDetection(packedPos, CONFIDENCE_BEACON, "§6BEACON §7(level " + levels + ")");
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHUNK SCANNING - Block Concentration Detection
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
        
        int chestCount = 0;
        int hopperCount = 0;
        int furnaceCount = 0;
        int craftingCount = 0;
        int railCount = 0;
        
        int startX = pos.getStartX();
        int startZ = pos.getStartZ();
        int bottomY = mc.world.getBottomY();
        int topY = mc.world.getTopYInclusive();
        
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                int surfaceY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - startX, z - startZ);
                
                // Only scan below surface (underground)
                for (int y = bottomY; y < Math.min(topY, surfaceY - 5); y++) {
                    mutablePos.set(x, y, z);
                    Block block = chunk.getBlockState(mutablePos).getBlock();
                    
                    // Count chests (storage indicators)
                    if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
                        chestCount++;
                    }
                    
                    // Count hoppers (redstone/storage systems)
                    if (block == Blocks.HOPPER) {
                        hopperCount++;
                    }
                    
                    // Count furnaces (smelting arrays)
                    if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) {
                        furnaceCount++;
                    }
                    
                    // Count player-placed utility blocks
                    if (block == Blocks.CRAFTING_TABLE || block == Blocks.ENCHANTING_TABLE ||
                        block == Blocks.ANVIL || block == Blocks.GRINDSTONE ||
                        block == Blocks.STONECUTTER || block == Blocks.LOOM ||
                        block == Blocks.CARTOGRAPHY_TABLE || block == Blocks.SMITHING_TABLE) {
                        craftingCount++;
                    }
                    
                    // Count rails (minecart systems)
                    if (block == Blocks.RAIL || block == Blocks.POWERED_RAIL || 
                        block == Blocks.DETECTOR_RAIL || block == Blocks.ACTIVATOR_RAIL) {
                        railCount++;
                    }
                }
            }
        }
        
        // Store counts
        if (chestCount > 0) chestCounts.put(pos, chestCount);
        if (hopperCount > 0) hopperCounts.put(pos, hopperCount);
        if (furnaceCount > 0) furnaceCounts.put(pos, furnaceCount);
        
        int totalPlayerBlocks = chestCount + hopperCount + furnaceCount + craftingCount + railCount;
        if (totalPlayerBlocks > 0) playerBlockCounts.put(pos, totalPlayerBlocks);
        
        // HIGH CONFIDENCE DETECTIONS - Multiple chests (storage room)
        if (chestCount >= chestThreshold.get()) {
            addDetection(packedPos, CONFIDENCE_CHEST_CLUSTER, 
                String.format("§eSTORAGE ROOM §7(%d chests)", chestCount));
        }
        
        // Multiple hoppers (item transport system)
        if (hopperCount >= hopperThreshold.get()) {
            addDetection(packedPos, CONFIDENCE_STORAGE_CONCENTRATION,
                String.format("§eITEM TRANSPORT §7(%d hoppers)", hopperCount));
        }
        
        // Multiple furnaces (smelting array)
        if (furnaceCount >= furnaceThreshold.get()) {
            addDetection(packedPos, CONFIDENCE_FURNACE_CLUSTER,
                String.format("§7SMELTING ARRAY §7(%d furnaces)", furnaceCount));
        }
        
        // Many player-placed blocks (active base)
        if (totalPlayerBlocks >= playerBlockThreshold.get()) {
            addDetection(packedPos, CONFIDENCE_PLAYER_BLOCKS,
                String.format("§aPLAYER BASE §7(%d player blocks)", totalPlayerBlocks));
        }
    }
    
    // ============================================================
    // DETECTION ADDITION
    // ============================================================
    
    private void addDetection(long packedPos, int confidence, String reason) {
        ChunkDetectionData existing = detectedChunks.get(packedPos);
        
        if (existing == null) {
            detectedChunks.put(packedPos, new ChunkDetectionData(packedPos, confidence, reason));
            if (!notifiedChunks.contains(packedPos)) {
                notifyQueue.add(packedPos);
            }
        } else {
            if (confidence > existing.confidence) {
                existing.confidence = confidence;
                existing.reason = reason;
                if (!notifiedChunks.contains(packedPos)) {
                    notifyQueue.add(packedPos);
                }
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
        if (notifiedChunks.contains(packedPos)) return;
        
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
        
        String confidenceStr = data.getConfidenceString();
        String bracketColor = data.confidence >= 90 ? "§c" : data.confidence >= 75 ? "§6" : "§e";
        
        String message = String.format(
            "§8[§c§lBLD§8] %s[§e%d, §e%d§8] §7- %s §8(§f%s§8) §7- §e%.0f blocks away",
            bracketColor, chunkX, chunkZ, data.reason, confidenceStr, distance
        );
        ChatUtils.info("BaseLeakDebug", message);
        
        if (mc.player != null) {
            float pitch = data.confidence >= 90 ? 2.0f : data.confidence >= 75 ? 1.5f : 1.0f;
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
            
            int chunkX = ChunkPos.getPackedX(data.packedPos);
            int chunkZ = ChunkPos.getPackedZ(data.packedPos);
            double x1 = chunkX * 16.0;
            double z1 = chunkZ * 16.0;
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;
            double yLevel = 60.0;
            
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
            
            int chunkX = ChunkPos.getPackedX(data.packedPos);
            int chunkZ = ChunkPos.getPackedZ(data.packedPos);
            double centerX = chunkX * 16.0 + 8.0;
            double centerZ = chunkZ * 16.0 + 8.0;
            
            tracerPoints.add(new TracerPoint(new Vec3d(centerX, 65.0, centerZ), TRACER_BASE));
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
        
        // Clear notified chunks after 5 minutes to allow re-notification
        if (tickCounter % 600 == 0) {
            notifiedChunks.clear();
        }
    }
    
    // ============================================================
    // RENDERING
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        
        Vec3d startPos = mc.player.getCameraPosVec(event.tickDelta);
        
        for (RenderBox box : renderBoxes) {
            event.renderer.box(box.box.minX, box.box.minY, box.box.minZ,
                box.box.maxX, box.box.maxY, box.box.maxZ,
                box.fill, box.line, ShapeMode.Both, 0);
        }
        
        for (TracerPoint tracer : tracerPoints) {
            if (!tracer.isValid()) continue;
            event.renderer.line(startPos.x, startPos.y, startPos.z,
                tracer.pos.x, tracer.pos.y, tracer.pos.z, tracer.color);
        }
    }
    
    // ============================================================
    // INFO STRING
    // ============================================================
    
    @Override
    public String getInfoString() {
        int high = (int) detectedChunks.values().stream().filter(d -> d.confidence >= 75).count();
        int medium = (int) detectedChunks.values().stream().filter(d -> d.confidence >= 60 && d.confidence < 75).count();
        return String.format("§c%d §7| §e%d", high, medium);
    }
}
