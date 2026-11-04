package obsidivision.ghostpath.ghost;

import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/**
 * The ghost's level data: everything is read from the real overworld's data (DerivedLevelData, 26.2
 * DerivedLevelData.java:12-40, whose setters are no-ops), except the game time, which is the ghost's own counter
 * (DESIGN finding 2, requirement 1). {@code ServerLevel.getGameTime()} reads it through {@code Level.levelData}
 * (Level.java:914), so scheduled ticks come due by the ghost's time.
 */
public final class GhostLevelData extends DerivedLevelData {
    private long gameTime;

    public GhostLevelData(WorldData worldData, ServerLevelData overworld) {
        super(worldData, overworld);
        this.gameTime = overworld.getGameTime();
    }

    @Override
    public long getGameTime() {
        return this.gameTime;
    }

    @Override
    public void setGameTime(long time) {
        this.gameTime = time;
    }
}
