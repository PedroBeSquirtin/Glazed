package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.TraderLlamaEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkFinder extends Module {
    // ============ CHUNK FINDER CONSTANTS ============
    private static final int SUSPICION_THRESHOLD = 35;
    private static final int MAX_RENDER_DISTANCE = 32;
    private static final int CLEANUP_INTERVAL_MS = 30000;
    private static final int CHUNK_SCAN_DELAY_MS = 50;
    private static final int CHUNKS_PER_TICK = 8;
    private static final double TRACER_HEIGHT = 60.0;

    // Y-Level detection rules
    private static final int DEEPSLATE_MAX_Y = 20;
    private static final int BAMBOO_MIN_Y = -64;
    private static final int BAMBOO_MAX_Y = 320;
    private static final int VINES_MIN_Y = -64;
    private static final int VINES_MAX_Y = 320;
    private static final int AMETHYST_MIN_Y = -64;
    private static final int AMETHYST_MAX_Y = 320;
    private static final int SPAWNER_MIN_Y = -64;
    private static final int SPAWNER_MAX_Y = 320;
    private static final int BEEHIVE_MIN_Y = -64;
    private static final int BEEHIVE_MAX_Y = 320;

    // Detection thresholds
    private static final int BAMBOO_HEIGHT_THRESHOLD = 16;
    private static final int AMETHYST_CLUSTER_REQUIRED = 7; // Changed from 5 to 7
    private static final int AMETHYST_CLUSTER_POINTS = 6;
    private static final int LONG_VINES_THRESHOLD = 30;
    private static final int ROTATED_DEEPSLATE_POINTS = 3;
    private static final int SPAWNER_POINTS = 10;
    
    private static final int TRADER_LLAMA_REQUIRED = 2;
    private static final int WANDERING_TRADER_REQUIRED = 1;
    private static final int TRADER_COMBO_POINTS = 9;
    private static final int BEEHIVE_HONEY_POINTS = 5;

    // ============ HOLE ESP CONSTANTS ============
    private static final Direction[] DIRECTIONS = { Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH };
    private static final int MIN_HOLE_DEPTH = 4;

    // ============ COLORS ============
    private static final Color CHUNK_COLOR = new Color(150, 255, 150, 180);
    private static final Color CHUNK_OUTLINE_COLOR = new Color(100, 255, 100, 255);
    private static final Color TRACER_COLOR = new Color(100, 255, 100, 200);
    
    private static final Color HOLE_1X1_LINE = new Color(255, 255, 255, 200);
    private static final Color HOLE_1X1_SIDE = new Color(255, 255, 255, 100);
    private static final Color HOLE_3X1_LINE = new Color(255, 255, 0, 200);
    private static final Color HOLE_3X1_SIDE = new Color(255, 255, 0, 100);

    // ============ SETTINGS ============
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgRender = settings.createGroup("Rendering");

    private final Setting<Boolean> holeESP = sgGeneral.add(new BoolSetting.Builder()
        .name("hole-esp")
        .description("Highlight 1x1 and 3x1 holes")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chunkTracer = sgRender.add(new BoolSetting.Builder()
        .name("chunk-tracer")
        .description("Draw smooth tracers to detected chunks at Y=60")
        .defaultValue(true)
        .build()
    );

    // ============ DATA STRUCTURES ============
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, ChunkAnalysis> chunkDataMap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> totalLoadTime = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> lastScanTime = new ConcurrentHashMap<>();
    private final AtomicInteger activeScans = new AtomicInteger(0);
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final Map<UUID, Set<ChunkPos>> sessionChunks = new ConcurrentHashMap<>();
    private UUID currentSessionId;

    // For merged tracers - groups adjacent chunks
    private final Map<ChunkGroupKey, ChunkGroup> chunkGroups = new ConcurrentHashMap<>();

    private final Long2ObjectMap<HoleChunk> holeChunks = new Long2ObjectOpenHashMap<>();
    private final Queue<Chunk> chunkQueue = new LinkedList<>();
    private final Set<Box> holes1x1 = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Box> holes3x1 = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<ChunkPos, EntityCount> entityCounts = new ConcurrentHashMap<>();

    public ChunkFinder() {
        super(GlazedAddon.esp, "chunk-finder", "Finds suspicious chunks and highlights holes");
    }

    // ============ DATA CLASSES ============
    private static class ChunkAnalysis {
        int bambooCount = 0;
        int amethystClusters = 0;
        int longVines = 0;
        int rotatedDeepslate = 0;
        int spawners = 0;
        int traderLlamas = 0;
        int wanderingTraders = 0;
        int beehivesHoney5 = 0;
    }

    private static class EntityCount {
        int traderLlamas = 0;
        int wanderingTraders = 0;
        long lastUpdate = 0;
    }

    private static class HoleChunk {
        final int x, z;
        boolean marked;
        
        HoleChunk(int x, int z) {
            this.x = x;
            this.z = z;
            this.marked = true;
        }
        
        long getKey() {
            return ChunkPos.toLong(x, z);
        }
    }

    // Key for chunk groups (based on group ID)
    private static class ChunkGroupKey {
        final int id;
        
        ChunkGroupKey(int id) {
            this.id = id;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkGroupKey that = (ChunkGroupKey) o;
            return id == that.id;
        }
        
        @Override
        public int hashCode() {
            return id;
        }
    }

    // Class to group adjacent chunks for merged tracers
    private static class ChunkGroup {
        final Set<ChunkPos> chunks = new HashSet<>();
        double centerX;
        double centerZ;
        boolean dirty = true;
        int groupId;
        
        ChunkGroup(int id) {
            this.groupId = id;
        }
        
        void addChunk(ChunkPos pos) {
            chunks.add(pos);
            dirty = true;
        }
        
        void removeChunk(ChunkPos pos) {
            chunks.remove(pos);
            dirty = true;
        }
        
        boolean isEmpty() {
            return chunks.isEmpty();
        }
        
        void updateCenter() {
            if (chunks.isEmpty()) return;
            double sumX = 0, sumZ = 0;
            for (ChunkPos pos : chunks) {
                sumX += pos.getStartX() + 8;
                sumZ += pos.getStartZ() + 8;
            }
            centerX = sumX / chunks.size();
            centerZ = sumZ / chunks.size();
            dirty = false;
        }
        
        double getCenterX() {
            if (dirty) updateCenter();
            return centerX;
        }
        
        double getCenterZ() {
            if (dirty) updateCenter();
            return centerZ;
        }
        
        boolean isAdjacent(ChunkPos pos) {
            for (ChunkPos chunk : chunks) {
                int dx = Math.abs(chunk.x - pos.x);
                int dz = Math.abs(chunk.z - pos.z);
                if (dx <= 1 && dz <= 1 && !(dx == 0 && dz == 0)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ============ MODULE LIFECYCLE ============
    @Override
    public void onActivate() {
        if (mc.world == null) return;
        
        clearData();
        isScanning.set(true);
        
        currentSessionId = UUID.randomUUID();
        sessionChunks.put(currentSessionId, ConcurrentHashMap.newKeySet());
        
        new Thread(this::scanAllChunks, "ChunkFinder-Scanner").start();
    }

    @Override
    public void onDeactivate() {
        isScanning.set(false);
        clearData();
    }

    private void clearData() {
        suspiciousChunks.clear();
        chunkDataMap.clear();
        totalLoadTime.clear();
        scannedChunks.clear();
        lastScanTime.clear();
        activeScans.set(0);
        sessionChunks.clear();
        currentSessionId = null;
        entityCounts.clear();
        chunkGroups.clear();
        
        holeChunks.clear();
        chunkQueue.clear();
        holes1x1.clear();
        holes3x1.clear();
    }

    // ============ CHUNK GROUP MANAGEMENT ============
    private void updateChunkGroups() {
        chunkGroups.clear();
        
        if (suspiciousChunks.isEmpty()) return;
        
        // Create a list of all suspicious chunks
        List<ChunkPos> chunks = new ArrayList<>(suspiciousChunks);
        boolean[] processed = new boolean[chunks.size()];
        int nextGroupId = 0;
        
        // Group adjacent chunks using BFS
        for (int i = 0; i < chunks.size(); i++) {
            if (processed[i]) continue;
            
            ChunkGroup group = new ChunkGroup(nextGroupId++);
            Queue<ChunkPos> queue = new LinkedList<>();
            queue.add(chunks.get(i));
            processed[i] = true;
            
            while (!queue.isEmpty()) {
                ChunkPos current = queue.poll();
                group.addChunk(current);
                
                // Find all adjacent chunks
                for (int j = 0; j < chunks.size(); j++) {
                    if (processed[j]) continue;
                    
                    ChunkPos other = chunks.get(j);
                    int dx = Math.abs(current.x - other.x);
                    int dz = Math.abs(current.z - other.z);
                    
                    if (dx <= 1 && dz <= 1) {
                        queue.add(other);
                        processed[j] = true;
                    }
                }
            }
            
            chunkGroups.put(new ChunkGroupKey(group.groupId), group);
        }
    }

    // ============ SCANNING ============
    private void scanAllChunks() {
        try {
            for (Chunk chunk : Utils.chunks()) {
                if (!isScanning.get() || mc.world == null) break;
                if (chunk instanceof WorldChunk worldChunk) {
                    scanChunkIfNeeded(worldChunk);
                    Thread.sleep(CHUNK_SCAN_DELAY_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (!isScanning.get() || mc.world == null) return;
        if (event.chunk() instanceof WorldChunk worldChunk) {
            ChunkPos pos = worldChunk.getPos();
            
            if (currentSessionId != null) {
                Set<ChunkPos> session = sessionChunks.get(currentSessionId);
                if (session != null) session.add(pos);
            }
            
            scanChunkIfNeeded(worldChunk);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (currentSessionId != null) {
            Set<ChunkPos> session = sessionChunks.get(currentSessionId);
            if (session != null) {
                for (Chunk chunk : Utils.chunks(true)) {
                    if (chunk instanceof WorldChunk) {
                        session.add(chunk.getPos());
                    }
                }
            }
        }

        if (mc.world.getTime() % 20 == 0) {
            scanEntities();
        }

        if (holeESP.get()) {
            synchronized (holeChunks) {
                for (HoleChunk hChunk : holeChunks.values()) hChunk.marked = false;

                for (Chunk chunk : Utils.chunks(true)) {
                    long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);

                    if (holeChunks.containsKey(key)) holeChunks.get(key).marked = true;
                    else if (!chunkQueue.contains(chunk)) {
                        chunkQueue.add(chunk);
                    }
                }

                processHoleChunkQueue();
                holeChunks.values().removeIf(hChunk -> !hChunk.marked);
            }
            removeDistantHoles();
        }

        if (mc.world.getTime() % 200 == 0) {
            cleanupDistantChunks();
        }
    }

    private void scanEntities() {
        if (mc.world == null) return;
        
        entityCounts.clear();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity == null) continue;
            
            ChunkPos pos = entity.getChunkPos();
            EntityCount count = entityCounts.computeIfAbsent(pos, k -> new EntityCount());
            count.lastUpdate = System.currentTimeMillis();
            
            if (entity instanceof TraderLlamaEntity) {
                count.traderLlamas++;
            } else if (entity instanceof WanderingTraderEntity) {
                count.wanderingTraders++;
            }
        }
        
        for (Map.Entry<ChunkPos, EntityCount> entry : entityCounts.entrySet()) {
            ChunkPos pos = entry.getKey();
            EntityCount count = entry.getValue();
            
            ChunkAnalysis analysis = chunkDataMap.get(pos);
            if (analysis == null) {
                analysis = new ChunkAnalysis();
                chunkDataMap.put(pos, analysis);
            }
            
            analysis.traderLlamas = count.traderLlamas;
            analysis.wanderingTraders = count.wanderingTraders;
        }
    }

    // ============ CHUNK FINDER LOGIC ============
    private void scanChunkIfNeeded(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        long now = System.currentTimeMillis();
        
        Long lastScan = lastScanTime.get(pos);
        if (lastScan != null && now - lastScan < CLEANUP_INTERVAL_MS && scannedChunks.contains(pos)) return;
        
        if (activeScans.get() >= 3) return;
        
        activeScans.incrementAndGet();
        try {
            scanChunk(chunk);
        } finally {
            activeScans.decrementAndGet();
            lastScanTime.put(pos, now);
        }
    }

    private void scanChunk(WorldChunk chunk) {
        if (!isScanning.get() || mc.world == null) return;
        
        ChunkPos pos = chunk.getPos();
        if (scannedChunks.contains(pos) && !shouldRescan(pos)) return;
        
        ChunkAnalysis analysis = new ChunkAnalysis();
        ChunkSection[] sections = chunk.getSectionArray();
        int chunkBottomY = chunk.getBottomY();
        
        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            if (!isScanning.get()) return;
            
            ChunkSection section = sections[sectionIdx];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = chunkBottomY + sectionIdx * 16;
            int startY = 0;
            int endY = 15;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = startY; y <= endY; y++) {
                        if (!isScanning.get()) return;
                        
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;

                        int worldY = sectionBaseY + y;
                        BlockPos blockPos = new BlockPos(
                            chunk.getPos().getStartX() + x,
                            worldY,
                            chunk.getPos().getStartZ() + z
                        );

                        analyzeBlock(blockPos, state, analysis);
                    }
                }
            }
        }

        EntityCount entityCount = entityCounts.get(pos);
        if (entityCount != null) {
            analysis.traderLlamas = entityCount.traderLlamas;
            analysis.wanderingTraders = entityCount.wanderingTraders;
        }

        int score = calculateScore(pos, analysis);
        chunkDataMap.put(pos, analysis);
        evaluateChunk(pos, score);
        scannedChunks.add(pos);
    }

    private boolean shouldRescan(ChunkPos pos) {
        Long lastScore = lastScanTime.get(pos);
        if (lastScore == null) return true;
        return System.currentTimeMillis() - lastScore > 300000;
    }

    private void analyzeBlock(BlockPos pos, BlockState state, ChunkAnalysis analysis) {
        Block block = state.getBlock();
        int y = pos.getY();
        
        if (block == Blocks.BAMBOO && y >= BAMBOO_MIN_Y && y <= BAMBOO_MAX_Y) {
            int height = getPlantHeight(pos, block);
            if (height >= BAMBOO_HEIGHT_THRESHOLD) {
                analysis.bambooCount++;
            }
        }
        
        if (block == Blocks.AMETHYST_CLUSTER && y >= AMETHYST_MIN_Y && y <= AMETHYST_MAX_Y) {
            analysis.amethystClusters++;
        }
        
        if ((block == Blocks.VINE || block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) &&
            y >= VINES_MIN_Y && y <= VINES_MAX_Y) {
            int height = getVineHeight(pos);
            if (height >= LONG_VINES_THRESHOLD) {
                analysis.longVines++;
            }
        }
        
        if (block == Blocks.DEEPSLATE && y <= DEEPSLATE_MAX_Y) {
            if (state.contains(net.minecraft.state.property.Properties.AXIS)) {
                var axis = state.get(net.minecraft.state.property.Properties.AXIS);
                if (axis != Direction.Axis.Y) {
                    analysis.rotatedDeepslate++;
                }
            }
        }
        
        if (block == Blocks.SPAWNER && y >= SPAWNER_MIN_Y && y <= SPAWNER_MAX_Y) {
            analysis.spawners++;
        }
        
        if (block == Blocks.BEEHIVE || block == Blocks.BEE_NEST) {
            if (state.contains(net.minecraft.state.property.Properties.HONEY_LEVEL)) {
                int honeyLevel = state.get(net.minecraft.state.property.Properties.HONEY_LEVEL);
                if (honeyLevel == 5) {
                    analysis.beehivesHoney5++;
                }
            }
        }
    }

    private int getPlantHeight(BlockPos pos, Block block) {
        int height = 1;
        BlockPos current = pos.down();
        
        while (current.getY() >= mc.world.getBottomY()) {
            BlockState state = mc.world.getBlockState(current);
            if (state.getBlock() != block) break;
            height++;
            current = current.down();
        }
        
        current = pos.up();
        while (current.getY() <= mc.world.getTopYInclusive()) {
            BlockState state = mc.world.getBlockState(current);
            if (state.getBlock() != block) break;
            height++;
            current = current.up();
        }
        
        return height;
    }

    private int getVineHeight(BlockPos pos) {
        int height = 1;
        BlockPos current = pos.down();
        
        while (current.getY() >= mc.world.getBottomY()) {
            BlockState state = mc.world.getBlockState(current);
            if (state.getBlock() != Blocks.VINE && 
                state.getBlock() != Blocks.CAVE_VINES && 
                state.getBlock() != Blocks.CAVE_VINES_PLANT) break;
            height++;
            current = current.down();
        }
        
        return height;
    }

    private int calculateScore(ChunkPos pos, ChunkAnalysis data) {
        int score = 0;
        
        score += data.bambooCount * 4;
        
        // Amethyst clusters - only count if 7 or more
        if (data.amethystClusters >= AMETHYST_CLUSTER_REQUIRED) {
            score += data.amethystClusters * AMETHYST_CLUSTER_POINTS;
        }
        
        score += data.longVines * 2;
        score += data.rotatedDeepslate * ROTATED_DEEPSLATE_POINTS;
        score += data.spawners * SPAWNER_POINTS;
        score += data.beehivesHoney5 * BEEHIVE_HONEY_POINTS;
        
        if (data.traderLlamas >= TRADER_LLAMA_REQUIRED && data.wanderingTraders >= WANDERING_TRADER_REQUIRED) {
            score += TRADER_COMBO_POINTS;
        }
        
        Long totalTime = totalLoadTime.get(pos);
        if (totalTime != null && totalTime >= 600000) score += 5;
        
        if (sessionChunks.size() >= 2) {
            int sessionsSeen = 0;
            for (Set<ChunkPos> session : sessionChunks.values()) {
                if (session.contains(pos)) sessionsSeen++;
            }
            if (sessionsSeen >= 2) score += 10;
        }
        
        Long totalLoad = totalLoadTime.get(pos);
        if (totalLoad != null && totalLoad >= 1800000) score += 20;
        
        return score;
    }

    private void evaluateChunk(ChunkPos pos, int score) {
        lastScanTime.put(pos, System.currentTimeMillis());
        
        ChunkAnalysis data = chunkDataMap.get(pos);
        boolean hasSpawners = data != null && data.spawners > 0;
        boolean hasTraderCombo = data != null && 
            data.traderLlamas >= TRADER_LLAMA_REQUIRED && 
            data.wanderingTraders >= WANDERING_TRADER_REQUIRED;
        
        boolean wasAdded = false;
        
        if (score >= SUSPICION_THRESHOLD || hasSpawners || hasTraderCombo) {
            wasAdded = suspiciousChunks.add(pos);
            if (wasAdded) {
                String reason;
                if (hasSpawners) {
                    reason = "Spawner detected!";
                } else if (hasTraderCombo) {
                    reason = "Trader + Llamas detected!";
                } else {
                    reason = String.format("Score: %d", score);
                }
                
                mc.execute(() -> {
                    mc.getToastManager().add(new MeteorToast(Items.COMPASS, "Chunk Finder", 
                        String.format("Suspicious chunk [%d, %d] - %s", pos.x, pos.z, reason)));
                    
                    mc.getSoundManager().play(PositionedSoundInstance.master(
                        SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.5f));
                });
            }
        } else {
            wasAdded = suspiciousChunks.remove(pos);
        }
        
        // Update chunk groups if the set changed
        if (wasAdded) {
            updateChunkGroups();
        }
    }

    // ============ HOLE ESP LOGIC ============
    private void processHoleChunkQueue() {
        int processed = 0;
        while (!chunkQueue.isEmpty() && processed < CHUNKS_PER_TICK) {
            Chunk chunk = chunkQueue.poll();
            if (chunk != null) {
                HoleChunk hChunk = new HoleChunk(chunk.getPos().x, chunk.getPos().z);
                holeChunks.put(hChunk.getKey(), hChunk);
                MeteorExecutor.execute(() -> searchHoleChunk(chunk));
                processed++;
            }
        }
    }

    private void searchHoleChunk(Chunk chunk) {
        if (!holeESP.get() || mc.world == null) return;
        
        ChunkSection[] sections = chunk.getSectionArray();
        int chunkBottomY = chunk.getBottomY();
        
        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            ChunkSection section = sections[sectionIdx];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = chunkBottomY + sectionIdx * 16;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        BlockPos pos = chunk.getPos().getBlockPos(x, sectionBaseY + y, z);
                        if (isPassableBlock(pos)) {
                            check1x1Hole(pos);
                            check3x1Hole(pos);
                        }
                    }
                }
            }
        }
    }

    private void check1x1Hole(BlockPos pos) {
        if (isValidHoleSection(pos)) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            while (isValidHoleSection(currentPos)) {
                currentPos.move(Direction.UP);
            }
            if (currentPos.getY() - pos.getY() >= MIN_HOLE_DEPTH) {
                Box holeBox = new Box(
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, currentPos.getY(), pos.getZ() + 1
                );
                if (!holes1x1.contains(holeBox) && holes1x1.stream().noneMatch(existing -> existing.intersects(holeBox))) {
                    holes1x1.add(holeBox);
                }
            }
        }
    }

    private void check3x1Hole(BlockPos pos) {
        if (isValid3x1HoleSectionX(pos)) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            while (isValid3x1HoleSectionX(currentPos)) {
                currentPos.move(Direction.UP);
            }
            if (currentPos.getY() - pos.getY() >= MIN_HOLE_DEPTH) {
                Box holeBox = new Box(
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 3, currentPos.getY(), pos.getZ() + 1
                );
                if (!holes3x1.contains(holeBox) && holes3x1.stream().noneMatch(existing -> existing.intersects(holeBox))) {
                    holes3x1.add(holeBox);
                }
            }
        }

        if (isValid3x1HoleSectionZ(pos)) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            while (isValid3x1HoleSectionZ(currentPos)) {
                currentPos.move(Direction.UP);
            }
            if (currentPos.getY() - pos.getY() >= MIN_HOLE_DEPTH) {
                Box holeBox = new Box(
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, currentPos.getY(), pos.getZ() + 3
                );
                if (!holes3x1.contains(holeBox) && holes3x1.stream().noneMatch(existing -> existing.intersects(holeBox))) {
                    holes3x1.add(holeBox);
                }
            }
        }
    }

    private boolean isValidHoleSection(BlockPos pos) {
        return isPassableBlock(pos) && 
               !isPassableBlock(pos.north()) && 
               !isPassableBlock(pos.south()) && 
               !isPassableBlock(pos.east()) && 
               !isPassableBlock(pos.west());
    }

    private boolean isValid3x1HoleSectionX(BlockPos pos) {
        return isPassableBlock(pos) &&
            isPassableBlock(pos.east()) &&
            isPassableBlock(pos.east(2)) &&
            !isPassableBlock(pos.north()) &&
            !isPassableBlock(pos.south()) &&
            !isPassableBlock(pos.east(3)) &&
            !isPassableBlock(pos.west()) &&
            !isPassableBlock(pos.east().north()) &&
            !isPassableBlock(pos.east().south()) &&
            !isPassableBlock(pos.east(2).north()) &&
            !isPassableBlock(pos.east(2).south());
    }

    private boolean isValid3x1HoleSectionZ(BlockPos pos) {
        return isPassableBlock(pos) &&
            isPassableBlock(pos.south()) &&
            isPassableBlock(pos.south(2)) &&
            !isPassableBlock(pos.east()) &&
            !isPassableBlock(pos.west()) &&
            !isPassableBlock(pos.south(3)) &&
            !isPassableBlock(pos.north()) &&
            !isPassableBlock(pos.south().east()) &&
            !isPassableBlock(pos.south().west()) &&
            !isPassableBlock(pos.south(2).east()) &&
            !isPassableBlock(pos.south(2).west());
    }

    private boolean isPassableBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        return shape.isEmpty() || !VoxelShapes.fullCube().equals(shape);
    }

    private void removeDistantHoles() {
        Set<WorldChunk> loadedChunks = new HashSet<>();
        for (Chunk chunk : Utils.chunks(true)) {
            if (chunk instanceof WorldChunk) {
                loadedChunks.add((WorldChunk) chunk);
            }
        }

        holes1x1.removeIf(box -> {
            BlockPos pos = new BlockPos((int)box.minX, (int)box.minY, (int)box.minZ);
            return !loadedChunks.contains(mc.world.getChunk(pos));
        });

        holes3x1.removeIf(box -> {
            BlockPos pos = new BlockPos((int)box.minX, (int)box.minY, (int)box.minZ);
            return !loadedChunks.contains(mc.world.getChunk(pos));
        });
    }

    // ============ CLEANUP ============
    private void cleanupDistantChunks() {
        if (mc.player == null) return;

        int playerX = (int) mc.player.getX() >> 4;
        int playerZ = (int) mc.player.getZ() >> 4;

        boolean changed = false;
        
        changed |= suspiciousChunks.removeIf(pos -> {
            int dx = Math.abs(pos.x - playerX);
            int dz = Math.abs(pos.z - playerZ);
            return dx > MAX_RENDER_DISTANCE || dz > MAX_RENDER_DISTANCE;
        });

        scannedChunks.removeIf(pos -> {
            int dx = Math.abs(pos.x - playerX);
            int dz = Math.abs(pos.z - playerZ);
            return dx > MAX_RENDER_DISTANCE + 10 || dz > MAX_RENDER_DISTANCE + 10;
        });

        chunkDataMap.entrySet().removeIf(entry -> {
            int dx = Math.abs(entry.getKey().x - playerX);
            int dz = Math.abs(entry.getKey().z - playerZ);
            return dx > MAX_RENDER_DISTANCE + 10 || dz > MAX_RENDER_DISTANCE + 10;
        });
        
        totalLoadTime.entrySet().removeIf(entry -> {
            int dx = Math.abs(entry.getKey().x - playerX);
            int dz = Math.abs(entry.getKey().z - playerZ);
            return dx > MAX_RENDER_DISTANCE + 10 || dz > MAX_RENDER_DISTANCE + 10;
        });
        
        changed |= !entityCounts.entrySet().removeIf(entry -> {
            int dx = Math.abs(entry.getKey().x - playerX);
            int dz = Math.abs(entry.getKey().z - playerZ);
            return dx > MAX_RENDER_DISTANCE + 10 || dz > MAX_RENDER_DISTANCE + 10;
        });
        
        // Update chunk groups if suspicious chunks changed
        if (changed) {
            updateChunkGroups();
        }
    }

    // ============ RENDERING ============
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || !isActive()) return;

        // Get player position with interpolation for smooth movement
        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        
        // Calculate camera position for tracers
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        // Determine start position based on perspective (like SkeletonESP)
        Vec3d startPos;
        if (mc.options.getPerspective() == Perspective.FIRST_PERSON && mc.player == mc.player) {
            // First person: start from camera position
            startPos = cameraPos;
        } else {
            // Third person: use player's eye position
            startPos = new Vec3d(
                playerPos.x,
                playerPos.y + mc.player.getEyeHeight(mc.player.getPose()),
                playerPos.z
            );
        }

        // Render tracers first (so they appear behind chunks)
        if (chunkTracer.get() && !chunkGroups.isEmpty()) {
            for (ChunkGroup group : chunkGroups.values()) {
                if (group.isEmpty()) continue;
                
                // Draw one tracer per group at the center point at Y=60
                double targetX = group.getCenterX();
                double targetZ = group.getCenterZ();
                double targetY = TRACER_HEIGHT;
                
                // Draw tracer line exactly like SkeletonESP draws lines
                event.renderer.line(
                    startPos.x, startPos.y, startPos.z,
                    targetX, targetY, targetZ,
                    TRACER_COLOR
                );
            }
        }

        // Render suspicious chunks
        if (!suspiciousChunks.isEmpty()) {
            int rendered = 0;
            for (ChunkPos pos : suspiciousChunks) {
                if (rendered++ >= MAX_RENDER_DISTANCE) break;
                renderChunkHighlight(event.renderer, pos);
            }
        }

        // Render holes if enabled
        if (holeESP.get()) {
            renderHoles(event.renderer);
        }
    }

    private void renderChunkHighlight(Renderer3D renderer, ChunkPos pos) {
        int startX = pos.getStartX();
        int startZ = pos.getStartZ();
        int endX = pos.getEndX();
        int endZ = pos.getEndZ();
        
        double y = 60;
        
        Box fillBox = new Box(startX, y, startZ, endX + 1, y + 0.1, endZ + 1);
        renderer.box(fillBox, CHUNK_COLOR, CHUNK_COLOR, ShapeMode.Both, 0);
        
        Box outlineBox = new Box(startX, y, startZ, endX + 1, y + 0.1, endZ + 1);
        renderer.box(outlineBox, CHUNK_OUTLINE_COLOR, CHUNK_OUTLINE_COLOR, ShapeMode.Lines, 0);
    }

    private void renderHoles(Renderer3D renderer) {
        for (Box box : holes1x1) {
            renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                HOLE_1X1_SIDE, HOLE_1X1_LINE, ShapeMode.Both, 0);
        }
        
        for (Box box : holes3x1) {
            renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                HOLE_3X1_SIDE, HOLE_3X1_LINE, ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() {
        return String.format("%d chunks | %d holes", suspiciousChunks.size(), holes1x1.size() + holes3x1.size());
    }
}
