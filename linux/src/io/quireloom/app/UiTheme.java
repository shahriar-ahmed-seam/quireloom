package io.quireloom.app;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.util.function.Supplier;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;

/**
 * Modern editorial Swing theme with Light ("Warm Paper") and Dark ("Midnight Ink") palettes.
 * Eliminates legacy Metal gradients with crisp antialiased rounded surfaces, slim scrollbars, and cohesive tokens.
 */
final class UiTheme {
    private static boolean dark = Preferences.userRoot().node("io/quireloom/desktop").getBoolean("darkMode", false);

    static boolean isDark() { return dark; }

    static void setDark(boolean enabled) {
        dark = enabled;
        Preferences.userRoot().node("io/quireloom/desktop").putBoolean("darkMode", enabled);
        applyGlobal();
    }

    static Color bg() { return dark ? new Color(0x171A1E) : new Color(0xF6F2EA); }
    static Color surface() { return dark ? new Color(0x1F2429) : new Color(0xFFFDF9); }
    static Color editorBg() { return dark ? new Color(0x1A1E23) : Color.WHITE; }
    static Color subtle() { return dark ? new Color(0x282F36) : new Color(0xECE6DA); }
    static Color hoverBg() { return dark ? new Color(0x313A43) : new Color(0xE3DBCC); }
    static Color border() { return dark ? new Color(0x313942) : new Color(0xDAD2C3); }
    static Color ink() { return dark ? new Color(0xE6EAEF) : new Color(0x1F2C26); }
    static Color muted() { return dark ? new Color(0x909CA6) : new Color(0x637069); }
    static Color accent() { return dark ? new Color(0x378A6C) : new Color(0x235344); }
    static Color accentHover() { return dark ? new Color(0x439F7D) : new Color(0x1A4135); }
    static Color selectionBg() { return dark ? new Color(0x223B32) : new Color(0xD8EAE1); }
    static Color selectionFg() { return dark ? new Color(0xDFF6EC) : new Color(0x153528); }
    static Color danger() { return dark ? new Color(0xD45B52) : new Color(0xA63A32); }
    static Color warn() { return dark ? new Color(0xD99A3B) : new Color(0x9C6515); }
    static Color pass() { return dark ? new Color(0x4BB587) : new Color(0x216E4E); }

    static Font uiFont(int style, float size) {
        return new Font(Font.SANS_SERIF, style, Math.round(size));
    }

    static Font serifFont(int style, float size) {
        return new Font(Font.SERIF, style, Math.round(size));
    }

    static Font monoFont(int style, float size) {
        return new Font(Font.MONOSPACED, style, Math.round(size));
    }

    static void applyGlobal() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {}
        UIManager.put("swing.boldMetal", Boolean.FALSE);
        UIManager.put("Panel.background", bg());
        UIManager.put("Panel.foreground", ink());
        UIManager.put("OptionPane.background", bg());
        UIManager.put("OptionPane.messageForeground", ink());
        UIManager.put("Label.foreground", ink());
        UIManager.put("CheckBox.background", bg());
        UIManager.put("CheckBox.foreground", ink());
        UIManager.put("RadioButton.background", bg());
        UIManager.put("RadioButton.foreground", ink());
        UIManager.put("TextField.background", editorBg());
        UIManager.put("TextField.foreground", ink());
        UIManager.put("TextField.caretForeground", ink());
        UIManager.put("TextArea.background", editorBg());
        UIManager.put("TextArea.foreground", ink());
        UIManager.put("TextArea.caretForeground", ink());
        UIManager.put("ComboBox.background", surface());
        UIManager.put("ComboBox.foreground", ink());
        UIManager.put("ComboBox.selectionBackground", selectionBg());
        UIManager.put("ComboBox.selectionForeground", selectionFg());
        UIManager.put("List.background", surface());
        UIManager.put("List.foreground", ink());
        UIManager.put("List.selectionBackground", selectionBg());
        UIManager.put("List.selectionForeground", selectionFg());
        UIManager.put("Tree.background", surface());
        UIManager.put("Tree.foreground", ink());
        UIManager.put("Tree.textBackground", surface());
        UIManager.put("Tree.textForeground", ink());
        UIManager.put("Tree.selectionBackground", selectionBg());
        UIManager.put("Tree.selectionForeground", selectionFg());
        UIManager.put("Table.background", surface());
        UIManager.put("Table.foreground", ink());
        UIManager.put("Table.gridColor", border());
        UIManager.put("Table.selectionBackground", selectionBg());
        UIManager.put("Table.selectionForeground", selectionFg());
        UIManager.put("TableHeader.background", subtle());
        UIManager.put("TableHeader.foreground", ink());
        UIManager.put("TabbedPane.background", bg());
        UIManager.put("TabbedPane.foreground", ink());
        UIManager.put("TabbedPane.selected", surface());
        UIManager.put("TabbedPane.contentAreaColor", surface());
        UIManager.put("TabbedPane.light", border());
        UIManager.put("TabbedPane.shadow", border());
        UIManager.put("TabbedPane.darkShadow", border());
        UIManager.put("TabbedPane.focus", accent());
        UIManager.put("MenuBar.background", surface());
        UIManager.put("MenuBar.foreground", ink());
        UIManager.put("MenuBar.border", BorderFactory.createMatteBorder(0, 0, 1, 0, border()));
        UIManager.put("Menu.background", surface());
        UIManager.put("Menu.foreground", ink());
        UIManager.put("Menu.selectionBackground", selectionBg());
        UIManager.put("Menu.selectionForeground", selectionFg());
        UIManager.put("MenuItem.background", surface());
        UIManager.put("MenuItem.foreground", ink());
        UIManager.put("MenuItem.selectionBackground", selectionBg());
        UIManager.put("MenuItem.selectionForeground", selectionFg());
        UIManager.put("CheckBoxMenuItem.background", surface());
        UIManager.put("CheckBoxMenuItem.foreground", ink());
        UIManager.put("CheckBoxMenuItem.selectionBackground", selectionBg());
        UIManager.put("CheckBoxMenuItem.selectionForeground", selectionFg());
        UIManager.put("PopupMenu.background", surface());
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(border()));
        UIManager.put("Separator.foreground", border());
        UIManager.put("ScrollPane.background", surface());
        UIManager.put("ScrollPane.border", BorderFactory.createLineBorder(border()));
        UIManager.put("SplitPane.background", bg());
        UIManager.put("Button.background", surface());
        UIManager.put("Button.foreground", ink());
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));
        UIManager.put("ToolTip.background", surface());
        UIManager.put("ToolTip.foreground", ink());
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border()), new EmptyBorder(4, 8, 4, 8)));
        UIManager.put("ProgressBar.foreground", accent());
        UIManager.put("ProgressBar.background", subtle());
    }

    enum ButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER }

    static JButton primaryButton(String text, Runnable action) {
        return styledButton(text, ButtonStyle.PRIMARY, null, action);
    }

    static JButton secondaryButton(String text, Runnable action) {
        return styledButton(text, ButtonStyle.SECONDARY, null, action);
    }

    static JButton dangerButton(String text, Runnable action) {
        return styledButton(text, ButtonStyle.DANGER, null, action);
    }

    static JButton ghostButton(String text, String tooltip, Runnable action) {
        var btn = styledButton(text, ButtonStyle.GHOST, tooltip, action);
        btn.setMargin(new Insets(3, 8, 3, 8));
        btn.setFont(uiFont(Font.PLAIN, 12.5f));
        return btn;
    }

    static JButton styledButton(String text, ButtonStyle style, String tooltip, Runnable action) {
        var button = new FlatButton(text, style);
        if (tooltip != null) button.setToolTipText(tooltip);
        if (action != null) button.addActionListener(e -> action.run());
        return button;
    }

    private static final class FlatButton extends JButton {
        private final ButtonStyle style;
        private boolean hovered;
        private boolean pressed;

        FlatButton(String text, ButtonStyle style) {
            super(text);
            this.style = style;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(uiFont(style == ButtonStyle.PRIMARY ? Font.BOLD : Font.PLAIN, 13f));
            setMargin(style == ButtonStyle.GHOST ? new Insets(3, 7, 3, 7) : new Insets(6, 13, 6, 13));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = style == ButtonStyle.GHOST ? 6 : 8;

            Color fill = null;
            Color stroke = null;
            Color fg = ink();
            if (!isEnabled()) {
                fill = style == ButtonStyle.GHOST ? null : subtle();
                stroke = style == ButtonStyle.GHOST ? null : border();
                fg = muted();
            } else {
                switch (style) {
                    case PRIMARY -> {
                        fill = pressed ? accentHover().darker() : hovered ? accentHover() : accent();
                        fg = Color.WHITE;
                    }
                    case SECONDARY -> {
                        fill = pressed ? hoverBg() : hovered ? subtle() : surface();
                        stroke = border();
                        fg = ink();
                    }
                    case DANGER -> {
                        fill = pressed ? danger().darker() : hovered ? danger() : surface();
                        stroke = hovered ? danger() : border();
                        fg = hovered ? Color.WHITE : danger();
                    }
                    case GHOST -> {
                        fill = pressed ? hoverBg() : hovered ? subtle() : null;
                        fg = ink();
                    }
                }
            }
            if (fill != null) {
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            if (stroke != null) {
                g2.setColor(stroke);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            setForeground(fg);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static JTextField textField(String accessibleName, int maxChars) {
        var field = new JTextField();
        field.getAccessibleContext().setAccessibleName(accessibleName);
        styleField(field);
        if (maxChars > 0) limit(field, maxChars);
        return field;
    }

    static void styleField(JTextField field) {
        field.setBackground(editorBg());
        field.setForeground(ink());
        field.setCaretColor(ink());
        field.setFont(uiFont(Font.PLAIN, 13.5f));
        field.setBorder(new CompoundBorder(new RoundedBorder(UiTheme::border, 6), new EmptyBorder(5, 8, 5, 8)));
    }

    static JScrollPane scrollPane(Component view) {
        var sp = new JScrollPane(view);
        refreshScrollPane(sp);
        return sp;
    }

    static void refreshScrollPane(JScrollPane sp) {
        if (sp == null) return;
        sp.setBorder(new RoundedBorder(UiTheme::border, 6));
        sp.setViewportBorder(null);
        sp.setBackground(surface());
        if (sp.getViewport().getView() != null) {
            sp.getViewport().setBackground(sp.getViewport().getView().getBackground());
        }
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.getHorizontalScrollBar().setUI(new SlimScrollBarUI());
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getHorizontalScrollBar().setUnitIncrement(18);
    }

    static void styleTabbedPane(JTabbedPane tabs) {
        tabs.setOpaque(false);
        tabs.setBackground(bg());
        tabs.setForeground(ink());
        tabs.setFont(uiFont(Font.BOLD, 12.5f));
        tabs.setUI(new BasicTabbedPaneUI() {
            @Override protected void installDefaults() {
                super.installDefaults();
                tabAreaInsets = new Insets(0, 4, 0, 4);
                tabInsets = new Insets(6, 16, 6, 16);
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
                contentBorderInsets = new Insets(1, 1, 1, 1);
            }
            @Override protected void paintTabBackground(Graphics g, int placement, int tabIndex,
                                                        int x, int y, int w, int h, boolean isSelected) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSelected ? surface() : subtle());
                g2.fillRoundRect(x + 1, y + 1, w - 3, h + 4, 8, 8);
                g2.dispose();
            }
            @Override protected void paintTabBorder(Graphics g, int placement, int tabIndex,
                                                    int x, int y, int w, int h, boolean isSelected) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSelected ? accent() : border());
                g2.drawRoundRect(x + 1, y + 1, w - 3, h + 4, 8, 8);
                g2.dispose();
            }
            @Override protected void paintText(Graphics g, int placement, Font font, FontMetrics metrics,
                                               int tabIndex, String title, Rectangle textRect, boolean isSelected) {
                g.setFont(font);
                g.setColor(isSelected ? ink() : muted());
                g.drawString(title, textRect.x, textRect.y + metrics.getAscent());
            }
            @Override protected void paintContentBorder(Graphics g, int placement, int selectedIndex) {
                g.setColor(border());
                int top = calculateTabAreaHeight(placement, runCount, maxTabHeight);
                g.drawRect(0, top, tabPane.getWidth() - 1, tabPane.getHeight() - top - 1);
            }
            @Override protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rects,
                                                         int tabIndex, Rectangle iconRect, Rectangle textRect, boolean isSelected) {}
        });
    }

    static void styleSplitPane(JSplitPane split) {
        split.setOpaque(false);
        split.setBorder(null);
        split.setUI(new BasicSplitPaneUI() {
            @Override public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics g) {
                        g.setColor(bg());
                        g.fillRect(0, 0, getWidth(), getHeight());
                        g.setColor(border());
                        if (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) {
                            int x = getWidth() / 2;
                            g.drawLine(x, 8, x, getHeight() - 8);
                        } else {
                            int y = getHeight() / 2;
                            g.drawLine(8, y, getWidth() - 8, y);
                        }
                    }
                };
            }
        });
    }

    static JPanel card(LayoutManager layout) {
        var p = new JPanel(layout) {
            @Override protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(surface());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.setColor(border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(10, 12, 10, 12));
        return p;
    }

    static JLabel mutedLabel(String text) {
        var l = new JLabel(text);
        l.setForeground(muted());
        l.setFont(uiFont(Font.PLAIN, 12f));
        return l;
    }

    static JLabel sectionTitle(String text) {
        var l = new JLabel(text);
        l.setForeground(ink());
        l.setFont(uiFont(Font.BOLD, 13.5f));
        return l;
    }

    static JPanel labeled(String text, JComponent field) {
        var panel = new JPanel(new BorderLayout(0, 3));
        panel.setOpaque(false);
        var label = new JLabel(text);
        label.setForeground(muted());
        label.setFont(uiFont(Font.BOLD, 11.5f));
        label.setLabelFor(field);
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    static void limit(javax.swing.text.JTextComponent field, int max) {
        ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
                    throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }
            @Override public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
                    throws BadLocationException {
                if (text == null || bypass.getDocument().getLength() - length + text.length() <= max) {
                    super.replace(bypass, offset, length, text, attributes);
                } else {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
        });
    }

    static final class RoundedBorder extends AbstractBorder {
        private final Supplier<Color> color;
        private final int radius;

        RoundedBorder(Color color, int radius) {
            this(() -> color, radius);
        }

        RoundedBorder(Supplier<Color> color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color.get());
            g2.drawRoundRect(x, y, w - 1, h - 1, radius, radius);
            g2.dispose();
        }

        @Override public Insets getBorderInsets(Component c) { return new Insets(1, 1, 1, 1); }
    }

    private static final class SlimScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            trackColor = surface();
            thumbColor = border();
        }
        @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
        private static JButton zeroButton() {
            var b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(c.getParent() != null ? c.getParent().getBackground() : surface());
            g.fillRect(r.x, r.y, r.width, r.height);
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() ? muted() : border());
            int pad = 3;
            g2.fillRoundRect(r.x + pad, r.y + pad, Math.max(4, r.width - pad * 2),
                Math.max(12, r.height - pad * 2), 6, 6);
            g2.dispose();
        }
        @Override public Dimension getPreferredSize(JComponent c) {
            return scrollbar.getOrientation() == Adjustable.VERTICAL ? new Dimension(11, 48) : new Dimension(48, 11);
        }
    }
}
