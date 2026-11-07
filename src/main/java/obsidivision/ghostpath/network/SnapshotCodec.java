package obsidivision.ghostpath.network;

import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.AreaData;
import net.minecraft.core.BlockPos;
import obsidivision.ghostpath.saved.*;

/** Versioned, bounded snapshot transport. Uses the lossless saved-path representation for flight data. */
public final class SnapshotCodec {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static byte[] encode(PathSnapshot s) throws IOException {
        var o = s.origin();
        var p = new SavedPath("network", s.dimension(), o == null ? new int[]{0,0,0} : new int[]{o.x(),o.y(),o.z()},
            s.ticks(), s.limitHit(), o == null ? 0 : o.gameTime(), "", true, s.source(), s.lines(), Map.of());
        CompoundTag tag = SavedCodec.toNbt(p);
        tag.putLong("serial", s.serial());
        tag.putBoolean("hasOrigin", o != null);
        if (s.area() != null) tag.put("area", area(s.area()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        if (out.size() > MAX_BYTES) throw new IOException("prediction exceeds 8 MiB transport limit");
        return out.toByteArray();
    }
    public static PathSnapshot decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("prediction too large");
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(64 * 1024 * 1024L));
        SavedPath p = SavedCodec.fromNbt(tag);
        return new PathSnapshot(tag.getLongOr("serial", 0), p.source(), p.dimension(), p.ticks(), p.limitHit(), p.lines(), tag.contains("area") ? area(tag.getCompoundOrEmpty("area")) : null)
            .withOrigin(tag.getBooleanOr("hasOrigin", false) ? new PathSnapshot.Origin(p.trigger()[0], p.trigger()[1], p.trigger()[2], p.gameTime()) : null);
    }
    private static CompoundTag area(AreaData a) {
        CompoundTag t = new CompoundTag();
        t.putIntArray("counts", new int[]{a.totalBoxes(),a.writeBlocks(),a.readBlocks(),a.watchedTotal(),a.margin(),a.minY(),a.maxY()});
        t.putBoolean("skipped", a.watchedSkipped());
        t.putIntArray("boxes", boxes(a.boxes())); t.putIntArray("watched", boxes(a.watched()));
        if(a.copied()!=null)t.putIntArray("copied",a.copied());
        if(a.base()!=null)t.putIntArray("base",a.base());
        if(a.change()!=null) {
            t.putString("change",a.change().label());
            if(a.change().pos()!=null)t.putIntArray("pos",new int[]{a.change().pos().getX(),a.change().pos().getY(),a.change().pos().getZ()});
        }
        return t;
    }
    private static int[] boxes(List<AreaData.Box> boxes) {
        int[] out=new int[boxes.size()*7]; int i=0;
        for(var b:boxes) {out[i++]=b.x0();out[i++]=b.y0();out[i++]=b.z0();out[i++]=b.x1();out[i++]=b.y1();out[i++]=b.z1();out[i++]=b.write()?1:0;}
        return out;
    }
    private static List<AreaData.Box> boxes(int[] a) throws IOException {
        if(a.length%7!=0 || a.length>3500*7)throw new IOException("invalid area boxes");
        List<AreaData.Box> out=new ArrayList<>();
        for(int i=0;i<a.length;i+=7)out.add(new AreaData.Box(a[i],a[i+1],a[i+2],a[i+3],a[i+4],a[i+5],a[i+6]!=0));
        return List.copyOf(out);
    }
    private static AreaData area(CompoundTag t) throws IOException {
        int[] c=t.getIntArray("counts").orElseThrow(()->new IOException("missing area counts"));
        if(c.length!=7)throw new IOException("invalid area counts");
        int[] pos=t.getIntArray("pos").orElse(null);
        var change=t.contains("change")?new AreaData.Change(pos==null?null:new BlockPos(pos[0],pos[1],pos[2]),t.getStringOr("change",""),System.currentTimeMillis()):null;
        return new AreaData(boxes(t.getIntArray("boxes").orElse(new int[0])),c[0],c[1],c[2],boxes(t.getIntArray("watched").orElse(new int[0])),c[3],t.getBooleanOr("skipped",false),c[4],t.getIntArray("copied").orElse(null),t.getIntArray("base").orElse(null),c[5],c[6],change);
    }
    private SnapshotCodec() {}
}
