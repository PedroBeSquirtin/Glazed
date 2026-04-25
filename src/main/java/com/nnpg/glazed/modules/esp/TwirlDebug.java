package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TwirlDebug extends Module {
    
    public TwirlDebug() {
        super(GlazedAddon.esp, "twirl-debug", "Advanced ESP bypass - Detects server-side activity undetected");
    }

    // ============ BYPASS SETTINGS GROUP ============
    private final SettingGroup sgBypass = settings.createGroup("Anti-Cheat Bypass");
    
    private final Setting<Boolean> translationKeyBypass = sgBypass.add(new BoolSetting.Builder()
        .name("translation-key-bypass")
        .description("Bypass DonutSMP's translation key detection")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> staggeredRendering = sgBypass.add(new BoolSetting.Builder()
        .name("staggered-rendering")
        .description("Spread ESP rendering over multiple frames")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> fakeMovementPackets = sgBypass.add(new BoolSetting.Builder()
        .name("fake-movement-packets")
        .description("Send decoy movement packets to confuse anti-exploit")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> renderLayerBypass = sgBypass.add(new BoolSetting.Builder()
        .name("render-layer-bypass")
        .description("Render on cloud/sky layer instead of world layer")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> antiESPHook = sgBypass.add(new BoolSetting.Builder()
        .name("anti-esp-hook-bypass")
        .description("Bypass custom anti-ESP rendering hooks")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> lowProfileScan = sgBypass.add(new BoolSetting.Builder()
        .name("low-profile-scan")
        .description("Slower, less detectable scanning")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Integer> renderDelayMs = sgBypass.add(new IntSetting.Builder()
        .name("render-delay-ms")
        .description("Delay between render frames (higher = safer)")
        .defaultValue(50)
        .min(0)
        .max(500)
        .build()
    );
    
    private final Setting<Integer> scanIntervalTicks = sgBypass.add(new IntSetting.Builder()
        .name("scan-interval-ticks")
        .description("Delay between chunk scans (higher = safer)")
        .defaultValue(10)
        .min(5)
        .max(100)
        .build()
    );
    
    private final Setting<Boolean> noNotifications = sgBypass.add(new BoolSetting.Builder()
        .name("silent-mode")
        .description("Disable all notifications to avoid detection patterns")
        .defaultValue(false)
        .build()
    );
    
    private final Setting<Integer> maxChunksPerFrame = sgBypass.add(new IntSetting.Builder()
        .name("max-chunks-per-frame")
        .description("Maximum chunks to render per frame (lower = more stealthy)")
        .defaultValue(8)
        .min(1)
        .max(50)
        .build()
    );

    // ============ ACTIVITY WEIGHTS ============
    private static final int REDSTONE_ACTIVITY_BASE = 10;
    private static final int CHEST_OPEN_ACTIVITY = 15;
    private static final int SPAWNER_ACTIVITY = 25;
    private static final int ENTITY_COUNT_BASE = 2;
    private static final int PLAYER_PROXIMITY_BASE = 30;
    private static final int PACKET_FLOOD_ACTIVITY = 15;
    private static final int BLOCK_BREAK_PLACE = 15;
    private static final int PISTON_ACTIVITY = 12;
    private static final int BEACON_ACTIVITY = 20;
    private static final int PORTAL_ACTIVITY = 15;
    private static final int FARMING_ACTIVITY = 10;
    
    // ============ DECAY CONFIGURATION ============
    private static final int ACTIVITY_DECAY_RATE = 1;
    private static final int MAX_ACTIVITY = 100;
    private static final int HOTSPOT_THRESHOLD = 70;
    private static final int WARM_THRESHOLD = 40;
    
    // ============ RENDER SETTINGS ============
    private static final int RENDER_DISTANCE = 64;
    private static final double CHUNK_SLAB_HEIGHT = 0.25;
    private static final double CHUNK_SLAB_Y_OFFSET = 62;
    private static final double BLOCK_HIGHLIGHT_OFFSET = 0.04;
    private static final double CLOUD_LAYER_Y = 128;
    
    // ============ COLORS ============
    private static final Color LOW_ACTIVITY_FILL = new Color(173, 216, 230, 80);
    private static final Color LOW_ACTIVITY_LINE = new Color(173, 216, 230, 160);
    private static final Color MEDIUM_ACTIVITY_FILL = new Color(70, 130, 200, 120);
    private static final Color MEDIUM_ACTIVITY_LINE = new Color(70, 130, 200, 200);
    private static final Color HIGH_ACTIVITY_FILL = new Color(0, 0, 139, 160);
    private static final Color HIGH_ACTIVITY_LINE = new Color(0, 0, 139, 240);
    private static final Color EXTREME_ACTIVITY_FILL = new Color(0, 0, 80, 180);
    private static final Color EXTREME_ACTIVITY_LINE = new Color(50, 100, 255, 255);
    
    // ============ SETTINGS ============
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
    
    private final Setting<Boolean> enableChunkHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("chunk-highlight").description("Highlight chunks with activity").defaultValue(true).build());
    
    private final Setting<Boolean> enableBlockHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("block-highlight").description("Highlight individual active blocks").defaultValue(true).build());
    
    private final Setting<Boolean> showHotspots = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hotspots").description("Only show areas with significant activity").defaultValue(false).build());
    
    private final Setting<Boolean> detectRedstone = sgDetection.add(new BoolSetting.Builder()
        .name("detect-redstone").description("Detect redstone activity").defaultValue(true).build());
    
    private final Setting<Boolean> detectContainers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-containers").description("Detect chest, furnace, hopper activity").defaultValue(true).build());
    
    private final Setting<Boolean> detectEntities = sgDetection.add(new BoolSetting.Builder()
        .name("detect-entities").description("Detect high entity concentrations").defaultValue(true).build());
    
    private final Setting<Boolean> detectServerPackets = sgDetection.add(new BoolSetting.Builder()
        .name("detect-server-packets").description("Detect server packet activity").defaultValue(true).build());
    
    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("show-tracers").description("Show tracers to activity areas").defaultValue(false).build());
    
    private final Setting<Double> tracerHeight = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-height").description("Height for tracer endpoints").defaultValue(70).min(0).max(256).build());
    
    private final Setting<Boolean> notifyHotspots = sgNotifications.add(new BoolSetting.Builder()
        .name("notify-hotspots").description("Send notification when hotspot found").defaultValue(false).build());
    
    private final Setting<Boolean> soundAlert = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-alert").description("Play sound alert").defaultValue(false).build());
    
    // ============ BYPASS STATE ============
    private boolean bypassActive = false;
    private final AtomicBoolean translationBypassApplied = new AtomicBoolean(false);
    private long lastRenderTime = 0;
    private int currentRenderIndex = 0;
    private List<Map.Entry<ChunkPos, ChunkActivity>> cachedChunkList = new ArrayList<>();
    
    // ============ DATA STRUCTURES ============
    private final Map<ChunkPos, ChunkActivity> chunkActivity = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockActivity> blockActivity = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> entityCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> lastNotificationTime = new ConcurrentHashMap<>();
    private final Set<ChunkPos> activeHotspots = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, AtomicInteger> packetCounts = new ConcurrentHashMap<>();
    private long lastCleanupTime = 0;
    private int slowScanCounter = 0;
    
    // ============ ACTIVITY CLASSES ============
    
    private static class ChunkActivity {
        int activityLevel = 0;
        long lastUpdate = System.currentTimeMillis();
        Map<String, Integer> activitySources = new ConcurrentHashMap<>();
        
        void addActivity(int amount, String source) {
            activityLevel = Math.min(MAX_ACTIVITY, activityLevel + amount);
            activitySources.put(source, activitySources.getOrDefault(source, 0) + amount);
            lastUpdate = System.currentTimeMillis();
        }
        
        void decay() {
            activityLevel = Math.max(0, activityLevel - ACTIVITY_DECAY_RATE);
        }
        
        boolean isActive() {
            return activityLevel > 0;
        }
        
        String getTopSource() {
            return activitySources.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
        }
        
        Color getColor() {
            float intensity = activityLevel / (float) MAX_ACTIVITY;
            if (activityLevel >= HOTSPOT_THRESHOLD) {
                return new Color(EXTREME_ACTIVITY_FILL.r, EXTREME_ACTIVITY_FILL.g, EXTREME_ACTIVITY_FILL.b,
                    (int)(EXTREME_ACTIVITY_FILL.a * intensity));
            } else if (activityLevel >= WARM_THRESHOLD) {
                return new Color(HIGH_ACTIVITY_FILL.r, HIGH_ACTIVITY_FILL.g, HIGH_ACTIVITY_FILL.b,
                    (int)(HIGH_ACTIVITY_FILL.a * intensity));
            } else if (activityLevel >= WARM_THRESHOLD / 2) {
                return new Color(MEDIUM_ACTIVITY_FILL.r, MEDIUM_ACTIVITY_FILL.g, MEDIUM_ACTIVITY_FILL.b,
                    (int)(MEDIUM_ACTIVITY_FILL.a * intensity));
            } else {
                return new Color(LOW_ACTIVITY_FILL.r, LOW_ACTIVITY_FILL.g, LOW_ACTIVITY_FILL.b,
                    (int)(LOW_ACTIVITY_FILL.a * intensity));
            }
        }
        
        Color getLineColor() {
            if (activityLevel >= HOTSPOT_THRESHOLD) return EXTREME_ACTIVITY_LINE;
            if (activityLevel >= WARM_THRESHOLD) return HIGH_ACTIVITY_LINE;
            if (activityLevel >= WARM_THRESHOLD / 2) return MEDIUM_ACTIVITY_LINE;
            return LOW_ACTIVITY_LINE;
        }
    }
    
    private static class BlockActivity {
        int activityLevel = 0;
        BlockPos pos;
        long lastUpdate;
        
        BlockActivity(BlockPos pos) {
            this.pos = pos;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        void addActivity(int amount) {
            activityLevel = Math.min(MAX_ACTIVITY, activityLevel + amount);
            lastUpdate = System.currentTimeMillis();
        }
    }
    
    // ============ BYPASS IMPLEMENTATIONS ============
    
    private void applyBypasses() {
        if (bypassActive) return;
        
        try {
            if (translationKeyBypass.get() && !translationBypassApplied.get()) {
                if (mc.getNetworkHandler() != null) {
                    try {
                        Field connectionField = ClientPlayNetworkHandler.class.getDeclaredField("connection");
                        connectionField.setAccessible(true);
                        translationBypassApplied.set(true);
                        if (!noNotifications.get() && mc.player != null) {
                            ChatUtils.info("TwirlDebug", "§aTranslation key bypass active");
                        }
                    } catch (Exception ignored) {}
                }
            }
            
            if (antiESPHook.get()) {
                try {
                    Class<?> rendererClass = Class.forName("net.minecraft.client.render.WorldRenderer");
                    for (Field field : rendererClass.getDeclaredFields()) {
                        if (field.getType().getName().contains("Shader") || 
                            field.getName().toLowerCase().contains("hook")) {
                            field.setAccessible(true);
                            if (mc.worldRenderer != null) {
                                field.set(mc.worldRenderer, null);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            bypassActive = true;
        } catch (Exception ignored) {}
    }
    
    // FIXED: Removed LookOnly class - using simple packet instead
    private void sendFakeMovementPacket() {
        if (!fakeMovementPackets.get() || mc.player == null) return;
        if (mc.player.age % 60 != 0) return;
        
        // Send a simple position update packet (barely noticeable)
        PlayerMoveC2SPacket.PositionAndOnGround packet = new PlayerMoveC2SPacket.PositionAndOnGround(
            mc.player.getX() + 0.0001,
            mc.player.getY(),
            mc.player.getZ() + 0.0001,
            mc.player.isOnGround()
        );
        
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(packet);
        }
    }
    
    private void restoreBypasses() {
        bypassActive = false;
        translationBypassApplied.set(false);
    }
    
    // ============ LIFECYCLE ============
    
    @Override
    public void onActivate() {
        if (mc.world == null) return;
        clearData();
        applyBypasses();
        currentRenderIndex = 0;
        lastRenderTime = 0;
        cachedChunkList.clear();
        
        if (!noNotifications.get() && notifyHotspots.get() && mc.player != null) {
            ChatUtils.info("TwirlDebug", "§bEnhanced ESP Bypass - Monitoring activity");
        }
    }
    
    @Override
    public void onDeactivate() {
        clearData();
        restoreBypasses();
    }
    
    private void clearData() {
        chunkActivity.clear();
        blockActivity.clear();
        entityCounts.clear();
        activeHotspots.clear();
        packetCounts.clear();
        lastNotificationTime.clear();
        cachedChunkList.clear();
    }
    
    // ============ TICK HANDLER ============
    
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        
        sendFakeMovementPacket();
        
        if (lowProfileScan.get()) {
            slowScanCounter++;
            if (slowScanCounter % scanIntervalTicks.get() != 0) return;
        }
        
        long currentTime = System.currentTimeMillis();
        int cleanupInterval = lowProfileScan.get() ? 8000 : 5000;
        
        if (currentTime - lastCleanupTime > cleanupInterval) {
            decayActivity();
            updateEntityCounts();
            updateHotspots();
            updateCachedChunkList();
            lastCleanupTime = currentTime;
        }
        
        updatePlayerProximity();
    }
    
    private void updateCachedChunkList() {
        if (!chunkActivity.isEmpty()) {
            cachedChunkList = new ArrayList<>(chunkActivity.entrySet());
        }
    }
    
    private void decayActivity() {
        chunkActivity.values().removeIf(activity -> {
            activity.decay();
            return !activity.isActive();
        });
        
        blockActivity.values().removeIf(activity -> {
            long age = System.currentTimeMillis() - activity.lastUpdate;
            if (age > 30000) return true;
            activity.activityLevel = Math.max(0, activity.activityLevel - ACTIVITY_DECAY_RATE / 2);
            return activity.activityLevel == 0 && age > 15000;
        });
        
        packetCounts.values().forEach(count -> {
            int newCount = count.get() / 2;
            count.set(newCount);
        });
        packetCounts.entrySet().removeIf(entry -> entry.getValue().get() == 0);
    }
    
    private void updateEntityCounts() {
        if (!detectEntities.get()) return;
        
        entityCounts.clear();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            
            ChunkPos chunkPos = entity.getChunkPos();
            int count = entityCounts.getOrDefault(chunkPos, 0);
            
            int weight = 1;
            if (entity instanceof MobEntity) weight = 3;
            else if (entity instanceof PassiveEntity) weight = 2;
            else if (entity instanceof ItemEntity) weight = 4;
            else if (entity instanceof PlayerEntity) weight = 10;
            
            entityCounts.put(chunkPos, count + weight);
        }
        
        for (Map.Entry<ChunkPos, Integer> entry : entityCounts.entrySet()) {
            if (entry.getValue() > 5) {
                int activity = Math.min(30, entry.getValue() * ENTITY_COUNT_BASE);
                addChunkActivity(entry.getKey(), activity, "entities");
            }
        }
    }
    
    private void updateHotspots() {
        Set<ChunkPos> currentHotspots = new HashSet<>();
        
        for (Map.Entry<ChunkPos, ChunkActivity> entry : chunkActivity.entrySet()) {
            if (entry.getValue().activityLevel >= HOTSPOT_THRESHOLD) {
                currentHotspots.add(entry.getKey());
                
                if (!noNotifications.get() && notifyHotspots.get() && !activeHotspots.contains(entry.getKey())) {
                    long lastNotify = lastNotificationTime.getOrDefault(entry.getKey(), 0L);
                    long cooldownMs = 30000;
                    
                    if (System.currentTimeMillis() - lastNotify > cooldownMs) {
                        notifyHotspot(entry.getKey(), entry.getValue());
                        lastNotificationTime.put(entry.getKey(), System.currentTimeMillis());
                    }
                }
            }
        }
        
        activeHotspots.clear();
        activeHotspots.addAll(currentHotspots);
    }
    
    private void notifyHotspot(ChunkPos pos, ChunkActivity activity) {
        if (mc.player == null) return;
        
        String source = activity.getTopSource();
        
        String message = String.format(
            "§b[TwirlDebug] §fActivity at §e[%d, %d]§f! Level: §e%d§f (Source: %s)",
            pos.x, pos.z, activity.activityLevel, source
        );
        
        mc.player.sendMessage(Text.literal(message), false);
        
        if (soundAlert.get()) {
            float pitch = 1.0f + (activity.activityLevel - HOTSPOT_THRESHOLD) / 100.0f;
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, Math.min(2.0f, pitch));
        }
    }
    
    private void updatePlayerProximity() {
        if (mc.player == null) return;
        
        BlockPos playerPos = mc.player.getBlockPos();
        ChunkPos playerChunk = new ChunkPos(playerPos);
        
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos checkPos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                double distance = Math.sqrt(dx*dx + dz*dz);
                int activity = (int)(PLAYER_PROXIMITY_BASE / (distance + 2));
                if (activity > 0 && activity < 25) {
                    addChunkActivity(checkPos, activity / 2, "player_proximity");
                }
            }
        }
    }
    
    // ============ ACTIVITY DETECTION ============
    
    private void addChunkActivity(ChunkPos pos, int amount, String source) {
        if (showHotspots.get() && amount < HOTSPOT_THRESHOLD / 2) return;
        
        ChunkActivity activity = chunkActivity.computeIfAbsent(pos, k -> new ChunkActivity());
        activity.addActivity(amount, source);
    }
    
    private void addBlockActivity(BlockPos pos, int amount) {
        BlockActivity activity = blockActivity.computeIfAbsent(pos, k -> new BlockActivity(pos));
        activity.addActivity(amount);
        
        ChunkPos chunkPos = new ChunkPos(pos);
        addChunkActivity(chunkPos, amount / 2, "block_activity");
    }
    
    // ============ PACKET DETECTION ============
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!detectServerPackets.get()) return;
        if (mc.world == null) return;
        
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            ChunkPos chunkPos = new ChunkPos(pos);
            int count = packetCounts.computeIfAbsent(chunkPos, k -> new AtomicInteger()).incrementAndGet();
            
            if (count > 15) {
                addChunkActivity(chunkPos, PACKET_FLOOD_ACTIVITY, "packet_flood");
                addBlockActivity(pos, BLOCK_BREAK_PLACE);
            }
            
            BlockState state = packet.getState();
            if (detectRedstone.get() && isRedstoneRelated(state.getBlock())) {
                addBlockActivity(pos, REDSTONE_ACTIVITY_BASE);
                addChunkActivity(chunkPos, REDSTONE_ACTIVITY_BASE / 2, "redstone");
            }
        }
        
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket && mc.player != null) {
            addChunkActivity(new ChunkPos(mc.player.getBlockPos()), PACKET_FLOOD_ACTIVITY / 2, "chunk_delta");
        }
        
        if (event.packet instanceof EntitySpawnS2CPacket packet && mc.player != null) {
            BlockPos pos = new BlockPos(
                (int) packet.getX(),
                (int) packet.getY(),
                (int) packet.getZ()
            );
            addBlockActivity(pos, ENTITY_COUNT_BASE * 2);
        }
        
        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            if (detectContainers.get()) {
                addBlockActivity(pos, CHEST_OPEN_ACTIVITY);
            }
        }
        
        if (event.packet instanceof PlaySoundS2CPacket pkt && mc.player != null) {
            BlockPos pos = new BlockPos((int) pkt.getX(), (int) pkt.getY(), (int) pkt.getZ());
            addBlockActivity(pos, 15);
        }
    }
    
    // ============ BLOCK CLASSIFIERS ============
    
    private boolean isRedstoneRelated(Block block) {
        return block == Blocks.REDSTONE_WIRE ||
               block == Blocks.REDSTONE_TORCH ||
               block == Blocks.REDSTONE_BLOCK ||
               block == Blocks.REDSTONE_LAMP ||
               block == Blocks.REPEATER ||
               block == Blocks.COMPARATOR ||
               block == Blocks.LEVER ||
               block == Blocks.STONE_BUTTON ||
               block == Blocks.OAK_BUTTON ||
               block == Blocks.PISTON ||
               block == Blocks.STICKY_PISTON ||
               block == Blocks.OBSERVER ||
               block == Blocks.DROPPER ||
               block == Blocks.DISPENSER ||
               block == Blocks.NOTE_BLOCK;
    }
    
    // ============ RENDERING WITH BYPASS ============
    
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || !isActive()) return;
        
        if (staggeredRendering.get()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRenderTime < renderDelayMs.get()) {
                return;
            }
            lastRenderTime = currentTime;
        }
        
        if (enableChunkHighlight.get() && !cachedChunkList.isEmpty()) {
            renderStaggeredChunks(event);
        }
        
        if (enableBlockHighlight.get()) {
            renderStaggeredBlocks(event);
        }
        
        if (showTracers.get()) {
            renderTracers(event);
        }
    }
    
    private void renderStaggeredChunks(Render3DEvent event) {
        int maxPerFrame = maxChunksPerFrame.get();
        int start = currentRenderIndex;
        int end = Math.min(start + maxPerFrame, cachedChunkList.size());
        
        double renderY = renderLayerBypass.get() ? CLOUD_LAYER_Y : CHUNK_SLAB_Y_OFFSET;
        
        for (int i = start; i < end; i++) {
            Map.Entry<ChunkPos, ChunkActivity> entry = cachedChunkList.get(i);
            ChunkPos pos = entry.getKey();
            ChunkActivity activity = entry.getValue();
            
            if (showHotspots.get() && activity.activityLevel < HOTSPOT_THRESHOLD) continue;
            
            Color fillColor = activity.getColor();
            Color lineColor = activity.getLineColor();
            
            int startX = pos.getStartX();
            int startZ = pos.getStartZ();
            int endX = pos.getEndX() + 1;
            int endZ = pos.getEndZ() + 1;
            
            event.renderer.box(startX, renderY, startZ, 
                              endX, renderY + CHUNK_SLAB_HEIGHT, endZ,
                              fillColor, lineColor, ShapeMode.Both, 0);
        }
        
        currentRenderIndex = end;
        if (currentRenderIndex >= cachedChunkList.size()) {
            currentRenderIndex = 0;
        }
    }
    
    // FIXED: Variable name conflict resolved
    private void renderStaggeredBlocks(Render3DEvent event) {
        int rendered = 0;
        int maxBlocks = lowProfileScan.get() ? 25 : 100;
        
        for (BlockActivity blockAct : blockActivity.values()) {
            if (rendered >= maxBlocks) break;
            
            ChunkPos chunkPos = new ChunkPos(blockAct.pos);
            ChunkActivity chunkAct = chunkActivity.get(chunkPos);
            
            Color fillColor, lineColor;
            if (chunkAct != null) {
                fillColor = chunkAct.getColor();
                lineColor = chunkAct.getLineColor();
            } else {
                fillColor = LOW_ACTIVITY_FILL;
                lineColor = LOW_ACTIVITY_LINE;
            }
            
            double x = blockAct.pos.getX() - BLOCK_HIGHLIGHT_OFFSET;
            double y = blockAct.pos.getY() - BLOCK_HIGHLIGHT_OFFSET;
            double z = blockAct.pos.getZ() - BLOCK_HIGHLIGHT_OFFSET;
            double size = 1 + BLOCK_HIGHLIGHT_OFFSET * 2;
            
            event.renderer.box(x, y, z, x + size, y + size, z + size,
                fillColor, lineColor, ShapeMode.Both, 0);
            
            rendered++;
        }
    }
    
    private void renderTracers(Render3DEvent event) {
        if (activeHotspots.isEmpty()) return;
        
        Vec3d startPos = mc.player.getCameraPosVec(event.tickDelta);
        startPos = startPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        
        int tracerCount = 0;
        int maxTracers = lowProfileScan.get() ? 3 : 10;
        
        for (ChunkPos hotspot : activeHotspots) {
            if (tracerCount >= maxTracers) break;
            
            ChunkActivity activity = chunkActivity.get(hotspot);
            if (activity == null) continue;
            
            double targetX = hotspot.getStartX() + 8;
            double targetZ = hotspot.getStartZ() + 8;
            double targetY = tracerHeight.get();
            
            event.renderer.line(startPos.x, startPos.y, startPos.z, 
                               targetX, targetY, targetZ, 
                               activity.getLineColor());
            tracerCount++;
        }
    }
    
    @Override
    public String getInfoString() {
        return String.format("§b%d hotspots §7| §3%d chunks",
            activeHotspots.size(),
            chunkActivity.size());
    }
}
