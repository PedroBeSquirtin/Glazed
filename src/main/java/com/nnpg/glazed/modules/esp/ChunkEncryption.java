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
    
    private final SettingGroup sgMain = settings.createGroup("Main Bypass");
    private final SettingGroup sgCrash = settings.createGroup("Crash Prevention");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // ============ MAIN SETTINGS ============
    private final Setting<Boolean> enableBypass = sgMain.add(new BoolSetting.Builder()
        .name("enable-bypass")
        .description("Enable chunk bypass (Y=16 anti-xray bypass)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> antiXrayY = sgMain.add(new IntSetting.Builder()
        .name("anti-xray-y")
        .description("Y level where anti-xray starts (DonutSMP = 16)")
        .defaultValue(16)
        .min(0)
        .max(64)
        .sliderRange(0, 64)
        .visible(enableBypass::get)
        .build()
    );

    private final Setting<Boolean> spoofY = sgMain.add(new BoolSetting.Builder()
        .name("spoof-y")
        .description("Make server think you're above the anti-xray level")
        .defaultValue(true)
        .visible(enableBypass::get)
        .build()
    );

    private final Setting<Double> fakeY = sgMain.add(new DoubleSetting.Builder()
        .name("fake-y")
        .description("Y level to pretend you're at")
        .defaultValue(64.0)
        .min(0)
        .max(256)
        .sliderRange(0, 256)
        .visible(() -> spoofY.get() && enableBypass.get())
        .build()
    );

    private final Setting<Boolean> showOres = sgMain.add(new BoolSetting.Builder()
        .name("show-ores")
        .description("Show all ores below anti-xray level")
        .defaultValue(true)
        .visible(enableBypass::get)
        .build()
    );

    // ============ CRASH PREVENTION ============
    private final Setting<Boolean> stopCrash = sgCrash.add(new BoolSetting.Builder()
        .name("stop-crash")
        .description("Prevent crashes when chunks unload")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> unloadDelay = sgCrash.add(new IntSetting.Builder()
        .name("unload-delay")
        .description("Delay before unloading chunks (ticks)")
        .defaultValue(60)
        .min(10)
        .max(200)
        .sliderRange(10, 200)
        .visible(stopCrash::get)
        .build()
    );

    private final Setting<Integer> keepRadius = sgCrash.add(new IntSetting.Builder()
        .name("keep-radius")
        .description("Radius of chunks to keep loaded (chunks)")
        .defaultValue(5)
        .min(2)
        .max(10)
        .sliderRange(2, 10)
        .visible(stopCrash::get)
        .build()
    );

    // ============ ADVANCED ============
    private final Setting<Boolean> debug = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug")
        .description("Show debug messages")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> threads = sgAdvanced.add(new IntSetting.Builder()
        .name("threads")
        .description("Processing threads")
        .defaultValue(2)
        .min(1)
        .max(4)
        .build()
    );

    // ============ INTERNAL STUFF ============
    private boolean onDonutSMP = false;
    private final Set<ChunkPos> activeChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> unloadQueue = new ConcurrentHashMap<>();
    private final Set<BlockPos> visibleBlocks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> processedChunks = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;
    private int tick = 0;
    private Random random = new Random();
    private Field[] packetFields;
    private SecretKey aesKey;
    private Cipher cipher;

    public ChunkEncryption() {
        super(GlazedAddon.esp, "chunk-encryption", "ULTIMATE DonutSMP Bypass - One File Solution");
        INSTANCE = this;
        initCrypto();
    }

    public static ChunkEncryption getInstance() {
        return INSTANCE;
    }

    private void initCrypto() {
        try {
            String key = "DONUTSMP_BYPASS_2026";
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
        threadPool = Executors.newFixedThreadPool(threads.get());
        info("§a[ChunkEncryption] §fULTIMATE BYPASS ACTIVATED");
        info("§7[+] §fBypassing Y=" + antiXrayY.get() + " anti-xray");
        if (spoofY.get()) info("§7[+] §fSpoofing Y=" + fakeY.get());
        activeChunks.clear();
        unloadQueue.clear();
        visibleBlocks.clear();
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
        if (onDonutSMP && debug.get()) info("§aDonutSMP detected");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        onDonutSMP = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || !onDonutSMP || mc.player == null || !enableBypass.get()) return;
        
        tick++;
        
        // Update active chunks near player
        if (tick % 5 == 0) {
            updateActiveChunks();
        }
        
        // Process unload queue
        if (stopCrash.get() && !unloadQueue.isEmpty()) {
            processUnloadQueue();
        }
        
        // Randomize packet timing
        if (tick % 3 == 0 && random.nextBoolean()) {
            try { Thread.sleep(random.nextInt(2)); } catch (Exception e) {}
        }
    }

    private void updateActiveChunks() {
        int cx = (int) mc.player.getX() >> 4;
        int cz = (int) mc.player.getZ() >> 4;
        int r = keepRadius.get();
        
        activeChunks.clear();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                activeChunks.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
    }

    private void processUnloadQueue() {
        Iterator<Map.Entry<ChunkPos, Integer>> it = unloadQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Integer> entry = it.next();
            if (entry.getValue() <= 0) {
                it.remove();
            } else {
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!isActive() || !onDonutSMP || !enableBypass.get()) return;
        
        WorldChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();
        
        if (!processedChunks.contains(pos)) {
            processedChunks.add(pos);
            threadPool.submit(() -> processChunk(chunk));
        }
    }

    private void processChunk(WorldChunk chunk) {
        try {
            ChunkPos pos = chunk.getPos();
            ChunkSection[] sections = chunk.getSectionArray();
            int bottomY = chunk.getBottomY();
            
            for (int i = 0; i < sections.length; i++) {
                ChunkSection section = sections[i];
                if (section == null) continue;
                
                int baseY = bottomY + (i * 16);
                
                // Only process below anti-xray level
                if (baseY + 15 < antiXrayY.get()) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                Block block = section.getBlockState(x, y, z).getBlock();
                                int worldY = baseY + y;
                                
                                BlockPos blockPos = new BlockPos(
                                    chunk.getPos().getStartX() + x,
                                    worldY,
                                    chunk.getPos().getStartZ() + z
                                );
                                
                                if (showOres.get() && isOre(block)) {
                                    visibleBlocks.add(blockPos);
                                }
                            }
                        }
                    }
                }
            }
            
            if (debug.get()) {
                info("Processed chunk " + pos.x + "," + pos.z);
            }
        } catch (Exception e) {
            if (debug.get()) error("Chunk error: " + e.getMessage());
        }
    }

    private boolean isOre(Block block) {
        return block == Blocks.COAL_ORE || block == Blocks.IRON_ORE ||
               block == Blocks.COPPER_ORE || block == Blocks.GOLD_ORE ||
               block == Blocks.REDSTONE_ORE || block == Blocks.EMERALD_ORE ||
               block == Blocks.LAPIS_ORE || block == Blocks.DIAMOND_ORE ||
               block == Blocks.ANCIENT_DEBRIS ||
               block == Blocks.DEEPSLATE_COAL_ORE || block == Blocks.DEEPSLATE_IRON_ORE ||
               block == Blocks.DEEPSLATE_COPPER_ORE || block == Blocks.DEEPSLATE_GOLD_ORE ||
               block == Blocks.DEEPSLATE_REDSTONE_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.DEEPSLATE_LAPIS_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isActive() || !onDonutSMP || !enableBypass.get()) return;
        
        Packet<?> packet = event.packet;
        
        // INTERCEPT UNLOAD PACKETS - PREVENT CRASH
        if (stopCrash.get() && packet instanceof UnloadChunkS2CPacket) {
            try {
                UnloadChunkS2CPacket unloadPacket = (UnloadChunkS2CPacket) packet;
                
                // Use reflection to get chunk coordinates
                if (packetFields == null) {
                    packetFields = UnloadChunkS2CPacket.class.getDeclaredFields();
                    for (Field f : packetFields) f.setAccessible(true);
                }
                
                int x = 0, z = 0;
                for (Field f : packetFields) {
                    if (f.getName().equals("x") || f.getType() == int.class) {
                        x = f.getInt(unloadPacket);
                        break;
                    }
                }
                for (Field f : packetFields) {
                    if (f.getName().equals("z") || (f.getType() == int.class && f.getInt(unloadPacket) != x)) {
                        z = f.getInt(unloadPacket);
                        break;
                    }
                }
                
                ChunkPos pos = new ChunkPos(x, z);
                
                // Don't unload active chunks
                if (activeChunks.contains(pos)) {
                    event.setCancelled(true);
                    return;
                }
                
                // Delay unload
                if (unloadDelay.get() > 0) {
                    unloadQueue.put(pos, unloadDelay.get());
                    event.setCancelled(true);
                }
                
            } catch (Exception e) {
                if (debug.get()) error("Unload intercept error: " + e.getMessage());
            }
        }
        
        // PROCESS BLOCK UPDATES
        if (packet instanceof BlockUpdateS2CPacket) {
            BlockUpdateS2CPacket blockPacket = (BlockUpdateS2CPacket) packet;
            BlockPos pos = blockPacket.getPos();
            
            if (pos.getY() < antiXrayY.get() && showOres.get()) {
                visibleBlocks.remove(pos);
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!isActive() || !onDonutSMP || !enableBypass.get()) return;
        if (!spoofY.get()) return;
        
        Packet<?> packet = event.packet;
        
        // SPOOF POSITION IN MOVEMENT PACKETS
        if (packet instanceof PlayerMoveC2SPacket) {
            try {
                // Use reflection to modify the Y value
                Field[] fields = PlayerMoveC2SPacket.class.getDeclaredFields();
                for (Field f : fields) {
                    f.setAccessible(true);
                    if (f.getName().equals("y") || 
                        (f.getType() == double.class && f.getName().contains("y"))) {
                        
                        double originalY = f.getDouble(packet);
                        
                        // Only spoof if we're below anti-xray level
                        if (originalY < antiXrayY.get()) {
                            // Randomize spoof to avoid patterns
                            if (random.nextInt(3) != 0) {
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
    public boolean isBlockVisible(BlockPos pos) {
        if (!isActive() || !onDonutSMP || !enableBypass.get()) return true;
        if (pos.getY() < antiXrayY.get() && showOres.get()) {
            return visibleBlocks.contains(pos);
        }
        return true;
    }

    @Override
    public String getInfoString() {
        if (isActive() && onDonutSMP) {
            return "§aBypassing";
        }
        return null;
    }
}
