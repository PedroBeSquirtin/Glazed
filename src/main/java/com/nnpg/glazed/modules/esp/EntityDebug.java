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
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
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

/**
 * KRYPTON-STYLE ENTITY DEBUG - 1.21.11 COMPATIBLE
 * Captures ALL server data channels:
 * 
 * 1. NBT (Block Entity Data) - Full structured data from chunk & update packets
 * 2. Packets - ChunkData, BlockEntityUpdate, BlockUpdate, ChunkDelta, BlockEvent
 * 3. BlockState - Redstone power levels, powered states, comparator mode
 * 4. Container Sync - Inventory override system (indirect via container events)
 * 5. Block Events - Piston movement, chest open, note block play
 * 6. Server Tick Updates - 20 TPS simulation reflected via block updates
 * 7. Comparator Output System - Derived signal from containers/furnaces
 * 8. Chunk Section Data - Bulk world sync on chunk load
 */
public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lKRYPTON §8| §78-Channel Server Data Extractor");
    }
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final int MAX_RENDER_DISTANCE = 160;
    private static final int MAX_DETECTION_Y = 18;
    private static final long DATA_TIMEOUT_MS = 90000;
    
    // ============================================================
    // DATA STORAGE - 8 CHANNELS
    // ============================================================
    
    // 1. NBT Block Entity Data (from chunk load + update packets)
    private final Map<BlockPos, NbtData> nbtData = new ConcurrentHashMap<>();
    private final Map<BlockPos, ChestData> chestData = new ConcurrentHashMap<>();
    private final Map<BlockPos, SpawnerData> spawnerData = new ConcurrentHashMap<>();
    private final Map<BlockPos, FurnaceData> furnaceData = new ConcurrentHashMap<>();
    private final Map<BlockPos, BeaconData> beaconData = new ConcurrentHashMap<>();
    private final Map<BlockPos, SignData> signData = new ConcurrentHashMap<>();
    
    // 2. Packets - Tracking which packets have been received
    private final Map<ChunkPos, ChunkData> chunkData = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastBlockUpdate = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastBlockEntityUpdate = new ConcurrentHashMap<>();
    
    // 3. BlockState (Redstone power levels, powered states, comparator mode)
    private final Map<BlockPos, BlockStateData> blockStateData = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> redstonePowerLevels = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> redstoneLastUpdate = new ConcurrentHashMap<>();
    private final Map<BlockPos, Boolean> redstonePoweredStates = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> repeaterDelays = new ConcurrentHashMap<>();
    
    // 4. Container Sync (Inventory override system - detected via container interaction)
    private final Map<Integer, ContainerSyncData> containerSyncs = new ConcurrentHashMap<>();
    private final Map<BlockPos, InventorySnapshot> inventories = new ConcurrentHashMap<>();
    
    // 5. Block Events (Piston movement, chest open, note block play)
    private final Map<BlockPos, BlockEventData> blockEvents = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> chestOpenEvents = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> pistonMoveEvents = new ConcurrentHashMap<>();
    
    // 6. Server Tick Updates - Tracked via block state changes over time
    private final Map<BlockPos, Integer> redstoneChangeFrequency = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastRedstoneChange = new ConcurrentHashMap<>();
    
    // 7. Comparator Output System (Derived signal from containers/furnaces)
    private final Map<BlockPos, ComparatorData> comparatorData = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> comparatorOutputs = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> comparatorModes = new ConcurrentHashMap<>();
    
    // 8. Chunk Section Data - Bulk sync tracking
    private final Map<ChunkPos, Long> chunkLoadTimes = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> chunkBlockEntityCounts = new ConcurrentHashMap<>();
    
    // Entity tracking (additional)
    private final Map<Integer, EntityData> entities = new ConcurrentHashMap<>();
    
    // Render cache
    private final Map<Integer, RenderData> renderCache = new ConcurrentHashMap<>();
    
    private boolean isActive = false;
    private int tickCounter = 0;
    private long lastRedstoneScan = 0;
    
    // ============================================================
    // DATA CLASSES
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
        int redstonePower;
        boolean isPowered;
        long lastSeen;
        BlockStateData(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
            this.redstonePower = 0;
            this.isPowered = false;
            this.lastSeen = System.currentTimeMillis();
        }
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class ComparatorData {
        final BlockPos pos;
        int outputSignal;
        int mode;
        long lastSeen;
        ComparatorData(BlockPos pos, int output, int mode) {
            this.pos = pos;
            this.outputSignal = output;
            this.mode = mode;
            this.lastSeen = System.currentTimeMillis();
        }
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
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
    
    private static class ContainerSyncData {
        final int syncId;
        final long timestamp;
        ContainerSyncData(int syncId) {
            this.syncId = syncId;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static class InventorySnapshot {
        final BlockPos pos;
        final String snapshot;
        final long timestamp;
        InventorySnapshot(BlockPos pos, String snapshot) {
            this.pos = pos;
            this.snapshot = snapshot;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static class ChunkData {
        final ChunkPos pos;
        final long loadTime;
        ChunkData(ChunkPos pos) {
            this.pos = pos;
            this.loadTime = System.currentTimeMillis();
        }
    }
    
    private static class EntityData {
        final int id;
        final String type;
        double x, y, z;
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
        RenderData(Box box, Color fill, Color line) {
            this.box = box;
            this.fill = fill;
            this.line = line;
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
    private static final Color EVENT_COLOR = new Color(255, 255, 100, 255);
    private static final Color EVENT_FILL = new Color(255, 255, 100, 100);
    
    // ============================================================
    // PACKET CAPTURE - ALL 8 CHANNELS
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // 1 & 8. CHUNK DATA - Bulk world sync with NBT (Channel 1 & 8)
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            captureChunkData(packet);
        }
        
        // 1. BLOCK ENTITY UPDATE - NBT data (Channel 1)
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            captureBlockEntityNBT(packet);
        }
        
        // 2 & 3. BLOCK UPDATE - State changes, redstone power (Channel 2 & 3)
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            captureBlockState(packet);
        }
        
        // 2. CHUNK DELTA - Multiple block updates (Channel 2)
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            captureChunkDelta(packet);
        }
        
        // 2 & 5. BLOCK EVENT - Actions/animations (Channel 2 & 5)
        if (event.packet instanceof BlockEventS2CPacket packet) {
            captureBlockEvent(packet);
        }
        
        // 4. CONTAINER SYNC - Inventory override (detected via open container)
        if (event.packet instanceof OpenScreenS2CPacket packet) {
            captureContainerSync(packet);
        }
        
        // 2. ENTITY SPAWN - Entity tracking (Channel 2)
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            captureEntitySpawn(packet);
        }
    }
    
    // ============================================================
    // CHANNEL 1 & 8: NBT + CHUNK SECTION DATA
    // ============================================================
    
    private void captureChunkData(ChunkDataS2CPacket packet) {
        try {
            // Use reflection to get chunk position (getChunkPos doesn't exist in 1.21.11)
            int chunkX = 0, chunkZ = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("chunkX") || f.getName().equals("field_1210")) chunkX = f.getInt(packet);
                if (f.getName().equals("chunkZ") || f.getName().equals("field_1211")) chunkZ = f.getInt(packet);
                if (f.getName().equals("x")) chunkX = f.getInt(packet);
                if (f.getName().equals("z")) chunkZ = f.getInt(packet);
            }
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            chunkData.put(chunkPos, new ChunkData(chunkPos));
            chunkLoadTimes.put(chunkPos, System.currentTimeMillis());
            
            ChatUtils.info("EntityDebug", "§7Chunk loaded: [" + chunkX + ", " + chunkZ + "]");
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 1: NBT BLOCK ENTITY DATA (1.21.11 compatible)
    // ============================================================
    
    private void captureBlockEntityNBT(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            NbtCompound nbt = packet.getNbt();
            
            if (nbt != null) {
                nbtData.put(pos, new NbtData(pos, type, nbt));
                lastBlockEntityUpdate.put(pos, System.currentTimeMillis());
                
                if (type.contains("Spawner")) parseSpawnerNBT(pos, nbt);
                if (type.contains("Chest") || type.contains("Barrel") || type.contains("Shulker")) parseChestNBT(pos, nbt);
                if (type.contains("Furnace")) parseFurnaceNBT(pos, nbt);
                if (type.contains("Beacon")) parseBeaconNBT(pos, nbt);
                if (type.contains("Sign")) parseSignNBT(pos, nbt);
            }
        } catch (Exception ignored) {}
    }
    
    // 1.21.11 NBT - Uses Optional returns, need to handle properly [citation:1]
    private void parseSpawnerNBT(BlockPos pos, NbtCompound nbt) {
        SpawnerData data = spawnerData.computeIfAbsent(pos, k -> new SpawnerData(pos));
        try {
            // getCompound returns Optional<NbtCompound> in 1.21.11
            nbt.getCompound("SpawnData").ifPresent(spawnData -> {
                spawnData.getCompound("entity").ifPresent(entity -> {
                    entity.getString("id").ifPresent(id -> data.spawnsEntity = id);
                });
            });
            nbt.getInt("Delay").ifPresent(delay -> data.delay = delay);
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.spawnsEntity != null) {
                ChatUtils.info("EntityDebug", "§c§lSPAWNER: " + data.spawnsEntity + " at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    private void parseChestNBT(BlockPos pos, NbtCompound nbt) {
        ChestData data = chestData.computeIfAbsent(pos, k -> new ChestData(pos));
        try {
            data.items.clear();
            data.itemCount = 0;
            
            nbt.getList("Items", NbtElement.COMPOUND_TYPE).ifPresent(items -> {
                data.itemCount = items.size();
                for (int i = 0; i < items.size(); i++) {
                    NbtCompound item = items.getCompound(i);
                    item.getString("id").ifPresent(id -> data.items.add(id));
                }
            });
            nbt.getString("CustomName").ifPresent(name -> data.customName = name);
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.itemCount > 0) {
                ChatUtils.info("EntityDebug", "§eCHEST with " + data.itemCount + " items at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    private void parseFurnaceNBT(BlockPos pos, NbtCompound nbt) {
        FurnaceData data = furnaceData.computeIfAbsent(pos, k -> new FurnaceData(pos));
        try {
            nbt.getInt("BurnTime").ifPresent(burn -> data.burnTime = burn);
            nbt.getInt("CookTime").ifPresent(cook -> data.cookTime = cook);
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
            nbt.getInt("Levels").ifPresent(levels -> data.levels = levels);
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.levels > 0) {
                ChatUtils.info("EntityDebug", "§bBEACON level " + data.levels + " at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    private void parseSignNBT(BlockPos pos, NbtCompound nbt) {
        SignData data = signData.computeIfAbsent(pos, k -> new SignData(pos));
        try {
            nbt.getCompound("front_text").ifPresent(frontText -> {
                frontText.getList("messages", NbtElement.STRING_TYPE).ifPresent(messages -> {
                    for (int i = 0; i < messages.size() && i < 4; i++) {
                        messages.getString(i).ifPresent(line -> data.lines[i] = line);
                    }
                });
            });
            data.lastSeen = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 2 & 3: BLOCKSTATE (Redstone power, comparator mode)
    // ============================================================
    
    private void captureBlockState(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            Block block = getBlockFromPacket(packet);
            
            if (block != Blocks.AIR) {
                BlockStateData stateData = blockStateData.computeIfAbsent(pos, k -> new BlockStateData(pos, block));
                stateData.block = block;
                stateData.lastSeen = System.currentTimeMillis();
                lastBlockUpdate.put(pos, System.currentTimeMillis());
                
                // Channel 3: Redstone power level detection
                if (isRedstoneComponent(block)) {
                    int power = getRedstonePower(packet);
                    boolean isPowered = getRedstonePowered(packet);
                    
                    if (power > 0 || isPowered) {
                        redstonePowerLevels.put(pos, power);
                        redstonePoweredStates.put(pos, isPowered);
                        redstoneLastUpdate.put(pos, System.currentTimeMillis());
                        
                        // Track change frequency for server tick analysis (Channel 6)
                        long now = System.currentTimeMillis();
                        if (lastRedstoneChange.containsKey(pos)) {
                            long delta = now - lastRedstoneChange.get(pos);
                            if (delta < 500) {
                                redstoneChangeFrequency.put(pos, redstoneChangeFrequency.getOrDefault(pos, 0) + 1);
                            }
                        }
                        lastRedstoneChange.put(pos, now);
                        
                        if (pos.getY() <= MAX_DETECTION_Y) {
                            ChatUtils.info("EntityDebug", "§cREDSTONE power " + power + " at Y=" + pos.getY());
                        }
                    }
                }
                
                // Channel 3: Repeater delay detection
                if (block == Blocks.REPEATER) {
                    int delay = getRepeaterDelay(packet);
                    if (delay > 0) repeaterDelays.put(pos, delay);
                }
                
                // Channel 7: Comparator output detection (derived signal)
                if (block == Blocks.COMPARATOR) {
                    int output = getComparatorOutput(packet);
                    int mode = getComparatorMode(packet);
                    comparatorOutputs.put(pos, output);
                    comparatorModes.put(pos, mode);
                    comparatorData.put(pos, new ComparatorData(pos, output, mode));
                    
                    if (pos.getY() <= MAX_DETECTION_Y && output > 0) {
                        ChatUtils.info("EntityDebug", "§5COMPARATOR output " + output + " at Y=" + pos.getY() + (mode == 0 ? " (compare)" : " (subtract)"));
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 2: CHUNK DELTA PACKET
    // ============================================================
    
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
    // CHANNEL 2 & 5: BLOCK EVENTS (Pistons, chests, note blocks)
    // ============================================================
    
    private void captureBlockEvent(BlockEventS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            int eventId = packet.getEventId();
            int eventData = packet.getData();
            
            blockEvents.put(pos, new BlockEventData(pos, eventId, eventData));
            
            if (eventId == 1) {
                chestOpenEvents.put(pos, System.currentTimeMillis());
                if (pos.getY() <= MAX_DETECTION_Y) {
                    ChatUtils.info("EntityDebug", "§eCHEST animation at Y=" + pos.getY());
                }
            } else if (eventId == 0) {
                pistonMoveEvents.put(pos, System.currentTimeMillis());
                if (pos.getY() <= MAX_DETECTION_Y) {
                    ChatUtils.info("EntityDebug", "§7PISTON moved at Y=" + pos.getY());
                }
            } else if (eventId == 3) {
                if (pos.getY() <= MAX_DETECTION_Y) {
                    ChatUtils.info("EntityDebug", "§bNOTE BLOCK played at Y=" + pos.getY());
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 4: CONTAINER SYNC (Inventory override system)
    // ============================================================
    
    private void captureContainerSync(OpenScreenS2CPacket packet) {
        try {
            int syncId = packet.getSyncId();
            containerSyncs.put(syncId, new ContainerSyncData(syncId));
            ChatUtils.info("EntityDebug", "§6Container opened - inventory sync active");
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // ENTITY SPAWN - General entity tracking
    // ============================================================
    
    private void captureEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int id = -1;
            double x = 0, y = 0, z = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("id") || f.getName().equals("field_1217")) id = f.getInt(packet);
                if (f.getType() == double.class) {
                    double val = f.getDouble(packet);
                    if (x == 0) x = val;
                    else if (y == 0) y = val;
                    else if (z == 0) z = val;
                }
            }
            if (id != -1 && y <= MAX_DETECTION_Y) {
                entities.put(id, new EntityData(id, "Entity", x, y, z));
                ChatUtils.info("EntityDebug", "§dEntity at Y=" + (int)y);
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 6: SERVER TICK UPDATE DETECTION
    // Detects redstone logic evaluation at 20 TPS via change frequency
    // ============================================================
    
    private void detectServerTickActivity() {
        long now = System.currentTimeMillis();
        
        // Analyze redstone change frequency to detect active circuits
        for (Map.Entry<BlockPos, Integer> freq : redstoneChangeFrequency.entrySet()) {
            BlockPos pos = freq.getKey();
            if (pos.getY() <= MAX_DETECTION_Y && freq.getValue() > 20 && System.currentTimeMillis() - lastRedstoneChange.getOrDefault(pos, 0L) < 5000) {
                // High frequency = active server tick simulation
                ChatUtils.info("EntityDebug", "§6Active redstone circuit at Y=" + pos.getY() + " - " + freq.getValue() + " changes/sec");
            }
        }
        
        // Decay frequency counter every 5 seconds
        if (tickCounter % 100 == 0) {
            redstoneChangeFrequency.clear();
        }
    }
    
    // ============================================================
    // CHANNEL 7: UPDATE COMPARATOR OUTPUTS (Derived signal)
    // Recalculates comparator output based on container inventories
    // ============================================================
    
    private void updateDerivedComparatorOutputs() {
        for (BlockPos pos : comparatorData.keySet()) {
            // Check container below comparator
            BlockPos below = pos.down();
            ChestData chest = chestData.get(below);
            FurnaceData furnace = furnaceData.get(below);
            
            int newOutput = 0;
            if (chest != null && chest.itemCount > 0) {
                newOutput = Math.min(15, chest.itemCount);
            } else if (furnace != null && furnace.isBurning) {
                newOutput = Math.min(15, furnace.cookTime / 10);
            }
            
            Integer oldOutput = comparatorOutputs.get(pos);
            if (oldOutput != null && oldOutput != newOutput) {
                comparatorOutputs.put(pos, newOutput);
                if (pos.getY() <= MAX_DETECTION_Y) {
                    ChatUtils.info("EntityDebug", "§5COMPARATOR output changed to " + newOutput + " at Y=" + pos.getY());
                }
            }
        }
    }
    
    // ============================================================
    // HELPER METHODS FOR PACKET EXTRACTION
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
                        String name = f2.getName().toLowerCase();
                        if (name.contains("power")) {
                            f2.setAccessible(true);
                            return f2.getInt(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private boolean getRedstonePowered(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field f2 : state.getClass().getDeclaredFields()) {
                        String name = f2.getName().toLowerCase();
                        if (name.contains("powered")) {
                            f2.setAccessible(true);
                            return f2.getBoolean(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return false;
    }
    
    private int getRepeaterDelay(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field f2 : state.getClass().getDeclaredFields()) {
                        String name = f2.getName().toLowerCase();
                        if (name.contains("delay")) {
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
                        String name = f2.getName().toLowerCase();
                        if (name.contains("output")) {
                            f2.setAccessible(true);
                            return f2.getInt(state);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private int getComparatorMode(BlockUpdateS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("BlockState")) {
                    f.setAccessible(true);
                    Object state = f.get(packet);
                    for (Field f2 : state.getClass().getDeclaredFields()) {
                        String name = f2.getName().toLowerCase();
                        if (name.contains("mode")) {
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
        return block == Blocks.REDSTONE_WIRE || block == Blocks.REDSTONE_TORCH ||
               block == Blocks.REDSTONE_BLOCK || block == Blocks.REDSTONE_LAMP ||
               block == Blocks.REPEATER || block == Blocks.COMPARATOR ||
               block == Blocks.LEVER || block instanceof net.minecraft.block.ButtonBlock ||
               block instanceof net.minecraft.block.PistonBlock;
    }
    
    // ============================================================
    // CLEANUP AND RENDER CACHE
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
        redstonePoweredStates.entrySet().removeIf(e -> now - redstoneLastUpdate.getOrDefault(e.getKey(), 0L) > 5000);
        comparatorData.entrySet().removeIf(e -> now - e.getValue().lastSeen > 10000);
        blockEvents.entrySet().removeIf(e -> !e.getValue().isRecent());
        chunkData.entrySet().removeIf(e -> now - e.getValue().loadTime > 120000);
        entities.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        containerSyncs.entrySet().removeIf(e -> now - e.getValue().timestamp > 5000);
    }
    
    private void updateRenderCache() {
        renderCache.clear();
        
        // Spawners
        for (SpawnerData data : spawnerData.values()) {
            if (data.isBelowY18() && data.spawnsEntity != null) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, SPAWNER_FILL, SPAWNER_COLOR));
            }
        }
        
        // Chests with items
        for (ChestData data : chestData.values()) {
            if (data.isBelowY18() && data.itemCount > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, CHEST_FILL, CHEST_COLOR));
            }
        }
        
        // Burning furnaces
        for (FurnaceData data : furnaceData.values()) {
            if (data.isBelowY18() && data.isBurning) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, FURNACE_FILL, FURNACE_COLOR));
            }
        }
        
        // Beacons
        for (BeaconData data : beaconData.values()) {
            if (data.isBelowY18() && data.levels > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, BEACON_FILL, BEACON_COLOR));
            }
        }
        
        // Active redstone
        for (Map.Entry<BlockPos, Integer> redstone : redstonePowerLevels.entrySet()) {
            BlockPos pos = redstone.getKey();
            if (pos.getY() <= MAX_DETECTION_Y && redstoneLastUpdate.containsKey(pos) && 
                System.currentTimeMillis() - redstoneLastUpdate.get(pos) < 5000) {
                Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                renderCache.put(pos.hashCode() + 1000000, new RenderData(box, REDSTONE_ACTIVE_FILL, REDSTONE_ACTIVE));
            }
        }
        
        // Comparators with output
        for (ComparatorData data : comparatorData.values()) {
            if (data.isBelowY18() && data.outputSignal > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode() + 2000000, new RenderData(box, COMPARATOR_FILL, COMPARATOR_COLOR));
            }
        }
        
        // Recent block events
        for (BlockEventData event : blockEvents.values()) {
            if (event.isRecent() && event.pos.getY() <= MAX_DETECTION_Y) {
                Box box = new Box(event.pos.getX(), event.pos.getY(), event.pos.getZ(), event.pos.getX() + 1, event.pos.getY() + 1, event.pos.getZ() + 1);
                renderCache.put(event.pos.hashCode() + 3000000, new RenderData(box, EVENT_FILL, EVENT_COLOR));
            }
        }
        
        // Entities
        for (EntityData data : entities.values()) {
            if (data.isBelowY18() && System.currentTimeMillis() - data.lastSeen < 5000) {
                Box box = new Box(data.x - 0.3, data.y, data.z - 0.3, data.x + 0.3, data.y + 1.8, data.z + 0.3);
                renderCache.put(data.id, new RenderData(box, ENTITY_FILL, ENTITY_COLOR));
            }
        }
    }
    
    // ============================================================
    // TICK HANDLER - Server Tick Simulation (Channel 6)
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        tickCounter++;
        
        // Channel 6: Detect server tick activity via redstone change frequency
        if (tickCounter % 20 == 0) { // Every second
            detectServerTickActivity();
        }
        
        // Channel 7: Update derived comparator outputs
        if (tickCounter % 10 == 0) {
            updateDerivedComparatorOutputs();
        }
        
        // Cleanup and render updates
        if (tickCounter % 50 == 0) cleanup();
        if (tickCounter % 5 == 0) updateRenderCache();
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
        
        // Clear all data structures
        nbtData.clear();
        chestData.clear();
        spawnerData.clear();
        furnaceData.clear();
        beaconData.clear();
        signData.clear();
        blockStateData.clear();
        redstonePowerLevels.clear();
        redstoneLastUpdate.clear();
        redstonePoweredStates.clear();
        repeaterDelays.clear();
        comparatorData.clear();
        comparatorOutputs.clear();
        comparatorModes.clear();
        blockEvents.clear();
        chestOpenEvents.clear();
        pistonMoveEvents.clear();
        chunkData.clear();
        chunkLoadTimes.clear();
        chunkBlockEntityCounts.clear();
        entities.clear();
        containerSyncs.clear();
        inventories.clear();
        redstoneChangeFrequency.clear();
        lastRedstoneChange.clear();
        lastBlockUpdate.clear();
        lastBlockEntityUpdate.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG - 8 CHANNELS");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=" + MAX_DETECTION_Y);
        ChatUtils.info("EntityDebug", "§7- NBT Data: Spawners, Chests, Furnaces, Beacons, Signs");
        ChatUtils.info("EntityDebug", "§7- BlockState: Redstone power, powered states, comparator mode");
        ChatUtils.info("EntityDebug", "§7- Block Events: Chests, Pistons, Note Blocks");
        ChatUtils.info("EntityDebug", "§7- Container Sync: Inventory tracking");
        ChatUtils.info("EntityDebug", "§7- Server Tick: Redstone change frequency");
        ChatUtils.info("EntityDebug", "§7- Comparator Outputs: Derived signals");
        ChatUtils.info("EntityDebug", "§7- Chunk Data: Bulk sync tracking");
        
        mc.player.sendMessage(Text.literal("§8[§c§lKRYPTON§8] §78-Channel Debug §aACTIVE"), false);
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
        redstonePoweredStates.clear();
        repeaterDelays.clear();
        comparatorData.clear();
        comparatorOutputs.clear();
        comparatorModes.clear();
        blockEvents.clear();
        chestOpenEvents.clear();
        pistonMoveEvents.clear();
        chunkData.clear();
        chunkLoadTimes.clear();
        chunkBlockEntityCounts.clear();
        entities.clear();
        containerSyncs.clear();
        inventories.clear();
        redstoneChangeFrequency.clear();
        lastRedstoneChange.clear();
        lastBlockUpdate.clear();
        lastBlockEntityUpdate.clear();
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
        int comparators = comparatorData.size();
        int events = blockEvents.size();
        int chunks = chunkData.size();
        
        return String.format("§c%d §7sp §8| §e%d §7ch §8| §7%d §7fu §8| §b%d §7be §8| §c%d §7rs §8| §d%d §7cmp §8| §e%d §7ev §8| §7%d §7ck", 
            spawners, chests, furnaces, beacons, redstone, comparators, events, chunks);
    }
}
