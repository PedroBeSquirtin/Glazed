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
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.KeepAliveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DonutSMP EntityDebug - SERVER DATA EXTRACTOR
 * Forces server to send hidden block data below Y=20 including redstone activity
 */
public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lDONUT BYPASS §7- Extract hidden server data below Y=20");
    }
    
    // Constants
    private static final int RENDER_DISTANCE = 64;
    private static final int MAX_RENDER_Y = 20;
    private static final int SCAN_RADIUS = 48;
    
    // Data structures for leaked server data
    private final Map<BlockPos, LeakedBlockData> leakedBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, LeakedBlockEntityData> leakedBlockEntities = new ConcurrentHashMap<>();
    private final Map<ChunkPos, ChunkLeakData> leakedChunkData = new ConcurrentHashMap<>();
    private final Map<BlockPos, RedstoneActivity> redstoneActivity = new ConcurrentHashMap<>();
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    // State
    private boolean isActive = false;
    private int bypassTick = 0;
    private int lastSequenceId = 0;
    private long lastPacketAnalysis = 0;
    
    // Colors
    private static final Color ESP_COLOR = new Color(100, 150, 255, 200);
    private static final Color ESP_FILL = new Color(100, 150, 255, 80);
    private static final Color REDSTONE_COLOR = new Color(255, 100, 100, 200);
    private static final Color REDSTONE_FILL = new Color(255, 100, 100, 80);
    
    // ============================================================
    // DATA STRUCTURES FOR SERVER LEAKS
    // ============================================================
    
    private static class LeakedBlockData {
        final BlockPos pos;
        Block block;
        BlockState state;
        int lightLevel;
        long lastSeen;
        String leakMethod;
        boolean isRedstonePowered;
        int redstonePower;
        
        LeakedBlockData(BlockPos pos, Block block, String method) {
            this.pos = pos;
            this.block = block;
            this.lastSeen = System.currentTimeMillis();
            this.leakMethod = method;
            this.isRedstonePowered = false;
            this.redstonePower = 0;
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_RENDER_Y; }
        boolean isRecent() { return System.currentTimeMillis() - lastSeen < 30000; }
        
        Box getBoundingBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
        
        Color getColor() {
            if (isRedstonePowered && redstonePower > 0) {
                return new Color(255, 100, 100, 200);
            }
            if (block == Blocks.SPAWNER) return new Color(200, 0, 0, 200);
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return new Color(255, 200, 0, 180);
            if (block == Blocks.BEACON) return new Color(0, 200, 255, 200);
            if (block == Blocks.REDSTONE_WIRE) return new Color(255, 50, 50, 180);
            if (block == Blocks.REPEATER || block == Blocks.COMPARATOR) return new Color(255, 100, 50, 180);
            if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) return new Color(150, 150, 255, 180);
            if (block == Blocks.OBSERVER) return new Color(100, 100, 200, 180);
            return new Color(100, 150, 255, 160);
        }
    }
    
    private static class LeakedBlockEntityData {
        final BlockPos pos;
        String entityType;
        Map<String, Object> data;
        long lastSeen;
        
        LeakedBlockEntityData(BlockPos pos, String type) {
            this.pos = pos;
            this.entityType = type;
            this.data = new HashMap<>();
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY20() { return pos.getY() <= MAX_RENDER_Y; }
        
        Box getBoundingBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
        
        Color getColor() {
            if (entityType.contains("Spawner")) return new Color(200, 0, 0, 200);
            if (entityType.contains("Chest")) return new Color(255, 200, 0, 180);
            if (entityType.contains("Furnace")) return new Color(150, 150, 150, 180);
            if (entityType.contains("Hopper")) return new Color(100, 100, 100, 180);
            if (entityType.contains("Sign")) return new Color(200, 200, 100, 160);
            return new Color(100, 150, 255, 160);
        }
    }
    
    private static class ChunkLeakData {
        final ChunkPos pos;
        int suspiciousBlockCount;
        long lastSeen;
        
        ChunkLeakData(ChunkPos pos) {
            this.pos = pos;
            this.suspiciousBlockCount = 0;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    private static class RedstoneActivity {
        final BlockPos pos;
        int lastPowerLevel;
        long lastUpdate;
        int updateCount;
        
        RedstoneActivity(BlockPos pos, int power) {
            this.pos = pos;
            this.lastPowerLevel = power;
            this.lastUpdate = System.currentTimeMillis();
            this.updateCount = 1;
        }
        
        boolean isActive() { 
            return System.currentTimeMillis() - lastUpdate < 5000 && lastPowerLevel > 0;
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 1: FORCE CHUNK DATA RESEND
    // ============================================================
    
    private void forceChunkDataResend() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        bypassTick++;
        
        // Every 30 ticks (1.5 seconds) - force chunk data refresh
        if (bypassTick % 30 == 0) {
            try {
                // Send teleport confirm with sequence to trigger chunk resync
                TeleportConfirmC2SPacket confirmPacket = new TeleportConfirmC2SPacket(++lastSequenceId);
                mc.getNetworkHandler().sendPacket(confirmPacket);
                
                // Small position jitter to force chunk update
                PlayerMoveC2SPacket.PositionAndOnGround movePacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX() + 0.00001,
                    mc.player.getY(),
                    mc.player.getZ() + 0.00001,
                    mc.player.isOnGround(),
                    false);
                mc.getNetworkHandler().sendPacket(movePacket);
                
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 2: EXTRACT FROM CHUNK UPDATE PACKETS
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // Extract block data from chunk updates (server is forced to send these)
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            analyzeBlockUpdatePacket(packet);
        }
        
        // Extract from chunk delta updates (batch block changes)
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            analyzeChunkDeltaPacket(packet);
        }
        
        // Extract block entity data (chests, spawners, etc.)
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            analyzeBlockEntityPacket(packet);
        }
        
        // Extract from full chunk data
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            analyzeChunkDataPacket(packet);
        }
    }
    
    private void analyzeBlockUpdatePacket(BlockUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            BlockState state = packet.getState();
            Block block = state.getBlock();
            
            // Only care about blocks below Y=20
            if (pos.getY() <= MAX_RENDER_Y) {
                LeakedBlockData leaked = leakedBlocks.get(pos);
                if (leaked == null) {
                    leaked = new LeakedBlockData(pos, block, "block_update");
                    leakedBlocks.put(pos, leaked);
                }
                leaked.block = block;
                leaked.state = state;
                leaked.lastSeen = System.currentTimeMillis();
                
                // Check for redstone power
                if (block instanceof RedstoneWireBlock) {
                    int power = state.get(RedstoneWireBlock.POWER);
                    if (power > 0) {
                        leaked.isRedstonePowered = true;
                        leaked.redstonePower = power;
                        redstoneActivity.put(pos, new RedstoneActivity(pos, power));
                    }
                }
                
                // Check for powered repeaters/comparators
                if (block instanceof RepeaterBlock && state.get(RepeaterBlock.POWERED)) {
                    leaked.isRedstonePowered = true;
                    leaked.redstonePower = 15;
                }
                if (block instanceof ComparatorBlock && state.get(ComparatorBlock.POWERED)) {
                    leaked.isRedstonePowered = true;
                    leaked.redstonePower = 15;
                }
                
                // Log interesting discoveries
                if (block == Blocks.SPAWNER || (leaked.isRedstonePowered && leaked.redstonePower > 0)) {
                    ChatUtils.info("EntityDebug", "§cFound " + block.getName().getString() + " at [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]");
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void analyzeChunkDeltaPacket(ChunkDeltaUpdateS2CPacket packet) {
        try {
            // Use reflection to extract multiple block updates
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Map<?, ?> updates = (Map<?, ?>) f.get(packet);
                    for (Map.Entry<?, ?> entry : updates.entrySet()) {
                        try {
                            long posLong = (long) entry.getKey();
                            BlockState state = (BlockState) entry.getValue();
                            int x = (int)(posLong >> 38);
                            int y = (int)(posLong << 52 >> 52);
                            int z = (int)(posLong << 26 >> 38);
                            BlockPos pos = new BlockPos(x, y, z);
                            
                            if (y <= MAX_RENDER_Y) {
                                LeakedBlockData leaked = leakedBlocks.get(pos);
                                if (leaked == null) {
                                    leaked = new LeakedBlockData(pos, state.getBlock(), "chunk_delta");
                                    leakedBlocks.put(pos, leaked);
                                }
                                leaked.state = state;
                                leaked.lastSeen = System.currentTimeMillis();
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void analyzeBlockEntityPacket(BlockEntityUpdateS2CPacket packet) {
        try {
            BlockPos pos = packet.getPos();
            if (pos.getY() <= MAX_RENDER_Y) {
                String type = packet.getBlockEntityType().toString();
                LeakedBlockEntityData leaked = leakedBlockEntities.get(pos);
                if (leaked == null) {
                    leaked = new LeakedBlockEntityData(pos, type);
                    leakedBlockEntities.put(pos, leaked);
                    ChatUtils.info("EntityDebug", "§eFound " + type + " at [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]");
                }
                leaked.lastSeen = System.currentTimeMillis();
                
                // Extract NBT data if available
                if (packet.getNbt() != null) {
                    var nbt = packet.getNbt();
                    for (String key : nbt.getKeys()) {
                        leaked.data.put(key, nbt.get(key));
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    private void analyzeChunkDataPacket(ChunkDataS2CPacket packet) {
        try {
            ChunkPos chunkPos = packet.getChunkPos();
            int chunkX = chunkPos.x;
            int chunkZ = chunkPos.z;
            
            ChunkLeakData chunkData = leakedChunkData.get(chunkPos);
            if (chunkData == null) {
                chunkData = new ChunkLeakData(chunkPos);
                leakedChunkData.put(chunkPos, chunkData);
            }
            chunkData.lastSeen = System.currentTimeMillis();
            
        } catch (Exception ignored) {}
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 3: ACTIVE REDSTONE DETECTION
    // Detects redstone activity even when hidden
    // ============================================================
    
    private void detectRedstoneActivity() {
        if (mc.world == null) return;
        
        // Scan for redstone components in loaded chunks below Y=20
        int radius = SCAN_RADIUS;
        BlockPos p = mc.player.getBlockPos();
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -20; dy <= MAX_RENDER_Y - p.getY(); dy++) {
                    BlockPos pos = p.add(dx, dy, dz);
                    if (pos.getY() > MAX_RENDER_Y) continue;
                    
                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    
                    // Check for powered redstone components
                    int power = 0;
                    if (block instanceof RedstoneWireBlock) {
                        power = state.get(RedstoneWireBlock.POWER);
                    } else if (block instanceof RepeaterBlock && state.get(RepeaterBlock.POWERED)) {
                        power = 15;
                    } else if (block instanceof ComparatorBlock && state.get(ComparatorBlock.POWERED)) {
                        power = 15;
                    } else if (block instanceof RedstoneTorchBlock && state.get(RedstoneTorchBlock.LIT)) {
                        power = 15;
                    } else if (block instanceof LeverBlock && state.get(LeverBlock.POWERED)) {
                        power = 15;
                    } else if (block instanceof ButtonBlock && state.get(ButtonBlock.POWERED)) {
                        power = 15;
                    }
                    
                    if (power > 0) {
                        LeakedBlockData leaked = leakedBlocks.get(pos);
                        if (leaked == null) {
                            leaked = new LeakedBlockData(pos, block, "redstone_scan");
                            leakedBlocks.put(pos, leaked);
                        }
                        leaked.isRedstonePowered = true;
                        leaked.redstonePower = power;
                        leaked.lastSeen = System.currentTimeMillis();
                        
                        redstoneActivity.put(pos, new RedstoneActivity(pos, power));
                    }
                }
            }
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 4: CLIENT STATUS TRIGGER
    // ============================================================
    
    private void clientStatusBypass() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        if (bypassTick % 80 == 0) { // Every 4 seconds
            try {
                ClientCommandC2SPacket commandPacket = new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY);
                mc.getNetworkHandler().sendPacket(commandPacket);
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 5: KEEP ALIVE TRIGGER
    // ============================================================
    
    private void keepAliveBypass() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        if (bypassTick % 50 == 0) { // Every 2.5 seconds
            try {
                KeepAliveC2SPacket keepAlive = new KeepAliveC2SPacket((int)(System.currentTimeMillis() / 1000));
                mc.getNetworkHandler().sendPacket(keepAlive);
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // ACTIVE BLOCK SCANNING (What the server actually has)
    // ============================================================
    
    private void activeBlockScan() {
        if (mc.world == null || mc.player == null) return;
        
        BlockPos p = mc.player.getBlockPos();
        int radius = SCAN_RADIUS;
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -20; dy <= MAX_RENDER_Y - p.getY(); dy++) {
                    BlockPos pos = p.add(dx, dy, dz);
                    if (pos.getY() > MAX_RENDER_Y || pos.getY() < mc.world.getBottomY()) continue;
                    
                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    
                    if (isImportantBlock(block)) {
                        LeakedBlockData leaked = leakedBlocks.get(pos);
                        if (leaked == null) {
                            leaked = new LeakedBlockData(pos, block, "active_scan");
                            leakedBlocks.put(pos, leaked);
                            if (block == Blocks.SPAWNER) {
                                ChatUtils.info("EntityDebug", "§cSCANNER: Found SPAWNER at [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]");
                            }
                        }
                        leaked.lastSeen = System.currentTimeMillis();
                        leaked.state = state;
                    }
                }
            }
        }
        
        // Clean up old entries
        leakedBlocks.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        leakedBlockEntities.entrySet().removeIf(entry -> !entry.getValue().isBelowY20());
        redstoneActivity.entrySet().removeIf(entry -> !entry.getValue().isActive());
    }
    
    private boolean isImportantBlock(Block block) {
        return block == Blocks.SPAWNER ||
               block == Blocks.CHEST ||
               block == Blocks.TRAPPED_CHEST ||
               block == Blocks.ENDER_CHEST ||
               block == Blocks.BEACON ||
               block == Blocks.FURNACE ||
               block == Blocks.BLAST_FURNACE ||
               block == Blocks.SMOKER ||
               block == Blocks.HOPPER ||
               block == Blocks.DROPPER ||
               block == Blocks.DISPENSER ||
               block == Blocks.OBSERVER ||
               block == Blocks.REPEATER ||
               block == Blocks.COMPARATOR ||
               block == Blocks.REDSTONE_WIRE ||
               block == Blocks.REDSTONE_TORCH ||
               block == Blocks.LEVER ||
               block instanceof ButtonBlock ||
               block instanceof PistonBlock ||
               block == Blocks.NOTE_BLOCK ||
               block == Blocks.JUKEBOX ||
               block == Blocks.SHULKER_BOX ||
               block == Blocks.BLACK_SHULKER_BOX ||
               block == Blocks.BLUE_SHULKER_BOX ||
               block == Blocks.BROWN_SHULKER_BOX ||
               block == Blocks.CYAN_SHULKER_BOX ||
               block == Blocks.GRAY_SHULKER_BOX ||
               block == Blocks.GREEN_SHULKER_BOX ||
               block == Blocks.LIGHT_BLUE_SHULKER_BOX ||
               block == Blocks.LIGHT_GRAY_SHULKER_BOX ||
               block == Blocks.LIME_SHULKER_BOX ||
               block == Blocks.MAGENTA_SHULKER_BOX ||
               block == Blocks.ORANGE_SHULKER_BOX ||
               block == Blocks.PINK_SHULKER_BOX ||
               block == Blocks.PURPLE_SHULKER_BOX ||
               block == Blocks.RED_SHULKER_BOX ||
               block == Blocks.WHITE_SHULKER_BOX ||
               block == Blocks.YELLOW_SHULKER_BOX ||
               block == Blocks.ANVIL ||
               block == Blocks.ENCHANTING_TABLE ||
               block == Blocks.GRINDSTONE ||
               block == Blocks.LOOM ||
               block == Blocks.CARTOGRAPHY_TABLE ||
               block == Blocks.STONECUTTER ||
               block == Blocks.SMITHING_TABLE ||
               block == Blocks.FLETCHING_TABLE;
    }
    
    // ============================================================
    // TICK HANDLER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        // Run all bypass techniques
        forceChunkDataResend();
        clientStatusBypass();
        keepAliveBypass();
        
        // Active scanning
        activeBlockScan();
        detectRedstoneActivity();
        
        // Update render cache
        if (System.currentTimeMillis() - lastPacketAnalysis > 100) {
            renderCache.clear();
            
            for (LeakedBlockData block : leakedBlocks.values()) {
                if (block.isBelowY20() && block.isRecent()) {
                    renderCache.put(block.pos.hashCode(), block.getBoundingBox());
                }
            }
            
            for (LeakedBlockEntityData blockEntity : leakedBlockEntities.values()) {
                if (blockEntity.isBelowY20()) {
                    renderCache.put(blockEntity.pos.hashCode(), blockEntity.getBoundingBox());
                }
            }
            
            lastPacketAnalysis = System.currentTimeMillis();
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
        
        for (Map.Entry<Integer, Box> entry : renderCache.entrySet()) {
            Box box = entry.getValue();
            
            if (box.minY > MAX_RENDER_Y) continue;
            
            double dx = box.getCenter().x - px;
            double dz = box.getCenter().z - pz;
            if (dx * dx + dz * dz > RENDER_DISTANCE * RENDER_DISTANCE) continue;
            
            // Check if this block has active redstone
            BlockPos pos = new BlockPos((int)box.minX, (int)box.minY, (int)box.minZ);
            LeakedBlockData blockData = leakedBlocks.get(pos);
            boolean isRedstoneActive = blockData != null && blockData.isRedstonePowered;
            
            Color fill = isRedstoneActive ? REDSTONE_FILL : ESP_FILL;
            Color line = isRedstoneActive ? REDSTONE_COLOR : ESP_COLOR;
            
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
        bypassTick = 0;
        lastSequenceId = 0;
        leakedBlocks.clear();
        leakedBlockEntities.clear();
        leakedChunkData.clear();
        redstoneActivity.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lDONUT BYPASS ACTIVATED");
        ChatUtils.info("EntityDebug", "§7- Extracting server data below Y=20");
        ChatUtils.info("EntityDebug", "§7- Monitoring: Spawners, Chests, Beacons");
        ChatUtils.info("EntityDebug", "§7- Redstone detection: ACTIVE");
        ChatUtils.info("EntityDebug", "§7- Block Entities: ALL");
        
        mc.player.sendMessage(Text.literal("§8[§c§lED§8] §7Server Data Extractor §aACTIVE"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        leakedBlocks.clear();
        leakedBlockEntities.clear();
        leakedChunkData.clear();
        redstoneActivity.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int blocks = (int) leakedBlocks.values().stream().filter(b -> b.isBelowY20()).count();
        int redstone = (int) redstoneActivity.values().stream().filter(r -> r.isActive()).count();
        int entities = leakedBlockEntities.size();
        return String.format("§c%d §7blocks §8| §c%d §7redstone §8| §c%d §7entities", blocks, redstone, entities);
    }
}
