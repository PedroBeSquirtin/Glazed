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
    private final SettingGroup sgGeneral = settings.createGroup("Settings");

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot")
        .description("Hotbar slot for totem (1-9)")
        .defaultValue(1)
        .min(1)
        .max(9)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between actions (ms)")
        .defaultValue(50)
        .min(30)
        .max(150)
        .sliderRange(30, 120)
        .build()
    );

    private long nextActionTime = 0;
    private boolean equipped = false;

    public HoverTotem() {
        super(GlazedAddon.pvp, "hover-totem", "Natural totem equipping - low risk");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        Screen screen = mc.currentScreen;
        if (!(screen instanceof InventoryScreen invScreen)) {
            equipped = false;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionTime) return;

        Slot hoveredSlot = getHoveredSlot(invScreen);
        if (hoveredSlot == null || hoveredSlot.getIndex() > 35) return;

        if (!hoveredSlot.getStack().isOf(Items.TOTEM_OF_UNDYING)) return;

        int syncId = invScreen.getScreenHandler().syncId;
        int slotIndex = hoveredSlot.getIndex();

        // Equip offhand if needed
        if (!equipped && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            mc.interactionManager.clickSlot(syncId, slotIndex, 40, SlotActionType.SWAP, mc.player);
            equipped = true;
            nextActionTime = now + delay.get() + 20;
            return;
        }

        // Equip hotbar if needed
        int hotbarIdx = hotbarSlot.get() - 1;
        if (!mc.player.getInventory().getStack(hotbarIdx).isOf(Items.TOTEM_OF_UNDYING)) {
            mc.interactionManager.clickSlot(syncId, slotIndex, hotbarIdx, SlotActionType.SWAP, mc.player);
            nextActionTime = now + delay.get() + 20;
        }
    }

    private Slot getHoveredSlot(InventoryScreen screen) {
        try {
            Field field = InventoryScreen.class.getSuperclass().getDeclaredField("focusedSlot");
            field.setAccessible(true);
            return (Slot) field.get(screen);
        } catch (Exception e) {
            return null;
        }
    }
}
