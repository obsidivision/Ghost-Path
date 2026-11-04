package obsidivision.ghostpath.record;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import obsidivision.ghostpath.mixin.LevelWeatherAccessor;

/**
 * Rain and thunder state of a level at one moment (ruling: saved in the recording, for the ghost). Weather decides
 * which light and heightmap reads happen ({@code Level.precipitationAt}, Level.java:955-969) and whether a burning
 * entity is put out, and it advances during a recording ({@code ServerLevel.advanceWeatherCycle}, ServerLevel.java:709).
 *
 * <p>The flags and timers live in {@code WeatherData} (ServerLevel.getWeatherData, :1691; read and written at :712-751);
 * the levels, which move 0.01 per tick towards the flags (:753-769), are fields of {@code Level} (:120-123).
 *
 * @param canHaveWeather {@code Level.canHaveWeather} (:940): sky light, no ceiling, not the End
 * @param advanceWeather the {@code advance_weather} game rule (timers frozen when false, :713)
 */
public record Weather(
    boolean raining,
    boolean thundering,
    int rainTime,
    int thunderTime,
    int clearWeatherTime,
    float oRainLevel,
    float rainLevel,
    float oThunderLevel,
    float thunderLevel,
    boolean canHaveWeather,
    boolean advanceWeather
) {
    public static Weather of(ServerLevel level) {
        WeatherData data = level.getWeatherData();
        LevelWeatherAccessor levels = (LevelWeatherAccessor) level;
        return new Weather(data.isRaining(), data.isThundering(), data.getRainTime(), data.getThunderTime(), data.getClearWeatherTime(),
            levels.ghostpath$oRainLevel(), levels.ghostpath$rainLevel(), levels.ghostpath$oThunderLevel(), levels.ghostpath$thunderLevel(),
            level.canHaveWeather(), level.getGameRules().get(GameRules.ADVANCE_WEATHER));
    }

    @Override
    public String toString() {
        return (this.raining ? "rain" : "clear") + (this.thundering ? "+thunder" : "") + " (rain level " + this.rainLevel
            + ", thunder level " + this.thunderLevel + ")";
    }
}
