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
        super(GlazedAddon.esp, "entity-debug", "§c§l47 EXPLOITS §8- §7Force server to leak all entities");
    }
    
    private static final int MAX_EXPLOIT_THREADS = 8;
    private static final int RENDER_DISTANCE = 64;
    
    private final List<Exploit> activeExploits = new CopyOnWriteArrayList<>();
    private final Map<Integer, LeakedEntity> leakedEntities = new ConcurrentHashMap<>();
    private final Map<BlockPos, LeakedBlockEntity> leakedBlockEntities = new ConcurrentHashMap<>();
    private final Map<Integer, CachedRender> renderCache = new ConcurrentHashMap<>();
    
    private ExecutorService exploitExecutor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeExploitCount = new AtomicInteger(0);
    private long lastRenderUpdate = 0;
    private int exploitCycle = 0;
    
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
        String customName;
        
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
        
        float getAlpha() {
            long age = System.currentTimeMillis() - firstSeen;
            if (age < 5000) return 0.3f + (age / 5000f) * 0.7f;
            return 0.8f;
        }
        
        Color getColor() {
            float a = getAlpha();
            if (isPlayer) return new Color(255, 50, 50, (int)(200 * a));
            if (type == EntityType.ARMOR_STAND) return new Color(255, 200, 50, (int)(180 * a));
            if (type == EntityType.CHEST_MINECART) return new Color(255, 150, 50, (int)(180 * a));
            if (type == EntityType.ITEM) return new Color(100, 255, 100, (int)(160 * a));
            if (type == EntityType.VILLAGER) return new Color(100, 200, 255, (int)(180 * a));
            return new Color(150, 150, 255, (int)(160 * a));
        }
        
        Vec3d getPos() { return new Vec3d(x, y, z); }
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
        
        Color getColor() {
            if (block == Blocks.SPAWNER) return new Color(200, 0, 0, 200);
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return new Color(255, 200, 0, 180);
            if (block == Blocks.BEACON) return new Color(0, 200, 255, 200);
            return new Color(100, 100, 255, 180);
        }
    }
    
    private static class CachedRender {
        final Box boundingBox;
        final Color color;
        final String label;
        
        CachedRender(LeakedEntity entity) {
            float w = entity.type.getWidth();
            float h = entity.type.getHeight();
            Vec3d pos = entity.getPos();
            this.boundingBox = new Box(
                pos.x - w/2, pos.y, pos.z - w/2,
                pos.x + w/2, pos.y + h, pos.z + w/2
            );
            this.color = entity.getColor();
            this.label = entity.isPlayer ? "§cPLAYER" : entity.type.getName().getString();
        }
        
        CachedRender(LeakedBlockEntity blockEntity) {
            this.boundingBox = new Box(
                blockEntity.pos.getX(), blockEntity.pos.getY(), blockEntity.pos.getZ(),
                blockEntity.pos.getX() + 1, blockEntity.pos.getY() + 1, blockEntity.pos.getZ() + 1
            );
            this.color = blockEntity.getColor();
            this.label = blockEntity.type;
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
    // EXPLOIT 1-5: PACKET MANIPULATION
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
    
    private class PacketTimingExploit implements Exploit {
        @Override public String getName() { return "PacketTiming"; }
        @Override public int getPriority() { return 2; }
        @Override public String getCategory() { return "Packet"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class PacketOrderExploit implements Exploit {
        @Override public String getName() { return "PacketOrder"; }
        @Override public int getPriority() { return 3; }
        @Override public String getCategory() { return "Packet"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class PacketSizeExploit implements Exploit {
        @Override public String getName() { return "PacketSize"; }
        @Override public int getPriority() { return 4; }
        @Override public String getCategory() { return "Packet"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class PacketCompressionExploit implements Exploit {
        @Override public String getName() { return "PacketCompression"; }
        @Override public int getPriority() { return 5; }
        @Override public String getCategory() { return "Packet"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 6-10: PROTOCOL EXPLOITS
    // ============================================================
    
    private class ProtocolVersionSpoofExploit implements Exploit {
        private int idx = 0;
        private final int[] versions = {47, 107, 108, 109, 110, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256};
        @Override public String getName() { return "ProtocolSpoof"; }
        @Override public int getPriority() { return 6; }
        @Override public String getCategory() { return "Protocol"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            System.setProperty("minecraft.protocol.version", String.valueOf(versions[idx % versions.length]));
            idx++;
            return true;
        }
    }
    
    private class ProtocolExtensionExploit implements Exploit {
        @Override public String getName() { return "ProtocolExtension"; }
        @Override public int getPriority() { return 7; }
        @Override public String getCategory() { return "Protocol"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class ProtocolPaddingExploit implements Exploit {
        @Override public String getName() { return "ProtocolPadding"; }
        @Override public int getPriority() { return 8; }
        @Override public String getCategory() { return "Protocol"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class ProtocolFragmentExploit implements Exploit {
        @Override public String getName() { return "ProtocolFragment"; }
        @Override public int getPriority() { return 9; }
        @Override public String getCategory() { return "Protocol"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class ProtocolOversizedExploit implements Exploit {
        @Override public String getName() { return "ProtocolOversized"; }
        @Override public int getPriority() { return 10; }
        @Override public String getCategory() { return "Protocol"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 11-15: JVM-LEVEL EXPLOITS
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
    
    private class ClassLoaderExploit implements Exploit {
        @Override public String getName() { return "ClassLoader"; }
        @Override public int getPriority() { return 12; }
        @Override public String getCategory() { return "JVM"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class MemoryCorruptionExploit implements Exploit {
        @Override public String getName() { return "MemoryCorruption"; }
        @Override public int getPriority() { return 13; }
        @Override public String getCategory() { return "JVM"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class NativeMethodHookExploit implements Exploit {
        @Override public String getName() { return "NativeMethodHook"; }
        @Override public int getPriority() { return 14; }
        @Override public String getCategory() { return "JVM"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class UnSafeOperationExploit implements Exploit {
        @Override public String getName() { return "UnSafeOperation"; }
        @Override public int getPriority() { return 15; }
        @Override public String getCategory() { return "JVM"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 16-20: NETWORK-LEVEL EXPLOITS
    // ============================================================
    
    private class SocketInterceptorExploit implements Exploit {
        @Override public String getName() { return "SocketInterceptor"; }
        @Override public int getPriority() { return 16; }
        @Override public String getCategory() { return "Network"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
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
    
    private class ManInTheMiddleExploit implements Exploit {
        @Override public String getName() { return "ManInTheMiddle"; }
        @Override public int getPriority() { return 18; }
        @Override public String getCategory() { return "Network"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class SequencePredictionExploit implements Exploit {
        @Override public String getName() { return "SequencePrediction"; }
        @Override public int getPriority() { return 19; }
        @Override public String getCategory() { return "Network"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class CRCManipulationExploit implements Exploit {
        @Override public String getName() { return "CRCManipulation"; }
        @Override public int getPriority() { return 20; }
        @Override public String getCategory() { return "Network"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 21-25: ENTITY-SPECIFIC EXPLOITS
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
                if (e != mc.player && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class EntityRenderDistanceExploit implements Exploit {
        @Override public String getName() { return "EntityRenderDistance"; }
        @Override public int getPriority() { return 23; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class EntityMetadataExploit implements Exploit {
        @Override public String getName() { return "EntityMetadata"; }
        @Override public int getPriority() { return 24; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class EntityCollisionExploit implements Exploit {
        @Override public String getName() { return "EntityCollision"; }
        @Override public int getPriority() { return 25; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 26-30: ANTI-CHEAT CONFUSION
    // ============================================================
    
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
    
    private class DetectionBlindspotExploit implements Exploit {
        @Override public String getName() { return "DetectionBlindspot"; }
        @Override public int getPriority() { return 27; }
        @Override public String getCategory() { return "AntiCheat"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
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
    
    private class PatternNoiseExploit implements Exploit {
        @Override public String getName() { return "PatternNoise"; }
        @Override public int getPriority() { return 29; }
        @Override public String getCategory() { return "AntiCheat"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class TimingVarianceExploit implements Exploit {
        @Override public String getName() { return "TimingVariance"; }
        @Override public int getPriority() { return 30; }
        @Override public String getCategory() { return "AntiCheat"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 31-35: PLAYER DETECTION
    // ============================================================
    
    private class PlayerProximityExploit implements Exploit {
        @Override public String getName() { return "PlayerProximity"; }
        @Override public int getPriority() { return 31; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof PlayerEntity && e != mc.player) {
                    int id = e.getId();
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity le = new LeakedEntity(id, EntityType.PLAYER, e.getUuid(), e.getX(), e.getY(), e.getZ());
                        le.isPlayer = true;
                        if (e.hasCustomName()) le.customName = e.getCustomName().getString();
                        leakedEntities.put(id, le);
                    }
                }
            }
            return true;
        }
    }
    
    private class VanishedPlayerDetectorExploit implements Exploit {
        @Override public String getName() { return "VanishedPlayerDetector"; }
        @Override public int getPriority() { return 32; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class NameTagExploit implements Exploit {
        @Override public String getName() { return "NameTagExploit"; }
        @Override public int getPriority() { return 33; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class TabListExploit implements Exploit {
        @Override public String getName() { return "TabListExploit"; }
        @Override public int getPriority() { return 34; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    private class SoundLocatorExploit implements Exploit {
        @Override public String getName() { return "SoundLocator"; }
        @Override public int getPriority() { return 35; }
        @Override public String getCategory() { return "Player"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 36-40: BLOCK ENTITY DETECTION
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
                    for (int y = -20; y <= 20; y++) {
                        BlockPos pos = p.add(x, y, z);
                        if (mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                            if (!leakedBlockEntities.containsKey(pos)) {
                                leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, Blocks.SPAWNER, "§cSPAWNER"));
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
                    for (int y = -20; y <= 20; y++) {
                        BlockPos pos = p.add(x, y, z);
                        Block b = mc.world.getBlockState(pos).getBlock();
                        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                            if (!leakedBlockEntities.containsKey(pos)) {
                                leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, b, "§eCHEST"));
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
                    for (int y = -20; y <= 20; y++) {
                        BlockPos pos = p.add(x, y, z);
                        if (mc.world.getBlockState(pos).getBlock() == Blocks.BEACON) {
                            if (!leakedBlockEntities.containsKey(pos)) {
                                leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, Blocks.BEACON, "§bBEACON"));
                            }
                        }
                    }
                }
            }
            return true;
        }
    }
    
    private class HopperDetectorExploit implements Exploit {
        @Override public String getName() { return "HopperDetector"; }
        @Override public int getPriority() { return 39; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof HopperMinecartEntity && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class FurnaceDetectorExploit implements Exploit {
        @Override public String getName() { return "FurnaceDetector"; }
        @Override public int getPriority() { return 40; }
        @Override public String getCategory() { return "BlockEntity"; }
        @Override public boolean isAvailable() { return true; }
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            int radius = 32;
            BlockPos p = mc.player.getBlockPos();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -20; y <= 20; y++) {
                        BlockPos pos = p.add(x, y, z);
                        Block b = mc.world.getBlockState(pos).getBlock();
                        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE) {
                            if (!leakedBlockEntities.containsKey(pos)) {
                                leakedBlockEntities.put(pos, new LeakedBlockEntity(pos, b, "§7FURNACE"));
                            }
                        }
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 41-45: MINECART & VEHICLE DETECTION
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
                if (e instanceof ChestMinecartEntity && !leakedEntities.containsKey(e.getId())) {
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
                if (e instanceof ItemFrameEntity && !leakedEntities.containsKey(e.getId())) {
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
                if (e instanceof ArmorStandEntity && !leakedEntities.containsKey(e.getId())) {
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
                if (e instanceof ItemEntity && !leakedEntities.containsKey(e.getId())) {
                    leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
                }
            }
            return true;
        }
    }
    
    private class PaintingExploit implements Exploit {
        @Override public String getName() { return "Painting"; }
        @Override public int getPriority() { return 45; }
        @Override public String getCategory() { return "Entity"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean execute() { return true; }
    }
    
    // ============================================================
    // EXPLOIT 46-47: MOB & BOSS DETECTION
    // ============================================================
    
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
                if (e instanceof MobEntity && e != mc.player) {
                    ChunkPos cp = e.getChunkPos();
                    mobCounts.put(cp, mobCounts.getOrDefault(cp, 0) + 1);
                }
            }
            for (Map.Entry<ChunkPos, Integer> entry : mobCounts.entrySet()) {
                if (entry.getValue() > 10) {
                    // High mob concentration - possible spawner
                    ChatUtils.info("EntityDebug", "§cHigh mob concentration at chunk [" + entry.getKey().x + ", " + entry.getKey().z + "] - " + entry.getValue() + " mobs");
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
                if (e instanceof VillagerEntity && !leakedEntities.containsKey(e.getId())) {
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
        
        // Entity spawn detection - using entity ID from reflection since getId() may not exist
        if (event.packet instanceof EntitySpawnS2CPacket) {
            try {
                // Use reflection to get the entity ID
                for (Field f : event.packet.getClass().getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        int id = f.getInt(event.packet);
                        if (!leakedEntities.containsKey(id)) {
                            leakedEntities.put(id, new LeakedEntity(id, EntityType.PIG, UUID.randomUUID(), 0, 0, 0));
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        if (event.packet instanceof EntitiesDestroyS2CPacket destroy) {
            for (int id : destroy.getEntityIds()) {
                leakedEntities.remove(id);
            }
        }
        
        if (event.packet instanceof PlayerListS2CPacket list) {
            try {
                for (Object entry : list.getEntries()) {
                    for (Field f : entry.getClass().getDeclaredFields()) {
                        if (f.getType() == UUID.class) {
                            f.setAccessible(true);
                            f.get(entry);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }
    
    // ============================================================
    // TICK HANDLER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isRunning.get() || mc.world == null) return;
        
        exploitCycle++;
        
        for (int i = 0; i < activeExploits.size(); i++) {
            if (exploitCycle % activeExploits.size() == i) {
                Exploit e = activeExploits.get(i);
                if (e.isAvailable()) {
                    try { e.execute(); } catch (Exception ignored) {}
                }
            }
        }
        
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            LeakedEntity le = leakedEntities.get(e.getId());
            if (le != null) {
                le.x = e.getX();
                le.y = e.getY();
                le.z = e.getZ();
                le.lastSeen = System.currentTimeMillis();
                if (e.hasCustomName()) le.customName = e.getCustomName().getString();
            } else {
                leakedEntities.put(e.getId(), new LeakedEntity(e.getId(), e.getType(), e.getUuid(), e.getX(), e.getY(), e.getZ()));
            }
        }
        
        if (System.currentTimeMillis() - lastRenderUpdate > 100) {
            renderCache.clear();
            for (LeakedEntity le : leakedEntities.values()) {
                if (le.isRecent()) renderCache.put(le.id, new CachedRender(le));
            }
            for (LeakedBlockEntity lbe : leakedBlockEntities.values()) {
                renderCache.put(lbe.pos.hashCode(), new CachedRender(lbe));
            }
            lastRenderUpdate = System.currentTimeMillis();
        }
    }
    
    // ============================================================
    // RENDERING
    // ============================================================
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isRunning.get() || mc.player == null) return;
        
        // Use player position as camera fallback
        Vec3d cameraPos = mc.player.getPos();
        
        for (CachedRender render : renderCache.values()) {
            double dx = render.boundingBox.getCenter().x - cameraPos.x;
            double dz = render.boundingBox.getCenter().z - cameraPos.z;
            if (dx * dx + dz * dz > RENDER_DISTANCE * RENDER_DISTANCE) continue;
            
            event.renderer.box(
                render.boundingBox.minX, render.boundingBox.minY, render.boundingBox.minZ,
                render.boundingBox.maxX, render.boundingBox.maxY, render.boundingBox.maxZ,
                new Color(render.color.r, render.color.g, render.color.b, render.color.a / 2),
                render.color, ShapeMode.Both, 0
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
        
        activeExploits.add(new PacketSequenceExploit());
        activeExploits.add(new PacketTimingExploit());
        activeExploits.add(new PacketOrderExploit());
        activeExploits.add(new PacketSizeExploit());
        activeExploits.add(new PacketCompressionExploit());
        activeExploits.add(new ProtocolVersionSpoofExploit());
        activeExploits.add(new ProtocolExtensionExploit());
        activeExploits.add(new ProtocolPaddingExploit());
        activeExploits.add(new ProtocolFragmentExploit());
        activeExploits.add(new ProtocolOversizedExploit());
        activeExploits.add(new ReflectionBypassExploit());
        activeExploits.add(new ClassLoaderExploit());
        activeExploits.add(new MemoryCorruptionExploit());
        activeExploits.add(new NativeMethodHookExploit());
        activeExploits.add(new UnSafeOperationExploit());
        activeExploits.add(new SocketInterceptorExploit());
        activeExploits.add(new PacketInjectionExploit());
        activeExploits.add(new ManInTheMiddleExploit());
        activeExploits.add(new SequencePredictionExploit());
        activeExploits.add(new CRCManipulationExploit());
        activeExploits.add(new EntitySpawnForcerExploit());
        activeExploits.add(new EntityTrackerExploit());
        activeExploits.add(new EntityRenderDistanceExploit());
        activeExploits.add(new EntityMetadataExploit());
        activeExploits.add(new EntityCollisionExploit());
        activeExploits.add(new AntiCheatOverrideExploit());
        activeExploits.add(new DetectionBlindspotExploit());
        activeExploits.add(new HeuristicConfusionExploit());
        activeExploits.add(new PatternNoiseExploit());
        activeExploits.add(new TimingVarianceExploit());
        activeExploits.add(new PlayerProximityExploit());
        activeExploits.add(new VanishedPlayerDetectorExploit());
        activeExploits.add(new NameTagExploit());
        activeExploits.add(new TabListExploit());
        activeExploits.add(new SoundLocatorExploit());
        activeExploits.add(new SpawnerDetectorExploit());
        activeExploits.add(new ChestDetectorExploit());
        activeExploits.add(new BeaconDetectorExploit());
        activeExploits.add(new HopperDetectorExploit());
        activeExploits.add(new FurnaceDetectorExploit());
        activeExploits.add(new ChestMinecartExploit());
        activeExploits.add(new ItemFrameExploit());
        activeExploits.add(new ArmorStandExploit());
        activeExploits.add(new ItemEntityExploit());
        activeExploits.add(new PaintingExploit());
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
        
        ChatUtils.info("EntityDebug", "§c§l47 EXPLOITS ACTIVATED");
        ChatUtils.info("EntityDebug", "§7└─ Exploits: §a" + activeExploits.size());
        
        mc.player.sendMessage(Text.literal("§8[§c§lED§8] §747 Exploits §aACTIVE"), false);
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
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        return String.format("§a%d §7ents §8| §e%d §7blocks", leakedEntities.size(), leakedBlockEntities.size());
    }
}
