package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.ui.UpdatesScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;

/**
 * Dev runs only, with {@code MODUPDATECHECKER_AUTOTEST=1}: once the check is done, screenshots the
 * title screen (with the button), opens the updates screen, screenshots it and quits. The
 * screenshots land in {@code run/screenshots}.
 */
public final class DevAutotest {

    private static final boolean ENABLED = FabricLoader.getInstance().isDevelopmentEnvironment()
            && "1".equals(System.getenv("MODUPDATECHECKER_AUTOTEST"));
    private static int step;
    private static int wait;

    private DevAutotest() {}

    /** An autotest is driving the game (nothing else should open screens). */
    static boolean active() {
        return ENABLED || ONECONFIG;
    }

    /** {@code MODUPDATECHECKER_AUTOTEST=oneconfig}: goes through the OneConfig page instead. */
    private static final boolean ONECONFIG = FabricLoader.getInstance().isDevelopmentEnvironment()
            && "oneconfig".equals(System.getenv("MODUPDATECHECKER_AUTOTEST"));

    /** Called as the mod starts, before the OneConfig page is built (it checks what to hide then). */
    static void early() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        if ("1".equals(System.getenv("MODUPDATECHECKER_AUTOTEST_PRISM"))) PrismIntegration.devShow = true;
        if ("1".equals(System.getenv("MODUPDATECHECKER_AUTOTEST_ONECLIENT"))) OneClientIntegration.devShow = true;
    }

    public static void tick() {
        if (!ENABLED && !ONECONFIG) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (wait > 0) {
            wait--;
            return;
        }
        if (ONECONFIG) {
            oneConfigTick(mc);
            return;
        }
        switch (step) {
            case 0 -> {
                if (mc.currentScreen instanceof GuiMainMenu && !UpdateChecker.running() && UpdateChecker.checkedAt() != 0) {
                    step++;
                    wait = 40;
                }
            }
            case 1 -> {
                shot(mc, "autotest-title.png");
                devTheme();
                if ("1".equals(System.getenv("MODUPDATECHECKER_AUTOTEST_PRISM"))) PrismIntegration.devShow = true;
                        mc.displayGuiScreen(UpdatesScreen.create(mc.currentScreen));
                step++;
                wait = 60;
            }
            case 2 -> {
                shot(mc, "autotest-updates.png");
                step++;
                if ("1".equals(System.getenv("MODUPDATECHECKER_AUTOTEST_FIX"))) {
                    // Removes every problem the screen lists, as its buttons would.
                    final var problems = com.ootruffle.modupdatechecker.core.ModProblems.current();
                    final var jars = new java.util.ArrayList<>(com.ootruffle.modupdatechecker.install.ModRemover.extraCopies(problems, true));
                    jars.addAll(problems.otherLoaders);
                    jars.addAll(problems.replaced);
                    com.ootruffle.modupdatechecker.install.ModRemover.removeAll(jars);
                    step = 4;
                    wait = 40;
                } else if ("1".equals(System.getenv("MODUPDATECHECKER_AUTOTEST_DOWNLOAD"))) {
                    com.ootruffle.modupdatechecker.install.Installer.downloadAll();
                    wait = 20;
                } else {
                    mc.shutdown();
                }
            }
            case 3 -> {
                for (ModStatus m : UpdateChecker.mods()) {
                    if (m.state == ModStatus.State.DOWNLOADING) return;
                }
                wait = 20;
                step++;
            }
            case 4 -> {
                shot(mc, "autotest-downloaded.png");
                step++;
                mc.shutdown();
            }
            default -> {
            }
        }
    }

    private static void oneConfigTick(Minecraft mc) {
        switch (step) {
            case 0 -> {
                if (mc.currentScreen instanceof GuiMainMenu && !UpdateChecker.running() && UpdateChecker.checkedAt() != 0) {
                    step++;
                    wait = 40;
                }
            }
            case 1 -> {
                com.ootruffle.modupdatechecker.compat.OneConfigCompat.devOpenPage();
                step++;
                wait = 60;
            }
            case 2 -> {
                shot(mc, "autotest-oc-page.png");
                com.ootruffle.modupdatechecker.compat.OneConfigCompat.devPress("updateAll");
                step++;
                wait = 30;
            }
            case 3 -> {
                shot(mc, "autotest-oc-updateall.png");
                com.ootruffle.modupdatechecker.compat.OneConfigCompat.devPress("openScreen");
                step++;
                wait = 60;
            }
            case 4 -> {
                for (ModStatus m : UpdateChecker.mods()) {
                    if (m.state == ModStatus.State.DOWNLOADING) return;
                }
                shot(mc, "autotest-oc-screen.png");
                step++;
                mc.shutdown();
            }
            default -> {
            }
        }
    }

    /**
     * With {@code MODUPDATECHECKER_AUTOTEST_THEME} (a OneConfig theme name) and optionally
     * {@code MODUPDATECHECKER_AUTOTEST_ACCENT} (hex RGB), switches OneConfig to them.
     */
    private static void devTheme() {
        final String theme = System.getenv("MODUPDATECHECKER_AUTOTEST_THEME");
        final String accent = System.getenv("MODUPDATECHECKER_AUTOTEST_ACCENT");
        try {
            final Class<?> cls = Class.forName("org.polyfrost.oneconfig.internal.ThemeConfig");
            if (theme != null) cls.getField("activeTheme").set(null, theme);
            if (accent != null) {
                final Class<?> polyColor = Class.forName("org.polyfrost.compose.render.PolyColor");
                final int argb = 0xFF000000 | Integer.parseInt(accent, 16);
                Object color = null;
                for (var c : polyColor.getConstructors()) {
                    if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == int.class) color = c.newInstance(argb);
                }
                if (color == null) {
                    for (var m : polyColor.getMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == int.class && polyColor.isAssignableFrom(m.getReturnType())) {
                            color = m.invoke(null, argb);
                            break;
                        }
                    }
                }
                if (color != null) cls.getField("accentColor").set(null, color);
                else ModUpdateChecker.LOGGER.warn("Autotest: couldn't make a PolyColor");
            }
            ModUpdateChecker.LOGGER.info("Autotest theme: {} accent {}", cls.getField("activeTheme").get(null), accent);
        } catch (Throwable e) {
            if (theme != null || accent != null) ModUpdateChecker.LOGGER.warn("Autotest: couldn't set the OneConfig theme: {}", e.toString());
        }
    }

    private static void shot(Minecraft mc, String name) {
        ModUpdateChecker.LOGGER.info("Autotest screenshot: {}", ScreenShotHelper.saveScreenshot(
                mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer()).getUnformattedText());
    }
}
