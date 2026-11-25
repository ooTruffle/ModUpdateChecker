package com.ootruffle.modupdatechecker.prelaunch;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.UpdateChecker;
import com.ootruffle.modupdatechecker.install.Installer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;

/**
 * The pre-launch update picker ({@code --gui}): a small Swing window in OneConfig's Polyfrosted
 * Dark style. (The in-game screen uses PolyCompose, which needs the game running, so it can't be
 * used before launch.) Lists the updates with a checkbox each; the ticked ones are installed before
 * the game starts. Closes by itself when there's nothing to update.
 */
final class PrelaunchGui {

    private static final Color PAGE = new Color(0x11171C);
    private static final Color CARD = new Color(0x1A2229);
    private static final Color CARD_BORDER = new Color(0x2A333B);
    private static final Color CHIP = new Color(0x232D32);
    private static final Color CHIP_HOVER = new Color(0x2E3A41);
    private static final Color TEXT = new Color(0xD5DBFF);
    private static final Color TEXT_SECONDARY = new Color(0x8A8F9C);
    private static final Color ACCENT = new Color(0x2B4BFF);
    private static final Color ACCENT_HOVER = new Color(0x4A65FF);
    private static final Color GREEN = new Color(0x3DDC84);
    private static final Color YELLOW = new Color(0xFFC94A);
    private static final Color RED = new Color(0xFF6B6B);

    private final CountDownLatch done = new CountDownLatch(1);
    /** Set by the Quit button: don't launch the game. */
    private volatile boolean quit;
    private final int timeoutSeconds;
    private JFrame frame;
    private JLabel subtitle;
    private JPanel list;
    private JButton updateButton;
    private JButton skipButton;
    private JButton quitButton;
    private final List<Row> rows = new ArrayList<>();
    private volatile boolean installing;

    private PrelaunchGui(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Whether a window can be shown at all. */
    static boolean available() {
        return !GraphicsEnvironment.isHeadless();
    }

    /**
     * Shows the window, checks, and returns once the player has chosen (or there was nothing to
     * do). Returns false if they pressed Quit, so the game shouldn't launch.
     */
    static boolean run(int timeoutSeconds) throws Exception {
        final PrelaunchGui gui = new PrelaunchGui(timeoutSeconds);
        SwingUtilities.invokeAndWait(gui::build);
        final Thread check = new Thread(gui::check, "ModUpdateChecker-Check");
        check.setDaemon(true);
        check.start();
        gui.done.await();
        return !gui.quit;
    }

    // ---- window ----

    private static Font font(int style, float size) {
        for (String name : new String[] {"Inter", "Segoe UI", "SF Pro Text", "Helvetica Neue", "Dialog"}) {
            final Font f = new Font(name, style, Math.round(size));
            if (f.getFamily().equals(name) || name.equals("Dialog")) return f.deriveFont(size);
        }
        return new Font(Font.DIALOG, style, Math.round(size));
    }

    private void build() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        frame = new JFrame("Mod Updates");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!installing) finish(); // Closing means "launch without updating".
            }
        });

        final JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(PAGE);
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));

        final JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        final JLabel title = label("Mod Updates", TEXT, font(Font.BOLD, 21f));
        subtitle = label("Checking your mods...", TEXT_SECONDARY, font(Font.PLAIN, 13f));
        header.add(title);
        header.add(Box.createVerticalStrut(3));
        header.add(subtitle);
        root.add(header, BorderLayout.NORTH);

        list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(PAGE);
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(PAGE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setBackground(PAGE);
        root.add(scroll, BorderLayout.CENTER);

        // Quit on the left, away from the two launch buttons.
        final JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        quitButton = button("Quit", false);
        quitButton.setForeground(RED);
        quitButton.setToolTipText("Don't update and don't start the game");
        quitButton.addActionListener(e -> {
            quit = true;
            finish();
        });
        final JPanel launch = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        launch.setOpaque(false);
        skipButton = button("Launch without updating", false);
        skipButton.addActionListener(e -> finish());
        updateButton = button("Update selected & launch", true);
        updateButton.setEnabled(false);
        updateButton.addActionListener(e -> installSelected());
        launch.add(skipButton);
        launch.add(updateButton);
        footer.add(quitButton, BorderLayout.WEST);
        footer.add(launch, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setSize(720, 480);
        frame.setMinimumSize(new Dimension(560, 320));
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true); // Launchers often sit on top; don't hide behind them.
        frame.setVisible(true);
        frame.toFront();
        frame.setAlwaysOnTop(false);
    }

    private static JLabel label(String text, Color color, Font font) {
        final JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(font);
        return l;
    }

    /** A rounded button: the accent for the main action, a dark chip otherwise. */
    private static JButton button(String text, boolean primary) {
        final JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                final Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final boolean hover = getModel().isRollover() && isEnabled();
                Color bg = primary ? (hover ? ACCENT_HOVER : ACCENT) : (hover ? CHIP_HOVER : CHIP);
                if (!isEnabled()) bg = new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 90);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                if (!primary) {
                    g2.setColor(CARD_BORDER);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(primary ? Color.WHITE : TEXT);
        b.setFont(font(Font.BOLD, 13f));
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return b;
    }

    /** The accent-filled checkbox OneConfig uses. */
    private static final class CheckIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            final boolean on = ((JCheckBox) c).isSelected();
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(on ? ACCENT : CHIP);
            g2.fillRoundRect(x, y, 18, 18, 6, 6);
            g2.setColor(on ? ACCENT : CARD_BORDER);
            g2.drawRoundRect(x, y, 17, 17, 6, 6);
            if (on) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawPolyline(new int[] {x + 5, x + 8, x + 13}, new int[] {y + 9, y + 12, y + 6}, 3);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static final class RoundBorder extends AbstractBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(x, y, w - 1, h - 1, 14, 14);
            g2.dispose();
        }

        @Override
        public java.awt.Insets getBorderInsets(Component c) {
            return new java.awt.Insets(10, 14, 10, 14);
        }
    }

    /** One update: checkbox, name, versions, source, status and an Open button. */
    private final class Row {
        final ModStatus mod;
        final JCheckBox box;
        final JLabel status;

        Row(ModStatus mod) {
            this.mod = mod;
            final JPanel card = new JPanel(new BorderLayout(12, 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    final Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(CARD);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                    g2.dispose();
                }
            };
            card.setOpaque(false);
            card.setBorder(new RoundBorder());
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));

            box = new JCheckBox();
            box.setOpaque(false);
            box.setIcon(new CheckIcon());
            box.setFocusPainted(false);
            final boolean downloadable = mod.canDownload();
            box.setSelected(downloadable);
            box.setEnabled(downloadable);
            box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            box.addActionListener(e -> refreshButton());
            card.add(box, BorderLayout.WEST);

            final JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(label(mod.name, TEXT, font(Font.BOLD, 14f)));
            final String where = mod.source == ModStatus.Source.MODRINTH ? "Modrinth" : "GitHub · " + mod.githubRepo;
            text.add(label(mod.version + "  →  " + mod.latestVersion + "    " + where, TEXT_SECONDARY, font(Font.PLAIN, 12f)));
            card.add(text, BorderLayout.CENTER);
            // Clicking the card toggles it too.
            text.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (box.isEnabled() && !installing) {
                        box.setSelected(!box.isSelected());
                        refreshButton();
                    }
                }
            });

            final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            right.setOpaque(false);
            status = label(downloadable ? "" : "No file to download", TEXT_SECONDARY, font(Font.PLAIN, 12f));
            right.add(status);
            ignore = button("Ignore", false);
            ignore.setFont(font(Font.PLAIN, 12f));
            ignore.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            ignore.setToolTipText("Skip " + mod.latestVersion + "; a newer version shows again");
            ignore.addActionListener(e -> ignore());
            right.add(ignore);
            if (mod.pageUrl != null) {
                final JButton open = button("Open", false);
                open.setFont(font(Font.PLAIN, 12f));
                open.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                open.addActionListener(e -> browse(mod.pageUrl));
                right.add(open);
            }
            card.add(right, BorderLayout.EAST);

            this.card = card;
            gap = Box.createVerticalStrut(8);
            list.add(card);
            list.add(gap);
        }

        private final JPanel card;
        private final Component gap;
        private final JButton ignore;

        /** Skips this version (the same list the in-game screen uses) and takes the row away. */
        private void ignore() {
            if (installing) return;
            Config.get().ignoredVersions.put(mod.id, mod.latestVersion);
            Config.save();
            System.out.println("[ModUpdateChecker]   Ignoring " + mod.name + " " + mod.latestVersion);
            list.remove(card);
            list.remove(gap);
            rows.remove(this);
            list.revalidate();
            list.repaint();
            refreshButton();
            if (rows.isEmpty()) {
                subtitle.setText("Nothing left to update.");
            }
        }

        void setStatus(String text, Color color) {
            status.setText(text);
            status.setForeground(color);
        }
    }

    private static void browse(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshButton() {
        int n = 0;
        for (Row r : rows) if (r.box.isSelected()) n++;
        updateButton.setEnabled(n > 0 && !installing);
        updateButton.setText(n > 0 ? "Update " + n + " & launch" : "Update selected & launch");
    }

    // ---- work ----

    private void check() {
        final Thread worker = new Thread(UpdateChecker::checkNow, "ModUpdateChecker");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        } catch (InterruptedException ignored) {
        }
        final boolean timedOut = worker.isAlive();
        final List<ModStatus> updates = new ArrayList<>();
        if (!timedOut) {
            for (ModStatus m : UpdateChecker.mods()) if (m.wantsUpdate()) updates.add(m);
        }
        SwingUtilities.invokeLater(() -> {
            if (timedOut) {
                subtitle.setText("The check took longer than " + timeoutSeconds + "s, so it was skipped.");
                subtitle.setForeground(YELLOW);
                return;
            }
            int installable = 0;
            for (ModStatus m : updates) if (m.canDownload()) installable++;
            if (installable == 0) {
                // Nothing that can be installed from here: don't hold the launch up. Updates without
                // a file still go to the launcher's log.
                for (ModStatus m : updates) {
                    System.out.println("[ModUpdateChecker]   " + m.name + " " + m.version + " -> " + m.latestVersion
                            + ": no file to download, get it from " + m.pageUrl);
                }
                subtitle.setText(UpdateChecker.summary() + ". Launching...");
                final Timer t = new Timer(900, e -> finish());
                t.setRepeats(false);
                t.start();
                return;
            }
            subtitle.setText(UpdateChecker.summary() + ". Pick what to update before the game starts.");
            for (ModStatus m : updates) rows.add(new Row(m));
            list.revalidate();
            list.repaint();
            refreshButton();
            devTest();
        });
    }

    /**
     * Testing only, with {@code MODUPDATECHECKER_GUI_SNAPSHOT=<folder>}: saves pictures of the
     * window, and with {@code MODUPDATECHECKER_GUI_AUTO=update} presses the update button.
     */
    private void devTest() {
        final String dir = System.getenv("MODUPDATECHECKER_GUI_SNAPSHOT");
        if (dir == null) return;
        final Timer t = new Timer(700, e -> {
            snapshot(dir, "gui-list.png");
            if ("ignore".equals(System.getenv("MODUPDATECHECKER_GUI_AUTO"))) {
                // Ignores the first row, pictures the result, then launches without updating.
                if (!rows.isEmpty()) rows.get(0).ignore.doClick();
                snapshot(dir, "gui-ignored.png");
                skipButton.doClick();
            } else if ("quit".equals(System.getenv("MODUPDATECHECKER_GUI_AUTO"))) {
                quitButton.doClick();
            } else if ("update".equals(System.getenv("MODUPDATECHECKER_GUI_AUTO"))) {
                installSelected();
                final Timer after = new Timer(100, null);
                after.addActionListener(ev -> {
                    boolean busy = false;
                    for (Row r : rows) if ("Downloading...".equals(r.status.getText())) busy = true;
                    if (!busy) {
                        after.stop();
                        snapshot(dir, "gui-installed.png");
                    }
                });
                after.setInitialDelay(1500);
                after.start();
            }
        });
        t.setRepeats(false);
        t.start();
    }

    private void snapshot(String dir, String name) {
        try {
            final java.awt.Container c = frame.getContentPane();
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, name));
        } catch (Exception e) {
            System.out.println("[ModUpdateChecker] Snapshot failed: " + e);
        }
    }

    private void installSelected() {
        installing = true;
        updateButton.setEnabled(false);
        skipButton.setEnabled(false);
        quitButton.setEnabled(false);
        final List<Row> chosen = new ArrayList<>();
        for (Row r : rows) {
            r.box.setEnabled(false);
            r.ignore.setEnabled(false);
            if (r.box.isSelected()) chosen.add(r);
        }
        final Thread t = new Thread(() -> {
            int ok = 0;
            for (Row r : chosen) {
                SwingUtilities.invokeLater(() -> r.setStatus("Downloading...", ACCENT_HOVER));
                final boolean installed = Installer.installNow(r.mod);
                if (installed) ok++;
                final String error = r.mod.error;
                System.out.println("[ModUpdateChecker]   " + r.mod.name + " " + r.mod.version + " -> " + r.mod.latestVersion
                        + (installed ? ": installed" : ": " + error));
                SwingUtilities.invokeLater(() -> {
                    if (installed) r.setStatus("Installed", GREEN);
                    else r.setStatus(error == null ? "Failed" : error, RED);
                });
            }
            final int installedCount = ok;
            final boolean failed = ok < chosen.size();
            SwingUtilities.invokeLater(() -> {
                subtitle.setText(installedCount + " of " + chosen.size() + " installed." + (failed ? " Launching anyway..." : " Launching..."));
                final Timer timer = new Timer(failed ? 3500 : 1200, e -> finish());
                timer.setRepeats(false);
                timer.start();
            });
        }, "ModUpdateChecker-Install");
        t.setDaemon(true);
        t.start();
    }

    private void finish() {
        if (frame != null) frame.dispose();
        done.countDown();
    }
}
