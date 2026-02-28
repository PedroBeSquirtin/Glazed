package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.lang.reflect.Field;

public class HoverTotem extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgTimings = settings.createGroup("Timings");

    // ============ GENERAL SETTINGS ============
    private final Setting<Boolean> hotbarTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-totem")
        .description("Place totem in hotbar slot")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot for totem (1-9)")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .visible(hotbarTotem::get)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Auto switch to totem slot when inventory opens")
        .defaultValue(false)
        .build()
    );

    // ============ TIMINGS ============
    private final Setting<Integer> tickDelay = sgTimings.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks between operations")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    // ============ STATE ============
    private int delay = 0;
    private boolean equippedOffhand = false;
    private boolean equippedHotbar = false;

    public HoverTotem() {
        super(GlazedAddon.pvp, "hover-totem", "Auto-equip totem when hovering - undetectable");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        Screen screen = mc.currentScreen;
        if (!(screen instanceof InventoryScreen invScreen)) {
            reset();
            return;
        }

        if (autoSwitch.get()) {
            mc.player.getInventory().selectedSlot = hotbarSlot.get() - 1;
        }

        Slot hoveredSlot = getHoveredSlot(invScreen);
        if (hoveredSlot == null || hoveredSlot.getIndex() > 35) return;

        if (!hoveredSlot.getStack().isOf(Items.TOTEM_OF_UNDYING)) return;

        if (delay > 0) {
            delay--;
            return;
        }

        int syncId = invScreen.getScreenHandler().syncId;
        int slotIndex = hoveredSlot.getIndex();

        // Equip offhand if needed
        if (!equippedOffhand && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            mc.interactionManager.clickSlot(syncId, slotIndex, 40, SlotActionType.SWAP, mc.player);
            equippedOffhand = true;
            delay = tickDelay.get();
            return;
        }

        // Equip hotbar if needed and enabled
        if (hotbarTotem.get() && !equippedHotbar) {
            int hotbarIdx = hotbarSlot.get() - 1;
            if (!mc.player.getInventory().getStack(hotbarIdx).isOf(Items.TOTEM_OF_UNDYING)) {
                mc.interactionManager.clickSlot(syncId, slotIndex, hotbarIdx, SlotActionType.SWAP, mc.player);
                equippedHotbar = true;
                delay = tickDelay.get();
            }
        }
    }

    private Slot getHoveredSlot(InventoryScreen screen) {
        try {
            // Try reflection to get focused slot
            Field field = InventoryScreen.class.getSuperclass().getDeclaredField("focusedSlot");
            field.setAccessible(true);
            return (Slot) field.get(screen);
        } catch (Exception e) {
            return null;
        }
    }

    private void reset() {
        delay = 0;
        equippedOffhand = false;
        equippedHotbar = false;
    }
}
