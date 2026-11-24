package io.quireloom.app;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipOutputStream;

/**
 * Built-in Native Lossless EPUB Package Studio!
 * Edit original EPUB 2 & EPUB 3 XHTML, CSS, OPF metadata, spine order, covers & assets
 * without losing formatting, stylesheets, or illustrations — zero Calibre dependency!
 */
final class PackageStudioFrame extends JFrame {
    private Path filePath;
    private EpubArchive archive;
    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final JTextArea codeArea = new JTextArea();
    private final UndoManager undoManager = new UndoManager();
    private final JEditorPane htmlPreview = new JEditorPane("text/html", "");
    private final JLabel imageView = new JLabel("", SwingConstants.CENTER);
    private final JLabel imageMeta = UiTheme.mutedLabel("");
    private final JButton setCoverBtn = UiTheme.secondaryButton("Use as Cover", null);
    private final JButton exportImgBtn = UiTheme.secondaryButton("Export Image…", null);
    private final CardLayout viewerCards = new CardLayout();
    private final JPanel viewerPanel = new JPanel(viewerCards);
    private final JTabbedPane codeTabs = new JTabbedPane();
    private final JLabel status = UiTheme.mutedLabel("Ready");
    private final JTextField metaTitle = UiTheme.textField("Title", 512);
    private final JTextField metaSubtitle = UiTheme.textField("Subtitle", 512);
    private final JTextField metaAuthor = UiTheme.textField("Author", 512);
    private final JTextField metaLang = UiTheme.textField("Language", 64);
    private final JTextField metaSeries = UiTheme.textField("Series", 256);
    private final JTextField metaSeriesIndex = UiTheme.textField("Series Index", 32);
    private final JTextField metaPublisher = UiTheme.textField("Publisher", 256);
    private final JTextField metaSubjects = UiTheme.textField("Subjects", 512);
    private final JTextField metaRights = UiTheme.textField("Rights", 512);
    private final JTextField metaDate = UiTheme.textField("Publication Date", 64);
    private final JTextField metaId = UiTheme.textField("Identifier", 256);
    private final JTextArea metaDesc = new JTextArea(4, 30);

    private String activePath;
    private boolean loadingText;
    private boolean dirty;
    private boolean rawOpfDirty;

    static void openPrompt(Component parent) {
        Path picked = FilePickers.pickOpenFile(parent, "Open Original EPUB in Package Studio", null,
            "EPUB books", "epub");
        if (picked == null) return;
        open(parent, picked);
    }

    static void open(Component parent, Path epubPath) {
        try {
            var arc = EpubArchive.load(epubPath);
            var frame = new PackageStudioFrame(epubPath, arc);
            frame.setLocationRelativeTo(parent);
            frame.setVisible(true);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), "Could Not Open EPUB", JOptionPane.ERROR_MESSAGE);
        }
    }

    private PackageStudioFrame(Path path, EpubArchive archive) {
        super("EPUB Package Studio — " + path.getFileName());
        this.filePath = path;
        this.archive = archive;
        try (var icon = DesktopApp.class.getResourceAsStream("icon.png")) {
            if (icon != null) setIconImage(ImageIO.read(icon));
        } catch (Exception ignored) {}

        var rootNode = new DefaultMutableTreeNode(path.getFileName().toString());
        this.treeModel = new DefaultTreeModel(rootNode);
        this.tree = new JTree(treeModel);
        tree.setBackground(UiTheme.surface());
        tree.setForeground(UiTheme.ink());
        tree.setFont(UiTheme.uiFont(Font.PLAIN, 12.5f));
        tree.setRowHeight(24);
        tree.addTreeSelectionListener(e -> {
            var node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof FileNode fn) {
                selectArchiveFile(fn.archivePath);
            }
        });

        codeArea.setFont(UiTheme.monoFont(Font.PLAIN, 13.5f));
        codeArea.setBackground(UiTheme.editorBg());
        codeArea.setForeground(UiTheme.ink());
        codeArea.setCaretColor(UiTheme.ink());
        codeArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        codeArea.setTabSize(2);
        codeArea.getDocument().addUndoableEditListener(e -> { if (!loadingText) undoManager.addEdit(e.getEdit()); });
        codeArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onCodeChanged(); }
            public void removeUpdate(DocumentEvent e) { onCodeChanged(); }
            public void changedUpdate(DocumentEvent e) { onCodeChanged(); }
        });

        htmlPreview.setEditable(false);
        htmlPreview.setBackground(Color.WHITE);
        codeTabs.addTab("Source Code", UiTheme.scrollPane(codeArea));
        codeTabs.addTab("Rendered Preview", UiTheme.scrollPane(htmlPreview));
        codeTabs.addTab("Package Metadata", buildMetadataPanel());
        codeTabs.addChangeListener(e -> {
            if (codeTabs.getSelectedIndex() == 1) refreshHtmlPreview();
        });

        setCoverBtn.addActionListener(e -> setSelectedAsCover());
        exportImgBtn.addActionListener(e -> exportSelectedResource());
        var imageFooter = new JPanel(new BorderLayout(8, 0));
        imageFooter.setOpaque(false);
        imageFooter.add(imageMeta, BorderLayout.CENTER);
        var imgBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        imgBtns.setOpaque(false);
        imgBtns.add(setCoverBtn);
        imgBtns.add(exportImgBtn);
        imageFooter.add(imgBtns, BorderLayout.EAST);

        var imagePanel = new JPanel(new BorderLayout(0, 8));
        imagePanel.setBackground(UiTheme.surface());
        imagePanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        imagePanel.add(UiTheme.scrollPane(imageView), BorderLayout.CENTER);
        imagePanel.add(imageFooter, BorderLayout.SOUTH);

        var placeholder = new JLabel("Select any XHTML chapter, CSS stylesheet, OPF package, or image from the package tree.", SwingConstants.CENTER);
        placeholder.setForeground(UiTheme.muted());
        viewerPanel.add(placeholder, "empty");
        viewerPanel.add(codeTabs, "code");
        viewerPanel.add(imagePanel, "image");
        viewerCards.show(viewerPanel, "empty");

        var leftTools = new JPanel(new GridLayout(3, 2, 4, 4));
        leftTools.setOpaque(false);
        leftTools.add(UiTheme.secondaryButton("Spine Up", () -> moveSpine(-1)));
        leftTools.add(UiTheme.secondaryButton("Spine Down", () -> moveSpine(1)));
        leftTools.add(UiTheme.secondaryButton("Toggle Linear", this::toggleSpineLinear));
        leftTools.add(UiTheme.secondaryButton("Rebuild TOC", this::rebuildToc));
        leftTools.add(UiTheme.secondaryButton("+ Add File…", this::addAsset));
        leftTools.add(UiTheme.dangerButton("Delete File", this::deleteSelected));

        var leftPane = new JPanel(new BorderLayout(0, 8));
        leftPane.setOpaque(false);
        leftPane.setPreferredSize(new Dimension(280, 600));
        leftPane.add(UiTheme.sectionTitle("EPUB Package Contents"), BorderLayout.NORTH);
        leftPane.add(UiTheme.scrollPane(tree), BorderLayout.CENTER);
        leftPane.add(leftTools, BorderLayout.SOUTH);

        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, viewerPanel);
        UiTheme.styleSplitPane(split);
        split.setDividerLocation(290);
        split.setDividerSize(8);

        var topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setOpaque(false);
        var heading = new JLabel("Lossless EPUB Package Studio");
        heading.setFont(UiTheme.serifFont(Font.BOLD, 22f));
        heading.setForeground(UiTheme.ink());
        var toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        toolbar.setOpaque(false);
        toolbar.add(UiTheme.secondaryButton("Find & Replace…", this::findReplaceAll));
        toolbar.add(UiTheme.secondaryButton("Optimize Images…", this::optimizeImages));
        toolbar.add(UiTheme.secondaryButton("EPUB Doctor", this::runDoctor));
        toolbar.add(UiTheme.secondaryButton("Save As…", () -> save(true)));
        toolbar.add(UiTheme.primaryButton("Save EPUB", () -> save(false)));
        topBar.add(heading, BorderLayout.WEST);
        topBar.add(toolbar, BorderLayout.EAST);

        var content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(UiTheme.bg());
        content.setBorder(new EmptyBorder(14, 16, 12, 16));
        content.add(topBar, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);

        // Keyboard shortcuts for Save, Undo, Redo, Find/Replace.
        bindKey(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK, () -> save(false));
        bindKey(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, () -> { if (undoManager.canUndo()) undoManager.undo(); });
        bindKey(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, () -> { if (undoManager.canRedo()) undoManager.redo(); });
        bindKey(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, this::findReplaceAll);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (!dirty || JOptionPane.showConfirmDialog(PackageStudioFrame.this,
                        "Discard unsaved changes to this EPUB?", "Close Package Studio",
                        JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    dispose();
                }
            }
        });

        loadMetadataFields();
        rebuildTree();
        setSize(1080, 720);
        setMinimumSize(new Dimension(780, 520));
    }

    private record FileNode(String label, String archivePath) {
        @Override public String toString() { return label; }
    }

    private JPanel buildMetadataPanel() {
        var panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(UiTheme.bg());
        panel.setBorder(new EmptyBorder(14, 16, 14, 16));
        metaDesc.setLineWrap(true);
        metaDesc.setWrapStyleWord(true);
        metaDesc.setBackground(UiTheme.editorBg());
        metaDesc.setForeground(UiTheme.ink());
        metaDesc.setCaretColor(UiTheme.ink());
        metaDesc.setBorder(new EmptyBorder(6, 8, 6, 8));

        var form = UiTheme.card(new GridBagLayout());
        var g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1; g.gridx = 0; g.insets = new Insets(0, 0, 8, 0);
        int r = 0;
        g.gridy = r++; form.add(UiTheme.labeled("Book Title", metaTitle), g);
        g.gridy = r++; form.add(UiTheme.labeled("Subtitle", metaSubtitle), g);
        g.gridy = r++; form.add(UiTheme.labeled("Author", metaAuthor), g);
        g.gridy = r++; form.add(UiTheme.labeled("Language (BCP-47)", metaLang), g);
        g.gridy = r++; form.add(UiTheme.labeled("Series", metaSeries), g);
        g.gridy = r++; form.add(UiTheme.labeled("Series Index", metaSeriesIndex), g);
        g.gridy = r++; form.add(UiTheme.labeled("Publisher", metaPublisher), g);
        g.gridy = r++; form.add(UiTheme.labeled("Subjects / Tags", metaSubjects), g);
        g.gridy = r++; form.add(UiTheme.labeled("Rights / License", metaRights), g);
        g.gridy = r++; form.add(UiTheme.labeled("Publication Date (YYYY-MM-DD)", metaDate), g);
        g.gridy = r++; form.add(UiTheme.labeled("Identifier (UUID or ISBN)", metaId), g);
        g.gridy = r++; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        form.add(UiTheme.labeled("Description", UiTheme.scrollPane(metaDesc)), g);

        var applyBtn = UiTheme.primaryButton("Update Package Metadata", () -> {
            if (!applyRawOpfIfDirty()) return;
            String id = metaId.getText().isBlank() ? archive.metadata.id() : metaId.getText().strip();
            archive.metadata = new Epub.Metadata(id, metaTitle.getText().strip(),
                metaSubtitle.getText().strip(), metaAuthor.getText().strip(), metaLang.getText().strip(),
                metaDesc.getText().strip(), metaPublisher.getText().strip(), metaSubjects.getText().strip(),
                metaSeries.getText().strip(), metaSeriesIndex.getText().strip(),
                metaRights.getText().strip(), metaDate.getText().strip());
            archive.tocModified = true;
            markDirty();
            status.setText("Metadata updated (remember to Save EPUB).");
        });
        var bar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bar.setOpaque(false);
        bar.add(applyBtn);

        panel.add(UiTheme.scrollPane(form), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        return panel;
    }

    private void loadMetadataFields() {
        var m = archive.metadata;
        metaTitle.setText(m.title());
        metaSubtitle.setText(m.subtitle());
        metaAuthor.setText(m.author());
        metaLang.setText(m.language());
        metaSeries.setText(m.series());
        metaSeriesIndex.setText(m.seriesIndex());
        metaPublisher.setText(m.publisher());
        metaSubjects.setText(m.subjects());
        metaRights.setText(m.rights());
        metaDate.setText(m.date());
        metaId.setText(m.id());
        metaDesc.setText(m.description());
    }

    private void rebuildTree() {
        var root = (DefaultMutableTreeNode) treeModel.getRoot();
        root.removeAllChildren();
        var spineNode = new DefaultMutableTreeNode("Spine (Reading Order)");
        var cssNode = new DefaultMutableTreeNode("Stylesheets (CSS)");
        var imgNode = new DefaultMutableTreeNode("Images & Media");
        var fontNode = new DefaultMutableTreeNode("Fonts");
        var otherNode = new DefaultMutableTreeNode("Navigation & Other");
        var pkgNode = new DefaultMutableTreeNode("Package Metadata Files");

        var inSpine = new HashSet<String>();
        int idx = 0;
        for (var se : archive.spine) {
            var mi = archive.manifest.get(se.idref);
            if (mi == null) continue;
            idx++;
            inSpine.add(mi.path);
            String label = idx + ". " + shortName(mi.path) + (se.linear ? "" : " [non-linear]");
            spineNode.add(new DefaultMutableTreeNode(new FileNode(label, mi.path)));
        }
        for (var mi : archive.manifest.values()) {
            if (inSpine.contains(mi.path)) continue;
            var fn = new FileNode(shortName(mi.path) + (mi.id.equals(archive.coverItemId) ? " ★ Cover" : ""), mi.path);
            if (mi.isCss()) cssNode.add(new DefaultMutableTreeNode(fn));
            else if (mi.isImage()) imgNode.add(new DefaultMutableTreeNode(fn));
            else if (mi.isFont()) fontNode.add(new DefaultMutableTreeNode(fn));
            else otherNode.add(new DefaultMutableTreeNode(fn));
        }
        if (archive.files.containsKey(archive.opfPath)) {
            pkgNode.add(new DefaultMutableTreeNode(new FileNode(shortName(archive.opfPath) + " (OPF)", archive.opfPath)));
        }
        if (archive.files.containsKey("META-INF/container.xml")) {
            pkgNode.add(new DefaultMutableTreeNode(new FileNode("container.xml", "META-INF/container.xml")));
        }
        root.add(spineNode);
        if (cssNode.getChildCount() > 0) root.add(cssNode);
        if (imgNode.getChildCount() > 0) root.add(imgNode);
        if (fontNode.getChildCount() > 0) root.add(fontNode);
        if (otherNode.getChildCount() > 0) root.add(otherNode);
        if (pkgNode.getChildCount() > 0) root.add(pkgNode);
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private void selectArchiveFile(String path) {
        if (activePath != null && !activePath.equals(path) && activePath.equals(archive.opfPath) && rawOpfDirty) {
            applyRawOpfIfDirty();
        }
        activePath = path;
        byte[] data = archive.files.get(path);
        if (data == null) {
            viewerCards.show(viewerPanel, "empty");
            return;
        }
        var mi = archive.findManifestByPath(path);
        if (mi != null && mi.isImage() && !"image/svg+xml".equals(mi.mediaType)) {
            setCoverBtn.setEnabled(!mi.id.equals(archive.coverItemId));
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null) {
                    int maxW = Math.max(300, viewerPanel.getWidth() - 60);
                    Image scaled = img.getWidth() > maxW ? img.getScaledInstance(maxW, -1, Image.SCALE_SMOOTH) : img;
                    imageView.setIcon(new ImageIcon(scaled));
                    imageView.setText("");
                    imageMeta.setText(path + "  ·  " + img.getWidth() + "×" + img.getHeight()
                        + "  ·  " + mi.mediaType + "  ·  " + EpubDoctor.formatBytes(data.length));
                } else {
                    imageView.setIcon(null);
                    imageView.setText("Preview unavailable for " + mi.mediaType);
                    imageMeta.setText(path + "  ·  " + EpubDoctor.formatBytes(data.length));
                }
            } catch (Exception e) {
                imageView.setIcon(null);
                imageView.setText("Cannot render image: " + e.getMessage());
            }
            viewerCards.show(viewerPanel, "image");
            return;
        }
        boolean textEditable = mi != null ? mi.isTextEditable()
            : path.equals(archive.opfPath) || path.endsWith(".xml") || path.endsWith(".opf") || path.endsWith(".ncx");
        if (textEditable) {
            loadingText = true;
            try {
                codeArea.setText(new String(data, StandardCharsets.UTF_8));
                codeArea.setCaretPosition(0);
                undoManager.discardAllEdits();
            } finally {
                loadingText = false;
            }
            codeTabs.setEnabledAt(1, mi != null && mi.isHtml());
            if (codeTabs.getSelectedIndex() == 1 && (mi == null || !mi.isHtml())) codeTabs.setSelectedIndex(0);
            viewerCards.show(viewerPanel, "code");
            if (codeTabs.getSelectedIndex() == 1) refreshHtmlPreview();
            status.setText(path + "  ·  " + EpubDoctor.formatBytes(data.length));
        } else {
            viewerCards.show(viewerPanel, "empty");
            String mime = mi == null ? "application/octet-stream" : mi.mediaType;
            status.setText(path + " (binary " + mime + ", " + EpubDoctor.formatBytes(data.length) + ")");
        }
    }

    private void onCodeChanged() {
        if (loadingText || activePath == null) return;
        archive.setText(activePath, codeArea.getText());
        if (activePath.equals(archive.opfPath)) rawOpfDirty = true;
        markDirty();
    }

    private void markDirty() {
        dirty = true;
        setTitle("• EPUB Package Studio — " + filePath.getFileName());
    }

    private boolean applyRawOpfIfDirty() {
        if (!rawOpfDirty) return true;
        try {
            Path temp = Files.createTempFile("quireloom-opf-", ".epub");
            try {
                try (var zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                    Epub.writeMimetype(zip);
                    for (var entry : archive.files.entrySet()) {
                        Epub.put(zip, entry.getKey(), entry.getValue());
                    }
                }
                archive = EpubArchive.load(temp);
                rawOpfDirty = false;
                loadMetadataFields();
                rebuildTree();
                return true;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Invalid OPF XML: " + ex.getMessage(),
                "OPF Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void refreshHtmlPreview() {
        if (activePath == null) return;
        String html = archive.getText(activePath);
        // Strip XML declaration and DOCTYPE for Swing's HTML renderer.
        String stripped = html.replaceFirst("(?s)^<\\?xml.*?\\?>\\s*", "").replaceFirst("(?is)<!DOCTYPE[^>]*>\\s*", "");
        htmlPreview.setText(stripped);
        htmlPreview.setCaretPosition(0);
    }

    private void moveSpine(int delta) {
        if (activePath == null || !applyRawOpfIfDirty()) return;
        var mi = archive.findManifestByPath(activePath);
        if (mi == null) return;
        int idx = -1;
        for (int i = 0; i < archive.spine.size(); i++) {
            if (archive.spine.get(i).idref.equals(mi.id)) { idx = i; break; }
        }
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= archive.spine.size()) return;
        Collections.swap(archive.spine, idx, target);
        markDirty();
        rebuildTree();
        status.setText("Reordered spine item: " + activePath);
    }

    private void toggleSpineLinear() {
        if (activePath == null || !applyRawOpfIfDirty()) return;
        var mi = archive.findManifestByPath(activePath);
        if (mi == null) return;
        for (var se : archive.spine) {
            if (se.idref.equals(mi.id)) {
                se.linear = !se.linear;
                markDirty();
                rebuildTree();
                status.setText(activePath + (se.linear ? " marked linear" : " marked non-linear"));
                return;
            }
        }
    }

    private void rebuildToc() {
        if (!applyRawOpfIfDirty()) return;
        archive.rebuildTocFromSpine();
        markDirty();
        status.setText("Rebuilt navigation table of contents from spine order.");
    }

    private void setSelectedAsCover() {
        if (activePath == null || !applyRawOpfIfDirty()) return;
        var mi = archive.findManifestByPath(activePath);
        if (mi == null || !mi.isImage()) return;
        archive.coverItemId = mi.id;
        markDirty();
        rebuildTree();
        setCoverBtn.setEnabled(false);
        status.setText("Set cover image to " + activePath);
    }

    private void exportSelectedResource() {
        if (activePath == null) return;
        byte[] data = archive.files.get(activePath);
        if (data == null) return;
        String name = shortName(activePath);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        Path out = FilePickers.pickSaveFile(this, "Export Package Resource", filePath.getParent(), name, ext, null);
        if (out == null) return;
        try {
            Files.write(out, data);
            status.setText("Exported " + out.getFileName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addAsset() {
        if (!applyRawOpfIfDirty()) return;
        Path p = FilePickers.pickOpenFile(this, "Add XHTML, CSS, Image, or Font to EPUB", filePath.getParent(),
            "Web & media assets", "xhtml", "html", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ttf", "otf", "woff", "woff2");
        if (p == null) return;
        try {
            byte[] data = Files.readAllBytes(p);
            String name = p.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            String mime = lower.endsWith(".css") ? "text/css"
                : lower.endsWith(".xhtml") || lower.endsWith(".html") ? "application/xhtml+xml"
                : lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                : lower.endsWith(".gif") ? "image/gif"
                : lower.endsWith(".webp") ? "image/webp"
                : lower.endsWith(".svg") ? "image/svg+xml"
                : lower.endsWith(".ttf") ? "font/ttf"
                : lower.endsWith(".otf") ? "font/otf"
                : lower.endsWith(".woff") ? "font/woff"
                : lower.endsWith(".woff2") ? "font/woff2"
                : "application/octet-stream";
            String dir = archive.opfPath.contains("/") ? archive.opfPath.substring(0, archive.opfPath.lastIndexOf('/') + 1) : "";
            String targetPath = dir + name;
            int n = 2;
            while (archive.files.containsKey(targetPath)) targetPath = dir + (n++) + "-" + name;
            archive.addFile(targetPath, data, mime, mime.equals("application/xhtml+xml"));
            markDirty();
            rebuildTree();
            selectArchiveFile(targetPath);
            status.setText("Added " + targetPath);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Add File Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        if (activePath == null || !applyRawOpfIfDirty()) return;
        var mi = archive.findManifestByPath(activePath);
        if (activePath.startsWith("META-INF/") || activePath.equals(archive.opfPath)
                || (mi != null && mi.hasProperty("nav"))) {
            JOptionPane.showMessageDialog(this,
                "Core package metadata and navigation files cannot be deleted directly.",
                "Protected Package File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete \"" + activePath + "\" from this EPUB?",
                "Delete Archive Entry", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        archive.removeFile(activePath);
        activePath = null;
        markDirty();
        viewerCards.show(viewerPanel, "empty");
        rebuildTree();
        status.setText("Deleted file from package.");
    }

    private void findReplaceAll() {
        if (!applyRawOpfIfDirty()) return;
        var query = UiTheme.textField("Find", 1024);
        var rep = UiTheme.textField("Replace", 1024);
        var regex = new JCheckBox("Regular expression");
        var caseSens = new JCheckBox("Match case", true);
        var css = new JCheckBox("Include CSS stylesheets", true);
        var p = new JPanel(new GridLayout(0, 1, 0, 4));
        p.add(new JLabel("Find across all XHTML files:")); p.add(query);
        p.add(new JLabel("Replace with:")); p.add(rep);
        p.add(regex); p.add(caseSens); p.add(css);
        if (JOptionPane.showConfirmDialog(this, p, "Find & Replace Across EPUB",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION || query.getText().isEmpty()) return;
        try {
            var result = archive.findAndReplace(query.getText(), rep.getText(),
                regex.isSelected(), caseSens.isSelected(), css.isSelected());
            if (result.totalReplacements() == 0) {
                JOptionPane.showMessageDialog(this, "No matches found.");
                return;
            }
            markDirty();
            if (activePath != null) selectArchiveFile(activePath);
            status.setText("Replaced " + result.totalReplacements() + " match(es) across " + result.filesChanged() + " file(s).");
            JOptionPane.showMessageDialog(this,
                "Replaced " + result.totalReplacements() + " match(es) across " + result.filesChanged() + " file(s):\n\n"
                    + String.join("\n", result.details()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Find & Replace Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void optimizeImages() {
        if (!applyRawOpfIfDirty()) return;
        var dimSpinner = new JSpinner(new SpinnerNumberModel(1600, 400, 4000, 100));
        var qualSpinner = new JSpinner(new SpinnerNumberModel(84, 40, 95, 2));
        var p = new JPanel(new GridLayout(0, 2, 8, 6));
        p.add(new JLabel("Max dimension (px):")); p.add(dimSpinner);
        p.add(new JLabel("JPEG quality (%):")); p.add(qualSpinner);
        if (JOptionPane.showConfirmDialog(this, p, "Optimize Embedded Images",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        var res = archive.optimizeAllImages((Integer) dimSpinner.getValue(), ((Integer) qualSpinner.getValue()) / 100f);
        if (res.imagesOptimized() > 0) {
            markDirty();
            if (activePath != null) selectArchiveFile(activePath);
        }
        JOptionPane.showMessageDialog(this,
            "Optimized " + res.imagesOptimized() + " image(s), saving " + EpubDoctor.formatBytes(res.bytesSaved()) + ".");
    }

    private void runDoctor() {
        if (!applyRawOpfIfDirty()) return;
        try {
            Path temp = Files.createTempFile("quireloom-doctor-", ".epub");
            try {
                archive.saveAsEpub3(temp, true);
                DoctorDialog.showReport(this, EpubDoctor.checkEpub(temp), filePath);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Doctor Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save(boolean saveAs) {
        if (!applyRawOpfIfDirty()) return;
        Path dest = filePath;
        if (saveAs) {
            dest = FilePickers.pickSaveFile(this, "Save EPUB Package As", filePath.getParent(),
                filePath.getFileName().toString(), "epub", "EPUB books", "epub");
            if (dest == null) return;
        }
        try {
            archive.saveAsEpub3(dest, true);
            filePath = dest;
            dirty = false;
            setTitle("EPUB Package Studio — " + filePath.getFileName());
            status.setText("Saved valid EPUB 3.3 to " + filePath.getFileName()
                + " (" + EpubDoctor.formatBytes(Files.size(filePath)) + ")");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void bindKey(int key, int modifiers, Runnable action) {
        String id = "studio-" + key + "-" + modifiers;
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, modifiers), id);
        getRootPane().getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
