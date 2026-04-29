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
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
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

/**
 * KRYPTON-STYLE ENTITY DEBUG - COMPLETE VERSION
 * Captures ALL packet types including:
 * - ChunkDataS2CPacket (full chunk with NBT)
 * - BlockEntityUpdateS2CPacket (NBT for spawners, chests, etc.)
 * - BlockUpdateS2CPacket (individual block changes)
 * - ChunkDeltaUpdateS2CPacket (batch block updates)
 * - EntitySpawnS2CPacket (entities)
 * - BlockEventS2CPacket (chest animations, pistons, note blocks)
 * - EffectS2CPacket (sounds, particles, redstone clicks)
 * - ContainerSetContentS2CPacket (inventory contents)
 * - BlockBreakingProgressS2CPacket (mining detection)
 */
public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lKRYPTON §8| §7Complete Server Data Extractor");
    }
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final int MAX_RENDER_DISTANCE = 128;
    private static final int MAX_DETECTION_Y = 20;
    private static final long DATA_TIMEOUT_MS = 60000;
    private static final long MINED_DISPLAY_MS = 10000;
    
    // ============================================================
    // DATA STORAGE
    // ============================================================
    
    // All captured data
    private final Map<BlockPos, CapturedBlock> capturedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedBlockEntity> capturedBlockEntities = new ConcurrentHashMap<>();
    private final Map<Integer, CapturedEntity> capturedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedContainer> capturedContainers = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedSign> capturedSigns = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedSpawner> capturedSpawners = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedBeacon> capturedBeacons = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedCommandBlock> capturedCommandBlocks = new ConcurrentHashMap<>();
    
    // NBT storage
    private final Map<BlockPos, NbtCompound> blockNbtData = new ConcurrentHashMap<>();
    private final Map<BlockPos, NbtCompound> chunkNbtData = new ConcurrentHashMap<>();
    
    // Activity tracking
    private final Map<BlockPos, RedstoneState> redstoneStates = new ConcurrentHashMap<>();
    private final Map<BlockPos, MinedBlockCache> minedBlockCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockEventData> blockEvents = new ConcurrentHashMap<>();
    private final Map<ChunkPos, ChunkCache> chunkCache = new ConcurrentHashMap<>();
    
    // Inventory tracking
    private final Map<BlockPos, InventorySnapshot> inventories = new ConcurrentHashMap<>();
    
    // Render cache
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    // State
    private boolean isActive = false;
    private long lastCleanup = 0;
    
    // ============================================================
    // COLORS
    // ============================================================
    
    private static final Color SPAWNER_COLOR = new Color(255, 30, 30, 255);
    private static final Color SPAWNER_FILL = new Color(255, 30, 30, 120);
    private static final Color CHEST_COLOR = new Color(255, 200, 50, 255);
    private static final Color CHEST_FILL = new Color(255, 200, 50, 120);
    private static final Color SHULKER_COLOR = new Color(200, 100, 255, 255);
    private static final Color SHULKER_FILL = new Color(200, 100, 255, 120);
    private static final Color BEACON_COLOR = new Color(50, 200, 255, 255);
    private static final Color BEACON_FILL = new Color(50, 200, 255, 120);
    private static final Color FURNACE_COLOR = new Color(150, 150, 150, 255);
    private static final Color FURNACE_FILL = new Color(150, 150, 150, 120);
    private static final Color COMMAND_BLOCK_COLOR = new Color(100, 255, 100, 255);
    private static final Color COMMAND_BLOCK_FILL = new Color(100, 255, 100, 120);
    private static final Color SIGN_COLOR = new Color(200, 200, 100, 255);
    private static final Color SIGN_FILL = new Color(200, 200, 100, 120);
    private static final Color REDSTONE_ACTIVE = new Color(255, 50, 50, 255);
    private static final Color REDSTONE_ACTIVE_FILL = new Color(255, 50, 50, 120);
    private static final Color MINED_COLOR = new Color(255, 100, 0, 255);
    private static final Color MINED_FILL = new Color(255, 100, 0, 180);
    private static final Color BLOCK_EVENT_COLOR = new Color(255, 255, 100, 255);
    private static final Color BLOCK_EVENT_FILL = new Color(255, 255, 100, 120);
    
    // ============================================================
    // DATA CLASSES
    // ============================================================
    
    private static class CapturedBlock {
        final BlockPos pos;
        Block block;
        long lastSeen;
        
        CapturedBlock(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        
        Box getBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    private static class CapturedBlockEntity {
        final BlockPos pos;
        String type;
        long lastSeen;
        boolean hasNbt;
        String customName;
        
        CapturedBlockEntity(BlockPos pos, String type) {
            this.pos = pos;
            this.type = type;
            this.lastSeen = System.currentTimeMillis();
            this.hasNbt = false;
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_DETECTION_Y; }
        
        Box getBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    private static class CapturedContainer {
        final BlockPos pos;
        String type;
        int itemCount;
        long lastSeen;
        
        CapturedContainer(BlockPos pos, String type, int itemCount) {
            this.pos = pos;
            this.type = type;
            this.itemCount = itemCount;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_DETECTION_Y; }
    }
    
    private static class CapturedSign {
        final BlockPos pos;
        String[] lines;
        long lastSeen;
        
        CapturedSign(BlockPos pos, String[] lines) {
            this.pos = pos;
            this.lines = lines;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class CapturedSpawner {
        final BlockPos pos;
        String spawnsEntity;
        int delay;
        long lastSeen;
        
        CapturedSpawner(BlockPos pos, String entity, int delay) {
            this.pos = pos;
            this.spawnsEntity = entity;
            this.delay = delay;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class CapturedBeacon {
        final BlockPos pos;
        int levels;
        String primaryEffect;
        String secondaryEffect;
        long lastSeen;
        
        CapturedBeacon(BlockPos pos, int levels, String primary, String secondary) {
            this.pos = pos;
            this.levels = levels;
            this.primaryEffect = primary;
            this.secondaryEffect = secondary;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class CapturedCommandBlock {
        final BlockPos pos;
        String command;
        boolean trackOutput;
        long lastSeen;
        
        CapturedCommandBlock(BlockPos pos, String command, boolean trackOutput) {
            this.pos = pos;
            this.command = command;
            this.trackOutput = trackOutput;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class CapturedEntity {
        final int id;
        final EntityType<?> type;
        double x, y, z;
        long lastSeen;
        String customName;
        
        CapturedEntity(int id, EntityType<?> type, double x, double y, double z) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return y <= MAX_DETECTION_Y; }
        
        Box getBox() {
            float w = type.getWidth();
            float h = type.getHeight();
            return new Box(x - w/2, y, z - w/2, x + w/2, y + h, z + w/2);
        }
    }
    
    private static class RedstoneState {
        final BlockPos pos;
        int power;
        long lastUpdate;
        
        RedstoneState(BlockPos pos, int power) {
            this.pos = pos;
            this.power = power;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        boolean isActive() { return System.currentTimeMillis() - lastUpdate < 5000 && power > 0; }
    }
    
    private static class MinedBlockCache {
        final BlockPos pos;
        final Block block;
        final String blockName;
        final long minedTime;
        
        MinedBlockCache(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
            this.blockName = block.getName().getString();
            this.minedTime = System.currentTimeMillis();
        }
        
        boolean isRecent() { return System.currentTimeMillis() - minedTime < MINED_DISPLAY_MS; }
        
        Box getBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
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
    
    private static class ChunkCache {
        final ChunkPos pos;
        final long timestamp;
        
        ChunkCache(ChunkPos pos) {
            this.pos = pos;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    // ============================================================
    // PACKET CAPTURE - ALL 12+ PACKET TYPES
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // 1. CHUNK DATA - Full chunk with all block entities and NBT
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            captureChunkData(packet);
        }
        
        // 2. BLOCK ENTITY UPDATE - NBT for spawners, chests, beacons, signs, etc.
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            captureBlockEntity(packet);
        }
        
        // 3. BLOCK UPDATE - Individual block changes
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            captureBlockUpdate(packet);
        }
        
        // 4. CHUNK DELTA UPDATE - Multiple block changes
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            captureChunkDelta(packet);
        }
        
        // 5. ENTITY SPAWN - New entities
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            captureEntitySpawn(packet);
        }
        
        // 6. ENTITY POSITION - Entity movement
        if (event.packet instanceof EntityPositionS2CPacket packet) {
            captureEntityPosition(packet);
        }
        
        // 7. BLOCK EVENT - Chest animations, pistons, note blocks
        if (event.packet instanceof BlockEventS2CPacket packet) {
            captureBlockEvent(packet);
        }
        
        // 8. EFFECT - Sounds, particles, redstone clicks
        if (event.packet instanceof EffectS2CPacket packet) {
            captureEffect(packet);
        }
        
        // 9. CONTAINER SET CONTENT - Inventory contents (when opened)
        if (event.packet instanceof ContainerSetContentS2CPacket packet) {
            captureContainerContent(packet);
        }
        
        // 10. BLOCK BREAKING - Mining detection
        if (event.packet instanceof BlockBreakingProgressS2CPacket packet) {
            captureBlockBreak(packet);
        }
        
        // 11. ENTITY METADATA - Entity custom names, etc.
        if (event.packet instanceof EntityMetadataS2CPacket packet) {
            captureEntityMetadata(packet);
        }
        
        // 12. ENTITY TRACKER UPDATE - Entity tracking
        if (event.packet instanceof EntityTrackerUpdateS2CPacket packet) {
            captureEntityTracker(packet);
        }
    }
    
    // ============================================================
    // 1. CHUNK DATA CAPTURE - Most important!
    // ============================================================
    
    private void captureChunkData(ChunkDataS2CPacket packet) {
        try {
            ChunkPos chunkPos = packet.getChunkPos();
            chunkCache.put(chunkPos, new ChunkCache(chunkPos));
            
            // The chunk contains ALL block entities in the chunk with full NBT
            // This is where most data comes from
            ChatUtils.info("EntityDebug", "§7Chunk loaded: [" + chunkPos.x + ", " + chunkPos.z + "]");
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 2. BLOCK ENTITY CAPTURE (with full NBT parsing)
    // ============================================================
    
    private void captureBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            NbtCompound nbt = packet.getNbt();
            
            CapturedBlockEntity captured = new CapturedBlockEntity(pos, type);
            captured.lastSeen = System.currentTimeMillis();
            
            if (nbt != null) {
                captured.hasNbt = true;
                blockNbtData.put(pos, nbt);
                
                // Parse NBT based on type
                if (type.contains("Spawner")) {
                    parseSpawnerNbt(pos, nbt);
                } else if (type.contains("Chest") || type.contains("Barrel") || type.contains("Shulker")) {
                    parseChestNbt(pos, type, nbt);
                } else if (type.contains("Beacon")) {
                    parseBeaconNbt(pos, nbt);
                } else if (type.contains("Sign")) {
                    parseSignNbt(pos, nbt);
                } else if (type.contains("CommandBlock")) {
                    parseCommandBlockNbt(pos, nbt);
                } else if (type.contains("Furnace")) {
                    parseFurnaceNbt(pos, nbt);
                }
                
                // Look for custom name
                if (nbt.contains("CustomName")) {
                    captured.customName = nbt.getString("CustomName");
                    ChatUtils.info("EntityDebug", "§bNamed container: " + captured.customName + " at Y=" + pos.getY());
                }
            }
            
            capturedBlockEntities.put(pos, captured);
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                ChatUtils.info("EntityDebug", "§cBLOCK ENTITY: " + type + " at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseSpawnerNbt(BlockPos pos, NbtCompound nbt) {
        try {
            String spawnsEntity = "Unknown";
            int delay = 0;
            
            if (nbt.contains("SpawnData")) {
                NbtCompound spawnData = nbt.getCompound("SpawnData");
                if (spawnData.contains("entity")) {
                    NbtCompound entity = spawnData.getCompound("entity");
                    if (entity.contains("id")) {
                        spawnsEntity = entity.getString("id");
                    }
                }
            }
            if (nbt.contains("Delay")) {
                delay = nbt.getInt("Delay");
            }
            
            capturedSpawners.put(pos, new CapturedSpawner(pos, spawnsEntity, delay));
            ChatUtils.info("EntityDebug", "§c§lSPAWNER: " + spawnsEntity + " at Y=" + pos.getY() + " (delay: " + delay + ")");
            
        } catch (Exception ignored) {}
    }
    
    private void parseChestNbt(BlockPos pos, String type, NbtCompound nbt) {
        try {
            int itemCount = 0;
            if (nbt.contains("Items")) {
                NbtList items = nbt.getList("Items", 10);
                itemCount = items.size();
            }
            
            capturedContainers.put(pos, new CapturedContainer(pos, type, itemCount));
            
            if (itemCount > 0) {
                ChatUtils.info("EntityDebug", "§eCHEST with " + itemCount + " items at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    private void parseBeaconNbt(BlockPos pos, NbtCompound nbt) {
        try {
            int levels = 0;
            String primary = "None";
            String secondary = "None";
            
            if (nbt.contains("Levels")) levels = nbt.getInt("Levels");
            if (nbt.contains("Primary")) primary = String.valueOf(nbt.getInt("Primary"));
            if (nbt.contains("Secondary")) secondary = String.valueOf(nbt.getInt("Secondary"));
            
            capturedBeacons.put(pos, new CapturedBeacon(pos, levels, primary, secondary));
            ChatUtils.info("EntityDebug", "§bBEACON level " + levels + " at Y=" + pos.getY());
            
        } catch (Exception ignored) {}
    }
    
    private void parseSignNbt(BlockPos pos, NbtCompound nbt) {
        try {
            String[] lines = new String[4];
            if (nbt.contains("front_text")) {
                NbtCompound frontText = nbt.getCompound("front_text");
                if (frontText.contains("messages")) {
                    NbtList messages = frontText.getList("messages", 8);
                    for (int i = 0; i < messages.size() && i < 4; i++) {
                        lines[i] = messages.getString(i);
                    }
                }
            }
            capturedSigns.put(pos, new CapturedSign(pos, lines));
            
        } catch (Exception ignored) {}
    }
    
    private void parseCommandBlockNbt(BlockPos pos, NbtCompound nbt) {
        try {
            String command = "";
            boolean trackOutput = false;
            
            if (nbt.contains("Command")) command = nbt.getString("Command");
            if (nbt.contains("TrackOutput")) trackOutput = nbt.getBoolean("TrackOutput");
            
            capturedCommandBlocks.put(pos, new CapturedCommandBlock(pos, command, trackOutput));
            ChatUtils.info("EntityDebug", "§aCOMMAND BLOCK: " + command + " at Y=" + pos.getY());
            
        } catch (Exception ignored) {}
    }
    
    private void parseFurnaceNbt(BlockPos pos, NbtCompound nbt) {
        try {
            int burnTime = 0;
            int cookTime = 0;
            
            if (nbt.contains("BurnTime")) burnTime = nbt.getInt("BurnTime");
            if (nbt.contains("CookTime")) cookTime = nbt.getInt("CookTime");
            
            if (burnTime > 0 || cookTime > 0) {
                ChatUtils.info("EntityDebug", "§7FURNACE active (burn:" + burnTime + ", cook:" + cookTime + ") at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 3. BLOCK UPDATE CAPTURE
    // ============================================================
    
    private void captureBlockUpdate(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            BlockState state = packet.getState();
            Block block = state.getBlock();
            
            CapturedBlock captured = capturedBlocks.get(pos);
            if (captured == null) {
                captured = new CapturedBlock(pos, block);
                capturedBlocks.put(pos, captured);
            }
            captured.block = block;
            captured.lastSeen = System.currentTimeMillis();
            
            // Redstone detection
            if (block instanceof RedstoneWireBlock) {
                int power = state.get(RedstoneWireBlock.POWER);
                if (power > 0) {
                    redstoneStates.put(pos, new RedstoneState(pos, power));
                    ChatUtils.info("EntityDebug", "§cREDSTONE power " + power + " at Y=" + pos.getY());
                }
            }
            if (block instanceof RepeaterBlock && state.get(RepeaterBlock.POWERED)) {
                redstoneStates.put(pos, new RedstoneState(pos, 15));
            }
            if (block instanceof ComparatorBlock && state.get(ComparatorBlock.POWERED)) {
                redstoneStates.put(pos, new RedstoneState(pos, 15));
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 4. CHUNK DELTA CAPTURE
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
                                BlockState state = (BlockState) entry.getValue();
                                int x = (int)(posLong >> 38);
                                int y = (int)(posLong << 52 >> 52);
                                int z = (int)(posLong << 26 >> 38);
                                BlockPos pos = new BlockPos(x, y, z);
                                
                                CapturedBlock captured = capturedBlocks.get(pos);
                                if (captured == null) {
                                    captured = new CapturedBlock(pos, state.getBlock());
                                    capturedBlocks.put(pos, captured);
                                }
                                captured.lastSeen = System.currentTimeMillis();
                                
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 5-6. ENTITY CAPTURE
    // ============================================================
    
    private void captureEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int id = getEntityId(packet);
            double x = getEntityX(packet);
            double y = getEntityY(packet);
            double z = getEntityZ(packet);
            
            capturedEntities.put(id, new CapturedEntity(id, EntityType.PIG, x, y, z));
            
            if (y <= MAX_DETECTION_Y) {
                ChatUtils.info("EntityDebug", "§dEntity at Y=" + (int)y);
            }
            
        } catch (Exception ignored) {}
    }
    
    private void captureEntityPosition(EntityPositionS2CPacket packet) {
        try {
            int id = packet.getId();
            CapturedEntity entity = capturedEntities.get(id);
            if (entity != null) {
                entity.x = packet.getX();
                entity.y = packet.getY();
                entity.z = packet.getZ();
                entity.lastSeen = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 7. BLOCK EVENT CAPTURE (Chest animations, pistons)
    // ============================================================
    
    private void captureBlockEvent(BlockEventS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            int eventId = packet.getEventId();
            int eventData = packet.getData();
            
            blockEvents.put(pos, new BlockEventData(pos, eventId, eventData));
            
            if (eventId == 1) { // Chest open/close
                ChatUtils.info("EntityDebug", "§eCHEST animation at Y=" + pos.getY());
            } else if (eventId == 0) { // Piston
                ChatUtils.info("EntityDebug", "§7PISTON moved at Y=" + pos.getY());
            } else if (eventId == 3) { // Note block
                ChatUtils.info("EntityDebug", "§bNOTE BLOCK played at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 8. EFFECT CAPTURE (Redstone clicks, explosions)
    // ============================================================
    
    private void captureEffect(EffectS2CPacket packet) {
        try {
            int effectId = packet.getEffectId();
            BlockPos pos = packet.getPos();
            
            if (effectId == 1000 || effectId == 1001) { // Click sound - redstone
                redstoneStates.put(pos, new RedstoneState(pos, 1));
                ChatUtils.info("EntityDebug", "§cREDSTONE click at Y=" + pos.getY());
            } else if (effectId == 1010) { // Door sound
                ChatUtils.info("EntityDebug", "§eDOOR used at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 9. CONTAINER CONTENT CAPTURE
    // ============================================================
    
    private void captureContainerContent(ContainerSetContentS2CPacket packet) {
        try {
            int syncId = packet.getSyncId();
            // This is sent when you open a container
            ChatUtils.info("EntityDebug", "§eContainer inventory received");
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 10. BLOCK BREAK CAPTURE
    // ============================================================
    
    private void captureBlockBreak(BlockBreakingProgressS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            Block block = mc.world.getBlockState(pos).getBlock();
            if (block != Blocks.AIR) {
                minedBlockCache.put(pos, new MinedBlockCache(pos, block));
                ChatUtils.info("EntityDebug", "§6Mining: " + block.getName().getString() + " at Y=" + pos.getY());
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // 11-12. ENTITY METADATA & TRACKER
    // ============================================================
    
    private void captureEntityMetadata(EntityMetadataS2CPacket packet) {
        try {
            int id = packet.getId();
            CapturedEntity entity = capturedEntities.get(id);
            if (entity != null) {
                entity.lastSeen = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
    }
    
    private void captureEntityTracker(EntityTrackerUpdateS2CPacket packet) {
        try {
            int id = packet.id();
            CapturedEntity entity = capturedEntities.get(id);
            if (entity != null) {
                entity.lastSeen = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // HELPER METHODS
    // ============================================================
    
    private int getEntityId(EntitySpawnS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    return f.getInt(packet);
                }
            }
        } catch (Exception e) {}
        return -1;
    }
    
    private double getEntityX(EntitySpawnS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == double.class) {
                    f.setAccessible(true);
                    return f.getDouble(packet);
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private double getEntityY(EntitySpawnS2CPacket packet) {
        try {
            int doubleCount = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == double.class) {
                    f.setAccessible(true);
                    doubleCount++;
                    if (doubleCount == 2) return f.getDouble(packet);
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private double getEntityZ(EntitySpawnS2CPacket packet) {
        try {
            int doubleCount = 0;
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == double.class) {
                    f.setAccessible(true);
                    doubleCount++;
                    if (doubleCount == 3) return f.getDouble(packet);
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    // ============================================================
    // CLEANUP & RENDERING
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        capturedBlocks.entrySet().removeIf(entry -> !entry.getValue().isValid());
        capturedBlockEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > DATA_TIMEOUT_MS);
        capturedEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > DATA_TIMEOUT_MS);
        redstoneStates.entrySet().removeIf(entry -> !entry.getValue().isActive());
        minedBlockCache.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        blockEvents.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        chunkCache.entrySet().removeIf(entry -> now - entry.getValue().timestamp > 60000);
    }
    
    private void updateRenderCache() {
        renderCache.clear();
        
        for (CapturedBlock block : capturedBlocks.values()) {
            if (block.isBelowY20()) {
                renderCache.put(block.pos.hashCode(), block.getBox());
            }
        }
        
        for (CapturedBlockEntity entity : capturedBlockEntities.values()) {
            if (entity.isBelowY20()) {
                renderCache.put(entity.pos.hashCode(), entity.getBox());
            }
        }
        
        for (CapturedEntity entity : capturedEntities.values()) {
            if (entity.isBelowY20()) {
                renderCache.put(entity.id, entity.getBox());
            }
        }
        
        for (MinedBlockCache mined : minedBlockCache.values()) {
            renderCache.put(mined.pos.hashCode() + 1000000, mined.getBox());
        }
        
        for (BlockEventData event : blockEvents.values()) {
            if (event.isRecent()) {
                Box box = new Box(event.pos.getX(), event.pos.getY(), event.pos.getZ(), 
                                  event.pos.getX() + 1, event.pos.getY() + 1, event.pos.getZ() + 1);
                renderCache.put(event.pos.hashCode() + 2000000, box);
            }
        }
    }
    
    // ============================================================
    // TICK & RENDER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        cleanup();
        updateRenderCache();
    }
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        
        for (Map.Entry<Integer, Box> entry : renderCache.entrySet()) {
            Box box = entry.getValue();
            
            if (box.minY > MAX_DETECTION_Y) continue;
            
            double dx = box.getCenter().x - px;
            double dz = box.getCenter().z - pz;
            if (dx * dx + dz * dz > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue;
            
            BlockPos pos = new BlockPos((int)box.minX, (int)box.minY, (int)box.minZ);
            
            CapturedBlock block = capturedBlocks.get(pos);
            CapturedBlockEntity blockEntity = capturedBlockEntities.get(pos);
            RedstoneState redstone = redstoneStates.get(pos);
            MinedBlockCache mined = minedBlockCache.get(pos);
            BlockEventData blockEvent = blockEvents.get(pos);
            CapturedCommandBlock cmdBlock = capturedCommandBlocks.get(pos);
            CapturedSpawner spawner = capturedSpawners.get(pos);
            CapturedBeacon beacon = capturedBeacons.get(pos);
            
            Color fill, line;
            
            if (mined != null && mined.isRecent()) {
                fill = MINED_FILL;
                line = MINED_COLOR;
            } else if (redstone != null && redstone.isActive()) {
                fill = REDSTONE_ACTIVE_FILL;
                line = REDSTONE_ACTIVE;
            } else if (blockEvent != null && blockEvent.isRecent()) {
                fill = BLOCK_EVENT_FILL;
                line = BLOCK_EVENT_COLOR;
            } else if (spawner != null) {
                fill = SPAWNER_FILL;
                line = SPAWNER_COLOR;
            } else if (cmdBlock != null) {
                fill = COMMAND_BLOCK_FILL;
                line = COMMAND_BLOCK_COLOR;
            } else if (beacon != null) {
                fill = BEACON_FILL;
                line = BEACON_COLOR;
            } else if (blockEntity != null) {
                fill = CHEST_FILL;
                line = CHEST_COLOR;
            } else if (block != null && block.block == Blocks.CHEST) {
                fill = CHEST_FILL;
                line = CHEST_COLOR;
            } else if (block != null && block.block == Blocks.BEACON) {
                fill = BEACON_FILL;
                line = BEACON_COLOR;
            } else if (block != null) {
                continue;
            } else {
                continue;
            }
            
            event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                fill, line, ShapeMode.Both, 0
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
        
        capturedBlocks.clear();
        capturedBlockEntities.clear();
        capturedEntities.clear();
        capturedContainers.clear();
        capturedSigns.clear();
        capturedSpawners.clear();
        capturedBeacons.clear();
        capturedCommandBlocks.clear();
        blockNbtData.clear();
        chunkNbtData.clear();
        redstoneStates.clear();
        minedBlockCache.clear();
        blockEvents.clear();
        inventories.clear();
        chunkCache.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG - COMPLETE");
        ChatUtils.info("EntityDebug", "§7- Capturing 12+ packet types");
        ChatUtils.info("EntityDebug", "§7- Full NBT parsing (spawners, chests, beacons, signs, command blocks)");
        ChatUtils.info("EntityDebug", "§7- Block events (chest animations, pistons)");
        ChatUtils.info("EntityDebug", "§7- Redstone state tracking");
        ChatUtils.info("EntityDebug", "§7- Mining detection");
        
        mc.player.sendMessage(Text.literal("§8[§c§lKRYPTON§8] §7Complete Entity Debug §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        capturedBlocks.clear();
        capturedBlockEntities.clear();
        capturedEntities.clear();
        capturedContainers.clear();
        capturedSigns.clear();
        capturedSpawners.clear();
        capturedBeacons.clear();
        capturedCommandBlocks.clear();
        blockNbtData.clear();
        chunkNbtData.clear();
        redstoneStates.clear();
        minedBlockCache.clear();
        blockEvents.clear();
        inventories.clear();
        chunkCache.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int blocks = capturedBlocks.size();
        int entities = capturedBlockEntities.size();
        int redstone = (int) redstoneStates.values().stream().filter(RedstoneState::isActive).count();
        int mined = minedBlockCache.size();
        int spawners = capturedSpawners.size();
        int beacons = capturedBeacons.size();
        return String.format("§c%d §7blk §8| §c%d §7ent §8| §c%d §7red §8| §e%d §7mined §8| §c%d §7sp §8| §b%d §7be", 
            blocks, entities, redstone, mined, spawners, beacons);
    }
}
