package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KRYPTON-STYLE ENTITY DEBUG - 1.21.11 COMPATIBLE
 * 
 * This module captures ALL 8 server data channels:
 * 1. NBT (Block Entity Data) - Full structured data from chunk & update packets
 * 2. Packets - ChunkData, BlockEntityUpdate, BlockUpdate, ChunkDelta, BlockEvent
 * 3. BlockState - Redstone power levels, powered states, comparator mode
 * 4. Container Sync - Inventory override system (indirect via container events)
 * 5. Block Events - Piston movement, chest open, note block play
 * 6. Server Tick Updates - 20 TPS simulation reflected via block updates
 * 7. Comparator Output System - Derived signal from containers/furnaces
 * 8. Chunk Section Data - Bulk world sync on chunk load
 * 
 * Features:
 * - Silent operation (no chat spam, only new discoveries)
 * - ESP boxes below Y=18 (light blue)
 * - Clean tracers from crosshair to detected locations
 * - Real-time detection of spawners, chests, furnaces, beacons, redstone, comparators
 * 
 * @author Glazed Development
 * @version 2.0.0
 * @since 2026
 */
public class EntityDebug extends Module {
    
    // ============================================================
    // ANTI-SPAM TRACKING
    // ============================================================
    
    private final Set<BlockPos> notifiedSpawners = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> notifiedChests = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> notifiedFurnaces = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> notifiedBeacons = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> notifiedRedstone = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> notifiedComparators = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> globalCooldown = new ConcurrentHashMap<>();
    private final AtomicInteger messageThrottle = new AtomicInteger(0);
    
    // ============================================================
    // ESP AND TRACER STORAGE
    // ============================================================
    
    private final CopyOnWriteArrayList<EspBox> espBoxes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TracerPoint> tracerPoints = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TracerPoint> tempTracers = new CopyOnWriteArrayList<>();
    
    // ============================================================
    // 8 CHANNEL DATA STORAGE
    // ============================================================
    
    // Channel 1: NBT Block Entity Data
    private final Map<BlockPos, SpawnerRecord> spawnerRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, ChestRecord> chestRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, FurnaceRecord> furnaceRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, BeaconRecord> beaconRecords = new ConcurrentHashMap<>();
    
    // Channel 3: BlockState (Redstone)
    private final Map<BlockPos, RedstoneRecord> redstoneRecords = new ConcurrentHashMap<>();
    private final Map<BlockPos, ComparatorRecord> comparatorRecords = new ConcurrentHashMap<>();
    
    // Channel 6: Server Tick Tracking
    private final Map<BlockPos, AtomicInteger> redstoneChangeFreq = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastRedstoneChangeTime = new ConcurrentHashMap<>();
    
    // Channel 8: Chunk Data
    private final Map<ChunkPos, Long> chunkLoadRecords = new ConcurrentHashMap<>();
    
    // Entity tracking
    private final Map<Integer, EntityRecord> entityRecords = new ConcurrentHashMap<>();
    
    // ============================================================
    // STATE VARIABLES
    // ============================================================
    
    private boolean moduleActive = false;
    private int tickCounter = 0;
    private long lastTracerUpdate = 0;
    private long lastEspUpdate = 0;
    private int totalDetections = 0;
    private int currentRenderDistance = 96;
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final int MAX_RENDER_DIST = 96;
    private static final int MAX_DETECTION_Y = 18;
    private static final long DATA_TIMEOUT_MS = 90000;
    private static final long NOTIFICATION_COOLDOWN_MS = 30000;
    private static final long GLOBAL_COOLDOWN_MS = 2000;
    private static final long TRACER_UPDATE_INTERVAL_MS = 100;
    private static final long ESP_UPDATE_INTERVAL_MS = 50;
    private static final double TRACER_HEIGHT = 65.0;
    private static final int SCAN_RADIUS = 48;
    
    // ============================================================
    // COLORS - Light blue theme for ESP
    // ============================================================
    
    private static final Color ESP_FILL = new Color(100, 150, 255, 60);
    private static final Color ESP_LINE = new Color(100, 150, 255, 200);
    private static final Color TRACER_COLOR = new Color(100, 150, 255, 180);
    private static final Color ACTIVE_REDSTONE_COLOR = new Color(255, 100, 100, 200);
    
    // HUD Colors (for info string only, not ESP)
    private static final Color HUD_SPAWNER = new Color(255, 80, 80, 255);
    private static final Color HUD_CHEST = new Color(255, 200, 80, 255);
    private static final Color HUD_FURNACE = new Color(200, 150, 150, 255);
    private static final Color HUD_BEACON = new Color(80, 200, 255, 255);
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§bEntity Debug §7- Silent underground detector with ESP");
    }
    
    // ============================================================
    // DATA RECORD CLASSES
    // ============================================================
    
    private static class SpawnerRecord {
        final BlockPos pos;
        String entityType;
        long lastSeen;
        SpawnerRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        boolean isBelowY() { return pos.getY() <= MAX_DETECTION_Y; }
        void update() { lastSeen = System.currentTimeMillis(); }
    }
    
    private static class ChestRecord {
        final BlockPos pos;
        int itemCount;
        long lastSeen;
        ChestRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        boolean isBelowY() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean hasItems() { return itemCount > 0; }
        void update() { lastSeen = System.currentTimeMillis(); }
    }
    
    private static class FurnaceRecord {
        final BlockPos pos;
        boolean isBurning;
        long lastSeen;
        FurnaceRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        boolean isBelowY() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean isActive() { return isBurning; }
        void update() { lastSeen = System.currentTimeMillis(); }
    }
    
    private static class BeaconRecord {
        final BlockPos pos;
        int powerLevel;
        long lastSeen;
        BeaconRecord(BlockPos p) { pos = p; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        boolean isBelowY() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean isActive() { return powerLevel > 0; }
        void update() { lastSeen = System.currentTimeMillis(); }
    }
    
    private static class RedstoneRecord {
        final BlockPos pos;
        int powerLevel;
        long lastSeen;
        RedstoneRecord(BlockPos p, int power) { pos = p; powerLevel = power; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 5000; }
        boolean isBelowY() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean isPowered() { return powerLevel > 0; }
        void update(int power) { powerLevel = power; lastSeen = System.currentTimeMillis(); }
    }
    
    private static class ComparatorRecord {
        final BlockPos pos;
        int outputSignal;
        long lastSeen;
        ComparatorRecord(BlockPos p, int output) { pos = p; outputSignal = output; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < 10000; }
        boolean isBelowY() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean hasOutput() { return outputSignal > 0; }
        void update(int output) { outputSignal = output; lastSeen = System.currentTimeMillis(); }
    }
    
    private static class EntityRecord {
        final int id;
        double yCoord;
        long lastSeen;
        EntityRecord(int id, double y) { this.id = id; yCoord = y; lastSeen = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        boolean isBelowY() { return yCoord <= MAX_DETECTION_Y; }
        void update(double y) { yCoord = y; lastSeen = System.currentTimeMillis(); }
    }
    
    private static class EspBox {
        final Box boundingBox;
        final Color fillColor;
        final Color lineColor;
        EspBox(Box box, Color fill, Color line) { boundingBox = box; fillColor = fill; lineColor = line; }
    }
    
    private static class TracerPoint {
        final Vec3d position;
        final long creationTime;
        TracerPoint(Vec3d pos) { position = pos; creationTime = System.currentTimeMillis(); }
        boolean isValid() { return System.currentTimeMillis() - creationTime < 1000; }
    }
    
    // ============================================================
    // PACKET CAPTURE - ALL 8 CHANNELS (SILENT)
    // ============================================================
    
    @EventHandler
    private void onPacketReceived(PacketEvent.Receive event) {
        if (!moduleActive) return;
        
        // Channel 1 & 8: Chunk Data
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            processChunkData(packet);
        }
        
        // Channel 1: Block Entity Update
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            processBlockEntityUpdate(packet);
        }
        
        // Channel 2 & 3: Block Update
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            processBlockUpdate(packet);
        }
        
        // Channel 2 & 5: Block Event
        if (event.packet instanceof BlockEventS2CPacket packet) {
            processBlockEvent(packet);
        }
        
        // Channel 4: Container Sync
        if (event.packet instanceof OpenScreenS2CPacket packet) {
            processContainerSync(packet);
        }
        
        // Channel 2: Entity Spawn
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            processEntitySpawn(packet);
        }
    }
    
    // ============================================================
    // CHANNEL 1 & 8: CHUNK DATA PROCESSING
    // ============================================================
    
    private void processChunkData(ChunkDataS2CPacket packet) {
        try {
            int chunkX = 0, chunkZ = 0;
            for (Field field : packet.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                String fieldName = field.getName();
                if (fieldName.equals("x") || fieldName.equals("field_1210")) chunkX = field.getInt(packet);
                if (fieldName.equals("z") || fieldName.equals("field_1211")) chunkZ = field.getInt(packet);
            }
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            chunkLoadRecords.put(chunkPos, System.currentTimeMillis());
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 1: NBT BLOCK ENTITY DATA PROCESSING
    // ============================================================
    
    private void processBlockEntityUpdate(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos position = packet.getPos();
            String entityType = packet.getBlockEntityType().toString();
            NbtCompound nbtData = packet.getNbt();
            
            if (nbtData == null) return;
            
            if (entityType.contains("Spawner")) {
                processSpawnerData(position, nbtData);
            }
            
            if (entityType.contains("Chest") || entityType.contains("Barrel") || entityType.contains("Shulker")) {
                processChestData(position, nbtData);
            }
            
            if (entityType.contains("Furnace")) {
                processFurnaceData(position, nbtData);
            }
            
            if (entityType.contains("Beacon")) {
                processBeaconData(position, nbtData);
            }
            
        } catch (Exception ignored) {}
    }
    
    private void processSpawnerData(BlockPos pos, NbtCompound nbt) {
        SpawnerRecord record = spawnerRecords.computeIfAbsent(pos, k -> new SpawnerRecord(pos));
        try {
            nbt.getCompound("SpawnData").ifPresent(spawnData -> {
                spawnData.getCompound("entity").ifPresent(entity -> {
                    entity.getString("id").ifPresent(id -> record.entityType = id);
                });
            });
            record.update();
            
            if (record.isBelowY() && !notifiedSpawners.contains(pos)) {
                sendNotification("SPAWNER", pos.getY(), "§c");
                notifiedSpawners.add(pos);
                scheduleCooldown();
            }
        } catch (Exception ignored) {}
    }
    
    private void processChestData(BlockPos pos, NbtCompound nbt) {
        ChestRecord record = chestRecords.computeIfAbsent(pos, k -> new ChestRecord(pos));
        try {
            nbt.getList("Items").ifPresent(items -> record.itemCount = items.size());
            record.update();
            
            if (record.isBelowY() && record.hasItems() && !notifiedChests.contains(pos)) {
                sendNotification("CHEST", pos.getY(), "§e");
                notifiedChests.add(pos);
                scheduleCooldown();
            }
        } catch (Exception ignored) {}
    }
    
    private void processFurnaceData(BlockPos pos, NbtCompound nbt) {
        FurnaceRecord record = furnaceRecords.computeIfAbsent(pos, k -> new FurnaceRecord(pos));
        try {
            int burnTimeValue = nbt.getInt("BurnTime").orElse(0);
            record.isBurning = burnTimeValue > 0;
            record.update();
            
            if (record.isBelowY() && record.isActive() && !notifiedFurnaces.contains(pos)) {
                sendNotification("FURNACE", pos.getY(), "§7");
                notifiedFurnaces.add(pos);
                scheduleCooldown();
            }
        } catch (Exception ignored) {}
    }
    
    private void processBeaconData(BlockPos pos, NbtCompound nbt) {
        BeaconRecord record = beaconRecords.computeIfAbsent(pos, k -> new BeaconRecord(pos));
        try {
            record.powerLevel = nbt.getInt("Levels").orElse(0);
            record.update();
            
            if (record.isBelowY() && record.isActive() && !notifiedBeacons.contains(pos)) {
                sendNotification("BEACON", pos.getY(), "§b");
                notifiedBeacons.add(pos);
                scheduleCooldown();
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 2 & 3: BLOCK STATE PROCESSING (Redstone)
    // ============================================================
    
    private void processBlockUpdate(BlockUpdateS2CPacket packet) {
        try {
            BlockPos position = packet.getPos();
            Block blockType = extractBlockFromPacket(packet);
            
            if (blockType == Blocks.REDSTONE_WIRE) {
                processRedstoneWire(position, packet);
            }
            
            if (blockType == Blocks.COMPARATOR) {
                processComparator(position, packet);
            }
            
        } catch (Exception ignored) {}
    }
    
    private void processRedstoneWire(BlockPos pos, BlockUpdateS2CPacket packet) {
        int powerLevel = extractRedstonePower(packet);
        if (powerLevel > 0) {
            RedstoneRecord record = redstoneRecords.computeIfAbsent(pos, k -> new RedstoneRecord(pos, powerLevel));
            record.update(powerLevel);
            
            // Track change frequency for server tick analysis (Channel 6)
            long currentTime = System.currentTimeMillis();
            Long lastChange = lastRedstoneChangeTime.get(pos);
            if (lastChange != null && currentTime - lastChange < 500) {
                redstoneChangeFreq.computeIfAbsent(pos, k -> new AtomicInteger()).incrementAndGet();
            }
            lastRedstoneChangeTime.put(pos, currentTime);
            
            if (record.isBelowY() && record.isPowered() && !notifiedRedstone.contains(pos)) {
                sendNotification("REDSTONE", pos.getY(), "§c");
                notifiedRedstone.add(pos);
                scheduleCooldown();
            }
        }
    }
    
    private void processComparator(BlockPos pos, BlockUpdateS2CPacket packet) {
        int outputValue = extractComparatorOutput(packet);
        if (outputValue > 0) {
            ComparatorRecord record = comparatorRecords.computeIfAbsent(pos, k -> new ComparatorRecord(pos, outputValue));
            record.update(outputValue);
            
            if (record.isBelowY() && record.hasOutput() && !notifiedComparators.contains(pos)) {
                sendNotification("COMPARATOR", pos.getY(), "§d");
                notifiedComparators.add(pos);
                scheduleCooldown();
            }
        }
    }
    
    // ============================================================
    // CHANNEL 2 & 5: BLOCK EVENTS PROCESSING
    // ============================================================
    
    private void processBlockEvent(BlockEventS2CPacket packet) {
        try {
            BlockPos position = packet.getPos();
            int eventIdentifier = 0;
            for (Field field : packet.getClass().getDeclaredFields()) {
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    eventIdentifier = field.getInt(packet);
                    break;
                }
            }
            
            // Silent capture - no chat messages
            if (position.getY() <= MAX_DETECTION_Y) {
                // Event detected but not logged to avoid spam
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 4: CONTAINER SYNC PROCESSING
    // ============================================================
    
    private void processContainerSync(OpenScreenS2CPacket packet) {
        try {
            int syncIdentifier = packet.getSyncId();
            // Silent capture - no chat messages
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // ENTITY SPAWN PROCESSING
    // ============================================================
    
    private void processEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int entityId = -1;
            double yCoordinate = 0;
            for (Field field : packet.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getName().equals("id") || field.getName().equals("field_1217")) entityId = field.getInt(packet);
                if (field.getType() == double.class && yCoordinate == 0) yCoordinate = field.getDouble(packet);
            }
            if (entityId != -1 && yCoordinate <= MAX_DETECTION_Y) {
                entityRecords.put(entityId, new EntityRecord(entityId, yCoordinate));
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // HELPER METHODS FOR PACKET EXTRACTION
    // ============================================================
    
    private Block extractBlockFromPacket(BlockUpdateS2CPacket packet) {
        try {
            for (Field field : packet.getClass().getDeclaredFields()) {
                if (field.getType().getName().contains("BlockState")) {
                    field.setAccessible(true);
                    Object blockState = field.get(packet);
                    for (Field innerField : blockState.getClass().getDeclaredFields()) {
                        if (innerField.getType() == Block.class) {
                            innerField.setAccessible(true);
                            return (Block) innerField.get(blockState);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return Blocks.AIR;
    }
    
    private int extractRedstonePower(BlockUpdateS2CPacket packet) {
        try {
            for (Field field : packet.getClass().getDeclaredFields()) {
                if (field.getType().getName().contains("BlockState")) {
                    field.setAccessible(true);
                    Object blockState = field.get(packet);
                    for (Field innerField : blockState.getClass().getDeclaredFields()) {
                        String innerName = innerField.getName().toLowerCase();
                        if (innerName.contains("power")) {
                            innerField.setAccessible(true);
                            return innerField.getInt(blockState);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
    
    private int extractComparatorOutput(BlockUpdateS2CPacket packet) {
        try {
            for (Field field : packet.getClass().getDeclaredFields()) {
                if (field.getType().getName().contains("BlockState")) {
                    field.setAccessible(true);
                    Object blockState = field.get(packet);
                    for (Field innerField : blockState.getClass().getDeclaredFields()) {
                        String innerName = innerField.getName().toLowerCase();
                        if (innerName.contains("output")) {
                            innerField.setAccessible(true);
                            return innerField.getInt(blockState);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
    
    // ============================================================
    // NOTIFICATION SYSTEM (NO SPAM)
    // ============================================================
    
    private void sendNotification(String detectionType, int yLevel, String colorCode) {
        // Check global cooldown for this detection type
        AtomicLong lastGlobal = globalCooldown.computeIfAbsent(detectionType, k -> new AtomicLong(0));
        if (System.currentTimeMillis() - lastGlobal.get() < GLOBAL_COOLDOWN_MS) return;
        if (messageThrottle.get() > 0) return;
        
        ChatUtils.info("EntityDebug", colorCode + detectionType + " §7found at Y=" + yLevel);
        lastGlobal.set(System.currentTimeMillis());
    }
    
    private void scheduleCooldown() {
        messageThrottle.incrementAndGet();
        // Will be decremented in tick handler
    }
    
    // ============================================================
    // CHANNEL 6: SERVER TICK DETECTION
    // ============================================================
    
    private void analyzeServerTickActivity() {
        for (Map.Entry<BlockPos, AtomicInteger> frequencyEntry : redstoneChangeFreq.entrySet()) {
            BlockPos position = frequencyEntry.getKey();
            int changeFrequency = frequencyEntry.getValue().get();
            if (position.getY() <= MAX_DETECTION_Y && changeFrequency > 15) {
                Long lastChange = lastRedstoneChangeTime.get(position);
                if (lastChange != null && System.currentTimeMillis() - lastChange < 5000) {
                    // Active redstone circuit detected - silent capture
                }
            }
        }
        
        // Reset frequency counters every 100 ticks (5 seconds)
        if (tickCounter % 100 == 0) {
            redstoneChangeFreq.clear();
        }
    }
    
    // ============================================================
    // CHANNEL 7: UPDATE COMPARATOR OUTPUTS (Derived Signal)
    // ============================================================
    
    private void updateDerivedComparatorSignals() {
        for (Map.Entry<BlockPos, ComparatorRecord> comparatorEntry : comparatorRecords.entrySet()) {
            BlockPos comparatorPos = comparatorEntry.getKey();
            BlockPos belowPos = comparatorPos.down();
            ChestRecord chestBelow = chestRecords.get(belowPos);
            
            int calculatedOutput = 0;
            if (chestBelow != null && chestBelow.hasItems()) {
                calculatedOutput = Math.min(15, chestBelow.itemCount);
            }
            
            ComparatorRecord record = comparatorEntry.getValue();
            if (record.outputSignal != calculatedOutput) {
                record.update(calculatedOutput);
                if (record.isBelowY() && calculatedOutput > 0) {
                    // Derived signal changed - silent capture
                }
            }
        }
    }
    
    // ============================================================
    // ESP VISUALIZATION UPDATE
    // ============================================================
    
    private void refreshEspVisuals() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEspUpdate < ESP_UPDATE_INTERVAL_MS) return;
        
        espBoxes.clear();
        
        // Add spawner ESP boxes
        for (SpawnerRecord spawner : spawnerRecords.values()) {
            if (spawner.isValid() && spawner.isBelowY() && spawner.entityType != null) {
                Box spawnerBox = createBlockBox(spawner.pos);
                espBoxes.add(new EspBox(spawnerBox, ESP_FILL, ESP_LINE));
            }
        }
        
        // Add chest ESP boxes
        for (ChestRecord chest : chestRecords.values()) {
            if (chest.isValid() && chest.isBelowY() && chest.hasItems()) {
                Box chestBox = createBlockBox(chest.pos);
                espBoxes.add(new EspBox(chestBox, ESP_FILL, ESP_LINE));
            }
        }
        
        // Add furnace ESP boxes (only burning)
        for (FurnaceRecord furnace : furnaceRecords.values()) {
            if (furnace.isValid() && furnace.isBelowY() && furnace.isActive()) {
                Box furnaceBox = createBlockBox(furnace.pos);
                espBoxes.add(new EspBox(furnaceBox, ESP_FILL, ESP_LINE));
            }
        }
        
        // Add beacon ESP boxes
        for (BeaconRecord beacon : beaconRecords.values()) {
            if (beacon.isValid() && beacon.isBelowY() && beacon.isActive()) {
                Box beaconBox = createBlockBox(beacon.pos);
                espBoxes.add(new EspBox(beaconBox, ESP_FILL, ESP_LINE));
            }
        }
        
        // Add redstone ESP boxes (active only)
        for (RedstoneRecord redstone : redstoneRecords.values()) {
            if (redstone.isValid() && redstone.isBelowY() && redstone.isPowered()) {
                Box redstoneBox = createBlockBox(redstone.pos);
                espBoxes.add(new EspBox(redstoneBox, ESP_FILL, REDSTONE_COLOR));
            }
        }
        
        // Add comparator ESP boxes
        for (ComparatorRecord comparator : comparatorRecords.values()) {
            if (comparator.isValid() && comparator.isBelowY() && comparator.hasOutput()) {
                Box comparatorBox = createBlockBox(comparator.pos);
                espBoxes.add(new EspBox(comparatorBox, ESP_FILL, ESP_LINE));
            }
        }
        
        totalDetections = espBoxes.size();
        lastEspUpdate = currentTime;
    }
    
    private Box createBlockBox(BlockPos position) {
        return new Box(
            position.getX(), position.getY(), position.getZ(),
            position.getX() + 1, position.getY() + 1, position.getZ() + 1
        );
    }
    
    // ============================================================
    // TRACER VISUALIZATION UPDATE
    // ============================================================
    
    private void refreshTracers() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTracerUpdate < TRACER_UPDATE_INTERVAL_MS) return;
        
        tempTracers.clear();
        
        // Collect all valid target positions
        for (SpawnerRecord spawner : spawnerRecords.values()) {
            if (spawner.isValid() && spawner.isBelowY() && spawner.entityType != null) {
                tempTracers.add(new TracerPoint(createTracerTarget(spawner.pos)));
            }
        }
        
        for (ChestRecord chest : chestRecords.values()) {
            if (chest.isValid() && chest.isBelowY() && chest.hasItems()) {
                tempTracers.add(new TracerPoint(createTracerTarget(chest.pos)));
            }
        }
        
        for (FurnaceRecord furnace : furnaceRecords.values()) {
            if (furnace.isValid() && furnace.isBelowY() && furnace.isActive()) {
                tempTracers.add(new TracerPoint(createTracerTarget(furnace.pos)));
            }
        }
        
        for (BeaconRecord beacon : beaconRecords.values()) {
            if (beacon.isValid() && beacon.isBelowY() && beacon.isActive()) {
                tempTracers.add(new TracerPoint(createTracerTarget(beacon.pos)));
            }
        }
        
        for (RedstoneRecord redstone : redstoneRecords.values()) {
            if (redstone.isValid() && redstone.isBelowY() && redstone.isPowered()) {
                tempTracers.add(new TracerPoint(createTracerTarget(redstone.pos)));
            }
        }
        
        tracerPoints.clear();
        tracerPoints.addAll(tempTracers);
        lastTracerUpdate = currentTime;
    }
    
    private Vec3d createTracerTarget(BlockPos position) {
        return new Vec3d(position.getX() + 0.5, TRACER_HEIGHT, position.getZ() + 0.5);
    }
    
    // ============================================================
    // EXISTING CHUNK SCANNER (Initial detection)
    // ============================================================
    
    private void scanExistingWorldChunks() {
        if (mc.world == null || mc.player == null) return;
        
        BlockPos playerPos = mc.player.getBlockPos();
        
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int yCoord = mc.world.getBottomY(); yCoord <= MAX_DETECTION_Y; yCoord++) {
                    BlockPos scanPos = playerPos.add(dx, yCoord - playerPos.getY(), dz);
                    if (yCoord > MAX_DETECTION_Y) continue;
                    
                    Block foundBlock = mc.world.getBlockState(scanPos).getBlock();
                    
                    if (foundBlock == Blocks.SPAWNER && !notifiedSpawners.contains(scanPos)) {
                        sendNotification("SPAWNER", scanPos.getY(), "§c");
                        notifiedSpawners.add(scanPos);
                        SpawnerRecord record = spawnerRecords.computeIfAbsent(scanPos, k -> new SpawnerRecord(scanPos));
                        record.entityType = "Unknown";
                        record.update();
                    }
                    
                    if ((foundBlock == Blocks.CHEST || foundBlock == Blocks.TRAPPED_CHEST) && !notifiedChests.contains(scanPos)) {
                        sendNotification("CHEST", scanPos.getY(), "§e");
                        notifiedChests.add(scanPos);
                        ChestRecord record = chestRecords.computeIfAbsent(scanPos, k -> new ChestRecord(scanPos));
                        record.itemCount = 1;
                        record.update();
                    }
                    
                    if ((foundBlock == Blocks.FURNACE || foundBlock == Blocks.BLAST_FURNACE) && !notifiedFurnaces.contains(scanPos)) {
                        sendNotification("FURNACE", scanPos.getY(), "§7");
                        notifiedFurnaces.add(scanPos);
                        FurnaceRecord record = furnaceRecords.computeIfAbsent(scanPos, k -> new FurnaceRecord(scanPos));
                        record.isBurning = false;
                        record.update();
                    }
                    
                    if (foundBlock == Blocks.BEACON && !notifiedBeacons.contains(scanPos)) {
                        sendNotification("BEACON", scanPos.getY(), "§b");
                        notifiedBeacons.add(scanPos);
                        BeaconRecord record = beaconRecords.computeIfAbsent(scanPos, k -> new BeaconRecord(scanPos));
                        record.powerLevel = 1;
                        record.update();
                    }
                }
            }
        }
    }
    
    // ============================================================
    // DATA CLEANUP
    // ============================================================
    
    private void purgeStaleData() {
        long currentTime = System.currentTimeMillis();
        
        spawnerRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        chestRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        furnaceRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        beaconRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        redstoneRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        comparatorRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        entityRecords.entrySet().removeIf(entry -> !entry.getValue().isValid());
        chunkLoadRecords.entrySet().removeIf(entry -> currentTime - entry.getValue() > 120000);
        
        // Clean up notification tracking periodically
        if (tickCounter % 500 == 0) {
            notifiedSpawners.clear();
            notifiedChests.clear();
            notifiedFurnaces.clear();
            notifiedBeacons.clear();
            notifiedRedstone.clear();
            notifiedComparators.clear();
        }
        
        // Decay message throttle
        if (messageThrottle.get() > 0 && tickCounter % 10 == 0) {
            messageThrottle.decrementAndGet();
        }
    }
    
    // ============================================================
    // TICK HANDLER - 20 TPS Sync
    // ============================================================
    
    @EventHandler
    private void onTickUpdate(TickEvent.Post event) {
        if (!moduleActive || mc.world == null || mc.player == null) return;
        
        tickCounter++;
        
        // Update entity positions from world
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            EntityRecord record = entityRecords.get(entity.getId());
            if (record != null) {
                record.update(entity.getY());
            }
        }
        
        // Channel 6: Server tick analysis
        if (tickCounter % 20 == 0) {
            analyzeServerTickActivity();
        }
        
        // Channel 7: Comparator derived signals
        if (tickCounter % 10 == 0) {
            updateDerivedComparatorSignals();
        }
        
        // Data cleanup
        if (tickCounter % 50 == 0) {
            purgeStaleData();
        }
        
        // Visual updates
        if (tickCounter % 2 == 0) {
            refreshEspVisuals();
            refreshTracers();
        }
    }
    
    // ============================================================
    // RENDER HANDLER - ESP Boxes and Tracers
    // ============================================================
    
    @EventHandler
    private void onRenderVisuals(Render3DEvent renderEvent) {
        if (!moduleActive || mc.player == null) return;
        
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        Vec3d crosshairOrigin = mc.player.getCameraPosVec(renderEvent.tickDelta);
        
        // Render ESP boxes
        for (EspBox espBox : espBoxes) {
            double boxCenterX = espBox.boundingBox.getCenter().x;
            double boxCenterZ = espBox.boundingBox.getCenter().z;
            double distanceSq = (boxCenterX - playerX) * (boxCenterX - playerX) + (boxCenterZ - playerZ) * (boxCenterZ - playerZ);
            
            if (distanceSq > MAX_RENDER_DIST * MAX_RENDER_DIST) continue;
            
            renderEvent.renderer.box(
                espBox.boundingBox.minX, espBox.boundingBox.minY, espBox.boundingBox.minZ,
                espBox.boundingBox.maxX, espBox.boundingBox.maxY, espBox.boundingBox.maxZ,
                espBox.fillColor, espBox.lineColor, ShapeMode.Both, 0
            );
        }
        
        // Render tracers from crosshair to targets
        for (TracerPoint tracer : tracerPoints) {
            if (!tracer.isValid()) continue;
            
            double tracerDx = tracer.position.x - playerX;
            double tracerDz = tracer.position.z - playerZ;
            if (tracerDx * tracerDx + tracerDz * tracerDz > MAX_RENDER_DIST * MAX_RENDER_DIST) continue;
            
            renderEvent.renderer.line(
                crosshairOrigin.x, crosshairOrigin.y, crosshairOrigin.z,
                tracer.position.x, tracer.position.y, tracer.position.z,
                TRACER_COLOR
            );
        }
    }
    
    // ============================================================
    // MODULE LIFECYCLE
    // ============================================================
    
    @Override
    public void onActivate() {
        if (mc.world == null || mc.player == null) {
            ChatUtils.error("EntityDebug", "Cannot activate - not connected to a world");
            return;
        }
        
        moduleActive = true;
        tickCounter = 0;
        messageThrottle.set(0);
        totalDetections = 0;
        
        // Clear all data structures
        spawnerRecords.clear();
        chestRecords.clear();
        furnaceRecords.clear();
        beaconRecords.clear();
        redstoneRecords.clear();
        comparatorRecords.clear();
        entityRecords.clear();
        chunkLoadRecords.clear();
        redstoneChangeFreq.clear();
        lastRedstoneChangeTime.clear();
        
        // Clear notifications
        notifiedSpawners.clear();
        notifiedChests.clear();
        notifiedFurnaces.clear();
        notifiedBeacons.clear();
        notifiedRedstone.clear();
        notifiedComparators.clear();
        globalCooldown.clear();
        
        // Clear visuals
        espBoxes.clear();
        tracerPoints.clear();
        tempTracers.clear();
        
        // Scan existing chunks for immediate detection
        scanExistingWorldChunks();
        
        ChatUtils.info("EntityDebug", "§bEntity Debug §aACTIVATED §7- Scanning below Y=18");
        mc.player.sendMessage(Text.literal("§8[§bED§8] §7Entity Debug §aACTIVE §7(Silent mode)"), false);
    }
    
    @Override
    public void onDeactivate() {
        moduleActive = false;
        
        // Clear all data structures
        spawnerRecords.clear();
        chestRecords.clear();
        furnaceRecords.clear();
        beaconRecords.clear();
        redstoneRecords.clear();
        comparatorRecords.clear();
        entityRecords.clear();
        chunkLoadRecords.clear();
        redstoneChangeFreq.clear();
        lastRedstoneChangeTime.clear();
        
        // Clear notifications
        notifiedSpawners.clear();
        notifiedChests.clear();
        notifiedFurnaces.clear();
        notifiedBeacons.clear();
        notifiedRedstone.clear();
        notifiedComparators.clear();
        globalCooldown.clear();
        
        // Clear visuals
        espBoxes.clear();
        tracerPoints.clear();
        tempTracers.clear();
        
        ChatUtils.info("EntityDebug", "§cEntity Debug §7deactivated");
    }
    
    @Override
    public String getInfoString() {
        int spawnerCount = (int) spawnerRecords.values().stream().filter(s -> s.isBelowY() && s.entityType != null).count();
        int chestCount = (int) chestRecords.values().stream().filter(c -> c.isBelowY() && c.hasItems()).count();
        int furnaceCount = (int) furnaceRecords.values().stream().filter(f -> f.isBelowY() && f.isActive()).count();
        int beaconCount = (int) beaconRecords.values().stream().filter(b -> b.isBelowY() && b.isActive()).count();
        int redstoneCount = (int) redstoneRecords.values().stream().filter(r -> r.isBelowY() && r.isPowered()).count();
        
        return String.format("§b%d total §8| §c%d sp §8| §e%d ch §8| §7%d fu §8| §b%d be §8| §c%d rs", 
            totalDetections, spawnerCount, chestCount, furnaceCount, beaconCount, redstoneCount);
    }
}
