package io.quireloom.app;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Interactive Book Design & Typography Studio + Full Book Metadata Editor Dialogs.
 */
final class BookDesignDialog {
    private static final String[] SCENE_BREAKS = {
        "Horizontal Rule (Default)", "* * *", "❦", "§", "◆ ◆ ◆", "— ✦ —", "• • •"
    };

    static void showDesignDialog(Component parent, Epub.Options current, Consumer<Epub.Options> onSave) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Book Design & Typography",
            Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(14, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        var themeList = new JList<>(Epub.BookTheme.values());
        themeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        themeList.setSelectedValue(current.theme(), true);
        themeList.setCellRenderer((list, theme, idx, sel, foc) -> {
            var p = new JPanel(new BorderLayout(0, 2));
            p.setBorder(new EmptyBorder(8, 10, 8, 10));
            p.setBackground(sel ? UiTheme.selectionBg() : UiTheme.surface());
            var name = new JLabel(theme.label);
            name.setFont(UiTheme.uiFont(Font.BOLD, 13.5f));
            name.setForeground(sel ? UiTheme.selectionFg() : UiTheme.ink());
            var desc = new JLabel(theme.summary);
            desc.setFont(UiTheme.uiFont(Font.PLAIN, 11.5f));
            desc.setForeground(UiTheme.muted());
            p.add(name, BorderLayout.NORTH);
            p.add(desc, BorderLayout.SOUTH);
            return p;
        });

        var sceneBox = new JComboBox<>(SCENE_BREAKS);
        sceneBox.setEditable(true);
        if (current.sceneBreak().isBlank()) sceneBox.setSelectedIndex(0);
        else sceneBox.setSelectedItem(current.sceneBreak());

        var smartTypo = check("Smart typography (“curly quotes”, en/em dashes, ellipses)", current.smartTypography());
        var dropCaps = check("Drop cap on opening paragraph of each chapter", current.dropCaps());
        var titlePage = check("Auto-generate formatted Title Page in exported EPUB", current.includeTitlePage());
        var copyrightPage = check("Auto-generate Copyright & Colophon Page", current.includeCopyright());
        var includeNcx = check("Include EPUB 2 NCX fallback (for older e-ink readers)", current.includeNcx());

        var cssArea = new JTextArea(current.customCss(), 7, 40);
        cssArea.setFont(UiTheme.monoFont(Font.PLAIN, 12.5f));
        cssArea.setBackground(UiTheme.editorBg());
        cssArea.setForeground(UiTheme.ink());
        cssArea.setCaretColor(UiTheme.ink());
        cssArea.setBorder(new EmptyBorder(8, 8, 8, 8));

        var previewPane = new JEditorPane("text/html", "");
        previewPane.setEditable(false);
        previewPane.setBackground(Color.WHITE);

        Runnable updatePreview = () -> {
            Epub.BookTheme theme = themeList.getSelectedValue();
            if (theme == null) theme = Epub.BookTheme.CLASSIC;
            String sb = selectedSceneBreak(sceneBox);
            var opts = new Epub.Options(theme, sb, smartTypo.isSelected(), dropCaps.isSelected(),
                titlePage.isSelected(), copyrightPage.isSelected(), includeNcx.isSelected(), cssArea.getText());
            var sample = new Epub.Chapter("The Lantern Keeper",
                "\"Listen carefully,\" she whispered -- the storm was rolling in...\n\n---\n\n> Every *page* holds a **world**.",
                true);
            String bodyHtml = Epub.render(sample, opts);
            previewPane.setText("<html><head><style>"
                + "body { background: #fffdf8; color: #202824; font-family: "
                + (theme == Epub.BookTheme.MODERN || theme == Epub.BookTheme.TECHNICAL ? "sans-serif" : "serif")
                + "; margin: 16px; font-size: 13pt; }"
                + "h1 { font-size: 18pt; margin-bottom: 10px; "
                + (theme == Epub.BookTheme.CLASSIC || theme == Epub.BookTheme.LITERARY || theme == Epub.BookTheme.POETRY ? "text-align: center;" : "")
                + "} blockquote { margin: 10px 18px; font-style: italic; color: #4a5550; }"
                + ".scene-break { text-align: center; margin: 12px 0; }"
                + "</style></head><body>" + bodyHtml + "</body></html>");
            previewPane.setCaretPosition(0);
        };
        themeList.addListSelectionListener(e -> updatePreview.run());
        sceneBox.addActionListener(e -> updatePreview.run());
        smartTypo.addActionListener(e -> updatePreview.run());
        dropCaps.addActionListener(e -> updatePreview.run());

        var left = new JPanel(new BorderLayout(0, 8));
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(280, 440));
        left.add(UiTheme.sectionTitle("Typography Themes"), BorderLayout.NORTH);
        left.add(UiTheme.scrollPane(themeList), BorderLayout.CENTER);

        var right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        var toggles = UiTheme.card(new GridLayout(0, 1, 0, 5));
        toggles.add(UiTheme.labeled("Scene break ornament (for --- lines)", sceneBox));
        toggles.add(smartTypo);
        toggles.add(dropCaps);
        toggles.add(titlePage);
        toggles.add(copyrightPage);
        toggles.add(includeNcx);

        var bottomTabs = new JTabbedPane();
        bottomTabs.addTab("Style Preview", UiTheme.scrollPane(previewPane));
        bottomTabs.addTab("Custom CSS", UiTheme.scrollPane(cssArea));
        right.add(toggles, BorderLayout.NORTH);
        right.add(bottomTabs, BorderLayout.CENTER);

        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(UiTheme.secondaryButton("Cancel", dialog::dispose));
        actions.add(UiTheme.primaryButton("Apply Book Design", () -> {
            Epub.BookTheme theme = themeList.getSelectedValue();
            if (theme == null) theme = Epub.BookTheme.CLASSIC;
            onSave.accept(new Epub.Options(theme, selectedSceneBreak(sceneBox),
                smartTypo.isSelected(), dropCaps.isSelected(),
                titlePage.isSelected(), copyrightPage.isSelected(),
                includeNcx.isSelected(), cssArea.getText()));
            dialog.dispose();
        }));

        root.add(left, BorderLayout.WEST);
        root.add(right, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        updatePreview.run();
        dialog.setSize(820, 580);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    static void showMetadataDialog(Component parent, Epub.Metadata meta, Consumer<Epub.Metadata> onSave) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Book Metadata & Catalog Details",
            Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        var subtitle = UiTheme.textField("Subtitle", 512); subtitle.setText(meta.subtitle());
        var series = UiTheme.textField("Series name", 256); series.setText(meta.series());
        var seriesIdx = UiTheme.textField("Book number", 32); seriesIdx.setText(meta.seriesIndex());
        var publisher = UiTheme.textField("Publisher", 256); publisher.setText(meta.publisher());
        var subjects = UiTheme.textField("Subjects / Genres", 512); subjects.setText(meta.subjects());
        var rights = UiTheme.textField("Rights / License", 512); rights.setText(meta.rights());
        var date = UiTheme.textField("Publication date", 64); date.setText(meta.date());
        var ident = UiTheme.textField("Identifier (UUID or ISBN)", 256); ident.setText(meta.id());
        var desc = new JTextArea(meta.description(), 5, 40);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setBackground(UiTheme.editorBg());
        desc.setForeground(UiTheme.ink());
        desc.setCaretColor(UiTheme.ink());
        desc.setFont(UiTheme.uiFont(Font.PLAIN, 13f));
        desc.setBorder(new EmptyBorder(6, 8, 6, 8));

        var form = UiTheme.card(new GridBagLayout());
        var g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 10, 10);
        g.weightx = 0.7; g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        form.add(UiTheme.labeled("Subtitle", subtitle), g);

        g.gridwidth = 1; g.gridy = 1; g.gridx = 0; g.weightx = 0.72;
        form.add(UiTheme.labeled("Series / Collection name", series), g);
        g.gridx = 1; g.weightx = 0.28; g.insets = new Insets(0, 0, 10, 0);
        form.add(UiTheme.labeled("Book # in series", seriesIdx), g);

        g.insets = new Insets(0, 0, 10, 10);
        g.gridy = 2; g.gridx = 0; g.weightx = 0.65;
        form.add(UiTheme.labeled("Publisher / Imprint", publisher), g);
        g.gridx = 1; g.weightx = 0.35; g.insets = new Insets(0, 0, 10, 0);
        form.add(UiTheme.labeled("Publication date (YYYY-MM-DD)", date), g);

        g.gridwidth = 2; g.gridx = 0; g.insets = new Insets(0, 0, 10, 0);
        g.gridy = 3; form.add(UiTheme.labeled("Subjects / Tags (comma separated)", subjects), g);
        g.gridy = 4; form.add(UiTheme.labeled("Rights / Copyright statement", rights), g);
        g.gridy = 5; form.add(UiTheme.labeled("Book Identifier (urn:uuid:... or ISBN)", ident), g);
        g.gridy = 6; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        form.add(UiTheme.labeled("Book description / blurb", UiTheme.scrollPane(desc)), g);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(UiTheme.secondaryButton("Cancel", dialog::dispose));
        buttons.add(UiTheme.primaryButton("Save Metadata", () -> {
            String id = ident.getText().isBlank() ? meta.id() : ident.getText().strip();
            onSave.accept(new Epub.Metadata(id, meta.title(), subtitle.getText().strip(),
                meta.author(), meta.language(), desc.getText().strip(), publisher.getText().strip(),
                subjects.getText().strip(), series.getText().strip(), seriesIdx.getText().strip(),
                rights.getText().strip(), date.getText().strip()));
            dialog.dispose();
        }));

        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(620, 560);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static String selectedSceneBreak(JComboBox<String> box) {
        Object val = box.getSelectedItem();
        if (val == null) return "";
        String s = val.toString().strip();
        return s.equals(SCENE_BREAKS[0]) ? "" : s;
    }

    private static JCheckBox check(String label, boolean selected) {
        var cb = new JCheckBox(label, selected);
        cb.setOpaque(false);
        cb.setForeground(UiTheme.ink());
        cb.setFont(UiTheme.uiFont(Font.PLAIN, 13f));
        return cb;
    }
}
