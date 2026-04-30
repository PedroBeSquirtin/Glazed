package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
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
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DonutBypass - Advanced Underground Structure Detector
 * 
 * Designed for DonutSMP to detect budding amethyst chunks, geodes, and suspicious underground patterns.
 * Features:
 * - Multi-threaded chunk scanning for performance
 * - Configurable detection thresholds
 * - Color-coded rendering with tracers
 * - Notification system with chat and sound alerts
 * - Distance filtering to reduce clutter
 */
public class DonutBypass extends Module {
    
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
    
    // ============================================================
    // GENERAL SETTINGS
    // ============================================================
    
    private final Setting<Integer> minDistance = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance")
        .description("Minimum horizontal distance from player to detect (chunks further than this are scanned)")
        .defaultValue(100)
        .min(0)
        .max(512)
        .sliderMax(512)
        .build()
    );
    
    private final Setting<Integer> maxDistance = sgGeneral.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Maximum horizontal distance to scan (chunks beyond this are ignored)")
        .defaultValue(500)
        .min(100)
        .max(1000)
        .sliderMax(1000)
        .build()
    );
    
    private final Setting<Integer> workerThreads = sgGeneral.add(new IntSetting.Builder()
        .name("worker-threads")
        .description("Background scanner threads (1 = best performance)")
        .defaultValue(1)
        .min(1)
        .max(4)
        .build()
    );
    
    // ============================================================
    // DETECTION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> detectBuddingAmethyst = sgDetection.add(new BoolSetting.Builder()
        .name("detect-budding-amethyst")
        .description("Detect budding amethyst blocks")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectAmethystClusters = sgDetection.add(new BoolSetting.Builder()
        .name("detect-amethyst-clusters")
        .description("Detect amethyst clusters (indicators of geodes)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectSuspiciousBlocks = sgDetection.add(new BoolSetting.Builder()
        .name("detect-suspicious-blocks")
        .description("Detect other suspicious underground blocks (ores, spawners, chests)")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Integer> blockCountThreshold = sgDetection.add(new IntSetting.Builder()
        .name("block-threshold")
        .description("Minimum block count to consider a chunk suspicious")
        .defaultValue(1)
        .min(1)
        .max(10)
        .build()
    );
    
    // ============================================================
    // RENDER SETTINGS
    // ============================================================
    
    private final Setting<Boolean> chunkHighlight = sgRender.add(new BoolSetting.Builder()
        .name("chunk-highlight")
        .description("Highlight chunks containing targets")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to found chunks")
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
    
    private final Setting<Double> highlightThickness = sgRender.add(new DoubleSetting.Builder()
        .name("highlight-thickness")
        .description("Thickness of chunk highlight boxes")
        .defaultValue(0.5)
        .min(0.1)
        .max(2.0)
        .build()
    );
    
    // Color settings - Unique vibrant colors
    private final Setting<SettingColor> foundColor = sgRender.add(new ColorSetting.Builder()
        .name("found-color")
        .description("Color for chunks containing detected blocks")
        .defaultValue(new SettingColor(100, 255, 150, 100))
        .build()
    );
    
    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color for tracer lines")
        .defaultValue(new SettingColor(100, 255, 150, 200))
        .build()
    );
    
    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Color for chunk outlines")
        .defaultValue(new SettingColor(50, 200, 100, 255))
        .build()
    );
    
    // ============================================================
    // NOTIFICATION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> chatNotify = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Send chat message when a new chunk is found")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> soundNotify = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-notify")
        .description("Play sound when a new chunk is found")
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
    // DATA STORAGE
    // ============================================================
    
    // Track different types of findings
    private final Set<Long> foundBuddingChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> foundClusterChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> foundSuspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> chunkBlockCounts = new ConcurrentHashMap<>();
    
    private final ConcurrentLinkedQueue<Long> notifyQueue = new ConcurrentLinkedQueue<>();
    private ExecutorService scanExecutor;
    
    private boolean isActive = false;
    private int tickCounter = 0;
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public DonutBypass() {
        super(GlazedAddon.esp, "donut-bypass", "§bDonutBypass §7- Advanced underground structure detector");
    }
    
    // ============================================================
    // LIFECYCLE
    // ============================================================
    
    @Override
    public void onActivate() {
        if (mc.world == null) {
            ChatUtils.error("DonutBypass", "Cannot activate - not in world");
            return;
        }
        
        isActive = true;
        tickCounter = 0;
        
        // Initialize thread pool
        scanExecutor = Executors.newFixedThreadPool(Math.max(1, workerThreads.get()), r -> {
            Thread t = new Thread(r, "DonutBypass-Scanner");
            t.setDaemon(true);
            return t;
        });
        
        // Clear all data
        foundBuddingChunks.clear();
        foundClusterChunks.clear();
        foundSuspiciousChunks.clear();
        scannedChunks.clear();
        notifiedChunks.clear();
        chunkBlockCounts.clear();
        notifyQueue.clear();
        
        // Queue all currently loaded chunks
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk wc) {
                queueChunkScan(wc);
            }
        }
        
        ChatUtils.info("DonutBypass", "§aActivated - Scanning for underground structures");
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§8[§bDonut§8] §7DonutBypass §aACTIVE"), false);
        }
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        
        foundBuddingChunks.clear();
        foundClusterChunks.clear();
        foundSuspiciousChunks.clear();
        scannedChunks.clear();
        notifiedChunks.clear();
        chunkBlockCounts.clear();
        notifyQueue.clear();
        
        ChatUtils.info("DonutBypass", "§cDeactivated");
    }
    
    // ============================================================
    // CHUNK QUEUEING
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
        
        // Skip if already scanned
        if (scannedChunks.contains(packedPos)) return;
        
        scanExecutor.submit(() -> {
            try {
                scanChunk(chunk);
            } catch (Exception ignored) {}
        });
    }
    
    // ============================================================
    // CORE SCANNING LOGIC
    // ============================================================
    
    private void scanChunk(WorldChunk chunk) {
        if (!isActive || mc.world == null) return;
        
        ChunkPos pos = chunk.getPos();
        long packedPos = pos.toLong();
        
        // Mark as scanned
        scannedChunks.add(packedPos);
        
        int buddingCount = 0;
        int clusterCount = 0;
        int suspiciousCount = 0;
        
        // Get chunk bounds
        int startX = pos.getStartX();
        int startZ = pos.getStartZ();
        int bottomY = mc.world.getBottomY();
        int topY = mc.world.getTopYInclusive();
        
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        
        // Scan all blocks in chunk
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                // Optimize: Use heightmap to limit scan range
                int surfaceY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - startX, z - startZ);
                
                for (int y = bottomY; y < Math.min(topY, surfaceY + 30); y++) {
                    mutablePos.set(x, y, z);
                    Block block = chunk.getBlockState(mutablePos).getBlock();
                    
                    // Detect budding amethyst
                    if (detectBuddingAmethyst.get() && block == Blocks.BUDDING_AMETHYST) {
                        buddingCount++;
                    }
                    
                    // Detect amethyst clusters
                    if (detectAmethystClusters.get() && (block == Blocks.AMETHYST_CLUSTER || 
                        block == Blocks.LARGE_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD)) {
                        clusterCount++;
                    }
                    
                    // Detect other suspicious blocks
                    if (detectSuspiciousBlocks.get() && isSuspiciousBlock(block)) {
                        suspiciousCount++;
                    }
                }
            }
        }
        
        // Store counts
        chunkBlockCounts.put(packedPos, buddingCount + clusterCount + suspiciousCount);
        
        // Update found sets based on thresholds
        boolean found = false;
        
        if (buddingCount >= blockCountThreshold.get()) {
            if (foundBuddingChunks.add(packedPos)) {
                found = true;
            }
        }
        
        if (clusterCount >= blockCountThreshold.get()) {
            if (foundClusterChunks.add(packedPos)) {
                found = true;
            }
        }
        
        if (suspiciousCount >= blockCountThreshold.get()) {
            if (foundSuspiciousChunks.add(packedPos)) {
                found = true;
            }
        }
        
        // Queue notification if newly found
        if (found && (notifyCooldown.get() || !notifiedChunks.contains(packedPos))) {
            notifyQueue.add(packedPos);
        }
    }
    
    private boolean isSuspiciousBlock(Block block) {
        // Removed NETHERITE_SCRAP (doesn't exist in 1.21.11)
        return block == Blocks.DIAMOND_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE ||
               block == Blocks.EMERALD_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.ANCIENT_DEBRIS ||
               block == Blocks.SPAWNER ||
               block == Blocks.CHEST ||
               block == Blocks.TRAPPED_CHEST;
    }
    
    // ============================================================
    // NOTIFICATION PROCESSING
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        tickCounter++;
        
        // Process notifications
        Long packedPos;
        while ((packedPos = notifyQueue.poll()) != null) {
            processNotification(packedPos);
        }
        
        // Cleanup every 10 seconds
        if (tickCounter % 200 == 0) {
            cleanupUnloadedChunks();
        }
    }
    
    private void processNotification(long packedPos) {
        if (notifyCooldown.get() && notifiedChunks.contains(packedPos)) return;
        
        notifiedChunks.add(packedPos);
        int chunkX = ChunkPos.getPackedX(packedPos);
        int chunkZ = ChunkPos.getPackedZ(packedPos);
        int blockCount = chunkBlockCounts.getOrDefault(packedPos, 0);
        
        // Determine what was found
        String foundType = "";
        if (foundBuddingChunks.contains(packedPos)) foundType = "Budding Amethyst";
        else if (foundClusterChunks.contains(packedPos)) foundType = "Amethyst Geode";
        else if (foundSuspiciousChunks.contains(packedPos)) foundType = "Suspicious Blocks";
        
        if (chatNotify.get()) {
            ChatUtils.info("DonutBypass", "§aFound " + foundType + " §7at chunk [§e" + chunkX + ", " + chunkZ + "§7] §8(" + blockCount + " blocks)");
        }
        
        // FIXED: Use SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP directly (no .value() needed in 1.21.11)
        if (soundNotify.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
    
    private void cleanupUnloadedChunks() {
        if (mc.world == null) return;
        
        foundBuddingChunks.removeIf(this::isChunkUnloaded);
        foundClusterChunks.removeIf(this::isChunkUnloaded);
        foundSuspiciousChunks.removeIf(this::isChunkUnloaded);
        scannedChunks.removeIf(this::isChunkUnloaded);
        chunkBlockCounts.keySet().removeIf(this::isChunkUnloaded);
    }
    
    private boolean isChunkUnloaded(long packedPos) {
        if (mc.world == null) return true;
        int x = ChunkPos.getPackedX(packedPos);
        int z = ChunkPos.getPackedZ(packedPos);
        return !mc.world.isChunkLoaded(x, z);
    }
    
    // ============================================================
    // DISTANCE CHECKING
    // ============================================================
    
    private boolean isWithinRenderDistance(long packedPos) {
        if (mc.player == null) return false;
        
        int chunkX = ChunkPos.getPackedX(packedPos);
        int chunkZ = ChunkPos.getPackedZ(packedPos);
        
        double chunkCenterX = chunkX * 16.0 + 8.0;
        double chunkCenterZ = chunkZ * 16.0 + 8.0;
        
        double dx = chunkCenterX - mc.player.getX();
        double dz = chunkCenterZ - mc.player.getZ();
        double distanceSq = dx * dx + dz * dz;
        
        int minDistSq = minDistance.get() * minDistance.get();
        int maxDistSq = maxDistance.get() * maxDistance.get();
        
        return distanceSq >= minDistSq && distanceSq <= maxDistSq;
    }
    
    // ============================================================
    // RENDERING
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        if (!chunkHighlight.get()) return;
        
        Vec3d startPos = mc.player.getCameraPosVec(event.tickDelta);
        double yLevel = highlightHeight.get();
        double thickness = highlightThickness.get();
        
        // Render found budding amethyst chunks (primary)
        for (long packedPos : foundBuddingChunks) {
            if (!isWithinRenderDistance(packedPos)) continue;
            renderChunk(event, packedPos, yLevel, thickness, foundColor.get(), outlineColor.get());
            
            if (tracers.get()) {
                renderTracer(event, packedPos, startPos, tracerColor.get());
            }
        }
        
        // Render amethyst cluster chunks (secondary)
        for (long packedPos : foundClusterChunks) {
            if (foundBuddingChunks.contains(packedPos)) continue;
            if (!isWithinRenderDistance(packedPos)) continue;
            
            Color secondaryColor = new Color(
                foundColor.get().r, foundColor.get().g, foundColor.get().b, 
                foundColor.get().a / 2
            );
            renderChunk(event, packedPos, yLevel, thickness, secondaryColor, outlineColor.get());
            
            if (tracers.get()) {
                renderTracer(event, packedPos, startPos, tracerColor.get());
            }
        }
        
        // Render suspicious chunks (tertiary)
        for (long packedPos : foundSuspiciousChunks) {
            if (foundBuddingChunks.contains(packedPos) || foundClusterChunks.contains(packedPos)) continue;
            if (!isWithinRenderDistance(packedPos)) continue;
            
            Color tertiaryColor = new Color(
                foundColor.get().r, foundColor.get().g, foundColor.get().b, 
                foundColor.get().a / 3
            );
            renderChunk(event, packedPos, yLevel, thickness, tertiaryColor, outlineColor.get());
        }
    }
    
    private void renderChunk(Render3DEvent event, long packedPos, double yLevel, double thickness, Color fill, Color outline) {
        int chunkX = ChunkPos.getPackedX(packedPos);
        int chunkZ = ChunkPos.getPackedZ(packedPos);
        
        double x1 = chunkX * 16.0;
        double z1 = chunkZ * 16.0;
        double x2 = x1 + 16.0;
        double z2 = z1 + 16.0;
        double y2 = yLevel + thickness;
        
        // Render as box with fill and outline
        event.renderer.box(x1, yLevel, z1, x2, y2, z2, fill, outline, ShapeMode.Both, 0);
        
        // Coordinates display removed - event.renderer.text not available in this version
        // Using tracer line as primary navigation method
    }
    
    private void renderTracer(Render3DEvent event, long packedPos, Vec3d startPos, Color color) {
        if (mc.player == null) return;
        
        int chunkX = ChunkPos.getPackedX(packedPos);
        int chunkZ = ChunkPos.getPackedZ(packedPos);
        double targetX = chunkX * 16.0 + 8.0;
        double targetZ = chunkZ * 16.0 + 8.0;
        double targetY = highlightHeight.get() + highlightThickness.get() / 2;
        
        event.renderer.line(startPos.x, startPos.y, startPos.z, targetX, targetY, targetZ, color);
    }
    
    // ============================================================
    // INFO STRING
    // ============================================================
    
    @Override
    public String getInfoString() {
        int total = foundBuddingChunks.size() + foundClusterChunks.size() + foundSuspiciousChunks.size();
        return String.format("§a%d §7found §8| §e%d §7budding", total, foundBuddingChunks.size());
    }
}
