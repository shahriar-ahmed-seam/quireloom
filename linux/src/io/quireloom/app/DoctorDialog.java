package io.quireloom.app;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * UI dialogs for EPUB Doctor (Preflight Validator & Auto-Fixer) and Book Word-Count Analytics.
 */
final class DoctorDialog {

    static void showReport(Component parent, EpubDoctor.Report report, Path repairableEpub) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
            "EPUB Doctor — " + report.targetName(), Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        // Top summary banner.
        var header = UiTheme.card(new BorderLayout(12, 6));
        String verdict = report.healthy()
            ? (report.warnings() == 0 ? "All Checks Passed" : "Ready with Suggestions")
            : "Issues Found — Attention Needed";
        var title = new JLabel(verdict);
        title.setFont(UiTheme.uiFont(Font.BOLD, 17f));
        title.setForeground(report.errors() > 0 ? UiTheme.danger() : (report.warnings() > 0 ? UiTheme.warn() : UiTheme.pass()));
        var counts = UiTheme.mutedLabel(report.passes() + " passed  ·  " + report.warnings() + " warnings  ·  " + report.errors() + " errors");
        header.add(title, BorderLayout.NORTH);
        header.add(counts, BorderLayout.SOUTH);

        // Stats strip.
        var statsGrid = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        statsGrid.setOpaque(false);
        for (var entry : report.stats().entrySet()) {
            var item = new JPanel(new BorderLayout(0, 1));
            item.setOpaque(false);
            var k = UiTheme.mutedLabel(entry.getKey());
            var v = new JLabel(entry.getValue());
            v.setFont(UiTheme.uiFont(Font.BOLD, 13f));
            v.setForeground(UiTheme.ink());
            item.add(k, BorderLayout.NORTH);
            item.add(v, BorderLayout.SOUTH);
            statsGrid.add(item);
        }
        var topBox = new JPanel(new BorderLayout(0, 8));
        topBox.setOpaque(false);
        topBox.add(header, BorderLayout.NORTH);
        topBox.add(statsGrid, BorderLayout.SOUTH);

        // Findings list.
        var listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(UiTheme.surface());
        listPanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        for (var f : report.findings()) {
            var row = new JPanel(new BorderLayout(10, 2));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.border()),
                new EmptyBorder(7, 8, 7, 8)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));

            Color badgeColor = switch (f.severity()) {
                case ERROR -> UiTheme.danger();
                case WARNING -> UiTheme.warn();
                case PASS -> UiTheme.pass();
                case INFO -> UiTheme.muted();
            };
            var badge = new JLabel(f.severity().name());
            badge.setFont(UiTheme.uiFont(Font.BOLD, 10.5f));
            badge.setForeground(badgeColor);
            badge.setPreferredSize(new Dimension(68, 20));

            var textCol = new JPanel(new BorderLayout(0, 2));
            textCol.setOpaque(false);
            var msg = new JLabel("[" + f.category() + "] " + f.message());
            msg.setFont(UiTheme.uiFont(Font.BOLD, 12.5f));
            msg.setForeground(UiTheme.ink());
            textCol.add(msg, BorderLayout.NORTH);
            if (!f.detail().isBlank()) {
                var det = UiTheme.mutedLabel(f.detail());
                textCol.add(det, BorderLayout.SOUTH);
            }
            row.add(badge, BorderLayout.WEST);
            row.add(textCol, BorderLayout.CENTER);
            listPanel.add(row);
        }

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        if (repairableEpub != null) {
            buttons.add(UiTheme.primaryButton("Auto-Fix & Optimize EPUB…", () -> {
                var optimize = new JCheckBox("Optimize & downscale oversized images (max 1600px)", true);
                var orphans = new JCheckBox("Remove unmanifested orphan files", true);
                var ncx = new JCheckBox("Include EPUB 2 NCX compatibility fallback", true);
                var optsPanel = new JPanel(new GridLayout(0, 1, 0, 4));
                optsPanel.add(new JLabel("Upgrade & repair package structure to valid EPUB 3.3:"));
                optsPanel.add(orphans);
                optsPanel.add(optimize);
                optsPanel.add(ncx);
                if (JOptionPane.showConfirmDialog(dialog, optsPanel, "Auto-Fix Options",
                        JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                var save = new JFileChooser(repairableEpub.getParent().toFile());
                String base = repairableEpub.getFileName().toString().replaceFirst("(?i)\\.epub$", "");
                save.setSelectedFile(new File(base + "-fixed.epub"));
                if (save.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
                Path out = save.getSelectedFile().toPath();
                if (!out.toString().toLowerCase().endsWith(".epub")) out = Path.of(out + ".epub");
                try {
                    var summary = EpubDoctor.repair(repairableEpub, out,
                        new EpubDoctor.RepairOptions(true, orphans.isSelected(), optimize.isSelected(),
                            1600, 0.84f, ncx.isSelected()));
                    JOptionPane.showMessageDialog(dialog,
                        "Repaired EPUB saved to " + out.getFileName()
                            + "\n• Upgraded to EPUB 3.3: " + (summary.upgradedFromEpub2() ? "Yes" : "Already 3.x (normalized)")
                            + "\n• Orphan files removed: " + summary.orphansRemoved()
                            + "\n• Images optimized: " + summary.imagesOptimized()
                            + (summary.bytesSaved() > 0 ? " (saved " + EpubDoctor.formatBytes(summary.bytesSaved()) + ")" : ""),
                        "EPUB Repaired", JOptionPane.INFORMATION_MESSAGE);
                    dialog.dispose();
                    showReport(parent, EpubDoctor.checkEpub(out), out);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Repair Failed", JOptionPane.ERROR_MESSAGE);
                }
            }));
        }
        buttons.add(UiTheme.secondaryButton("Close", dialog::dispose));

        root.add(topBox, BorderLayout.NORTH);
        root.add(UiTheme.scrollPane(listPanel), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(720, 560);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    static void inspectExternalEpub(Component parent) {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Select an EPUB to inspect with EPUB Doctor");
        chooser.setFileFilter(new FileNameExtensionFilter("EPUB books", "epub"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        showReport(parent, EpubDoctor.checkEpub(path), path);
    }

    static void showBookStatistics(Component parent, Draft draft) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Manuscript Statistics & Pacing",
            Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        List<Epub.Chapter> chapters = draft.chapters();
        int[] counts = new int[chapters.size()];
        int totalWords = 0;
        int totalChars = 0;
        int maxWords = 1;
        int totalImages = 0;
        for (int i = 0; i < chapters.size(); i++) {
            var ch = chapters.get(i);
            counts[i] = Manuscript.wordCount(ch.body());
            totalWords += counts[i];
            totalChars += ch.body().length();
            maxWords = Math.max(maxWords, counts[i]);
            totalImages += ch.images().size();
        }
        int avgWords = chapters.isEmpty() ? 0 : totalWords / chapters.size();
        int minutes = Math.max(1, (int) Math.round(totalWords / 250.0));
        int estPages = Math.max(1, (int) Math.round(totalWords / 280.0));

        var summary = UiTheme.card(new GridLayout(1, 5, 10, 0));
        summary.add(statTile("Total Words", String.format("%,d", totalWords)));
        summary.add(statTile("Chapters", String.valueOf(chapters.size())));
        summary.add(statTile("Avg / Chapter", String.format("%,d", avgWords)));
        summary.add(statTile("Est. Pages", String.format("~%,d", estPages)));
        summary.add(statTile("Reading Time", minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + " min"));

        int peak = maxWords;
        var barsPanel = new JPanel();
        barsPanel.setLayout(new BoxLayout(barsPanel, BoxLayout.Y_AXIS));
        barsPanel.setBackground(UiTheme.surface());
        barsPanel.setBorder(new EmptyBorder(10, 12, 10, 12));
        for (int i = 0; i < chapters.size(); i++) {
            var ch = chapters.get(i);
            int w = counts[i];
            var row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            row.setBorder(new EmptyBorder(3, 0, 3, 0));
            var label = new JLabel((i + 1) + ". " + ch.title());
            label.setForeground(UiTheme.ink());
            label.setFont(UiTheme.uiFont(Font.PLAIN, 12.5f));
            label.setPreferredSize(new Dimension(210, 22));
            label.putClientProperty("html.disable", Boolean.TRUE);

            var bar = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    var g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UiTheme.subtle());
                    g2.fillRoundRect(0, 5, getWidth(), 12, 6, 6);
                    int fill = peak == 0 ? 0 : (int) Math.round((double) w / peak * getWidth());
                    g2.setColor(UiTheme.accent());
                    if (fill > 0) g2.fillRoundRect(0, 5, Math.max(6, fill), 12, 6, 6);
                    g2.dispose();
                }
            };
            bar.setOpaque(false);

            var num = new JLabel(String.format("%,d words", w));
            num.setForeground(UiTheme.muted());
            num.setFont(UiTheme.uiFont(Font.PLAIN, 12f));
            num.setHorizontalAlignment(SwingConstants.RIGHT);
            num.setPreferredSize(new Dimension(92, 22));

            row.add(label, BorderLayout.WEST);
            row.add(bar, BorderLayout.CENTER);
            row.add(num, BorderLayout.EAST);
            barsPanel.add(row);
        }

        var footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(UiTheme.mutedLabel(String.format("Characters: %,d  ·  Embedded illustrations: %d  ·  250 wpm reading speed",
            totalChars, totalImages)), BorderLayout.WEST);
        footer.add(UiTheme.secondaryButton("Close", dialog::dispose), BorderLayout.EAST);

        root.add(summary, BorderLayout.NORTH);
        root.add(UiTheme.scrollPane(barsPanel), BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(680, 500);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static JPanel statTile(String label, String value) {
        var p = new JPanel(new BorderLayout(0, 3));
        p.setOpaque(false);
        var l = UiTheme.mutedLabel(label);
        var v = new JLabel(value);
        v.setFont(UiTheme.uiFont(Font.BOLD, 17f));
        v.setForeground(UiTheme.ink());
        p.add(l, BorderLayout.NORTH);
        p.add(v, BorderLayout.CENTER);
        return p;
    }
}
