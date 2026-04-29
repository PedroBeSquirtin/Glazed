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
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
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
        super(GlazedAddon.esp, "entity-debug", "§c§lDONUT BYPASS §7- Instant Server Data Extractor");
    }
    
    // Constants
    private static final int RENDER_DISTANCE = 80;
    private static final int MAX_RENDER_Y = 20;
    private static final int SCAN_RADIUS = 64;
    
    // Data structures - instant update
    private final Map<BlockPos, DetectedBlock> detectedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, DetectedBlockEntity> detectedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, RedstoneData> redstoneData = new ConcurrentHashMap<>();
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    // State
    private boolean isActive = false;
    private long lastRenderUpdate = 0;
    
    // Colors
    private static final Color SPAWNER_COLOR = new Color(255, 50, 50, 255);
    private static final Color SPAWNER_FILL = new Color(255, 50, 50, 100);
    private static final Color CHEST_COLOR = new Color(255, 200, 50, 255);
    private static final Color CHEST_FILL = new Color(255, 200, 50, 100);
    private static final Color REDSTONE_COLOR = new Color(255, 100, 100, 255);
    private static final Color REDSTONE_FILL = new Color(255, 100, 100, 100);
    private static final Color BEACON_COLOR = new Color(100, 200, 255, 255);
    private static final Color BEACON_FILL = new Color(100, 200, 255, 100);
    private static final Color FURNACE_COLOR = new Color(150, 150, 150, 255);
    private static final Color FURNACE_FILL = new Color(150, 150, 150, 100);
    private static final Color DEFAULT_COLOR = new Color(100, 150, 255, 255);
    private static final Color DEFAULT_FILL = new Color(100, 150, 255, 100);
    
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
        
        boolean isBelowY20() { return pos.getY() <= MAX_RENDER_Y; }
        
        Color getColor() {
            if (block == Blocks.SPAWNER) return SPAWNER_COLOR;
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return CHEST_COLOR;
            if (block == Blocks.BEACON) return BEACON_COLOR;
            if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE) return FURNACE_COLOR;
            return DEFAULT_COLOR;
        }
        
        Color getFill() {
            if (block == Blocks.SPAWNER) return SPAWNER_FILL;
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return CHEST_FILL;
            if (block == Blocks.BEACON) return BEACON_FILL;
            if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE) return FURNACE_FILL;
            return DEFAULT_FILL;
        }
        
        Box getBoundingBox() {
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
        
        boolean isBelowY20() { return pos.getY() <= MAX_RENDER_Y; }
        
        Color getColor() {
            if (type.contains("Spawner")) return SPAWNER_COLOR;
            if (type.contains("Chest")) return CHEST_COLOR;
            if (type.contains("Beacon")) return BEACON_COLOR;
            if (type.contains("Furnace")) return FURNACE_COLOR;
            return DEFAULT_COLOR;
        }
        
        Box getBoundingBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    private static class RedstoneData {
        final BlockPos pos;
        int power;
        long lastUpdate;
        
        RedstoneData(BlockPos pos, int power) {
            this.pos = pos;
            this.power = power;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        boolean isActive() {
            return System.currentTimeMillis() - lastUpdate < 3000 && power > 0;
        }
    }
    
    // ============================================================
    // NATURAL GENERATION DETECTION
    // ============================================================
    
    private static boolean isPlayerPlaced(Block block, BlockPos pos) {
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return true;
        if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE) return true;
        if (block == Blocks.SMOKER) return true;
        if (block == Blocks.HOPPER) return true;
        if (block == Blocks.DROPPER || block == Blocks.DISPENSER) return true;
        if (block == Blocks.OBSERVER) return true;
        if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) return true;
        if (block == Blocks.REDSTONE_WIRE) return true;
        if (block == Blocks.REDSTONE_TORCH) return true;
        if (block == Blocks.LEVER) return true;
        if (block instanceof ButtonBlock) return true;
        if (block == Blocks.NOTE_BLOCK) return true;
        if (block == Blocks.JUKEBOX) return true;
        if (block == Blocks.BEACON) return true;
        if (block == Blocks.ANVIL) return true;
        if (block == Blocks.ENCHANTING_TABLE) return true;
        if (block == Blocks.GRINDSTONE) return true;
        if (block == Blocks.SPAWNER) return true;
        if (block.toString().contains("shulker_box")) return true;
        return false;
    }
    
    // ============================================================
    // INSTANT PACKET CAPTURE - NO DELAY
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // INSTANT processing - no delay
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            analyzeBlockUpdate(packet);
        }
        
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            analyzeChunkDelta(packet);
        }
        
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            analyzeBlockEntity(packet);
        }
        
        // Update render cache instantly
        updateRenderCache();
    }
    
    private void analyzeBlockUpdate(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            BlockState state = packet.getState();
            Block block = state.getBlock();
            
            if (pos.getY() <= MAX_RENDER_Y) {
                DetectedBlock detected = detectedBlocks.get(pos);
                if (detected == null) {
                    detected = new DetectedBlock(pos, block);
                    detectedBlocks.put(pos, detected);
                    
                    if (isPlayerPlaced(block, pos)) {
                        ChatUtils.info("EntityDebug", "§eFound " + block.getName().getString() + " at Y=" + pos.getY());
                    }
                }
                detected.block = block;
                detected.lastSeen = System.currentTimeMillis();
                
                // Track redstone INSTANTLY
                if (block instanceof RedstoneWireBlock) {
                    int power = state.get(RedstoneWireBlock.POWER);
                    if (power > 0) {
                        redstoneData.put(pos, new RedstoneData(pos, power));
                    }
                }
                if (block instanceof RepeaterBlock && state.get(RepeaterBlock.POWERED)) {
                    redstoneData.put(pos, new RedstoneData(pos, 15));
                }
                if (block instanceof ComparatorBlock && state.get(ComparatorBlock.POWERED)) {
                    redstoneData.put(pos, new RedstoneData(pos, 15));
                }
                if (block instanceof LeverBlock && state.get(LeverBlock.POWERED)) {
                    redstoneData.put(pos, new RedstoneData(pos, 15));
                }
                if (block instanceof ButtonBlock && state.get(ButtonBlock.POWERED)) {
                    redstoneData.put(pos, new RedstoneData(pos, 15));
                }
                if (block == Blocks.REDSTONE_TORCH && state.get(RedstoneTorchBlock.LIT)) {
                    redstoneData.put(pos, new RedstoneData(pos, 15));
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void analyzeChunkDelta(ChunkDeltaUpdateS2CPacket packet) {
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
                                
                                if (y <= MAX_RENDER_Y) {
                                    DetectedBlock detected = detectedBlocks.get(pos);
                                    if (detected == null) {
                                        detected = new DetectedBlock(pos, state.getBlock());
                                        detectedBlocks.put(pos, detected);
                                    }
                                    detected.lastSeen = System.currentTimeMillis();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void analyzeBlockEntity(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            if (pos.getY() <= MAX_RENDER_Y) {
                String type = packet.getBlockEntityType().toString();
                DetectedBlockEntity detected = detectedEntities.get(pos);
                if (detected == null) {
                    detected = new DetectedBlockEntity(pos, type);
                    detectedEntities.put(pos, detected);
                    ChatUtils.info("EntityDebug", "§cFound " + type + " at Y=" + pos.getY());
                }
                detected.lastSeen = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // INSTANT RENDER CACHE UPDATE
    // ============================================================
    
    private void updateRenderCache() {
        renderCache.clear();
        
        for (DetectedBlock block : detectedBlocks.values()) {
            if (block.isBelowY20()) {
                renderCache.put(block.pos.hashCode(), block.getBoundingBox());
            }
        }
        
        for (DetectedBlockEntity entity : detectedEntities.values()) {
            if (entity.isBelowY20()) {
                renderCache.put(entity.pos.hashCode(), entity.getBoundingBox());
            }
        }
    }
    
    // ============================================================
    // PASSIVE WORLD SCANNING - NO MOVEMENT PACKETS
    // ============================================================
    
    private void passiveWorldScan() {
        if (mc.world == null || mc.player == null) return;
        
        BlockPos p = mc.player.getBlockPos();
        
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -20; dy <= MAX_RENDER_Y - p.getY(); dy++) {
                    BlockPos pos = p.add(dx, dy, dz);
                    if (pos.getY() > MAX_RENDER_Y || pos.getY() < mc.world.getBottomY()) continue;
                    
                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    
                    if (block == Blocks.SPAWNER || 
                        block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST ||
                        block == Blocks.BEACON ||
                        block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE ||
                        block == Blocks.REPEATER || block == Blocks.COMPARATOR ||
                        block == Blocks.REDSTONE_WIRE) {
                        
                        if (!detectedBlocks.containsKey(pos)) {
                            DetectedBlock detected = new DetectedBlock(pos, block);
                            detectedBlocks.put(pos, detected);
                            if (block == Blocks.SPAWNER) {
                                ChatUtils.info("EntityDebug", "§c§lSPAWNER at Y=" + pos.getY());
                            }
                        }
                    }
                    
                    // Redstone power detection
                    if (block instanceof RedstoneWireBlock) {
                        int power = state.get(RedstoneWireBlock.POWER);
                        if (power > 0) {
                            redstoneData.put(pos, new RedstoneData(pos, power));
                        }
                    }
                    if (block instanceof RepeaterBlock && state.get(RepeaterBlock.POWERED)) {
                        redstoneData.put(pos, new RedstoneData(pos, 15));
                    }
                    if (block instanceof ComparatorBlock && state.get(ComparatorBlock.POWERED)) {
                        redstoneData.put(pos, new RedstoneData(pos, 15));
                    }
                }
            }
        }
    }
    
    // ============================================================
    // CLEANUP
    // ============================================================
    
    private void cleanupOldData() {
        long now = System.currentTimeMillis();
        detectedBlocks.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > 30000);
        detectedEntities.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > 30000);
        redstoneData.entrySet().removeIf(entry -> !entry.getValue().isActive());
    }
    
    // ============================================================
    // TICK HANDLER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        // Only passive scanning - NO movement packets to avoid kicks
        passiveWorldScan();
        cleanupOldData();
        
        // Update render cache every tick for INSTANT rendering
        updateRenderCache();
    }
    
    // ============================================================
    // INSTANT RENDERING - NO DELAY
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive || mc.player == null) return;
        
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        
        for (Map.Entry<Integer, Box> entry : renderCache.entrySet()) {
            Box box = entry.getValue();
            
            if (box.minY > MAX_RENDER_Y) continue;
            
            double dx = box.getCenter().x - px;
            double dz = box.getCenter().z - pz;
            if (dx * dx + dz * dz > RENDER_DISTANCE * RENDER_DISTANCE) continue;
            
            BlockPos pos = new BlockPos((int)box.minX, (int)box.minY, (int)box.minZ);
            
            DetectedBlock block = detectedBlocks.get(pos);
            DetectedBlockEntity entity = detectedEntities.get(pos);
            RedstoneData redstone = redstoneData.get(pos);
            
            Color fill, line;
            
            if (redstone != null && redstone.isActive()) {
                fill = REDSTONE_FILL;
                line = REDSTONE_COLOR;
            } else if (block != null) {
                fill = block.getFill();
                line = block.getColor();
            } else if (entity != null) {
                fill = entity.getColor();
                line = entity.getColor();
            } else {
                fill = DEFAULT_FILL;
                line = DEFAULT_COLOR;
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
        detectedEntities.clear();
        redstoneData.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lINSTANT SERVER EXTRACTOR ACTIVATED");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=20");
        ChatUtils.info("EntityDebug", "§7- Instant ESP (no delay)");
        ChatUtils.info("EntityDebug", "§7- NO movement packets (no kicks)");
        
        mc.player.sendMessage(Text.literal("§8[§c§lED§8] §7Instant Entity Debug §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        detectedBlocks.clear();
        detectedEntities.clear();
        redstoneData.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int blocks = detectedBlocks.size();
        int entities = detectedEntities.size();
        int redstone = (int) redstoneData.values().stream().filter(RedstoneData::isActive).count();
        return String.format("§c%d §7blocks §8| §c%d §7entities §8| §c%d §7redstone", blocks, entities, redstone);
    }
}
