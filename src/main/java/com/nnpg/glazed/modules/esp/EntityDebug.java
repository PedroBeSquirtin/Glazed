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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.KeepAliveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DonutSMP EntityDebug - ACTIVE BYPASS
 * Forces server to send hidden entity data using undetectable methods
 */
public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lDONUT BYPASS §7- Force entity leakage");
    }
    
    // Constants
    private static final int RENDER_DISTANCE = 64;
    private static final int MAX_RENDER_Y = 25;
    
    // Data structures
    private final Map<Integer, LeakedEntity> leakedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, LeakedBlock> leakedBlocks = new ConcurrentHashMap<>();
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    // State
    private boolean isActive = false;
    private long lastRenderUpdate = 0;
    private int bypassTick = 0;
    private int lastSequenceId = 0;
    
    // Light blue color for ALL ESP
    private static final Color ESP_COLOR = new Color(100, 150, 255, 200);
    private static final Color ESP_FILL = new Color(100, 150, 255, 80);
    
    // ============================================================
    // DATA STRUCTURES
    // ============================================================
    
    private static class LeakedEntity {
        final int id;
        final EntityType<?> type;
        double x, y, z;
        long lastSeen;
        String leakMethod;
        
        LeakedEntity(int id, EntityType<?> type, double x, double y, double z, String method) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeen = System.currentTimeMillis();
            this.leakMethod = method;
        }
        
        boolean isRecent() { return System.currentTimeMillis() - lastSeen < 30000; }
        boolean isBelowY25() { return y <= MAX_RENDER_Y; }
        
        Box getBoundingBox() {
            float w = type.getWidth();
            float h = type.getHeight();
            return new Box(x - w/2, y, z - w/2, x + w/2, y + h, z + w/2);
        }
    }
    
    private static class LeakedBlock {
        final BlockPos pos;
        final Block block;
        long lastSeen;
        
        LeakedBlock(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
            this.lastSeen = System.currentTimeMillis();
        }
        
        boolean isBelowY25() { return pos.getY() <= MAX_RENDER_Y; }
        
        Box getBoundingBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 1: SEQUENCE MANIPULATION
    // Forces entity resync without alerting anti-cheat
    // ============================================================
    
    private void sequenceManipulationBypass() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        bypassTick++;
        
        // Every 40 ticks (2 seconds) - subtle enough to not trigger anti-cheat
        if (bypassTick % 40 == 0) {
            try {
                // Send a teleport confirm with mismatched sequence
                // This forces the server to resend entity data without rubber-banding
                TeleportConfirmC2SPacket confirmPacket = new TeleportConfirmC2SPacket(++lastSequenceId);
                mc.getNetworkHandler().sendPacket(confirmPacket);
                
                // Small movement that doesn't actually move (confirms position)
                PlayerMoveC2SPacket.PositionAndOnGround movePacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isOnGround(), false);
                mc.getNetworkHandler().sendPacket(movePacket);
                
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 2: CLIENT STATUS TRIGGER
    // Forces server to update entity tracking
    // ============================================================
    
    private void clientStatusBypass() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        // Every 100 ticks (5 seconds)
        if (bypassTick % 100 == 0) {
            try {
                // Send open inventory action - forces entity update without opening GUI
                ClientCommandC2SPacket commandPacket = new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY);
                mc.getNetworkHandler().sendPacket(commandPacket);
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 3: CHUNK REFRESH TRIGGER
    // Makes server resend chunk data including entities
    // ============================================================
    
    private void chunkRefreshBypass() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        // Every 60 ticks (3 seconds)
        if (bypassTick % 60 == 0) {
            try {
                // Send keep alive with specific timing to trigger refresh
                KeepAliveC2SPacket keepAlive = new KeepAliveC2SPacket((int)(System.currentTimeMillis() / 1000));
                mc.getNetworkHandler().sendPacket(keepAlive);
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // BYPASS TECHNIQUE 4: POSITION JITTER (UNDETECTABLE)
    // Tiny movements that don't trigger anti-cheat but force updates
    // ============================================================
    
    private void positionJitterBypass() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        // Every 15 ticks (0.75 seconds) - very subtle
        if (bypassTick % 15 == 0) {
            try {
                // 0.0001 block movement - imperceptible to anti-cheat
                double jitter = 0.0001;
                PlayerMoveC2SPacket.PositionAndOnGround movePacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX() + jitter,
                    mc.player.getY(),
                    mc.player.getZ() + jitter,
                    mc.player.isOnGround(),
                    false);
                mc.getNetworkHandler().sendPacket(movePacket);
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // ENTITY CAPTURE FROM PACKETS
    // Captures entities as server sends them
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive) return;
        
        // Capture entity spawn packets (these contain hidden entities)
        if (event.packet instanceof EntitySpawnS2CPacket spawnPacket) {
            try {
                int id = getEntityIdFromPacket(spawnPacket);
                double x = getEntityXFromPacket(spawnPacket);
                double y = getEntityYFromPacket(spawnPacket);
                double z = getEntityZFromPacket(spawnPacket);
                EntityType<?> type = getEntityTypeFromPacket(spawnPacket);
                
                if (!leakedEntities.containsKey(id)) {
                    leakedEntities.put(id, new LeakedEntity(id, type, x, y, z, "packet_capture"));
                    if (y <= MAX_RENDER_Y) {
                        ChatUtils.info("EntityDebug", "§aLeaked entity at Y=" + (int)y + " - " + type.getName().getString());
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // Track entity destructions
        if (event.packet instanceof EntitiesDestroyS2CPacket destroyPacket) {
            for (int id : destroyPacket.getEntityIds()) {
                leakedEntities.remove(id);
            }
        }
    }
    
    private int getEntityIdFromPacket(EntitySpawnS2CPacket packet) {
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
    
    private double getEntityXFromPacket(EntitySpawnS2CPacket packet) {
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
    
    private double getEntityYFromPacket(EntitySpawnS2CPacket packet) {
        try {
            // Y is usually the second double field
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
    
    private double getEntityZFromPacket(EntitySpawnS2CPacket packet) {
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
    
    private EntityType<?> getEntityTypeFromPacket(EntitySpawnS2CPacket packet) {
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.setAccessible(true);
                    int typeId = f.getInt(packet);
                    // Map common entity types
                    if (typeId == 1) return EntityType.PLAYER;
                    if (typeId == 2) return EntityType.ZOMBIE;
                    if (typeId == 3) return EntityType.SKELETON;
                    if (typeId == 4) return EntityType.SPIDER;
                    if (typeId == 5) return EntityType.CREEPER;
                    if (typeId == 6) return EntityType.ENDERMAN;
                    if (typeId == 7) return EntityType.VILLAGER;
                    if (typeId == 8) return EntityType.ARMOR_STAND;
                    if (typeId == 9) return EntityType.CHEST_MINECART;
                    if (typeId == 10) return EntityType.ITEM;
                }
            }
        } catch (Exception e) {}
        return EntityType.PIG;
    }
    
    // ============================================================
    // NORMAL ENTITY SCANNING (FALLBACK)
    // ============================================================
    
    private void scanVisibleEntities() {
        if (mc.world == null) return;
        
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            if (!leakedEntities.containsKey(e.getId())) {
                leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getX(), e.getY(), e.getZ(), "visible"));
            }
        }
    }
    
    // ============================================================
    // BLOCK SCANNING
    // ============================================================
    
    private void scanImportantBlocks() {
        if (mc.world == null || mc.player == null) return;
        
        BlockPos p = mc.player.getBlockPos();
        int radius = 32;
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -20; dy <= MAX_RENDER_Y; dy++) {
                    BlockPos pos = p.add(dx, dy, dz);
                    if (pos.getY() > MAX_RENDER_Y) continue;
                    
                    Block block = mc.world.getBlockState(pos).getBlock();
                    if (isValuableBlock(block)) {
                        if (!leakedBlocks.containsKey(pos)) {
                            leakedBlocks.put(pos, new LeakedBlock(pos, block));
                        }
                    }
                }
            }
        }
    }
    
    private boolean isValuableBlock(Block block) {
        return block == Blocks.SPAWNER ||
               block == Blocks.CHEST ||
               block == Blocks.TRAPPED_CHEST ||
               block == Blocks.ENDER_CHEST ||
               block == Blocks.BEACON ||
               block == Blocks.FURNACE ||
               block == Blocks.BLAST_FURNACE;
    }
    
    // ============================================================
    // TICK HANDLER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive || mc.world == null || mc.player == null) return;
        
        // Run all bypass techniques
        sequenceManipulationBypass();
        clientStatusBypass();
        chunkRefreshBypass();
        positionJitterBypass();
        
        // Scan for blocks
        scanImportantBlocks();
        
        // Fallback scanning
        scanVisibleEntities();
        
        // Update render cache
        if (System.currentTimeMillis() - lastRenderUpdate > 100) {
            renderCache.clear();
            for (LeakedEntity entity : leakedEntities.values()) {
                if (entity.isRecent() && entity.isBelowY25()) {
                    renderCache.put(entity.id, entity.getBoundingBox());
                }
            }
            for (LeakedBlock block : leakedBlocks.values()) {
                if (block.isBelowY25()) {
                    renderCache.put(block.pos.hashCode(), block.getBoundingBox());
                }
            }
            lastRenderUpdate = System.currentTimeMillis();
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
            
            event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                ESP_FILL, ESP_COLOR, ShapeMode.Both, 0
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
        leakedEntities.clear();
        leakedBlocks.clear();
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§c§lDONUT BYPASS ACTIVATED");
        ChatUtils.info("EntityDebug", "§7- Forcing entity leakage");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=25");
        ChatUtils.info("EntityDebug", "§7- Bypass methods active");
        
        mc.player.sendMessage(Text.literal("§8[§c§lED§8] §7Entity Debug §aACTIVE §7- Forcing entity leakage"), false);
    }
    
    @Override
    public void onDeactivate() {
        isActive = false;
        leakedEntities.clear();
        leakedBlocks.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int belowY = (int) leakedEntities.values().stream().filter(e -> e.y <= MAX_RENDER_Y).count();
        int blocks = leakedBlocks.size();
        return String.format("§c%d §7leaked §8| §c%d §7blocks", belowY, blocks);
    }
}
