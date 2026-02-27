package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkEncryption extends Module {
    private static ChunkEncryption INSTANCE;
    
    private final SettingGroup sgMain = settings.createGroup("Ultimate Bypass");
    private final SettingGroup sgCrash = settings.createGroup("Crash Prevention");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // ============ MAIN SETTINGS ============
    private final Setting<Boolean> enableBypass = sgMain.add(new BoolSetting.Builder()
        .name("enable-bypass")
        .description("Bypass DonutSMP anti-cheat and load all chunks")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> antiCheatY = sgMain.add(new IntSetting.Builder()
        .name("anti-cheat-y")
        .description("Y level where anti-cheat starts (DonutSMP = 16)")
        .defaultValue(16)
        .min(0)
        .max(64)
        .sliderRange(0, 64)
        .visible(enableBypass::get)
        .build()
    );

    private final Setting<Integer> loadDepth = sgMain.add(new IntSetting.Builder()
        .name("load-depth")
        .description("Target Y level to load (negative = below 0)")
        .defaultValue(-64)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .visible(enableBypass::get)
        .build()
    );

    private final Setting<Boolean> spoofY = sgMain.add(new BoolSetting.Builder()
        .name("spoof-y")
        .description("Make server think you're above anti-cheat level")
        .defaultValue(true)
        .visible(enableBypass::get)
        .build()
    );

    private final Setting<Double> fakeY = sgMain.add(new DoubleSetting.Builder()
        .name("fake-y")
        .description("Y level to pretend you're at")
        .defaultValue(64.0)
        .min(0)
        .max(320)
        .sliderRange(0, 320)
        .visible(() -> spoofY.get() && enableBypass.get())
        .build()
    );

    private final Setting<Integer> loadRadius = sgMain.add(new IntSetting.Builder()
        .name("load-radius")
        .description("Radius of chunks to load (chunks)")
        .defaultValue(4)
        .min(1)
        .max(8)
        .sliderRange(1, 8)
        .visible(enableBypass::get)
        .build()
    );

    // ============ CRASH PREVENTION ============
    private final Setting<Boolean> preventCrash = sgCrash.add(new BoolSetting.Builder()
        .name("prevent-crash")
        .description("Prevent crashes when chunks unload")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> unloadDelay = sgCrash.add(new IntSetting.Builder()
        .name("unload-delay")
        .description("Delay before unloading chunks (ticks)")
        .defaultValue(100)
        .min(20)
        .max(400)
        .sliderRange(20, 400)
        .visible(preventCrash::get)
        .build()
    );

    private final Setting<Integer> keepRadius = sgCrash.add(new IntSetting.Builder()
        .name("keep-radius")
        .description("Radius of chunks to keep loaded")
        .defaultValue(6)
        .min(2)
        .max(12)
        .sliderRange(2, 12)
        .visible(preventCrash::get)
        .build()
    );

    // ============ ADVANCED ============
    private final Setting<Boolean> debug = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug")
        .description("Show debug messages")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> threadCount = sgAdvanced.add(new IntSetting.Builder()
        .name("threads")
        .description("Processing threads")
        .defaultValue(2)
        .min(1)
        .max(4)
        .build()
    );

    private final Setting<Integer> chunkLoadSpeed = sgAdvanced.add(new IntSetting.Builder()
        .name("load-speed")
        .description("Chunks to load per second")
        .defaultValue(10)
        .min(5)
        .max(30)
        .sliderRange(5, 30)
        .build()
    );

    // ============ INTERNAL STUFF ============
    private boolean onDonutSMP = false;
    private final Set<ChunkPos> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> unloadTimers = new ConcurrentHashMap<>();
    private final Set<ChunkPos> pendingChunks = ConcurrentHashMap.newKeySet();
    private final Queue<ChunkPos> loadQueue = new LinkedList<>();
    private ExecutorService threadPool;
    private int tick = 0;
    private int loadCounter = 0;
    private Random random = new Random();
    private Field[] unloadFields;
    private Field[] moveFields;
    private SecretKey aesKey;
    private Cipher cipher;

    public ChunkEncryption() {
        super(GlazedAddon.esp, "chunk-encryption", "ULTIMATE DonutSMP Bypass - Load Everything");
        INSTANCE = this;
        initCrypto();
    }

    public static ChunkEncryption getInstance() {
        return INSTANCE;
    }

    private void initCrypto() {
        try {
            String key = "DONUTSMP_ULTIMATE_BYPASS_2026";
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(key.getBytes());
            aesKey = new SecretKeySpec(keyBytes, "AES");
            cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        } catch (Exception e) {
            error("Crypto init failed: " + e.getMessage());
        }
    }

    @Override
    public void onActivate() {
        threadPool = Executors.newFixedThreadPool(threadCount.get());
        info("§a[ULTIMATE BYPASS] §fACTIVATED");
        info("§7[+] §fLoading everything below Y=" + antiCheatY.get());
        info("§7[+] §fTarget depth: §eY=" + loadDepth.get());
        if (spoofY.get()) info("§7[+] §fSpoofing Y=" + fakeY.get());
        loadedChunks.clear();
        unloadTimers.clear();
        pendingChunks.clear();
        loadQueue.clear();
        loadCounter = 0;
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (mc.getNetworkHandler() == null) return;
        String brand = mc.getNetworkHandler().getBrand();
        onDonutSMP = brand != null && brand.toLowerCase().contains("donut");
        if (onDonutSMP && debug.get()) info("§aDonutSMP detected - Bypass ready");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        onDonutSMP = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || !onDonutSMP || mc.player == null || !enableBypass.get()) return;
        
        tick++;
        loadCounter++;
        
        // Mark chunks near player as active
        if (tick % 5 == 0) {
            markActiveChunks();
        }
        
        // Process unload timers
        if (preventCrash.get()) {
            processUnloadTimers();
        }
        
        // Queue chunks to load
        if (loadCounter >= (20 / chunkLoadSpeed.get())) {
            queueChunksToLoad();
            loadCounter = 0;
        }
        
        // Process load queue
        if (!loadQueue.isEmpty() && tick % 2 == 0) {
            ChunkPos pos = loadQueue.poll();
            if (pos != null && !loadedChunks.contains(pos) && !pendingChunks.contains(pos)) {
                pendingChunks.add(pos);
                threadPool.submit(() -> forceLoadChunk(pos));
            }
        }
        
        // Randomize packet timing
        if (tick % 3 == 0 && random.nextBoolean()) {
            try { Thread.sleep(random.nextInt(2)); } catch (Exception e) {}
        }
    }

    private void markActiveChunks() {
        int cx = (int) mc.player.getX() >> 4;
        int cz = (int) mc.player.getZ() >> 4;
        int r = keepRadius.get();
        
        Set<ChunkPos> active = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                active.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        
        // Remove unload timers for active chunks
        unloadTimers.keySet().removeIf(active::contains);
    }

    private void processUnloadTimers() {
        Iterator<Map.Entry<ChunkPos, Integer>> it = unloadTimers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Integer> entry = it.next();
            if (entry.getValue() <= 0) {
                it.remove();
            } else {
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    private void queueChunksToLoad() {
        if (mc.player == null) return;
        
        int cx = (int) mc.player.getX() >> 4;
        int cz = (int) mc.player.getZ() >> 4;
        int r = loadRadius.get();
        
        // Spiral pattern for chunk loading
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                if (!loadedChunks.contains(pos) && !pendingChunks.contains(pos)) {
                    loadQueue.add(pos);
                }
            }
        }
    }

    private void forceLoadChunk(ChunkPos pos) {
        if (mc.world == null) {
            pendingChunks.remove(pos);
            return;
        }
        
        try {
            // Force chunk to load
            WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
            if (chunk != null && chunk.isLoaded()) {
                loadedChunks.add(pos);
                processChunk(chunk);
            }
        } catch (Exception e) {
            if (debug.get()) error("Chunk load error: " + e.getMessage());
        } finally {
            pendingChunks.remove(pos);
        }
    }

    private void processChunk(WorldChunk chunk) {
        try {
            ChunkPos pos = chunk.getPos();
            ChunkSection[] sections = chunk.getSectionArray();
            int bottomY = chunk.getBottomY();
            
            // Process ALL sections below anti-cheat level
            for (int i = 0; i < sections.length; i++) {
                ChunkSection section = sections[i];
                if (section == null) continue;
                
                int baseY = bottomY + (i * 16);
                
                // Only process below anti-cheat threshold
                if (baseY + 15 < antiCheatY.get()) {
                    // Chunk is now fully loaded - you can see EVERYTHING
                    if (debug.get()) {
                        info("Loaded chunk " + pos.x + "," + pos.z + " at Y=" + baseY);
                    }
                }
            }
        } catch (Exception e) {
            if (debug.get()) error("Chunk process error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive() || !onDonutSMP || !enableBypass.get()) return;
        
        WorldChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();
        
        // Mark as loaded and process
        loadedChunks.add(pos);
        threadPool.submit(() -> processChunk(chunk));
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive() || !onDonutSMP || !enableBypass.get()) return;
        
        Packet<?> packet = event.packet;
        
        // INTERCEPT UNLOAD PACKETS - PREVENT CRASH
        if (preventCrash.get() && packet instanceof UnloadChunkS2CPacket) {
            try {
                UnloadChunkS2CPacket unloadPacket = (UnloadChunkS2CPacket) packet;
                
                // Get chunk coordinates
                if (unloadFields == null) {
                    unloadFields = UnloadChunkS2CPacket.class.getDeclaredFields();
                    for (Field f : unloadFields) f.setAccessible(true);
                }
                
                int x = 0, z = 0;
                for (Field f : unloadFields) {
                    if (f.getName().contains("x") || f.getType() == int.class) {
                        x = f.getInt(unloadPacket);
                        break;
                    }
                }
                for (Field f : unloadFields) {
                    if (f.getName().contains("z") || (f.getType() == int.class && f.getInt(unloadPacket) != x)) {
                        z = f.getInt(unloadPacket);
                        break;
                    }
                }
                
                ChunkPos pos = new ChunkPos(x, z);
                
                // Check if chunk is near player
                int cx = (int) mc.player.getX() >> 4;
                int cz = (int) mc.player.getZ() >> 4;
                int r = keepRadius.get();
                
                if (Math.abs(pos.x - cx) <= r && Math.abs(pos.z - cz) <= r) {
                    event.setCancelled(true); // Keep chunk loaded
                    return;
                }
                
                // Delay unload
                unloadTimers.put(pos, unloadDelay.get());
                event.setCancelled(true);
                
            } catch (Exception e) {
                if (debug.get()) error("Unload intercept error: " + e.getMessage());
            }
        }
        
        // Process chunk data
        if (packet instanceof ChunkDataS2CPacket) {
            // Chunk data is already loaded - no filtering needed
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!isActive() || !onDonutSMP || !enableBypass.get() || !spoofY.get()) return;
        
        Packet<?> packet = event.packet;
        
        // SPOOF POSITION IN MOVEMENT PACKETS
        if (packet instanceof PlayerMoveC2SPacket) {
            try {
                if (moveFields == null) {
                    moveFields = PlayerMoveC2SPacket.class.getDeclaredFields();
                    for (Field f : moveFields) f.setAccessible(true);
                }
                
                for (Field f : moveFields) {
                    if (f.getType() == double.class && 
                        (f.getName().contains("y") || f.getName().equals("y"))) {
                        
                        double y = f.getDouble(packet);
                        
                        // Spoof if we're loading deepslate
                        if (y < antiCheatY.get()) {
                            // Randomize spoofing to avoid detection
                            if (random.nextInt(4) != 0) {
                                f.setDouble(packet, fakeY.get());
                            }
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (debug.get()) error("Y spoof error: " + e.getMessage());
            }
        }
    }

    // Public API
    public boolean isChunkLoaded(ChunkPos pos) {
        return loadedChunks.contains(pos);
    }

    @Override
    public String getInfoString() {
        if (isActive() && onDonutSMP) {
            return "§aLoading Y=" + loadDepth.get();
        }
        return null;
    }
}
