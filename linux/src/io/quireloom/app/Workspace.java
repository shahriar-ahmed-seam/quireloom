package io.quireloom.app;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Workspace extends JPanel {
    private final JTextField title = UiTheme.textField("Book title", 512);
    private final JTextField author = UiTheme.textField("Author", 512);
    private final JTextField language = UiTheme.textField("Language", 64);
    private final JTextField chapterTitle = UiTheme.textField("Chapter title", 2048);
    private final JComboBox<Epub.ChapterRole> roleBox = new JComboBox<>(Epub.ChapterRole.values());
    private final JCheckBox markdown = new JCheckBox("Interpret as Markdown");
    private final JCheckBox hideHeading = new JCheckBox("Hide heading");
    private final JTextArea body = new JTextArea();
    private final UndoManager textUndo = new UndoManager();
    private record StructureSnapshot(List<Epub.Chapter> chapters, int selectedIndex) {}
    private final Deque<StructureSnapshot> structureUndo = new ArrayDeque<>();
    private final JEditorPane livePreview = new JEditorPane("text/html", "");
    private final JScrollPane livePreviewScroll;
    private final JEditorPane preview = new JEditorPane();
    private final JScrollPane previewScroll;
    private final javax.swing.Timer previewDelay = new javax.swing.Timer(320, e -> {
        refreshPreview();
        refreshStats();
        repaintChapterList();
    });
    private JSplitPane editSplit;
    private JSplitPane mainSplit;
    private JPanel headerPanel;
    private JPanel sidebarPanel;
    private JLabel wordmarkLabel;
    private JPanel formatToolbar;
    private JPanel previewTabPanel;
    private JScrollPane listScroll;
    private JScrollPane bodyScroll;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = UiTheme.mutedLabel("Ready");
    private final JLabel statsLabel = UiTheme.mutedLabel("");
    private final JLabel count = UiTheme.sectionTitle("Chapters (0)");
    private final DefaultListModel<Epub.Chapter> chapters = new DefaultListModel<>();
    private final JList<Epub.Chapter> list = new JList<>(chapters);
    private final JPanel editor = new JPanel(new BorderLayout(0, 6));
    private final CardLayout editorialCards = new CardLayout();
    private final JPanel editorial = new JPanel(editorialCards);

    // Find & Replace bar inside chapter editor.
    private final JPanel findBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final JTextField findField = UiTheme.textField("Find in chapter", 512);
    private final JTextField replaceField = UiTheme.textField("Replace in chapter", 512);
    private final JCheckBox findCase = new JCheckBox("Aa");
    private final JCheckBox findRegex = new JCheckBox(".*");
    private final JLabel findCount = UiTheme.mutedLabel("");

    // Cover thumbnail widget & header action buttons.
    private final JButton coverThumb = new JButton();
    private final JButton openBookBtn = UiTheme.secondaryButton("Open book…", () -> DesktopApp.chooseBook(this, false));
    private final JButton addChaptersBtn = UiTheme.secondaryButton("Add chapters…", this::chooseFiles);
    private final JButton pasteBtn = UiTheme.secondaryButton("Paste text", this::paste);
    private final JButton designBtn = UiTheme.secondaryButton("Design…", this::openDesignDialog);
    private final JButton doctorBtn = UiTheme.secondaryButton("Doctor", this::runDoctor);
    private final JButton exportBtn = UiTheme.primaryButton("Export EPUB", this::chooseExport);
    private final JButton moreMetaBtn = UiTheme.secondaryButton("Metadata…", this::openMetadataDialog);
    private final JButton designCoverBtn = UiTheme.secondaryButton("Cover Studio…", this::openCoverStudio);
    private final JButton newChapterBtn = UiTheme.secondaryButton("New chapter", this::addBlank);
    private final JButton up = UiTheme.secondaryButton("Move up", () -> move(-1));
    private final JButton down = UiTheme.secondaryButton("Move down", () -> move(1));
    private final JButton delete = UiTheme.dangerButton("Delete", this::delete);

    private final Map<String, Image> scaledPreviewCache = new HashMap<>();
    private final Path draftPath;
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final javax.swing.Timer autosave = new javax.swing.Timer(650, e -> save(false, () -> {}));

    private Epub.Metadata extraMeta;
    private Epub.Options bookOptions;
    private Epub.Image cover;
    private boolean loading;
    private boolean busy;
    private boolean closing;
    private boolean focusMode;
    private Runnable pendingClose;
    private boolean dirty;
    private long changes;
    private final boolean saveEnabled;
    private Path lastFolder;

    Workspace(Path draftPath, Draft draft, boolean saveEnabled) {
        super(new BorderLayout(0, 10));
        this.draftPath = draftPath;
        this.saveEnabled = saveEnabled;
        this.extraMeta = draft.metadata();
        this.bookOptions = draft.options();
        this.cover = draft.cover();

        autosave.setRepeats(false);
        previewDelay.setRepeats(false);
        setBackground(UiTheme.bg());
        setBorder(new EmptyBorder(12, 16, 10, 16));

        livePreview.setEditable(false);
        livePreview.setBackground(Color.WHITE);
        livePreviewScroll = UiTheme.scrollPane(livePreview);

        preview.setContentType("text/html");
        preview.setEditable(false);
        preview.setBackground(Color.WHITE);
        preview.getAccessibleContext().setAccessibleName("Chapter preview");
        previewScroll = UiTheme.scrollPane(preview);

        setTransferHandler(fileDropHandler());
        headerPanel = buildHeader();
        mainSplit = buildCenter();
        add(headerPanel, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        bindShortcuts();

        title.setText(draft.title());
        author.setText(draft.author());
        language.setText(draft.language());
        refreshCoverThumbnail();
        chapters.addAll(draft.chapters());
        if (!chapters.isEmpty()) list.setSelectedIndex(0);
        else editorialCards.show(editorial, "empty");
        refreshControls();
        refreshStats();

        watch(title, () -> {
            firePropertyChange("bookTitle", null, title.getText());
            changed();
        });
        watch(author, this::changed);
        watch(language, this::changed);
        watch(chapterTitle, () -> updateChapter(true));
        watch(body, () -> updateChapter(false));
        body.getDocument().addUndoableEditListener(e -> {
            if (!loading) textUndo.addEdit(e.getEdit());
        });
        markdown.addActionListener(e -> updateChapter(true));
        hideHeading.addActionListener(e -> updateChapter(true));
        roleBox.addActionListener(e -> {
            if (!loading) updateChapter(true);
        });

        if (!saveEnabled) {
            status.setText("Saved draft could not be opened. Autosave is disabled for this session.");
        }
    }

    private JPanel buildHeader() {
        var header = new JPanel(new BorderLayout(0, 10));
        header.setOpaque(false);

        var top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        var brandBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        brandBox.setOpaque(false);
        wordmarkLabel = new JLabel("Quireloom");
        wordmarkLabel.setFont(UiTheme.serifFont(Font.BOLD, 28f));
        wordmarkLabel.setForeground(UiTheme.ink());
        var tag = UiTheme.mutedLabel("EPUB Studio");
        tag.setBorder(new EmptyBorder(8, 0, 0, 0));
        brandBox.add(wordmarkLabel);
        brandBox.add(tag);
        top.add(brandBox, BorderLayout.WEST);

        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        actions.setOpaque(false);
        actions.add(openBookBtn);
        actions.add(addChaptersBtn);
        actions.add(pasteBtn);
        actions.add(designBtn);
        actions.add(doctorBtn);
        actions.add(exportBtn);
        top.add(actions, BorderLayout.EAST);
        header.add(top, BorderLayout.NORTH);

        // Metadata card with visible clickable cover thumbnail!
        var metaCard = UiTheme.card(new BorderLayout(14, 0));
        coverThumb.setPreferredSize(new Dimension(54, 78));
        coverThumb.setMinimumSize(new Dimension(54, 78));
        coverThumb.setMaximumSize(new Dimension(54, 78));
        coverThumb.setFocusPainted(false);
        coverThumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        coverThumb.setMargin(new Insets(0, 0, 0, 0));
        coverThumb.setToolTipText("Click to design, replace, or remove cover");
        coverThumb.addActionListener(e -> showCoverPopup());
        metaCard.add(coverThumb, BorderLayout.WEST);

        var fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        var g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 10);
        g.weightx = 0.48; g.gridx = 0;
        fields.add(UiTheme.labeled("Book title", title), g);
        g.weightx = 0.30; g.gridx = 1;
        fields.add(UiTheme.labeled("Author", author), g);
        g.weightx = 0.10; g.gridx = 2;
        fields.add(UiTheme.labeled("Language", language), g);
        g.weightx = 0; g.fill = GridBagConstraints.NONE; g.anchor = GridBagConstraints.SOUTH;
        g.gridx = 3; g.insets = new Insets(0, 0, 0, 6);
        fields.add(moreMetaBtn, g);
        g.gridx = 4; g.insets = new Insets(0, 0, 0, 0);
        fields.add(designCoverBtn, g);

        metaCard.add(fields, BorderLayout.CENTER);
        header.add(metaCard, BorderLayout.CENTER);
        return header;
    }

    private JSplitPane buildCenter() {
        sidebarPanel = new JPanel(new BorderLayout(0, 8));
        sidebarPanel.setOpaque(false);
        sidebarPanel.add(count, BorderLayout.NORTH);

        list.setName("Chapters");
        list.getAccessibleContext().setAccessibleName("Chapters");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(UiTheme.surface());
        if (!GraphicsEnvironment.isHeadless()) list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(chapterReorderHandler());
        list.setCellRenderer(new ChapterCellRenderer());
        list.setFixedCellHeight(34);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelection();
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) list.setSelectedIndex(idx);
                buildChapterContextMenu().show(list, e.getX(), e.getY());
            }
        });
        listScroll = UiTheme.scrollPane(list);
        sidebarPanel.add(listScroll, BorderLayout.CENTER);

        var controls = new JPanel(new GridLayout(2, 2, 5, 5));
        controls.setOpaque(false);
        controls.add(newChapterBtn);
        controls.add(delete);
        controls.add(up);
        controls.add(down);
        sidebarPanel.add(controls, BorderLayout.SOUTH);

        // Editor panel.
        editor.setBackground(UiTheme.surface());
        editor.setBorder(new EmptyBorder(10, 12, 10, 12));

        var topEditor = new JPanel(new BorderLayout(0, 6));
        topEditor.setOpaque(false);
        var chapterBar = new JPanel(new BorderLayout(10, 0));
        chapterBar.setOpaque(false);
        chapterBar.add(UiTheme.labeled("Chapter title", chapterTitle), BorderLayout.CENTER);

        var rightOpts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightOpts.setOpaque(false);
        roleBox.setRenderer((l, val, i, sel, foc) -> {
            var lbl = new JLabel(val == null ? "" : val.label);
            lbl.setBorder(new EmptyBorder(3, 6, 3, 6));
            lbl.setOpaque(true);
            lbl.setBackground(sel ? UiTheme.selectionBg() : UiTheme.surface());
            lbl.setForeground(sel ? UiTheme.selectionFg() : UiTheme.ink());
            return lbl;
        });
        markdown.setOpaque(false);
        markdown.setForeground(UiTheme.ink());
        hideHeading.setOpaque(false);
        hideHeading.setForeground(UiTheme.ink());
        rightOpts.add(UiTheme.labeled("Role", roleBox));
        var checkStack = new JPanel(new GridLayout(2, 1, 0, 0));
        checkStack.setOpaque(false);
        checkStack.add(markdown);
        checkStack.add(hideHeading);
        rightOpts.add(checkStack);
        chapterBar.add(rightOpts, BorderLayout.EAST);

        topEditor.add(chapterBar, BorderLayout.NORTH);
        formatToolbar = buildFormatToolbar();
        topEditor.add(formatToolbar, BorderLayout.CENTER);
        topEditor.add(buildFindBar(), BorderLayout.SOUTH);
        editor.add(topEditor, BorderLayout.NORTH);

        configureBody(body, "Chapter text");
        bodyScroll = UiTheme.scrollPane(body);
        editSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, bodyScroll, null);
        UiTheme.styleSplitPane(editSplit);
        editSplit.setResizeWeight(0.5);
        editSplit.setDividerSize(0);
        editor.add(editSplit, BorderLayout.CENTER);

        previewTabPanel = new JPanel(new BorderLayout(0, 6));
        previewTabPanel.setBackground(UiTheme.surface());
        previewTabPanel.setBorder(new EmptyBorder(8, 10, 8, 10));
        var previewBar = new JPanel(new BorderLayout());
        previewBar.setOpaque(false);
        previewBar.add(UiTheme.mutedLabel("Rendered chapter preview (approximates book theme)"), BorderLayout.WEST);
        previewBar.add(UiTheme.secondaryButton("Open Full Book in Browser", this::previewInBrowser), BorderLayout.EAST);
        previewTabPanel.add(previewBar, BorderLayout.NORTH);
        previewTabPanel.add(previewScroll, BorderLayout.CENTER);

        UiTheme.styleTabbedPane(tabs);
        tabs.addTab("Edit", editor);
        tabs.addTab("Preview", previewTabPanel);
        tabs.addChangeListener(e -> refreshPreview());
        editorial.add(tabs, "chapter");

        var emptyCard = UiTheme.card(new GridBagLayout());
        var emptyInner = new JPanel();
        emptyInner.setOpaque(false);
        emptyInner.setLayout(new BoxLayout(emptyInner, BoxLayout.Y_AXIS));
        var emptyHeading = new JLabel("Start your next book");
        emptyHeading.setFont(UiTheme.serifFont(Font.BOLD, 24f));
        emptyHeading.setForeground(UiTheme.ink());
        emptyHeading.setAlignmentX(CENTER_ALIGNMENT);
        var emptySub = UiTheme.mutedLabel("Drop EPUB, Markdown, TXT, PDF, or images here — or create a chapter below.");
        emptySub.setAlignmentX(CENTER_ALIGNMENT);
        var emptyBtns = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 12));
        emptyBtns.setOpaque(false);
        emptyBtns.add(UiTheme.primaryButton("+ New Chapter", this::addBlank));
        emptyBtns.add(UiTheme.secondaryButton("Import Files…", this::chooseFiles));
        emptyBtns.add(UiTheme.secondaryButton("Paste Manuscript…", this::paste));
        emptyInner.add(emptyHeading);
        emptyInner.add(Box.createVerticalStrut(6));
        emptyInner.add(emptySub);
        emptyInner.add(Box.createVerticalStrut(8));
        emptyInner.add(emptyBtns);
        emptyCard.add(emptyInner);
        editorial.add(emptyCard, "empty");

        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, editorial);
        UiTheme.styleSplitPane(split);
        split.setDividerLocation(268);
        split.setDividerSize(9);
        sidebarPanel.setMinimumSize(new Dimension(210, 200));
        editorial.setMinimumSize(new Dimension(400, 200));
        return split;
    }

    private JPanel buildFormatToolbar() {
        var bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        bar.setBackground(UiTheme.subtle());
        bar.setBorder(BorderFactory.createCompoundBorder(
            new UiTheme.RoundedBorder(UiTheme::border, 6), new EmptyBorder(2, 4, 2, 4)));

        bar.add(UiTheme.ghostButton("B", "Bold (Ctrl+B)", () -> wrapSelection("**", "**", "bold text")));
        bar.add(UiTheme.ghostButton("I", "Italic (Ctrl+I)", () -> wrapSelection("*", "*", "italic text")));
        bar.add(UiTheme.ghostButton("S̶", "Strikethrough", () -> wrapSelection("~~", "~~", "deleted")));
        bar.add(UiTheme.ghostButton("H2", "Section heading", () -> prefixLine("## ")));
        bar.add(UiTheme.ghostButton("H3", "Subheading", () -> prefixLine("### ")));
        bar.add(UiTheme.ghostButton("“”", "Blockquote", () -> prefixLine("> ")));
        bar.add(UiTheme.ghostButton("</>", "Inline code", () -> wrapSelection("`", "`", "code")));
        bar.add(UiTheme.ghostButton("Link", "Insert hyperlink (Ctrl+K)", this::insertLink));
        bar.add(UiTheme.ghostButton("• List", "Bullet list", () -> prefixLine("- ")));
        bar.add(UiTheme.ghostButton("1. List", "Numbered list", () -> prefixLine("1. ")));
        bar.add(UiTheme.ghostButton("Table", "Insert Markdown table", () -> insertSnippet(
            "\n\n| Column 1 | Column 2 |\n| --- | --- |\n| Value A | Value B |\n\n")));
        bar.add(UiTheme.ghostButton("───", "Scene break", () -> insertSnippet("\n\n---\n\n")));
        bar.add(UiTheme.ghostButton("[^1]", "Insert EPUB 3 popup footnote", this::insertFootnote));
        bar.add(UiTheme.ghostButton("Image", "Insert illustration at cursor", () -> chooseImages(false)));
        bar.add(UiTheme.ghostButton("Split", "Split chapter at cursor (Ctrl+Shift+S)", this::splitChapterAtCursor));
        bar.add(UiTheme.ghostButton("Polish", "Clean whitespace & typography", this::polishCurrentChapter));
        return bar;
    }

    private JPanel buildFindBar() {
        findBar.setBackground(UiTheme.subtle());
        findBar.setBorder(BorderFactory.createCompoundBorder(
            new UiTheme.RoundedBorder(UiTheme::border, 6), new EmptyBorder(2, 6, 2, 6)));
        findField.setPreferredSize(new Dimension(170, 26));
        replaceField.setPreferredSize(new Dimension(150, 26));
        findCase.setOpaque(false); findCase.setForeground(UiTheme.ink()); findCase.setToolTipText("Match case");
        findRegex.setOpaque(false); findRegex.setForeground(UiTheme.ink()); findRegex.setToolTipText("Regular expression");

        findBar.add(new JLabel("Find:"));
        findBar.add(findField);
        findBar.add(UiTheme.ghostButton("◀", "Previous match", () -> findNext(false)));
        findBar.add(UiTheme.ghostButton("▶", "Next match", () -> findNext(true)));
        findBar.add(new JLabel("Replace:"));
        findBar.add(replaceField);
        findBar.add(UiTheme.ghostButton("One", "Replace selection", this::replaceCurrentMatch));
        findBar.add(UiTheme.ghostButton("All", "Replace all in chapter", this::replaceAllInChapter));
        findBar.add(findCase);
        findBar.add(findRegex);
        findBar.add(findCount);
        findBar.add(UiTheme.ghostButton("✕", "Close Find bar (Esc)", () -> {
            findBar.setVisible(false);
            body.requestFocusInWindow();
        }));
        findField.addActionListener(e -> findNext(true));
        findField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFindCount(); }
            public void removeUpdate(DocumentEvent e) { updateFindCount(); }
            public void changedUpdate(DocumentEvent e) { updateFindCount(); }
        });
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        findField.getActionMap().put("closeFind", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { findBar.setVisible(false); body.requestFocusInWindow(); }
        });
        findBar.setVisible(false);
        return findBar;
    }

    private JPanel buildStatusBar() {
        var bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        bar.add(status, BorderLayout.WEST);
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(statsLabel);
        right.add(UiTheme.ghostButton("Focus (F11)", "Toggle distraction-free Focus mode", this::toggleFocusMode));
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPopupMenu buildChapterContextMenu() {
        var menu = new JPopupMenu();
        addMenu(menu, "New Chapter", this::addBlank);
        addMenu(menu, "Duplicate Chapter", this::duplicateChapter);
        addMenu(menu, "Split Chapter at Cursor", this::splitChapterAtCursor);
        addMenu(menu, "Merge with Next Chapter", this::mergeWithNextChapter);
        addMenu(menu, "Auto-Split by Headings", this::autoSplitSelectedChapter);
        menu.addSeparator();
        addMenu(menu, "Move Up", () -> move(-1));
        addMenu(menu, "Move Down", () -> move(1));
        menu.addSeparator();
        addMenu(menu, "Delete Chapter", this::delete);
        return menu;
    }

    private static void addMenu(JPopupMenu menu, String label, Runnable action) {
        var item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }

    private void bindShortcuts() {
        shortcut("export", KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK, this::chooseExport);
        shortcut("save", KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK, () -> save(false, () -> status.setText("Draft saved.")));
        shortcut("find", KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, () -> showFindBar(false));
        shortcut("replace", KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK, () -> showFindBar(true));
        shortcut("globalReplace", KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, this::replaceAcrossChapters);
        shortcut("bold", KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, () -> wrapSelection("**", "**", "bold text"));
        shortcut("italic", KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK, () -> wrapSelection("*", "*", "italic text"));
        shortcut("link", KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK, this::insertLink);
        shortcut("undo", KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, this::undoText);
        shortcut("redo", KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, this::redoText);
        shortcut("redoY", KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, this::redoText);
        shortcut("duplicate", KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK, this::duplicateChapter);
        shortcut("splitCh", KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, this::splitChapterAtCursor);
        shortcut("focus", KeyEvent.VK_F11, 0, this::toggleFocusMode);
    }

    private TransferHandler fileDropHandler() {
        return new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return !busy && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    var files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    importPaths(files.stream().map(file -> ((File) file).toPath()).toList());
                    return true;
                } catch (Exception error) {
                    report("Import failed", message(error));
                    return false;
                }
            }
        };
    }

    private TransferHandler chapterReorderHandler() {
        return new TransferHandler() {
            private int draggedIndex = -1;
            @Override public int getSourceActions(JComponent c) { return MOVE; }
            @Override protected Transferable createTransferable(JComponent c) {
                draggedIndex = list.getSelectedIndex();
                return new StringSelection(String.valueOf(draggedIndex));
            }
            @Override public boolean canImport(TransferSupport support) {
                if (busy) return false;
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true;
                return support.isDrop() && draggedIndex >= 0 && support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return fileDropHandler().importData(support);
                }
                if (draggedIndex < 0 || !support.isDrop()) return false;
                var dl = (JList.DropLocation) support.getDropLocation();
                int target = dl.getIndex();
                if (target < 0 || target > chapters.size() || target == draggedIndex || target == draggedIndex + 1) return false;
                pushStructureUndo();
                var item = chapters.remove(draggedIndex);
                if (target > draggedIndex) target--;
                chapters.add(target, item);
                select(target);
                changed();
                draggedIndex = -1;
                return true;
            }
            @Override protected void exportDone(JComponent c, Transferable data, int action) { draggedIndex = -1; }
        };
    }

    // --- Authoring & Chapter Actions ---

    void undoText() {
        if (textUndo.canUndo()) textUndo.undo();
    }

    void redoText() {
        if (textUndo.canRedo()) textUndo.redo();
    }

    void undoStructure() {
        if (busy || structureUndo.isEmpty()) {
            status.setText("Nothing to undo in book structure.");
            return;
        }
        var prev = structureUndo.pop();
        int prevSel = prev.chapters().isEmpty() ? -1 : Math.max(0, Math.min(prev.selectedIndex(), prev.chapters().size() - 1));
        loading = true;
        try {
            chapters.clear();
            chapters.addAll(prev.chapters());
        } finally {
            loading = false;
        }
        if (prevSel >= 0) select(prevSel);
        showSelection();
        changed();
        status.setText("Undid last chapter structure change.");
    }

    private void pushStructureUndo() {
        structureUndo.push(new StructureSnapshot(List.copyOf(Collections.list(chapters.elements())), list.getSelectedIndex()));
        while (structureUndo.size() > 25) structureUndo.removeLast();
    }

    void showFindBar(boolean focusReplace) {
        if (list.getSelectedIndex() < 0) return;
        tabs.setSelectedIndex(0);
        findBar.setVisible(true);
        String sel = body.getSelectedText();
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) findField.setText(sel);
        updateFindCount();
        (focusReplace ? replaceField : findField).requestFocusInWindow();
        (focusReplace ? replaceField : findField).selectAll();
    }

    private Pattern compileFindPattern() {
        String q = findField.getText();
        if (q.isEmpty()) return null;
        int flags = findCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (!findRegex.isSelected()) flags |= Pattern.LITERAL;
        try { return Pattern.compile(q, flags); }
        catch (Exception ignored) { return null; }
    }

    private void updateFindCount() {
        Pattern p = compileFindPattern();
        if (p == null) { findCount.setText(""); return; }
        int total = 0;
        var m = p.matcher(body.getText());
        while (m.find()) total++;
        findCount.setText(total + " match" + (total == 1 ? "" : "es"));
    }

    private void findNext(boolean forward) {
        Pattern p = compileFindPattern();
        if (p == null) return;
        String text = body.getText();
        var matches = new ArrayList<int[]>();
        var m = p.matcher(text);
        while (m.find()) matches.add(new int[]{m.start(), m.end()});
        if (matches.isEmpty()) { Toolkit.getDefaultToolkit().beep(); return; }
        int caret = body.getCaretPosition();
        int[] pick = null;
        if (forward) {
            for (var pair : matches) if (pair[0] >= caret) { pick = pair; break; }
            if (pick == null) pick = matches.get(0);
        } else {
            int selStart = body.getSelectionStart();
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i)[0] < selStart) { pick = matches.get(i); break; }
            }
            if (pick == null) pick = matches.get(matches.size() - 1);
        }
        body.requestFocusInWindow();
        body.select(pick[0], pick[1]);
        updateFindCount();
    }

    private void replaceCurrentMatch() {
        Pattern p = compileFindPattern();
        if (p == null) return;
        String selected = body.getSelectedText();
        if (selected != null && p.matcher(selected).matches()) {
            // Protect image markers from accidental corruption.
            if (Epub.ILLUSTRATION.matcher(selected).find()) {
                status.setText("Skipped replacing inside an embedded image marker.");
                return;
            }
            String rep = findRegex.isSelected() ? p.matcher(selected).replaceFirst(replaceField.getText()) : replaceField.getText();
            body.replaceSelection(rep);
        }
        findNext(true);
    }

    private void replaceAllInChapter() {
        Pattern p = compileFindPattern();
        if (p == null) return;
        try {
            var res = safeReplaceProtectingImages(body.getText(), p,
                findRegex.isSelected() ? replaceField.getText() : Matcher.quoteReplacement(replaceField.getText()));
            if (res.count() == 0) { status.setText("No matches in current chapter."); return; }
            body.setText(res.text());
            updateFindCount();
            status.setText("Replaced " + res.count() + " match(es) in current chapter.");
        } catch (Exception ex) {
            report("Replace error", message(ex));
        }
    }

    private void wrapSelection(String open, String close, String placeholder) {
        if (busy || list.getSelectedIndex() < 0) return;
        tabs.setSelectedIndex(0);
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        String selected = start == end ? placeholder : body.getSelectedText();
        body.replaceRange(open + selected + close, start, end);
        body.requestFocusInWindow();
        body.select(start + open.length(), start + open.length() + selected.length());
    }

    private void prefixLine(String prefix) {
        if (busy || list.getSelectedIndex() < 0) return;
        tabs.setSelectedIndex(0);
        try {
            int line = body.getLineOfOffset(body.getCaretPosition());
            int start = body.getLineStartOffset(line);
            body.insert(prefix, start);
            body.requestFocusInWindow();
        } catch (Exception ignored) {}
    }

    private void insertSnippet(String snippet) {
        if (busy || list.getSelectedIndex() < 0) return;
        tabs.setSelectedIndex(0);
        int pos = body.getCaretPosition();
        body.insert(snippet, pos);
        body.requestFocusInWindow();
        body.setCaretPosition(pos + snippet.length());
    }

    private void insertLink() {
        if (busy || list.getSelectedIndex() < 0) return;
        String sel = body.getSelectedText();
        String url = JOptionPane.showInputDialog(this, "Enter URL (https://... or mailto:...):", "https://");
        if (url == null || url.isBlank()) return;
        wrapSelection("[", "](" + url.strip() + ")", sel == null || sel.isEmpty() ? "link text" : sel);
    }

    private void insertFootnote() {
        if (busy || list.getSelectedIndex() < 0) return;
        String text = body.getText();
        int n = 1;
        while (text.contains("[^" + n + "]")) n++;
        String note = JOptionPane.showInputDialog(this, "Footnote #" + n + " text (renders as EPUB 3 popup footnote):");
        if (note == null || note.isBlank()) return;
        int pos = body.getCaretPosition();
        body.insert("[^" + n + "]", pos);
        String endDef = (body.getText().endsWith("\n") ? "\n" : "\n\n") + "[^" + n + "]: " + note.strip() + "\n";
        body.append(endDef);
        body.requestFocusInWindow();
        body.setCaretPosition(pos + ("[^" + n + "]").length());
    }

    void polishCurrentChapter() {
        int idx = list.getSelectedIndex();
        if (busy || idx < 0) return;
        String cleaned = Manuscript.polish(body.getText(), bookOptions.smartTypography());
        if (!cleaned.equals(body.getText())) {
            body.setText(cleaned);
            status.setText("Cleaned whitespace" + (bookOptions.smartTypography() ? " and smart typography." : "."));
        } else {
            status.setText("Chapter text is already clean.");
        }
    }

    void duplicateChapter() {
        int idx = list.getSelectedIndex();
        if (busy || idx < 0) return;
        pushStructureUndo();
        var src = chapters.get(idx);
        var copy = new Epub.Chapter(src.title() + " (Copy)", src.body(), src.markdown(),
            src.images(), src.role(), src.hideHeading());
        chapters.add(idx + 1, copy);
        select(idx + 1);
        changed();
        status.setText("Duplicated chapter.");
    }

    void splitChapterAtCursor() {
        int idx = list.getSelectedIndex();
        if (busy || idx < 0) return;
        var ch = chapters.get(idx);
        int caret = Math.max(0, Math.min(body.getCaretPosition(), ch.body().length()));
        if (caret == 0 || caret == ch.body().length()) {
            report("Split Chapter", "Place the cursor inside the chapter text where you want the new chapter to begin.");
            return;
        }
        String newTitle = JOptionPane.showInputDialog(this, "Title for the new chapter:", ch.title() + " (Part 2)");
        if (newTitle == null || newTitle.isBlank()) return;
        pushStructureUndo();
        String firstBody = ch.body().substring(0, caret).stripTrailing();
        String secondBody = ch.body().substring(caret).stripLeading();
        var first = new Epub.Chapter(ch.title(), firstBody, ch.markdown(), ch.images(), ch.role(), ch.hideHeading()).pruneUnusedImages();
        var second = new Epub.Chapter(newTitle.strip(), secondBody, ch.markdown(), ch.images(), ch.role(), ch.hideHeading()).pruneUnusedImages();
        chapters.set(idx, first);
        chapters.add(idx + 1, second);
        select(idx + 1);
        showSelection();
        changed();
        status.setText("Split chapter into two chapters.");
    }

    void mergeWithNextChapter() {
        int idx = list.getSelectedIndex();
        if (busy || idx < 0 || idx + 1 >= chapters.size()) return;
        var a = chapters.get(idx);
        var b = chapters.get(idx + 1);
        if (JOptionPane.showConfirmDialog(this,
                "Merge \"" + b.title() + "\" into the end of \"" + a.title() + "\"?",
                "Merge Chapters", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        pushStructureUndo();
        var mergedImages = new LinkedHashMap<>(a.images());
        mergedImages.putAll(b.images());
        String sep = a.body().isBlank() ? "" : "\n\n";
        String headingPrefix = b.title().isBlank() ? "" : (a.markdown() ? "## " + b.title() + "\n\n" : b.title() + "\n\n");
        var merged = new Epub.Chapter(a.title(), a.body() + sep + headingPrefix + b.body(),
            a.markdown() || b.markdown(), mergedImages, a.role(), a.hideHeading());
        chapters.set(idx, merged);
        chapters.remove(idx + 1);
        select(idx);
        showSelection();
        changed();
        status.setText("Merged chapters.");
    }

    void autoSplitSelectedChapter() {
        int idx = list.getSelectedIndex();
        if (busy || idx < 0) return;
        var ch = chapters.get(idx);
        var split = Manuscript.fromPaste(ch.body(), true, ch.markdown());
        if (split.size() <= 1) {
            JOptionPane.showMessageDialog(this, "No chapter headings (e.g. # Title, Chapter 1, Part II, অধ্যায় ১) were detected inside this chapter.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "Detected " + split.size() + " chapters inside \"" + ch.title() + "\". Split them now?",
                "Auto-Split Chapter", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        pushStructureUndo();
        chapters.remove(idx);
        for (int i = 0; i < split.size(); i++) {
            var part = split.get(i);
            var withImages = new Epub.Chapter(part.title(), part.body(), part.markdown(), ch.images()).pruneUnusedImages();
            chapters.add(idx + i, withImages);
        }
        select(idx);
        showSelection();
        changed();
        status.setText("Auto-split into " + split.size() + " chapters.");
    }

    void toggleFocusMode() {
        focusMode = !focusMode;
        headerPanel.setVisible(!focusMode);
        sidebarPanel.setVisible(!focusMode);
        if (focusMode) {
            mainSplit.setDividerSize(0);
            mainSplit.setDividerLocation(0);
        } else {
            mainSplit.setDividerSize(9);
            mainSplit.setDividerLocation(268);
        }
        revalidate();
        repaint();
    }

    void openDesignDialog() {
        if (busy) return;
        BookDesignDialog.showDesignDialog(this, bookOptions, opts -> {
            bookOptions = opts;
            changed();
            refreshPreview();
            status.setText("Updated book theme: " + opts.theme().label);
        });
    }

    void openMetadataDialog() {
        if (busy) return;
        var current = snapshot().metadata();
        BookDesignDialog.showMetadataDialog(this, current, updated -> {
            extraMeta = updated;
            changed();
            status.setText("Updated book metadata.");
        });
    }

    void openCoverStudio() {
        if (busy) return;
        CoverDesigner.showDialog(this, snapshot().metadata(), img -> {
            setCover(img);
            status.setText("Applied custom designed cover.");
        });
    }

    void runDoctor() {
        if (busy) return;
        DoctorDialog.showReport(this, EpubDoctor.checkDraft(snapshot()), null);
    }

    void showStatistics() {
        DoctorDialog.showBookStatistics(this, snapshot());
    }

    void optimizeEmbeddedImages() {
        if (busy) return;
        runTask("Optimizing embedded images…", () -> {
            int optimized = 0;
            long saved = 0;
            Epub.Image newCover = cover;
            if (newCover != null) {
                var opt = BookFiles.optimizeImage(newCover, 1600, 0.86f);
                if (opt.bytes().length < newCover.bytes().length) {
                    saved += newCover.bytes().length - opt.bytes().length;
                    newCover = opt;
                    optimized++;
                }
            }
            var updatedList = new ArrayList<Epub.Chapter>();
            for (var ch : Collections.list(chapters.elements())) {
                var clean = ch.pruneUnusedImages();
                var newImgs = new LinkedHashMap<String, Epub.Image>();
                String newBody = clean.body();
                for (var e : clean.images().entrySet()) {
                    var opt = BookFiles.optimizeImage(e.getValue(), 1600, 0.84f);
                    if (opt.bytes().length < e.getValue().bytes().length) {
                        saved += e.getValue().bytes().length - opt.bytes().length;
                        optimized++;
                        String newKey = e.getKey().replaceFirst("\\.[^.]+$", opt.extension());
                        if (!newKey.equals(e.getKey())) newBody = newBody.replace("(" + e.getKey() + ")", "(" + newKey + ")");
                        newImgs.put(newKey, opt);
                    } else {
                        newImgs.put(e.getKey(), e.getValue());
                    }
                }
                updatedList.add(new Epub.Chapter(clean.title(), newBody, clean.markdown(), newImgs, clean.role(), clean.hideHeading()));
            }
            return new Object[]{newCover, updatedList, optimized, saved};
        }, res -> {
            Epub.Image newCover = (Epub.Image) res[0];
            @SuppressWarnings("unchecked") var updatedList = (List<Epub.Chapter>) res[1];
            int optCount = (Integer) res[2];
            long bytesSaved = (Long) res[3];
            if (optCount > 0) {
                pushStructureUndo();
                cover = newCover;
                int sel = list.getSelectedIndex();
                loading = true;
                try {
                    chapters.clear();
                    chapters.addAll(updatedList);
                } finally { loading = false; }
                scaledPreviewCache.clear();
                refreshCoverThumbnail();
                if (sel >= 0 && sel < chapters.size()) select(sel);
                showSelection();
                changed();
            }
            String msg = optCount == 0 ? "All images are already compact."
                : "Optimized " + optCount + " image(s), saving " + EpubDoctor.formatBytes(bytesSaved) + ".";
            status.setText(msg);
            if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(this, msg, "Image Optimizer", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    void previewInBrowser() {
        if (busy || chapters.isEmpty()) return;
        try {
            Draft snap = snapshot();
            if (snap.title().isBlank()) {
                snap = new Draft(new Epub.Metadata(snap.id(), "Book Preview", snap.metadata().subtitle(),
                    snap.author(), snap.language(), snap.metadata().description(), snap.metadata().publisher(),
                    snap.metadata().subjects(), snap.metadata().series(), snap.metadata().seriesIndex(),
                    snap.metadata().rights(), snap.metadata().date()), snap.chapters(), snap.cover(), snap.options());
            }
            Path temp = Files.createTempFile("quireloom-preview-", ".html");
            temp.toFile().deleteOnExit();
            snap.exportHtml(temp);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(temp.toUri());
            } else {
                new ProcessBuilder("xdg-open", temp.toString()).start();
            }
            status.setText("Opened live HTML reader preview in browser.");
        } catch (Exception ex) {
            report("Browser Preview", message(ex));
        }
    }

    // --- Imports & Exports ---

    private void chooseFiles() {
        if (busy) return;
        var chooser = chooser("Add chapters to this book",
            "EPUB, Markdown, TXT, PDF & Images", "epub", "md", "markdown", "txt", "pdf", "png", "jpg", "jpeg", "gif", "webp");
        chooser.setMultiSelectionEnabled(true);
        var note = new JLabel("<html>EPUB import preserves formatting,<br>illustrations, cover &amp; metadata.<br>PDFs import text or pages.</html>");
        note.setBorder(new EmptyBorder(12, 10, 12, 7));
        chooser.setAccessory(note);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            lastFolder = chooser.getCurrentDirectory().toPath();
            importPaths(Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList());
        }
    }

    void importPaths(List<Path> paths) {
        if (busy || paths.isEmpty()) return;
        var ordered = new ArrayList<>(paths);
        ordered.sort((a, b) -> Manuscript.compareNames(a.getFileName().toString(), b.getFileName().toString()));
        int slots = BookFiles.CHAPTER_LIMIT - chapters.size();
        long budget = remainingBytes();
        var failures = new ArrayList<String>();
        runTask("Importing files…", () -> {
            var accepted = new ArrayList<BookFiles.Imported>();
            long remaining = budget;
            int free = slots;
            for (var file : ordered) {
                try {
                    var data = BookFiles.read(file);
                    long cost = chapterBytes(data.chapters());
                    if (data.chapters().size() > free || cost > remaining) {
                        throw new IOException("The draft limit is 2,000 chapters or 256 MB.");
                    }
                    if (data.chapters().stream().anyMatch(ch -> ch.title().length() > 2048 || ch.body().length() > BookFiles.TEXT_LIMIT)) {
                        throw new IOException("A chapter exceeds the title or text length limit.");
                    }
                    accepted.add(data);
                    free -= data.chapters().size();
                    remaining -= cost;
                } catch (Exception error) {
                    failures.add(file.getFileName() + " — " + message(error));
                }
            }
            return accepted;
        }, imports -> {
            int first = chapters.size();
            boolean setLanguage = first == 0 && language.getText().equals("en");
            boolean importedEpub = false;
            int imgCount = 0;
            if (!imports.isEmpty()) pushStructureUndo();
            for (var data : imports) {
                var m = data.metadata();
                if (!m.title().isBlank() && title.getText().isBlank()) title.setText(shortField(m.title(), 512));
                if (!m.author().isBlank() && author.getText().isBlank()) author.setText(shortField(m.author(), 512));
                if (!m.language().isBlank() && (setLanguage || language.getText().isBlank())) {
                    language.setText(shortField(m.language(), 64));
                    setLanguage = false;
                }
                if (data.fromEpub()) {
                    importedEpub = true;
                    extraMeta = mergeMissingMetadata(extraMeta, m);
                    if (cover == null && data.cover() != null) {
                        cover = data.cover();
                        refreshCoverThumbnail();
                    }
                }
                for (var ch : data.chapters()) imgCount += ch.images().size();
                chapters.addAll(data.chapters());
            }
            if (chapters.size() > first) {
                select(first);
                changed();
            }
            int imported = chapters.size() - first;
            status.setText("Imported " + imported + (imported == 1 ? " chapter" : " chapters")
                + (imgCount > 0 ? " · " + imgCount + " image" + (imgCount == 1 ? "" : "s") : "")
                + (importedEpub ? " · Rich EPUB import" : "")
                + (failures.isEmpty() ? "" : " · " + failures.size() + (failures.size() == 1 ? " file skipped" : " files skipped")));
            if (!failures.isEmpty()) report("Files skipped", String.join("\n", failures));
            refreshStats();
        });
    }

    private static Epub.Metadata mergeMissingMetadata(Epub.Metadata base, Epub.Metadata incoming) {
        return new Epub.Metadata(
            base.id(), base.title(),
            base.subtitle().isBlank() ? incoming.subtitle() : base.subtitle(),
            base.author(), base.language(),
            base.description().isBlank() ? incoming.description() : base.description(),
            base.publisher().isBlank() ? incoming.publisher() : base.publisher(),
            base.subjects().isBlank() ? incoming.subjects() : base.subjects(),
            base.series().isBlank() ? incoming.series() : base.series(),
            base.seriesIndex().isBlank() ? incoming.seriesIndex() : base.seriesIndex(),
            base.rights().isBlank() ? incoming.rights() : base.rights(),
            base.date().isBlank() ? incoming.date() : base.date());
    }

    private void paste() {
        if (busy) return;
        var input = new JTextArea(18, 66);
        configureBody(input, "Manuscript");
        var split = new JCheckBox("Detect chapter headings", true);
        var format = new JCheckBox("Interpret as Markdown", true);
        var options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        options.add(split);
        options.add(format);
        var panel = new JPanel(new BorderLayout(0, 6));
        panel.add(UiTheme.scrollPane(input), BorderLayout.CENTER);
        panel.add(options, BorderLayout.SOUTH);
        while (JOptionPane.showConfirmDialog(this, panel, "Paste manuscript text", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            var added = Manuscript.fromPaste(input.getText(), split.isSelected(), format.isSelected());
            if (added.isEmpty()) report("No text", "Paste some manuscript text first.");
            else if (addChapters(added)) return;
        }
    }

    void addBlank() {
        if (!busy && addChapters(List.of(new Epub.Chapter("Untitled chapter", "", true)))) {
            tabs.setSelectedIndex(0);
            chapterTitle.requestFocusInWindow();
            chapterTitle.selectAll();
        }
    }

    private boolean addChapters(List<Epub.Chapter> added) {
        if (chapters.size() + added.size() > BookFiles.CHAPTER_LIMIT || chapterBytes(added) > remainingBytes()
                || added.stream().anyMatch(ch -> ch.title().length() > 2048)) {
            report("Draft limit reached", "Use up to 2,000 chapters, 256 MB per draft, and 2,048 characters per title.");
            return false;
        }
        pushStructureUndo();
        int first = chapters.size();
        chapters.addAll(added);
        select(first);
        changed();
        refreshStats();
        return true;
    }

    private long remainingBytes() {
        return BookFiles.DRAFT_LIMIT - 256_000L - chapterBytes(Collections.list(chapters.elements()));
    }

    private static long chapterBytes(List<Epub.Chapter> list) {
        return list.stream().mapToLong(ch -> ch.title().getBytes(StandardCharsets.UTF_8).length
            + ch.images().values().stream().mapToLong(img -> img.bytes().length + 100L).sum()
            + ch.body().getBytes(StandardCharsets.UTF_8).length + 9L).sum();
    }

    private static String shortField(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private void showSelection() {
        if (loading) return;
        loading = true;
        try {
            var selection = list.getSelectedValue();
            editorialCards.show(editorial, selection == null ? "empty" : "chapter");
            if (selection != null) {
                chapterTitle.setText(selection.title());
                body.setText(selection.body());
                body.setCaretPosition(0);
                textUndo.discardAllEdits();
                markdown.setSelected(selection.markdown());
                hideHeading.setSelected(selection.hideHeading());
                roleBox.setSelectedItem(selection.role());
            }
            refreshControls();
            refreshPreview();
            refreshStats();
            if (findBar.isVisible()) updateFindCount();
        } finally {
            loading = false;
        }
    }

    private void updateChapter(boolean metadataOrTitleChanged) {
        int index = list.getSelectedIndex();
        if (loading || index < 0) return;
        var old = chapters.get(index);
        Epub.ChapterRole role = (Epub.ChapterRole) roleBox.getSelectedItem();
        if (role == null) role = Epub.ChapterRole.CHAPTER;
        var updated = new Epub.Chapter(chapterTitle.getText(), body.getText(),
            markdown.isSelected(), old.images(), role, hideHeading.isSelected());
        if (updated.equals(old)) return;
        chapters.set(index, updated);
        if (editSplit.getRightComponent() != null || metadataOrTitleChanged) previewDelay.restart();
        else previewDelay.restart();
        changed();
    }

    private static String toSwingHtml(String xhtml) {
        return xhtml
            .replaceAll(" epub:type=\"[^\"]*\"", "")
            .replaceAll(" aria-label=\"[^\"]*\"", "")
            .replaceAll(" aria-hidden=\"[^\"]*\"", "")
            .replaceAll("<section[^>]*>", "<div>")
            .replace("</section>", "</div>")
            .replaceAll("<figure[^>]*>", "<div style=\"text-align:center; margin:12px 0;\">")
            .replace("</figure>", "</div>")
            .replace("<figcaption>", "<div style=\"font-size:11pt; font-style:italic;\">")
            .replace("</figcaption>", "</div>")
            .replace("<del>", "<strike>")
            .replace("</del>", "</strike>")
            .replace("<br/>", "<br>")
            .replace("<hr/>", "<hr>");
    }

    private void refreshPreview() {
        var selection = list.getSelectedValue();
        boolean sideBySide = editSplit.getRightComponent() != null;
        if ((tabs.getSelectedIndex() == 1 || sideBySide) && selection != null) {
            var pane = tabs.getSelectedIndex() == 1 ? preview : livePreview;
            var scroll = tabs.getSelectedIndex() == 1 ? previewScroll : livePreviewScroll;
            // Preserve proportional scroll position so live preview NEVER snaps back to the top!
            var bar = scroll.getVerticalScrollBar();
            int maxBefore = Math.max(1, bar.getMaximum() - bar.getVisibleAmount());
            double scrollRatio = bar.getValue() > 0 ? (double) bar.getValue() / maxBefore : 0.0;

            var cache = new Hashtable<java.net.URL, Image>();
            String rendered = toSwingHtml(Epub.render(selection, bookOptions));
            int availW = Math.max(200, pane.getWidth() - 52);
            for (var entry : selection.images().entrySet()) {
                String file = entry.getKey();
                try {
                    var url = new URI("file", null, "/quireloom-preview/" + file, null).toURL();
                    String cacheKey = file + "@" + (availW / 32);
                    Image image = scaledPreviewCache.get(cacheKey);
                    if (image == null) {
                        var icon = new ImageIcon(entry.getValue().bytes());
                        image = icon.getImage();
                        if (icon.getIconWidth() > availW) {
                            image = image.getScaledInstance(availW, -1, Image.SCALE_SMOOTH);
                        }
                        if (scaledPreviewCache.size() > 64) scaledPreviewCache.clear();
                        scaledPreviewCache.put(cacheKey, image);
                    }
                    cache.put(url, image);
                    rendered = rendered.replace("src=\"" + file + "\"", "src=\"" + url + "\"");
                } catch (Exception ignored) {}
            }
            boolean sans = bookOptions.theme() == Epub.BookTheme.MODERN || bookOptions.theme() == Epub.BookTheme.TECHNICAL;
            boolean centerH1 = bookOptions.theme() == Epub.BookTheme.CLASSIC
                || bookOptions.theme() == Epub.BookTheme.LITERARY || bookOptions.theme() == Epub.BookTheme.POETRY;
            String bgHex = UiTheme.isDark() ? "#1E2227" : "#FFFDF8";
            String fgHex = UiTheme.isDark() ? "#E3E8EC" : "#1F2C26";
            String mutedHex = UiTheme.isDark() ? "#96A3AD" : "#5F6C65";

            pane.getDocument().putProperty("imageCache", cache);
            pane.setText("<html><head><style>"
                + "body { margin: 22px 26px; background: " + bgHex + "; color: " + fgHex
                + "; font-family: " + (sans ? "sans-serif" : "serif") + "; font-size: 14pt; }"
                + "h1 { font-size: 20pt; margin-bottom: 14px; " + (centerH1 ? "text-align: center;" : "") + " }"
                + "h2 { font-size: 16pt; margin-top: 16px; } h3 { font-size: 14pt; }"
                + "blockquote { margin: 12px 20px; font-style: italic; color: " + mutedHex + "; }"
                + "pre { font-family: monospace; font-size: 11pt; background: " + (UiTheme.isDark() ? "#272D34" : "#F1ECE1") + "; padding: 8px; }"
                + "code { font-family: monospace; font-size: 12pt; }"
                + "table { border-collapse: collapse; margin: 10px 0; }"
                + "th, td { border: 1px solid " + mutedHex + "; padding: 4px 8px; }"
                + ".scene-break { text-align: center; margin: 14px 0; color: " + mutedHex + "; }"
                + "figcaption { font-size: 11pt; font-style: italic; color: " + mutedHex + "; }"
                + "</style></head><body>" + rendered + "</body></html>");

            if (scrollRatio > 0.0) {
                SwingUtilities.invokeLater(() -> {
                    int maxAfter = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
                    bar.setValue((int) Math.round(scrollRatio * maxAfter));
                });
            } else {
                pane.setCaretPosition(0);
            }
        }
    }

    // --- Protected Global Find & Replace ---

    private record ReplaceOutcome(String text, long count) {}

    private static ReplaceOutcome safeReplaceProtectingImages(String text, Pattern pattern, String quotedReplacement) {
        // Split around ILLUSTRATION markers so regex/literal replaces NEVER corrupt image UUID filenames!
        var imgMatcher = Epub.ILLUSTRATION.matcher(text);
        var out = new StringBuilder(text.length());
        int last = 0;
        long total = 0;
        while (imgMatcher.find()) {
            String segment = text.substring(last, imgMatcher.start());
            var segRes = replaceSegment(segment, pattern, quotedReplacement);
            out.append(segRes.text());
            total += segRes.count();
            // Keep the illustration line untouched!
            out.append(imgMatcher.group(0));
            last = imgMatcher.end();
        }
        var tailRes = replaceSegment(text.substring(last), pattern, quotedReplacement);
        out.append(tailRes.text());
        total += tailRes.count();
        if (out.length() > BookFiles.TEXT_LIMIT) throw new IllegalArgumentException("Replacement exceeds the chapter limit.");
        return new ReplaceOutcome(out.toString(), total);
    }

    private static ReplaceOutcome replaceSegment(String segment, Pattern pattern, String rep) {
        var matcher = pattern.matcher(segment);
        var sb = new StringBuilder(segment.length());
        long count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(sb, rep);
            if (sb.length() > BookFiles.TEXT_LIMIT) throw new IllegalArgumentException("Replacement exceeds the chapter limit.");
            count++;
        }
        matcher.appendTail(sb);
        return new ReplaceOutcome(sb.toString(), count);
    }

    void replaceAcrossChapters() {
        if (busy || chapters.isEmpty()) return;
        var query = UiTheme.textField("Find", 1024);
        var replacement = UiTheme.textField("Replace", 1024);
        var caseSensitive = new JCheckBox("Match case", true);
        var wholeWord = new JCheckBox("Whole words only");
        var regex = new JCheckBox("Regular expression");
        var includeTitles = new JCheckBox("Also search chapter titles");
        var controls = new JPanel(new GridLayout(0, 1, 0, 4));
        controls.add(new JLabel("Find across all chapters (image markers are automatically protected):"));
        controls.add(query);
        controls.add(new JLabel("Replace with (leave empty to delete):"));
        controls.add(replacement);
        var optsRow = new JPanel(new GridLayout(2, 2, 8, 2));
        optsRow.add(caseSensitive); optsRow.add(wholeWord);
        optsRow.add(regex); optsRow.add(includeTitles);
        controls.add(optsRow);
        if (JOptionPane.showConfirmDialog(this, controls, "Find & Replace Across Book",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION || query.getText().isEmpty()) return;

        var original = List.copyOf(Collections.list(chapters.elements()));
        var updated = new ArrayList<Epub.Chapter>();
        var affected = new ArrayList<String>();
        long matches = 0;
        try {
            int flags = caseSensitive.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            String expr = query.getText();
            if (!regex.isSelected()) {
                expr = Pattern.quote(expr);
                if (wholeWord.isSelected()) expr = "\\b" + expr + "\\b";
            } else if (wholeWord.isSelected()) {
                expr = "\\b(?:" + expr + ")\\b";
            }
            var pattern = Pattern.compile(expr, flags);
            String value = regex.isSelected() ? replacement.getText() : Matcher.quoteReplacement(replacement.getText());

            for (var chapter : original) {
                var bodyRes = safeReplaceProtectingImages(chapter.body(), pattern, value);
                String newTitle = chapter.title();
                long chCount = bodyRes.count();
                if (includeTitles.isSelected()) {
                    var tRes = replaceSegment(chapter.title(), pattern, value);
                    if (tRes.count() > 0 && !tRes.text().isBlank()) {
                        newTitle = shortField(tRes.text(), 2048);
                        chCount += tRes.count();
                    }
                }
                if (chCount > 0) affected.add(chapter.title() + " (" + chCount + ")");
                matches += chCount;
                updated.add(new Epub.Chapter(newTitle, bodyRes.text(), chapter.markdown(),
                    chapter.images(), chapter.role(), chapter.hideHeading()));
            }
            if (chapterBytes(updated) > BookFiles.DRAFT_LIMIT - 256_000L) {
                throw new IllegalArgumentException("Replacement exceeds the 256 MB draft limit.");
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            report("Find and replace", message(error));
            return;
        }
        if (matches == 0) {
            JOptionPane.showMessageDialog(this, "No matches found.");
            return;
        }
        var summary = new JTextArea(matches + " match(es) across " + affected.size() + " chapter(s)\n\n" + String.join("\n", affected), 10, 42);
        summary.setEditable(false);
        if (JOptionPane.showConfirmDialog(this, UiTheme.scrollPane(summary), "Apply Replacements?",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        pushStructureUndo();
        for (int i = 0; i < updated.size(); i++) chapters.set(i, updated.get(i));
        showSelection();
        changed();
        status.setText("Applied " + matches + " replacement(s) across " + affected.size() + " chapter(s).");
    }

    void splitPreview(boolean enabled) {
        tabs.setSelectedIndex(0);
        editSplit.setRightComponent(enabled ? livePreviewScroll : null);
        editSplit.setDividerSize(enabled ? 7 : 0);
        if (enabled) SwingUtilities.invokeLater(() -> editSplit.setDividerLocation(0.5));
        refreshPreview();
    }

    void move(int by) {
        int from = list.getSelectedIndex();
        int to = from + by;
        if (busy || from < 0 || to < 0 || to >= chapters.size()) return;
        pushStructureUndo();
        var selected = chapters.get(from);
        chapters.set(from, chapters.get(to));
        chapters.set(to, selected);
        select(to);
        changed();
    }

    void delete() {
        int index = list.getSelectedIndex();
        if (busy || index < 0) return;
        if (JOptionPane.showConfirmDialog(this, "Delete \"" + chapters.get(index).title() + "\"?", "Delete chapter",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        pushStructureUndo();
        chapters.remove(index);
        if (!chapters.isEmpty()) select(Math.min(index, chapters.size() - 1));
        else showSelection();
        changed();
        refreshStats();
    }

    private void select(int index) {
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    void chooseImages(boolean standalone) {
        if (busy) return;
        int index = list.getSelectedIndex();
        if (!standalone && index < 0) {
            report("Insert illustrations", "Create or select a chapter first.");
            return;
        }
        var picker = chooser(standalone ? "Add image plate chapters" : "Insert illustrations at cursor",
            "PNG, JPEG, GIF & WebP images", "png", "jpg", "jpeg", "gif", "webp");
        picker.setMultiSelectionEnabled(true);
        if (picker.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        lastFolder = picker.getCurrentDirectory().toPath();
        var paths = Arrays.stream(picker.getSelectedFiles())
            .sorted((a, b) -> Manuscript.compareNames(a.getName(), b.getName())).toList();
        int caret = Math.max(0, body.getCaretPosition());
        long budget = remainingBytes();
        Epub.Chapter targetSnapshot = standalone ? null : chapters.get(index);

        runTask("Reading illustrations…", () -> {
            var pages = new ArrayList<Epub.Chapter>();
            long used = 0;
            for (var file : paths) {
                var image = BookFiles.readImage(file.toPath());
                String name = file.getName().replaceFirst("\\.[^.]+$", "").replaceAll("[\\]\\r\\n]", " ").strip();
                if (name.isEmpty()) name = "Illustration";
                String key = "img-" + UUID.randomUUID() + image.extension();
                var page = new Epub.Chapter(name, "![" + name + "](" + key + ")", false,
                    Map.of(key, image), Epub.ChapterRole.PLATE, standalone);
                used += chapterBytes(List.of(page)) + 8;
                if (used > budget) throw new IOException("Images exceed the 256 MB draft limit.");
                pages.add(page);
            }
            return pages;
        }, pages -> {
            if (pages.isEmpty()) return;
            if (standalone) { addChapters(pages); return; }
            if (index >= chapters.size() || !chapters.get(index).equals(targetSnapshot)) return;
            pushStructureUndo();
            var old = chapters.get(index);
            var images = new LinkedHashMap<>(old.images());
            var insert = new StringBuilder("\n\n");
            for (var page : pages) { images.putAll(page.images()); insert.append(page.body()).append("\n\n"); }
            if (old.body().length() + insert.length() > BookFiles.TEXT_LIMIT || images.size() > 10000) {
                report("Chapter limit", "This chapter cannot hold more illustrations.");
                return;
            }
            int safeCaret = Math.min(caret, old.body().length());
            String text = old.body().substring(0, safeCaret) + insert + old.body().substring(safeCaret);
            chapters.set(index, new Epub.Chapter(old.title(), text, old.markdown(), images, old.role(), old.hideHeading()));
            select(index);
            showSelection();
            body.setCaretPosition(Math.min(body.getText().length(), safeCaret + insert.length()));
            changed();
        });
    }

    private void showCoverPopup() {
        if (busy) return;
        var menu = new JPopupMenu();
        addMenu(menu, "Design Cover in Cover Studio…", this::openCoverStudio);
        addMenu(menu, "Choose Cover Image File…", this::chooseCover);
        if (cover != null) {
            menu.addSeparator();
            addMenu(menu, "Remove Cover", () -> setCover(null));
        }
        menu.show(coverThumb, 0, coverThumb.getHeight());
    }

    void chooseCover() {
        if (busy) return;
        var chooser = chooser("Choose cover image", "PNG, JPEG, GIF & WebP images", "png", "jpg", "jpeg", "gif", "webp");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            lastFolder = chooser.getCurrentDirectory().toPath();
            Path path = chooser.getSelectedFile().toPath();
            runTask("Reading cover…", () -> BookFiles.readImage(path), this::setCover);
        }
    }

    void setCover(Epub.Image image) {
        if (busy) return;
        cover = image;
        refreshCoverThumbnail();
        changed();
    }

    private void refreshCoverThumbnail() {
        coverThumb.setBackground(UiTheme.subtle());
        coverThumb.setBorder(new UiTheme.RoundedBorder(UiTheme.border(), 6));
        if (cover == null) {
            coverThumb.setIcon(null);
            coverThumb.setText("<html><center><small>+ Cover</small></center></html>");
            coverThumb.setForeground(UiTheme.muted());
            return;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(cover.bytes()));
            if (img != null) {
                Image scaled = img.getScaledInstance(50, 74, Image.SCALE_SMOOTH);
                coverThumb.setIcon(new ImageIcon(scaled));
                coverThumb.setText("");
                return;
            }
        } catch (IOException ignored) {}
        coverThumb.setIcon(null);
        coverThumb.setText("Cover");
    }

    void chooseExport() {
        if (busy) return;
        Draft draft = snapshot();
        try {
            draft.exportLanguage();
        } catch (IOException error) {
            report("Book needs attention", message(error));
            return;
        }
        var chooser = chooser("Export EPUB 3.3", "EPUB book", "epub");
        chooser.setSelectedFile(new File(shortField(draft.title().strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"), 60) + ".epub"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        lastFolder = chooser.getCurrentDirectory().toPath();
        Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub")) {
            destination = destination.resolveSibling(destination.getFileName() + ".epub");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && JOptionPane.showConfirmDialog(this,
                "Replace the existing file?", "Export EPUB", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        exportTo(destination);
    }

    void exportTo(Path destination) {
        if (busy) return;
        Draft draft = snapshot();
        runTask("Exporting EPUB 3.3…", () -> {
            draft.export(destination);
            return destination;
        }, path -> {
            try {
                status.setText("Exported " + path.getFileName() + " (" + EpubDoctor.formatBytes(Files.size(path)) + ")");
            } catch (IOException ignored) {
                status.setText("Exported " + path.getFileName());
            }
        });
    }

    void chooseExportHtml() {
        if (busy) return;
        Draft draft = snapshot();
        try { draft.exportLanguage(); }
        catch (IOException error) { report("Book needs attention", message(error)); return; }
        var chooser = chooser("Export Self-Contained HTML Reader", "HTML document", "html");
        chooser.setSelectedFile(new File(shortField(draft.title().strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"), 60) + ".html"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dest = chooser.getSelectedFile().toPath();
        if (!dest.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")) {
            dest = dest.resolveSibling(dest.getFileName() + ".html");
        }
        Path finalDest = dest;
        runTask("Exporting HTML Web Reader…", () -> {
            draft.exportHtml(finalDest);
            return finalDest;
        }, p -> status.setText("Exported HTML reader: " + p.getFileName()));
    }

    void chooseExportMarkdownFolder() {
        if (busy) return;
        Draft draft = snapshot();
        if (draft.chapters().isEmpty()) { report("Export Markdown", "Add at least one chapter first."); return; }
        var chooser = new JFileChooser(lastFolder == null ? null : lastFolder.toFile());
        chooser.setDialogTitle("Export Manuscript as Markdown Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dir = chooser.getSelectedFile().toPath();
        runTask("Exporting Markdown folder…", () -> {
            BookFiles.exportMarkdownFolder(draft, dir);
            return dir;
        }, p -> status.setText("Exported Markdown folder to " + p.getFileName()));
    }

    private JFileChooser chooser(String label, String description, String... types) {
        var chooser = new JFileChooser(lastFolder == null ? null : lastFolder.toFile());
        chooser.setDialogTitle(label);
        chooser.setFileFilter(new FileNameExtensionFilter(description, types));
        return chooser;
    }

    private void repaintChapterList() { list.repaint(); }

    private void refreshStats() {
        int totalWords = 0;
        for (int i = 0; i < chapters.size(); i++) totalWords += Manuscript.wordCount(chapters.get(i).body());
        var sel = list.getSelectedValue();
        int chWords = sel == null ? 0 : Manuscript.wordCount(sel.body());
        int mins = Math.max(1, (int) Math.round(totalWords / 250.0));
        String time = totalWords == 0 ? "0 min" : (mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + " min");
        statsLabel.setText(String.format("Chapter: %,d words  ·  Book: %,d words (~%s)", chWords, totalWords, time));
    }

    private void refreshControls() {
        int selection = list.getSelectedIndex();
        count.setText("Chapters (" + chapters.size() + ")");
        list.setEnabled(!busy);
        openBookBtn.setEnabled(!busy);
        addChaptersBtn.setEnabled(!busy);
        pasteBtn.setEnabled(!busy);
        designBtn.setEnabled(!busy);
        doctorBtn.setEnabled(!busy);
        moreMetaBtn.setEnabled(!busy);
        designCoverBtn.setEnabled(!busy);
        coverThumb.setEnabled(!busy);
        newChapterBtn.setEnabled(!busy);
        up.setEnabled(!busy && selection > 0);
        down.setEnabled(!busy && selection >= 0 && selection + 1 < chapters.size());
        delete.setEnabled(!busy && selection >= 0);
        boolean ready = !chapters.isEmpty() && !title.getText().isBlank();
        exportBtn.setEnabled(!busy && ready);
        exportBtn.setToolTipText(ready ? "Export valid EPUB 3.3 book (Ctrl+E)" : "Add a book title and at least one chapter to export");
        title.setEditable(!busy);
        author.setEditable(!busy);
        language.setEditable(!busy);
        chapterTitle.setEditable(!busy);
        body.setEditable(!busy);
        markdown.setEnabled(!busy);
        hideHeading.setEnabled(!busy);
        roleBox.setEnabled(!busy);
    }

    private void changed() {
        dirty = true;
        changes++;
        if (saveEnabled) autosave.restart();
        refreshControls();
    }

    Draft snapshot() {
        var meta = new Epub.Metadata(
            extraMeta.id(), title.getText(), extraMeta.subtitle(), author.getText(), language.getText(),
            extraMeta.description(), extraMeta.publisher(), extraMeta.subjects(),
            extraMeta.series(), extraMeta.seriesIndex(), extraMeta.rights(), extraMeta.date());
        return new Draft(meta, List.copyOf(Collections.list(chapters.elements())), cover, bookOptions);
    }

    void saveNow() {
        save(false, () -> status.setText("Draft saved."));
    }

    private void save(boolean closing, Runnable done) {
        if (!saveEnabled || !dirty) {
            done.run();
            return;
        }
        Draft draft = snapshot();
        long revision = changes;
        storage.submit(() -> {
            try {
                draft.save(draftPath);
                SwingUtilities.invokeLater(() -> {
                    if (revision == changes) dirty = false;
                    done.run();
                });
            } catch (IOException error) {
                SwingUtilities.invokeLater(() -> {
                    status.setText("Draft could not be saved · " + message(error));
                    if (closing && JOptionPane.showConfirmDialog(this, "Close without saving the latest changes?",
                            "Draft could not be saved", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                        done.run();
                    } else if (closing) {
                        var window = SwingUtilities.getWindowAncestor(this);
                        if (window != null) window.setEnabled(true);
                        busy = false;
                        this.closing = false;
                        refreshControls();
                    }
                });
            }
        });
    }

    void close(Runnable done) {
        if (closing) return;
        if (busy) {
            pendingClose = done;
            status.setText("Finishing the current operation before closing…");
            return;
        }
        closing = true;
        busy = true;
        autosave.stop();
        previewDelay.stop();
        var window = SwingUtilities.getWindowAncestor(this);
        if (window != null) window.setEnabled(false);
        save(true, () -> {
            storage.shutdown();
            done.run();
        });
    }

    boolean isBusy() { return busy; }

    void refreshThemeColors() {
        SwingUtilities.updateComponentTreeUI(this);
        setBackground(UiTheme.bg());
        editorial.setBackground(UiTheme.bg());
        if (wordmarkLabel != null) wordmarkLabel.setForeground(UiTheme.ink());
        list.setBackground(UiTheme.surface());
        editor.setBackground(UiTheme.surface());
        if (previewTabPanel != null) previewTabPanel.setBackground(UiTheme.surface());
        if (formatToolbar != null) formatToolbar.setBackground(UiTheme.subtle());
        findBar.setBackground(UiTheme.subtle());
        findCase.setForeground(UiTheme.ink());
        findRegex.setForeground(UiTheme.ink());
        body.setBackground(UiTheme.editorBg());
        body.setForeground(UiTheme.ink());
        body.setCaretColor(UiTheme.ink());
        body.setBorder(new EmptyBorder(12, 16, 12, 16));
        livePreview.setBackground(UiTheme.isDark() ? new Color(0x1E2227) : new Color(0xFFFDF8));
        livePreview.setBorder(null);
        preview.setBackground(UiTheme.isDark() ? new Color(0x1E2227) : new Color(0xFFFDF8));
        preview.setBorder(null);
        for (JScrollPane sp : List.of(listScroll, bodyScroll, livePreviewScroll, previewScroll)) {
            UiTheme.refreshScrollPane(sp);
        }
        UiTheme.styleField(title);
        UiTheme.styleField(author);
        UiTheme.styleField(language);
        UiTheme.styleField(chapterTitle);
        UiTheme.styleField(findField);
        UiTheme.styleField(replaceField);
        roleBox.setBackground(UiTheme.surface());
        roleBox.setForeground(UiTheme.ink());
        UiTheme.styleTabbedPane(tabs);
        UiTheme.styleSplitPane(mainSplit);
        UiTheme.styleSplitPane(editSplit);
        markdown.setForeground(UiTheme.ink());
        hideHeading.setForeground(UiTheme.ink());
        count.setForeground(UiTheme.ink());
        status.setForeground(UiTheme.muted());
        statsLabel.setForeground(UiTheme.muted());
        refreshCoverThumbnail();
        refreshPreview();
        revalidate();
        repaint();
    }

    private static final class ChapterCellRenderer extends JPanel implements ListCellRenderer<Epub.Chapter> {
        private final JLabel titleLbl = new JLabel();
        private final JLabel metaLbl = new JLabel();

        ChapterCellRenderer() {
            super(new BorderLayout(8, 0));
            setOpaque(true);
            setBorder(new EmptyBorder(6, 10, 6, 10));
            titleLbl.putClientProperty("html.disable", Boolean.TRUE);
            metaLbl.setHorizontalAlignment(SwingConstants.RIGHT);
            add(titleLbl, BorderLayout.CENTER);
            add(metaLbl, BorderLayout.EAST);
        }

        @Override public Component getListCellRendererComponent(JList<? extends Epub.Chapter> list, Epub.Chapter chapter,
                                                                int index, boolean selected, boolean focused) {
            setBackground(selected ? UiTheme.selectionBg() : UiTheme.surface());
            String prefix = switch (chapter.role()) {
                case PART -> "\u00A7 ";
                case FRONTMATTER -> "\u25C7 ";
                case BACKMATTER -> "\u25C8 ";
                case PLATE -> "\u25A3 ";
                default -> (index + 1) + ". ";
            };
            titleLbl.setText(prefix + chapter.title());
            titleLbl.setFont(UiTheme.uiFont(chapter.role() == Epub.ChapterRole.PART ? Font.BOLD : Font.PLAIN, 13f));
            titleLbl.setForeground(selected ? UiTheme.selectionFg() : UiTheme.ink());

            int words = Manuscript.wordCount(chapter.body());
            String suffix = (chapter.images().isEmpty() ? "" : "\u25A3 ") + (words > 0 ? words + "w" : "");
            metaLbl.setText(suffix);
            metaLbl.setFont(UiTheme.uiFont(Font.PLAIN, 11f));
            metaLbl.setForeground(selected ? UiTheme.selectionFg() : UiTheme.muted());
            return this;
        }

        @Override public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            doLayout();
        }
    }

    private <T> void runTask(String label, Callable<T> work, Consumer<T> result) {
        busy = true;
        status.setText(label);
        refreshControls();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                busy = false;
                refreshControls();
                try {
                    result.accept(get());
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText(message(cause));
                    report("Could not complete the operation", message(cause));
                } finally {
                    if (pendingClose != null) {
                        Runnable next = pendingClose;
                        pendingClose = null;
                        close(next);
                    }
                }
            }
        }.execute();
    }

    private void report(String heading, String text) {
        status.setToolTipText(text);
        if (GraphicsEnvironment.isHeadless()) return;
        var msgArea = new JTextArea(text, Math.min(10, Math.max(2, text.split("\n").length)), 46);
        msgArea.setEditable(false);
        msgArea.setLineWrap(true);
        msgArea.setWrapStyleWord(true);
        msgArea.setBackground(UiTheme.bg());
        msgArea.setForeground(UiTheme.ink());
        JOptionPane.showMessageDialog(this, UiTheme.scrollPane(msgArea), heading, JOptionPane.WARNING_MESSAGE);
    }

    private static String message(Throwable error) {
        var msg = error.getMessage();
        return msg == null || msg.isBlank() ? "The file could not be processed." : msg;
    }

    private void shortcut(String key, int code, int modifiers, Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(code, modifiers), key);
        getActionMap().put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private static void configureBody(JTextArea area, String name) {
        area.setFont(UiTheme.serifFont(Font.PLAIN, 17f));
        area.setBackground(UiTheme.editorBg());
        area.setForeground(UiTheme.ink());
        area.setCaretColor(UiTheme.ink());
        area.setBorder(new EmptyBorder(12, 16, 12, 16));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.getAccessibleContext().setAccessibleName(name);
        UiTheme.limit(area, BookFiles.TEXT_LIMIT);
    }

    private static void watch(javax.swing.text.JTextComponent field, Runnable change) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { change.run(); }
            @Override public void removeUpdate(DocumentEvent event) { change.run(); }
            @Override public void changedUpdate(DocumentEvent event) { change.run(); }
        });
    }
}
