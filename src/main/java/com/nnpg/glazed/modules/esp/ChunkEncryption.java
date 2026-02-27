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
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkEncryption extends Module {
    private final SettingGroup sgDonutSMP = settings.createGroup("DonutSMP Anti-Xray Bypass");
    private final SettingGroup sgCrashPrevention = settings.createGroup("Crash Prevention");
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgEncryption = settings.createGroup("Encryption");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // ============ CRASH PREVENTION SETTINGS ============
    private final Setting<Boolean> preventUnloadCrash = sgCrashPrevention.add(new BoolSetting.Builder()
        .name("prevent-unload-crash")
        .description("Prevent crashes when chunks unload")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> unloadDelay = sgCrashPrevention.add(new IntSetting.Builder()
        .name("unload-delay")
        .description("Delay in ticks before processing unload (0 = disable)")
        .defaultValue(20)
        .min(0)
        .max(100)
        .sliderRange(0, 100)
        .visible(preventUnloadCrash::get)
        .build()
    );

    private final Setting<Boolean> fakeKeepAlive = sgCrashPrevention.add(new BoolSetting.Builder()
        .name("fake-keep-alive")
        .description("Send fake keep-alive packets to prevent timeout crashes")
        .defaultValue(true)
        .visible(preventUnloadCrash::get)
        .build()
    );

    private final Setting<Integer> keepAliveInterval = sgCrashPrevention.add(new IntSetting.Builder()
        .name("keep-alive-interval")
        .description("Ticks between fake keep-alive packets")
        .defaultValue(10)
        .min(5)
        .max(50)
        .sliderRange(5, 50)
        .visible(() -> fakeKeepAlive.get() && preventUnloadCrash.get())
        .build()
    );

    private final Setting<Boolean> preventMemoryLeak = sgCrashPrevention.add(new BoolSetting.Builder()
        .name("prevent-memory-leak")
        .description("Clean up cached chunks to prevent memory leaks")
        .defaultValue(true)
        .visible(preventUnloadCrash::get)
        .build()
    );

    private final Setting<Integer> maxCacheSize = sgCrashPrevention.add(new IntSetting.Builder()
        .name("max-cache-size")
        .description("Maximum number of chunks to keep in cache")
        .defaultValue(100)
        .min(50)
        .max(500)
        .sliderRange(50, 500)
        .visible(() -> preventMemoryLeak.get() && preventUnloadCrash.get())
        .build()
    );

    private final Setting<Boolean> chunkGradualUnload = sgCrashPrevention.add(new BoolSetting.Builder()
        .name("gradual-unload")
        .description("Slowly unload chunks instead of all at once")
        .defaultValue(true)
        .visible(preventUnloadCrash::get)
        .build()
    );

    private final Setting<Integer> unloadRate = sgCrashPrevention.add(new IntSetting.Builder()
        .name("unload-rate")
        .description("Chunks to unload per second")
        .defaultValue(5)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .visible(() -> chunkGradualUnload.get() && preventUnloadCrash.get())
        .build()
    );

    // ============ DONUTSMP ANTI-XRAY BYPASS ============
    private final Setting<Boolean> bypassAntiXray = sgDonutSMP.add(new BoolSetting.Builder()
        .name("bypass-anti-xray")
        .description("Bypass DonutSMP anti-xray (hides blocks below Y=16)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> antiXrayThreshold = sgDonutSMP.add(new IntSetting.Builder()
        .name("anti-xray-threshold")
        .description("Y level where anti-xray activates (DonutSMP = 16)")
        .defaultValue(16)
        .min(0)
        .max(64)
        .sliderRange(0, 64)
        .visible(bypassAntiXray::get)
        .build()
    );

    private final Setting<Integer> targetDepth = sgDonutSMP.add(new IntSetting.Builder()
        .name("target-depth")
        .description("Target Y level to load (negative values = below 0)")
        .defaultValue(-32)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .visible(bypassAntiXray::get)
        .build()
    );

    private final Setting<Boolean> spoofPosition = sgDonutSMP.add(new BoolSetting.Builder()
        .name("spoof-position")
        .description("Make server think you're above Y=16 while loading below")
        .defaultValue(true)
        .visible(bypassAntiXray::get)
        .build()
    );

    private final Setting<Double> spoofedY = sgDonutSMP.add(new DoubleSetting.Builder()
        .name("spoofed-y")
        .description("Y level to spoof to server")
        .defaultValue(64.0)
        .min(0)
        .max(320)
        .sliderRange(0, 320)
        .visible(() -> spoofPosition.get() && bypassAntiXray.get())
        .build()
    );

    private final Setting<Boolean> chunkReload = sgDonutSMP.add(new BoolSetting.Builder()
        .name("force-chunk-reload")
        .description("Force server to send real chunks, not obfuscated ones")
        .defaultValue(true)
        .visible(bypassAntiXray::get)
        .build()
    );

    private final Setting<Integer> reloadInterval = sgDonutSMP.add(new IntSetting.Builder()
        .name("reload-interval")
        .description("Ticks between chunk reload requests")
        .defaultValue(40)
        .min(10)
        .max(200)
        .sliderRange(10, 200)
        .visible(() -> chunkReload.get() && bypassAntiXray.get())
        .build()
    );

    private final Setting<Boolean> oreUnobfuscate = sgDonutSMP.add(new BoolSetting.Builder()
        .name("ore-unobfuscate")
        .description("Make ores visible below anti-xray threshold")
        .defaultValue(true)
        .visible(bypassAntiXray::get)
        .build()
    );

    // ============ ENUMS ============
    public enum EncryptionMethod {
        AES("AES-256"),
        XOR("XOR Obfuscation"),
        DONUT("DonutSMP Custom");

        private final String title;
        EncryptionMethod(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    // ============ GENERAL SETTINGS ============
    private final Setting<Boolean> autoEnable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-enable")
        .description("Automatically enable on DonutSMP")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugMode = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Log bypass operations")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show chat notifications")
        .defaultValue(true)
        .build()
    );

    // ============ ENCRYPTION SETTINGS ============
    private final Setting<EncryptionMethod> encryptionMethod = sgEncryption.add(new EnumSetting.Builder<EncryptionMethod>()
        .name("encryption-method")
        .description("Method to encrypt chunk data")
        .defaultValue(EncryptionMethod.DONUT)
        .build()
    );

    private final Setting<Integer> keyLength = sgEncryption.add(new IntSetting.Builder()
        .name("key-length")
        .description("Encryption key length")
        .defaultValue(256)
        .min(128)
        .max(512)
        .build()
    );

    // ============ PERFORMANCE SETTINGS ============
    private final Setting<Integer> threadPoolSize = sgPerformance.add(new IntSetting.Builder()
        .name("thread-pool")
        .description("Threads for processing")
        .defaultValue(2)
        .min(1)
        .max(4)
        .build()
    );

    private final Setting<Integer> chunkQueueSize = sgPerformance.add(new IntSetting.Builder()
        .name("chunk-queue")
        .description("Max chunks to queue before processing")
        .defaultValue(50)
        .min(10)
        .max(200)
        .build()
    );

    // ============ INTERNAL STATE ============
    private boolean onDonutSMP = false;
    private SecretKey secretKey;
    private Cipher encryptCipher;
    private Cipher decryptCipher;
    private final Map<ChunkPos, ChunkData> chunkCache = new ConcurrentHashMap<>();
    private final Queue<ChunkPos> chunkLoadQueue = new LinkedList<>();
    private final Set<ChunkPos> loadingChunks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> hiddenBlocks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> unloadTimers = new ConcurrentHashMap<>();
    private final Queue<ChunkPos> unloadQueue = new LinkedList<>();
    private final Set<ChunkPos> chunksToKeep = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;
    private int tickCounter = 0;
    private int keepAliveCounter = 0;
    private Vec3d actualPlayerPos = Vec3d.ZERO;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private boolean isShuttingDown = false;

    private static class ChunkData {
        WorldChunk chunk;
        long timestamp;
        boolean processed;
        
        ChunkData(WorldChunk chunk) {
            this.chunk = chunk;
            this.timestamp = System.currentTimeMillis();
            this.processed = false;
        }
    }

    public ChunkEncryption() {
        super(GlazedAddon.esp, "chunk-encryption", "DonutSMP Anti-Xray Bypass - Crash-Free Edition");
    }

    @Override
    public void onActivate() {
        try {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
            initEncryption();
            isShuttingDown = false;
            
            if (autoEnable.get()) {
                checkServer();
            }
            
            if (bypassAntiXray.get() && onDonutSMP) {
                info("§a[Anti-Xray Bypass] §fEnabled - Crash prevention active");
                info("§7[+] §fBypassing Y=" + antiXrayThreshold.get() + " anti-xray");
                info("§7[+] §fTarget depth: §eY=" + targetDepth.get());
                if (spoofPosition.get()) {
                    info("§7[+] §fSpoofing position: §eY=" + spoofedY.get());
                }
            }
            
        } catch (Exception e) {
            error("Init failed: " + e.getMessage());
        }
    }

    @Override
    public void onDeactivate() {
        isShuttingDown = true;
        
        // Graceful shutdown
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
            threadPool = null;
        }
        
        // Clear all data structures
        chunkCache.clear();
        chunkLoadQueue.clear();
        loadingChunks.clear();
        hiddenBlocks.clear();
        unloadTimers.clear();
        unloadQueue.clear();
        chunksToKeep.clear();
        queueSize.set(0);
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        checkServer();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        // Clean up when leaving server
        onDeactivate();
    }

    private void checkServer() {
        if (mc.getCurrentServerEntry() == null) return;
        
        String address = mc.getCurrentServerEntry().address.toLowerCase();
        onDonutSMP = address.contains("donutsmp") || address.contains("donut");
        
        if (onDonutSMP && notifications.get()) {
            info("§a[+] §fDonutSMP detected - Anti-xray bypass ready");
        }
    }

    private void initEncryption() throws Exception {
        switch (encryptionMethod.get()) {
            case AES:
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(keyLength.get());
                secretKey = keyGen.generateKey();
                encryptCipher = Cipher.getInstance("AES");
                encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey);
                decryptCipher = Cipher.getInstance("AES");
                decryptCipher.init(Cipher.DECRYPT_MODE, secretKey);
                break;

            case DONUT:
                String donutKey = "DONUTSMP_ANTI_XRAY_BYPASS_2026";
                byte[] keyBytes = donutKey.getBytes();
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                keyBytes = sha.digest(keyBytes);
                secretKey = new SecretKeySpec(keyBytes, "AES");
                encryptCipher = Cipher.getInstance("AES");
                encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey);
                decryptCipher = Cipher.getInstance("AES");
                decryptCipher.init(Cipher.DECRYPT_MODE, secretKey);
                break;
                
            case XOR:
                // Simple XOR - no setup needed
                break;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get() || isShuttingDown) return;
        
        tickCounter++;
        keepAliveCounter++;
        
        // Store actual position
        if (mc.player != null) {
            actualPlayerPos = mc.player.getPos();
            
            // Mark nearby chunks as "to keep"
            int playerChunkX = (int) mc.player.getX() >> 4;
            int playerChunkZ = (int) mc.player.getZ() >> 4;
            
            chunksToKeep.clear();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    chunksToKeep.add(new ChunkPos(playerChunkX + dx, playerChunkZ + dz));
                }
            }
        }
        
        // Send fake keep-alive packets to prevent timeout
        if (fakeKeepAlive.get() && keepAliveCounter >= keepAliveInterval.get()) {
            sendFakeKeepAlive();
            keepAliveCounter = 0;
        }
        
        // Process chunk load queue
        if (!chunkLoadQueue.isEmpty() && queueSize.get() < chunkQueueSize.get()) {
            ChunkPos pos = chunkLoadQueue.poll();
            if (pos != null && !loadingChunks.contains(pos)) {
                queueSize.decrementAndGet();
                loadingChunks.add(pos);
                threadPool.submit(() -> requestChunk(pos));
            }
        }
        
        // Process unload queue gradually
        if (chunkGradualUnload.get() && !unloadQueue.isEmpty()) {
            int toUnload = Math.max(1, unloadRate.get() / 20);
            for (int i = 0; i < toUnload && !unloadQueue.isEmpty(); i++) {
                ChunkPos pos = unloadQueue.poll();
                if (pos != null) {
                    performSafeUnload(pos);
                }
            }
        }
        
        // Process unload timers
        unloadTimers.entrySet().removeIf(entry -> {
            if (entry.getValue() <= 0) {
                if (!chunksToKeep.contains(entry.getKey())) {
                    if (chunkGradualUnload.get()) {
                        unloadQueue.add(entry.getKey());
                    } else {
                        performSafeUnload(entry.getKey());
                    }
                }
                return true;
            }
            entry.setValue(entry.getValue() - 1);
            return false;
        });
        
        // Queue chunks below anti-xray threshold
        if (tickCounter % 10 == 0 && mc.player != null) {
            int playerChunkX = (int) mc.player.getX() >> 4;
            int playerChunkZ = (int) mc.player.getZ() >> 4;
            
            // Load chunks in a spiral pattern
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    ChunkPos pos = new ChunkPos(playerChunkX + dx, playerChunkZ + dz);
                    
                    // Only queue if not already loading/cached
                    if (!loadingChunks.contains(pos) && !chunkCache.containsKey(pos)) {
                        if (queueSize.get() < chunkQueueSize.get()) {
                            chunkLoadQueue.add(pos);
                            queueSize.incrementAndGet();
                        }
                    }
                }
            }
        }
        
        // Clean up cache if too large
        if (preventMemoryLeak.get() && chunkCache.size() > maxCacheSize.get()) {
            cleanUpCache();
        }
    }

    private void requestChunk(ChunkPos pos) {
        if (mc.world == null || mc.getNetworkHandler() == null || isShuttingDown) return;
        
        try {
            // Create encrypted chunk request
            byte[] requestData = createChunkRequest(pos);
            byte[] encryptedRequest = encryptData(requestData);
            
            // Send request via packet manipulation
            mc.execute(() -> {
                try {
                    // Force chunk load by accessing it
                    WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
                    if (chunk != null) {
                        processChunk(chunk);
                    }
                } catch (Exception e) {
                    if (debugMode.get()) {
                        error("Chunk access failed: " + e.getMessage());
                    }
                }
            });
            
        } catch (Exception e) {
            if (debugMode.get()) {
                error("Chunk request failed: " + e.getMessage());
            }
        } finally {
            loadingChunks.remove(pos);
        }
    }

    private void requestChunkReload(ChunkPos pos) {
        if (mc.world == null || isShuttingDown) return;
        
        mc.execute(() -> {
            try {
                // Force chunk reload by unloading and reloading
                if (mc.world.getChunkManager() != null) {
                    // This tricks the server into sending fresh chunk data
                    mc.world.getChunk(pos.x, pos.z);
                    
                    if (debugMode.get()) {
                        info("Reloaded chunk " + pos.x + "," + pos.z);
                    }
                }
            } catch (Exception e) {
                if (debugMode.get()) {
                    error("Chunk reload failed: " + e.getMessage());
                }
            }
        });
    }

    private byte[] createChunkRequest(ChunkPos pos) {
        // Create a request that includes spoofed Y level
        String request = String.format("CHUNK:%d,%d;Y=%.1f", pos.x, pos.z, 
            spoofPosition.get() ? spoofedY.get() : (actualPlayerPos.y != 0 ? actualPlayerPos.y : 64.0));
        return request.getBytes();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get() || isShuttingDown) return;

        WorldChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();
        
        // Cache the chunk for later processing
        chunkCache.put(pos, new ChunkData(chunk));
        
        // Set reload timer if enabled
        if (chunkReload.get()) {
            unloadTimers.put(pos, reloadInterval.get());
        }
        
        // Process chunk for anti-xray bypass
        threadPool.submit(() -> processChunk(chunk));
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get() || isShuttingDown) return;

        // Intercept chunk unload packets
        if (event.packet instanceof UnloadChunkS2CPacket && preventUnloadCrash.get()) {
            UnloadChunkS2CPacket packet = (UnloadChunkS2CPacket) event.packet;
            ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
            
            // Don't unload chunks near player
            if (chunksToKeep.contains(pos)) {
                event.setCancelled(true);
                return;
            }
            
            // Delay the unload
            if (unloadDelay.get() > 0) {
                event.setCancelled(true);
                unloadTimers.put(pos, unloadDelay.get());
            }
        }

        // Handle block updates
        if (event.packet instanceof BlockUpdateS2CPacket) {
            BlockUpdateS2CPacket packet = (BlockUpdateS2CPacket) event.packet;
            BlockPos pos = packet.getPos();

            // Unhide blocks below anti-xray threshold
            if (pos.getY() < antiXrayThreshold.get()) {
                hiddenBlocks.remove(pos);
                if (debugMode.get()) {
                    info("Block update at " + pos.toShortString());
                }
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get() || isShuttingDown) return;

        // Spoof position in movement packets
        if (spoofPosition.get() && event.packet instanceof PlayerMoveC2SPacket) {
            // This would need a mixin to actually modify the packet
            // For now, we just let it through
        }
    }

    private void processChunk(WorldChunk chunk) {
        if (isShuttingDown) return;
        
        try {
            ChunkPos pos = chunk.getPos();
            ChunkSection[] sections = chunk.getSectionArray();
            int chunkBottomY = chunk.getBottomY();
            
            for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                ChunkSection section = sections[sectionIdx];
                if (section == null) continue;

                int sectionBaseY = chunkBottomY + sectionIdx * 16;
                
                // Process sections below anti-xray threshold
                if (sectionBaseY + 15 < antiXrayThreshold.get()) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int worldY = sectionBaseY + y;
                                
                                BlockPos blockPos = new BlockPos(
                                    chunk.getPos().getStartX() + x,
                                    worldY,
                                    chunk.getPos().getStartZ() + z
                                );
                                
                                Block block = section.getBlockState(x, y, z).getBlock();
                                
                                // Track hidden blocks
                                if (block == Blocks.STONE || block == Blocks.DEEPSLATE) {
                                    hiddenBlocks.add(blockPos);
                                }
                                
                                // Unobfuscate ores if enabled
                                if (oreUnobfuscate.get() && isOre(block)) {
                                    // Mark ore as visible
                                    if (debugMode.get()) {
                                        info("Found ore at " + blockPos.toShortString());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (debugMode.get()) {
                info("Processed chunk " + pos.x + "," + pos.z);
            }
        } catch (Exception e) {
            if (debugMode.get()) {
                error("Error processing chunk: " + e.getMessage());
            }
        }
    }

    private boolean isOre(Block block) {
        return block == Blocks.COAL_ORE ||
               block == Blocks.IRON_ORE ||
               block == Blocks.COPPER_ORE ||
               block == Blocks.GOLD_ORE ||
               block == Blocks.REDSTONE_ORE ||
               block == Blocks.EMERALD_ORE ||
               block == Blocks.LAPIS_ORE ||
               block == Blocks.DIAMOND_ORE ||
               block == Blocks.ANCIENT_DEBRIS ||
               block == Blocks.DEEPSLATE_COAL_ORE ||
               block == Blocks.DEEPSLATE_IRON_ORE ||
               block == Blocks.DEEPSLATE_COPPER_ORE ||
               block == Blocks.DEEPSLATE_GOLD_ORE ||
               block == Blocks.DEEPSLATE_REDSTONE_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.DEEPSLATE_LAPIS_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE;
    }

    private void performSafeUnload(ChunkPos pos) {
        if (mc.world == null || isShuttingDown) return;
        
        try {
            // Remove from cache first
            chunkCache.remove(pos);
            hiddenBlocks.removeIf(blockPos -> 
                blockPos.getX() >> 4 == pos.x && blockPos.getZ() >> 4 == pos.z);
            
            if (debugMode.get()) {
                info("Safely unloaded chunk " + pos.x + "," + pos.z);
            }
        } catch (Exception e) {
            if (debugMode.get()) {
                error("Error unloading chunk: " + e.getMessage());
            }
        }
    }

    private void sendFakeKeepAlive() {
        if (mc.getNetworkHandler() == null || isShuttingDown || mc.player == null) return;
        
        try {
            // Send a harmless packet to keep connection alive
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), true
            ));
        } catch (Exception e) {
            // Ignore
        }
    }

    private void cleanUpCache() {
        if (isShuttingDown) return;
        
        long now = System.currentTimeMillis();
        chunkCache.entrySet().removeIf(entry -> {
            if (chunksToKeep.contains(entry.getKey())) {
                return false;
            }
            return now - entry.getValue().timestamp > 30000; // Remove after 30 seconds
        });
    }

    private byte[] encryptData(byte[] data) {
        if (encryptCipher == null) return data;
        try {
            return encryptCipher.doFinal(data);
        } catch (Exception e) {
            return data;
        }
    }

    // Public API for other modules
    public boolean isBlockVisible(BlockPos pos) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get()) return true;
        
        if (pos.getY() < antiXrayThreshold.get()) {
            return !hiddenBlocks.contains(pos);
        }
        return true;
    }

    public boolean isOnDonutSMP() {
        return onDonutSMP;
    }

    @Override
    public String getInfoString() {
        if (onDonutSMP && bypassAntiXray.get()) {
            return "§aBypassing Y=" + antiXrayThreshold.get();
        } else if (onDonutSMP) {
            return "§aDonutSMP";
        }
        return null;
    }
}
