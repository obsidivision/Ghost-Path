package obsidivision.ghostpath.client;

import net.fabricmc.fabric.api.client.networking.v1.*;
import obsidivision.ghostpath.network.*;
import obsidivision.ghostpath.preview.PathStore;

/** Client-thread reception. An incomplete transfer never replaces the displayed snapshot. */
public final class RemotePaths {
    private static SnapshotAssembler assembler = new SnapshotAssembler();
    static boolean connected;
    public static volatile long receivedSnapshots;
    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SnapshotPayload.TYPE, (p, context) -> context.client().execute(() -> receive(p)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset(client.getSingleplayerServer() == null));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(true));
    }
    private static void reset(boolean clear) {
        connected = false; assembler = new SnapshotAssembler();
        Estimates.clear();
        Inspector.resetSession();
        PathRenderer.resetSession();
        if (clear) PathStore.clearRemote();
    }
    private static void receive(SnapshotPayload p) {
        if (net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null) return;
        try {
            var snapshot = assembler.accept(p);
            connected = true;
            if (snapshot != null) { PathStore.receive(snapshot); receivedSnapshots++; }
        } catch (java.io.IOException | RuntimeException e) { Toasts.show("Could not read server prediction"); }
    }
    private RemotePaths() {}
}
