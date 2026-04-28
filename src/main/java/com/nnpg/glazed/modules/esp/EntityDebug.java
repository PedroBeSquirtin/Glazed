package com.nnpg.glazed.modules.debug;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityDebug extends Module {
    
    public EntityDebug() {
        super(GlazedAddon.esp, "entity-debug", "§c§lENTITY DEBUG - Force server to leak all hidden entities");
    }
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final int MAX_EXPLOIT_THREADS = 8;
    private static final int EXPLOIT_PRIORITY = Thread.MAX_PRIORITY;
    
    // ============================================================
    // FIELDS
    // ============================================================
    
    private final List<Exploit> activeExploits = new CopyOnWriteArrayList<>();
    private final Map<Integer, LeakedEntity> leakedEntities = new ConcurrentHashMap<>();
    private final Set<UUID> knownPlayerUUIDs = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CachedEntityRender> renderCache = new ConcurrentHashMap<>();
    
    private ExecutorService exploitExecutor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeExploitCount = new AtomicInteger(0);
    
    private long lastRenderUpdate = 0;
    
    // ============================================================
    // INNER CLASSES
    // ============================================================
    
    private static class LeakedEntity {
        final int id;
        final EntityType<?> type;
        final UUID uuid;
        Vec3d position;
        long firstSeen;
        long lastSeen;
        boolean isPlayer;
        String customName;
        
        LeakedEntity(int id, EntityType<?> type, UUID uuid, Vec3d position) {
            this.id = id;
            this.type = type;
            this.uuid = uuid;
            this.position = position;
            this.firstSeen = System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
            this.isPlayer = type == EntityType.PLAYER;
        }
        
        boolean isRecentlyActive() {
            return System.currentTimeMillis() - lastSeen < 30000;
        }
        
        float getAlpha() {
            long age = System.currentTimeMillis() - firstSeen;
            if (age < 5000) return 0.3f + (age / 5000f) * 0.7f;
            return 0.8f;
        }
        
        Color getColor() {
            if (isPlayer) {
                return new Color(255, 50, 50, (int)(200 * getAlpha()));
            }
            if (type == EntityType.ARMOR_STAND) {
                return new Color(255, 200, 50, (int)(180 * getAlpha()));
            }
            if (type == EntityType.CHEST_MINECART) {
                return new Color(255, 150, 50, (int)(180 * getAlpha()));
            }
            if (type == EntityType.ITEM) {
                return new Color(100, 255, 100, (int)(160 * getAlpha()));
            }
            if (type == EntityType.VILLAGER) {
                return new Color(100, 200, 255, (int)(180 * getAlpha()));
            }
            return new Color(150, 150, 255, (int)(160 * getAlpha()));
        }
    }
    
    private static class CachedEntityRender {
        final Box boundingBox;
        final Color color;
        final String label;
        final int id;
        
        CachedEntityRender(LeakedEntity entity) {
            this.id = entity.id;
            float w = entity.type.getWidth();
            float h = entity.type.getHeight();
            this.boundingBox = new Box(
                entity.position.x - w/2, entity.position.y,
                entity.position.z - w/2,
                entity.position.x + w/2, entity.position.y + h,
                entity.position.z + w/2
            );
            this.color = entity.getColor();
            this.label = entity.isPlayer ? "§cPLAYER" : entity.type.getName().getString();
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
    // EXPLOIT 1: REFLECTION BYPASS
    // ============================================================
    
    private class ReflectionBypassExploit implements Exploit {
        private Field entityIdField;
        private Field entityPosField;
        
        @Override public String getName() { return "ReflectionBypass"; }
        @Override public int getPriority() { return 1; }
        @Override public String getCategory() { return "JVM"; }
        
        @Override
        public boolean isAvailable() {
            try {
                entityIdField = Entity.class.getDeclaredField("id");
                entityIdField.setAccessible(true);
                entityPosField = Entity.class.getDeclaredField("blockPos");
                entityPosField.setAccessible(true);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            
            try {
                for (Entity entity : mc.world.getEntities()) {
                    if (entity == mc.player) continue;
                    
                    int id = entityIdField.getInt(entity);
                    BlockPos pos = (BlockPos) entityPosField.get(entity);
                    Vec3d position = new Vec3d(pos.getX(), pos.getY(), pos.getZ());
                    
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity leaked = new LeakedEntity(id, entity.getType(), entity.getUuid(), position);
                        leakedEntities.put(id, leaked);
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
    
    // ============================================================
    // EXPLOIT 2: PLAYER PROXIMITY SCANNER
    // ============================================================
    
    private class PlayerProximityExploit implements Exploit {
        @Override public String getName() { return "PlayerProximity"; }
        @Override public int getPriority() { return 2; }
        @Override public String getCategory() { return "Detection"; }
        
        @Override
        public boolean isAvailable() { return true; }
        
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof PlayerEntity && entity != mc.player) {
                    PlayerEntity player = (PlayerEntity) entity;
                    int id = player.getId();
                    
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity leaked = new LeakedEntity(id, EntityType.PLAYER, player.getUuid(), player.getPos());
                        leaked.isPlayer = true;
                        if (player.hasCustomName()) {
                            leaked.customName = player.getCustomName().getString();
                        }
                        leakedEntities.put(id, leaked);
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 3: ENTITY SPAWN FORCER
    // ============================================================
    
    private class EntitySpawnForcerExploit implements Exploit {
        @Override public String getName() { return "EntitySpawnForcer"; }
        @Override public int getPriority() { return 3; }
        @Override public String getCategory() { return "Entity"; }
        
        @Override
        public boolean isAvailable() {
            return mc.getNetworkHandler() != null;
        }
        
        @Override
        public boolean execute() {
            if (mc.getNetworkHandler() == null) return false;
            
            String[] commands = {
                "/tp @e[distance=0..100] ~ ~ ~",
                "/data get entity @e[limit=1]",
                "/effect give @e minecraft:glowing 1 1 true"
            };
            
            for (String cmd : commands) {
                try {
                    CommandExecutionC2SPacket packet = new CommandExecutionC2SPacket(cmd);
                    mc.getNetworkHandler().sendPacket(packet);
                } catch (Exception ignored) {}
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 4: ANTI-CHEAT CONFUSION
    // ============================================================
    
    private class AntiCheatConfusionExploit implements Exploit {
        private final Random random = new Random();
        
        @Override public String getName() { return "AntiCheatConfusion"; }
        @Override public int getPriority() { return 4; }
        @Override public String getCategory() { return "AntiCheat"; }
        
        @Override
        public boolean isAvailable() { return true; }
        
        @Override
        public boolean execute() {
            if (mc.player == null) return false;
            
            // Send random tiny movements to confuse anti-cheat
            double offsetX = (random.nextDouble() - 0.5) * 0.0001;
            double offsetZ = (random.nextDouble() - 0.5) * 0.0001;
            
            PlayerMoveC2SPacket.PositionAndOnGround packet = new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX() + offsetX,
                mc.player.getY(),
                mc.player.getZ() + offsetZ,
                mc.player.isOnGround(),
                false
            );
            
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(packet);
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 5: ENTITY TRACKER EXPLOIT
    // ============================================================
    
    private class EntityTrackerExploit implements Exploit {
        @Override public String getName() { return "EntityTracker"; }
        @Override public int getPriority() { return 5; }
        @Override public String getCategory() { return "Entity"; }
        
        @Override
        public boolean isAvailable() { return true; }
        
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            
            // Track all entities in render distance
            for (Entity entity : mc.world.getEntities()) {
                if (entity == mc.player) continue;
                
                double dist = mc.player.distanceTo(entity);
                if (dist < 100) {
                    int id = entity.getId();
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity leaked = new LeakedEntity(id, entity.getType(), entity.getUuid(), entity.getPos());
                        leakedEntities.put(id, leaked);
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 6: SPAWNER DETECTION
    // ============================================================
    
    private class SpawnerDetectorExploit implements Exploit {
        @Override public String getName() { return "SpawnerDetector"; }
        @Override public int getPriority() { return 6; }
        @Override public String getCategory() { return "BlockEntity"; }
        
        @Override
        public boolean isAvailable() { return true; }
        
        @Override
        public boolean execute() {
            if (mc.world == null || mc.player == null) return false;
            
            // Scan for spawner entities (spawners spawn mobs)
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof MobEntity) {
                    // If we see mobs, there might be a spawner nearby
                    int id = entity.getId();
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity leaked = new LeakedEntity(id, entity.getType(), entity.getUuid(), entity.getPos());
                        leakedEntities.put(id, leaked);
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 7: CONTAINER SCANNER
    // ============================================================
    
    private class ContainerScannerExploit implements Exploit {
        @Override public String getName() { return "ContainerScanner"; }
        @Override public int getPriority() { return 7; }
        @Override public String getCategory() { return "BlockEntity"; }
        
        @Override
        public boolean isAvailable() { return true; }
        
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            
            // Look for chest minecarts (containers)
            for (Entity entity : mc.world.getEntities()) {
                if (entity.getType() == EntityType.CHEST_MINECART || 
                    entity.getType() == EntityType.HOPPER_MINECART) {
                    int id = entity.getId();
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity leaked = new LeakedEntity(id, entity.getType(), entity.getUuid(), entity.getPos());
                        leakedEntities.put(id, leaked);
                    }
                }
            }
            return true;
        }
    }
    
    // ============================================================
    // EXPLOIT 8: HIDDEN ITEM DETECTOR
    // ============================================================
    
    private class HiddenItemDetectorExploit implements Exploit {
        @Override public String getName() { return "HiddenItemDetector"; }
        @Override public int getPriority() { return 8; }
        @Override public String getCategory() { return "Entity"; }
        
        @Override
        public boolean isAvailable() { return true; }
        
        @Override
        public boolean execute() {
            if (mc.world == null) return false;
            
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof ItemEntity) {
                    int id = entity.getId();
                    if (!leakedEntities.containsKey(id)) {
                        LeakedEntity leaked = new LeakedEntity(id, EntityType.ITEM, entity.getUuid(), entity.getPos());
                        leakedEntities.put(id, leaked);
                    }
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
        
        // Capture entity spawn packets
        if (event.packet instanceof EntitySpawnS2CPacket spawnPacket) {
            try {
                int id = spawnPacket.getId();
                EntityType<?> type = EntityType.PIG; // Default fallback
                Vec3d pos = new Vec3d(spawnPacket.getX(), spawnPacket.getY(), spawnPacket.getZ());
                UUID uuid = UUID.randomUUID();
                
                if (!leakedEntities.containsKey(id)) {
                    LeakedEntity entity = new LeakedEntity(id, type, uuid, pos);
                    leakedEntities.put(id, entity);
                }
            } catch (Exception ignored) {}
        }
        
        // Capture entity destroy
        if (event.packet instanceof EntitiesDestroyS2CPacket destroyPacket) {
            for (int id : destroyPacket.getEntityIds()) {
                leakedEntities.remove(id);
                renderCache.remove(id);
            }
        }
    }
    
    // ============================================================
    // TICK HANDLER
    // ============================================================
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isRunning.get() || mc.world == null) return;
        
        // Update entity positions
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            
            LeakedEntity leaked = leakedEntities.get(entity.getId());
            if (leaked != null) {
                leaked.position = entity.getPos();
                leaked.lastSeen = System.currentTimeMillis();
                if (entity.hasCustomName()) {
                    leaked.customName = entity.getCustomName().getString();
                }
            }
        }
        
        // Update render cache
        if (System.currentTimeMillis() - lastRenderUpdate > 100) {
            renderCache.clear();
            for (LeakedEntity entity : leakedEntities.values()) {
                if (entity.isRecentlyActive()) {
                    renderCache.put(entity.id, new CachedEntityRender(entity));
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
        if (!isRunning.get() || mc.player == null) return;
        
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        
        for (CachedEntityRender render : renderCache.values()) {
            double dx = render.boundingBox.getCenter().x - cameraPos.x;
            double dz = render.boundingBox.getCenter().z - cameraPos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > 2500) continue;
            
            event.renderer.box(
                render.boundingBox.minX, render.boundingBox.minY, render.boundingBox.minZ,
                render.boundingBox.maxX, render.boundingBox.maxY, render.boundingBox.maxZ,
                new Color(render.color.r, render.color.g, render.color.b, render.color.a / 2),
                render.color,
                ShapeMode.Both, 0
            );
            
            if (distSq < 400) {
                String label = render.label;
                double labelY = render.boundingBox.maxY + 0.5;
                event.renderer.text(label, render.boundingBox.getCenter().x, labelY, render.boundingBox.getCenter().z, true, render.color);
            }
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
        
        // Register exploits
        activeExploits.add(new ReflectionBypassExploit());
        activeExploits.add(new PlayerProximityExploit());
        activeExploits.add(new EntitySpawnForcerExploit());
        activeExploits.add(new AntiCheatConfusionExploit());
        activeExploits.add(new EntityTrackerExploit());
        activeExploits.add(new SpawnerDetectorExploit());
        activeExploits.add(new ContainerScannerExploit());
        activeExploits.add(new HiddenItemDetectorExploit());
        
        exploitExecutor = Executors.newFixedThreadPool(MAX_EXPLOIT_THREADS, r -> {
            Thread t = new Thread(r, "EntityDebug-Exploit");
            t.setDaemon(true);
            return t;
        });
        
        // Run exploits
        for (Exploit exploit : activeExploits) {
            exploitExecutor.submit(() -> {
                if (exploit.isAvailable() && isRunning.get()) {
                    activeExploitCount.incrementAndGet();
                    exploit.execute();
                    activeExploitCount.decrementAndGet();
                }
            });
        }
        
        ChatUtils.info("EntityDebug", "§c§lENTITY DEBUG ACTIVATED");
        ChatUtils.info("EntityDebug", "§7└─ Exploits: §a" + activeExploits.size());
        ChatUtils.info("EntityDebug", "§7└─ Status: §a§lLEAKING ENTITIES");
        
        mc.player.sendMessage(Text.literal("§8[§c§lED§8] §7Entity Debug §a§lACTIVE"), false);
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
        renderCache.clear();
        
        ChatUtils.info("EntityDebug", "§cEntity Debug deactivated");
    }
    
    @Override
    public String getInfoString() {
        int total = leakedEntities.size();
        int active = activeExploitCount.get();
        return String.format("§a%d §7entities §8| §7%d exploits", total, active);
    }
}
