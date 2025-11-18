package com.fairkeepinventory.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class TooltipHook {

    private static int lastRequestId = -1;
    private static int lastSlotIndex = -1;
    private static byte lastContainerType = -1;

    // New: track when we last asked the server for this slot
    private static long lastRequestTimeMs = 0L;
    private static final long REFRESH_INTERVAL_MS = 1000L; // 1 second

    private TooltipHook() {
    }

    static void init() {
        // Signature: (stack, context, type, lines)
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.world == null) return;
            if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
            if (!ClientPlayNetworking.canSend(FkiPayload.ID)) return;

            // Find the slot whose stack instance is the one we're rendering a tooltip for
            Slot targetSlot = null;
            for (Slot slot : screen.getScreenHandler().slots) {
                if (!slot.hasStack()) continue;
                if (slot.getStack() == stack) {
                    targetSlot = slot;
                    break;
                }
            }
            if (targetSlot == null) return;

            byte containerType = isPlayerInventorySlot(targetSlot) ? (byte) 0 : (byte) 1;
            int slotIndex = targetSlot.getIndex();

            boolean sameSlot = (slotIndex == lastSlotIndex) && (containerType == lastContainerType);
            long now = System.currentTimeMillis();

            // If this is a different slot, or we never requested before, start a fresh request id
            if (!sameSlot || lastRequestId < 0) {
                lastRequestId = FairKeepInventoryClient.nextRequestId();
                lastSlotIndex = slotIndex;
                lastContainerType = containerType;
                lastRequestTimeMs = now;
                sendTooltipRequest(lastRequestId, containerType, slotIndex);
            } else if (now - lastRequestTimeMs >= REFRESH_INTERVAL_MS) {
                // Same slot, but it's time to refresh from the server
                lastRequestTimeMs = now;
                // Re-use the same requestId so the cache entry gets updated in place
                sendTooltipRequest(lastRequestId, containerType, slotIndex);
            }

            // Always show the latest data we have for this request id
            String[] extra = (lastRequestId >= 0)
                    ? FairKeepInventoryClient.getResponse(lastRequestId)
                    : null;

            if (extra == null) {
                // No data yet for this request id
                lines.add(Text.literal("§8[FKI] Loading ownership..."));
            } else {
                for (String s : extra) {
                    if (s != null && !s.isEmpty()) {
                        lines.add(Text.literal(s));
                    }
                }
            }
        });
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.inventory instanceof net.minecraft.entity.player.PlayerInventory;
    }

    private static void sendTooltipRequest(int requestId, byte containerType, int slotIndex) {
        if (!ClientPlayNetworking.canSend(FkiPayload.ID)) return;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeByte(0x02);       // TOOLTIP_REQUEST
            out.writeInt(requestId);
            out.writeByte(containerType);
            out.writeInt(slotIndex);
            out.flush();

            ClientPlayNetworking.send(new FkiPayload(baos.toByteArray()));
        } catch (IOException e) {
            // ignore
        }
    }
}
