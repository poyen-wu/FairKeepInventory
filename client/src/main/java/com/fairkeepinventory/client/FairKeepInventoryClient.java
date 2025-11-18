package com.fairkeepinventory.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FairKeepInventoryClient implements ClientModInitializer {

    // Must match your Bukkit plugin channel
    public static final String CHANNEL_ID = "fairkeepinventory:ownership";

    private static final Map<Integer, String[]> RESPONSE_CACHE = new ConcurrentHashMap<>();
    private static int NEXT_REQUEST_ID = 0;

    @Override
    public void onInitializeClient() {
        // Register payload type
        PayloadTypeRegistry.playS2C().register(FkiPayload.ID, FkiPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FkiPayload.ID, FkiPayload.CODEC);

        TooltipHook.init();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendHello());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RESPONSE_CACHE.clear());

        ClientPlayNetworking.registerGlobalReceiver(FkiPayload.ID, (payload, context) -> {
            context.client().execute(() -> handleServerMessage(payload.data()));
        });
    }

    private static void sendHello() {
        if (!ClientPlayNetworking.canSend(FkiPayload.ID)) return;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(0x01); // HELLO
            out.flush();

            ClientPlayNetworking.send(new FkiPayload(baos.toByteArray()));
        } catch (IOException e) {
            // ignore, ByteArrayOutputStream should not throw
        }
    }

    private static void handleServerMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readUnsignedByte();
            if (type != 0x03) {
                return; // only TOOLTIP_RESPONSE
            }

            int requestId = in.readInt();
            int count = in.readUnsignedByte();

            String[] lines = new String[count];
            for (int i = 0; i < count; i++) {
                lines[i] = in.readUTF();
            }

            RESPONSE_CACHE.put(requestId, lines);
        } catch (IOException e) {
            // ignore malformed packets
        }
    }

    public static int nextRequestId() {
        return NEXT_REQUEST_ID++;
    }

    public static String[] getResponse(int requestId) {
        return RESPONSE_CACHE.get(requestId);
    }
}
