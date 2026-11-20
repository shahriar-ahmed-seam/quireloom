package io.quireloom.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Lossless in-memory EPUB 2 / EPUB 3 package representation.
 * Powers the native Package Studio editor, EPUB Merger, Splitter, and EPUB Doctor Auto-Fixer.
 */
final class EpubArchive {
    static final class ManifestItem {
        String id;
        String path; // archive-relative path
        String mediaType;
        String properties;

        ManifestItem(String id, String path, String mediaType, String properties) {
            this.id = id;
            this.path = path;
            this.mediaType = mediaType == null ? "" : mediaType;
            this.properties = properties == null ? "" : properties.strip();
        }

        boolean hasProperty(String prop) {
            return !properties.isEmpty() && Arrays.asList(properties.split("\\s+")).contains(prop);
        }

        boolean isHtml() {
            return "application/xhtml+xml".equals(mediaType) || "text/html".equals(mediaType);
        }

        boolean isCss() { return "text/css".equals(mediaType); }
        boolean isImage() { return mediaType.startsWith("image/"); }
        boolean isFont() { return mediaType.startsWith("font/") || mediaType.contains("font") || mediaType.contains("opentype"); }
        boolean isTextEditable() {
            return isHtml() || isCss() || mediaType.contains("xml") || mediaType.startsWith("text/")
                || "application/javascript".equals(mediaType) || "image/svg+xml".equals(mediaType);
        }
    }

    static final class SpineEntry {
        String idref;
        boolean linear;
        String properties;

        SpineEntry(String idref, boolean linear, String properties) {
            this.idref = idref;
            this.linear = linear;
            this.properties = properties == null ? "" : properties.strip();
        }
    }

    static final class TocNode {
        String label;
        String path; // archive-relative path (may include #fragment)
        final List<TocNode> children = new ArrayList<>();

        TocNode(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    String epubVersion = "3.0";
    String opfPath = "EPUB/book.opf";
    Epub.Metadata metadata = Epub.Metadata.basic("urn:uuid:" + UUID.randomUUID(), "Untitled", "", "en");
    final Map<String, byte[]> files = new LinkedHashMap<>();
    final Map<String, ManifestItem> manifest = new LinkedHashMap<>();
    final List<SpineEntry> spine = new ArrayList<>();
    final List<TocNode> toc = new ArrayList<>();
    String coverItemId;

    static EpubArchive load(Path path) throws Exception {
        var archive = new EpubArchive();
        try (var zip = new ZipFile(path.toFile())) {
            if (zip.size() > 16000) throw new IOException("EPUB contains too many entries.");
            String mime = new String(BookFiles.readEntry(zip, "mimetype", 128), StandardCharsets.US_ASCII).strip();
            if (!"application/epub+zip".equals(mime)) throw new IOException("File is not an EPUB archive.");
            BookFiles.checkDrm(zip);

            var parser = BookFiles.newXmlParser();
            var container = BookFiles.xml(zip, "META-INF/container.xml", parser, BookFiles.TEXT_LIMIT);
            var root = BookFiles.firstElement(container, BookFiles.CONTAINER_NS, "rootfile");
            if (root == null) throw new IOException("EPUB container.xml has no rootfile.");
            archive.opfPath = BookFiles.archivePath(root.getAttribute("full-path"));
            var opf = BookFiles.xml(zip, archive.opfPath, parser, BookFiles.TEXT_LIMIT);
            archive.epubVersion = opf.getDocumentElement().getAttribute("version");
            if (archive.epubVersion.isBlank()) archive.epubVersion = "3.0";
            archive.metadata = BookFiles.extractMetadata(opf);

            // Read all archive entries (up to draft limit).
            long total = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.equals("mimetype")) continue;
                byte[] data;
                try (var in = zip.getInputStream(entry)) {
                    data = BookFiles.limited(in, BookFiles.COVER_LIMIT * 2);
                }
                total += data.length;
                if (total > BookFiles.DRAFT_LIMIT) throw new IOException("EPUB exceeds 256 MB workspace limit.");
                archive.files.put(name, data);
            }

            // Parse manifest.
            for (var item : BookFiles.elementsByLocalName(opf, "item")) {
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                String mt = item.getAttribute("media-type");
                String props = item.getAttribute("properties");
                if (id.isBlank() || href.isBlank()) continue;
                try {
                    String resolved = BookFiles.resolveHref(archive.opfPath, href);
                    var mi = new ManifestItem(id, resolved, mt, props);
                    archive.manifest.put(id, mi);
                    if (mi.hasProperty("cover-image")) archive.coverItemId = id;
                } catch (IOException ignored) {}
            }
            if (archive.coverItemId == null) {
                for (var m : BookFiles.elementsByLocalName(opf, "meta")) {
                    if ("cover".equals(m.getAttribute("name"))) {
                        String cid = m.getAttribute("content").strip();
                        if (archive.manifest.containsKey(cid) && archive.manifest.get(cid).isImage()) {
                            archive.coverItemId = cid;
                        }
                    }
                }
            }

            // Parse spine.
            for (var ref : BookFiles.elementsByLocalName(opf, "itemref")) {
                String idref = ref.getAttribute("idref");
                if (!archive.manifest.containsKey(idref)) continue;
                boolean linear = !"no".equalsIgnoreCase(ref.getAttribute("linear"));
                archive.spine.add(new SpineEntry(idref, linear, ref.getAttribute("properties")));
            }

            // Parse TOC tree (EPUB 3 nav preferred, else EPUB 2 NCX).
            archive.toc.addAll(parseTocTree(archive, parser));
        }
        return archive;
    }

    private static List<TocNode> parseTocTree(EpubArchive archive, javax.xml.parsers.DocumentBuilder parser) {
        // 1. EPUB 3 nav.
        for (var item : archive.manifest.values()) {
            if (item.hasProperty("nav") && archive.files.containsKey(item.path)) {
                try {
                    byte[] raw = BookFiles.injectHtmlEntitiesIfNeeded(archive.files.get(item.path));
                    var doc = parser.parse(new ByteArrayInputStream(raw));
                    for (var nav : BookFiles.elementsByLocalName(doc, "nav")) {
                        String type = nav.getAttributeNS(BookFiles.EPUB_NS, "type");
                        if (type.isBlank()) type = nav.getAttribute("epub:type");
                        if (type.isBlank() || Arrays.asList(type.split("\\s+")).contains("toc")) {
                            Element ol = firstChildElement(nav, "ol");
                            if (ol != null) {
                                var nodes = parseNavOl(ol, item.path);
                                if (!nodes.isEmpty()) return nodes;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        // 2. EPUB 2 NCX.
        for (var item : archive.manifest.values()) {
            if ("application/x-dtbncx+xml".equals(item.mediaType) && archive.files.containsKey(item.path)) {
                try {
                    var doc = parser.parse(new ByteArrayInputStream(archive.files.get(item.path)));
                    var navMap = BookFiles.firstElementAnyNs(doc, "navMap");
                    if (navMap != null) {
                        var nodes = parseNcxPoints(navMap, item.path);
                        if (!nodes.isEmpty()) return nodes;
                    }
                } catch (Exception ignored) {}
            }
        }
        // 3. Fallback from spine titles.
        var fallback = new ArrayList<TocNode>();
        int idx = 0;
        for (var se : archive.spine) {
            var mi = archive.manifest.get(se.idref);
            if (mi == null || !mi.isHtml() || mi.hasProperty("nav")) continue;
            idx++;
            String label = archive.extractTitle(mi.path, "Chapter " + idx);
            fallback.add(new TocNode(label, mi.path));
        }
        return fallback;
    }

    private static List<TocNode> parseNavOl(Element ol, String navPath) {
        var result = new ArrayList<TocNode>();
        for (Node c = ol.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element li && "li".equalsIgnoreCase(li.getLocalName())) {
                Element link = firstChildElement(li, "a");
                Element span = link == null ? firstChildElement(li, "span") : null;
                String label = link != null ? link.getTextContent().replaceAll("\\s+", " ").strip()
                    : (span != null ? span.getTextContent().replaceAll("\\s+", " ").strip() : "");
                String target = "";
                if (link != null && !link.getAttribute("href").isBlank()) {
                    target = resolveWithFragment(navPath, link.getAttribute("href"));
                }
                var node = new TocNode(label.isEmpty() ? "Section" : label, target);
                Element subOl = firstChildElement(li, "ol");
                if (subOl != null) node.children.addAll(parseNavOl(subOl, navPath));
                if (!node.path.isEmpty() || !node.children.isEmpty()) result.add(node);
            }
        }
        return result;
    }

    private static List<TocNode> parseNcxPoints(Element parent, String ncxPath) {
        var result = new ArrayList<TocNode>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element np && "navPoint".equalsIgnoreCase(np.getLocalName())) {
                String label = "";
                String src = "";
                for (Node sub = np.getFirstChild(); sub != null; sub = sub.getNextSibling()) {
                    if (sub instanceof Element el) {
                        if ("navLabel".equalsIgnoreCase(el.getLocalName())) {
                            Element txt = firstChildElement(el, "text");
                            if (txt != null) label = txt.getTextContent().replaceAll("\\s+", " ").strip();
                        } else if ("content".equalsIgnoreCase(el.getLocalName())) {
                            src = resolveWithFragment(ncxPath, el.getAttribute("src"));
                        }
                    }
                }
                var node = new TocNode(label.isEmpty() ? "Section" : label, src);
                node.children.addAll(parseNcxPoints(np, ncxPath));
                if (!node.path.isEmpty() || !node.children.isEmpty()) result.add(node);
            }
        }
        return result;
    }

    private static String resolveWithFragment(String basePath, String href) {
        if (href == null || href.isBlank()) return "";
        int hash = href.indexOf('#');
        String filePart = hash >= 0 ? href.substring(0, hash) : href;
        String frag = hash >= 0 ? href.substring(hash) : "";
        try {
            String resolved = filePart.isEmpty() ? basePath : BookFiles.resolveHref(basePath, filePart);
            return resolved + frag;
        } catch (IOException ignored) {
            return "";
        }
    }

    private static Element firstChildElement(Element parent, String localName) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && localName.equalsIgnoreCase(el.getLocalName())) return el;
        }
        return null;
    }

    String extractTitle(String path, String fallback) {
        byte[] data = files.get(path);
        if (data == null) return fallback;
        try {
            var parser = BookFiles.newXmlParser();
            var doc = parser.parse(new ByteArrayInputStream(BookFiles.injectHtmlEntitiesIfNeeded(data)));
            Element h1 = BookFiles.firstElementAnyNs(doc, "h1");
            if (h1 != null) {
                String t = h1.getTextContent().replaceAll("\\s+", " ").strip();
                if (!t.isEmpty()) return t;
            }
            String title = BookFiles.firstTextAnyNs(doc, "title");
            if (!title.isEmpty() && !title.equalsIgnoreCase(metadata.title())) return title;
        } catch (Exception ignored) {}
        return fallback;
    }

    String getText(String path) {
        byte[] data = files.get(path);
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    void setText(String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    ManifestItem findManifestByPath(String path) {
        for (var item : manifest.values()) {
            if (item.path.equals(path)) return item;
        }
        return null;
    }

    String addFile(String path, byte[] data, String mediaType, boolean addToSpine) {
        files.put(path, data);
        var existing = findManifestByPath(path);
        if (existing != null) {
            existing.mediaType = mediaType;
            return existing.id;
        }
        String id = uniqueId("item");
        manifest.put(id, new ManifestItem(id, path, mediaType, ""));
        if (addToSpine) spine.add(new SpineEntry(id, true, ""));
        return id;
    }

    void removeFile(String path) {
        files.remove(path);
        String removedId = null;
        for (var it = manifest.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().path.equals(path)) {
                removedId = entry.getKey();
                it.remove();
            }
        }
        if (removedId != null) {
            String rid = removedId;
            spine.removeIf(s -> s.idref.equals(rid));
            if (rid.equals(coverItemId)) coverItemId = null;
        }
    }

    String uniqueId(String base) {
        String candidate = base;
        int n = 1;
        while (manifest.containsKey(candidate)) candidate = base + "-" + (++n);
        return candidate;
    }

    record ReplaceResult(int filesChanged, int totalReplacements, List<String> details) {}

    ReplaceResult findAndReplace(String query, String replacement, boolean regex, boolean caseSensitive, boolean includeCss) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (!regex) flags |= Pattern.LITERAL;
        Pattern pattern = Pattern.compile(query, flags);
        String rep = regex ? replacement : java.util.regex.Matcher.quoteReplacement(replacement);
        int filesChanged = 0;
        int total = 0;
        var details = new ArrayList<String>();
        for (var item : manifest.values()) {
            if (!(item.isHtml() || (includeCss && item.isCss()))) continue;
            byte[] raw = files.get(item.path);
            if (raw == null) continue;
            String content = new String(raw, StandardCharsets.UTF_8);
            var m = pattern.matcher(content);
            var sb = new StringBuilder();
            int count = 0;
            while (m.find()) {
                m.appendReplacement(sb, rep);
                count++;
            }
            if (count > 0) {
                m.appendTail(sb);
                files.put(item.path, sb.toString().getBytes(StandardCharsets.UTF_8));
                filesChanged++;
                total += count;
                details.add(item.path + " (" + count + ")");
            }
        }
        return new ReplaceResult(filesChanged, total, details);
    }

    record OptimizeResult(int imagesOptimized, long bytesSaved) {}

    OptimizeResult optimizeAllImages(int maxDimension, float quality) {
        int count = 0;
        long saved = 0;
        for (var item : manifest.values()) {
            if (!item.isImage()) continue;
            byte[] raw = files.get(item.path);
            if (raw == null) continue;
            try {
                var src = new Epub.Image(item.mediaType, raw);
                var opt = BookFiles.optimizeImage(src, maxDimension, quality);
                if (opt.bytes().length < raw.length) {
                    saved += raw.length - opt.bytes().length;
                    files.put(item.path, opt.bytes());
                    item.mediaType = opt.mime();
                    count++;
                }
            } catch (IOException ignored) {}
        }
        return new OptimizeResult(count, saved);
    }

    /**
     * Normalizes an XHTML document (especially from EPUB 2) to clean, strict EPUB 3.3 XHTML5 bytes.
     * Also returns detected manifest properties (svg, mathml, scripted).
     */
    record NormalizedXhtml(byte[] bytes, Set<String> detectedProperties) {}

    static NormalizedXhtml normalizeToEpub3Xhtml(byte[] raw, String fallbackTitle, String lang) {
        var props = new LinkedHashSet<String>();
        try {
            var parser = BookFiles.newXmlParser();
            var doc = parser.parse(new ByteArrayInputStream(BookFiles.injectHtmlEntitiesIfNeeded(raw)));
            Element root = doc.getDocumentElement();
            if (root != null) {
                if (root.getAttribute("lang").isBlank() && !lang.isBlank()) root.setAttribute("lang", lang);
                if (root.getAttribute("xml:lang").isBlank() && !lang.isBlank()) root.setAttribute("xml:lang", lang);

                // Ensure <head><title> exists and is non-empty (required by EPUB 3.3).
                Element head = BookFiles.firstElementAnyNs(doc, "head");
                if (head != null) {
                    // Remove deprecated http-equiv="Content-Type" meta tags that trigger EPUBCheck warnings.
                    var metas = BookFiles.elementsByLocalName(head, "meta");
                    for (var m : metas) {
                        if ("Content-Type".equalsIgnoreCase(m.getAttribute("http-equiv"))) {
                            m.getParentNode().removeChild(m);
                        }
                    }
                    Element titleEl = BookFiles.firstElementAnyNs(head, "title");
                    if (titleEl == null) {
                        titleEl = doc.createElementNS(BookFiles.XHTML, "title");
                        titleEl.setTextContent(fallbackTitle == null || fallbackTitle.isBlank() ? "Chapter" : fallbackTitle);
                        head.appendChild(titleEl);
                    } else if (titleEl.getTextContent().isBlank()) {
                        titleEl.setTextContent(fallbackTitle == null || fallbackTitle.isBlank() ? "Chapter" : fallbackTitle);
                    }
                }

                // Upgrade <a name="foo"> to id="foo" and clean obsolete attributes for EPUB 3.3 validity.
                cleanLegacyNodes(root, props);

                // Ensure clean serialization with XML declaration + <!DOCTYPE html>.
                var tf = TransformerFactory.newInstance();
                var transformer = tf.newTransformer();
                transformer.setOutputProperty(OutputKeys.METHOD, "xml");
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                var writer = new StringWriter();
                transformer.transform(new DOMSource(doc), new StreamResult(writer));
                String bodyXml = writer.toString();
                // Ensure XHTML namespace is present on root <html>.
                if (!bodyXml.contains("http://www.w3.org/1999/xhtml")) {
                    bodyXml = bodyXml.replaceFirst("<html\\b", "<html xmlns=\"http://www.w3.org/1999/xhtml\"");
                }
                String full = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" + bodyXml;
                return new NormalizedXhtml(full.getBytes(StandardCharsets.UTF_8), props);
            }
        } catch (Exception ignored) {
            // Keep original bytes if already valid or non-XML HTML.
        }
        return new NormalizedXhtml(raw, props);
    }

    private static void cleanLegacyNodes(Element el, Set<String> props) {
        String local = el.getLocalName() == null ? el.getTagName().toLowerCase(Locale.ROOT) : el.getLocalName().toLowerCase(Locale.ROOT);
        String ns = el.getNamespaceURI();
        if ("svg".equals(local) || "http://www.w3.org/2000/svg".equals(ns)) props.add("svg");
        if ("math".equals(local) || "http://www.w3.org/1998/Math/MathML".equals(ns)) props.add("mathml");
        if ("script".equals(local)) props.add("scripted");
        if ("a".equals(local) && el.hasAttribute("name")) {
            String name = el.getAttribute("name").strip();
            if (!name.isEmpty() && !el.hasAttribute("id")) el.setAttribute("id", name);
            el.removeAttribute("name");
        }
        // Strip legacy HTML4 presentational attributes that EPUBCheck 5.2 flags as errors in EPUB 3.3.
        if (el.hasAttribute("border") && "img".equals(local)) el.removeAttribute("border");
        if (el.hasAttribute("align") && !"col".equals(local)) el.removeAttribute("align");
        if (el.hasAttribute("clear") && "br".equals(local)) el.removeAttribute("clear");
        if (el.hasAttribute("type") && ("ul".equals(local) || "li".equals(local))) el.removeAttribute("type");
        if ("img".equals(local) && !el.hasAttribute("alt")) el.setAttribute("alt", "");

        var children = new ArrayList<Node>();
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) children.add(c);
        for (Node c : children) {
            if (c instanceof Element childEl) cleanLegacyNodes(childEl, props);
        }
    }

    /**
     * Writes this archive back as a valid EPUB 3.3 package atomically.
     */
    void saveAsEpub3(Path destination, boolean includeNcx) throws IOException {
        String lang = metadata.language().isBlank() ? "en" : metadata.language().strip();
        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        String navPath = null;
        for (var item : manifest.values()) {
            if (item.hasProperty("nav")) { navPath = item.path; break; }
        }
        boolean generateNav = navPath == null || !files.containsKey(navPath);
        if (generateNav) navPath = opfDir + "nav.xhtml";

        String finalNavPath = navPath;
        BookFiles.atomicWrite(destination, output -> {
            try (var zip = new ZipOutputStream(output)) {
                Epub.writeMimetype(zip);
                Epub.put(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + """
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="%s" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """.formatted(Epub.xml(opfPath)));

                var manifestXml = new StringBuilder();
                var written = new HashSet<String>();
                written.add("mimetype");
                written.add("META-INF/container.xml");
                written.add(opfPath);

                String navId = null;
                String ncxId = null;

                for (var item : manifest.values()) {
                    if ("application/x-dtbncx+xml".equals(item.mediaType)) {
                        if (!includeNcx) continue;
                        ncxId = item.id;
                    }
                    if (!files.containsKey(item.path)) continue;
                    byte[] payload = files.get(item.path);
                    String props = item.properties;
                    if (item.isHtml() && !item.hasProperty("nav")) {
                        var norm = normalizeToEpub3Xhtml(payload, metadata.title(), lang);
                        payload = norm.bytes();
                        props = mergeProperties(props, norm.detectedProperties());
                    }
                    if (item.id.equals(coverItemId)) {
                        props = mergeProperties(props, Set.of("cover-image"));
                    }
                    if (item.path.equals(finalNavPath)) {
                        navId = item.id;
                        props = mergeProperties(props, Set.of("nav"));
                    }
                    if (written.add(item.path)) {
                        Epub.put(zip, item.path, payload);
                    }
                    manifestXml.append("<item id=\"").append(Epub.xml(item.id))
                        .append("\" href=\"").append(Epub.xml(relativeHref(opfPath, item.path)))
                        .append("\" media-type=\"").append(Epub.xml(item.mediaType)).append('"');
                    if (!props.isBlank()) manifestXml.append(" properties=\"").append(Epub.xml(props)).append('"');
                    manifestXml.append("/>\n");
                }

                if (navId == null) {
                    navId = uniqueId("nav");
                    String navXml = buildNavXhtml(finalNavPath, lang, toc);
                    if (written.add(finalNavPath)) Epub.put(zip, finalNavPath, navXml);
                    manifestXml.append("<item id=\"").append(Epub.xml(navId))
                        .append("\" href=\"").append(Epub.xml(relativeHref(opfPath, finalNavPath)))
                        .append("\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
                }

                if (includeNcx && ncxId == null) {
                    ncxId = uniqueId("ncx");
                    String ncxPath = opfDir + "toc.ncx";
                    if (written.add(ncxPath)) {
                        Epub.put(zip, ncxPath, buildNcxXml(ncxPath, metadata.id(), metadata.title(), toc));
                    }
                    manifestXml.append("<item id=\"").append(Epub.xml(ncxId))
                        .append("\" href=\"").append(Epub.xml(relativeHref(opfPath, ncxPath)))
                        .append("\" media-type=\"application/x-dtbncx+xml\"/>\n");
                }

                var spineXml = new StringBuilder();
                for (var ref : spine) {
                    if (!manifest.containsKey(ref.idref)) continue;
                    var item = manifest.get(ref.idref);
                    if (!files.containsKey(item.path)) continue;
                    spineXml.append("<itemref idref=\"").append(Epub.xml(ref.idref)).append('"');
                    if (!ref.linear) spineXml.append(" linear=\"no\"");
                    if (!ref.properties.isBlank()) spineXml.append(" properties=\"").append(Epub.xml(ref.properties)).append('"');
                    spineXml.append("/>\n");
                }

                var metaXml = buildOpfMetadataXml(metadata, lang, includeNcx ? coverItemId : null);
                String spineAttr = (includeNcx && ncxId != null) ? " toc=\"" + Epub.xml(ncxId) + "\"" : "";
                String opfXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + String.format("""
                    <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"
                             version="3.0" unique-identifier="book-id">
                      <metadata>%s</metadata>
                      <manifest>%s</manifest>
                      <spine%s>%s</spine>
                    </package>
                    """, metaXml, manifestXml, spineAttr, spineXml);
                Epub.put(zip, opfPath, opfXml);
            }
        });
    }

    static String buildOpfMetadataXml(Epub.Metadata meta, String lang, String legacyCoverId) {
        var sb = new StringBuilder();
        String uid = meta.id().isBlank() ? "urn:uuid:" + UUID.randomUUID() : meta.id();
        String title = meta.title().isBlank() ? "Untitled" : meta.title().strip();
        sb.append("<dc:identifier id=\"book-id\">").append(Epub.xml(uid)).append("</dc:identifier>\n");
        sb.append("<dc:title id=\"main-title\">").append(Epub.xml(title)).append("</dc:title>\n");
        if (!meta.subtitle().isBlank()) {
            sb.append("<meta refines=\"#main-title\" property=\"title-type\">main</meta>\n");
            sb.append("<dc:title id=\"sub-title\">").append(Epub.xml(meta.subtitle().strip())).append("</dc:title>\n");
            sb.append("<meta refines=\"#sub-title\" property=\"title-type\">subtitle</meta>\n");
        }
        sb.append("<dc:language>").append(Epub.xml(lang)).append("</dc:language>\n");
        if (!meta.author().isBlank()) {
            sb.append("<dc:creator id=\"creator\">").append(Epub.xml(meta.author().strip())).append("</dc:creator>\n");
            sb.append("<meta refines=\"#creator\" property=\"role\" scheme=\"marc:relators\">aut</meta>\n");
        }
        if (!meta.description().isBlank()) sb.append("<dc:description>").append(Epub.xml(meta.description().strip())).append("</dc:description>\n");
        if (!meta.publisher().isBlank()) sb.append("<dc:publisher>").append(Epub.xml(meta.publisher().strip())).append("</dc:publisher>\n");
        if (!meta.rights().isBlank()) sb.append("<dc:rights>").append(Epub.xml(meta.rights().strip())).append("</dc:rights>\n");
        if (!meta.date().isBlank()) sb.append("<dc:date>").append(Epub.xml(meta.date().strip())).append("</dc:date>\n");
        if (!meta.subjects().isBlank()) {
            for (String s : meta.subjects().split("[,;]+")) {
                if (!s.isBlank()) sb.append("<dc:subject>").append(Epub.xml(s.strip())).append("</dc:subject>\n");
            }
        }
        if (!meta.series().isBlank()) {
            sb.append("<meta property=\"belongs-to-collection\" id=\"col\">").append(Epub.xml(meta.series().strip())).append("</meta>\n");
            sb.append("<meta refines=\"#col\" property=\"collection-type\">series</meta>\n");
            if (!meta.seriesIndex().isBlank()) {
                sb.append("<meta refines=\"#col\" property=\"group-position\">").append(Epub.xml(meta.seriesIndex().strip())).append("</meta>\n");
            }
        }
        sb.append("<meta property=\"dcterms:modified\">").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append("</meta>\n");
        if (legacyCoverId != null && !legacyCoverId.isBlank()) {
            sb.append("<meta name=\"cover\" content=\"").append(Epub.xml(legacyCoverId)).append("\"/>\n");
        }
        return sb.toString();
    }

    static String buildNavXhtml(String navPath, String lang, List<TocNode> nodes) {
        var sb = new StringBuilder();
        appendNavList(sb, navPath, nodes);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" + String.format("""
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="%s" xml:lang="%s">
              <head><title>Contents</title></head>
              <body>
                <nav epub:type="toc" id="toc">
                  <h1>Contents</h1>
                  %s
                </nav>
              </body>
            </html>
            """, Epub.xml(lang), Epub.xml(lang), sb);
    }

    private static void appendNavList(StringBuilder sb, String navPath, List<TocNode> nodes) {
        sb.append("<ol>");
        for (var node : nodes) {
            sb.append("<li>");
            if (node.path != null && !node.path.isBlank()) {
                sb.append("<a href=\"").append(Epub.xml(relativeHrefWithFragment(navPath, node.path)))
                    .append("\">").append(Epub.xml(node.label)).append("</a>");
            } else {
                sb.append("<span>").append(Epub.xml(node.label)).append("</span>");
            }
            if (!node.children.isEmpty()) appendNavList(sb, navPath, node.children);
            sb.append("</li>");
        }
        sb.append("</ol>");
    }

    static String buildNcxXml(String ncxPath, String uid, String title, List<TocNode> nodes) {
        var map = new StringBuilder();
        int[] order = {0};
        int depth = appendNcxList(map, ncxPath, nodes, order, 1);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + String.format("""
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
            """, Epub.xml(uid), Math.max(1, depth), Epub.xml(title), map);
    }

    private static int appendNcxList(StringBuilder sb, String ncxPath, List<TocNode> nodes, int[] order, int level) {
        int maxDepth = level;
        for (var node : nodes) {
            String target = firstReachablePath(node);
            if (target.isEmpty()) continue;
            order[0]++;
            sb.append("<navPoint id=\"np-").append(order[0]).append("\" playOrder=\"").append(order[0])
                .append("\"><navLabel><text>").append(Epub.xml(node.label))
                .append("</text></navLabel><content src=\"")
                .append(Epub.xml(relativeHrefWithFragment(ncxPath, target))).append("\"/>");
            if (!node.children.isEmpty()) {
                maxDepth = Math.max(maxDepth, appendNcxList(sb, ncxPath, node.children, order, level + 1));
            }
            sb.append("</navPoint>");
        }
        return maxDepth;
    }

    private static String firstReachablePath(TocNode node) {
        if (node.path != null && !node.path.isBlank()) return node.path;
        for (var c : node.children) {
            String p = firstReachablePath(c);
            if (!p.isEmpty()) return p;
        }
        return "";
    }

    static String relativeHref(String fromFile, String toFile) {
        String[] fromParts = fromFile.contains("/") ? fromFile.substring(0, fromFile.lastIndexOf('/')).split("/") : new String[0];
        String[] toParts = toFile.split("/");
        int common = 0;
        while (common < fromParts.length && common < toParts.length - 1 && fromParts[common].equals(toParts[common])) {
            common++;
        }
        var rel = new StringBuilder();
        for (int i = common; i < fromParts.length; i++) rel.append("../");
        for (int i = common; i < toParts.length; i++) {
            if (i > common) rel.append('/');
            rel.append(encodePathSegment(toParts[i]));
        }
        return rel.toString();
    }

    static String relativeHrefWithFragment(String fromFile, String toPathWithFrag) {
        int hash = toPathWithFrag.indexOf('#');
        String filePart = hash >= 0 ? toPathWithFrag.substring(0, hash) : toPathWithFrag;
        String frag = hash >= 0 ? toPathWithFrag.substring(hash) : "";
        return relativeHref(fromFile, filePart) + frag;
    }

    static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String mergeProperties(String existing, Set<String> extra) {
        var set = new LinkedHashSet<String>();
        if (existing != null && !existing.isBlank()) {
            for (String p : existing.split("\\s+")) if (!p.isBlank()) set.add(p);
        }
        set.addAll(extra);
        return String.join(" ", set);
    }
}
