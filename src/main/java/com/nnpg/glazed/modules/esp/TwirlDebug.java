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
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
 
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
 
public class TwirlDebug extends Module {
 
    public TwirlDebug() {
        super(GlazedAddon.esp, "twirl-debug", "Detects server-side player activity with color-coded highlighting");
    }
 
    // ============ ACTIVITY WEIGHTS ============
    private static final int REDSTONE_ACTIVITY          = 12;
    private static final int PISTON_ACTIVITY            = 14;
    private static final int CHEST_OPEN_ACTIVITY        = 18;
    private static final int FURNACE_ACTIVITY           = 10;
    private static final int HOPPER_ACTIVITY            = 8;
    private static final int ITEM_DROP_ACTIVITY         = 22;
    private static final int SPAWNER_BREAK_ACTIVITY     = 30;
    private static final int ENTITY_COUNT_BASE          = 3;
    private static final int PLAYER_NEARBY_ACTIVITY     = 35;
    private static final int PACKET_BURST_ACTIVITY      = 18;
    private static final int BLOCK_CHANGE_ACTIVITY      = 14;
    private static final int WATER_LAVA_ACTIVITY        = 6;
    private static final int COMPARATOR_ACTIVITY        = 10;
    private static final int OBSERVER_ACTIVITY          = 9;
    private static final int BEACON_ACTIVITY            = 22;
    private static final int PORTAL_ACTIVITY            = 16;
    private static final int FARM_ACTIVITY              = 12;
    private static final int BREEDING_ACTIVITY          = 28;
    private static final int VILLAGER_TRADE_ACTIVITY    = 22;
    private static final int EXPLOSION_ACTIVITY         = 40;
    private static final int SIGN_UPDATE_ACTIVITY       = 8;
    private static final int NOTEBLOCK_ACTIVITY         = 7;
    private static final int SCULK_ACTIVITY             = 15;
    private static final int TNT_ACTIVITY               = 38;
    private static final int DISPENSER_ACTIVITY         = 11;
    private static final int MINECART_ACTIVITY          = 9;
    private static final int CHUNK_DELTA_BURST_ACTIVITY = 20;
 
    // ============ THRESHOLDS & DECAY ============
    private static final int MAX_ACTIVITY       = 150;
    private static final int HOTSPOT_THRESHOLD  = 90;
    private static final int WARM_THRESHOLD     = 50;
    private static final int COOL_THRESHOLD     = 20;
    private static final int DECAY_RATE         = 1;
 
    // ============ RENDER ============
    private static final int    RENDER_DISTANCE      = 80;
    private static final double CHUNK_SLAB_HEIGHT    = 0.25;
    private static final double CHUNK_SLAB_Y_OFFSET  = 62;
    private static final double BLOCK_OFFSET         = 0.04;
 
    // ============ COLORS (cool blue -> deep navy) ============
    // Tier 1 – faint activity
    private static final Color T1_FILL = new Color(200, 230, 255, 60);
    private static final Color T1_LINE = new Color(200, 230, 255, 160);
    // Tier 2 – low activity
    private static final Color T2_FILL = new Color(100, 180, 240, 90);
    private static final Color T2_LINE = new Color(100, 180, 240, 190);
    // Tier 3 – medium activity
    private static final Color T3_FILL = new Color(40, 120, 210, 130);
    private static final Color T3_LINE = new Color(40, 120, 210, 220);
    // Tier 4 – high activity
    private static final Color T4_FILL = new Color(10, 60, 160, 160);
    private static final Color T4_LINE = new Color(10, 60, 160, 240);
    // Tier 5 – hotspot (darkest navy + bright accent line)
    private static final Color T5_FILL = new Color(5, 20, 90, 190);
    private static final Color T5_LINE = new Color(30, 80, 255, 255);
 
    // ============ SETTINGS ============
    private final SettingGroup sgGeneral       = settings.createGroup("General");
    private final SettingGroup sgDetection     = settings.createGroup("Detection");
    private final SettingGroup sgRender        = settings.createGroup("Rendering");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");
 
    // General
    private final Setting<Boolean> chunkHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("chunk-highlight").description("Highlight chunks with detected activity").defaultValue(true).build());
    private final Setting<Boolean> blockHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("block-highlight").description("Highlight individual active blocks").defaultValue(true).build());
    private final Setting<Boolean> hotspotOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hotspot-only").description("Only show chunks above hotspot threshold").defaultValue(false).build());
    private final Setting<Integer> decayInterval = sgGeneral.add(new IntSetting.Builder()
        .name("decay-interval").description("How often (ms) activity decays").defaultValue(4000).min(1000).max(15000).build());
 
    // Detection toggles
    private final Setting<Boolean> detectRedstone = sgDetection.add(new BoolSetting.Builder()
        .name("redstone").defaultValue(true).build());
    private final Setting<Boolean> detectContainers = sgDetection.add(new BoolSetting.Builder()
        .name("containers").defaultValue(true).build());
    private final Setting<Boolean> detectEntities = sgDetection.add(new BoolSetting.Builder()
        .name("entities").defaultValue(true).build());
    private final Setting<Boolean> detectPacketBursts = sgDetection.add(new BoolSetting.Builder()
        .name("packet-bursts").defaultValue(true).build());
    private final Setting<Boolean> detectMachines = sgDetection.add(new BoolSetting.Builder()
        .name("machines").description("Pistons, dispensers, droppers, hoppers").defaultValue(true).build());
    private final Setting<Boolean> detectFarming = sgDetection.add(new BoolSetting.Builder()
        .name("farming").description("Crop growth, animal breeding, villager trading").defaultValue(true).build());
    private final Setting<Boolean> detectExplosions = sgDetection.add(new BoolSetting.Builder()
        .name("explosions").defaultValue(true).build());
    private final Setting<Boolean> detectFluid = sgDetection.add(new BoolSetting.Builder()
        .name("fluid").description("Water/lava flow activity").defaultValue(true).build());
    private final Setting<Boolean> detectSculk = sgDetection.add(new BoolSetting.Builder()
        .name("sculk").defaultValue(true).build());
    private final Setting<Boolean> detectNearbyPlayers = sgDetection.add(new BoolSetting.Builder()
        .name("nearby-players").description("Spike activity when other players are close").defaultValue(true).build());
 
    // Render
    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").defaultValue(false).build());
    private final Setting<Double> tracerHeight = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-height").defaultValue(72).min(0).max(320).build());
    private final Setting<Boolean> pulseEffect = sgRender.add(new BoolSetting.Builder()
        .name("pulse-hotspots").description("Hotspot borders pulse in brightness").defaultValue(true).build());
 
    // Notifications
    private final Setting<Boolean> notifyChat = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-notify").defaultValue(true).build());
    private final Setting<Boolean> notifySound = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-notify").defaultValue(true).build());
    private final Setting<Integer> notifyCooldown = sgNotifications.add(new IntSetting.Builder()
        .name("notify-cooldown").description("Seconds between notifications per chunk").defaultValue(25).min(5).max(300).build());
 
    // ============ STATE ============
    private final Map<ChunkPos, ChunkData>    chunks       = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockData>    blocks       = new ConcurrentHashMap<>();
    private final Map<ChunkPos, AtomicInteger> packetBurst = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long>         notifyTimes  = new ConcurrentHashMap<>();
    private final Set<ChunkPos>               hotspots     = ConcurrentHashMap.newKeySet();
    private final List<RecentEvent>           recentEvents = new CopyOnWriteArrayList<>();
    private long lastDecay = 0;
    private long pulseTimer = 0;
 
    // ============ DATA CLASSES ============
 
    private static class ChunkData {
        volatile int level = 0;
        long lastUpdate = System.currentTimeMillis();
        final Map<String, Integer> sources = new ConcurrentHashMap<>();
 
        void add(int amount, String source) {
            level = Math.min(MAX_ACTIVITY, level + amount);
            sources.merge(source, amount, Integer::sum);
            lastUpdate = System.currentTimeMillis();
        }
 
        void decay(int rate) {
            level = Math.max(0, level - rate);
        }
 
        boolean active() { return level > 0; }
 
        String topSource() {
            return sources.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("?");
        }
 
        Color[] colors(boolean pulse, long timer) {
            float pulseAlpha = pulse ? (0.7f + 0.3f * (float) Math.sin(timer / 300.0)) : 1.0f;
            if (level >= HOTSPOT_THRESHOLD) {
                int la = (int)(T5_LINE.a * pulseAlpha);
                return new Color[]{T5_FILL, new Color(T5_LINE.r, T5_LINE.g, T5_LINE.b, Math.min(255, la))};
            } else if (level >= WARM_THRESHOLD * 2) {
                return new Color[]{T4_FILL, T4_LINE};
            } else if (level >= WARM_THRESHOLD) {
                return new Color[]{T3_FILL, T3_LINE};
            } else if (level >= COOL_THRESHOLD) {
                return new Color[]{T2_FILL, T2_LINE};
            } else {
                return new Color[]{T1_FILL, T1_LINE};
            }
        }
    }
 
    private static class BlockData {
        volatile int level = 0;
        final BlockPos pos;
        long lastUpdate = System.currentTimeMillis();
 
        BlockData(BlockPos pos) { this.pos = pos; }
 
        void add(int amount) {
            level = Math.min(MAX_ACTIVITY, level + amount);
            lastUpdate = System.currentTimeMillis();
        }
    }
 
    private static class RecentEvent {
        final long time = System.currentTimeMillis();
        final BlockPos pos;
        RecentEvent(BlockPos pos) { this.pos = pos; }
    }
 
    // ============ LIFECYCLE ============
 
    @Override
    public void onActivate() {
        reset();
        if (notifyChat.get() && mc.player != null)
            ChatUtils.info("TwirlDebug", "§bMonitoring server-side activity...");
    }
 
    @Override
    public void onDeactivate() { reset(); }
 
    private void reset() {
        chunks.clear(); blocks.clear(); packetBurst.clear();
        notifyTimes.clear(); hotspots.clear(); recentEvents.clear();
    }
 
    // ============ TICK ============
 
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        pulseTimer = now;
 
        // Purge old events
        recentEvents.removeIf(e -> now - e.time > 15000);
 
        // Periodic decay & analysis
        if (now - lastDecay > decayInterval.get()) {
            decayAll();
            if (detectEntities.get()) scanEntities();
            if (detectNearbyPlayers.get()) scanNearbyPlayers();
            updateHotspots(now);
            lastDecay = now;
        }
    }
 
    private void decayAll() {
        chunks.values().removeIf(d -> { d.decay(DECAY_RATE); return !d.active(); });
        blocks.values().removeIf(d -> {
            long age = System.currentTimeMillis() - d.lastUpdate;
            d.level = Math.max(0, d.level - 1);
            return d.level == 0 && age > 20000;
        });
        packetBurst.values().forEach(c -> c.set(Math.max(0, c.get() - 3)));
        packetBurst.entrySet().removeIf(e -> e.getValue().get() == 0);
    }
 
    private void scanEntities() {
        Map<ChunkPos, Integer> counts = new HashMap<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            ChunkPos cp = e.getChunkPos();
            int w = 1;
            if (e instanceof PlayerEntity) w = 15;
            else if (e instanceof ItemEntity) w = 5;
            else if (e instanceof MobEntity) w = 3;
            else if (e instanceof PassiveEntity) w = 2;
            counts.merge(cp, w, Integer::sum);
        }
        counts.forEach((cp, total) -> {
            if (total > 8) addChunk(cp, Math.min(35, total * ENTITY_COUNT_BASE), "entities");
        });
    }
 
    private void scanNearbyPlayers() {
        if (mc.player == null) return;
        double px = mc.player.getX(), pz = mc.player.getZ();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity) || e == mc.player) continue;
            double dist = Math.sqrt(Math.pow(e.getX() - px, 2) + Math.pow(e.getZ() - pz, 2));
            if (dist < 64) {
                int bonus = (int)(PLAYER_NEARBY_ACTIVITY * (1.0 - dist / 64.0));
                addChunk(e.getChunkPos(), bonus, "player_nearby");
            }
        }
    }
 
    private void updateHotspots(long now) {
        Set<ChunkPos> current = new HashSet<>();
        chunks.forEach((cp, data) -> {
            if (data.level >= HOTSPOT_THRESHOLD) {
                current.add(cp);
                if (notifyChat.get() && !hotspots.contains(cp)) {
                    long last = notifyTimes.getOrDefault(cp, 0L);
                    if (now - last > notifyCooldown.get() * 1000L) {
                        fireNotification(cp, data);
                        notifyTimes.put(cp, now);
                    }
                }
            }
        });
        hotspots.clear();
        hotspots.addAll(current);
    }
 
    private void fireNotification(ChunkPos cp, ChunkData data) {
        if (mc.player == null) return;
        String msg = String.format(
            "§b[TwirlDebug] §fActivity hotspot at §e[%d, %d]§f | Level: §c%d§f | Source: §e%s",
            cp.x, cp.z, data.level, data.topSource());
        mc.player.sendMessage(Text.literal(msg), false);
        if (notifySound.get()) {
            float pitch = Math.min(2.0f, 1.0f + (data.level - HOTSPOT_THRESHOLD) / 80.0f);
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, pitch);
        }
    }
 
    // ============ HELPERS ============
 
    private void addChunk(ChunkPos cp, int amount, String source) {
        if (hotspotOnly.get() && amount < HOTSPOT_THRESHOLD / 3) return;
        chunks.computeIfAbsent(cp, k -> new ChunkData()).add(amount, source);
    }
 
    private void addBlock(BlockPos bp, int amount, String source) {
        blocks.computeIfAbsent(bp, k -> new BlockData(bp)).add(amount);
        addChunk(new ChunkPos(bp), amount / 2, source);
        recentEvents.add(new RecentEvent(bp));
    }
 
    // ============ PACKET HANDLER ============
 
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null) return;
 
        // ---- Block updates ----
        if (event.packet instanceof BlockUpdateS2CPacket pkt) {
            BlockPos bp = pkt.getPos();
            ChunkPos cp = new ChunkPos(bp);
            BlockState state = pkt.getState();
            Block block = state.getBlock();
 
            // Count packet bursts
            if (detectPacketBursts.get()) {
                int burst = packetBurst.computeIfAbsent(cp, k -> new AtomicInteger()).incrementAndGet();
                if (burst > 8) addChunk(cp, PACKET_BURST_ACTIVITY, "packet_burst");
            }
 
            // Redstone
            if (detectRedstone.get() && isRedstone(block)) {
                addBlock(bp, REDSTONE_ACTIVITY, "redstone");
            }
 
            // Machines
            if (detectMachines.get()) {
                if (isMachine(block)) addBlock(bp, PISTON_ACTIVITY, "machine");
                if (block == Blocks.DISPENSER || block == Blocks.DROPPER)
                    addBlock(bp, DISPENSER_ACTIVITY, "dispenser");
                if (block == Blocks.COMPARATOR) addBlock(bp, COMPARATOR_ACTIVITY, "comparator");
                if (block == Blocks.OBSERVER)   addBlock(bp, OBSERVER_ACTIVITY, "observer");
            }
 
            // Fluid
            if (detectFluid.get() && isFluid(block))
                addBlock(bp, WATER_LAVA_ACTIVITY, "fluid");
 
            // Farming / crops
            if (detectFarming.get() && isCrop(block))
                addBlock(bp, FARM_ACTIVITY, "farming");
 
            // TNT
            if (detectExplosions.get() && block == Blocks.TNT)
                addBlock(bp, TNT_ACTIVITY, "tnt");
 
            // Note block / sculk
            if (block == Blocks.NOTE_BLOCK)
                addBlock(bp, NOTEBLOCK_ACTIVITY, "noteblock");
            if (detectSculk.get() && isSculk(block))
                addBlock(bp, SCULK_ACTIVITY, "sculk");
 
            // Generic block change
            addBlock(bp, BLOCK_CHANGE_ACTIVITY / 3, "block_change");
        }
 
        // ---- Chunk delta updates (many blocks changing at once = machine activity) ----
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket pkt) {
            if (mc.player == null) return;
            // Extract chunk from packet via the player's rough position as fallback
            ChunkPos cp = new ChunkPos(mc.player.getBlockPos());
            addChunk(cp, CHUNK_DELTA_BURST_ACTIVITY, "chunk_delta");
        }
 
        // ---- Block entity updates (containers opened/modified) ----
        if (event.packet instanceof BlockEntityUpdateS2CPacket pkt && detectContainers.get()) {
            BlockPos bp = pkt.getPos();
            addBlock(bp, CHEST_OPEN_ACTIVITY, "container");
        }
 
        // ---- Entity spawn (rapid spawning = activity) ----
        if (event.packet instanceof EntitySpawnS2CPacket pkt) {
            BlockPos bp = new BlockPos((int) pkt.getX(), (int) pkt.getY(), (int) pkt.getZ());
            addBlock(bp, ENTITY_COUNT_BASE * 4, "entity_spawn");
        }
 
        // ---- Explosion ----
        if (event.packet instanceof ExplosionS2CPacket pkt && detectExplosions.get()) {
            BlockPos bp = new BlockPos((int) pkt.getX(), (int) pkt.getY(), (int) pkt.getZ());
            addBlock(bp, EXPLOSION_ACTIVITY, "explosion");
        }
 
        // ---- Sign update (can indicate player interaction) ----
        if (event.packet instanceof SignEditorOpenS2CPacket pkt) {
            addBlock(pkt.getPos(), SIGN_UPDATE_ACTIVITY, "sign");
        }
 
        // ---- Open screen (container open) ----
        if (event.packet instanceof OpenScreenS2CPacket && detectContainers.get()) {
            if (mc.player != null)
                addChunk(new ChunkPos(mc.player.getBlockPos()), CHEST_OPEN_ACTIVITY / 2, "screen_open");
        }
 
        // ---- Play sound (beacon, portal, farming indicators) ----
        if (event.packet instanceof PlaySoundS2CPacket pkt) {
            BlockPos bp = new BlockPos((int) pkt.getX(), (int) pkt.getY(), (int) pkt.getZ());
            String id = pkt.getSound().value().getId().getPath();
            int weight = 0;
            if (id.contains("beacon"))                           weight = BEACON_ACTIVITY;
            else if (id.contains("portal"))                     weight = PORTAL_ACTIVITY;
            else if (id.contains("villager") && detectFarming.get()) weight = VILLAGER_TRADE_ACTIVITY;
            else if (id.contains("mob.chicken") || id.contains("mob.cow") || id.contains("mob.pig"))
                weight = detectFarming.get() ? BREEDING_ACTIVITY / 3 : 0;
            else if (id.contains("mob.generic.explode") && detectExplosions.get()) weight = EXPLOSION_ACTIVITY / 2;
            else if (id.contains("furnace") && detectContainers.get()) weight = FURNACE_ACTIVITY;
            else if (id.contains("hopper") && detectMachines.get()) weight = HOPPER_ACTIVITY;
            else if (id.contains("item.pickup"))                weight = ITEM_DROP_ACTIVITY / 3;
            if (weight > 0) addBlock(bp, weight, "sound:" + id.substring(0, Math.min(id.length(), 12)));
        }
    }
 
    // ============ BLOCK CLASSIFIERS ============
 
    private boolean isRedstone(Block b) {
        return b == Blocks.REDSTONE_WIRE || b == Blocks.REDSTONE_TORCH ||
               b == Blocks.REDSTONE_WALL_TORCH || b == Blocks.REDSTONE_BLOCK ||
               b == Blocks.REDSTONE_LAMP || b == Blocks.REPEATER ||
               b == Blocks.COMPARATOR || b == Blocks.LEVER ||
               b == Blocks.STONE_BUTTON || b == Blocks.OAK_BUTTON ||
               b == Blocks.SPRUCE_BUTTON || b == Blocks.BIRCH_BUTTON ||
               b == Blocks.JUNGLE_BUTTON || b == Blocks.ACACIA_BUTTON ||
               b == Blocks.DARK_OAK_BUTTON || b == Blocks.CRIMSON_BUTTON ||
               b == Blocks.WARPED_BUTTON || b == Blocks.TRIPWIRE ||
               b == Blocks.TRIPWIRE_HOOK || b == Blocks.TARGET ||
               b == Blocks.DAYLIGHT_DETECTOR || b == Blocks.TRAPPED_CHEST;
    }
 
    private boolean isMachine(Block b) {
        return b == Blocks.PISTON || b == Blocks.STICKY_PISTON ||
               b == Blocks.PISTON_HEAD || b == Blocks.OBSERVER ||
               b == Blocks.HOPPER || b == Blocks.POWERED_RAIL ||
               b == Blocks.ACTIVATOR_RAIL || b == Blocks.DETECTOR_RAIL;
    }
 
    private boolean isFluid(Block b) {
        return b == Blocks.WATER || b == Blocks.LAVA ||
               b == Blocks.KELP || b == Blocks.SEAGRASS;
    }
 
    private boolean isCrop(Block b) {
        return b == Blocks.WHEAT || b == Blocks.CARROTS ||
               b == Blocks.POTATOES || b == Blocks.BEETROOTS ||
               b == Blocks.SWEET_BERRY_BUSH || b == Blocks.NETHER_WART ||
               b == Blocks.SUGAR_CANE || b == Blocks.CACTUS ||
               b == Blocks.BAMBOO || b == Blocks.MELON_STEM ||
               b == Blocks.PUMPKIN_STEM || b == Blocks.MELON ||
               b == Blocks.PUMPKIN || b == Blocks.COCOA ||
               b == Blocks.CAVE_VINES || b == Blocks.TWISTING_VINES ||
               b == Blocks.WEEPING_VINES;
    }
 
    private boolean isSculk(Block b) {
        return b == Blocks.SCULK_SENSOR || b == Blocks.SCULK_CATALYST ||
               b == Blocks.SCULK_SHRIEKER || b == Blocks.SCULK ||
               b == Blocks.SCULK_VEIN;
    }
 
    // ============ RENDERING ============
 
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || !isActive()) return;
        Vec3d cam = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
 
        if (chunkHighlight.get()) renderChunks(event, cam);
        if (blockHighlight.get()) renderBlocks(event, cam);
        if (showTracers.get()) renderTracers(event);
    }
 
    private void renderChunks(Render3DEvent event, Vec3d cam) {
        double maxDistSq = (double) RENDER_DISTANCE * RENDER_DISTANCE * 256;
        chunks.forEach((cp, data) -> {
            if (hotspotOnly.get() && data.level < HOTSPOT_THRESHOLD) return;
 
            double cx = cp.getStartX() + 8, cz = cp.getStartZ() + 8;
            if (Math.pow(cx - cam.x, 2) + Math.pow(cz - cam.z, 2) > maxDistSq) return;
 
            Color[] colors = data.colors(pulseEffect.get() && data.level >= HOTSPOT_THRESHOLD, pulseTimer);
            float intensity = Math.min(1.0f, data.level / (float) MAX_ACTIVITY);
            Color fill = new Color(colors[0].r, colors[0].g, colors[0].b, (int)(colors[0].a * intensity));
 
            int x1 = cp.getStartX(), z1 = cp.getStartZ();
            int x2 = cp.getEndX() + 1, z2 = cp.getEndZ() + 1;
            event.renderer.box(x1, CHUNK_SLAB_Y_OFFSET, z1,
                               x2, CHUNK_SLAB_Y_OFFSET + CHUNK_SLAB_HEIGHT, z2,
                               fill, colors[1], ShapeMode.Both, 0);
        });
    }
 
    private void renderBlocks(Render3DEvent event, Vec3d cam) {
        double maxDistSq = (double) RENDER_DISTANCE * RENDER_DISTANCE * 64;
        blocks.forEach((bp, data) -> {
            double dx = bp.getX() + 0.5 - cam.x, dz = bp.getZ() + 0.5 - cam.z;
            if (dx * dx + dz * dz > maxDistSq) return;
 
            ChunkData cd = chunks.get(new ChunkPos(bp));
            Color[] colors = cd != null
                ? cd.colors(pulseEffect.get() && data.level >= HOTSPOT_THRESHOLD, pulseTimer)
                : new Color[]{T2_FILL, T2_LINE};
            float intensity = Math.min(1.0f, data.level / (float) MAX_ACTIVITY);
            Color fill = new Color(colors[0].r, colors[0].g, colors[0].b, (int)(colors[0].a * intensity));
 
            double x = bp.getX() - BLOCK_OFFSET, y = bp.getY() - BLOCK_OFFSET, z = bp.getZ() - BLOCK_OFFSET;
            double s = 1 + BLOCK_OFFSET * 2;
            event.renderer.box(x, y, z, x + s, y + s, z + s, fill, colors[1], ShapeMode.Both, 0);
        });
    }
 
    private void renderTracers(Render3DEvent event) {
        if (hotspots.isEmpty() || mc.player == null) return;
        Vec3d start = mc.player.getCameraPosVec(event.tickDelta)
            .add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
 
        hotspots.forEach(cp -> {
            ChunkData data = chunks.get(cp);
            if (data == null) return;
            Color[] colors = data.colors(false, 0);
            event.renderer.line(start.x, start.y, start.z,
                cp.getStartX() + 8, tracerHeight.get(), cp.getStartZ() + 8,
                colors[1]);
        });
    }
 
    @Override
    public String getInfoString() {
        return String.format("§b%d hot §7| §3%d chunks §7| §9%d blocks",
            hotspots.size(), chunks.size(), blocks.size());
    }
}
 
