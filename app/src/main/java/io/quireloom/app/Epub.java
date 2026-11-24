package io.quireloom.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Epub {
    enum ChapterRole {
        CHAPTER("Chapter", "bodymatter"),
        PART("Part divider", "part"),
        FRONTMATTER("Frontmatter", "frontmatter"),
        BACKMATTER("Backmatter", "backmatter"),
        PLATE("Illustration plate", "bodymatter");

        final String label;
        final String epubType;

        ChapterRole(String label, String epubType) {
            this.label = label;
            this.epubType = epubType;
        }

        static ChapterRole fromName(String name) {
            if (name == null || name.isBlank()) return CHAPTER;
            try { return valueOf(name); }
            catch (IllegalArgumentException ignored) { return CHAPTER; }
        }
    }

    enum BookTheme {
        CLASSIC("Classic Book", "Refined serif prose, indented paragraphs, timeless typography", """
            body { margin: 5% 6%; font-family: Georgia, "Liberation Serif", "Times New Roman", serif; line-height: 1.62; color: inherit; }
            h1 { font-size: 1.85em; font-weight: normal; letter-spacing: 0.02em; text-align: center; margin: 1.6em 0 1.2em; line-height: 1.2; }
            h2 { font-size: 1.35em; font-weight: normal; margin: 1.5em 0 0.6em; line-height: 1.25; }
            h3 { font-size: 1.15em; font-style: italic; font-weight: normal; margin: 1.3em 0 0.5em; }
            p { margin: 0 0 0.35em; text-indent: 1.35em; }
            h1 + p, h2 + p, h3 + p, hr + p, .scene-break + p, blockquote + p, figure + p, p.first-para { text-indent: 0; }
            blockquote { margin: 1.1em 1.8em; font-style: italic; border-left: 2px solid #8c8273; padding-left: 1em; }
            blockquote p { text-indent: 0; margin-bottom: 0.6em; }
            """),
        MODERN("Modern Editorial", "Crisp sans-serif headings, clean spacing, refined callouts", """
            body { margin: 5% 6%; font-family: "Liberation Sans", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.65; color: inherit; }
            h1 { font-size: 1.9em; font-weight: 700; letter-spacing: -0.02em; margin: 1.2em 0 0.9em; line-height: 1.15; border-bottom: 1px solid #d4cfc5; padding-bottom: 0.35em; }
            h2 { font-size: 1.35em; font-weight: 600; margin: 1.5em 0 0.5em; }
            h3 { font-size: 1.12em; font-weight: 600; margin: 1.2em 0 0.4em; }
            p { margin: 0 0 0.95em; text-indent: 0; }
            blockquote { margin: 1.1em 0; padding: 0.7em 1.1em; border-left: 3px solid #285648; background: rgba(40, 86, 72, 0.06); }
            blockquote p:last-child { margin-bottom: 0; }
            """),
        LITERARY("Literary Paper", "Centered classical chapters, generous air, bookshop feel", """
            body { margin: 6% 7%; font-family: "Bitstream Charter", Georgia, "Liberation Serif", serif; line-height: 1.68; color: inherit; }
            h1 { font-size: 1.7em; font-weight: normal; text-transform: uppercase; letter-spacing: 0.12em; text-align: center; margin: 2em 0 1.4em; line-height: 1.3; }
            h2 { font-size: 1.25em; font-variant: small-caps; letter-spacing: 0.06em; text-align: center; margin: 1.6em 0 0.8em; }
            h3 { font-size: 1.1em; font-style: italic; margin: 1.2em 0 0.5em; }
            p { margin: 0 0 0.3em; text-indent: 1.5em; text-align: justify; }
            h1 + p, h2 + p, h3 + p, hr + p, .scene-break + p, blockquote + p, figure + p, p.first-para { text-indent: 0; }
            blockquote { margin: 1.2em 2em; font-style: italic; }
            blockquote p { text-indent: 0; }
            """),
        TECHNICAL("Technical & Docs", "Structured hierarchy, code blocks, clean data tables", """
            body { margin: 4% 5%; font-family: "Liberation Sans", "DejaVu Sans", sans-serif; line-height: 1.58; color: inherit; }
            h1 { font-size: 1.85em; font-weight: 700; margin: 1em 0 0.8em; line-height: 1.2; }
            h2 { font-size: 1.38em; font-weight: 600; margin: 1.5em 0 0.5em; border-bottom: 1px solid #d0d7de; padding-bottom: 0.2em; }
            h3 { font-size: 1.14em; font-weight: 600; margin: 1.2em 0 0.4em; }
            p { margin: 0 0 0.85em; }
            blockquote { margin: 1em 0; padding: 0.5em 1em; border-left: 4px solid #6b7280; background: rgba(128,128,128,0.08); }
            pre { background: rgba(128,128,128,0.1); border: 1px solid rgba(128,128,128,0.25); border-radius: 4px; padding: 0.85em 1em; }
            """),
        POETRY("Poetry & Verse", "Graceful margins, centered titles, preserved stanza flow", """
            body { margin: 6% 9%; font-family: Georgia, "Liberation Serif", serif; line-height: 1.75; color: inherit; }
            h1 { font-size: 1.65em; font-weight: normal; font-style: italic; text-align: center; margin: 1.8em 0 1.4em; }
            h2 { font-size: 1.25em; font-weight: normal; font-style: italic; margin: 1.5em 0 0.7em; }
            p { margin: 0 0 1.15em; text-indent: 0; }
            blockquote { margin: 1.2em 1.6em; font-style: italic; }
            """),
        MINIMAL("Minimal E-Ink", "Ultra-lightweight defaults respecting every e-reader preference", """
            body { margin: 5%; line-height: 1.55; }
            h1 { line-height: 1.15; }
            """);

        final String label;
        final String summary;
        final String css;

        BookTheme(String label, String summary, String css) {
            this.label = label;
            this.summary = summary;
            this.css = css;
        }

        static BookTheme fromName(String name) {
            if (name == null || name.isBlank()) return CLASSIC;
            try { return valueOf(name.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return CLASSIC; }
        }
    }

    record Metadata(
        String id, String title, String subtitle, String author, String language,
        String description, String publisher, String subjects,
        String series, String seriesIndex, String rights, String date
    ) {
        Metadata {
            id = safe(id);
            title = safe(title);
            subtitle = safe(subtitle);
            author = safe(author);
            language = safe(language);
            description = safe(description);
            publisher = safe(publisher);
            subjects = safe(subjects);
            series = safe(series);
            seriesIndex = safe(seriesIndex);
            rights = safe(rights);
            date = safe(date);
        }

        static Metadata basic(String id, String title, String author, String language) {
            return new Metadata(id, title, "", author, language, "", "", "", "", "", "", "");
        }

        private static String safe(String s) { return s == null ? "" : s; }
    }

    record Options(
        BookTheme theme, String sceneBreak, boolean smartTypography, boolean dropCaps,
        boolean includeTitlePage, boolean includeCopyright, boolean includeNcx, String customCss
    ) {
        Options {
            theme = theme == null ? BookTheme.MINIMAL : theme;
            sceneBreak = sceneBreak == null ? "" : sceneBreak;
            customCss = customCss == null ? "" : customCss;
        }

        static Options defaults() {
            return new Options(BookTheme.MINIMAL, "", false, false, false, false, true, "");
        }

        static Options editorial() {
            return new Options(BookTheme.CLASSIC, "* * *", true, false, false, false, true, "");
        }
    }

    record Chapter(String title, String body, boolean markdown, Map<String, Image> images,
                   ChapterRole role, boolean hideHeading) {
        Chapter(String title, String body, boolean markdown) {
            this(title, body, markdown, Map.of(), ChapterRole.CHAPTER, false);
        }
        Chapter(String title, String body, boolean markdown, Map<String, Image> images) {
            this(title, body, markdown, images, ChapterRole.CHAPTER, false);
        }
        Chapter(String title, String body, boolean markdown, Map<String, Image> images, ChapterRole role) {
            this(title, body, markdown, images, role, role == ChapterRole.PLATE);
        }
        Chapter {
            title = title == null ? "" : title;
            body = body == null ? "" : body;
            images = images == null ? Map.of() : Map.copyOf(images);
            role = role == null ? ChapterRole.CHAPTER : role;
        }

        Set<String> referencedImages() {
            var refs = new LinkedHashSet<String>();
            var m = ILLUSTRATION.matcher(body);
            while (m.find()) refs.add(m.group(2));
            return refs;
        }

        Chapter pruneUnusedImages() {
            if (images.isEmpty()) return this;
            var used = referencedImages();
            if (used.size() == images.size() && images.keySet().containsAll(used)) return this;
            var kept = new LinkedHashMap<String, Image>();
            for (var entry : images.entrySet()) {
                if (used.contains(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
            }
            return new Chapter(title, body, markdown, kept, role, hideHeading);
        }
    }

    static final Pattern ILLUSTRATION = Pattern.compile(
        "(?m)^!\\[([^\\]\\n]*)\\]\\((img-[a-f0-9-]{36}\\.(?:png|jpg|jpeg|gif|webp|svg))\\)[ \\t]*$");

    record Image(String mime, byte[] bytes) {
        Image {
            Objects.requireNonNull(mime, "mime");
            Objects.requireNonNull(bytes, "bytes");
        }

        String extension() {
            return switch (mime.toLowerCase(Locale.ROOT)) {
                case "image/png" -> ".png";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                case "image/svg+xml" -> ".svg";
                default -> ".jpg";
            };
        }

        String filename() {
            return "cover" + extension();
        }

        @Override public boolean equals(Object obj) {
            return this == obj || (obj instanceof Image other
                && Objects.equals(mime, other.mime) && Arrays.equals(bytes, other.bytes));
        }

        @Override public int hashCode() {
            return 31 * Objects.hashCode(mime) + Arrays.hashCode(bytes);
        }
    }

    static final String CSS = """
        body { margin: 5%; line-height: 1.55; }
        h1 { line-height: 1.15; }
        img { max-width: 100%; height: auto; }
        .cover { text-align: center; }
        pre { white-space: pre-wrap; }
        """;

    private static final String SHARED_CSS = """
        img { max-width: 100%; height: auto; }
        .cover { text-align: center; margin: 0; padding: 0; }
        .cover img { max-height: 96vh; max-width: 100%; height: auto; }
        figure.illustration { text-align: center; margin: 1.3em auto; page-break-inside: avoid; }
        figure.illustration img { max-width: 100%; height: auto; }
        figure.illustration figcaption { font-size: 0.88em; font-style: italic; opacity: 0.78; margin-top: 0.45em; }
        pre { white-space: pre-wrap; word-break: break-word; font-family: "Liberation Mono", "DejaVu Sans Mono", monospace; font-size: 0.88em; padding: 0.7em 0.9em; background: rgba(128,128,128,0.09); border-radius: 4px; }
        code { font-family: "Liberation Mono", "DejaVu Sans Mono", monospace; font-size: 0.9em; }
        hr { border: none; border-top: 1px solid rgba(128,128,128,0.35); margin: 1.8em auto; width: 42%; }
        .scene-break { text-align: center; margin: 1.5em 0; letter-spacing: 0.25em; text-indent: 0; opacity: 0.75; }
        table { border-collapse: collapse; width: 100%; margin: 1.2em 0; font-size: 0.93em; }
        th, td { border: 1px solid rgba(128,128,128,0.35); padding: 0.45em 0.65em; text-align: left; }
        th { background: rgba(128,128,128,0.1); font-weight: bold; }
        .part-page { text-align: center; margin-top: 22%; }
        .part-page h1 { font-size: 2.1em; letter-spacing: 0.08em; margin-bottom: 0.6em; }
        .title-page { text-align: center; margin-top: 18%; }
        .title-page h1 { font-size: 2.3em; margin-bottom: 0.35em; border: none; }
        .title-page .subtitle { font-size: 1.2em; font-style: italic; opacity: 0.8; margin: 0.4em 0 1.4em; text-indent: 0; }
        .title-page .author { font-size: 1.15em; margin-top: 2.2em; text-indent: 0; }
        .title-page .series { font-size: 0.9em; letter-spacing: 0.12em; text-transform: uppercase; opacity: 0.7; margin-bottom: 1em; text-indent: 0; }
        .title-page .publisher { font-size: 0.9em; opacity: 0.65; margin-top: 3.5em; text-indent: 0; }
        .copyright-page { font-size: 0.88em; line-height: 1.6; margin-top: 15%; }
        .copyright-page p { text-indent: 0; margin-bottom: 0.8em; }
        .footnotes { margin-top: 2.2em; font-size: 0.88em; }
        .footnotes-sep { width: 30%; margin-left: 0; }
        .noteref { text-decoration: none; }
        .footnote-backref { text-decoration: none; margin-left: 0.3em; }
        .task { font-style: normal; }
        """;

    private static final String DROP_CAP_CSS = """
        p.first-para::first-letter { float: left; font-size: 3.1em; line-height: 0.84; padding-right: 0.1em; padding-top: 0.06em; font-weight: bold; }
        """;

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    static String stylesheet(Options options) {
        Options opts = options == null ? Options.defaults() : options;
        var sb = new StringBuilder();
        sb.append(opts.theme().css).append('\n').append(SHARED_CSS);
        if (opts.dropCaps()) sb.append('\n').append(DROP_CAP_CSS);
        if (!opts.customCss().isBlank()) sb.append("\n/* Custom CSS */\n").append(opts.customCss()).append('\n');
        return sb.toString();
    }

    static void write(OutputStream destination, String id, String title, String author,
                      String language, List<Chapter> chapters, Image cover) throws IOException {
        write(destination, Metadata.basic(id, title, author, language), chapters, cover, Options.defaults());
    }

    static void write(OutputStream destination, Metadata meta, List<Chapter> chapters,
                      Image cover, Options options) throws IOException {
        Options opts = options == null ? Options.defaults() : options;
        String lang = meta.language().isBlank() ? "en" : meta.language().strip();
        String bookTitle = meta.title().strip();
        String bookAuthor = meta.author().strip();

        try (var zip = new ZipOutputStream(destination)) {
            writeMimetype(zip);
            put(zip, "META-INF/container.xml", XML + """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """);
            put(zip, "EPUB/book.css", stylesheet(opts));

            var manifest = new StringBuilder("""
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="css" href="book.css" media-type="text/css"/>
                """);
            if (opts.includeNcx()) {
                manifest.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>");
            }
            var spine = new StringBuilder();
            var tocEntries = new ArrayList<TocEntry>();
            String startHref = null;

            if (cover != null) {
                manifest.append("<item id=\"cover-img\" href=\"").append(cover.filename())
                    .append("\" properties=\"cover-image\" media-type=\"").append(xml(cover.mime())).append("\"/>");
                addPage(manifest, spine, "cover");
                put(zip, "EPUB/" + cover.filename(), cover.bytes());
                put(zip, "EPUB/cover.xhtml", page("Cover", lang,
                    "<section epub:type=\"cover\" class=\"cover\"><img src=\"" + cover.filename()
                        + "\" alt=\"Cover of " + xml(bookTitle) + "\"/></section>"));
            }

            if (opts.includeTitlePage()) {
                addPage(manifest, spine, "titlepage");
                var tp = new StringBuilder("<section epub:type=\"titlepage\" class=\"title-page\">");
                if (!meta.series().isBlank()) {
                    tp.append("<p class=\"series\">").append(xml(meta.series()));
                    if (!meta.seriesIndex().isBlank()) tp.append(" \u00B7 Book ").append(xml(meta.seriesIndex()));
                    tp.append("</p>");
                }
                tp.append("<h1>").append(xml(bookTitle)).append("</h1>");
                if (!meta.subtitle().isBlank()) {
                    tp.append("<p class=\"subtitle\">").append(xml(meta.subtitle())).append("</p>");
                }
                if (!bookAuthor.isBlank()) {
                    tp.append("<p class=\"author\">").append(xml(bookAuthor)).append("</p>");
                }
                if (!meta.publisher().isBlank()) {
                    tp.append("<p class=\"publisher\">").append(xml(meta.publisher())).append("</p>");
                }
                tp.append("</section>");
                put(zip, "EPUB/titlepage.xhtml", page(bookTitle, lang, tp.toString()));
                tocEntries.add(new TocEntry("Title Page", "titlepage.xhtml", false, false));
            }

            if (opts.includeCopyright()) {
                addPage(manifest, spine, "copyright");
                String year = !meta.date().isBlank() && meta.date().length() >= 4
                    ? meta.date().substring(0, 4) : String.valueOf(LocalDate.now().getYear());
                var cp = new StringBuilder("<section epub:type=\"copyright-page\" class=\"copyright-page\">");
                cp.append("<p><strong>").append(xml(bookTitle)).append("</strong></p>");
                if (!bookAuthor.isBlank()) {
                    cp.append("<p>Copyright \u00A9 ").append(xml(year)).append(' ').append(xml(bookAuthor)).append("</p>");
                }
                if (!meta.rights().isBlank()) {
                    cp.append("<p>").append(xml(meta.rights())).append("</p>");
                } else {
                    cp.append("<p>All rights reserved.</p>");
                }
                if (!meta.publisher().isBlank()) {
                    cp.append("<p>Published by ").append(xml(meta.publisher())).append("</p>");
                }
                if (!meta.id().isBlank()) {
                    cp.append("<p>Identifier: ").append(xml(meta.id())).append("</p>");
                }
                cp.append("</section>");
                put(zip, "EPUB/copyright.xhtml", page("Copyright", lang, cp.toString()));
                tocEntries.add(new TocEntry("Copyright", "copyright.xhtml", false, false));
            }

            var writtenImages = new HashSet<String>();
            int imageSerial = 0;
            for (int i = 0; i < chapters.size(); i++) {
                String name = "chapter-" + (i + 1);
                Chapter chapter = chapters.get(i);
                var refs = ILLUSTRATION.matcher(chapter.body());
                while (refs.find()) {
                    String file = refs.group(2);
                    Image image = chapter.images().get(file);
                    if (image != null && writtenImages.add(file)) {
                        imageSerial++;
                        manifest.append("<item id=\"img-").append(imageSerial).append("\" href=\"")
                            .append(xml(file)).append("\" media-type=\"").append(xml(image.mime())).append("\"/>");
                        put(zip, "EPUB/" + file, image.bytes());
                    }
                }
                addPage(manifest, spine, name);
                put(zip, "EPUB/" + name + ".xhtml", page(chapter.title(), lang, render(chapter, opts)));
                boolean isPart = chapter.role() == ChapterRole.PART;
                boolean isBackmatter = chapter.role() == ChapterRole.BACKMATTER;
                tocEntries.add(new TocEntry(chapter.title(), name + ".xhtml", isPart, isBackmatter));
                if (startHref == null && (chapter.role() == ChapterRole.CHAPTER || chapter.role() == ChapterRole.PART)) {
                    startHref = name + ".xhtml";
                }
            }
            if (startHref == null && !chapters.isEmpty()) startHref = "chapter-1.xhtml";

            put(zip, "EPUB/nav.xhtml", buildNav(lang, tocEntries, cover != null, opts.includeTitlePage(), startHref));
            if (opts.includeNcx()) {
                put(zip, "EPUB/toc.ncx", buildNcx(meta.id(), bookTitle, tocEntries));
            }

            var metadataXml = new StringBuilder();
            metadataXml.append("<dc:identifier id=\"book-id\">").append(xml(meta.id())).append("</dc:identifier>");
            metadataXml.append("<dc:title id=\"main-title\">").append(xml(bookTitle)).append("</dc:title>");
            if (!meta.subtitle().isBlank()) {
                metadataXml.append("<meta refines=\"#main-title\" property=\"title-type\">main</meta>");
                metadataXml.append("<dc:title id=\"sub-title\">").append(xml(meta.subtitle())).append("</dc:title>");
                metadataXml.append("<meta refines=\"#sub-title\" property=\"title-type\">subtitle</meta>");
            }
            metadataXml.append("<dc:language>").append(xml(lang)).append("</dc:language>");
            if (!bookAuthor.isBlank()) {
                metadataXml.append("<dc:creator id=\"creator\">").append(xml(bookAuthor)).append("</dc:creator>");
                metadataXml.append("<meta refines=\"#creator\" property=\"role\" scheme=\"marc:relators\">aut</meta>");
            }
            if (!meta.description().isBlank()) {
                metadataXml.append("<dc:description>").append(xml(meta.description())).append("</dc:description>");
            }
            if (!meta.publisher().isBlank()) {
                metadataXml.append("<dc:publisher>").append(xml(meta.publisher())).append("</dc:publisher>");
            }
            if (!meta.rights().isBlank()) {
                metadataXml.append("<dc:rights>").append(xml(meta.rights())).append("</dc:rights>");
            }
            if (!meta.date().isBlank()) {
                metadataXml.append("<dc:date>").append(xml(meta.date())).append("</dc:date>");
            }
            if (!meta.subjects().isBlank()) {
                for (String tag : meta.subjects().split("[,;]+")) {
                    String trimmed = tag.strip();
                    if (!trimmed.isEmpty()) {
                        metadataXml.append("<dc:subject>").append(xml(trimmed)).append("</dc:subject>");
                    }
                }
            }
            if (!meta.series().isBlank()) {
                metadataXml.append("<meta property=\"belongs-to-collection\" id=\"col\">")
                    .append(xml(meta.series())).append("</meta>");
                metadataXml.append("<meta refines=\"#col\" property=\"collection-type\">series</meta>");
                if (!meta.seriesIndex().isBlank()) {
                    metadataXml.append("<meta refines=\"#col\" property=\"group-position\">")
                        .append(xml(meta.seriesIndex())).append("</meta>");
                }
            }
            metadataXml.append("<meta property=\"dcterms:modified\">")
                .append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append("</meta>");
            if (cover != null && opts.includeNcx()) {
                // Legacy cover meta for older e-readers (valid EPUB 3.3 fallback).
                metadataXml.append("<meta name=\"cover\" content=\"cover-img\"/>");
            }

            String spineAttr = opts.includeNcx() ? " toc=\"ncx\"" : "";
            put(zip, "EPUB/book.opf", XML + String.format(Locale.ROOT, """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"
                         version="3.0" unique-identifier="book-id">
                  <metadata>%s</metadata>
                  <manifest>%s</manifest>
                  <spine%s>%s</spine>
                </package>
                """, metadataXml, manifest, spineAttr, spine));
        }
    }

    private record TocEntry(String title, String href, boolean isPart, boolean isBackmatter) {}

    private static String buildNav(String lang, List<TocEntry> entries, boolean hasCover,
                                   boolean hasTitlePage, String startHref) {
        var toc = new StringBuilder();
        boolean inPart = false;
        boolean partHasChildren = false;
        for (TocEntry entry : entries) {
            if (entry.isPart()) {
                if (inPart) {
                    if (partHasChildren) toc.append("</ol>");
                    toc.append("</li>");
                }
                toc.append("<li><a href=\"").append(entry.href()).append("\">")
                    .append(xml(entry.title())).append("</a>");
                inPart = true;
                partHasChildren = false;
            } else if (inPart && entry.isBackmatter() && partHasChildren) {
                toc.append("</ol></li>");
                inPart = false;
                partHasChildren = false;
                toc.append("<li><a href=\"").append(entry.href()).append("\">")
                    .append(xml(entry.title())).append("</a></li>");
            } else if (inPart) {
                if (!partHasChildren) {
                    toc.append("<ol>");
                    partHasChildren = true;
                }
                toc.append("<li><a href=\"").append(entry.href()).append("\">")
                    .append(xml(entry.title())).append("</a></li>");
            } else {
                toc.append("<li><a href=\"").append(entry.href()).append("\">")
                    .append(xml(entry.title())).append("</a></li>");
            }
        }
        if (inPart) {
            if (partHasChildren) toc.append("</ol>");
            toc.append("</li>");
        }

        var landmarkItems = new StringBuilder();
        if (hasCover) landmarkItems.append("<li><a epub:type=\"cover\" href=\"cover.xhtml\">Cover</a></li>");
        if (hasTitlePage) landmarkItems.append("<li><a epub:type=\"titlepage\" href=\"titlepage.xhtml\">Title Page</a></li>");
        if (startHref != null) landmarkItems.append("<li><a epub:type=\"bodymatter\" href=\"").append(startHref).append("\">Begin Reading</a></li>");
        String landmarks = landmarkItems.length() == 0 ? ""
            : "<nav epub:type=\"landmarks\" id=\"landmarks\" hidden=\"hidden\"><h2>Guide</h2><ol>" + landmarkItems + "</ol></nav>";

        return page("Contents", lang,
            "<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>" + toc + "</ol></nav>" + landmarks);
    }

    private static String buildNcx(String uid, String title, List<TocEntry> entries) {
        var navMap = new StringBuilder();
        int playOrder = 0;
        boolean inPart = false;
        boolean partHasChildren = false;
        boolean hasNested = false;
        for (TocEntry entry : entries) {
            playOrder++;
            if (entry.isPart()) {
                if (inPart) navMap.append("</navPoint>");
                navMap.append("<navPoint id=\"np-").append(playOrder).append("\" playOrder=\"").append(playOrder)
                    .append("\"><navLabel><text>").append(xml(entry.title()))
                    .append("</text></navLabel><content src=\"").append(entry.href()).append("\"/>");
                inPart = true;
                partHasChildren = false;
            } else if (inPart && entry.isBackmatter() && partHasChildren) {
                navMap.append("</navPoint>");
                inPart = false;
                partHasChildren = false;
                navMap.append("<navPoint id=\"np-").append(playOrder).append("\" playOrder=\"").append(playOrder)
                    .append("\"><navLabel><text>").append(xml(entry.title()))
                    .append("</text></navLabel><content src=\"").append(entry.href()).append("\"/></navPoint>");
            } else {
                if (inPart) {
                    hasNested = true;
                    partHasChildren = true;
                }
                navMap.append("<navPoint id=\"np-").append(playOrder).append("\" playOrder=\"").append(playOrder)
                    .append("\"><navLabel><text>").append(xml(entry.title()))
                    .append("</text></navLabel><content src=\"").append(entry.href()).append("\"/></navPoint>");
            }
        }
        if (inPart) navMap.append("</navPoint>");
        return XML + String.format(Locale.ROOT, """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head>
                <meta name="dtb:uid" content="%s"/>
                <meta name="dtb:depth" content="%d"/>
                <meta name="dtb:totalPageCount" content="0"/>
                <meta name="dtb:maxPageNumber" content="0"/>
              </head>
              <docTitle><text>%s</text></docTitle>
              <navMap>%s</navMap>
            </ncx>
            """, xml(uid), hasNested ? 2 : 1, xml(title), navMap);
    }

    static String render(Chapter chapter) {
        return render(chapter, Options.defaults());
    }

    static String render(Chapter chapter, Options options) {
        Options opts = options == null ? Options.defaults() : options;
        String sceneBreakHtml = opts.sceneBreak().isBlank()
            ? "<hr/>"
            : "<p class=\"scene-break\" aria-hidden=\"true\">" + xml(opts.sceneBreak()) + "</p>";
        boolean isPart = chapter.role() == ChapterRole.PART;
        var result = new StringBuilder();
        result.append("<section epub:type=\"").append(chapter.role().epubType).append('"');
        if (isPart) result.append(" class=\"part-page\"");
        result.append('>');
        if (!chapter.hideHeading()) {
            String headingText = opts.smartTypography() ? Manuscript.smartifyText(chapter.title()) : chapter.title();
            result.append("<h1>").append(xml(headingText)).append("</h1>");
        }
        int offset = 0;
        boolean dropCap = opts.dropCaps() && chapter.role() == ChapterRole.CHAPTER;
        var match = ILLUSTRATION.matcher(chapter.body());
        while (match.find()) {
            if (!chapter.images().containsKey(match.group(2))) continue;
            String before = chapter.body().substring(offset, match.start());
            if (!before.isBlank()) {
                result.append(Manuscript.html(before, chapter.markdown(), opts.smartTypography(), sceneBreakHtml, dropCap));
                dropCap = false;
            }
            String caption = match.group(1).strip();
            result.append("<figure class=\"illustration\"><img src=\"").append(xml(match.group(2)))
                .append("\" alt=\"").append(xml(caption)).append("\"/>");
            if (!caption.isEmpty() && chapter.role() != ChapterRole.PLATE) {
                result.append("<figcaption>").append(xml(opts.smartTypography() ? Manuscript.smartifyText(caption) : caption))
                    .append("</figcaption>");
            }
            result.append("</figure>");
            offset = match.end();
        }
        String tail = chapter.body().substring(offset);
        if (!tail.isBlank()) {
            result.append(Manuscript.html(tail, chapter.markdown(), opts.smartTypography(), sceneBreakHtml, dropCap));
        }
        result.append("</section>");
        return result.toString();
    }

    static String exportSingleHtml(Metadata meta, List<Chapter> chapters, Image cover, Options options) {
        Options opts = options == null ? Options.editorial() : options;
        var toc = new StringBuilder();
        var body = new StringBuilder();
        if (cover != null) {
            String b64 = Base64.getEncoder().encodeToString(cover.bytes());
            body.append("<div class=\"web-cover\"><img src=\"data:").append(cover.mime())
                .append(";base64,").append(b64).append("\" alt=\"Cover\"/></div>");
        }
        body.append("<header class=\"web-header\"><h1>").append(xml(meta.title())).append("</h1>");
        if (!meta.subtitle().isBlank()) body.append("<p class=\"web-sub\">").append(xml(meta.subtitle())).append("</p>");
        if (!meta.author().isBlank()) body.append("<p class=\"web-author\">").append(xml(meta.author())).append("</p>");
        body.append("</header>");

        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            String anchor = "ch-" + (i + 1);
            toc.append("<a class=\"").append(ch.role() == ChapterRole.PART ? "toc-part" : "toc-item")
                .append("\" href=\"#").append(anchor).append("\">").append(xml(ch.title())).append("</a>");
            String rendered = render(ch, opts);
            for (var entry : ch.images().entrySet()) {
                String dataUri = "data:" + entry.getValue().mime() + ";base64,"
                    + Base64.getEncoder().encodeToString(entry.getValue().bytes());
                rendered = rendered.replace("src=\"" + xml(entry.getKey()) + "\"", "src=\"" + dataUri + "\"");
            }
            body.append("<article id=\"").append(anchor).append("\" class=\"web-chapter\">").append(rendered).append("</article>");
        }

        return String.format(Locale.ROOT, """
            <!DOCTYPE html>
            <html lang="%s">
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>%s</title>
              <style>
                :root { --bg: #f8f4ec; --fg: #222925; --panel: #efe9dc; --accent: #285648; --border: #dad2c2; }
                body.sepia { --bg: #f4ecd8; --fg: #3b2f1e; --panel: #eae0c8; --accent: #7a4b23; --border: #d7c9ab; }
                body.dark { --bg: #171a1d; --fg: #dfe4e8; --panel: #1f2429; --accent: #68b89b; --border: #313840; }
                * { box-sizing: border-box; }
                body { margin: 0; background: var(--bg); color: var(--fg); transition: background 0.2s, color 0.2s; }
                .topbar { position: sticky; top: 0; z-index: 10; display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center;
                  padding: 10px 20px; background: var(--panel); border-bottom: 1px solid var(--border); font-family: sans-serif; font-size: 14px; }
                .controls button { background: transparent; border: 1px solid var(--border); color: var(--fg); border-radius: 6px;
                  padding: 4px 10px; margin-left: 6px; cursor: pointer; }
                .layout { display: flex; max-width: 1120px; margin: 0 auto; }
                aside.toc { width: 250px; flex-shrink: 0; padding: 24px 16px; border-right: 1px solid var(--border);
                  position: sticky; top: 48px; height: calc(100vh - 48px); overflow-y: auto; font-family: sans-serif; }
                aside.toc a { display: block; color: var(--fg); text-decoration: none; padding: 6px 8px; border-radius: 6px; font-size: 14px; }
                aside.toc a:hover { background: var(--panel); color: var(--accent); }
                aside.toc a.toc-part { font-weight: 700; margin-top: 12px; color: var(--accent); }
                main { flex: 1; max-width: 740px; margin: 0 auto; padding: 32px 28px 80px; }
                .web-cover { text-align: center; margin-bottom: 36px; }
                .web-cover img { max-width: 100%%; max-height: 460px; border-radius: 6px; box-shadow: 0 10px 30px rgba(0,0,0,0.18); }
                .web-header { text-align: center; margin-bottom: 48px; padding-bottom: 24px; border-bottom: 1px solid var(--border); }
                .web-chapter { margin-bottom: 56px; padding-bottom: 36px; border-bottom: 1px solid var(--border); }
                .toc summary { cursor: pointer; margin-bottom: 8px; }
                @media (max-width: 780px) {
                  .layout { display: block; }
                  aside.toc { position: static; height: auto; width: auto; padding: 12px 20px; border-bottom: 1px solid var(--border); }
                  aside details[open] { max-height: 36vh; overflow: auto; }
                  main { min-width: 0; padding: 24px 18px 64px; overflow-wrap: anywhere; }
                  .web-chapter { scroll-margin-top: 112px; }
                }
                %s
              </style>
            </head>
            <body>
              <div class="topbar">
                <strong>%s</strong>
                <div class="controls">
                  <button onclick="document.body.className=''">Light</button>
                  <button onclick="document.body.className='sepia'">Sepia</button>
                  <button onclick="document.body.className='dark'">Dark</button>
                  <button onclick="scale(-1)">A-</button>
                  <button onclick="scale(1)">A+</button>
                </div>
              </div>
              <div class="layout">
                <aside class="toc"><details id="contents" open><summary>Contents</summary>%s</details></aside>
                <main id="reader">%s</main>
              </div>
              <script>
                const compact = matchMedia('(max-width: 780px)');
                const contents = document.getElementById('contents');
                function adaptContents(){ contents.open = !compact.matches; }
                adaptContents();
                compact.addEventListener('change', adaptContents);
                contents.addEventListener('click', e => { if (compact.matches && e.target.closest('a')) contents.open = false; });
                let sz = 18;
                function scale(d){ sz = Math.max(13, Math.min(28, sz + d*2)); document.getElementById('reader').style.fontSize = sz + 'px'; }
                document.getElementById('reader').style.fontSize = sz + 'px';
              </script>
            </body>
            </html>
            """, xml(meta.language().isBlank() ? "en" : meta.language()),
                xml(meta.title()), stylesheet(opts).replaceAll("(?i)</style", "<" + java.util.regex.Matcher.quoteReplacement("\\/style")),
                xml(meta.title()), toc, body);
    }

    static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] mime = bytes("application/epub+zip");
        var entry = new ZipEntry("mimetype");
        var crc = new CRC32();
        crc.update(mime);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(mime.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(mime);
        zip.closeEntry();
    }

    private static void addPage(StringBuilder manifest, StringBuilder spine, String id) {
        manifest.append("<item id=\"").append(id).append("\" href=\"").append(id)
            .append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
        spine.append("<itemref idref=\"").append(id).append("\"/>");
    }

    private static String page(String title, String lang, String body) {
        return XML + "<!DOCTYPE html>\n" + String.format(Locale.ROOT, """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="%s" xml:lang="%s">
              <head><title>%s</title><link rel="stylesheet" type="text/css" href="book.css"/></head>
              <body>%s</body>
            </html>
            """, xml(lang), xml(lang), xml(title), body);
    }

    static String xml(String value) {
        if (value == null) return "";
        var escaped = new StringBuilder(value.length());
        value.codePoints().forEach(point -> {
            switch (point) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> {
                    if (point == 9 || point == 10 || point == 13 || point >= 32 && point <= 0xd7ff
                            || point >= 0xe000 && point <= 0xfffd || point >= 0x10000) {
                        escaped.appendCodePoint(point);
                    }
                }
            }
        });
        return escaped.toString();
    }

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static void put(ZipOutputStream zip, String name, String value) throws IOException {
        put(zip, name, bytes(value));
    }

    static void put(ZipOutputStream zip, String name, byte[] value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }
}
