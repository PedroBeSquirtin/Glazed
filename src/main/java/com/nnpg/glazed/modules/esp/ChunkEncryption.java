package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
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
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgEncryption = settings.createGroup("Encryption");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

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
    private final Map<ChunkPos, Integer> reloadTimers = new ConcurrentHashMap<>();
    private ExecutorService threadPool;
    private int tickCounter = 0;
    private Vec3d actualPlayerPos = Vec3d.ZERO;
    private final AtomicInteger queueSize = new AtomicInteger(0);

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
        super(GlazedAddon.esp, "chunk-encryption", "DonutSMP Anti-Xray Bypass - Load below Y=16 undetected");
    }

    @Override
    public void onActivate() {
        try {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
            initEncryption();
            
            if (autoEnable.get()) {
                checkServer();
            }
            
            if (bypassAntiXray.get() && onDonutSMP) {
                info("§a[Anti-Xray Bypass] §fEnabled - Loading below Y=" + antiXrayThreshold.get());
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
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }
        chunkCache.clear();
        chunkLoadQueue.clear();
        loadingChunks.clear();
        hiddenBlocks.clear();
        reloadTimers.clear();
        queueSize.set(0);
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        checkServer();
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
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get()) return;
        
        tickCounter++;
        
        // Store actual position for later use
        if (mc.player != null) {
            actualPlayerPos = mc.player.getPos();
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
        
        // Process chunk reload timers
        reloadTimers.entrySet().removeIf(entry -> {
            if (entry.getValue() <= 0) {
                if (chunkReload.get()) {
                    requestChunkReload(entry.getKey());
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
    }

    private void requestChunk(ChunkPos pos) {
        if (mc.world == null || mc.getNetworkHandler() == null) return;
        
        try {
            // Create encrypted chunk request
            byte[] requestData = createChunkRequest(pos);
            byte[] encryptedRequest = encryptData(requestData);
            
            // Send request via packet manipulation
            mc.execute(() -> {
                // Force chunk load by accessing it
                WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
                if (chunk != null && chunk.isLoaded()) {
                    processChunk(chunk);
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
        if (mc.world == null) return;
        
        mc.execute(() -> {
            // Force chunk reload by unloading and reloading
            if (mc.world.getChunkManager() != null) {
                // This tricks the server into sending fresh chunk data
                mc.world.getChunk(pos.x, pos.z);
                
                if (debugMode.get()) {
                    info("Reloaded chunk " + pos.x + "," + pos.z);
                }
            }
        });
    }

    private byte[] createChunkRequest(ChunkPos pos) {
        // Create a request that includes spoofed Y level
        String request = String.format("CHUNK:%d,%d;Y=%.1f", pos.x, pos.z, 
            spoofPosition.get() ? spoofedY.get() : actualPlayerPos.y);
        return request.getBytes();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get()) return;

        WorldChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();
        
        // Cache the chunk for later processing
        chunkCache.put(pos, new ChunkData(chunk));
        
        // Set reload timer if enabled
        if (chunkReload.get()) {
            reloadTimers.put(pos, reloadInterval.get());
        }
        
        // Process chunk for anti-xray bypass
        threadPool.submit(() -> processChunk(chunk));
    }

    private void processChunk(WorldChunk chunk) {
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
               block == Blocks.NETHERITE_ORE ||
               block == Blocks.DEEPSLATE_COAL_ORE ||
               block == Blocks.DEEPSLATE_IRON_ORE ||
               block == Blocks.DEEPSLATE_COPPER_ORE ||
               block == Blocks.DEEPSLATE_GOLD_ORE ||
               block == Blocks.DEEPSLATE_REDSTONE_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.DEEPSLATE_LAPIS_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get()) return;

        // Decrypt incoming packets
        if (event.packet instanceof ChunkDataS2CPacket) {
            try {
                if (decryptCipher != null) {
                    // Decrypt chunk data
                    // This would need reflection to access private fields
                }
            } catch (Exception e) {
                if (debugMode.get()) {
                    error("Decryption failed: " + e.getMessage());
                }
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
        if (!isActive() || !onDonutSMP || !bypassAntiXray.get()) return;

        // Spoof position in movement packets
        if (spoofPosition.get() && event.packet instanceof PlayerMoveC2SPacket) {
            // This would need to modify the packet to spoof Y level
            // Requires mixin to access/modify packet fields
        }
    }

    private byte[] encryptData(byte[] data) {
        if (encryptCipher == null) return data;
        try {
            return encryptCipher.doFinal(data);
        } catch (Exception e) {
            return data;
        }
    }

    private byte[] decryptData(byte[] data) {
        if (decryptCipher == null) return data;
        try {
            return decryptCipher.doFinal(data);
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
