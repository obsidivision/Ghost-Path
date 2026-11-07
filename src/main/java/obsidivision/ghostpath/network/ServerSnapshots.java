package obsidivision.ghostpath.network;

import java.util.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import obsidivision.ghostpath.GhostPath;
import obsidivision.ghostpath.preview.*;

/** Sends only to clients advertising the receiver; no serverbound payload can change a world. */
public final class ServerSnapshots {
    private record Transfer(byte[] bytes, long id, int next) {}
    private static final Map<UUID, Transfer> pending = new HashMap<>();
    private static PathSnapshot last;
    private static byte[] encoded;
    private static long transferId;
    public static void init() { PayloadTypeRegistry.clientboundPlay().register(SnapshotPayload.TYPE, SnapshotPayload.CODEC); }
    public static void tick(MinecraftServer server) {
        PathSnapshot s = PathStore.latest();
        if (s != last) {
            last = s;
            encoded = null;
            pending.clear();
            transferId++;
            if (s != null) try { encoded = SnapshotCodec.encode(s); }
            catch (java.io.IOException e) { GhostPath.LOGGER.warn("Prediction could not be sent to clients", e); }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) queue(p);
        }
        pending.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Transfer t = pending.get(p.getUUID());
            if (t == null || !ServerPlayNetworking.canSend(p, SnapshotPayload.TYPE)) continue;
            int total = (t.bytes.length + SnapshotPayload.CHUNK - 1) / SnapshotPayload.CHUNK;
            int i = t.next;
            for (int n = 0; n < 2 && i < total; n++, i++) {
                int start = i * SnapshotPayload.CHUNK;
                ServerPlayNetworking.send(p, new SnapshotPayload(t.id, i, total, Arrays.copyOfRange(t.bytes, start, Math.min(t.bytes.length, start + SnapshotPayload.CHUNK))));
            }
            if (i == total) pending.remove(p.getUUID());
            else pending.put(p.getUUID(), new Transfer(t.bytes, t.id, i));
        }
    }
    public static void queue(ServerPlayer player) {
        if (encoded == null) {
            try { encoded = SnapshotCodec.encode(new PathSnapshot(0,"server",player.level().dimension().identifier().toString(),0,false,List.of())); }
            catch (java.io.IOException e) { return; }
        }
        pending.put(player.getUUID(), new Transfer(encoded, transferId, 0));
    }
    public static void clear() { pending.clear(); last = null; encoded = null; PathStore.clear(); }
    private ServerSnapshots() {}
}
