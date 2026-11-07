package obsidivision.ghostpath.network;

import java.io.*;
import obsidivision.ghostpath.preview.PathSnapshot;
import org.jspecify.annotations.Nullable;

/** Bounded, ordered reassembly; a new transfer replaces unfinished data without exposing a partial snapshot. */
public final class SnapshotAssembler {
    private long transfer;
    private int next, total;
    private ByteArrayOutputStream bytes;
    public @Nullable PathSnapshot accept(SnapshotPayload p) throws IOException {
        if (p.total() < 1 || p.total() > (SnapshotCodec.MAX_BYTES + SnapshotPayload.CHUNK - 1) / SnapshotPayload.CHUNK
                || p.index() < 0 || p.index() >= p.total() || p.data().length > SnapshotPayload.CHUNK) throw new IOException("invalid snapshot chunk");
        if (p.index() == 0) { transfer = p.transfer(); next = 0; total = p.total(); bytes = new ByteArrayOutputStream(); }
        if (bytes == null || transfer != p.transfer() || total != p.total() || next != p.index()) { bytes = null; throw new IOException("out-of-order snapshot chunk"); }
        if (bytes.size() + p.data().length > SnapshotCodec.MAX_BYTES) { bytes = null; throw new IOException("snapshot too large"); }
        bytes.writeBytes(p.data()); next++;
        if(next != total) return null;
        byte[] complete = bytes.toByteArray(); bytes = null;
        return SnapshotCodec.decode(complete);
    }
}
