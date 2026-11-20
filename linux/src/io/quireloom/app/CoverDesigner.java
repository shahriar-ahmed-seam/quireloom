package io.quireloom.app;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Built-in Book Cover Designer Studio!
 * Generates bookstore-grade 1600x2560 high-res EPUB covers procedurally or over a custom background photograph.
 */
final class CoverDesigner {
    enum Template {
        CLASSIC("Classic Literary"),
        MINIMAL("Minimalist Modern"),
        SWISS("Swiss Typographic"),
        FOLIO("Split Folio"),
        NOCTURNE("Nocturne Sky"),
        PHOTO("Photo Overlay");

        final String label;
        Template(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    record Palette(String name, Color primary, Color secondary, Color accent, Color text) {
        @Override public String toString() { return name; }
    }

    static final List<Palette> PALETTES = List.of(
        new Palette("Forest & Parchment", new Color(0x1E4035), new Color(0x132B23), new Color(0xD4AF6A), new Color(0xF7F2E7)),
        new Palette("Midnight & Gold", new Color(0x141B2D), new Color(0x0B0F1A), new Color(0xE0B45C), new Color(0xF5F3EE)),
        new Palette("Warm Cream & Ink", new Color(0xF4EFE4), new Color(0xE6DEC9), new Color(0x285648), new Color(0x1D2822)),
        new Palette("Terracotta & Sand", new Color(0x8E3B2E), new Color(0x5B2118), new Color(0xF0C987), new Color(0xFAF4EC)),
        new Palette("Deep Plum & Silver", new Color(0x37223D), new Color(0x1E1124), new Color(0xC9B8D9), new Color(0xF7F3FA)),
        new Palette("Ocean Slate & Coral", new Color(0x1F3647), new Color(0x11202B), new Color(0xE87A5D), new Color(0xF4F7F9)),
        new Palette("Obsidian & Crimson", new Color(0x18181B), new Color(0x09090B), new Color(0xC83E37), new Color(0xFAFAFA)),
        new Palette("Sage & Espresso", new Color(0xD8E2D8), new Color(0xBCCBBB), new Color(0x3D2E24), new Color(0x231B16))
    );

    record Spec(
        Template template,
        Palette palette,
        String title,
        String subtitle,
        String author,
        String seriesBadge,
        String publisher,
        boolean serifTitle,
        BufferedImage backgroundPhoto
    ) {}

    static BufferedImage render(Spec spec, int width, int height) {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        Palette pal = spec.palette();
        // Base gradient background.
        g.setPaint(new GradientPaint(0, 0, pal.primary(), width * 0.4f, height, pal.secondary()));
        g.fillRect(0, 0, width, height);

        if (spec.template() == Template.PHOTO && spec.backgroundPhoto() != null) {
            drawCoverPhoto(g, spec.backgroundPhoto(), width, height);
            // Smooth editorial dark scrim top & bottom for legibility.
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 175), 0, height * 0.45f, new Color(0, 0, 0, 35)));
            g.fillRect(0, 0, width, (int) (height * 0.5));
            g.setPaint(new GradientPaint(0, height * 0.45f, new Color(0, 0, 0, 30), 0, height, new Color(0, 0, 0, 205)));
            g.fillRect(0, (int) (height * 0.45), width, (int) (height * 0.56));
        }

        Color textColor = (spec.template() == Template.PHOTO && spec.backgroundPhoto() != null) ? Color.WHITE : pal.text();
        Color accentColor = pal.accent();
        String fontFamily = spec.serifTitle() ? Font.SERIF : Font.SANS_SERIF;
        int pad = Math.round(width * 0.10f);
        int textWidth = width - pad * 2;

        switch (spec.template()) {
            case CLASSIC -> {
                // Double editorial frame + diamond ornament.
                g.setColor(withAlpha(accentColor, 180));
                g.setStroke(new BasicStroke(Math.max(2f, width * 0.004f)));
                int m1 = Math.round(width * 0.055f);
                int m2 = Math.round(width * 0.072f);
                g.drawRect(m1, m1, width - m1 * 2, height - m1 * 2);
                g.setStroke(new BasicStroke(Math.max(1f, width * 0.0018f)));
                g.drawRect(m2, m2, width - m2 * 2, height - m2 * 2);
                drawSeriesBadge(g, spec.seriesBadge(), width, Math.round(height * 0.14f), accentColor, true);
                int afterTitle = drawWrappedText(g, spec.title(), new Font(fontFamily, Font.BOLD, Math.round(width * 0.095f)),
                    textColor, pad, Math.round(height * 0.25f), textWidth, true, 1.18f);
                drawOrnament(g, width / 2, afterTitle + Math.round(height * 0.045f), Math.round(width * 0.18f), accentColor);
                drawWrappedText(g, spec.subtitle(), new Font(Font.SERIF, Font.ITALIC, Math.round(width * 0.042f)),
                    withAlpha(textColor, 220), pad, afterTitle + Math.round(height * 0.09f), textWidth, true, 1.3f);
                drawFooter(g, spec.author(), spec.publisher(), width, height, textColor, accentColor, true);
            }
            case MINIMAL -> {
                // Clean left accent bar + asymmetric bold typography.
                g.setColor(accentColor);
                g.fillRect(0, 0, Math.round(width * 0.045f), height);
                int left = Math.round(width * 0.13f);
                int avail = width - left - pad;
                drawSeriesBadgeLeft(g, spec.seriesBadge(), left, Math.round(height * 0.14f), accentColor, width);
                int after = drawWrappedText(g, spec.title(), new Font(fontFamily, Font.BOLD, Math.round(width * 0.105f)),
                    textColor, left, Math.round(height * 0.23f), avail, false, 1.12f);
                g.setColor(accentColor);
                g.fillRect(left, after + Math.round(height * 0.025f), Math.round(width * 0.18f), Math.max(3, Math.round(height * 0.005f)));
                drawWrappedText(g, spec.subtitle(), new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(width * 0.042f)),
                    withAlpha(textColor, 210), left, after + Math.round(height * 0.065f), avail, false, 1.35f);
                drawFooterLeft(g, spec.author(), spec.publisher(), left, width, height, textColor, accentColor);
            }
            case SWISS -> {
                // Bold modernist geometric accent bands.
                g.setColor(accentColor);
                g.fillRect(0, Math.round(height * 0.56f), width, Math.round(height * 0.032f));
                g.setColor(withAlpha(accentColor, 55));
                g.fill(new Ellipse2D.Double(width * 0.52, height * 0.50, width * 0.65, width * 0.65));
                g.setColor(withAlpha(textColor, 22));
                g.fillRect(pad, Math.round(height * 0.07f), textWidth, Math.max(2, height / 400));
                drawSeriesBadgeLeft(g, spec.seriesBadge(), pad, Math.round(height * 0.115f), accentColor, width);
                int after = drawWrappedText(g, spec.title().toUpperCase(), new Font(Font.SANS_SERIF, Font.BOLD, Math.round(width * 0.095f)),
                    textColor, pad, Math.round(height * 0.18f), textWidth, false, 1.08f);
                drawWrappedText(g, spec.subtitle(), new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(width * 0.040f)),
                    withAlpha(textColor, 210), pad, after + Math.round(height * 0.04f), textWidth, false, 1.3f);
                drawFooterLeft(g, spec.author(), spec.publisher(), pad, width, height, textColor, accentColor);
            }
            case FOLIO -> {
                // Two-tone split card cover.
                int splitY = Math.round(height * 0.62f);
                g.setColor(pal.secondary());
                g.fillRect(0, splitY, width, height - splitY);
                g.setColor(accentColor);
                g.fillRect(0, splitY - Math.max(4, height / 220), width, Math.max(8, height / 110));
                // Subtle geometric emblem on upper half.
                g.setColor(withAlpha(accentColor, 36));
                int r = Math.round(width * 0.28f);
                g.fill(new RoundRectangle2D.Double((width - r) / 2.0, height * 0.10, r, r, r * 0.2, r * 0.2));
                drawSeriesBadge(g, spec.seriesBadge(), width, Math.round(height * 0.14f), accentColor, true);
                int after = drawWrappedText(g, spec.title(), new Font(fontFamily, Font.BOLD, Math.round(width * 0.095f)),
                    textColor, pad, Math.round(height * 0.24f), textWidth, true, 1.15f);
                drawWrappedText(g, spec.subtitle(), new Font(Font.SERIF, Font.ITALIC, Math.round(width * 0.040f)),
                    withAlpha(textColor, 210), pad, after + Math.round(height * 0.04f), textWidth, true, 1.3f);
                drawFooter(g, spec.author(), spec.publisher(), width, height, textColor, accentColor, true);
            }
            case NOCTURNE -> {
                // Celestial arcs & golden compass lines.
                g.setColor(withAlpha(accentColor, 70));
                g.setStroke(new BasicStroke(Math.max(1.5f, width * 0.0022f)));
                int cx = width / 2;
                int cy = Math.round(height * 0.60f);
                for (double factor : new double[]{0.22, 0.34, 0.48}) {
                    int d = (int) Math.round(width * factor * 2);
                    g.draw(new Ellipse2D.Double(cx - d / 2.0, cy - d / 2.0, d, d));
                }
                g.setColor(withAlpha(accentColor, 160));
                int starR = Math.round(width * 0.035f);
                drawStar(g, cx, cy, starR, starR * 0.35);
                drawSeriesBadge(g, spec.seriesBadge(), width, Math.round(height * 0.12f), accentColor, true);
                int after = drawWrappedText(g, spec.title(), new Font(fontFamily, Font.BOLD, Math.round(width * 0.095f)),
                    textColor, pad, Math.round(height * 0.20f), textWidth, true, 1.16f);
                drawWrappedText(g, spec.subtitle(), new Font(Font.SERIF, Font.ITALIC, Math.round(width * 0.041f)),
                    withAlpha(textColor, 220), pad, after + Math.round(height * 0.035f), textWidth, true, 1.3f);
                drawFooter(g, spec.author(), spec.publisher(), width, height, textColor, accentColor, true);
            }
            case PHOTO -> {
                drawSeriesBadge(g, spec.seriesBadge(), width, Math.round(height * 0.11f), accentColor, true);
                int after = drawWrappedText(g, spec.title(), new Font(fontFamily, Font.BOLD, Math.round(width * 0.10f)),
                    textColor, pad, Math.round(height * 0.18f), textWidth, true, 1.15f);
                drawWrappedText(g, spec.subtitle(), new Font(Font.SERIF, Font.ITALIC, Math.round(width * 0.042f)),
                    withAlpha(textColor, 235), pad, after + Math.round(height * 0.035f), textWidth, true, 1.3f);
                drawFooter(g, spec.author(), spec.publisher(), width, height, textColor, accentColor, true);
            }
        }
        g.dispose();
        return img;
    }

    static Epub.Image renderEpubImage(Spec spec) throws IOException {
        BufferedImage hiRes = render(spec, 1600, 2560);
        var out = new ByteArrayOutputStream();
        ImageIO.write(hiRes, "png", out);
        return new Epub.Image("image/png", out.toByteArray());
    }

    private static void drawCoverPhoto(Graphics2D g, BufferedImage photo, int w, int h) {
        double scale = Math.max((double) w / photo.getWidth(), (double) h / photo.getHeight());
        int dw = (int) Math.round(photo.getWidth() * scale);
        int dh = (int) Math.round(photo.getHeight() * scale);
        g.drawImage(photo, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
    }

    private static void drawSeriesBadge(Graphics2D g, String badge, int width, int y, Color color, boolean centered) {
        if (badge == null || badge.isBlank()) return;
        Font f = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, Math.round(width * 0.028f)));
        g.setFont(f);
        g.setColor(color);
        String text = spacedCaps(badge.strip());
        FontMetrics fm = g.getFontMetrics();
        int x = centered ? (width - fm.stringWidth(text)) / 2 : Math.round(width * 0.10f);
        g.drawString(text, Math.max(12, x), y);
    }

    private static void drawSeriesBadgeLeft(Graphics2D g, String badge, int left, int y, Color color, int width) {
        if (badge == null || badge.isBlank()) return;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, Math.round(width * 0.028f))));
        g.setColor(color);
        g.drawString(spacedCaps(badge.strip()), left, y);
    }

    private static void drawOrnament(Graphics2D g, int cx, int cy, int halfWidth, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(1.5f, halfWidth * 0.015f)));
        g.drawLine(cx - halfWidth, cy, cx - halfWidth / 5, cy);
        g.drawLine(cx + halfWidth / 5, cy, cx + halfWidth, cy);
        int r = Math.max(4, halfWidth / 14);
        var diamond = new Path2D.Double();
        diamond.moveTo(cx, cy - r);
        diamond.lineTo(cx + r, cy);
        diamond.lineTo(cx, cy + r);
        diamond.lineTo(cx - r, cy);
        diamond.closePath();
        g.fill(diamond);
    }

    private static void drawStar(Graphics2D g, int cx, int cy, double outer, double inner) {
        var path = new Path2D.Double();
        for (int i = 0; i < 8; i++) {
            double r = (i % 2 == 0) ? outer : inner;
            double angle = Math.PI / 4 * i - Math.PI / 2;
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();
        g.fill(path);
    }

    private static void drawFooter(Graphics2D g, String author, String publisher, int width, int height,
                                   Color textColor, Color accentColor, boolean centered) {
        int pad = Math.round(width * 0.10f);
        if (author != null && !author.isBlank()) {
            drawWrappedText(g, author.strip(), new Font(Font.SANS_SERIF, Font.BOLD, Math.round(width * 0.044f)),
                textColor, pad, Math.round(height * 0.84f), width - pad * 2, centered, 1.25f);
        }
        if (publisher != null && !publisher.isBlank()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, Math.round(width * 0.025f))));
            g.setColor(withAlpha(accentColor, 220));
            String p = spacedCaps(publisher.strip());
            int x = centered ? (width - g.getFontMetrics().stringWidth(p)) / 2 : pad;
            g.drawString(p, Math.max(pad, x), Math.round(height * 0.92f));
        }
    }

    private static void drawFooterLeft(Graphics2D g, String author, String publisher, int left, int width, int height,
                                       Color textColor, Color accentColor) {
        int avail = width - left - Math.round(width * 0.08f);
        if (author != null && !author.isBlank()) {
            drawWrappedText(g, author.strip(), new Font(Font.SANS_SERIF, Font.BOLD, Math.round(width * 0.046f)),
                textColor, left, Math.round(height * 0.83f), avail, false, 1.25f);
        }
        if (publisher != null && !publisher.isBlank()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, Math.round(width * 0.025f))));
            g.setColor(withAlpha(accentColor, 220));
            g.drawString(spacedCaps(publisher.strip()), left, Math.round(height * 0.92f));
        }
    }

    private static int drawWrappedText(Graphics2D g, String text, Font font, Color color,
                                       int x, int startY, int maxWidth, boolean centered, float lineHeight) {
        if (text == null || text.isBlank()) return startY;
        Font active = font;
        g.setFont(active);
        List<String> lines = wrap(text.strip(), g.getFontMetrics(), maxWidth);
        // Shrink font gently if title is very long (> 5 lines).
        while (lines.size() > 5 && active.getSize() > 14) {
            active = active.deriveFont(active.getSize2D() * 0.88f);
            g.setFont(active);
            lines = wrap(text.strip(), g.getFontMetrics(), maxWidth);
        }
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        int step = Math.round(fm.getHeight() * lineHeight);
        int y = startY;
        for (String line : lines) {
            int drawX = centered ? x + (maxWidth - fm.stringWidth(line)) / 2 : x;
            g.drawString(line, drawX, y);
            y += step;
        }
        return y;
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (current.isEmpty()) {
                current.append(word);
            } else if (fm.stringWidth(current + " " + word) <= maxWidth) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private static String spacedCaps(String s) {
        String up = s.toUpperCase();
        if (up.length() > 28) return up;
        var sb = new StringBuilder(up.length() * 2);
        for (int i = 0; i < up.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(up.charAt(i));
        }
        return sb.toString();
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /**
     * Opens the interactive Cover Designer Studio dialog.
     */
    static void showDialog(Component parent, Epub.Metadata meta, Consumer<Epub.Image> onApply) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Cover Designer Studio",
            Dialog.ModalityType.APPLICATION_MODAL);
        var root = new JPanel(new BorderLayout(16, 12));
        root.setBackground(UiTheme.bg());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));

        var templateBox = new JComboBox<>(Template.values());
        var paletteBox = new JComboBox<>(PALETTES.toArray(Palette[]::new));
        var titleField = UiTheme.textField("Cover title", 256);
        titleField.setText(meta.title().isBlank() ? "Untitled Book" : meta.title());
        var subtitleField = UiTheme.textField("Cover subtitle", 256);
        subtitleField.setText(meta.subtitle());
        var authorField = UiTheme.textField("Cover author", 256);
        authorField.setText(meta.author());
        var seriesField = UiTheme.textField("Series badge", 128);
        String seriesLabel = meta.series().isBlank() ? ""
            : meta.series() + (meta.seriesIndex().isBlank() ? "" : " · Book " + meta.seriesIndex());
        seriesField.setText(seriesLabel);
        var pubField = UiTheme.textField("Publisher imprint", 128);
        pubField.setText(meta.publisher());
        var serifBox = new JCheckBox("Classic serif typography", true);
        serifBox.setOpaque(false);
        serifBox.setForeground(UiTheme.ink());

        final BufferedImage[] photo = {null};
        var photoStatus = UiTheme.mutedLabel("No background photo selected");

        var previewPanel = new JPanel() {
            BufferedImage previewImg;
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (previewImg == null) return;
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int pad = 14;
                int availW = getWidth() - pad * 2;
                int availH = getHeight() - pad * 2;
                double ratio = Math.min((double) availW / previewImg.getWidth(), (double) availH / previewImg.getHeight());
                int dw = (int) Math.round(previewImg.getWidth() * ratio);
                int dh = (int) Math.round(previewImg.getHeight() * ratio);
                int x = (getWidth() - dw) / 2;
                int y = (getHeight() - dh) / 2;
                // Subtle drop shadow behind book cover preview.
                g2.setColor(new Color(0, 0, 0, 42));
                g2.fillRoundRect(x + 6, y + 8, dw, dh, 8, 8);
                g2.drawImage(previewImg, x, y, dw, dh, null);
                g2.setColor(UiTheme.border());
                g2.drawRect(x, y, dw - 1, dh - 1);
                g2.dispose();
            }
            void update(Spec spec) {
                previewImg = render(spec, 400, 640);
                repaint();
            }
        };
        previewPanel.setOpaque(false);
        previewPanel.setPreferredSize(new Dimension(340, 520));

        Runnable refresh = () -> previewPanel.update(new Spec(
            (Template) templateBox.getSelectedItem(),
            (Palette) paletteBox.getSelectedItem(),
            titleField.getText(),
            subtitleField.getText(),
            authorField.getText(),
            seriesField.getText(),
            pubField.getText(),
            serifBox.isSelected(),
            photo[0]));

        var listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh.run(); }
            public void removeUpdate(DocumentEvent e) { refresh.run(); }
            public void changedUpdate(DocumentEvent e) { refresh.run(); }
        };
        titleField.getDocument().addDocumentListener(listener);
        subtitleField.getDocument().addDocumentListener(listener);
        authorField.getDocument().addDocumentListener(listener);
        seriesField.getDocument().addDocumentListener(listener);
        pubField.getDocument().addDocumentListener(listener);
        templateBox.addActionListener(e -> refresh.run());
        paletteBox.addActionListener(e -> refresh.run());
        serifBox.addActionListener(e -> refresh.run());

        var pickPhoto = UiTheme.secondaryButton("Choose background photo…", () -> {
            var chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("PNG and JPEG images", "png", "jpg", "jpeg"));
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    BufferedImage loaded = ImageIO.read(chooser.getSelectedFile());
                    if (loaded == null) throw new IOException("Unreadable image.");
                    photo[0] = loaded;
                    photoStatus.setText(chooser.getSelectedFile().getName());
                    templateBox.setSelectedItem(Template.PHOTO);
                    refresh.run();
                } catch (IOException error) {
                    JOptionPane.showMessageDialog(dialog, error.getMessage(), "Photo Error", JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        var form = UiTheme.card(new GridBagLayout());
        var c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0;
        c.insets = new Insets(0, 0, 10, 0);
        int row = 0;
        c.gridy = row++; form.add(UiTheme.labeled("Cover template", templateBox), c);
        c.gridy = row++; form.add(UiTheme.labeled("Color palette", paletteBox), c);
        c.gridy = row++; form.add(UiTheme.labeled("Book title", titleField), c);
        c.gridy = row++; form.add(UiTheme.labeled("Subtitle", subtitleField), c);
        c.gridy = row++; form.add(UiTheme.labeled("Author", authorField), c);
        c.gridy = row++; form.add(UiTheme.labeled("Series / edition badge", seriesField), c);
        c.gridy = row++; form.add(UiTheme.labeled("Publisher / imprint", pubField), c);
        c.gridy = row++; form.add(serifBox, c);
        var photoRow = new JPanel(new BorderLayout(8, 0));
        photoRow.setOpaque(false);
        photoRow.add(pickPhoto, BorderLayout.WEST);
        photoRow.add(photoStatus, BorderLayout.CENTER);
        c.gridy = row++; form.add(photoRow, c);
        c.gridy = row; c.weighty = 1; form.add(Box.createGlue(), c);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(UiTheme.secondaryButton("Export PNG…", () -> {
            var chooser = new JFileChooser();
            chooser.setSelectedFile(new File("cover.png"));
            if (chooser.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    var spec = new Spec((Template) templateBox.getSelectedItem(), (Palette) paletteBox.getSelectedItem(),
                        titleField.getText(), subtitleField.getText(), authorField.getText(),
                        seriesField.getText(), pubField.getText(), serifBox.isSelected(), photo[0]);
                    var rendered = renderEpubImage(spec);
                    Files.write(chooser.getSelectedFile().toPath(), rendered.bytes());
                    JOptionPane.showMessageDialog(dialog, "Exported 1600×2560 high-res cover PNG.");
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Export Failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }));
        buttons.add(UiTheme.secondaryButton("Cancel", dialog::dispose));
        buttons.add(UiTheme.primaryButton("Apply Cover to Book", () -> {
            try {
                var spec = new Spec((Template) templateBox.getSelectedItem(), (Palette) paletteBox.getSelectedItem(),
                    titleField.getText(), subtitleField.getText(), authorField.getText(),
                    seriesField.getText(), pubField.getText(), serifBox.isSelected(), photo[0]);
                onApply.accept(renderEpubImage(spec));
                dialog.dispose();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Cover Error", JOptionPane.ERROR_MESSAGE);
            }
        }));

        root.add(previewPanel, BorderLayout.WEST);
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        refresh.run();
        dialog.setSize(780, 610);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
