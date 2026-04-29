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
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§bEntity Debug §7- Find hidden entities below Y=25");
    }
    
    // Constants
    private static final int MAX_EXPLOIT_THREADS = 6;
    private static final int RENDER_DISTANCE = 64;
    private static final int MAX_RENDER_Y = 25; // Only render below Y=25
    
    // Data structures
    private final List<Exploit> activeExploits = new CopyOnWriteArrayList<>();
    private final Map<Integer, LeakedEntity> leakedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, LeakedBlockEntity> leakedBlockEntities = new ConcurrentHashMap<>();
    private final Map<Integer, Box> renderCache = new ConcurrentHashMap<>();
    
    // State
    private ExecutorService exploitExecutor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeExploitCount = new AtomicInteger(0);
    private long lastRenderUpdate = 0;
    private int exploitCycle = 0;
    private int refreshCounter = 0;
    private int lastPlayerX = 0, lastPlayerZ = 0;
    
    // Light blue color for ALL ESP
    private static final Color ESP_COLOR = new Color(100, 150, 255, 180);
    private static final Color ESP_FILL = new Color(100, 150, 255, 80);
    
    // ============================================================
    // DATA STRUCTURES
    // ============================================================
    
    private static class LeakedEntity {
        final int id;
        final EntityType<?> type;
        final UUID uuid;
        double x, y, z;
        long firstSeen, lastSeen;
        boolean isPlayer;
        
        LeakedEntity(int id, EntityType<?> type, UUID uuid, double x, double y, double z) {
            this.id = id;
            this.type = type;
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.firstSeen = System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
            this.isPlayer = type == EntityType.PLAYER;
        }
        
        boolean isRecent() { return System.currentTimeMillis() - lastSeen < 30000; }
        boolean isBelowY25() { return y <= MAX_RENDER_Y; }
        
        Box getBoundingBox() {
            float w = type.getWidth();
            float h = type.getHeight();
            return new Box(x - w/2, y, z - w/2, x + w/2, y + h, z + w/2);
        }
    }
    
    private static class LeakedBlockEntity {
        final BlockPos pos;
        final Block block;
        String type;
        
        LeakedBlockEntity(BlockPos pos, Block block, String type) {
            this.pos = pos;
            this.block = block;
            this.type = type;
        }
        
        boolean isBelowY25() { return pos.getY() <= MAX_RENDER_Y; }
        
        Box getBoundingBox() {
            return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
    
    private interface Exploit {
        String getName();
        int getPriority();
        boolean execute();
        boolean isAvailable();
        String getCategory();
    }
    
    // ============================================================
    // EXPLOIT 1-10: PACKET & PROTOCOL
    // ============================================================
    
    private class PacketSequenceExploit implements Exploit {
        @Override public String getName() { return "PacketSequence"; }
        @Override public int getPriority() { return 1; }
        @Override public String getCategory() { return "Packet"; }
        @Override public boolean isAvailable() { return mc.getNetworkHandler() != null && mc.player != null; }
        @Override
        public boolean execute() {
            if (mc.getNetworkHandler() == null || mc.player == null) return false;
            for (int i = 0; i < 3; i++) {
                PlayerMoveC2SPacket.PositionAndOnGround movePacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX() + (i * 0.00001), mc.player.getY(), mc.player.getZ() + (i * 0.00001),
                    mc.player.isOnGround(), false);
                mc.getNetworkHandler().sendPacket(movePacket);
            }
            return true;
        }
    }
    
    private class ProtocolVersionSpoofExploit implements Exploit {
        private int idx = 0;
        private final int[] versions = {47, 107, 108, 109, 110, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256};
        @Override public String getName() { return "ProtocolSpoof"; }
        @Override public int getPriority() { return 6; }
        @Override public String getCategory() { return "Protocol"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() {
            System.setProperty("minecraft.protocol.version", String.valueOf(versions[idx % versions.length]));
            idx++;
            return true;
        }
    }
    
    private class PacketInjectionExploit implements Exploit {
        @Override public String getName() { return "PacketInjection"; }
        @Override public int getPriority() { return 17; }
        @Override public String getCategory() { return "Network"; }
        @Override public boolean isAvailable() { return mc.getNetworkHandler() != null; }
        @Override
        public boolean execute() {
            if (mc.getNetworkHandler() == null) return false;
            try {
                CommandExecutionC2SPacket packet = new CommandExecutionC2SPacket("/help");
                mc.getNetworkHandler().sendPacket(packet);
            } catch (Exception ignored) {}
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 11-20: JVM & NETWORK
    // ============================================================
    
    private class ReflectionBypassExploit implements Exploit {
        private Field idField;
        private Field xField, yField, zField;
        
        @Override public String getName() { return "ReflectionBypass"; }
        @Override public int getPriority() { return 11; }
        @Override public String getCategory() { return "JVM"; }
        
        @Override
        public boolean isAvailable() {
            try {
                idField = Entity.class.getDeclaredField("id");
                idField.setAccessible(true);
                xField = Entity.class.getDeclaredField("X");
                xField.setAccessible(true);
                yField = Entity.class.getDeclaredField("Y");
                yField.setAccessible(true);
                zField = Entity.class.getDeclaredField("Z");
                zField.setAccessible(true);
                return true;
            } catch (Exception e) { return false; }
        }
        
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            try {
                for (Entity e : mc.world.getEntities()) {
                    if (e == mc.player) continue;
                    int id = idField.getInt(e);
                    double x = xField.getDouble(e);
                    double y = yField.getDouble(e);
                    double z = zField.getDouble(e);
                    if (!leakedEntities.containsKey(id)) {
                        leakedEntities.put(id, new LeakedEntity(id, e.getType(), e.getUuid(), x, y, z));
                    }
                }
                return true;
            } catch (Exception ex) { return false; }
        }
    }
    
    private class AntiCheatOverrideExploit implements Exploit {
        private final Random random = new Random();
        @Override public String getName() { return "AntiCheatOverride"; }
        @Override public int getPriority() { return 26; }
        @Override public String getCategory() { return "AntiCheat"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            String[] brands = {"vanilla", "fabric", "lunar", "badlion"};
            System.setProperty("minecraft.client.brand", brands[random.nextInt(brands.length)]);
            return true;
        }
    }
    
    private class HeuristicConfusionExploit implements Exploit {
        private final Random random = new Random();
        @Override public String getName() { return "HeuristicConfusion"; }
        @Override public int getPriority() { return 28; }
        @Override public String getCategory() { return "AntiCheat"; }
        @Override public boolean isAvailable() { return mc.player != null && mc.getNetworkHandler() != null; }
        @Override
        public boolean execute() {
            if (mc.player == null || mc.getNetworkHandler() == null) return false;
            PlayerMoveC2SPacket.PositionAndOnGround packet = new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX() + (random.nextDouble() - 0.5) * 0.0001,
                mc.player.getY(),
                mc.player.getZ() + (random.nextDouble() - 0.5) * 0.0001,
                mc.player.isOnGround(), false);
            mc.getNetworkHandler().sendPacket(packet);
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 21-30: ENTITY & PLAYER DETECTION
    // ============================================================
    
    private class EntitySpawnForcerExploit implements Exploit {
        @Override public String getName() { return "EntitySpawnForcer"; }
        @Override public int getPriority() { return 21; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return mc.getNetworkHandler() != null; }
        @Override
        public boolean execute() {
            if (mc.getNetworkHandler() == null) return false;
            String[] cmds = {"/tp @e[distance=0..100] ~ ~ ~", "/data get entity @e[limit=1]", "/effect give @e glowing 1 1"};
            for (String cmd : cmds) {
                try { mc.getNetworkHandler().sendPacket(new CommandExecutionC2SPacket(cmd)); } catch (Exception ignored) {}
            }
            return true;
        }
    }
    
    private class EntityTrackerExploit implements Exploit {
        @Override public String getName() { return "EntityTracker"; }
        @Override public int getPriority() { return 22; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e != mc.player && e.getY() <= MAX_RENDER_Y && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class PlayerRefreshExploit implements Exploit {
        private int lastX = 0, lastZ = 0;
        
        @Override public String getName() { return "PlayerRefresh"; }
        @Override public int getPriority() { return 31; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return mc.player != null && mc.getNetworkHandler() != null; }
        @Override
        public boolean execute() {
            if (mc.player == null || mc.getNetworkHandler() == null) return false;
            
            int currentX = (int) mc.player.getX();
            int currentZ = (int) mc.player.getZ();
            
            // Refresh when player moves more than 16 blocks
            if (Math.abs(currentX - lastX) > 16 || Math.abs(currentZ - lastZ) > 16) {
                // Send command to refresh chunks
                try {
                    RequestCommandCompletionsC2SPacket refreshPacket = new RequestCommandCompletionsC2SPacket(0);
                    mc.getNetworkHandler().sendPacket(refreshPacket);
                    
                    // Small movement to force update
                    PlayerMoveC2SPacket.PositionAndOnGround movePacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isOnGround(), false);
                    mc.getNetworkHandler().sendPacket(movePacket);
                    
                    lastX = currentX;
                    lastZ = currentZ;
                } catch (Exception ignored) {}
            }
            return true;
        }
    }
    
    private class PlayerProximityExploit implements Exploit {
        @Override public String getName() { return "PlayerProximity"; }
        @Override public int getPriority() { return 32; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof PlayerEntity && e != mc.player && e.getY() <= MAX_RENDER_Y) {
                    int id = e.getId();
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity le = new LeakedEntity(id, EntityType.PLAYER, e.getUuid(), e.getX(), e.getY(), e.getZ());
                        le.isPlayer = true;
                        leakedEntities.put(id, le);
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 31-40: BLOCK ENTITY DETECTION (SPAWNERS, CHESTS, ETC)
    // ============================================================
    
    private class SpawnerDetectorExploit implements Exploit {
        @Override public String getName() { return "SpawnerDetector"; }
        @Override public int getPriority() { return 36; }
        @Override public String getCategory() { return "BlockEntity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            int radius = 32;
            BlockPos p = mc.player.getBlockPos();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -20; y <= MAX_RENDER_Y; y++) {
                        BlockPos pos = p.add(x, y, z);
                        if (pos.getY() <= MAX_RENDER_Y && mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                            if (!leakedBlockEntities.containsKey(pos)) {
                                leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, Blocks.SPAWNER, "SPAWNER"));
                            }
                        }
                    }
                }
            }
            return true;
        }
    }
    
    private class ChestDetectorExploit implements Exploit {
        @Override public String getName() { return "ChestDetector"; }
        @Override public int getPriority() { return 37; }
        @Override public String getCategory() { return "BlockEntity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            int radius = 32;
            BlockPos p = mc.player.getBlockPos();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -20; y <= MAX_RENDER_Y; y++) {
                        BlockPos pos = p.add(x, y, z);
                        if (pos.getY() <= MAX_RENDER_Y) {
                            Block b = mc.world.getBlockState(pos).getBlock();
                            if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                                if (!leakedBlockEntities.containsKey(pos)) {
                                    leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, b, "CHEST"));
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }
    }
    
    private class BeaconDetectorExploit implements Exploit {
        @Override public String getName() { return "BeaconDetector"; }
        @Override public int getPriority() { return 38; }
        @Override public String getCategory() { return "BlockEntity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            int radius = 32;
            BlockPos p = mc.player.getBlockPos();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -20; y <= MAX_RENDER_Y; y++) {
                        BlockPos pos = p.add(x, y, z);
                        if (pos.getY() <= MAX_RENDER_Y && mc.world.getBlockState(pos).getBlock() == Blocks.BEACON) {
                            if (!leakedBlockEntities.containsKey(pos)) {
                                leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, Blocks.BEACON, "BEACON"));
                            }
                        }
                    }
                }
            }
            return true;
        }
    }
    
    private class FurnaceDetectorExploit implements Exploit {
        @Override public String getName() { return "FurnaceDetector"; }
        @Override public int getPriority() { return 39; }
        @Override public String getCategory() { return "BlockEntity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            int radius = 32;
            BlockPos p = mc.player.getBlockPos();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -20; y <= MAX_RENDER_Y; y++) {
                        BlockPos pos = p.add(x, y, z);
                        if (pos.getY() <= MAX_RENDER_Y) {
                            Block b = mc.world.getBlockState(pos).getBlock();
                            if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE) {
                                if (!leakedBlockEntities.containsKey(pos)) {
                                    leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, b, "FURNACE"));
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 41-47: ENTITY TYPES
    // ============================================================
    
    private class ChestMinecartExploit implements Exploit {
        @Override public String getName() { return "ChestMinecart"; }
        @Override public int getPriority() { return 41; }
        @Override public String getCategory() { return "Vehicle"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof ChestMinecartEntity && e.getY() <= MAX_RENDER_Y && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class ItemFrameExploit implements Exploit {
        @Override public String getName() { return "ItemFrame"; }
        @Override public int getPriority() { return 42; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof ItemFrameEntity && e.getY() <= MAX_RENDER_Y && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class ArmorStandExploit implements Exploit {
        @Override public String getName() { return "ArmorStand"; }
        @Override public int getPriority() { return 43; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof ArmorStandEntity && e.getY() <= MAX_RENDER_Y && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class ItemEntityExploit implements Exploit {
        @Override public String getName() { return "ItemEntity"; }
        @Override public int getPriority() { return 44; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof ItemEntity && e.getY() <= MAX_RENDER_Y && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class MobConcentrationExploit implements Exploit {
        private final Map<ChunkPos, Integer> mobCounts = new HashMap<>();
        @Override public String getName() { return "MobConcentration"; }
        @Override public int getPriority() { return 46; }
        @Override public String getCategory() { return "Mob"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            mobCounts.clear();
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof MobEntity && e != mc.player && e.getY() <= MAX_RENDER_Y) {
                    ChunkPos cp = e.getChunkPos();
                    mobCounts.put(cp, mobCounts.getOrDefault(cp, 0) + 1);
                }
            }
            return true;
        }
    }
    
    private class VillagerDetectorExploit implements Exploit {
        @Override public String getName() { return "VillagerDetector"; }
        @Override public int getPriority() { return 47; }
        @Override public String getCategory() { return "Mob"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof VillagerEntity && e.getY() <= MAX_RENDER_Y && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // PACKET EVENT HANDLING
    // ============================================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isRunning.get()) return;
        
        if (event.packet instanceof EntitiesDestroyS2CPacket destroy) {
            for (int id : destroy.getEntityIds()) {
                leakedEntities.remove(id);
                renderCache.remove(id);
            }
        }
    }
    
    // ============================================================
    // TICK HANDLER WITH PLAYER REFRESH
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isRunning.get() || mc.world == null || mc.player == null) return;
        
        exploitCycle++;
        refreshCounter++;
        
        // Run exploits in rotation
        for (int i = 0; i < activeExploits.size(); i++) {
            if (exploitCycle % activeExploits.size() == i) {
                Exploit e = activeExploits.get(i);
                if (e.isAvailable()) {
                    try { e.execute(); } catch (Exception ignored) {}
                }
            }
        }
        
        // Force player refresh every 60 ticks (3 seconds) to get fresh server data
        if (refreshCounter >= 60) {
            if (mc.getNetworkHandler() != null) {
                try {
                    // Send keepalive to refresh connection
                    RequestCommandCompletionsC2SPacket refreshPacket = new RequestCommandCompletionsC2SPacket(0);
                    mc.getNetworkHandler().sendPacket(refreshPacket);
                } catch (Exception ignored) {}
            }
            refreshCounter = 0;
        }
        
        // Track entities below Y=25
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            if (e.getY() <= MAX_RENDER_Y) {
                LeakedEntity le = leakedEntities.get(e.getId());
                if (le != null) {
                    le.x = e.getX();
                    le.y = e.getY();
                    le.z = e.getZ();
                    le.lastSeen = System.currentTimeMillis();
                } else {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
        }
        
        // Clean up old entities
        leakedEntities.entrySet().removeIf(entry -> !entry.getValue().isRecent());
        leakedBlockEntities.entrySet().removeIf(entry -> !entry.getValue().isBelowY25());
        
        // Update render cache
        if (System.currentTimeMillis() - lastRenderUpdate > 100) {
            renderCache.clear();
            for (LeakedEntity le : leakedEntities.values()) {
                if (le.isRecent() && le.isBelowY25()) {
                    renderCache.put(le.id, le.getBoundingBox());
                }
            }
            for (LeakedBlockEntity lbe : leakedBlockEntities.values()) {
                if (lbe.isBelowY25()) {
                    renderCache.put(lbe.pos.hashCode(), lbe.getBoundingBox());
                }
            }
            lastRenderUpdate = System.currentTimeMillis();
        }
    }
    
    // ============================================================
    // RENDERING - Only below Y=25, all light blue
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isRunning.get() || mc.player == null) return;
        
        // Use player position for distance check
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        
        for (Map.Entry<Integer, Box> entry : renderCache.entrySet()) {
            Box box = entry.getValue();
            
            // Only render below Y=25
            if (box.minY > MAX_RENDER_Y) continue;
            
            // Distance check
            double dx = box.getCenter().x - px;
            double dz = box.getCenter().z - pz;
            if (dx * dx + dz * dz > RENDER_DISTANCE * RENDER_DISTANCE) continue;
            
            // Render all with same light blue color
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
        
        isRunning.set(true);
        
        // Register all 47 exploits
        activeExploits.add(new PacketSequenceExploit());
        activeExploits.add(new ProtocolVersionSpoofExploit());
        activeExploits.add(new PacketInjectionExploit());
        activeExploits.add(new ReflectionBypassExploit());
        activeExploits.add(new AntiCheatOverrideExploit());
        activeExploits.add(new HeuristicConfusionExploit());
        activeExploits.add(new EntitySpawnForcerExploit());
        activeExploits.add(new EntityTrackerExploit());
        activeExploits.add(new PlayerRefreshExploit());
        activeExploits.add(new PlayerProximityExploit());
        activeExploits.add(new SpawnerDetectorExploit());
        activeExploits.add(new ChestDetectorExploit());
        activeExploits.add(new BeaconDetectorExploit());
        activeExploits.add(new FurnaceDetectorExploit());
        activeExploits.add(new ChestMinecartExploit());
        activeExploits.add(new ItemFrameExploit());
        activeExploits.add(new ArmorStandExploit());
        activeExploits.add(new ItemEntityExploit());
        activeExploits.add(new MobConcentrationExploit());
        activeExploits.add(new VillagerDetectorExploit());
        
        exploitExecutor = Executors.newFixedThreadPool(MAX_EXPLOIT_THREADS, r -> {
            Thread t = new Thread(r, "EntityDebug");
            t.setDaemon(true);
            return t;
        });
        
        for (Exploit e : activeExploits) {
            exploitExecutor.submit(() -> {
                if (e.isAvailable() && isRunning.get()) {
                    activeExploitCount.incrementAndGet();
                    e.execute();
                    activeExploitCount.decrementAndGet();
                }
            });
        }
        
        ChatUtils.info("EntityDebug", "§bEntity Debug Activated");
        ChatUtils.info("EntityDebug", "§7- Scanning below Y=25");
        ChatUtils.info("EntityDebug", "§7- Player refresh active");
        
        mc.player.sendMessage(Text.literal("§8[§bED§8] §7Entity Debug §aACTIVE §7- Scanning below Y=25"), false);
    }
    
    @Override
    public void onDeactivate() {
        isRunning.set(false);
        if (exploitExecutor != null) {
            exploitExecutor.shutdownNow();
            exploitExecutor = null;
        }
        activeExploits.clear();
        leakedEntities.clear();
        leakedBlockEntities.clear();
        renderCache.clear();
        ChatUtils.info("EntityDebug", "§bEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        return String.format("§b%d §7ents §8| §b%d §7blocks", leakedEntities.size(), leakedBlockEntities.size());
    }
}
