package com.fairkeepinventory.client;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record FkiPayload(byte[] data) implements CustomPayload {

    // Must match the Bukkit plugin channel "fairkeepinventory:ownership"
    public static final CustomPayload.Id<FkiPayload> ID =
            new CustomPayload.Id<>(Identifier.of("fairkeepinventory", "ownership"));

    // Explicit codec implementation for 1.21.8
    public static final PacketCodec<RegistryByteBuf, FkiPayload> CODEC =
            new PacketCodec<RegistryByteBuf, FkiPayload>() {
                @Override
                public FkiPayload decode(RegistryByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new FkiPayload(bytes);
                }

                @Override
                public void encode(RegistryByteBuf buf, FkiPayload value) {
                    buf.writeBytes(value.data());
                }
            };

    @Override
    public Id<FkiPayload> getId() {
        return ID;
    }
}
