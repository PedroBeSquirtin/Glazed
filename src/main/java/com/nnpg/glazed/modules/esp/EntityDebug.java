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
        super(GlazedAddon.esp, "entity-debug", "§c§lKRYPTON §8| §7Server Entity Extractor");
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
    
    private final Map<BlockPos, CapturedBlock> capturedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedBlockEntity> capturedBlockEntities = new ConcurrentHashMap<>();
    private final Map<Integer, CapturedEntity> capturedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedSpawner> capturedSpawners = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedContainer> capturedContainers = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedBeacon> capturedBeacons = new ConcurrentHashMap<>();
    private final Map<BlockPos, RedstoneState> redstoneStates = new ConcurrentHashMap<>();
    private final Map<BlockPos, MinedBlockCache> minedBlockCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockEventData> blockEvents = new ConcurrentHashMap<>();
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    private boolean isActive = false;
    
    // ============================================================
    // COLORS
    // ============================================================
    
    private static final Color SPAWNER_COLOR = new Color(255, 30, 30, 255);
    private static final Color SPAWNER_FILL = new Color(255, 30, 30, 120);
    private static final Color CHEST_COLOR = new Color(255, 200, 50, 255);
    private static final Color CHEST_FILL = new Color(255, 200, 50, 120);
    private static final Color BEACON_COLOR = new Color(50, 200, 255, 255);
    private static final Color BEACON_FILL = new Color(50, 200, 255, 120);
    private static final Color REDSTONE_ACTIVE = new Color(255, 50, 50, 255);
    private static final Color REDSTONE_ACTIVE_FILL = new Color(255, 50, 50, 120);
    private static final Color MINED_COLOR = new Color(255, 100, 0, 255);
    private static final Color MINED_FILL = new Color(255, 100, 0, 180);
    private static final Color BLOCK_EVENT_COLOR = new Color(255, 255, 100, 255);
    private static final Color BLOCK_EVENT_FILL = new Color(255, 255, 100, 120);
    private static final Color ENTITY_COLOR = new Color(255, 100, 255, 255);
    private static final Color ENTITY_FILL = new Color(255, 100, 255, 120);
    
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
    
    private static class CapturedSpawner {
        final BlockPos pos;
        String spawnsEntity;
        int delay;
        
        CapturedSpawner(BlockPos pos, String entity, int delay) {
            this.pos = pos;
            this.spawnsEntity = entity;
            this.delay = delay;
        }
    }
    
    private static class CapturedContainer {
        final BlockPos pos;
        String type;
        int itemCount;
        
        CapturedContainer(BlockPos pos, String type, int itemCount) {
            this.pos = pos;
            this.type = type;
            this.itemCount = itemCount;
        }
    }
    
    private static class CapturedBeacon {
        final BlockPos pos;
        int levels;
        
        CapturedBeacon(BlockPos pos, int levels) {
            this.pos = pos;
            this.levels = levels;
        }
    }
    
    private static class CapturedEntity {
        final int id;
        final EntityType<?> type;
        double x, y, z;
        long lastSeen;
        
        CapturedEntity(int id, EntityType<?> type, double x, double y, double z) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return y <= MAX_DETECTION_Y; }
        boolean isRecent() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        
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
        final long minedTime;
        
        MinedBlockCache(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
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
        final long timestamp;
        
        BlockEventData(BlockPos pos, int eventId) {
            this.pos = pos;
            this.eventId = eventId;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isRecent() { return System.currentTimeMillis() - timestamp < 3000; }
    }
    
    // ============================================================
    // PACKET CAPTURE
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // Chunk Data - Full chunk with all block entities
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            captureChunkData(packet);
        }
        
        // Block Entity Update - NBT for spawners, chests, beacons
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            captureBlockEntity(packet);
        }
        
        // Block Update - Individual block changes
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            captureBlockUpdate(packet);
        }
        
        // Chunk Delta Update - Multiple block changes
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            captureChunkDelta(packet);
        }
        
        // Entity Spawn - New entities
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            captureEntitySpawn(packet);
        }
        
        // Entity Position - Entity movement
        if (event.packet instanceof EntityPositionS2CPacket packet) {
            captureEntityPosition(packet);
        }
        
        // Block Event - Chest animations, pistons
        if (event.packet instanceof BlockEventS2CPacket packet) {
            captureBlockEvent(packet);
        }
        
        // Block Breaking - Mining detection
        if (event.packet instanceof BlockBreakingProgressS2CPacket packet) {
            captureBlockBreak(packet);
        }
    }
    
    private void captureChunkData(ChunkDataS2CPacket packet) {
        try {
            ChunkPos chunkPos = packet.getChunkPos();
            ChatUtils.info("EntityDebug", "§7Chunk loaded: [" + chunkPos.x + ", " + chunkPos.z + "]");
        } catch (Exception ignored) {}
    }
    
    private void captureBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            NbtCompound nbt = packet.getNbt();
            
            CapturedBlockEntity captured = new CapturedBlockEntity(pos, type);
            captured.lastSeen = System.currentTimeMillis();
            
            if (nbt != null) {
                captured.hasNbt = true;
                
                if (type.contains("Spawner")) {
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
                    if (nbt.contains("Delay")) delay = nbt.getInt("Delay");
                    capturedSpawners.put(pos, new CapturedSpawner(pos, spawnsEntity, delay));
                    ChatUtils.info("EntityDebug", "§c§lSPAWNER: " + spawnsEntity + " at Y=" + pos.getY());
                }
                
                if (type.contains("Chest") || type.contains("Barrel") || type.contains("Shulker")) {
                    int itemCount = 0;
                    if (nbt.contains("Items")) {
                        itemCount = nbt.getList("Items", 10).size();
                    }
                    capturedContainers.put(pos, new CapturedContainer(pos, type, itemCount));
                    if (itemCount > 0) {
                        ChatUtils.info("EntityDebug", "§eCHEST with " + itemCount + " items at Y=" + pos.getY());
                    }
                }
                
                if (type.contains("Beacon")) {
                    int levels = 0;
                    if (nbt.contains("Levels")) levels = nbt.getInt("Levels");
                    capturedBeacons.put(pos, new CapturedBeacon(pos, levels));
                    ChatUtils.info("EntityDebug", "§bBEACON level " + levels + " at Y=" + pos.getY());
                }
            }
            
            capturedBlockEntities.put(pos, captured);
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                ChatUtils.info("EntityDebug", "§cBLOCK ENTITY: " + type + " at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
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
            if (block == Blocks.REDSTONE_WIRE) {
                int power = state.get(RedstoneWireBlock.POWER);
                if (power > 0) {
                    redstoneStates.put(pos, new RedstoneState(pos, power));
                    ChatUtils.info("EntityDebug", "§cREDSTONE power " + power + " at Y=" + pos.getY());
                }
            }
            if (block == Blocks.REPEATER && state.get(RepeaterBlock.POWERED)) {
                redstoneStates.put(pos, new RedstoneState(pos, 15));
            }
            if (block == Blocks.COMPARATOR && state.get(ComparatorBlock.POWERED)) {
                redstoneStates.put(pos, new RedstoneState(pos, 15));
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
                                BlockState state = (BlockState) entry.getValue();
                                int x = (int)(posLong >> 38);
                                int y = (int)(posLong << 52 >> 52);
                                int z = (int)(posLong << 26 >> 38);
                                BlockPos pos = new BlockPos(x, y, z);
                                
                                if (y <= MAX_DETECTION_Y) {
                                    CapturedBlock captured = capturedBlocks.get(pos);
                                    if (captured == null) {
                                        captured = new CapturedBlock(pos, state.getBlock());
                                        capturedBlocks.put(pos, captured);
                                    }
                                    captured.lastSeen = System.currentTimeMillis();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void captureEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int id = packet.getId();
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();
            
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
    
    private void captureBlockEvent(BlockEventS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            int eventId = packet.getEventId();
            
            blockEvents.put(pos, new BlockEventData(pos, eventId));
            
            if (eventId == 1) {
                ChatUtils.info("EntityDebug", "§eCHEST animation at Y=" + pos.getY());
            } else if (eventId == 0) {
                ChatUtils.info("EntityDebug", "§7PISTON moved at Y=" + pos.getY());
            }
            
        } catch (Exception ignored) {}
    }
    
    private void captureBlockBreak(BlockBreakingProgressS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            if (mc.world != null) {
                Block block = mc.world.getBlockState(pos).getBlock();
                if (block != Blocks.AIR) {
                    minedBlockCache.put(pos, new MinedBlockCache(pos, block));
                    ChatUtils.info("EntityDebug", "§6Mining: " + block.getName().getString() + " at Y=" + pos.getY());
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CLEANUP & RENDERING
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        capturedBlocks.entrySet().removeIf(entry -> !entry.getValue().isValid());
        capturedBlockEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > DATA_TIMEOUT_MS);
        capturedEntities.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        redstoneStates.entrySet().removeIf(entry -> !entry.getValue().isActive());
        minedBlockCache.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        blockEvents.entrySet().removeIf(entry -> !entry.getValue().isRecent());
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
            if (entity.isBelowY20() && entity.isRecent()) {
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
            CapturedSpawner spawner = capturedSpawners.get(pos);
            CapturedBeacon beacon = capturedBeacons.get(pos);
            CapturedEntity entity = capturedEntities.get(entry.getKey());
            
            Color fill, line;
            
            if (mined != null && mined.isRecent()) {
                fill = MINED_FILL;
                line = MINED_COLOR;
            } else if (entity != null && entity.isBelowY20()) {
                fill = ENTITY_FILL;
                line = ENTITY_COLOR;
            } else if (redstone != null && redstone.isActive()) {
                fill = REDSTONE_ACTIVE_FILL;
                line = REDSTONE_ACTIVE;
            } else if (blockEvent != null && blockEvent.isRecent()) {
                fill = BLOCK_EVENT_FILL;
                line = BLOCK_EVENT_COLOR;
            } else if (spawner != null) {
                fill = SPAWNER_FILL;
                line = SPAWNER_COLOR;
            } else if (beacon != null) {
                fill = BEACON_FILL;
                line = BEACON_COLOR;
            } else if (blockEntity != null) {
                fill = CHEST_FILL;
                line = CHEST_COLOR;
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
        capturedSpawners.clear();
        capturedContainers.clear();
        capturedBeacons.clear();
        redstoneStates.clear();
        minedBlockCache.clear();
        blockEvents.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG ACTIVATED");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=" + MAX_DETECTION_Y);
        ChatUtils.info("EntityDebug", "§7- Capturing: Spawners, Chests, Beacons, Redstone");
        ChatUtils.info("EntityDebug", "§7- Entity tracking: ACTIVE");
        ChatUtils.info("EntityDebug", "§7- Mining detection: ACTIVE");
        
        mc.player.sendMessage(Text.literal("§8[§c§lKRYPTON§8] §7Entity Debug §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        capturedBlocks.clear();
        capturedBlockEntities.clear();
        capturedEntities.clear();
        capturedSpawners.clear();
        capturedContainers.clear();
        capturedBeacons.clear();
        redstoneStates.clear();
        minedBlockCache.clear();
        blockEvents.clear();
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
        return String.format("§c%d §7blk §8| §c%d §7ent §8| §c%d §7red §8| §e%d §7mined §8| §c%d §7sp", 
            blocks, entities, redstone, mined, spawners);
    }
}
