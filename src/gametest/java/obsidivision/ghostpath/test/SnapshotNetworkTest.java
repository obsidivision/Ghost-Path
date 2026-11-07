package obsidivision.ghostpath.test;

import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import obsidivision.ghostpath.network.*;
import obsidivision.ghostpath.preview.*;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

public class SnapshotNetworkTest {
    @GameTest public void snapshotRoundTripsAndReassembles(GameTestHelper h) throws Exception {
        var line = new PathSnapshot.Line("minecraft:tnt",FlightKind.TNT,1,0,new long[]{0},new double[]{-0.0,0.1+0.2,3},new double[]{1,2,3},new double[]{0,0,0,1,1,1},new int[]{80},new int[]{42},new PathSnapshot.End("limit",0,0,0,0),null,null);
        var area = new AreaData(List.of(new AreaData.Box(0,0,0,1,1,1,true)),1,1,0,List.of(),0,false,2,new int[]{0,0,0,0},null,0,1,null);
        var s = new PathSnapshot(7,"rerun","minecraft:overworld",1,true,List.of(line),area,new PathSnapshot.Origin(1,2,3,99));
        byte[] data = SnapshotCodec.encode(s);
        var assembler = new SnapshotAssembler(); int cut = data.length/2;
        h.assertTrue(assembler.accept(new SnapshotPayload(1,0,2,Arrays.copyOfRange(data,0,cut))) == null,"partial snapshot published");
        var result = assembler.accept(new SnapshotPayload(1,1,2,Arrays.copyOfRange(data,cut,data.length)));
        h.assertTrue(result != null && result.serial()==7 && result.origin().gameTime()==99 && result.area().boxes().equals(area.boxes()),"metadata/area lost");
        h.assertTrue(Arrays.equals(result.lines().getFirst().xyz(),line.xyz()) && Double.doubleToRawLongBits(result.lines().getFirst().xyz()[0])==Double.doubleToRawLongBits(-0.0),"positions changed");
        h.succeed();
    }
    @GameTest public void rejectsInvalidAndOutOfOrderChunks(GameTestHelper h) throws Exception {
        var a = new SnapshotAssembler(); int failures=0;
        for(var p:List.of(new SnapshotPayload(1,0,Integer.MAX_VALUE,new byte[0]),new SnapshotPayload(1,1,2,new byte[1]),new SnapshotPayload(1,0,1,new byte[SnapshotPayload.CHUNK+1]))) {
            try { a.accept(p); } catch(java.io.IOException expected) { failures++; }
        }
        h.assertTrue(failures==3,"invalid chunks accepted"); h.succeed();
    }
}
