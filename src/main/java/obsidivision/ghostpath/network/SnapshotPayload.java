package obsidivision.ghostpath.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SnapshotPayload(long transfer, int index, int total, byte[] data) implements CustomPacketPayload {
    public static final int CHUNK = 24 * 1024;
    public static final Type<SnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ghostpath", "snapshot_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = CustomPacketPayload.codec(
        (p,b) -> { b.writeLong(p.transfer); b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.data); },
        b -> new SnapshotPayload(b.readLong(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK)));
    @Override public Type<SnapshotPayload> type() { return TYPE; }
}
