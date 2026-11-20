package io.quireloom.app;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

final class PublishingMenu extends JMenuBar {
    PublishingMenu(JFrame frame, Workspace workspace) {
        // Edit menu
        var edit = new JMenu("Edit");
        addItem(edit, "Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), workspace::undoText);
        addItem(edit, "Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), workspace::redoText);
        addItem(edit, "Undo Chapter Structure Change", null, workspace::undoStructure);
        edit.addSeparator();
        addItem(edit, "Find in Chapter…", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), () -> workspace.showFindBar(false));
        addItem(edit, "Replace in Chapter…", KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), () -> workspace.showFindBar(true));
        addItem(edit, "Find & Replace Across Book…", KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), workspace::replaceAcrossChapters);
        edit.addSeparator();
        addItem(edit, "Clean & Polish Current Chapter", null, workspace::polishCurrentChapter);
        add(edit);

        // Chapter menu
        var chapter = new JMenu("Chapter");
        addItem(chapter, "New Chapter", null, workspace::addBlank);
        addItem(chapter, "Duplicate Chapter", KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), workspace::duplicateChapter);
        addItem(chapter, "Split Chapter at Cursor…", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), workspace::splitChapterAtCursor);
        addItem(chapter, "Merge with Next Chapter…", null, workspace::mergeWithNextChapter);
        addItem(chapter, "Auto-Split Chapter by Headings…", null, workspace::autoSplitSelectedChapter);
        chapter.addSeparator();
        addItem(chapter, "Move Up", null, () -> workspace.move(-1));
        addItem(chapter, "Move Down", null, () -> workspace.move(1));
        chapter.addSeparator();
        addItem(chapter, "Delete Chapter…", null, workspace::delete);
        add(chapter);

        // Images menu
        var images = new JMenu("Images");
        addItem(images, "Insert Illustrations at Cursor…", null, () -> workspace.chooseImages(false));
        addItem(images, "Add Images as Plate Chapters…", null, () -> workspace.chooseImages(true));
        images.addSeparator();
        addItem(images, "Cover Designer Studio…", null, workspace::openCoverStudio);
        addItem(images, "Choose Cover Image…", null, workspace::chooseCover);
        addItem(images, "Remove Cover", null, () -> workspace.setCover(null));
        images.addSeparator();
        addItem(images, "Optimize All Embedded Images", null, workspace::optimizeEmbeddedImages);
        add(images);

        // Design menu
        var design = new JMenu("Design");
        addItem(design, "Book Typography & Themes…", null, workspace::openDesignDialog);
        addItem(design, "Cover Designer Studio…", null, workspace::openCoverStudio);
        addItem(design, "Book Metadata & Series…", null, workspace::openMetadataDialog);
        add(design);

        // Publishing / EPUB Tools menu
        var tools = new JMenu("Publishing");
        addItem(tools, "Omnibus Merger & Builder Studio…", null, () -> MergerDialog.showMerger(frame, false));
        addItem(tools, "Build EPUB from Folder…", null, () -> MergerDialog.showMerger(frame, true));
        addItem(tools, "Split EPUB into Multiple Books…", null, () -> MergerDialog.showSplitter(frame));
        tools.addSeparator();
        addItem(tools, "Edit Original EPUB in Package Studio…", null, () -> PackageStudioFrame.openPrompt(frame));
        tools.addSeparator();
        addItem(tools, "EPUB Doctor — Check Current Book", null, workspace::runDoctor);
        addItem(tools, "EPUB Doctor — Inspect & Auto-Fix Any EPUB…", null, () -> DoctorDialog.inspectExternalEpub(frame));
        addItem(tools, "Manuscript Statistics & Pacing…", null, workspace::showStatistics);
        add(tools);

        // View menu
        var view = new JMenu("View");
        var preview = new JCheckBoxMenuItem("Side-by-Side Live Preview");
        preview.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        preview.addActionListener(e -> workspace.splitPreview(preview.isSelected()));
        view.add(preview);
        addItem(view, "Open Full Book Preview in Browser", null, workspace::previewInBrowser);
        addItem(view, "Focus / Zen Mode", KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), workspace::toggleFocusMode);
        view.addSeparator();
        var darkItem = new JCheckBoxMenuItem("Dark Mode (Midnight Ink)", UiTheme.isDark());
        darkItem.addActionListener(e -> DesktopApp.toggleDarkMode(darkItem.isSelected()));
        view.add(darkItem);
        add(view);
    }

    private static void addItem(JMenu menu, String label, KeyStroke accel, Runnable action) {
        var item = new JMenuItem(label);
        if (accel != null) item.setAccelerator(accel);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }
}
