package obsidivision.ghostpath.record;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Unseeded random draws by chain work that are known to be harmless (DESIGN ruling, 1b follow-up). They are still
 * logged as {@link ChainEvent.Kind#UNSEEDED_DRAW}, marked "allowed"; any site not listed here is logged as a warning.
 *
 * <p>Key: "full.class.Name.method" of the first caller outside {@code RandomSource}, {@code Mth} and {@code Util}. Not the
 * line: line numbers differ between builds of the jar (a second checkout failed the tests on them) and move with every Minecraft
 * update. The line stays in the log message for humans. The price: every draw in a listed method is allowed, so only methods whose
 * draws are all harmless belong here (checked against the 26.2 source, quoted per entry). A renamed method turns the entry back
 * into a warning, which is the safe direction.
 */
public final class UnseededDrawAllowlist {
    private static final Map<String, String> ALLOWED = Map.of(
        // 26.2 PistonBaseBlock.triggerEvent has exactly two draws, both the pitch of a sound: :172 extend (random.nextFloat() * 0.25F + 0.6F)
        // and :217 contract (random.nextFloat() * 0.15F + 0.6F)
        "net.minecraft.world.level.block.piston.PistonBaseBlock.triggerEvent", "piston extend/contract sound pitch; changes no block, entity or later draw by chain work",
        // 26.2 NoteBlock.triggerEvent (:148-174) has exactly one draw: :171 level.getRandom().nextLong(), the seed of the note's sound (playSeededSound)
        "net.minecraft.world.level.block.NoteBlock.triggerEvent", "note block sound seed; changes no block, entity or later draw by chain work",
        // 26.2 DoorBlock.playSound (:253-255): one draw, the pitch of the open/close sound (random.nextFloat() * 0.1F + 0.9F)
        "net.minecraft.world.level.block.DoorBlock.playSound", "door open/close sound pitch; changes no block, entity or later draw by chain work",
        // 26.2 TrapDoorBlock.playSound (:122-127): one draw, the same pitch
        "net.minecraft.world.level.block.TrapDoorBlock.playSound", "trapdoor open/close sound pitch; changes no block, entity or later draw by chain work"
    );

    private UnseededDrawAllowlist() {
    }

    /** The reason this site is allowed, or null if it is not on the list. */
    public static @Nullable String reason(String classAndMethod) {
        return ALLOWED.get(classAndMethod);
    }
}
