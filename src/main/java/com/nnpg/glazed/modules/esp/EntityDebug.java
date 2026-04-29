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
    
    // 1. NBT Block Entity Data
    private final Map<BlockPos, ChestData> chestData = new ConcurrentHashMap<>();
    private final Map<BlockPos, SpawnerData> spawnerData = new ConcurrentHashMap<>();
    private final Map<BlockPos, FurnaceData> furnaceData = new ConcurrentHashMap<>();
    private final Map<BlockPos, BeaconData> beaconData = new ConcurrentHashMap<>();
    
    // 2. Packets tracking
    private final Map<ChunkPos, Long> chunkLoadTimes = new ConcurrentHashMap<>();
    
    // 3. BlockState (Redstone power, powered states)
    private final Map<BlockPos, Integer> redstonePowerLevels = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> redstoneLastUpdate = new ConcurrentHashMap<>();
    
    // 4. Container Sync
    private final Map<Integer, Long> containerSyncs = new ConcurrentHashMap<>();
    
    // 5. Block Events
    private final Map<BlockPos, BlockEventData> blockEvents = new ConcurrentHashMap<>();
    
    // 6. Server Tick Updates - Tracked via redstone change frequency
    private final Map<BlockPos, Integer> redstoneChangeFrequency = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastRedstoneChange = new ConcurrentHashMap<>();
    
    // 7. Comparator Output System
    private final Map<BlockPos, Integer> comparatorOutputs = new ConcurrentHashMap<>();
    
    // 8. Chunk Section Data
    private final Map<ChunkPos, Long> chunkData = new ConcurrentHashMap<>();
    
    // Entity tracking
    private final Map<Integer, EntityData> entities = new ConcurrentHashMap<>();
    
    // Render cache
    private final Map<Integer, RenderData> renderCache = new ConcurrentHashMap<>();
    
    private boolean isActive = false;
    private int tickCounter = 0;
    
    // ============================================================
    // DATA CLASSES
    // ============================================================
    
    private static class ChestData {
        final BlockPos pos;
        int itemCount;
        long lastSeen;
        ChestData(BlockPos pos) { this.pos = pos; this.lastSeen = System.currentTimeMillis(); }
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class SpawnerData {
        final BlockPos pos;
        String spawnsEntity;
        long lastSeen;
        SpawnerData(BlockPos pos) { this.pos = pos; this.lastSeen = System.currentTimeMillis(); }
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class FurnaceData {
        final BlockPos pos;
        boolean isBurning;
        long lastSeen;
        FurnaceData(BlockPos pos) { this.pos = pos; this.lastSeen = System.currentTimeMillis(); }
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class BeaconData {
        final BlockPos pos;
        int levels;
        long lastSeen;
        BeaconData(BlockPos pos) { this.pos = pos; this.lastSeen = System.currentTimeMillis(); }
        boolean isBelowY18() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class BlockEventData {
        final BlockPos pos;
        final int eventId;
        final long timestamp;
        BlockEventData(BlockPos pos, int eventId) { this.pos = pos; this.eventId = eventId; this.timestamp = System.currentTimeMillis(); }
        boolean isRecent() { return System.currentTimeMillis() - timestamp < 3000; }
    }
    
    private static class EntityData {
        final int id;
        double x, y, z;
        long lastSeen;
        EntityData(int id, double x, double y, double z) { this.id = id; this.x = x; this.y = y; this.z = z; this.lastSeen = System.currentTimeMillis(); }
        boolean isBelowY18() { return y <= MAX_DETECTION_Y; }
    }
    
    private static class RenderData {
        final Box box;
        final Color fill;
        final Color line;
        RenderData(Box box, Color fill, Color line) { this.box = box; this.fill = fill; this.line = line; }
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
        
        // 1 & 8. CHUNK DATA - Bulk world sync
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            captureChunkData(packet);
        }
        
        // 1. BLOCK ENTITY UPDATE - NBT data
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            captureBlockEntityNBT(packet);
        }
        
        // 2 & 3. BLOCK UPDATE - State changes, redstone power
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            captureBlockState(packet);
        }
        
        // 2. CHUNK DELTA - Multiple block updates
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            captureChunkDelta(packet);
        }
        
        // 2 & 5. BLOCK EVENT - Actions/animations
        if (event.packet instanceof BlockEventS2CPacket packet) {
            captureBlockEvent(packet);
        }
        
        // 4. CONTAINER SYNC - Inventory override
        if (event.packet instanceof OpenScreenS2CPacket packet) {
            captureContainerSync(packet);
        }
        
        // 2. ENTITY SPAWN
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            captureEntitySpawn(packet);
        }
    }
    
    // ============================================================
    // CHANNEL 1 & 8: NBT + CHUNK SECTION DATA
    // ============================================================
    
    private void captureChunkData(ChunkDataS2CPacket packet) {
        try {
            int chunkX = 0, chunkZ = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("x")) chunkX = f.getInt(packet);
                if (f.getName().equals("z")) chunkZ = f.getInt(packet);
            }
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            chunkData.put(pos, System.currentTimeMillis());
            chunkLoadTimes.put(pos, System.currentTimeMillis());
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
                if (type.contains("Spawner")) parseSpawnerNBT(pos, nbt);
                if (type.contains("Chest") || type.contains("Barrel") || type.contains("Shulker")) parseChestNBT(pos, nbt);
                if (type.contains("Furnace")) parseFurnaceNBT(pos, nbt);
                if (type.contains("Beacon")) parseBeaconNBT(pos, nbt);
            }
        } catch (Exception ignored) {}
    }
    
    private void parseSpawnerNBT(BlockPos pos, NbtCompound nbt) {
        SpawnerData data = spawnerData.computeIfAbsent(pos, k -> new SpawnerData(pos));
        try {
            nbt.getCompound("SpawnData").ifPresent(spawnData -> {
                spawnData.getCompound("entity").ifPresent(entity -> {
                    entity.getString("id").ifPresent(id -> data.spawnsEntity = id);
                });
            });
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.spawnsEntity != null) {
                ChatUtils.info("EntityDebug", "§c§lSPAWNER: " + data.spawnsEntity + " at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    private void parseChestNBT(BlockPos pos, NbtCompound nbt) {
        ChestData data = chestData.computeIfAbsent(pos, k -> new ChestData(pos));
        try {
            // Fixed: getList only takes String in 1.21.11 - no type parameter
            NbtList items = nbt.getList("Items");
            data.itemCount = items.size();
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.itemCount > 0) {
                ChatUtils.info("EntityDebug", "§eCHEST with " + data.itemCount + " items at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    private void parseFurnaceNBT(BlockPos pos, NbtCompound nbt) {
        FurnaceData data = furnaceData.computeIfAbsent(pos, k -> new FurnaceData(pos));
        try {
            int burnTime = nbt.getInt("BurnTime").orElse(0);
            data.isBurning = burnTime > 0;
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.isBurning) {
                ChatUtils.info("EntityDebug", "§7FURNACE burning at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    private void parseBeaconNBT(BlockPos pos, NbtCompound nbt) {
        BeaconData data = beaconData.computeIfAbsent(pos, k -> new BeaconData(pos));
        try {
            data.levels = nbt.getInt("Levels").orElse(0);
            data.lastSeen = System.currentTimeMillis();
            if (pos.getY() <= MAX_DETECTION_Y && data.levels > 0) {
                ChatUtils.info("EntityDebug", "§bBEACON level " + data.levels + " at Y=" + pos.getY());
            }
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
                // Channel 3: Redstone power level detection
                if (isRedstoneComponent(block)) {
                    int power = getRedstonePower(packet);
                    if (power > 0) {
                        redstonePowerLevels.put(pos, power);
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
                
                // Channel 7: Comparator output detection
                if (block == Blocks.COMPARATOR) {
                    int output = getComparatorOutput(packet);
                    comparatorOutputs.put(pos, output);
                    if (pos.getY() <= MAX_DETECTION_Y && output > 0) {
                        ChatUtils.info("EntityDebug", "§5COMPARATOR output " + output + " at Y=" + pos.getY());
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
                                int y = (int)(posLong << 52 >> 52);
                                if (y <= MAX_DETECTION_Y) {
                                    // Just mark that activity happened
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 2 & 5: BLOCK EVENTS
    // ============================================================
    
    private void captureBlockEvent(BlockEventS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            // Use reflection to get event ID since getEventId() doesn't exist in 1.21.11
            int eventId = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    eventId = f.getInt(packet);
                    break;
                }
            }
            blockEvents.put(pos, new BlockEventData(pos, eventId));
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                if (eventId == 1) ChatUtils.info("EntityDebug", "§eCHEST animation at Y=" + pos.getY());
                else if (eventId == 0) ChatUtils.info("EntityDebug", "§7PISTON moved at Y=" + pos.getY());
                else if (eventId == 3) ChatUtils.info("EntityDebug", "§bNOTE BLOCK played at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 4: CONTAINER SYNC
    // ============================================================
    
    private void captureContainerSync(OpenScreenS2CPacket packet) {
        try {
            int syncId = packet.getSyncId();
            containerSyncs.put(syncId, System.currentTimeMillis());
            ChatUtils.info("EntityDebug", "§6Container opened - inventory sync active");
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // ENTITY SPAWN
    // ============================================================
    
    private void captureEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int id = -1;
            double y = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("id")) id = f.getInt(packet);
                if (f.getType() == double.class) {
                    double val = f.getDouble(packet);
                    if (y == 0) y = val;
                }
            }
            if (id != -1 && y <= MAX_DETECTION_Y) {
                entities.put(id, new EntityData(id, 0, y, 0));
                ChatUtils.info("EntityDebug", "§dEntity at Y=" + (int)y);
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CHANNEL 6: SERVER TICK DETECTION
    // ============================================================
    
    private void detectServerTickActivity() {
        for (Map.Entry<BlockPos, Integer> freq : redstoneChangeFrequency.entrySet()) {
            BlockPos pos = freq.getKey();
            if (pos.getY() <= MAX_DETECTION_Y && freq.getValue() > 20 && 
                System.currentTimeMillis() - lastRedstoneChange.getOrDefault(pos, 0L) < 5000) {
                ChatUtils.info("EntityDebug", "§6Active redstone circuit at Y=" + pos.getY() + " - " + freq.getValue() + " changes/sec");
            }
        }
        if (tickCounter % 100 == 0) redstoneChangeFrequency.clear();
    }
    
    // ============================================================
    // CHANNEL 7: UPDATE COMPARATOR OUTPUTS (Derived signal)
    // ============================================================
    
    private void updateDerivedComparatorOutputs() {
        for (Map.Entry<BlockPos, Integer> comp : comparatorOutputs.entrySet()) {
            BlockPos below = comp.getKey().down();
            ChestData chest = chestData.get(below);
            int newOutput = 0;
            if (chest != null && chest.itemCount > 0) newOutput = Math.min(15, chest.itemCount);
            if (comp.getValue() != newOutput) {
                comparatorOutputs.put(comp.getKey(), newOutput);
                if (comp.getKey().getY() <= MAX_DETECTION_Y && newOutput > 0) {
                    ChatUtils.info("EntityDebug", "§5COMPARATOR output changed to " + newOutput + " at Y=" + comp.getKey().getY());
                }
            }
        }
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
                        if (f2.getName().toLowerCase().contains("power")) {
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
                        if (f2.getName().toLowerCase().contains("output")) {
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
               block == Blocks.LEVER;
    }
    
    // ============================================================
    // CLEANUP AND RENDER CACHE
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        chestData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        spawnerData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        furnaceData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        beaconData.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        redstoneLastUpdate.entrySet().removeIf(e -> now - e.getValue() > 5000);
        blockEvents.entrySet().removeIf(e -> !e.getValue().isRecent());
        entities.entrySet().removeIf(e -> now - e.getValue().lastSeen > DATA_TIMEOUT_MS);
        chunkData.entrySet().removeIf(e -> now - e.getValue() > 120000);
        containerSyncs.entrySet().removeIf(e -> now - e.getValue() > 5000);
    }
    
    private void updateRenderCache() {
        renderCache.clear();
        
        for (SpawnerData data : spawnerData.values()) {
            if (data.isBelowY18() && data.spawnsEntity != null) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, SPAWNER_FILL, SPAWNER_COLOR));
            }
        }
        
        for (ChestData data : chestData.values()) {
            if (data.isBelowY18() && data.itemCount > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, CHEST_FILL, CHEST_COLOR));
            }
        }
        
        for (FurnaceData data : furnaceData.values()) {
            if (data.isBelowY18() && data.isBurning) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, FURNACE_FILL, FURNACE_COLOR));
            }
        }
        
        for (BeaconData data : beaconData.values()) {
            if (data.isBelowY18() && data.levels > 0) {
                Box box = new Box(data.pos.getX(), data.pos.getY(), data.pos.getZ(), data.pos.getX() + 1, data.pos.getY() + 1, data.pos.getZ() + 1);
                renderCache.put(data.pos.hashCode(), new RenderData(box, BEACON_FILL, BEACON_COLOR));
            }
        }
        
        for (Map.Entry<BlockPos, Integer> redstone : redstonePowerLevels.entrySet()) {
            BlockPos pos = redstone.getKey();
            if (pos.getY() <= MAX_DETECTION_Y && redstoneLastUpdate.containsKey(pos) && 
                System.currentTimeMillis() - redstoneLastUpdate.get(pos) < 5000) {
                Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                renderCache.put(pos.hashCode() + 1000000, new RenderData(box, REDSTONE_ACTIVE_FILL, REDSTONE_ACTIVE));
            }
        }
        
        for (Map.Entry<BlockPos, Integer> comp : comparatorOutputs.entrySet()) {
            if (comp.getKey().getY() <= MAX_DETECTION_Y && comp.getValue() > 0) {
                Box box = new Box(comp.getKey().getX(), comp.getKey().getY(), comp.getKey().getZ(), comp.getKey().getX() + 1, comp.getKey().getY() + 1, comp.getKey().getZ() + 1);
                renderCache.put(comp.getKey().hashCode() + 2000000, new RenderData(box, COMPARATOR_FILL, COMPARATOR_COLOR));
            }
        }
        
        for (BlockEventData event : blockEvents.values()) {
            if (event.isRecent() && event.pos.getY() <= MAX_DETECTION_Y) {
                Box box = new Box(event.pos.getX(), event.pos.getY(), event.pos.getZ(), event.pos.getX() + 1, event.pos.getY() + 1, event.pos.getZ() + 1);
                renderCache.put(event.pos.hashCode() + 3000000, new RenderData(box, EVENT_FILL, EVENT_COLOR));
            }
        }
        
        for (EntityData data : entities.values()) {
            if (data.isBelowY18() && System.currentTimeMillis() - data.lastSeen < 5000) {
                Box box = new Box(data.x - 0.3, data.y, data.z - 0.3, data.x + 0.3, data.y + 1.8, data.z + 0.3);
                renderCache.put(data.id, new RenderData(box, ENTITY_FILL, ENTITY_COLOR));
            }
        }
    }
    
    // ============================================================
    // TICK & RENDER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        tickCounter++;
        if (tickCounter % 20 == 0) detectServerTickActivity();
        if (tickCounter % 10 == 0) updateDerivedComparatorOutputs();
        if (tickCounter % 50 == 0) cleanup();
        if (tickCounter % 5 == 0) updateRenderCache();
    }
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        double px = mc.player.getX(), pz = mc.player.getZ();
        for (RenderData render : renderCache.values()) {
            double dx = render.box.getCenter().x - px;
            double dz = render.box.getCenter().z - pz;
            if (dx * dx + dz * dz > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue;
            event.renderer.box(render.box.minX, render.box.minY, render.box.minZ, render.box.maxX, render.box.maxY, render.box.maxZ, render.fill, render.line, ShapeMode.Both, 0);
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
        
        chestData.clear(); spawnerData.clear(); furnaceData.clear(); beaconData.clear();
        redstonePowerLevels.clear(); redstoneLastUpdate.clear(); blockEvents.clear();
        chunkData.clear(); chunkLoadTimes.clear(); entities.clear(); containerSyncs.clear();
        redstoneChangeFrequency.clear(); lastRedstoneChange.clear(); comparatorOutputs.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG - 8 CHANNELS");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=" + MAX_DETECTION_Y);
        ChatUtils.info("EntityDebug", "§7- NBT Data: Spawners, Chests, Furnaces, Beacons");
        ChatUtils.info("EntityDebug", "§7- BlockState: Redstone power levels");
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
        chestData.clear(); spawnerData.clear(); furnaceData.clear(); beaconData.clear();
        redstonePowerLevels.clear(); redstoneLastUpdate.clear(); blockEvents.clear();
        chunkData.clear(); chunkLoadTimes.clear(); entities.clear(); containerSyncs.clear();
        redstoneChangeFrequency.clear(); lastRedstoneChange.clear(); comparatorOutputs.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int spawners = (int) spawnerData.values().stream().filter(s -> s.pos.getY() <= MAX_DETECTION_Y).count();
        int chests = (int) chestData.values().stream().filter(c -> c.pos.getY() <= MAX_DETECTION_Y && c.itemCount > 0).count();
        int furnaces = (int) furnaceData.values().stream().filter(f -> f.pos.getY() <= MAX_DETECTION_Y && f.isBurning).count();
        int beacons = (int) beaconData.values().stream().filter(b -> b.pos.getY() <= MAX_DETECTION_Y && b.levels > 0).count();
        int redstone = redstonePowerLevels.size();
        int comparators = comparatorOutputs.size();
        int events = blockEvents.size();
        int chunks = chunkData.size();
        return String.format("§c%d sp §8| §e%d ch §8| §7%d fu §8| §b%d be §8| §c%d rs §8| §d%d cmp §8| §e%d ev §8| §7%d ck", 
            spawners, chests, furnaces, beacons, redstone, comparators, events, chunks);
    }
}
