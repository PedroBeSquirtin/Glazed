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
import net.minecraft.nbt.NbtCompound;
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
    
    private final Map<BlockPos, DetectedBlock> detectedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, DetectedBlockEntity> detectedBlockEntities = new ConcurrentHashMap<>();
    private final Map<Integer, DetectedEntity> detectedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> redstonePower = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> redstoneTime = new ConcurrentHashMap<>();
    private final Map<BlockPos, MinedBlock> minedBlocks = new ConcurrentHashMap<>();
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
    private static final Color ENTITY_COLOR = new Color(255, 100, 255, 255);
    private static final Color ENTITY_FILL = new Color(255, 100, 255, 120);
    
    // ============================================================
    // DATA CLASSES
    // ============================================================
    
    private static class DetectedBlock {
        final BlockPos pos;
        Block block;
        long lastSeen;
        
        DetectedBlock(BlockPos pos, Block block) {
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
    
    private static class DetectedBlockEntity {
        final BlockPos pos;
        String type;
        long lastSeen;
        
        DetectedBlockEntity(BlockPos pos, String type) {
            this.pos = pos;
            this.type = type;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_DETECTION_Y; }
        
        Box getBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    private static class DetectedEntity {
        final int id;
        double x, y, z;
        long lastSeen;
        
        DetectedEntity(int id, double x, double y, double z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return y <= MAX_DETECTION_Y; }
        boolean isRecent() { return System.currentTimeMillis() - lastSeen < DATA_TIMEOUT_MS; }
        
        Box getBox() {
            return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        }
    }
    
    private static class MinedBlock {
        final BlockPos pos;
        final Block block;
        final long minedTime;
        
        MinedBlock(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
            this.minedTime = System.currentTimeMillis();
        }
        
        boolean isRecent() { return System.currentTimeMillis() - minedTime < MINED_DISPLAY_MS; }
        
        Box getBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    // ============================================================
    // PACKET CAPTURE - Using reflection for compatibility
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
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
        
        // Block Breaking - Mining detection
        if (event.packet instanceof BlockBreakingProgressS2CPacket packet) {
            captureBlockBreak(packet);
        }
    }
    
    private void captureBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            String type = packet.getBlockEntityType().toString();
            
            DetectedBlockEntity detected = new DetectedBlockEntity(pos, type);
            detectedBlockEntities.put(pos, detected);
            
            if (pos.getY() <= MAX_DETECTION_Y) {
                if (type.contains("Spawner")) {
                    ChatUtils.info("EntityDebug", "§c§lSPAWNER at Y=" + pos.getY() + " [" + pos.getX() + ", " + pos.getZ() + "]");
                } else if (type.contains("Chest")) {
                    ChatUtils.info("EntityDebug", "§eCHEST at Y=" + pos.getY() + " [" + pos.getX() + ", " + pos.getZ() + "]");
                } else if (type.contains("Beacon")) {
                    ChatUtils.info("EntityDebug", "§bBEACON at Y=" + pos.getY() + " [" + pos.getX() + ", " + pos.getZ() + "]");
                } else {
                    ChatUtils.info("EntityDebug", "§cBLOCK ENTITY: " + type + " at Y=" + pos.getY());
                }
            }
            
        } catch (Exception ignored) {}
    }
    
    private void captureBlockUpdate(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            
            // Try to get block state via reflection
            Block block = Blocks.AIR;
            try {
                for (Field f : packet.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("BlockState")) {
                        f.setAccessible(true);
                        Object state = f.get(packet);
                        for (Field f2 : state.getClass().getDeclaredFields()) {
                            if (f2.getType() == Block.class) {
                                f2.setAccessible(true);
                                block = (Block) f2.get(state);
                                break;
                            }
                        }
                        break;
                    }
                }
            } catch (Exception e) {}
            
            if (block != Blocks.AIR) {
                DetectedBlock detected = detectedBlocks.get(pos);
                if (detected == null) {
                    detected = new DetectedBlock(pos, block);
                    detectedBlocks.put(pos, detected);
                }
                detected.block = block;
                detected.lastSeen = System.currentTimeMillis();
                
                // Redstone detection
                if (block == Blocks.REDSTONE_WIRE && pos.getY() <= MAX_DETECTION_Y) {
                    redstonePower.put(pos, 15);
                    redstoneTime.put(pos, System.currentTimeMillis());
                    ChatUtils.info("EntityDebug", "§cREDSTONE active at Y=" + pos.getY());
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
                                    DetectedBlock detected = detectedBlocks.get(pos);
                                    if (detected != null) {
                                        detected.lastSeen = System.currentTimeMillis();
                                    }
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
            int id = -1;
            double x = 0, y = 0, z = 0;
            
            for (Field f : packet.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("id") || f.getName().equals("field_1217")) {
                    id = f.getInt(packet);
                }
                if (f.getType() == double.class) {
                    double val = f.getDouble(packet);
                    if (x == 0) x = val;
                    else if (y == 0) y = val;
                    else if (z == 0) z = val;
                }
            }
            
            if (id != -1 && y <= MAX_DETECTION_Y) {
                detectedEntities.put(id, new DetectedEntity(id, x, y, z));
                ChatUtils.info("EntityDebug", "§dEntity spawned at Y=" + (int)y);
            }
            
        } catch (Exception ignored) {}
    }
    
    private void captureBlockBreak(BlockBreakingProgressS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            if (mc.world != null) {
                Block block = mc.world.getBlockState(pos).getBlock();
                if (block != Blocks.AIR && pos.getY() <= MAX_DETECTION_Y) {
                    minedBlocks.put(pos, new MinedBlock(pos, block));
                    ChatUtils.info("EntityDebug", "§6Mined: " + block.getName().getString() + " at Y=" + pos.getY());
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // CLEANUP
    // ============================================================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        detectedBlocks.entrySet().removeIf(entry -> !entry.getValue().isValid());
        detectedBlockEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > DATA_TIMEOUT_MS);
        detectedEntities.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        redstoneTime.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
        minedBlocks.entrySet().removeIf(entry -> !entry.getValue().isRecent());
    }
    
    private void updateRenderCache() {
        renderCache.clear();
        
        for (DetectedBlock block : detectedBlocks.values()) {
            if (block.isBelowY20()) {
                renderCache.put(block.pos.hashCode(), block.getBox());
            }
        }
        
        for (DetectedBlockEntity entity : detectedBlockEntities.values()) {
            if (entity.isBelowY20()) {
                renderCache.put(entity.pos.hashCode(), entity.getBox());
            }
        }
        
        for (DetectedEntity entity : detectedEntities.values()) {
            if (entity.isBelowY20() && entity.isRecent()) {
                renderCache.put(entity.id, entity.getBox());
            }
        }
        
        for (MinedBlock mined : minedBlocks.values()) {
            renderCache.put(mined.pos.hashCode() + 1000000, mined.getBox());
        }
        
        for (Map.Entry<BlockPos, Integer> redstone : redstonePower.entrySet()) {
            if (redstoneTime.containsKey(redstone.getKey()) && 
                System.currentTimeMillis() - redstoneTime.get(redstone.getKey()) < 5000) {
                BlockPos pos = redstone.getKey();
                Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                renderCache.put(pos.hashCode() + 2000000, box);
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
            
            DetectedBlock block = detectedBlocks.get(pos);
            DetectedBlockEntity blockEntity = detectedBlockEntities.get(pos);
            MinedBlock mined = minedBlocks.get(pos);
            boolean isRedstone = redstonePower.containsKey(pos) && redstoneTime.containsKey(pos) && 
                                 System.currentTimeMillis() - redstoneTime.get(pos) < 5000;
            DetectedEntity entity = detectedEntities.get(entry.getKey());
            
            Color fill, line;
            
            if (mined != null && mined.isRecent()) {
                fill = MINED_FILL;
                line = MINED_COLOR;
            } else if (entity != null && entity.isBelowY20()) {
                fill = ENTITY_FILL;
                line = ENTITY_COLOR;
            } else if (isRedstone) {
                fill = REDSTONE_ACTIVE_FILL;
                line = REDSTONE_ACTIVE;
            } else if (blockEntity != null && blockEntity.type.contains("Spawner")) {
                fill = SPAWNER_FILL;
                line = SPAWNER_COLOR;
            } else if (blockEntity != null && blockEntity.type.contains("Beacon")) {
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
        
        detectedBlocks.clear();
        detectedBlockEntities.clear();
        detectedEntities.clear();
        redstonePower.clear();
        redstoneTime.clear();
        minedBlocks.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lKRYPTON ENTITY DEBUG ACTIVATED");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=" + MAX_DETECTION_Y);
        ChatUtils.info("EntityDebug", "§7- Detecting: Spawners, Chests, Beacons");
        ChatUtils.info("EntityDebug", "§7- Redstone tracking: ACTIVE");
        ChatUtils.info("EntityDebug", "§7- Entity tracking: ACTIVE");
        ChatUtils.info("EntityDebug", "§7- Mining detection: ACTIVE");
        
        mc.player.sendMessage(Text.literal("§8[§c§lKRYPTON§8] §7Entity Debug §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        detectedBlocks.clear();
        detectedBlockEntities.clear();
        detectedEntities.clear();
        redstonePower.clear();
        redstoneTime.clear();
        minedBlocks.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int blocks = (int) detectedBlocks.values().stream().filter(b -> b.isBelowY20()).count();
        int entities = (int) detectedBlockEntities.values().stream().filter(e -> e.isBelowY20()).count();
        int redstone = redstonePower.size();
        int mined = minedBlocks.size();
        return String.format("§c%d §7blk §8| §c%d §7ent §8| §c%d §7red §8| §e%d §7mined", 
            blocks, entities, redstone, mined);
    }
}
