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

/**
 * KRYPTON-STYLE ENTITY DEBUG
 * Captures ALL server data - blocks, entities, NBT, hidden data
 * Works from any Y level - detects below Y=20 even if you're at surface
 * Shows mined blocks and what was there
 */
public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lKRYPTON §8| §7Server Entity Extractor");
    }
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final int MAX_RENDER_DISTANCE = 128;
    private static final int MAX_DETECTION_Y = 20; // Detects below Y=20 regardless of player Y
    private static final long DATA_TIMEOUT_MS = 45000;
    private static final long MINED_DISPLAY_MS = 10000;
    
    // ============================================================
    // DATA STORAGE - Complete server-side capture
    // ============================================================
    
    // Blocks from server (what the server ACTUALLY has, not just rendered)
    private final Map<BlockPos, CapturedBlock> capturedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, CapturedBlockEntity> capturedBlockEntities = new ConcurrentHashMap<>();
    private final Map<Integer, CapturedEntity> capturedEntities = new ConcurrentHashMap<>();
    private final Map<Integer, CapturedEntity> capturedMinecarts = new ConcurrentHashMap<>();
    
    // NBT data storage
    private final Map<BlockPos, NbtCompound> blockNbtData = new ConcurrentHashMap<>();
    private final Map<Integer, NbtCompound> entityNbtData = new ConcurrentHashMap<>();
    
    // Mined block tracking - shows what was there before mining
    private final Map<BlockPos, MinedBlockCache> minedBlockCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> minedTimestamps = new ConcurrentHashMap<>();
    
    // Redstone state tracking
    private final Map<BlockPos, RedstoneState> redstoneStates = new ConcurrentHashMap<>();
    
    // Chunk data cache
    private final Map<ChunkPos, ChunkCache> chunkCache = new ConcurrentHashMap<>();
    
    // Render cache
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    // State
    private boolean isActive = false;
    private long lastCleanup = 0;
    private int renderFrame = 0;
    
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
    private static final Color HOPPER_COLOR = new Color(100, 100, 100, 255);
    private static final Color HOPPER_FILL = new Color(100, 100, 100, 120);
    private static final Color REDSTONE_ACTIVE = new Color(255, 50, 50, 255);
    private static final Color REDSTONE_ACTIVE_FILL = new Color(255, 50, 50, 120);
    private static final Color REDSTONE_INACTIVE = new Color(150, 50, 50, 200);
    private static final Color MINED_COLOR = new Color(255, 100, 0, 255);
    private static final Color MINED_FILL = new Color(255, 100, 0, 180);
    private static final Color PLAYER_COLOR = new Color(255, 50, 255, 255);
    private static final Color MOB_COLOR = new Color(255, 100, 100, 255);
    private static final Color ARMOR_STAND_COLOR = new Color(200, 200, 100, 255);
    
    // ============================================================
    // DATA CLASSES
    // ============================================================
    
    private static class CapturedBlock {
        final BlockPos pos;
        Block block;
        long firstSeen;
        long lastSeen;
        String source;
        
        CapturedBlock(BlockPos pos, Block block, String source) {
            this.pos = pos;
            this.block = block;
            this.firstSeen = System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
            this.source = source;
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_DETECTION_Y; }
        boolean isValid() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        
        Box getBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
        
        Color getColor() {
            if (block == Blocks.SPAWNER) return SPAWNER_COLOR;
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return CHEST_COLOR;
            if (block == Blocks.BEACON) return BEACON_COLOR;
            if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) return FURNACE_COLOR;
            if (block == Blocks.HOPPER) return HOPPER_COLOR;
            if (block.toString().contains("shulker_box")) return SHULKER_COLOR;
            return null;
        }
        
        Color getFill() {
            if (block == Blocks.SPAWNER) return SPAWNER_FILL;
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return CHEST_FILL;
            if (block == Blocks.BEACON) return BEACON_FILL;
            if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) return FURNACE_FILL;
            if (block == Blocks.HOPPER) return HOPPER_FILL;
            if (block.toString().contains("shulker_box")) return SHULKER_FILL;
            return null;
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
        
        Color getColor() {
            if (type.contains("Spawner")) return SPAWNER_COLOR;
            if (type.contains("Chest")) return CHEST_COLOR;
            if (type.contains("Beacon")) return BEACON_COLOR;
            if (type.contains("Furnace")) return FURNACE_COLOR;
            if (type.contains("Hopper")) return HOPPER_COLOR;
            return SHULKER_COLOR;
        }
        
        Color getFill() {
            if (type.contains("Spawner")) return SPAWNER_FILL;
            if (type.contains("Chest")) return CHEST_FILL;
            if (type.contains("Beacon")) return BEACON_FILL;
            if (type.contains("Furnace")) return FURNACE_FILL;
            if (type.contains("Hopper")) return HOPPER_FILL;
            return SHULKER_FILL;
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
        
        Color getColor() {
            if (type == EntityType.PLAYER) return PLAYER_COLOR;
            if (type == EntityType.ARMOR_STAND) return ARMOR_STAND_COLOR;
            if (type == EntityType.CHEST_MINECART || type == EntityType.HOPPER_MINECART) return CHEST_COLOR;
            return MOB_COLOR;
        }
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
    
    private static class RedstoneState {
        final BlockPos pos;
        int power;
        long lastUpdate;
        boolean isActive;
        
        RedstoneState(BlockPos pos, int power) {
            this.pos = pos;
            this.power = power;
            this.lastUpdate = System.currentTimeMillis();
            this.isActive = power > 0;
        }
        
        boolean isRecent() { return System.currentTimeMillis() - lastUpdate < 5000; }
    }
    
    private static class ChunkCache {
        final ChunkPos pos;
        final long timestamp;
        int blockCount;
        
        ChunkCache(ChunkPos pos) {
            this.pos = pos;
            this.timestamp = System.currentTimeMillis();
            this.blockCount = 0;
        }
    }
    
    // ============================================================
    // PACKET CAPTURE - Captures EVERYTHING server sends
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // BLOCK UPDATE PACKETS - Individual block changes
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            captureBlockUpdate(packet);
        }
        
        // CHUNK DELTA UPDATE - Multiple block changes at once
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            captureChunkDelta(packet);
        }
        
        // BLOCK ENTITY UPDATE - Chests, spawners, furnaces with NBT
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            captureBlockEntity(packet);
        }
        
        // ENTITY SPAWN - New entities appearing
        if (event.packet instanceof EntitySpawnS2CPacket packet) {
            captureEntitySpawn(packet);
        }
        
        // ENTITY TRACKING - Entity movement/updates
        if (event.packet instanceof EntityPositionS2CPacket packet) {
            captureEntityPosition(packet);
        }
        
        // CHUNK DATA - Full chunk loads
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            captureChunkData(packet);
        }
        
        // BLOCK BREAK ANIMATION - Someone mining
        if (event.packet instanceof BlockBreakingProgressS2CPacket packet) {
            captureBlockBreak(packet);
        }
        
        // EFFECT PACKET - Particles, sounds (indicates activity)
        if (event.packet instanceof EffectS2CPacket packet) {
            captureEffect(packet);
        }
    }
    
    private void captureBlockUpdate(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            BlockState state = packet.getState();
            Block block = state.getBlock();
            
            // Capture for ANY Y level, but we'll filter rendering later
            CapturedBlock captured = capturedBlocks.get(pos);
            if (captured == null) {
                captured = new CapturedBlock(pos, block, "block_update");
                capturedBlocks.put(pos, captured);
                
                // Log important discoveries
                if (pos.getY() <= MAX_DETECTION_Y && isImportantBlock(block)) {
                    ChatUtils.info("EntityDebug", "§aFound " + block.getName().getString() + " at Y=" + pos.getY());
                }
            }
            captured.block = block;
            captured.lastSeen = System.currentTimeMillis();
            
            // Capture redstone state
            if (block instanceof RedstoneWireBlock) {
                int power = state.get(RedstoneWireBlock.POWER);
                if (power > 0) {
                    redstoneStates.put(pos, new RedstoneState(pos, power));
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
                                    captured = new CapturedBlock(pos, state.getBlock(), "chunk_delta");
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
    
    private void captureBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            
            CapturedBlockEntity captured = capturedBlockEntities.get(pos);
            if (captured == null) {
                captured = new CapturedBlockEntity(pos, type);
                capturedBlockEntities.put(pos, captured);
                
                // Get NBT data
                if (packet.getNbt() != null) {
                    captured.hasNbt = true;
                    blockNbtData.put(pos, packet.getNbt());
                    
                    // Extract useful NBT info
                    NbtCompound nbt = packet.getNbt();
                    if (nbt.contains("SpawnData")) {
                        ChatUtils.info("EntityDebug", "§c§lSPAWNER with data at Y=" + pos.getY());
                    }
                    if (nbt.contains("Items")) {
                        int itemCount = nbt.getList("Items", 10).size();
                        ChatUtils.info("EntityDebug", "§eChest with " + itemCount + " items at Y=" + pos.getY());
                    }
                    if (nbt.contains("CustomName")) {
                        ChatUtils.info("EntityDebug", "§bNamed container at Y=" + pos.getY());
                    }
                }
                
                if (pos.getY() <= MAX_DETECTION_Y) {
                    ChatUtils.info("EntityDebug", "§cBLOCK ENTITY: " + type + " at Y=" + pos.getY());
                }
            }
            captured.lastSeen = System.currentTimeMillis();
            
        } catch (Exception ignored) {}
    }
    
    private void captureEntitySpawn(EntitySpawnS2CPacket packet) {
        try {
            int id = getEntityId(packet);
            double x = getEntityX(packet);
            double y = getEntityY(packet);
            double z = getEntityZ(packet);
            EntityType<?> type = getEntityType(packet);
            
            CapturedEntity captured = new CapturedEntity(id, type, x, y, z);
            capturedEntities.put(id, captured);
            
            if (y <= MAX_DETECTION_Y) {
                ChatUtils.info("EntityDebug", "§dEntity spawned: " + type.getName().getString() + " at Y=" + (int)y);
            }
            
        } catch (Exception ignored) {}
    }
    
    private void captureEntityPosition(EntityPositionS2CPacket packet) {
        try {
            int id = packet.getId();
            CapturedEntity entity = capturedEntities.get(id);
            if (entity != null) {
                double x = packet.getX();
                double y = packet.getY();
                double z = packet.getZ();
                entity.x = x;
                entity.y = y;
                entity.z = z;
                entity.lastSeen = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
    }
    
    private void captureChunkData(ChunkDataS2CPacket packet) {
        try {
            ChunkPos pos = packet.getChunkPos();
            ChunkCache cache = new ChunkCache(pos);
            chunkCache.put(pos, cache);
        } catch (Exception ignored) {}
    }
    
    private void captureBlockBreak(BlockBreakingProgressS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            // Someone is mining here - capture what block was there
            Block block = mc.world.getBlockState(pos).getBlock();
            if (block != Blocks.AIR) {
                minedBlockCache.put(pos, new MinedBlockCache(pos, block));
                minedTimestamps.put(pos, System.currentTimeMillis());
            }
        } catch (Exception ignored) {}
    }
    
    private void captureEffect(EffectS2CPacket packet) {
        try {
            // Effects indicate activity - could be redstone, explosions, etc.
            int effectId = packet.getEffectId();
            BlockPos pos = packet.getPos();
            if (effectId == 1000 || effectId == 1001) { // Click sound, redstone click
                redstoneStates.put(pos, new RedstoneState(pos, 1));
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // HELPER METHODS FOR PACKET EXTRACTION
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
    
    private EntityType<?> getEntityType(EntitySpawnS2CPacket packet) {
        // Default fallback
        return EntityType.PIG;
    }
    
    private boolean isImportantBlock(Block block) {
        return block == Blocks.SPAWNER ||
               block == Blocks.CHEST ||
               block == Blocks.TRAPPED_CHEST ||
               block == Blocks.BEACON ||
               block == Blocks.FURNACE ||
               block == Blocks.BLAST_FURNACE ||
               block == Blocks.HOPPER ||
               block.toString().contains("shulker_box");
    }
    
    // ============================================================
    // ACTIVE WORLD SCAN - Forces server to send data
    // ============================================================
    
    private void activeWorldScan() {
        if (mc.world == null || mc.player == null) return;
        
        // Scan chunks in render distance
        int radius = 16;
        BlockPos playerPos = mc.player.getBlockPos();
        ChunkPos centerChunk = new ChunkPos(playerPos);
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                if (!chunkCache.containsKey(chunkPos)) {
                    // New chunk - request data by looking at it
                    chunkCache.put(chunkPos, new ChunkCache(chunkPos));
                }
            }
        }
        
        // Scan for entities in world (what the client already knows)
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            
            CapturedEntity captured = capturedEntities.get(entity.getId());
            if (captured == null) {
                captured = new CapturedEntity(entity.getId(), entity.getType(), entity.getX(), entity.getY(), entity.getZ());
                capturedEntities.put(entity.getId(), captured);
            }
            captured.x = entity.getX();
            captured.y = entity.getY();
            captured.z = entity.getZ();
            captured.lastSeen = System.currentTimeMillis();
            if (entity.hasCustomName()) {
                captured.customName = entity.getCustomName().getString();
            }
        }
    }
    
    // ============================================================
    // CLEANUP OLD DATA
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        capturedBlocks.entrySet().removeIf(entry -> !entry.getValue().isValid());
        capturedBlockEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > DATA_TIMEOUT_MS);
        capturedEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > DATA_TIMEOUT_MS);
        redstoneStates.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        minedBlockCache.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        chunkCache.entrySet().removeIf(entry -> now - entry.getValue().timestamp > 60000);
    }
    
    // ============================================================
    // UPDATE RENDER CACHE
    // ============================================================
    
    private void updateRenderCache() {
        renderCache.clear();
        
        // Add captured blocks below Y=20
        for (CapturedBlock block : capturedBlocks.values()) {
            if (block.isBelowY20()) {
                renderCache.put(block.pos.hashCode(), block.getBox());
            }
        }
        
        // Add captured block entities below Y=20
        for (CapturedBlockEntity entity : capturedBlockEntities.values()) {
            if (entity.isBelowY20()) {
                renderCache.put(entity.pos.hashCode(), entity.getBox());
            }
        }
        
        // Add captured entities below Y=20
        for (CapturedEntity entity : capturedEntities.values()) {
            if (entity.isBelowY20()) {
                renderCache.put(entity.id, entity.getBox());
            }
        }
        
        // Add mined blocks (shows what was there)
        for (MinedBlockCache mined : minedBlockCache.values()) {
            renderCache.put(mined.pos.hashCode() + 1000000, mined.getBox());
        }
    }
    
    // ============================================================
    // TICK HANDLER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        activeWorldScan();
        cleanup();
        updateRenderCache();
    }
    
    // ============================================================
    // RENDERING
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        
        renderFrame++;
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        
        for (Map.Entry<Integer, Box> entry : renderCache.entrySet()) {
            Box box = entry.getValue();
            
            // Only render below Y=20
            if (box.minY > MAX_DETECTION_Y) continue;
            
            double dx = box.getCenter().x - px;
            double dz = box.getCenter().z - pz;
            if (dx * dx + dz * dz > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue;
            
            BlockPos pos = new BlockPos((int)box.minX, (int)box.minY, (int)box.minZ);
            
            // Determine what we're rendering
            CapturedBlock block = capturedBlocks.get(pos);
            CapturedBlockEntity blockEntity = capturedBlockEntities.get(pos);
            RedstoneState redstone = redstoneStates.get(pos);
            MinedBlockCache mined = minedBlockCache.get(pos);
            
            Color fill, line;
            
            if (mined != null && mined.isRecent()) {
                // Show what was just mined
                fill = MINED_FILL;
                line = MINED_COLOR;
            } else if (redstone != null && redstone.isRecent() && redstone.isActive) {
                fill = REDSTONE_ACTIVE_FILL;
                line = REDSTONE_ACTIVE;
            } else if (blockEntity != null && blockEntity.isBelowY20()) {
                fill = blockEntity.getFill();
                line = blockEntity.getColor();
            } else if (block != null && block.isBelowY20()) {
                Color blockColor = block.getColor();
                Color blockFill = block.getFill();
                if (blockColor != null) {
                    fill = blockFill;
                    line = blockColor;
                } else {
                    continue;
                }
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
        capturedMinecarts.clear();
        blockNbtData.clear();
        entityNbtData.clear();
        minedBlockCache.clear();
        redstoneStates.clear();
        chunkCache.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG ACTIVATED");
        ChatUtils.info("EntityDebug", "§7- Capturing ALL server data");
        ChatUtils.info("EntityDebug", "§7- NBT Data: ENABLED");
        ChatUtils.info("EntityDebug", "§7- Detection range: ALL loaded chunks");
        ChatUtils.info("EntityDebug", "§7- Mining detector: ACTIVE");
        
        mc.player.sendMessage(Text.literal("§8[§c§lKRYPTON§8] §7Entity Debug §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        capturedBlocks.clear();
        capturedBlockEntities.clear();
        capturedEntities.clear();
        capturedMinecarts.clear();
        blockNbtData.clear();
        entityNbtData.clear();
        minedBlockCache.clear();
        redstoneStates.clear();
        chunkCache.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int blocks = (int) capturedBlocks.values().stream().filter(b -> b.isBelowY20()).count();
        int entities = (int) capturedBlockEntities.values().stream().filter(e -> e.isBelowY20()).count();
        int redstone = (int) redstoneStates.values().stream().filter(r -> r.isRecent() && r.isActive).count();
        int mined = minedBlockCache.size();
        return String.format("§c%d §7blocks §8| §c%d §7entities §8| §c%d §7redstone §8| §e%d §7mined", blocks, entities, redstone, mined);
    }
}
