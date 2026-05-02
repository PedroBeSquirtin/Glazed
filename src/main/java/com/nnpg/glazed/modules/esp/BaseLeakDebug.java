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
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
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
 * BaseLeakDebug - Advanced Player Activity Detector
 * 
 * Uses multiple Minecraft mechanics to detect player activity:
 * - Crop growth detection (max growth = player planted)
 * - Vine/Bamboo length (long growth = player placed)
 * - Redstone activity (clocks, circuits)
 * - Entity concentrations (mob farms, villagers)
 * - Block concentrations (chests, furnaces, hoppers)
 * - Player-placed blocks (crafting tables, anvils, etc.)
 */
public class BaseLeakDebug extends Module {
    
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgGrowth = settings.createGroup("Growth Detection");
    private final SettingGroup sgRedstone = settings.createGroup("Redstone Detection");
    private final SettingGroup sgDetection = settings.createGroup("Block Detection");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
    
    // ============================================================
    // GENERAL SETTINGS
    // ============================================================
    
    private final Setting<Integer> minDistance = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance")
        .description("Minimum distance from player to detect")
        .defaultValue(80)
        .min(30)
        .max(300)
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
    // GROWTH DETECTION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> detectMaxGrowth = sgGrowth.add(new BoolSetting.Builder()
        .name("detect-max-growth")
        .description("Detect fully grown crops (player planted)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectLongBamboo = sgGrowth.add(new BoolSetting.Builder()
        .name("detect-long-bamboo")
        .description("Detect bamboo stalks 10+ blocks tall (player placed)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectLongVines = sgGrowth.add(new BoolSetting.Builder()
        .name("detect-long-vines")
        .description("Detect vines 15+ blocks long (player placed)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Integer> bambooMinHeight = sgGrowth.add(new IntSetting.Builder()
        .name("bamboo-min-height")
        .description("Minimum bamboo height to trigger detection")
        .defaultValue(10)
        .min(5)
        .max(20)
        .build()
    );
    
    private final Setting<Integer> vineMinLength = sgGrowth.add(new IntSetting.Builder()
        .name("vine-min-length")
        .description("Minimum vine length to trigger detection")
        .defaultValue(15)
        .min(8)
        .max(30)
        .build()
    );
    
    // ============================================================
    // REDSTONE DETECTION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> detectRedstoneActivity = sgRedstone.add(new BoolSetting.Builder()
        .name("detect-redstone")
        .description("Detect active redstone components")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectRedstoneClocks = sgRedstone.add(new BoolSetting.Builder()
        .name("detect-redstone-clocks")
        .description("Detect rapid redstone toggling (clocks)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Integer> redstoneClockThreshold = sgRedstone.add(new IntSetting.Builder()
        .name("clock-threshold")
        .description("Power changes per second to classify as clock")
        .defaultValue(10)
        .min(5)
        .max(30)
        .build()
    );
    
    // ============================================================
    // BLOCK DETECTION SETTINGS
    // ============================================================
    
    private final Setting<Boolean> detectStorage = sgDetection.add(new BoolSetting.Builder()
        .name("detect-storage")
        .description("Detect chest/hopper concentrations")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectVillagers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-villagers")
        .description("Detect villager concentrations (trading halls)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> detectMobFarms = sgDetection.add(new BoolSetting.Builder()
        .name("detect-mob-farms")
        .description("Detect mob concentrations (spawner farms)")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Integer> storageThreshold = sgDetection.add(new IntSetting.Builder()
        .name("storage-threshold")
        .description("Minimum chests+hoppers in a chunk to trigger")
        .defaultValue(5)
        .min(3)
        .max(20)
        .build()
    );
    
    private final Setting<Integer> villagerThreshold = sgDetection.add(new IntSetting.Builder()
        .name("villager-threshold")
        .description("Minimum villagers in a chunk to trigger")
        .defaultValue(3)
        .min(2)
        .max(10)
        .build()
    );
    
    private final Setting<Integer> mobThreshold = sgDetection.add(new IntSetting.Builder()
        .name("mob-threshold")
        .description("Minimum mobs in a chunk to trigger")
        .defaultValue(8)
        .min(5)
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
    
    private final Setting<Boolean> showLabels = sgRender.add(new BoolSetting.Builder()
        .name("show-labels")
        .description("Show detection labels on ESP")
        .defaultValue(false)
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
    // CONFIDENCE SCORES
    // ============================================================
    
    private static final int CONFIDENCE_SPAWNER = 100;
    private static final int CONFIDENCE_REDSTONE_CLOCK = 95;
    private static final int CONFIDENCE_BEACON = 90;
    private static final int CONFIDENCE_LONG_BAMBOO = 85;
    private static final int CONFIDENCE_MAX_GROWTH = 80;
    private static final int CONFIDENCE_VILLAGER_HALL = 80;
    private static final int CONFIDENCE_LONG_VINES = 75;
    private static final int CONFIDENCE_MOB_FARM = 75;
    private static final int CONFIDENCE_STORAGE = 70;
    private static final int CONFIDENCE_REDSTONE = 65;
    private static final int CONFIDENCE_PLAYER_BLOCKS = 60;
    
    // ============================================================
    // COLORS
    // ============================================================
    
    private static final Color COLOR_SPAWNER = new Color(255, 50, 50, 160);
    private static final Color COLOR_REDSTONE = new Color(255, 100, 50, 150);
    private static final Color COLOR_GROWTH = new Color(100, 255, 100, 140);
    private static final Color COLOR_VILLAGER = new Color(50, 150, 255, 140);
    private static final Color COLOR_STORAGE = new Color(255, 200, 50, 130);
    private static final Color COLOR_MOB = new Color(200, 100, 255, 130);
    
    private static final Color TRACER_COLOR = new Color(255, 100, 50, 200);
    
    // ============================================================
    // DATA STORAGE
    // ============================================================
    
    private final Map<Long, ChunkDetectionData> detectedChunks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> notifyQueue = new ConcurrentLinkedQueue<>();
    
    // Growth tracking
    private final Map<BlockPos, Integer> bambooHeights = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> vineLengths = new ConcurrentHashMap<>();
    private final Set<BlockPos> maxGrowthCrops = ConcurrentHashMap.newKeySet();
    
    // Redstone tracking
    private final Map<BlockPos, AtomicInteger> redstoneChangeCount = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> redstoneLastChange = new ConcurrentHashMap<>();
    private final Set<ChunkPos> redstoneClocks = ConcurrentHashMap.newKeySet();
    
    // Entity tracking
    private final Map<ChunkPos, Integer> villagerCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> mobCounts = new ConcurrentHashMap<>();
    
    // Block tracking
    private final Map<ChunkPos, Integer> chestCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> hopperCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> furnaceCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> playerBlockCounts = new ConcurrentHashMap<>();
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
        
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 180000; }
        
        Color getColor() {
            if (confidence >= 90) return COLOR_SPAWNER;
            if (confidence >= 80) return COLOR_REDSTONE;
            if (confidence >= 70) return COLOR_STORAGE;
            if (confidence >= 60) return COLOR_GROWTH;
            return COLOR_MOB;
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
        final String label;
        RenderBox(Box box, Color fill, Color line, String label) {
            this.box = box;
            this.fill = fill;
            this.line = line;
            this.label = label;
        }
    }
    
    private static class TracerPoint {
        final Vec3d pos;
        final long time;
        TracerPoint(Vec3d pos) { this.pos = pos; this.time = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - time < 1000; }
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public BaseLeakDebug() {
        super(GlazedAddon.esp, "base-leak-debug", "§c§lBASE LEAK §8| §7Advanced Player Activity Detector");
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
        
        clearData();
        
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk wc) queueChunkScan(wc);
        }
        
        ChatUtils.info("BaseLeakDebug", "§c§lBASE LEAK DETECTOR §aACTIVATED");
        ChatUtils.info("BaseLeakDebug", "§7- Growth detection: §aON");
        ChatUtils.info("BaseLeakDebug", "§7- Redstone detection: §aON");
        ChatUtils.info("BaseLeakDebug", "§7- Entity detection: §aON");
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
        clearData();
        ChatUtils.info("BaseLeakDebug", "§cBaseLeakDebug deactivated");
    }
    
    private void clearData() {
        detectedChunks.clear();
        scannedChunks.clear();
        notifiedChunks.clear();
        notifyQueue.clear();
        bambooHeights.clear();
        vineLengths.clear();
        maxGrowthCrops.clear();
        redstoneChangeCount.clear();
        redstoneLastChange.clear();
        redstoneClocks.clear();
        villagerCounts.clear();
        mobCounts.clear();
        chestCounts.clear();
        hopperCounts.clear();
        furnaceCounts.clear();
        playerBlockCounts.clear();
        beaconChunks.clear();
        spawnerChunks.clear();
        renderBoxes.clear();
        tracerPoints.clear();
    }
    
    // ============================================================
    // PACKET CAPTURE
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            processBlockEntity(packet);
        }
        
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            processBlockUpdate(packet);
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
            
            ChunkPos chunkPos = new ChunkPos(pos);
            long packedPos = chunkPos.toLong();
            
            if (type.contains("Spawner")) {
                spawnerChunks.add(chunkPos);
                addDetection(packedPos, CONFIDENCE_SPAWNER, "§c§lSPAWNER");
            }
            
            if (type.contains("Beacon") && detectBeacons()) {
                beaconChunks.add(chunkPos);
                addDetection(packedPos, CONFIDENCE_BEACON, "§6BEACON");
            }
            
        } catch (Exception ignored) {}
    }
    
    private void processBlockUpdate(BlockUpdateS2CPacket packet) {
        if (!detectRedstoneActivity.get()) return;
        
        try {
            BlockPos pos = packet.getPos();
            Block block = extractBlock(packet);
            
            if (block == Blocks.REDSTONE_WIRE) {
                int power = extractRedstonePower(packet);
                long now = System.currentTimeMillis();
                
                if (power > 0) {
                    AtomicInteger count = redstoneChangeCount.computeIfAbsent(pos, k -> new AtomicInteger(0));
                    Long last = redstoneLastChange.get(pos);
                    if (last == null) {
                        redstoneLastChange.put(pos, now);
                        count.set(0);
                    } else if (now - last < 1000) {
                        count.incrementAndGet();
                        if (count.get() >= redstoneClockThreshold.get()) {
                            ChunkPos chunkPos = new ChunkPos(pos);
                            if (!redstoneClocks.contains(chunkPos)) {
                                redstoneClocks.add(chunkPos);
                                addDetection(chunkPos.toLong(), CONFIDENCE_REDSTONE_CLOCK, "§cREDSTONE CLOCK");
                            }
                        }
                        redstoneLastChange.put(pos, now);
                    } else {
                        count.set(0);
                        redstoneLastChange.put(pos, now);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void processEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            double y = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == double.class && y == 0) y = f.getDouble(packet);
            }
            
            // Only track underground entities (potential base indicators)
            if (y <= 30) {
                // Entity type detection would go here
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHUNK SCANNING - Growth Detection
    // ============================================================
    
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive || scanExecutor == null) return;
        if (event.chunk() instanceof WorldChunk wc) queueChunkScan(wc);
    }
    
    private void queueChunkScan(WorldChunk chunk) {
        if (!isActive || scanExecutor == null) return;
        long packedPos = chunk.getPos().toLong();
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
        int playerBlocks = 0;
        
        int startX = pos.getStartX();
        int startZ = pos.getStartZ();
        int bottomY = mc.world.getBottomY();
        int topY = mc.world.getTopYInclusive();
        
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                int surfaceY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - startX, z - startZ);
                
                // Scan below surface (underground)
                for (int y = bottomY; y < Math.min(topY, surfaceY - 3); y++) {
                    mutablePos.set(x, y, z);
                    Block block = chunk.getBlockState(mutablePos).getBlock();
                    
                    // GROWTH DETECTION - Max growth crops (underground farms)
                    if (detectMaxGrowth.get() && isMaxGrowthCrop(chunk, mutablePos, block)) {
                        if (!maxGrowthCrops.contains(mutablePos.toImmutable())) {
                            maxGrowthCrops.add(mutablePos.toImmutable());
                            addDetection(packedPos, CONFIDENCE_MAX_GROWTH, "§aMAX GROWTH CROPS");
                        }
                    }
                    
                    // BAMBOO HEIGHT DETECTION
                    if (detectLongBamboo.get() && block == Blocks.BAMBOO) {
                        int height = measureBambooHeight(chunk, mutablePos);
                        if (height >= bambooMinHeight.get()) {
                            bambooHeights.put(mutablePos.toImmutable(), height);
                            addDetection(packedPos, CONFIDENCE_LONG_BAMBOO, "§aLONG BAMBOO (" + height + " blocks)");
                        }
                    }
                    
                    // VINE LENGTH DETECTION
                    if (detectLongVines.get() && block == Blocks.VINE) {
                        int length = measureVineLength(chunk, mutablePos);
                        if (length >= vineMinLength.get()) {
                            vineLengths.put(mutablePos.toImmutable(), length);
                            addDetection(packedPos, CONFIDENCE_LONG_VINES, "§aLONG VINES (" + length + " blocks)");
                        }
                    }
                    
                    // STORAGE DETECTION
                    if (detectStorage.get()) {
                        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) chestCount++;
                        if (block == Blocks.HOPPER) hopperCount++;
                        if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE) furnaceCount++;
                    }
                    
                    // PLAYER BLOCKS DETECTION
                    if (isPlayerPlacedBlock(block)) playerBlocks++;
                }
            }
        }
        
        // Store counts
        if (chestCount > 0) chestCounts.put(pos, chestCount);
        if (hopperCount > 0) hopperCounts.put(pos, hopperCount);
        if (furnaceCount > 0) furnaceCounts.put(pos, furnaceCount);
        if (playerBlocks > 0) playerBlockCounts.put(pos, playerBlocks);
        
        // STORAGE CONCENTRATION
        int totalStorage = chestCount + hopperCount + furnaceCount;
        if (totalStorage >= storageThreshold.get()) {
            addDetection(packedPos, CONFIDENCE_STORAGE, "§eSTORAGE (" + totalStorage + " blocks)");
        }
        
        // PLAYER BLOCKS
        if (playerBlocks >= 8) {
            addDetection(packedPos, CONFIDENCE_PLAYER_BLOCKS, "§7PLAYER BLOCKS (" + playerBlocks + ")");
        }
    }
    
    // ============================================================
    // ENTITY SCANNING (Tick handler for real-time entities)
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
        
        // Entity scanning every 20 ticks
        if (tickCounter % 20 == 0) {
            scanEntities();
        }
        
        // Cleanup
        if (tickCounter % 100 == 0) cleanup();
        if (tickCounter % 5 == 0) { updateRenderBoxes(); updateTracers(); }
    }
    
    private void scanEntities() {
        if (mc.world == null) return;
        
        Map<ChunkPos, Integer> localVillagers = new HashMap<>();
        Map<ChunkPos, Integer> localMobs = new HashMap<>();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            
            ChunkPos cp = entity.getChunkPos();
            
            if (entity instanceof VillagerEntity) {
                localVillagers.put(cp, localVillagers.getOrDefault(cp, 0) + 1);
            } else if (entity instanceof MobEntity) {
                localMobs.put(cp, localMobs.getOrDefault(cp, 0) + 1);
            }
        }
        
        // Update counts and detect concentrations
        for (Map.Entry<ChunkPos, Integer> entry : localVillagers.entrySet()) {
            long packedPos = entry.getKey().toLong();
            int count = entry.getValue();
            villagerCounts.put(entry.getKey(), count);
            
            if (count >= villagerThreshold.get()) {
                addDetection(packedPos, CONFIDENCE_VILLAGER_HALL, "§bVILLAGERS (" + count + ")");
            }
        }
        
        for (Map.Entry<ChunkPos, Integer> entry : localMobs.entrySet()) {
            long packedPos = entry.getKey().toLong();
            int count = entry.getValue();
            mobCounts.put(entry.getKey(), count);
            
            if (count >= mobThreshold.get()) {
                addDetection(packedPos, CONFIDENCE_MOB_FARM, "§dMOB FARM (" + count + " mobs)");
            }
        }
    }
    
    // ============================================================
    // GROWTH MEASUREMENT METHODS
    // ============================================================
    
    private boolean isMaxGrowthCrop(WorldChunk chunk, BlockPos pos, Block block) {
        BlockState state = chunk.getBlockState(pos);
        
        if (block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES) {
            return state.get(Properties.AGE_7) == 7;
        }
        if (block == Blocks.BEETROOTS) {
            return state.get(Properties.AGE_3) == 3;
        }
        if (block == Blocks.NETHER_WART) {
            return state.get(Properties.AGE_3) == 3;
        }
        if (block == Blocks.SWEET_BERRY_BUSH) {
            return state.get(Properties.AGE_3) == 3;
        }
        if (block == Blocks.COCOA) {
            return state.get(Properties.AGE_2) == 2;
        }
        return false;
    }
    
    private int measureBambooHeight(WorldChunk chunk, BlockPos pos) {
        int height = 1;
        BlockPos.Mutable current = pos.mutableCopy();
        
        // Go up
        while (current.getY() < 320) {
            current.move(0, 1, 0);
            if (chunk.getBlockState(current).getBlock() != Blocks.BAMBOO) break;
            height++;
        }
        
        return height;
    }
    
    private int measureVineLength(WorldChunk chunk, BlockPos pos) {
        int length = 1;
        BlockPos.Mutable current = pos.mutableCopy();
        
        // Go down
        while (current.getY() > chunk.getBottomY()) {
            current.move(0, -1, 0);
            Block block = chunk.getBlockState(current).getBlock();
            if (block != Blocks.VINE && block != Blocks.CAVE_VINES && block != Blocks.CAVE_VINES_PLANT) break;
            length++;
        }
        
        return length;
    }
    
    // ============================================================
    // HELPER METHODS
    // ============================================================
    
    private boolean detectBeacons() {
        return true; // Beacons are always detected
    }
    
    private boolean isPlayerPlacedBlock(Block block) {
        return block == Blocks.CRAFTING_TABLE ||
               block == Blocks.ENCHANTING_TABLE ||
               block == Blocks.ANVIL ||
               block == Blocks.CHIPPED_ANVIL ||
               block == Blocks.DAMAGED_ANVIL ||
               block == Blocks.GRINDSTONE ||
               block == Blocks.STONECUTTER ||
               block == Blocks.LOOM ||
               block == Blocks.CARTOGRAPHY_TABLE ||
               block == Blocks.SMITHING_TABLE ||
               block == Blocks.FLETCHING_TABLE ||
               block == Blocks.LECTERN ||
               block == Blocks.COMPOSTER ||
               block == Blocks.BARREL ||
               block == Blocks.BLAST_FURNACE ||
               block == Blocks.SMOKER ||
               block == Blocks.CAMPFIRE ||
               block == Blocks.SOUL_CAMPFIRE ||
               block == Blocks.BREWING_STAND ||
               block == Blocks.CAULDRON ||
               block == Blocks.BELL;
    }
    
    private Block extractBlock(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field inner : state.getClass().getDeclaredFields()) {
                        if (inner.getType() == Block.class) {
                            inner.setAccessible(true);
                            return (Block) inner.get(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return Blocks.AIR;
    }
    
    private int extractRedstonePower(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field inner : state.getClass().getDeclaredFields()) {
                        if (inner.getName().toLowerCase().contains("power")) {
                            inner.setAccessible(true);
                            return inner.getInt(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private void addDetection(long packedPos, int confidence, String reason) {
        ChunkDetectionData existing = detectedChunks.get(packedPos);
        
        if (existing == null) {
            detectedChunks.put(packedPos, new ChunkDetectionData(packedPos, confidence, reason));
            if (!notifiedChunks.contains(packedPos)) notifyQueue.add(packedPos);
        } else if (confidence > existing.confidence) {
            existing.confidence = confidence;
            existing.reason = reason;
            if (!notifiedChunks.contains(packedPos)) notifyQueue.add(packedPos);
        }
    }
    
    private void processNotification(long packedPos) {
        if (notifiedChunks.contains(packedPos)) return;
        
        ChunkDetectionData data = detectedChunks.get(packedPos);
        if (data == null) return;
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
        String message = String.format(
            "§8[§c§lBLD§8] §7[§e%d, §e%d§8] §7- %s §8(§f%s§8) §7- §e%.0f§7 blocks away",
            chunkX, chunkZ, data.reason, confidenceStr, distance
        );
        ChatUtils.info("BaseLeakDebug", message);
        
        if (mc.player != null) {
            float pitch = data.confidence >= 90 ? 2.0f : data.confidence >= 75 ? 1.5f : 1.0f;
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, pitch);
        }
    }
    
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
            double yLevel = highlightHeight.get();
            
            Color color = data.getColor();
            Box box = new Box(x1, yLevel, z1, x2, yLevel + 0.5, z2);
            renderBoxes.add(new RenderBox(box, color, color, data.reason));
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
            tracerPoints.add(new TracerPoint(new Vec3d(centerX, 65.0, centerZ)));
        }
        
        lastTracerUpdate = now;
    }
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        detectedChunks.entrySet().removeIf(e -> !e.getValue().isValid());
        
        scannedChunks.removeIf(p -> {
            if (mc.world == null) return true;
            int x = ChunkPos.getPackedX(p);
            int z = ChunkPos.getPackedZ(p);
            return !mc.world.isChunkLoaded(x, z);
        });
        
        // Clear old redstone tracking
        redstoneChangeCount.clear();
        redstoneLastChange.clear();
        
        if (tickCounter % 600 == 0) notifiedChunks.clear();
    }
    
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
                tracer.pos.x, tracer.pos.y, tracer.pos.z, TRACER_COLOR);
        }
    }
    
    @Override
    public String getInfoString() {
        int high = (int) detectedChunks.values().stream().filter(d -> d.confidence >= 80).count();
        int med = (int) detectedChunks.values().stream().filter(d -> d.confidence >= 60 && d.confidence < 80).count();
        return String.format("§c%d §7| §e%d", high, med);
    }
}
