package com.ootruffle.modupdatechecker.prelaunch;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.UpdateChecker;
import com.ootruffle.modupdatechecker.core.ModProblems;
import com.ootruffle.modupdatechecker.install.Installer;
import com.ootruffle.modupdatechecker.install.ModRemover;
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
import java.awt.event.KeyEvent;
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
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
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
    /** Whether Quit can stop the launch (not in OneClient, which launches anyway). */
    private boolean canCancel = true;
    private final List<Row> rows = new ArrayList<>();
    private final List<ProblemRow> problemRows = new ArrayList<>();
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
    static boolean run(int timeoutSeconds, boolean canCancel) throws Exception {
        final PrelaunchGui gui = new PrelaunchGui(timeoutSeconds);
        gui.canCancel = canCancel;
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
        if (canCancel) footer.add(quitButton, BorderLayout.WEST);
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
            if (mod.changelog != null) {
                final JButton changes = button("Changes", false);
                changes.setFont(font(Font.PLAIN, 12f));
                changes.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                changes.setToolTipText("What's new in " + mod.latestVersion);
                changes.addActionListener(e -> showChangelog(mod));
                right.add(changes);
                this.changes = changes;
            } else {
                this.changes = null;
            }
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
        private final JButton changes;

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

    /** A problem in the mods folder (see {@link ModProblems}): checkbox, what's wrong, and the jars it takes out. */
    private final class ProblemRow {
        final List<ModProblems.Jar> jars;
        final JCheckBox box;
        final JLabel status;

        ProblemRow(String title, String detail, List<ModProblems.Jar> jars, boolean ticked) {
            this.jars = jars;
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
            box.setSelected(ticked);
            box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            box.addActionListener(e -> refreshButton());
            card.add(box, BorderLayout.WEST);

            final JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(label(title, TEXT, font(Font.BOLD, 14f)));
            text.add(label(detail, TEXT_SECONDARY, font(Font.PLAIN, 12f)));
            final StringBuilder tip = new StringBuilder("<html>Moves to removed-mods:");
            for (ModProblems.Jar j : jars) tip.append("<br>").append(j.fileName().replace("<", "&lt;"));
            text.setToolTipText(tip.append("</html>").toString());
            text.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (box.isEnabled() && !installing) {
                        box.setSelected(!box.isSelected());
                        refreshButton();
                    }
                }
            });
            card.add(text, BorderLayout.CENTER);

            final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            right.setOpaque(false);
            status = label("", TEXT_SECONDARY, font(Font.PLAIN, 12f));
            right.add(status);
            card.add(right, BorderLayout.EAST);

            list.add(card);
            list.add(Box.createVerticalStrut(8));
        }

        void setStatus(String text, Color color) {
            status.setText(text);
            status.setForeground(color);
        }
    }

    private static String versionOf(ModProblems.Jar j) {
        return j.version != null ? j.version : j.fileName();
    }

    /** One row per problem the last scan found. */
    private void addProblemRows(ModProblems.Result result) {
        for (ModProblems.Jar j : result.replaced) {
            final String name = ModProblems.REPLACED.getOrDefault(j.id, j.label());
            problemRows.add(new ProblemRow(name + " isn't needed any more",
                    "Mod Update Checker finds duplicate mods now. Removes " + j.fileName(), List.of(j), true));
        }
        for (ModProblems.Group g : result.duplicates) {
            final StringBuilder extra = new StringBuilder();
            // Versions tell the copies apart, unless they're the same: then the file names do.
            boolean sameVersions = false;
            for (ModProblems.Jar j : g.extra()) sameVersions |= versionOf(j).equals(versionOf(g.keep()));
            for (ModProblems.Jar j : g.extra()) {
                extra.append(extra.length() == 0 ? "" : ", ").append(sameVersions ? j.fileName() : versionOf(j));
            }
            problemRows.add(new ProblemRow(g.name + " is installed " + g.jars.size() + " times"
                            + (g.identical ? " (identical copies)" : g.byNameOnly ? " (same name, different ids)" : ""),
                    "Keeps " + (sameVersions ? g.keep().fileName() : versionOf(g.keep())) + ", removes " + extra, g.extra(), !g.byNameOnly));
        }
        if (!result.otherLoaders.isEmpty()) {
            final StringBuilder names = new StringBuilder();
            for (ModProblems.Jar j : result.otherLoaders) {
                names.append(names.length() == 0 ? "" : ", ").append(j.fileName()).append(" (").append(j.otherLoader).append(')');
            }
            final int n = result.otherLoaders.size();
            problemRows.add(new ProblemRow(n == 1 ? "1 mod is made for another loader" : n + " mods are made for other loaders",
                    "Ornithe never loads " + (n == 1 ? "it" : "them") + ": " + names, result.otherLoaders, false));
        }
    }

    /** The latest version's changelog (from Modrinth or the GitHub release) in a window of its own. */
    private JDialog showChangelog(ModStatus mod) {
        final JDialog dialog = new JDialog(frame, mod.name + " " + mod.latestVersion, false);
        final JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(PAGE);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        final JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(label(mod.name, TEXT, font(Font.BOLD, 18f)));
        header.add(Box.createVerticalStrut(3));
        final String where = mod.source == ModStatus.Source.MODRINTH ? "Modrinth" : "GitHub · " + mod.githubRepo;
        header.add(label("What's new in " + mod.latestVersion + " (you have " + mod.version + ")    " + where,
                TEXT_SECONDARY, font(Font.PLAIN, 12f)));
        root.add(header, BorderLayout.NORTH);

        final JTextArea text = new JTextArea(mod.changelog);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(CARD);
        text.setForeground(TEXT);
        text.setCaretColor(TEXT);
        text.setSelectionColor(ACCENT);
        text.setSelectedTextColor(Color.WHITE);
        text.setFont(font(Font.PLAIN, 13f));
        text.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        text.setCaretPosition(0);
        final JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
        scroll.getViewport().setBackground(CARD);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setBackground(CARD);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(scroll, BorderLayout.CENTER);

        final JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        if (mod.pageUrl != null) {
            final JButton open = button("Open page", false);
            open.addActionListener(e -> browse(mod.pageUrl));
            footer.add(open);
        }
        final JButton close = button("Close", true);
        close.addActionListener(e -> dialog.dispose());
        footer.add(close);
        root.add(footer, BorderLayout.SOUTH);

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().setDefaultButton(close);
        dialog.setContentPane(root);
        dialog.setSize(600, 460);
        dialog.setMinimumSize(new Dimension(420, 260));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return dialog;
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
        int fixes = 0;
        for (ProblemRow p : problemRows) if (p.box.isSelected()) fixes++;
        updateButton.setEnabled(n + fixes > 0 && !installing);
        if (fixes == 0) {
            updateButton.setText(n > 0 ? "Update " + n + " & launch" : rows.isEmpty() ? "Fix selected & launch" : "Update selected & launch");
        } else {
            updateButton.setText((n > 0 ? "Update " + n + ", fix " + fixes : "Fix " + fixes) + " & launch");
        }
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
            final ModProblems.Result problems = ModProblems.current();
            if (installable == 0 && problems.isEmpty()) {
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
            if (problems.isEmpty()) {
                subtitle.setText(UpdateChecker.summary() + ". Pick what to update before the game starts.");
            } else {
                final int n = problemCount(problems);
                subtitle.setText((n == 1 ? "1 problem in your mods folder" : n + " problems in your mods folder")
                        + (installable > 0 ? " and " + installable + (installable == 1 ? " update" : " updates") : "")
                        + ". Pick what to fix before the game starts.");
                subtitle.setForeground(YELLOW);
                skipButton.setText(installable > 0 ? "Launch without changes" : "Launch without fixing");
                addProblemRows(problems);
            }
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
            } else if ("changelog".equals(System.getenv("MODUPDATECHECKER_GUI_AUTO"))) {
                // Opens the first changelog, pictures it, then launches without updating.
                for (Row r : rows) {
                    if (r.changes == null) continue;
                    final JDialog d = showChangelog(r.mod);
                    final Timer shot = new Timer(500, ev -> {
                        snapshot(d.getContentPane(), dir, "gui-changelog.png");
                        skipButton.doClick();
                    });
                    shot.setRepeats(false);
                    shot.start();
                    return;
                }
                System.out.println("[ModUpdateChecker] No update has a changelog");
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
        snapshot(frame.getContentPane(), dir, name);
    }

    private static void snapshot(java.awt.Container c, String dir, String name) {
        try {
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, name));
        } catch (Exception e) {
            System.out.println("[ModUpdateChecker] Snapshot failed: " + e);
        }
    }

    private static int problemCount(ModProblems.Result r) {
        return r.duplicates.size() + r.replaced.size() + (r.otherLoaders.isEmpty() ? 0 : 1);
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
        final List<ProblemRow> fixes = new ArrayList<>();
        for (ProblemRow p : problemRows) {
            p.box.setEnabled(false);
            if (p.box.isSelected()) fixes.add(p);
        }
        final Thread t = new Thread(() -> {
            // Problems first, so an update never lands next to a copy about to be removed.
            int fixFailed = 0;
            for (ProblemRow p : fixes) {
                final int moved = ModRemover.removeAll(p.jars);
                final boolean all = moved == p.jars.size();
                if (!all) fixFailed++;
                for (ModProblems.Jar j : p.jars) {
                    System.out.println("[ModUpdateChecker]   " + j.fileName() + (ModRemover.isRemoved(j.path) ? ": moved to removed-mods" : ": couldn't be moved"));
                }
                SwingUtilities.invokeLater(() -> {
                    if (all) p.setStatus("Moved to removed-mods", GREEN);
                    else p.setStatus("Couldn't move " + (p.jars.size() - moved) + " (in use?)", RED);
                });
            }
            final int failedFixes = fixFailed;
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
            final boolean failed = ok < chosen.size() || failedFixes > 0;
            SwingUtilities.invokeLater(() -> {
                final String fixed = fixes.isEmpty() ? "" : (fixes.size() - failedFixes) + " of " + fixes.size() + " fixed. ";
                final String installed = chosen.isEmpty() ? "" : installedCount + " of " + chosen.size() + " installed. ";
                subtitle.setText(fixed + installed + (failed ? "Launching anyway..." : "Launching..."));
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
