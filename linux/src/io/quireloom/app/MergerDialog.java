package io.quireloom.app;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Interactive Visual Omnibus Merger, Folder Builder, and EPUB Splitter Studio Dialogs.
 * 100% pure Java — zero Calibre dependency!
 */
final class MergerDialog {

    private static final class RowEntry {
        final Path path;
        String customTitle;
        boolean includeDivider = true;
        boolean rasterizePdf = false;
        Set<String> selectedSpineIds; // null = all
        int totalChapters = -1;

        RowEntry(Path path) {
            this.path = path;
            this.customTitle = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        }

        EpubMerger.SourceItem toSourceItem() {
            return new EpubMerger.SourceItem(path, customTitle, includeDivider, rasterizePdf, selectedSpineIds);
        }

        @Override public String toString() {
            String kind = Files.isDirectory(path) ? "[FOLDER]" : "[" + extLabel(path) + "]";
            String extra = "";
            if (selectedSpineIds != null && !selectedSpineIds.isEmpty() && totalChapters > 0) {
                extra = "  ·  " + selectedSpineIds.size() + "/" + totalChapters + " chapters";
            } else if (extLabel(path).equals("PDF")) {
                extra = rasterizePdf ? "  ·  Image pages" : "  ·  Extract text";
            }
            return kind + "  " + customTitle + extra + "   (" + path.getFileName() + ")";
        }

        private static String extLabel(Path p) {
            String n = p.getFileName().toString();
            int dot = n.lastIndexOf('.');
            return dot >= 0 ? n.substring(dot + 1).toUpperCase(Locale.ROOT) : "FILE";
        }
    }

    static void showMerger(Component parent, boolean startWithFolder) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
            "Omnibus Merger & Book Builder Studio", Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        var model = new DefaultListModel<RowEntry>();
        var list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(UiTheme.surface());
        list.setForeground(UiTheme.ink());
        list.setFont(UiTheme.uiFont(Font.PLAIN, 13f));
        list.setFixedCellHeight(34);

        var titleField = UiTheme.textField("Omnibus title", 256);
        var authorField = UiTheme.textField("Author", 256);
        var langField = UiTheme.textField("Language", 32);
        langField.setText("en");
        var themeBox = new JComboBox<>(Epub.BookTheme.values());
        themeBox.setSelectedItem(Epub.BookTheme.CLASSIC);
        var dividersBox = new JCheckBox("Insert volume title divider pages between books", true);
        dividersBox.setOpaque(false);
        dividersBox.setForeground(UiTheme.ink());
        var inheritCoverBox = new JCheckBox("Inherit cover from first EPUB if no custom cover is set", true);
        inheritCoverBox.setOpaque(false);
        inheritCoverBox.setForeground(UiTheme.ink());
        var smartTypoBox = new JCheckBox("Smart typography for Markdown/TXT sources", true);
        smartTypoBox.setOpaque(false);
        smartTypoBox.setForeground(UiTheme.ink());

        final Epub.Image[] customCover = {null};
        var coverLabel = UiTheme.mutedLabel("No custom cover");
        final File[] lastDir = {null};

        Runnable addFiles = () -> {
            var chooser = new JFileChooser(lastDir[0]);
            chooser.setDialogTitle("Add EPUBs, Markdown, TXT, PDFs, or Images");
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new FileNameExtensionFilter("Books, manuscripts, PDFs & images",
                "epub", "md", "markdown", "txt", "pdf", "png", "jpg", "jpeg", "gif", "webp"));
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            lastDir[0] = chooser.getCurrentDirectory();
            var files = Arrays.stream(chooser.getSelectedFiles())
                .sorted((a, b) -> Manuscript.compareNames(a.getName(), b.getName())).toList();
            for (File f : files) {
                var entry = new RowEntry(f.toPath());
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".epub")) {
                    try {
                        var arc = EpubArchive.load(f.toPath());
                        if (!arc.metadata.title().isBlank()) entry.customTitle = arc.metadata.title();
                        entry.totalChapters = (int) arc.spine.stream()
                            .filter(s -> arc.manifest.containsKey(s.idref) && !arc.manifest.get(s.idref).hasProperty("nav")).count();
                        if (authorField.getText().isBlank() && !arc.metadata.author().isBlank()) {
                            authorField.setText(arc.metadata.author());
                        }
                        if (langField.getText().equals("en") && !arc.metadata.language().isBlank()) {
                            langField.setText(arc.metadata.language());
                        }
                    } catch (Exception ignored) {}
                }
                model.addElement(entry);
            }
            if (titleField.getText().isBlank() && !model.isEmpty()) {
                titleField.setText(model.size() > 1 ? "Collected Volumes" : model.get(0).customTitle);
            }
        };

        Runnable addFolder = () -> {
            var chooser = new JFileChooser(lastDir[0]);
            chooser.setDialogTitle("Select manuscript or book folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            lastDir[0] = chooser.getSelectedFile().getParentFile();
            Path dir = chooser.getSelectedFile().toPath();
            model.addElement(new RowEntry(dir));
            if (titleField.getText().isBlank()) titleField.setText(dir.getFileName().toString());
        };

        var addFilesBtn = UiTheme.secondaryButton("+ Add Files…", addFiles);
        var addFolderBtn = UiTheme.secondaryButton("+ Add Folder…", addFolder);
        var upBtn = UiTheme.secondaryButton("Move Up", () -> {
            int i = list.getSelectedIndex();
            if (i > 0) {
                var el = model.remove(i);
                model.add(i - 1, el);
                list.setSelectedIndex(i - 1);
            }
        });
        var downBtn = UiTheme.secondaryButton("Move Down", () -> {
            int i = list.getSelectedIndex();
            if (i >= 0 && i + 1 < model.size()) {
                var el = model.remove(i);
                model.add(i + 1, el);
                list.setSelectedIndex(i + 1);
            }
        });
        var renameBtn = UiTheme.secondaryButton("Rename Volume…", () -> {
            int i = list.getSelectedIndex();
            if (i < 0) return;
            var entry = model.get(i);
            String val = JOptionPane.showInputDialog(dialog, "Volume / Chapter Title:", entry.customTitle);
            if (val != null && !val.isBlank()) {
                entry.customTitle = val.strip();
                model.set(i, entry);
            }
        });
        var pickChaptersBtn = UiTheme.secondaryButton("Pick Chapters…", () -> {
            int i = list.getSelectedIndex();
            if (i < 0) return;
            var entry = model.get(i);
            String lower = entry.path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                entry.rasterizePdf = !entry.rasterizePdf;
                model.set(i, entry);
                return;
            }
            if (!lower.endsWith(".epub")) {
                JOptionPane.showMessageDialog(dialog, "Chapter cherry-picking applies to EPUB sources (and toggles Text/Image mode for PDFs).");
                return;
            }
            chooseEpubChapters(dialog, entry);
            model.set(i, entry);
        });
        var removeBtn = UiTheme.dangerButton("Remove", () -> {
            int i = list.getSelectedIndex();
            if (i >= 0) model.remove(i);
        });

        var listToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listToolbar.setOpaque(false);
        listToolbar.add(addFilesBtn);
        listToolbar.add(addFolderBtn);
        listToolbar.add(upBtn);
        listToolbar.add(downBtn);
        listToolbar.add(renameBtn);
        listToolbar.add(pickChaptersBtn);
        listToolbar.add(removeBtn);

        var leftPane = new JPanel(new BorderLayout(0, 8));
        leftPane.setOpaque(false);
        var infoHint = UiTheme.mutedLabel("Order your EPUB 2/3 books, Markdown/TXT chapters, PDFs, or folders. Zero Calibre required — original CSS, fonts, images & TOC are preserved.");
        var topStrip = new JPanel(new BorderLayout(0, 6));
        topStrip.setOpaque(false);
        topStrip.add(infoHint, BorderLayout.NORTH);
        topStrip.add(listToolbar, BorderLayout.SOUTH);
        leftPane.add(topStrip, BorderLayout.NORTH);
        leftPane.add(UiTheme.scrollPane(list), BorderLayout.CENTER);

        // Right metadata & output options card.
        var metaCard = UiTheme.card(new GridBagLayout());
        metaCard.setPreferredSize(new Dimension(300, 420));
        var g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.weightx = 1; g.insets = new Insets(0, 0, 10, 0);
        int r = 0;
        g.gridy = r++; metaCard.add(UiTheme.sectionTitle("Output Book Details"), g);
        g.gridy = r++; metaCard.add(UiTheme.labeled("Book / Omnibus title", titleField), g);
        g.gridy = r++; metaCard.add(UiTheme.labeled("Author / Editor", authorField), g);
        g.gridy = r++; metaCard.add(UiTheme.labeled("Language (e.g. en, bn)", langField), g);
        g.gridy = r++; metaCard.add(UiTheme.labeled("Theme (for Markdown/TXT/Dividers)", themeBox), g);
        g.gridy = r++; metaCard.add(dividersBox, g);
        g.gridy = r++; metaCard.add(inheritCoverBox, g);
        g.gridy = r++; metaCard.add(smartTypoBox, g);

        var coverActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        coverActions.setOpaque(false);
        coverActions.add(UiTheme.secondaryButton("Design Cover…", () -> {
            var meta = Epub.Metadata.basic("", titleField.getText(), authorField.getText(), langField.getText());
            CoverDesigner.showDialog(dialog, meta, img -> {
                customCover[0] = img;
                coverLabel.setText("Designed cover attached (" + EpubDoctor.formatBytes(img.bytes().length) + ")");
            });
        }));
        coverActions.add(UiTheme.secondaryButton("Pick Image…", () -> {
            var ch = new JFileChooser(lastDir[0]);
            ch.setFileFilter(new FileNameExtensionFilter("PNG and JPEG images", "png", "jpg", "jpeg"));
            if (ch.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    customCover[0] = BookFiles.readImage(ch.getSelectedFile().toPath());
                    coverLabel.setText(ch.getSelectedFile().getName());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Cover Error", JOptionPane.WARNING_MESSAGE);
                }
            }
        }));
        g.gridy = r++; metaCard.add(coverActions, g);
        g.gridy = r++; metaCard.add(coverLabel, g);
        g.gridy = r; g.weighty = 1; metaCard.add(Box.createGlue(), g);

        var statusBar = UiTheme.mutedLabel("Ready to merge or build.");
        var buildBtn = UiTheme.primaryButton("Build / Merge EPUB…", null);
        buildBtn.addActionListener(e -> {
            if (model.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Add at least one EPUB, file, or folder first.");
                return;
            }
            String outTitle = titleField.getText().isBlank() ? "Collected Volumes" : titleField.getText().strip();
            var save = new JFileChooser(lastDir[0]);
            save.setDialogTitle("Save Combined EPUB");
            save.setSelectedFile(new File(outTitle.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + ".epub"));
            if (save.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            Path out = save.getSelectedFile().toPath().toAbsolutePath();
            if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub")) {
                out = out.resolveSibling(out.getFileName() + ".epub");
            }
            if (Files.exists(out) && JOptionPane.showConfirmDialog(dialog, "Replace existing file?",
                    "Save EPUB", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;

            var items = new ArrayList<EpubMerger.SourceItem>();
            for (int i = 0; i < model.size(); i++) {
                var row = model.get(i);
                row.includeDivider = dividersBox.isSelected();
                items.add(row.toSourceItem());
            }
            var cfg = new EpubMerger.MergeConfig(
                Epub.Metadata.basic("urn:uuid:" + UUID.randomUUID(), outTitle, authorField.getText().strip(),
                    langField.getText().isBlank() ? "en" : langField.getText().strip()),
                customCover[0],
                new Epub.Options((Epub.BookTheme) themeBox.getSelectedItem(), "* * *",
                    smartTypoBox.isSelected(), false, false, false, true, ""),
                inheritCoverBox.isSelected());

            Path finalOut = out;
            buildBtn.setEnabled(false);
            statusBar.setText("Building EPUB 3.3 archive…");
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    EpubMerger.merge(items, finalOut, cfg);
                    return null;
                }
                @Override protected void done() {
                    buildBtn.setEnabled(true);
                    try {
                        get();
                        statusBar.setText("Saved " + finalOut.getFileName() + " (" + EpubDoctor.formatBytes(Files.size(finalOut)) + ")");
                        Object[] choices = {"Inspect with EPUB Doctor", "Open in Package Studio", "Done"};
                        int pick = JOptionPane.showOptionDialog(dialog,
                            "Successfully built valid EPUB 3.3:\n" + finalOut,
                            "Build Complete", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                            null, choices, choices[0]);
                        if (pick == 0) DoctorDialog.showReport(dialog, EpubDoctor.checkEpub(finalOut), finalOut);
                        else if (pick == 1) PackageStudioFrame.open(dialog, finalOut);
                    } catch (Exception error) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        statusBar.setText("Failed: " + cause.getMessage());
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Build Failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        var bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setOpaque(false);
        bottom.add(statusBar, BorderLayout.CENTER);
        var btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.add(UiTheme.secondaryButton("Close", dialog::dispose));
        btnRow.add(buildBtn);
        bottom.add(btnRow, BorderLayout.EAST);

        root.add(leftPane, BorderLayout.CENTER);
        root.add(metaCard, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(940, 580);
        dialog.setLocationRelativeTo(parent);
        if (startWithFolder) SwingUtilities.invokeLater(addFolder);
        dialog.setVisible(true);
    }

    private static void chooseEpubChapters(Component parent, RowEntry entry) {
        try {
            var arc = EpubArchive.load(entry.path);
            var checks = new ArrayList<JCheckBox>();
            var idrefs = new ArrayList<String>();
            var panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(UiTheme.surface());
            panel.setBorder(new EmptyBorder(8, 8, 8, 8));
            int num = 0;
            for (var se : arc.spine) {
                var mi = arc.manifest.get(se.idref);
                if (mi == null || !mi.isHtml() || mi.hasProperty("nav")) continue;
                num++;
                boolean selected = entry.selectedSpineIds == null || entry.selectedSpineIds.isEmpty()
                    || entry.selectedSpineIds.contains(se.idref);
                String label = num + ". " + arc.extractTitle(mi.path, mi.path) + "  (" + mi.path + ")";
                var cb = new JCheckBox(label, selected);
                cb.setOpaque(false);
                cb.setForeground(UiTheme.ink());
                checks.add(cb);
                idrefs.add(se.idref);
                panel.add(cb);
            }
            var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            controls.setOpaque(false);
            controls.add(UiTheme.secondaryButton("Select All", () -> checks.forEach(c -> c.setSelected(true))));
            controls.add(UiTheme.secondaryButton("Select None", () -> checks.forEach(c -> c.setSelected(false))));
            var wrap = new JPanel(new BorderLayout(0, 6));
            wrap.add(controls, BorderLayout.NORTH);
            var scroll = UiTheme.scrollPane(panel);
            scroll.setPreferredSize(new Dimension(520, 360));
            wrap.add(scroll, BorderLayout.CENTER);
            if (JOptionPane.showConfirmDialog(parent, wrap, "Cherry-Pick Chapters — " + entry.customTitle,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                var picked = new LinkedHashSet<String>();
                for (int i = 0; i < checks.size(); i++) {
                    if (checks.get(i).isSelected()) picked.add(idrefs.get(i));
                }
                entry.totalChapters = checks.size();
                entry.selectedSpineIds = picked.size() == checks.size() ? null : picked;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), "Cannot Read Chapters", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Interactive EPUB Splitter Dialog — splits any EPUB into standalone resource-pruned EPUB 3.3 volumes!
     */
    static void showSplitter(Component parent) {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Choose an EPUB to Split into Multiple Books");
        chooser.setFileFilter(new FileNameExtensionFilter("EPUB books", "epub"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        EpubArchive arc;
        try {
            arc = EpubArchive.load(source);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), "Cannot Open EPUB", JOptionPane.ERROR_MESSAGE);
            return;
        }

        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
            "EPUB Splitter — " + (arc.metadata.title().isBlank() ? source.getFileName() : arc.metadata.title()),
            Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        var byParts = new JRadioButton("Split by Top-Level Table of Contents Parts/Volumes", true);
        var byChunks = new JRadioButton("Split every N chapters:");
        byParts.setOpaque(false); byParts.setForeground(UiTheme.ink());
        byChunks.setOpaque(false); byChunks.setForeground(UiTheme.ink());
        var group = new ButtonGroup();
        group.add(byParts); group.add(byChunks);
        var chunkSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 500, 1));
        chunkSpinner.setPreferredSize(new Dimension(70, 26));

        var previewModel = new DefaultListModel<String>();
        var previewList = new JList<>(previewModel);
        previewList.setBackground(UiTheme.surface());
        previewList.setForeground(UiTheme.ink());
        previewList.setFont(UiTheme.uiFont(Font.PLAIN, 13f));
        previewList.setFixedCellHeight(28);

        var plan = new java.util.concurrent.atomic.AtomicReference<List<EpubMerger.SplitPart>>(List.of());
        Runnable recompute = () -> {
            plan.set(byParts.isSelected()
                ? EpubMerger.autoSplitByParts(arc)
                : EpubMerger.autoSplitByChunkSize(arc, (Integer) chunkSpinner.getValue()));
            previewModel.clear();
            int n = 0;
            for (var p : plan.get()) {
                n++;
                previewModel.addElement(String.format("%02d. %s  (%d chapter%s)",
                    n, p.title(), p.spineIdrefs().size(), p.spineIdrefs().size() == 1 ? "" : "s"));
            }
        };
        byParts.addActionListener(e -> recompute.run());
        byChunks.addActionListener(e -> recompute.run());
        chunkSpinner.addChangeListener(e -> { byChunks.setSelected(true); recompute.run(); });

        var modeCard = UiTheme.card(new GridLayout(0, 1, 0, 6));
        modeCard.add(byParts);
        var chunkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chunkRow.setOpaque(false);
        chunkRow.add(byChunks);
        chunkRow.add(chunkSpinner);
        modeCard.add(chunkRow);
        modeCard.add(UiTheme.mutedLabel("Each output book keeps only the CSS, fonts, and images it references (smart pruning)."));

        var splitBtn = UiTheme.primaryButton("Split to Folder…", () -> {
            if (plan.get().isEmpty()) return;
            var dirPicker = new JFileChooser(source.getParent().toFile());
            dirPicker.setDialogTitle("Select Output Folder for Split EPUBs");
            dirPicker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (dirPicker.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            Path outDir = dirPicker.getSelectedFile().toPath();
            try {
                var results = EpubMerger.split(source, outDir, plan.get());
                JOptionPane.showMessageDialog(dialog,
                    "Created " + results.size() + " standalone EPUB 3.3 volume(s) in:\n" + outDir,
                    "Split Complete", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Split Failed", JOptionPane.ERROR_MESSAGE);
            }
        });

        var footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(UiTheme.secondaryButton("Cancel", dialog::dispose));
        footer.add(splitBtn);

        root.add(modeCard, BorderLayout.NORTH);
        root.add(UiTheme.labeled("Resulting Volumes Preview", UiTheme.scrollPane(previewList)), BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        recompute.run();
        dialog.setSize(620, 480);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
