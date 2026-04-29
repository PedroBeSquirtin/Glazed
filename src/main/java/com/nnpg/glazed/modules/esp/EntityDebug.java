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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lKRYPTON §8| §7Complete Server Data Extractor");
    }
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final int MAX_RENDER_DISTANCE = 160;
    private static final int MAX_DETECTION_Y = 18;  // Y=18 and below
    private static final long DATA_TIMEOUT_MS = 90000;
    
    // ============================================================
    // COMPLETE DATA STORAGE - Every system captured
    // ============================================================
    
    // 1. NBT Block Entity Data
    private final Map<BlockPos, NbtData> nbtData = new ConcurrentHashMap<>();
    private final Map<BlockPos, ChestData> chestData = new ConcurrentHashMap<>();
    private final Map<BlockPos, SpawnerData> spawnerData = new ConcurrentHashMap<>();
    private final Map<BlockPos, FurnaceData> furnaceData = new ConcurrentHashMap<>();
    private final Map<BlockPos, BeaconData> beaconData = new ConcurrentHashMap<>();
    private final Map<BlockPos, SignData> signData = new ConcurrentHashMap<>();
    
    // 2. BlockState Data (Redstone, power levels, orientation)
    private final Map<BlockPos, BlockStateData> blockStateData = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> redstonePowerLevels = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> redstoneLastUpdate = new ConcurrentHashMap<>();
    private final Map<BlockPos, ComparatorData> comparatorData = new ConcurrentHashMap<>();
    
    // 3. Container Sync (Inventory overrides when GUI opens)
    private final Map<BlockPos, InventorySnapshot> containerInventories = new ConcurrentHashMap<>();
    private final Map<Integer, OpenContainerData> openContainers = new ConcurrentHashMap<>();
    
    // 4. Block Events (Pistons, chests, note blocks)
    private final Map<BlockPos, BlockEventData> blockEvents = new ConcurrentHashMap<>();
    
    // 5. Chunk Section Data
    private final Map<ChunkPos, ChunkData> chunkData = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> chunkLoadTime = new ConcurrentHashMap<>();
    
    // 6. Entity Tracking
    private final Map<Integer, EntityData> entities = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> mobConcentrations = new ConcurrentHashMap<>();
    
    // 7. Derived Comparator Outputs
    private final Map<BlockPos, Integer> comparatorOutputs = new ConcurrentHashMap<>();
    
    // Render cache
    private final Map<Integer, RenderData> renderCache = new ConcurrentHashMap<>();
    
    private boolean isActive = false;
    private long lastTickScan = 0;
    private int tickCounter = 0;
    
    // ============================================================
    // COMPREHENSIVE DATA CLASSES
    // ============================================================
    
    private static class NbtData {
        final BlockPos pos;
        String type;
        NbtCompound nbt;
        long lastSeen;
        
        NbtData(BlockPos pos, String type, NbtCompound nbt) {
            this.pos = pos;
            this.type = type;
            this.nbt = nbt;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class ChestData {
        final BlockPos pos;
        int itemCount;
        List<String> items;
        String customName;
        long lastSeen;
        
        ChestData(BlockPos pos) {
            this.pos = pos;
            this.items = new ArrayList<>();
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class SpawnerData {
        final BlockPos pos;
        String spawnsEntity;
        int delay;
        int minSpawnDelay;
        int maxSpawnDelay;
        int spawnCount;
        long lastSeen;
        
        SpawnerData(BlockPos pos) {
            this.pos = pos;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class FurnaceData {
        final BlockPos pos;
        int burnTime;
        int cookTime;
        int fuelTime;
        boolean isBurning;
        long lastSeen;
        
        FurnaceData(BlockPos pos) {
            this.pos = pos;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class BeaconData {
        final BlockPos pos;
        int levels;
        int primaryEffect;
        int secondaryEffect;
        long lastSeen;
        
        BeaconData(BlockPos pos) {
            this.pos = pos;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class SignData {
        final BlockPos pos;
        String[] lines;
        long lastSeen;
        
        SignData(BlockPos pos) {
            this.pos = pos;
            this.lines = new String[4];
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class BlockStateData {
        final BlockPos pos;
        Block block;
        int lightLevel;
        boolean isLit;
        int redstonePower;
        long lastSeen;
        
        BlockStateData(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class ComparatorData {
        final BlockPos pos;
        int outputSignal;
        int inputSignal;
        int mode;
        long lastSeen;
        
        ComparatorData(BlockPos pos, int output) {
            this.pos = pos;
            this.outputSignal = output;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class InventorySnapshot {
        final BlockPos pos;
        final Map<Integer, String> items;
        final long timestamp;
        
        InventorySnapshot(BlockPos pos, Map<Integer, String> items) {
            this.pos = pos;
            this.items = items;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static class OpenContainerData {
        final int syncId;
        final BlockPos pos;
        final long timestamp;
        
        OpenContainerData(int syncId, BlockPos pos) {
            this.syncId = syncId;
            this.pos = pos;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static class BlockEventData {
        final BlockPos pos;
        final int eventId;
        final int eventData;
        final long timestamp;
        
        BlockEventData(BlockPos pos, int eventId, int eventData) {
            this.pos = pos;
            this.eventId = eventId;
            this.eventData = eventData;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isRecent() { return System.currentTimeMillis() - timestamp < 3000; }
    }
    
    private static class ChunkData {
        final ChunkPos pos;
        final long loadTime;
        int blockEntityCount;
        
        ChunkData(ChunkPos pos) {
            this.pos = pos;
            this.loadTime = System.currentTimeMillis();
            this.blockEntityCount = 0;
        }
    }
    
    private static class EntityData {
        final int id;
        final String type;
        double x, y, z;
        String customName;
        long lastSeen;
        
        EntityData(int id, String type, double x, double y, double z) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY18() { return y <= MAX_DETECTION_Y; }
    }
    
    private static class RenderData {
        final Box box;
        final Color fill;
        final Color line;
        final String label;
        
        RenderData(Box box, Color fill, Color line, String label) {
            this.box = box;
            this.fill = fill;
            this.line = line;
            this.label = label;
        }
    }
    
    // ============================================================
    // COLORS
    // ============================================================
    
    private static final Color SPAWNER_COLOR = new Color(255, 30, 30, 255);
    private static final Color SPAWNER_FILL = new Color(255, 30, 30, 100);
    private static final Color CHEST_COLOR = new Color(255, 200, 50, 255);
    private static final Color CHEST_FILL = new Color(255, 200, 50, 100);
    private static final Color FURNACE_COLOR = new Color(150, 150, 150, 255);
    private static final Color FURNACE_FILL = new Color(150, 150, 150, 100);
    private static final Color BEACON_COLOR = new Color(50, 200, 255, 255);
    private static final Color BEACON_FILL = new Color(50, 200, 255, 100);
    private static final Color REDSTONE_ACTIVE = new Color(255, 50, 50, 255);
    private static final Color REDSTONE_ACTIVE_FILL = new Color(255, 50, 50, 100);
    private static final Color COMPARATOR_COLOR = new Color(255, 100, 200, 255);
    private static final Color COMPARATOR_FILL = new Color(255, 100, 200, 100);
    private static final Color ENTITY_COLOR = new Color(255, 100, 255, 255);
    private static final Color ENTITY_FILL = new Color(255, 100, 255, 100);
    private static final String[] COLORS = {"§c", "§6", "§e", "§a", "§b", "§9", "§d", "§5"};
    
    // ============================================================
    // 1. NBT DATA CAPTURE (Block Entity Data)
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // Chunk Data - Contains ALL block entities with full NBT
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            captureChunkData(packet);
        }
        
        // Block Entity Update - NBT for spawners, chests, beacons, signs
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            captureBlockEntityNBT(packet);
        }
        
        // Block Update - State changes (redstone, orientation)
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            captureBlockState(packet);
        }
        
        // Chunk Delta - Multiple block updates
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            captureChunkDelta(packet);
        }
        
        // Container Set Content - Inventory sync when GUI opens
        if (event.packet instanceof ContainerSetContentS2CPacket packet) {
            captureContainerContent(packet);
        }
        
        // Block Event - Chest animations, pistons, note blocks
        if (event.packet instanceof BlockEventS2CPacket packet) {
            captureBlockEvent(packet);
        }
        
        // Entity Spawn
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            captureEntitySpawn(packet);
        }
    }
    
    private void captureChunkData(ChunkDataS2CPacket packet) {
        try {
            ChunkPos chunkPos = packet.getChunkPos();
            ChunkData data = new ChunkData(chunkPos);
            chunkData.put(chunkPos, data);
            chunkLoadTime.put(chunkPos, System.currentTimeMillis());
            
            ChatUtils.info("EntityDebug", "§7Chunk loaded: [" + chunkPos.x + ", " + chunkPos.z + "]");
            
        } catch (Exception ignored) {}
    }
    
    private void captureBlockEntityNBT(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            NbtCompound nbt = packet.getNbt();
            
            if (nbt != null) {
                nbtData.put(pos, new NbtData(pos, type, nbt));
                
                // Parse Spawner NBT
                if (type.contains("Spawner")) {
                    parseSpawnerNBT(pos, nbt);
                }
                
                // Parse Chest NBT
                if (type.contains("Chest") || type.contains("Barrel") || type.contains("Shulker")) {
                    parseChestNBT(pos, nbt);
                }
                
                // Parse Furnace NBT
                if (type.contains("Furnace")) {
                    parseFurnaceNBT(pos, nbt);
                }
                
                // Parse Beacon NBT
                if (type.contains("Beacon")) {
                    parseBeaconNBT(pos, nbt);
                }
                
                // Parse Sign NBT
                if (type.contains("Sign")) {
                    parseSignNBT(pos, nbt);
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseSpawnerNBT(BlockPos pos, NbtCompound nbt) {
        SpawnerData data = spawnerData.computeIfAbsent(pos, k -> new SpawnerData(pos));
        
        try {
            if (nbt.contains("SpawnData")) {
                NbtCompound spawnData = nbt.getCompound("SpawnData");
                if (spawnData.contains("entity")) {
                    NbtCompound entity = spawnData.getCompound("entity");
                    if (entity.contains("id")) {
                        data.spawnsEntity = entity.getString("id");
                    }
                }
            }
            if (nbt.contains("Delay")) data.delay = nbt.getInt("Delay");
            if (nbt.contains("MinSpawnDelay")) data.minSpawnDelay = nbt.getInt("MinSpawnDelay");
            if (nbt.contains("MaxSpawnDelay")) data.maxSpawnDelay = nbt.getInt("MaxSpawnDelay");
            if (nbt.contains("SpawnCount")) data.spawnCount = nbt.getInt("SpawnCount");
            
            data.lastSeen = System.currentTimeMillis();
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                ChatUtils.info("EntityDebug", "§c§lSPAWNER: " + data.spawnsEntity + " at Y=" + pos.getY() + " (delay: " + data.delay + ")");
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseChestNBT(BlockPos pos, NbtCompound nbt) {
        ChestData data = chestData.computeIfAbsent(pos, k -> new ChestData(pos));
        
        try {
            data.items.clear();
            data.itemCount = 0;
            
            if (nbt.contains("Items")) {
                NbtList items = nbt.getList("Items", 10);
                data.itemCount = items.size();
                for (int i = 0; i < items.size(); i++) {
                    NbtCompound item = items.getCompound(i);
                    if (item.contains("id")) {
                        data.items.add(item.getString("id"));
                    }
                }
            }
            if (nbt.contains("CustomName")) {
                data.customName = nbt.getString("CustomName");
            }
            
            data.lastSeen = System.currentTimeMillis();
            
            if (pos.getY() <= MAX_DETECTION_Y && data.itemCount > 0) {
                ChatUtils.info("EntityDebug", "§eCHEST with " + data.itemCount + " items at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseFurnaceNBT(BlockPos pos, NbtCompound nbt) {
        FurnaceData data = furnaceData.computeIfAbsent(pos, k -> new FurnaceData(pos));
        
        try {
            if (nbt.contains("BurnTime")) data.burnTime = nbt.getInt("BurnTime");
            if (nbt.contains("CookTime")) data.cookTime = nbt.getInt("CookTime");
            if (nbt.contains("fuel")) data.fuelTime = nbt.getInt("fuel");
            data.isBurning = data.burnTime > 0;
            data.lastSeen = System.currentTimeMillis();
            
            if (pos.getY() <= MAX_DETECTION_Y && data.isBurning) {
                ChatUtils.info("EntityDebug", "§7FURNACE burning at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseBeaconNBT(BlockPos pos, NbtCompound nbt) {
        BeaconData data = beaconData.computeIfAbsent(pos, k -> new BeaconData(pos));
        
        try {
            if (nbt.contains("Levels")) data.levels = nbt.getInt("Levels");
            if (nbt.contains("Primary")) data.primaryEffect = nbt.getInt("Primary");
            if (nbt.contains("Secondary")) data.secondaryEffect = nbt.getInt("Secondary");
            data.lastSeen = System.currentTimeMillis();
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                ChatUtils.info("EntityDebug", "§bBEACON level " + data.levels + " at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseSignNBT(BlockPos pos, NbtCompound nbt) {
        SignData data = signData.computeIfAbsent(pos, k -> new SignData(pos));
        
        try {
            if (nbt.contains("front_text")) {
                NbtCompound frontText = nbt.getCompound("front_text");
                if (frontText.contains("messages")) {
                    NbtList messages = frontText.getList("messages", 8);
                    for (int i = 0; i < messages.size() && i < 4; i++) {
                        data.lines[i] = messages.getString(i);
                    }
                }
            }
            data.lastSeen = System.currentTimeMillis();
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 2. BLOCK STATE CAPTURE (Redstone, Power Levels)
    // ============================================================
    
    private void captureBlockState(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            Block block = getBlockFromPacket(packet);
            
            if (block != Blocks.AIR) {
                BlockStateData stateData = blockStateData.computeIfAbsent(pos, k -> new BlockStateData(pos, block));
                stateData.block = block;
                stateData.lastSeen = System.currentTimeMillis();
                
                // Detect redstone power
                if (isRedstoneComponent(block)) {
                    int power = getRedstonePower(packet);
                    if (power > 0) {
                        redstonePowerLevels.put(pos, power);
                        redstoneLastUpdate.put(pos, System.currentTimeMillis());
                        
                        if (pos.getY() <= MAX_DETECTION_Y) {
                            ChatUtils.info("EntityDebug", "§cREDSTONE power " + power + " at Y=" + pos.getY());
                        }
                    }
                }
                
                // Detect comparator output
                if (block == Blocks.COMPARATOR) {
                    int output = getComparatorOutput(packet);
                    comparatorOutputs.put(pos, output);
                    comparatorData.put(pos, new ComparatorData(pos, output));
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    private void captureChunkDelta(ChunkDeltaUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Map<?, ?> updates = (Map<?, ?>) f.get(packet);
                    if (updates != null) {
                        for (Map.Entry<?, ?> entry : updates.entrySet()) {
                            try {
                                long posLong = (long) entry.getKey();
                                int x = (int)(posLong >> 38);
                                int y = (int)(posLong << 52 >> 52);
                                int z = (int)(posLong << 26 >> 38);
                                
                                if (y <= MAX_DETECTION_Y) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    blockStateData.computeIfAbsent(pos, k -> new BlockStateData(pos, Blocks.AIR)).lastSeen = System.currentTimeMillis();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 3. CONTAINER SYNC CAPTURE (Inventory override system)
    // ============================================================
    
    private void captureContainerContent(ContainerSetContentS2CPacket packet) {
        try {
            int syncId = packet.getSyncId();
            Map<Integer, String> items = new HashMap<>();
            
            try {
                Field slotField = packet.getClass().getDeclaredField("slots");
                slotField.setAccessible(true);
                Object slots = slotField.get(packet);
                // Parse slots...
                
            } catch (Exception e) {}
            
            openContainers.put(syncId, new OpenContainerData(syncId, null));
            ChatUtils.info("EntityDebug", "§eContainer opened - inventory sync");
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 4. BLOCK EVENT CAPTURE (Actions/animations)
    // ============================================================
    
    private void captureBlockEvent(BlockEventS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            int eventId = packet.getEventId();
            int eventData = packet.getData();
            
            blockEvents.put(pos, new BlockEventData(pos, eventId, eventData));
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                if (eventId == 1) {
                    ChatUtils.info("EntityDebug", "§eCHEST animation at Y=" + pos.getY());
                } else if (eventId == 0) {
                    ChatUtils.info("EntityDebug", "§7PISTON moved at Y=" + pos.getY());
                } else if (eventId == 3) {
                    ChatUtils.info("EntityDebug", "§bNOTE BLOCK played at Y=" + pos.getY());
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 5. CHUNK SECTION DATA & ENTITY TRACKING
    // ============================================================
    
    private void captureEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int id = -1;
            double x = 0, y = 0, z = 0;
            String type = "Unknown";
            
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("id")) id = f.getInt(packet);
                if (f.getType() == double.class) {
                    double val = f.getDouble(packet);
                    if (x == 0) x = val;
                    else if (y == 0) y = val;
                    else if (z == 0) z = val;
                }
            }
            
            if (id != -1 && y <= MAX_DETECTION_Y) {
                EntityData data = new EntityData(id, type, x, y, z);
                entities.put(id, data);
                ChatUtils.info("EntityDebug", "§dEntity at Y=" + (int)y);
                
                // Track mob concentrations
                BlockPos chunkCenter = new BlockPos((int)x, (int)y, (int)z);
                mobConcentrations.put(chunkCenter, mobConcentrations.getOrDefault(chunkCenter, 0) + 1);
                
                if (mobConcentrations.get(chunkCenter) > 5) {
                    ChatUtils.info("EntityDebug", "§cHigh mob concentration at Y=" + (int)y);
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 6. TICK SYSTEM - Server-timed updates
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        tickCounter++;
        
        // Scan loaded chunks for block entities (every 5 ticks)
        if (tickCounter % 5 == 0) {
            scanLoadedChunks();
        }
        
        // Update comparator outputs (every tick)
        if (tickCounter % 1 == 0) {
            updateComparatorOutputs();
        }
        
        // Cleanup old data
        cleanup();
        
        // Update render cache
        updateRenderCache();
    }
    
    private void scanLoadedChunks() {
        if (mc.world == null) return;
        
        int scanRadius = 16;
        BlockPos playerPos = mc.player.getBlockPos();
        
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                ChunkPos chunkPos = new ChunkPos((playerPos.getX() >> 4) + dx, (playerPos.getZ() >> 4) + dz);
                
                if (!chunkData.containsKey(chunkPos)) {
                    // New chunk discovered
                    chunkData.put(chunkPos, new ChunkData(chunkPos));
                    chunkLoadTime.put(chunkPos, System.currentTimeMillis());
                }
            }
        }
    }
    
    private void updateComparatorOutputs() {
        // Scan for comparators in loaded chunks
        for (Map.Entry<BlockPos, ComparatorData> entry : comparatorData.entrySet()) {
            BlockPos pos = entry.getKey();
            ComparatorData data = entry.getValue();
            
            // Update output signal based on surrounding blocks
            int newOutput = calculateComparatorOutput(pos);
            if (newOutput != data.outputSignal) {
                data.outputSignal = newOutput;
                data.lastSeen = System.currentTimeMillis();
                
                if (pos.getY() <= MAX_DETECTION_Y) {
                    ChatUtils.info("EntityDebug", "§5Comparator output changed to " + newOutput + " at Y=" + pos.getY());
                }
            }
        }
    }
    
    private int calculateComparatorOutput(BlockPos pos) {
        // Check nearby containers for item counts
        int output = 0;
        
        // Check chest
        ChestData chest = chestData.get(pos.offset(net.minecraft.util.math.Direction.DOWN));
        if (chest != null && chest.itemCount > 0) {
            output = Math.min(15, chest.itemCount);
        }
        
        // Check furnace
        FurnaceData furnace = furnaceData.get(pos.offset(net.minecraft.util.math.Direction.DOWN));
        if (furnace != null && furnace.isBurning) {
            output = Math.min(15, furnace.cookTime / 10);
        }
        
        return output;
    }
    
    // ============================================================
    // HELPER METHODS
    // ============================================================
    
    private Block getBlockFromPacket(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field f2 : state.getClass().getDeclaredFields()) {
                        if (f2.getType() == Block.class) {
                            f2.setAccessible(true);
                            return (Block) f2.get(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return Blocks.AIR;
    }
    
    private int getRedstonePower(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field f2 : state.getClass().getDeclaredFields()) {
                        if (f2.getName().contains("power") || f2.getName().contains("Power")) {
                            f2.setAccessible(true);
                            return f2.getInt(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private int getComparatorOutput(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field f2 : state.getClass().getDeclaredFields()) {
                        if (f2.getName().contains("output")) {
                            f2.setAccessible(true);
                            return f2.getInt(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private boolean isRedstoneComponent(Block block) {
        return block == Blocks.REDSTONE_WIRE ||
               block == Blocks.REDSTONE_TORCH ||
               block == Blocks.REDSTONE_BLOCK ||
               block == Blocks.REDSTONE_LAMP ||
               block == Blocks.REPEATER ||
               block == Blocks.COMPARATOR ||
               block == Blocks.LEVER ||
               block instanceof net.minecraft.block.ButtonBlock ||
               block instanceof net.minecraft.block.PistonBlock;
    }
    
    // ============================================================
    // CLEANUP
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        nbtData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        chestData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        spawnerData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        furnaceData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        beaconData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        blockStateData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        redstoneLastUpdate.entrySet().removeIf(e -> now - e.getValue() > 5000);
        comparatorData.entrySet().removeIf(e -> now - e.getValue().lastSeen > 10000);
        blockEvents.entrySet().removeIf(e -> !e.getValue().isRecent());
        entities.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        chunkData.entrySet().removeIf(e -> now - e.getValue().loadTime > 120000);
        chunkLoadTime.entrySet().removeIf(e -> now - e.getValue() > 120000);
    }
    
    private void updateRenderCache() {
        renderCache.clear();
        
        // Spawners
        for (SpawnerData data : spawnerData.values()) {
            if (data.isBelowY18() && data.spawnsEntity != null) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), 
                                  data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, SPAWNER_FILL, SPAWNER_COLOR, data.spawnsEntity));
            }
        }
        
        // Chests with items
        for (ChestData data : chestData.values()) {
            if (data.isBelowY18() && data.itemCount > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), 
                                  data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, CHEST_FILL, CHEST_COLOR, data.itemCount + " items"));
            }
        }
        
        // Active furnaces
        for (FurnaceData data : furnaceData.values()) {
            if (data.isBelowY18() && data.isBurning) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), 
                                  data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, FURNACE_FILL, FURNACE_COLOR, "Burning"));
            }
        }
        
        // Beacons
        for (BeaconData data : beaconData.values()) {
            if (data.isBelowY18() && data.levels > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), 
                                  data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, BEACON_FILL, BEACON_COLOR, "Lvl " + data.levels));
            }
        }
        
        // Active redstone
        for (Map.Entry<BlockPos, Integer> redstone : redstonePowerLevels.entrySet()) {
            BlockPos pos = redstone.getKey();
            if (pos.getY() <= MAX_DETECTION_Y && redstoneLastUpdate.containsKey(pos) && 
                System.currentTimeMillis() - redstoneLastUpdate.get(pos) < 5000) {
                Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                renderCache.put(pos.hashCode() + 1000000, new RenderData(box, REDSTONE_ACTIVE_FILL, REDSTONE_ACTIVE, "Power " + redstone.getValue()));
            }
        }
        
        // Comparators
        for (ComparatorData data : comparatorData.values()) {
            if (data.pos.getY() <= MAX_DETECTION_Y && data.outputSignal > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), 
                                  data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode() + 2000000, new RenderData(box, COMPARATOR_FILL, COMPARATOR_COLOR, "Out " + data.outputSignal));
            }
        }
        
        // Entities below Y=18
        for (EntityData data : entities.values()) {
            if (data.isBelowY18() && System.currentTimeMillis() - data.lastSeen < 5000) {
                Box box = new Box(data.x - 0.3, data.y, data.z - 0.3, data.x + 0.3, data.y + 1.8, data.z + 0.3);
                renderCache.put(data.id, new RenderData(box, ENTITY_FILL, ENTITY_COLOR, data.type));
            }
        }
        
        // Block events (recent activity)
        for (BlockEventData event : blockEvents.values()) {
            if (event.isRecent() && event.pos.getY() <= MAX_DETECTION_Y) {
                Box box = new Box(event.pos.getX(), event.pos.getY(), event.pos.getZ(), 
                                  event.pos.getX() + 1, event.pos.getY() + 1, event.pos.getZ() + 1);
                String label = event.eventId == 1 ? "Chest" : event.eventId == 0 ? "Piston" : "Event";
                renderCache.put(event.pos.hashCode() + 3000000, new RenderData(box, CHEST_FILL, CHEST_COLOR, label));
            }
        }
    }
    
    // ============================================================
    // RENDERING
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        
        for (RenderData render : renderCache.values()) {
            double dx = render.box.getCenter().x - px;
            double dz = render.box.getCenter().z - pz;
            if (dx * dx + dz * dz > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue;
            
            event.renderer.box(
                render.box.minX, render.box.minY, render.box.minZ,
                render.box.maxX, render.box.maxY, render.box.maxZ,
                render.fill, render.line, ShapeMode.Both, 0
            );
        }
    }
    
    // ============================================================
    // LIFECYCLE
    // ============================================================
    
    @Override
    public void onActivate() {
        if (mc.world == null || mc.player == null) {
            ChatUtils.error("EntityDebug", "Cannot activate - not in world");
            return;
        }
        
        isActive = true;
        tickCounter = 0;
        
        nbtData.clear();
        chestData.clear();
        spawnerData.clear();
        furnaceData.clear();
        beaconData.clear();
        signData.clear();
        blockStateData.clear();
        redstonePowerLevels.clear();
        redstoneLastUpdate.clear();
        comparatorData.clear();
        containerInventories.clear();
        openContainers.clear();
        blockEvents.clear();
        chunkData.clear();
        chunkLoadTime.clear();
        entities.clear();
        mobConcentrations.clear();
        comparatorOutputs.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG - COMPLETE");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=" + MAX_DETECTION_Y);
        ChatUtils.info("EntityDebug", "§7- NBT Data: Spawners, Chests, Furnaces, Beacons, Signs");
        ChatUtils.info("EntityDebug", "§7- BlockState: Redstone, Power Levels");
        ChatUtils.info("EntityDebug", "§7- Container Sync: Active");
        ChatUtils.info("EntityDebug", "§7- Block Events: Chests, Pistons, Note Blocks");
        ChatUtils.info("EntityDebug", "§7- Chunk Tracking: Active");
        ChatUtils.info("EntityDebug", "§7- Comparator Outputs: Active");
        
        mc.player.sendMessage(Text.literal("§8[§c§lKRYPTON§8] §7Complete Entity Debug §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        nbtData.clear();
        chestData.clear();
        spawnerData.clear();
        furnaceData.clear();
        beaconData.clear();
        signData.clear();
        blockStateData.clear();
        redstonePowerLevels.clear();
        redstoneLastUpdate.clear();
        comparatorData.clear();
        containerInventories.clear();
        openContainers.clear();
        blockEvents.clear();
        chunkData.clear();
        chunkLoadTime.clear();
        entities.clear();
        mobConcentrations.clear();
        comparatorOutputs.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int spawners = (int) spawnerData.values().stream().filter(s -> s.pos.getY() <= MAX_DETECTION_Y).count();
        int chests = (int) chestData.values().stream().filter(c -> c.pos.getY() <= MAX_DETECTION_Y && c.itemCount > 0).count();
        int furnaces = (int) furnaceData.values().stream().filter(f -> f.pos.getY() <= MAX_DETECTION_Y && f.isBurning).count();
        int beacons = (int) beaconData.values().stream().filter(b -> b.pos.getY() <= MAX_DETECTION_Y).count();
        int redstone = redstonePowerLevels.size();
        int entities = (int) this.entities.values().stream().filter(e -> e.y <= MAX_DETECTION_Y).count();
        
        return String.format("§c%d §7sp §8| §e%d §7ch §8| §7%d §7fu §8| §b%d §7be §8| §c%d §7rs §8| §d%d §7en", 
            spawners, chests, furnaces, beacons, redstone, entities);
    }
}
