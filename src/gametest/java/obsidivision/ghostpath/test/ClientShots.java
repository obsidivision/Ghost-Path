package obsidivision.ghostpath.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;

/**
 * Screenshots that include gizmos. {@code ClientGameTestContext.takeScreenshot} re-renders a frame itself
 * ({@code GameRenderer.update/extract/render} from the test thread, outside {@code Minecraft.runTick}) and that frame has no gizmos:
 * neither ours nor vanilla's (F3+G, F3+B). This helper reads the real main render target with vanilla's own
 * {@link Screenshot#takeScreenshot} between two normal frames, and that image has them (see {@code GizmoVisibilityGameTest}).
 */
final class ClientShots {
    private ClientShots() {
    }

    /** Saves the main render target as {@code screenshots/<name>.png} in the client run directory and returns the path. */
    static Path capture(ClientGameTestContext context, String name) {
        Path file = Path.of("screenshots", name + ".png").toAbsolutePath();
        AtomicBoolean done = new AtomicBoolean();
        context.runOnClient(client -> Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                done.set(true);
            }
        }));
        for (int i = 0; i < 200 && !done.get(); i++) {
            context.waitTick();
        }
        if (!done.get()) {
            throw new AssertionError("the screenshot " + name + " was not written");
        }
        return file;
    }

    /** Number of pixels of the PNG for which {@code rgb} (0xRRGGBB) returns true. */
    static int count(Path png, IntPredicate rgb) {
        try {
            BufferedImage image = ImageIO.read(png.toFile());
            int n = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if (rgb.test(image.getRGB(x, y) & 0xFFFFFF)) {
                        n++;
                    }
                }
            }
            return n;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Pixels whose colour differs between the two PNGs (same size) by more than {@code threshold} in some channel. */
    static int countDifferent(Path a, Path b, int threshold) {
        try {
            BufferedImage x = ImageIO.read(a.toFile());
            BufferedImage y = ImageIO.read(b.toFile());
            int n = 0;
            for (int i = 0; i < x.getWidth(); i++) {
                for (int j = 0; j < x.getHeight(); j++) {
                    int p = x.getRGB(i, j);
                    int q = y.getRGB(i, j);
                    if (Math.abs(r(p) - r(q)) > threshold || Math.abs(g(p) - g(q)) > threshold || Math.abs(b(p) - b(q)) > threshold) {
                        n++;
                    }
                }
            }
            return n;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static int r(int rgb) {
        return rgb >> 16 & 255;
    }

    static int g(int rgb) {
        return rgb >> 8 & 255;
    }

    static int b(int rgb) {
        return rgb & 255;
    }
}
