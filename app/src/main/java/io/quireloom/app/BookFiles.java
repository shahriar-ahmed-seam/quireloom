package io.quireloom.app;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.*;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class BookFiles {
    static final int TEXT_LIMIT = 4 * 1024 * 1024;
    static final int COVER_LIMIT = 16 * 1024 * 1024;
    static final int DRAFT_LIMIT = 256 * 1024 * 1024;
    static final int CHAPTER_LIMIT = 2000;
    static final String OPF = "http://www.idpf.org/2007/opf";
    static final String DC = "http://purl.org/dc/elements/1.1/";
    static final String XHTML = "http://www.w3.org/1999/xhtml";
    static final String EPUB_NS = "http://www.idpf.org/2007/ops";
    static final String NCX = "http://www.daisy.org/z3986/2005/ncx/";
    static final String CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container";

    private static final Set<String> SKIPPED = Set.of("script", "style", "nav", "noscript", "head");
    private static final Pattern HTML_SPACE = Pattern.compile("[\\t\\n\\r ]+");
    private static final Pattern IMAGE_EXT = Pattern.compile("(?is).+\\.(png|jpg|jpeg|gif|webp)");

    record Imported(Epub.Metadata metadata, Epub.Image cover, List<Epub.Chapter> chapters, boolean fromEpub) {
        String title() { return metadata.title(); }
        String author() { return metadata.author(); }
        String language() { return metadata.language(); }
    }

    static DocumentBuilder newXmlParser() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        var parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> {
            // Supply XHTML named entities locally for any DTD reference; never fetch over the network.
            InputStream entities = BookFiles.class.getResourceAsStream("/io/quireloom/app/xhtml-entities.dtd");
            return new InputSource(entities == null ? InputStream.nullInputStream() : entities);
        });
        parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException {
                throw error;
            }
        });
        return parser;
    }

    static byte[] injectHtmlEntitiesIfNeeded(byte[] raw) {
        String source = new String(raw, StandardCharsets.UTF_8);
        var matcher = Pattern.compile("&([A-Za-z][A-Za-z0-9]+);").matcher(source);
        var out = new StringBuffer();
        while (matcher.find()) {
            if (Set.of("amp","lt","gt","quot","apos").contains(matcher.group(1))) continue;
            String decoded = android.text.Html.fromHtml(matcher.group(),android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            if (!decoded.equals(matcher.group()) && !decoded.isEmpty()) {
                var numeric=new StringBuilder();
                decoded.codePoints().forEach(cp->numeric.append("&#").append(cp).append(";"));
                matcher.appendReplacement(out,numeric.toString());
            }
        }
        matcher.appendTail(out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Imported read(Path path) throws Exception {
        return read(path, true);
    }

    static Imported read(Path path, boolean richMarkdown) throws Exception {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub")) return readEpub(path, richMarkdown);
        if (lower.endsWith(".pdf")) return readPdf(path, false);
        if (IMAGE_EXT.matcher(lower).matches()) {
            var img = readImage(path);
            String title = name.replaceFirst("\\.[^.]+$", "").replaceAll("[\\]\\r\\n]", " ").strip();
            if (title.isEmpty()) title = "Illustration";
            String key = "img-" + UUID.randomUUID() + img.extension();
            var ch = new Epub.Chapter(title, "![" + title + "](" + key + ")", false,
                Map.of(key, img), Epub.ChapterRole.PLATE, true);
            return new Imported(Epub.Metadata.basic("", "", "", ""), null, List.of(ch), false);
        }
        if (!lower.matches("(?s).+\\.(md|markdown|txt)")) {
            throw new IOException("Choose an EPUB, Markdown, TXT, PDF, or image file.");
        }
        return new Imported(Epub.Metadata.basic("", "", "", ""), null,
            List.of(Manuscript.fromFile(name, readText(path))), false);
    }

    static String readText(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            return StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(limited(stream, TEXT_LIMIT))).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("Text files must be UTF-8.", error);
        }
    }

    static Imported readPdf(Path path, boolean rasterizePages) throws Exception {
            var chapters = new ArrayList<Epub.Chapter>();
        long total = 0;
        try (var fd = android.os.ParcelFileDescriptor.open(path.toFile(), android.os.ParcelFileDescriptor.MODE_READ_ONLY);
             var pdf = new android.graphics.pdf.PdfRenderer(fd)) {
            if (pdf.getPageCount() > CHAPTER_LIMIT) throw new IOException("Too many PDF pages");
            for (int i = 0; i < pdf.getPageCount(); i++) try (var page = pdf.openPage(i)) {
                if (!rasterizePages && android.os.Build.VERSION.SDK_INT >= 35) {
                    var text = new StringBuilder();
                    for (var block : page.getTextContents()) text.append(block.getText()).append("\n");
                    if (!text.toString().isBlank()) {
                        chapters.add(new Epub.Chapter("Page "+(i+1),text.toString().strip(),false));
                        continue;
                    }
                }
                double scale = 1600.0 / Math.max(page.getWidth(), page.getHeight());
                var bitmap = android.graphics.Bitmap.createBitmap(Math.max(1,(int)(page.getWidth()*scale)), Math.max(1,(int)(page.getHeight()*scale)), android.graphics.Bitmap.Config.ARGB_8888);
                byte[] bytes;
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE);
                    page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    var out = new ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out);
                    bytes = out.toByteArray();
                } finally { bitmap.recycle(); }
                total += bytes.length;
                if (total > 64L*1024*1024) throw new IOException("PDF image workspace exceeds 64 MB");
                String key="img-"+UUID.randomUUID()+".jpg", title="Page "+(i+1);
                chapters.add(new Epub.Chapter(title,"!["+title+"]("+key+")",false,Map.of(key,new Epub.Image("image/jpeg",bytes)),Epub.ChapterRole.PLATE));
            }
        }
        return new Imported(Epub.Metadata.basic("",path.getFileName().toString(),"","en"),null,chapters,false);
    }

    static Imported readEpub(Path path, boolean richMarkdown) throws Exception {
        try (var zip = new ZipFile(path.toFile())) {
            if (zip.size() > 16000) throw new IOException("EPUB contains too many entries.");
            String mimeStr = new String(readEntry(zip, "mimetype", 128), StandardCharsets.US_ASCII).strip();
            if (!"application/epub+zip".equals(mimeStr)) {
                throw new IOException("File is not an EPUB archive.");
            }
            checkDrm(zip);

            var parser = newXmlParser();
            var container = xml(zip, "META-INF/container.xml", parser, TEXT_LIMIT);
            var root = firstElement(container, CONTAINER_NS, "rootfile");
            if (root == null) throw new IOException("EPUB package location is missing.");
            String packagePath = root.getAttribute("full-path");
            var opf = xml(zip, packagePath, parser, TEXT_LIMIT);
            if (!OPF.equals(opf.getDocumentElement().getNamespaceURI())
                    && !"package".equals(opf.getDocumentElement().getLocalName())) {
                throw new IOException("EPUB package metadata is invalid.");
            }

            var meta = extractMetadata(opf);
            var manifest = new LinkedHashMap<String, Element>();
            var items = elementsByLocalName(opf, "item");
            for (var item : items) {
                manifest.put(item.getAttribute("id"), item);
            }

            // Extract cover image if present (EPUB 3 properties="cover-image" or EPUB 2 <meta name="cover">).
            Epub.Image cover = extractCover(zip, opf, packagePath, manifest);
            String coverEntryPath = findCoverHref(opf, packagePath, manifest);

            // Extract TOC labels from nav.xhtml or toc.ncx for accurate chapter titles.
            Map<String, String> tocLabels = extractTocLabels(zip, opf, packagePath, manifest, parser);

            var order = elementsByLocalName(opf, "itemref");
            if (order.size() > CHAPTER_LIMIT) throw new IOException("EPUB has more than 2,000 spine entries.");
            var chapters = new ArrayList<Epub.Chapter>();
            int remaining = DRAFT_LIMIT;

            for (var ref : order) {
                var item = manifest.get(ref.getAttribute("idref"));
                if (item == null) throw new IOException("EPUB spine references a missing manifest item.");
                String mediaType = item.getAttribute("media-type");
                boolean isHtml = "application/xhtml+xml".equals(mediaType) || "text/html".equals(mediaType);
                if (!isHtml) continue;
                List<String> props = Arrays.asList(item.getAttribute("properties").split("\\s+"));
                if (props.contains("nav")) continue;

                String entry = resolveHref(packagePath, item.getAttribute("href"));
                byte[] source = readEntry(zip, entry, Math.min(remaining, TEXT_LIMIT));
                remaining -= source.length;
                Document page;
                try {
                    page = parser.parse(new ByteArrayInputStream(injectHtmlEntitiesIfNeeded(source)));
                } catch (SAXException e) {
                    throw new IOException("EPUB chapter contains unreadable XML: " + entry, e);
                }

                Element body = firstElement(page, XHTML, "body");
                if (body == null) body = firstElementAnyNs(page, "body");
                if (body == null) throw new IOException("EPUB chapter has no XHTML body.");

                Element heading = firstElement(body, XHTML, "h1");
                if (heading == null) heading = firstElementAnyNs(body, "h1");
                String headingText = heading == null ? "" : cleanInlineSpaces(heading.getTextContent());

                var chapterImages = new LinkedHashMap<String, Epub.Image>();
                var imgCounter = new int[]{0};
                var content = new StringBuilder();
                if (richMarkdown) {
                    appendMarkdown(body, headingText.isBlank() ? null : heading, content,
                        zip, entry, chapterImages, remaining, imgCounter);
                } else {
                    appendPlainText(body, headingText.isBlank() ? null : heading, content);
                }
                remaining -= imgCounter[0];

                String text = content.toString().replaceAll("\\n{3,}", "\n\n").strip();

                // Skip cover-only XHTML page if we already extracted the book cover.
                if (cover != null && isCoverOnlyPage(entry, item, body, text, chapterImages, cover, coverEntryPath)) {
                    continue;
                }
                if (text.isBlank() && chapterImages.isEmpty()) continue;

                String tocTitle = tocLabels.getOrDefault(entry, "");
                String htmlTitle = firstTextAnyNs(page, "title");
                boolean specificHtmlTitle = !htmlTitle.isBlank()
                    && !htmlTitle.equalsIgnoreCase(meta.title())
                    && !Set.of("unknown", "untitled", "document", "index", "cover").contains(htmlTitle.toLowerCase(Locale.ROOT));
                String chTitle = !headingText.isBlank() ? headingText
                    : (specificHtmlTitle ? htmlTitle
                    : (!tocTitle.isBlank() ? tocTitle
                    : (!htmlTitle.isBlank() ? htmlTitle
                    : "Chapter " + (chapters.size() + 1))));

                boolean imageOnly = !chapterImages.isEmpty()
                    && Epub.ILLUSTRATION.matcher(text).replaceAll("").isBlank();
                Epub.ChapterRole role = imageOnly ? Epub.ChapterRole.PLATE : Epub.ChapterRole.CHAPTER;
                boolean hideHeading = imageOnly && headingText.isBlank();
                boolean useMarkdown = richMarkdown && !imageOnly;
                chapters.add(new Epub.Chapter(chTitle, text, useMarkdown, chapterImages, role, hideHeading));
            }
            if (chapters.isEmpty()) throw new IOException("EPUB has no readable text or illustrated chapters.");
            return new Imported(meta, cover, List.copyOf(chapters), true);
        } catch (SAXException error) {
            throw new IOException("EPUB contains unreadable or encrypted XML/XHTML.", error);
        } catch (java.util.zip.ZipException error) {
            throw new IOException("EPUB ZIP archive is damaged or unreadable.", error);
        }
    }

    private static boolean isCoverOnlyPage(String entry, Element item, Element body, String text,
                                           Map<String, Epub.Image> chapterImages, Epub.Image cover, String coverHref) {
        String lowerEntry = entry.toLowerCase(Locale.ROOT);
        String id = item.getAttribute("id").toLowerCase(Locale.ROOT);
        boolean looksLikeCover = lowerEntry.contains("cover") || id.contains("cover")
            || "cover".equalsIgnoreCase(body.getAttributeNS(EPUB_NS, "type"));
        if (!looksLikeCover) return false;
        String nonImageText = Epub.ILLUSTRATION.matcher(text).replaceAll("").strip();
        if (!nonImageText.isEmpty()) return false;
        if (chapterImages.isEmpty()) return true;
        return chapterImages.size() == 1 && chapterImages.values().iterator().next().equals(cover);
    }

    static void checkDrm(ZipFile zip) throws IOException {
        var enc = zip.getEntry("META-INF/encryption.xml");
        if (enc == null) return;
        try {
            byte[] data = readEntry(zip, "META-INF/encryption.xml", TEXT_LIMIT);
            String xml = new String(data, StandardCharsets.UTF_8);
            // Allow standard IDPF/Adobe font obfuscation, block actual DRM content encryption.
            if (!xml.contains("http://www.idpf.org/2008/embedding")
                    && !xml.contains("http://ns.adobe.com/pdf/enc#RC")) {
                throw new IOException("This EPUB is DRM-encrypted and cannot be opened.");
            }
            if (xml.contains(".xhtml") || xml.contains(".html") || xml.contains(".xml")) {
                throw new IOException("This EPUB contains DRM-encrypted chapters and cannot be opened.");
            }
        } catch (IOException e) {
            throw e;
        }
    }

    static Epub.Metadata extractMetadata(Document opf) {
        String id = firstText(opf, DC, "identifier");
        if (id.isBlank()) id = "urn:uuid:" + UUID.randomUUID();
        String title = "";
        String subtitle = "";
        var titles = opf.getElementsByTagNameNS(DC, "title");
        var metas = elementsByLocalName(opf, "meta");
        var refines = new HashMap<String, Map<String, String>>();
        for (var m : metas) {
            String ref = m.getAttribute("refines");
            String prop = m.getAttribute("property");
            if (!ref.isBlank() && !prop.isBlank()) {
                refines.computeIfAbsent(ref.startsWith("#") ? ref.substring(1) : ref, k -> new HashMap<>())
                    .put(prop, m.getTextContent().strip());
            }
        }
        for (int i = 0; i < titles.getLength(); i++) {
            var el = (Element) titles.item(i);
            String text = el.getTextContent().strip();
            String elId = el.getAttribute("id");
            String type = elId.isBlank() ? "" : refines.getOrDefault(elId, Map.of()).getOrDefault("title-type", "");
            if ("subtitle".equalsIgnoreCase(type)) subtitle = text;
            else if (title.isBlank()) title = text;
        }
        String author = firstText(opf, DC, "creator");
        String language = firstText(opf, DC, "language");
        String description = firstText(opf, DC, "description");
        String publisher = firstText(opf, DC, "publisher");
        String rights = firstText(opf, DC, "rights");
        String date = firstText(opf, DC, "date");

        var subjectNodes = opf.getElementsByTagNameNS(DC, "subject");
        var subjectList = new ArrayList<String>();
        for (int i = 0; i < subjectNodes.getLength(); i++) {
            String s = subjectNodes.item(i).getTextContent().strip();
            if (!s.isEmpty()) subjectList.add(s);
        }
        String series = "";
        String seriesIndex = "";
        for (var m : metas) {
            String prop = m.getAttribute("property");
            String name = m.getAttribute("name");
            if ("belongs-to-collection".equals(prop)) {
                series = m.getTextContent().strip();
                String colId = m.getAttribute("id");
                if (!colId.isBlank()) {
                    seriesIndex = refines.getOrDefault(colId, Map.of()).getOrDefault("group-position", seriesIndex);
                }
            } else if ("calibre:series".equals(name) && series.isBlank()) {
                series = m.getAttribute("content").strip();
            } else if ("calibre:series_index".equals(name) && seriesIndex.isBlank()) {
                seriesIndex = m.getAttribute("content").strip();
            }
        }
        return new Epub.Metadata(id, title, subtitle, author, language, description,
            publisher, String.join(", ", subjectList), series, seriesIndex, rights, date);
    }

    static String findCoverHref(Document opf, String packagePath, Map<String, Element> manifest) {
        for (var item : manifest.values()) {
            if (Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("cover-image")) {
                try { return resolveHref(packagePath, item.getAttribute("href")); }
                catch (IOException ignored) {}
            }
        }
        for (var m : elementsByLocalName(opf, "meta")) {
            if ("cover".equals(m.getAttribute("name"))) {
                String coverId = m.getAttribute("content").strip();
                var item = manifest.get(coverId);
                if (item != null && item.getAttribute("media-type").startsWith("image/")) {
                    try { return resolveHref(packagePath, item.getAttribute("href")); }
                    catch (IOException ignored) {}
                }
            }
        }
        return null;
    }

    private static Epub.Image extractCover(ZipFile zip, Document opf, String packagePath, Map<String, Element> manifest) {
        String coverHref = findCoverHref(opf, packagePath, manifest);
        if (coverHref == null) return null;
        try {
            byte[] bytes = readEntry(zip, coverHref, COVER_LIMIT);
            return decodeImageBytes(bytes);
        } catch (IOException ignored) {
            return null;
        }
    }

    static Map<String, String> extractTocLabels(ZipFile zip, Document opf, String packagePath,
                                                Map<String, Element> manifest, DocumentBuilder parser) {
        var labels = new LinkedHashMap<String, String>();
        // 1. Try EPUB 3 nav.xhtml first.
        for (var item : manifest.values()) {
            if (Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("nav")) {
                try {
                    String navPath = resolveHref(packagePath, item.getAttribute("href"));
                    var doc = parser.parse(new ByteArrayInputStream(injectHtmlEntitiesIfNeeded(readEntry(zip, navPath, TEXT_LIMIT))));
                    for (var nav : elementsByLocalName(doc, "nav")) {
                        String type = nav.getAttributeNS(EPUB_NS, "type");
                        if (type.isBlank()) type = nav.getAttribute("epub:type");
                        if (type.isBlank() || Arrays.asList(type.split("\\s+")).contains("toc")) {
                            for (var a : elementsByLocalName(nav, "a")) {
                                String href = a.getAttribute("href");
                                String label = cleanInlineSpaces(a.getTextContent());
                                if (!href.isBlank() && !label.isBlank()) {
                                    String clean = stripFragment(href);
                                    if (!clean.isEmpty()) {
                                        try { labels.putIfAbsent(resolveHref(navPath, clean), label); }
                                        catch (IOException ignored) {}
                                    }
                                }
                            }
                            if (!labels.isEmpty()) return labels;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        // 2. Fall back to EPUB 2 toc.ncx.
        for (var item : manifest.values()) {
            if ("application/x-dtbncx+xml".equals(item.getAttribute("media-type"))) {
                try {
                    String ncxPath = resolveHref(packagePath, item.getAttribute("href"));
                    var doc = parser.parse(new ByteArrayInputStream(readEntry(zip, ncxPath, TEXT_LIMIT)));
                    for (var np : elementsByLocalName(doc, "navPoint")) {
                        Element labelEl = firstDirectOrNested(np, "text");
                        Element contentEl = firstDirectOrNested(np, "content");
                        if (labelEl != null && contentEl != null) {
                            String label = cleanInlineSpaces(labelEl.getTextContent());
                            String src = stripFragment(contentEl.getAttribute("src"));
                            if (!label.isBlank() && !src.isBlank()) {
                                try { labels.putIfAbsent(resolveHref(ncxPath, src), label); }
                                catch (IOException ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return labels;
    }

    private static Element firstDirectOrNested(Element parent, String localName) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el) {
                if (localName.equals(el.getLocalName())) return el;
                if (!"navPoint".equals(el.getLocalName())) {
                    Element found = firstDirectOrNested(el, localName);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    static String stripFragment(String href) {
        int hash = href.indexOf('#');
        String s = hash >= 0 ? href.substring(0, hash) : href;
        int q = s.indexOf('?');
        return q >= 0 ? s.substring(0, q) : s;
    }

    private static void appendMarkdown(Node node, Element firstHeading, StringBuilder out,
                                       ZipFile zip, String chapterEntry, Map<String, Epub.Image> images,
                                       int byteBudget, int[] bytesUsed) {
        if (node == firstHeading) return;
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String text = HTML_SPACE.matcher(node.getNodeValue()).replaceAll(" ");
            if (text.startsWith(" ") && (out.length() == 0 || " \n".indexOf(out.charAt(out.length() - 1)) >= 0)) {
                text = text.substring(1);
            }
            out.append(text);
            return;
        }
        if (!(node instanceof Element element)) return;
        String tag = element.getLocalName();
        if (tag == null) tag = element.getTagName().toLowerCase(Locale.ROOT);
        else tag = tag.toLowerCase(Locale.ROOT);
        if (SKIPPED.contains(tag) || element.hasAttribute("hidden")) return;

        switch (tag) {
            case "br" -> out.append("  \n");
            case "hr" -> out.append("\n\n---\n\n");
            case "pre" -> {
                String code = element.getTextContent().replace("\r\n", "\n");
                out.append("\n\n```\n").append(code.endsWith("\n") ? code : code + "\n").append("```\n\n");
            }
            case "code" -> {
                String code = element.getTextContent();
                if (!code.isEmpty()) out.append('`').append(code.replace("`", "\\`")).append('`');
            }
            case "strong", "b" -> wrapInline(element, "**", "**", firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
            case "em", "i" -> wrapInline(element, "*", "*", firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
            case "del", "s", "strike" -> wrapInline(element, "~~", "~~", firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
            case "a" -> {
                String href = element.getAttribute("href").strip();
                var inner = new StringBuilder();
                appendChildren(element, firstHeading, inner, zip, chapterEntry, images, byteBudget, bytesUsed);
                String label = inner.toString().strip();
                if (label.isEmpty()) return;
                if (href.matches("(?i)https?://.+|mailto:.+")) {
                    out.append('[').append(label).append("](").append(href).append(')');
                } else {
                    out.append(label);
                }
            }
            case "figure" -> {
                Element img = firstElementAnyNs(element, "img");
                Element cap = firstElementAnyNs(element, "figcaption");
                if (img != null) {
                    String caption = cap != null ? cleanInlineSpaces(cap.getTextContent()) : img.getAttribute("alt").strip();
                    emitImageMarker(img.getAttribute("src"), caption, out, zip, chapterEntry, images, byteBudget, bytesUsed);
                } else {
                    out.append("\n\n");
                    appendChildren(element, firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
                    out.append("\n\n");
                }
            }
            case "img" -> emitImageMarker(element.getAttribute("src"), element.getAttribute("alt").strip(),
                out, zip, chapterEntry, images, byteBudget, bytesUsed);
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Math.max(1, Math.min(5, (tag.charAt(1) - '0')));
                int mdLevel = Math.max(1, level - 1);
                var inner = new StringBuilder();
                appendChildren(element, firstHeading, inner, zip, chapterEntry, images, byteBudget, bytesUsed);
                String text = inner.toString().strip();
                if (!text.isEmpty()) {
                    out.append("\n\n").append("#".repeat(mdLevel)).append(' ').append(text).append("\n\n");
                }
            }
            case "blockquote" -> {
                var inner = new StringBuilder();
                appendChildren(element, firstHeading, inner, zip, chapterEntry, images, byteBudget, bytesUsed);
                String quoted = inner.toString().replaceAll("\\n{3,}", "\n\n").strip();
                if (!quoted.isEmpty()) {
                    out.append("\n\n");
                    for (String line : quoted.split("\n", -1)) {
                        out.append(line.isBlank() ? ">" : "> " + line).append('\n');
                    }
                    out.append('\n');
                }
            }
            case "ul", "ol" -> {
                boolean ordered = tag.equals("ol");
                int idx = 0;
                out.append("\n\n");
                for (Node c = element.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element li && "li".equalsIgnoreCase(li.getLocalName())) {
                        idx++;
                        var inner = new StringBuilder();
                        appendChildren(li, firstHeading, inner, zip, chapterEntry, images, byteBudget, bytesUsed);
                        String itemText = inner.toString().replace("\n", " ").replaceAll(" +", " ").strip();
                        if (!itemText.isEmpty()) {
                            out.append(ordered ? idx + ". " : "- ").append(itemText).append('\n');
                        }
                    }
                }
                out.append('\n');
            }
            case "table" -> appendMarkdownTable(element, out);
            case "p", "div", "section", "article", "header", "footer", "aside", "dt", "dd" -> {
                out.append("\n\n");
                appendChildren(element, firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
                out.append("\n\n");
            }
            default -> appendChildren(element, firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
        }
    }

    private static void wrapInline(Element element, String open, String close, Element firstHeading,
                                   StringBuilder out, ZipFile zip, String chapterEntry,
                                   Map<String, Epub.Image> images, int byteBudget, int[] bytesUsed) {
        var inner = new StringBuilder();
        appendChildren(element, firstHeading, inner, zip, chapterEntry, images, byteBudget, bytesUsed);
        String text = inner.toString();
        if (text.isBlank()) return;
        String leading = text.startsWith(" ") ? " " : "";
        String trailing = text.endsWith(" ") ? " " : "";
        out.append(leading).append(open).append(text.strip()).append(close).append(trailing);
    }

    private static void appendChildren(Element element, Element firstHeading, StringBuilder out,
                                       ZipFile zip, String chapterEntry, Map<String, Epub.Image> images,
                                       int byteBudget, int[] bytesUsed) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            appendMarkdown(child, firstHeading, out, zip, chapterEntry, images, byteBudget, bytesUsed);
        }
    }

    private static void emitImageMarker(String src, String alt, StringBuilder out, ZipFile zip,
                                        String chapterEntry, Map<String, Epub.Image> images,
                                        int byteBudget, int[] bytesUsed) {
        if (src == null || src.isBlank() || src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:")) {
            return;
        }
        try {
            String resolved = resolveHref(chapterEntry, stripFragment(src));
            int allowed = Math.min(COVER_LIMIT, Math.max(0, byteBudget - bytesUsed[0]));
            if (allowed <= 0) return;
            byte[] data = readEntry(zip, resolved, allowed);
            Epub.Image image = decodeImageBytes(data);
            if (image == null) return;
            bytesUsed[0] += data.length;
            String key = "img-" + UUID.randomUUID() + image.extension();
            images.put(key, image);
            String cleanAlt = alt == null ? "" : alt.replaceAll("[\\]\\r\\n]", " ").strip();
            out.append("\n\n![").append(cleanAlt).append("](").append(key).append(")\n\n");
        } catch (IOException ignored) {
            // Skip unreadable image reference rather than aborting the whole chapter import.
        }
    }

    private static void appendMarkdownTable(Element table, StringBuilder out) {
        var rows = new ArrayList<List<String>>();
        for (var tr : elementsByLocalName(table, "tr")) {
            var cells = new ArrayList<String>();
            for (Node c = tr.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof Element cell) {
                    String name = cell.getLocalName();
                    if ("th".equalsIgnoreCase(name) || "td".equalsIgnoreCase(name)) {
                        cells.add(cleanInlineSpaces(cell.getTextContent()).replace("|", "\\|"));
                    }
                }
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.isEmpty()) return;
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) return;
        out.append("\n\n");
        for (int r = 0; r < rows.size(); r++) {
            out.append('|');
            var row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                out.append(' ').append(c < row.size() ? row.get(c) : "").append(" |");
            }
            out.append('\n');
            if (r == 0) {
                out.append('|');
                for (int c = 0; c < cols; c++) out.append(" --- |");
                out.append('\n');
            }
        }
        out.append('\n');
    }

    private static void appendPlainText(Node node, Element firstHeading, StringBuilder out) {
        if (node == firstHeading) return;
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String text = HTML_SPACE.matcher(node.getNodeValue()).replaceAll(" ");
            if (text.startsWith(" ") && (out.length() == 0 || " \n".indexOf(out.charAt(out.length() - 1)) >= 0)) {
                text = text.substring(1);
            }
            out.append(text);
            return;
        }
        if (!(node instanceof Element element)) return;
        String tag = element.getLocalName();
        if (tag == null) tag = element.getTagName().toLowerCase(Locale.ROOT);
        if (SKIPPED.contains(tag) || element.hasAttribute("hidden")) return;
        if ("br".equals(tag)) { out.append('\n'); return; }
        if ("hr".equals(tag)) { out.append("\n\n* * *\n\n"); return; }
        if ("pre".equals(tag)) { out.append("\n\n").append(element.getTextContent()).append("\n\n"); return; }
        boolean block = Set.of("p", "div", "section", "article", "blockquote", "ul", "ol", "li", "dt", "dd",
            "tr", "table", "figure", "figcaption", "header", "footer", "aside", "h1", "h2", "h3", "h4", "h5", "h6").contains(tag);
        if (block) out.append("\n\n");
        if ("li".equals(tag)) out.append("• ");
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            appendPlainText(child, firstHeading, out);
        }
        if (block) out.append("\n\n");
    }

    private static String cleanInlineSpaces(String text) {
        return text == null ? "" : HTML_SPACE.matcher(text).replaceAll(" ").strip();
    }

    static Document xml(ZipFile zip, String path, DocumentBuilder parser, int limit)
            throws IOException, SAXException {
        return parser.parse(new ByteArrayInputStream(readEntry(zip, path, limit)));
    }

    static String firstText(Document document, String ns, String name) {
        var node = document.getElementsByTagNameNS(ns, name).item(0);
        return node == null ? "" : node.getTextContent().strip();
    }

    static String firstTextAnyNs(Document document, String localName) {
        var el = firstElementAnyNs(document, localName);
        return el == null ? "" : el.getTextContent().strip();
    }

    static Element firstElement(Document doc, String ns, String name) {
        var nodes = doc.getElementsByTagNameNS(ns, name);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    static Element firstElement(Element el, String ns, String name) {
        var nodes = el.getElementsByTagNameNS(ns, name);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    static Element firstElementAnyNs(Document doc, String localName) {
        var nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) return (Element) nodes.item(0);
        nodes = doc.getElementsByTagName(localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    static Element firstElementAnyNs(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() > 0) return (Element) nodes.item(0);
        nodes = parent.getElementsByTagName(localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    static List<Element> elementsByLocalName(Document doc, String localName) {
        var list = new ArrayList<Element>();
        var nodes = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) list.add((Element) nodes.item(i));
        if (list.isEmpty()) {
            nodes = doc.getElementsByTagName(localName);
            for (int i = 0; i < nodes.getLength(); i++) list.add((Element) nodes.item(i));
        }
        return list;
    }

    static List<Element> elementsByLocalName(Element parent, String localName) {
        var list = new ArrayList<Element>();
        var nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) list.add((Element) nodes.item(i));
        if (list.isEmpty()) {
            nodes = parent.getElementsByTagName(localName);
            for (int i = 0; i < nodes.getLength(); i++) list.add((Element) nodes.item(i));
        }
        return list;
    }

    static String resolveHref(String basePath, String href) throws IOException {
        if (href == null || href.isBlank()) throw new IOException("EPUB references an empty path.");
        String cleaned = stripFragment(href).strip();
        if (cleaned.matches("(?i)[a-z][a-z0-9+.-]*:.*") || cleaned.startsWith("//") || cleaned.startsWith("/")) {
            throw new IOException("EPUB references an invalid chapter path: " + href);
        }
        String decoded;
        try {
            // Decode percent-escapes while preserving literal '+' signs in filenames.
            decoded = URLDecoder.decode(cleaned.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            decoded = cleaned;
        }
        int lastSlash = basePath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? basePath.substring(0, lastSlash) : "";
        String combined = dir.isEmpty() ? decoded : dir + "/" + decoded;
        var stack = new ArrayDeque<String>();
        for (String part : combined.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (stack.isEmpty()) throw new IOException("EPUB path escapes archive root: " + href);
                stack.removeLast();
            } else {
                if (part.contains("\\")) throw new IOException("EPUB references an invalid archive path.");
                stack.addLast(part);
            }
        }
        if (stack.isEmpty()) throw new IOException("EPUB references an invalid archive path.");
        return String.join("/", stack);
    }

    static String archivePath(String name) throws IOException {
        if (name == null || name.startsWith("/") || name.isBlank() || name.contains("\\")
                || Arrays.stream(name.split("/", -1)).anyMatch(part ->
                    part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IOException("EPUB references an invalid archive path.");
        }
        return name;
    }

    static byte[] readEntry(ZipFile zip, String name, int limit) throws IOException {
        String safe = archivePath(name);
        ZipEntry entry = zip.getEntry(safe);
        if (entry == null) {
            // Try URL-decoded fallback for archives with non-canonical encoding.
            try {
                entry = zip.getEntry(URLDecoder.decode(safe.replace("+", "%2B"), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {}
        }
        if (entry == null || entry.isDirectory()) throw new IOException("EPUB entry is missing: " + name);
        if (entry.getSize() > limit) throw new IOException("EPUB exceeds its import size limit.");
        try (var stream = zip.getInputStream(entry)) {
            return limited(stream, limit);
        }
    }

    static Epub.Image cover(Path path) throws IOException {
        return readImage(path);
    }

    static Epub.Image readImage(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = limited(stream, COVER_LIMIT);
            Epub.Image decoded = decodeImageBytes(bytes);
            if (decoded == null) throw new IOException("Select a valid PNG, JPEG, GIF, or WebP image.");
            return decoded;
        }
    }

    static Epub.Image decodeImageBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return null;
        var bounds = dimensions(bytes);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long)bounds.outWidth*bounds.outHeight > 40000000 ||
            !Set.of("image/jpeg","image/png","image/webp","image/gif").contains(bounds.outMimeType)) throw new IOException("Unsupported or oversized image");
        return new Epub.Image(bounds.outMimeType, bytes);
    }

    static android.graphics.BitmapFactory.Options dimensions(byte[] bytes) {
        var opts = new android.graphics.BitmapFactory.Options(); opts.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts); return opts;
    }

    static Epub.Image optimizeImage(Epub.Image source,int maxDimension,float quality) throws IOException {
        if (!Set.of("image/jpeg","image/png").contains(source.mime())) return source;
        var bounds=dimensions(source.bytes());
        var opts=new android.graphics.BitmapFactory.Options();
        opts.inSampleSize=1;
        while (Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize > 2*maxDimension) opts.inSampleSize*=2;
        var bitmap=android.graphics.BitmapFactory.decodeByteArray(source.bytes(),0,source.bytes().length,opts);
        if(bitmap==null) return source;
        double ratio=Math.min(1.0,(double)maxDimension/Math.max(bitmap.getWidth(),bitmap.getHeight()));
        var scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(ratio*bitmap.getWidth())),Math.max(1,(int)(ratio*bitmap.getHeight())),true);
        var out=new ByteArrayOutputStream();
        scaled.compress(source.mime().equals("image/png")?android.graphics.Bitmap.CompressFormat.PNG:android.graphics.Bitmap.CompressFormat.JPEG,Math.round(quality*100),out);
        if(scaled!=bitmap) scaled.recycle();
        bitmap.recycle();
        return out.size()<source.bytes().length?new Epub.Image(source.mime(),out.toByteArray()):source;
    }

    static void exportMarkdownFolder(Draft draft, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path assetsDir = directory.resolve("assets");
        if (draft.cover() != null) {
            Files.createDirectories(assetsDir);
            Files.write(assetsDir.resolve(draft.cover().filename()), draft.cover().bytes());
        }
        int num = 0;
        for (Epub.Chapter ch : draft.chapters()) {
            num++;
            String slug = ch.title().replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("^-|-$", "");
            if (slug.isEmpty()) slug = "chapter";
            if (slug.length() > 48) slug = slug.substring(0, 48);
            String filename = String.format(Locale.ROOT, "%02d-%s.%s", num, slug, ch.markdown() ? "md" : "txt");
            String body = ch.body();
            for (var entry : ch.images().entrySet()) {
                Files.createDirectories(assetsDir);
                if (!entry.getKey().matches("img-[a-f0-9-]{36}\\.(png|jpg|jpeg|gif|webp|svg)")) throw new IOException("Invalid image reference");
                Files.write(assetsDir.resolve(entry.getKey()), entry.getValue().bytes());
                body = body.replace("(" + entry.getKey() + ")", "(assets/" + entry.getKey() + ")");
            }
            String content = ch.markdown() ? "# " + ch.title() + "\n\n" + body + "\n" : body + "\n";
            Files.write(directory.resolve(filename), Epub.bytes(content));
        }
    }

    static byte[] limited(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("File exceeds its size limit.");
        return bytes;
    }

    static void deleteTree(Path dir) {
        if (dir == null || Files.notExists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    @FunctionalInterface
    interface Writer { void write(OutputStream output) throws IOException; }

    static void atomicWrite(Path destination, Writer writer) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) throw new IOException("The destination is a folder.");
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        var temp = Files.createTempFile(parent, ".quireloom-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) {
                writer.write(output);
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
