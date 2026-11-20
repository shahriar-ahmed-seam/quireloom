package io.quireloom.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java, zero-Calibre lossless EPUB 2 & EPUB 3 Merger, Folder Builder (+ Watch), and EPUB Splitter.
 */
final class EpubMerger {
    private static final Set<String> SUPPORTED_EXT = Set.of(
        ".epub", ".md", ".markdown", ".txt", ".pdf", ".png", ".jpg", ".jpeg", ".gif", ".webp"
    );
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]\\n]*)\\]\\(([^\\s()\\[\\]]+)\\)");
    private static final Pattern HTML_REF = Pattern.compile("(?:href|src)\\s*=\\s*[\"']([^\"'#?]+)");
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*[\"']?([^\"')#?]+)[\"']?\\s*\\)");

    record SourceItem(
        Path path,
        String customTitle,
        boolean includeDivider,
        boolean rasterizePdf,
        Set<String> selectedSpineIds // null or empty = include all linear spine items
    ) {
        SourceItem(Path path) {
            this(path, "", true, false, null);
        }
    }

    record MergeConfig(
        Epub.Metadata metadata,
        Epub.Image cover,
        Epub.Options options,
        boolean inheritFirstCover
    ) {
        static MergeConfig basic(String title, String author, String language) {
            return new MergeConfig(
                Epub.Metadata.basic("urn:uuid:" + UUID.randomUUID(), title, author, language),
                null, Epub.Options.editorial(), true);
        }
    }

    /**
     * Losslessly merges any mix of EPUB 2, EPUB 3, Markdown, TXT, PDF, and image files/folders into a valid EPUB 3.3 book.
     */
    static void merge(List<SourceItem> sources, Path output, MergeConfig config) throws Exception {
        if (sources.isEmpty()) throw new IOException("Add at least one file or folder to build or merge.");
        Path resolvedOut = output.toAbsolutePath().normalize();
        var expanded = expandInputs(sources, resolvedOut);
        if (expanded.isEmpty()) throw new IOException("No supported books, manuscripts, images, or PDFs found.");

        Epub.Options opts = config.options() == null ? Epub.Options.editorial() : config.options();
        String lang = config.metadata().language().isBlank() ? "en" : config.metadata().language().strip();
        String title = config.metadata().title().isBlank() ? "Collected Volumes" : config.metadata().title().strip();
        Epub.Image cover = config.cover();

        var manifestXml = new StringBuilder();
        var spineXml = new StringBuilder();
        var tocRoots = new ArrayList<EpubArchive.TocNode>();
        var entries = new LinkedHashMap<String, byte[]>();
        int[] serial = {0};
        String[] firstReadingHref = {null};
        boolean hasSharedCss = false;

        // Pre-scan for inherited cover if user didn't pick one.
        if (cover == null && config.inheritFirstCover()) {
            for (var item : expanded) {
                if (ext(item.path()).equals(".epub")) {
                    try {
                        var arc = EpubArchive.load(item.path());
                        if (arc.coverItemId != null) {
                            var ci = arc.manifest.get(arc.coverItemId);
                            if (ci != null && arc.files.containsKey(ci.path)) {
                                cover = new Epub.Image(ci.mediaType, arc.files.get(ci.path));
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (cover != null) {
            String coverFile = "EPUB/" + cover.filename();
            entries.put(coverFile, cover.bytes());
            manifestXml.append("<item id=\"cover-img\" href=\"").append(cover.filename())
                .append("\" properties=\"cover-image\" media-type=\"").append(Epub.xml(cover.mime())).append("\"/>\n");
            String coverPage = wrapXhtml("Cover", lang, "book.css",
                "<section epub:type=\"cover\" class=\"cover\"><img src=\"" + cover.filename()
                    + "\" alt=\"Cover of " + Epub.xml(title) + "\"/></section>");
            entries.put("EPUB/cover.xhtml", coverPage.getBytes(StandardCharsets.UTF_8));
            manifestXml.append("<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n");
            spineXml.append("<itemref idref=\"cover\"/>\n");
            hasSharedCss = true;
        }

        int volIndex = 0;
        for (var source : expanded) {
            Path p = source.path();
            String e = ext(p);
            if (e.equals(".epub")) {
                volIndex++;
                var arc = EpubArchive.load(p);
                String volPrefix = "EPUB/vol-" + volIndex + "/";
                String volTitle = !source.customTitle().isBlank() ? source.customTitle()
                    : (!arc.metadata.title().isBlank() ? arc.metadata.title() : stem(p));

                String dividerPath = null;
                if (source.includeDivider() && expanded.size() > 1) {
                    hasSharedCss = true;
                    String divName = "vol-" + volIndex + "-title.xhtml";
                    dividerPath = "EPUB/" + divName;
                    String authorLine = arc.metadata.author().isBlank() ? ""
                        : "<p class=\"author\">" + Epub.xml(arc.metadata.author()) + "</p>";
                    String divHtml = wrapXhtml(volTitle, lang, "book.css",
                        "<section epub:type=\"part\" class=\"part-page\"><h1>" + Epub.xml(volTitle) + "</h1>" + authorLine + "</section>");
                    String divId = "m-" + (++serial[0]);
                    entries.put(dividerPath, divHtml.getBytes(StandardCharsets.UTF_8));
                    manifestXml.append("<item id=\"").append(divId).append("\" href=\"").append(divName)
                        .append("\" media-type=\"application/xhtml+xml\"/>\n");
                    spineXml.append("<itemref idref=\"").append(divId).append("\"/>\n");
                    if (firstReadingHref[0] == null) firstReadingHref[0] = dividerPath;
                }

                // Determine which spine items to include.
                var includedSpine = new ArrayList<EpubArchive.SpineEntry>();
                var includedPaths = new HashSet<String>();
                for (var se : arc.spine) {
                    var mi = arc.manifest.get(se.idref);
                    if (mi == null || !arc.files.containsKey(mi.path)) continue;
                    if (mi.hasProperty("nav")) continue;
                    if (source.selectedSpineIds() != null && !source.selectedSpineIds().isEmpty()
                            && !source.selectedSpineIds().contains(se.idref)) {
                        continue;
                    }
                    includedSpine.add(se);
                    includedPaths.add(mi.path);
                }
                if (includedSpine.isEmpty()) continue;

                // Map old manifest IDs to new unique IDs & paths under EPUB/vol-N/.
                var idMap = new HashMap<String, String>();
                var pathMap = new HashMap<String, String>();
                for (var mi : arc.manifest.values()) {
                    if ("application/x-dtbncx+xml".equals(mi.mediaType)) continue;
                    if (mi.hasProperty("nav")) continue;
                    if (!arc.files.containsKey(mi.path)) continue;
                    // If cherry-picking chapters, skip unchosen HTML chapters (keep shared CSS/images/fonts).
                    if (mi.isHtml() && !includedPaths.contains(mi.path)) continue;

                    String newArchivePath = volPrefix + mi.path;
                    String newId = "m-" + (++serial[0]);
                    byte[] payload = arc.files.get(mi.path);
                    String cleanedProps = stripProperties(mi.properties, Set.of("nav", "cover-image"));
                    if (mi.isHtml()) {
                        var norm = EpubArchive.normalizeToEpub3Xhtml(payload, volTitle, lang);
                        payload = norm.bytes();
                        cleanedProps = EpubArchive.mergeProperties(cleanedProps, norm.detectedProperties());
                    }
                    entries.put(newArchivePath, payload);
                    String relHref = EpubArchive.relativeHref("EPUB/book.opf", newArchivePath);
                    manifestXml.append("<item id=\"").append(newId).append("\" href=\"").append(Epub.xml(relHref))
                        .append("\" media-type=\"").append(Epub.xml(mi.mediaType)).append('"');
                    if (!cleanedProps.isBlank()) manifestXml.append(" properties=\"").append(Epub.xml(cleanedProps)).append('"');
                    manifestXml.append("/>\n");
                    idMap.put(mi.id, newId);
                    pathMap.put(mi.path, newArchivePath);
                }

                String firstVolChapter = null;
                for (var se : includedSpine) {
                    String newId = idMap.get(se.idref);
                    if (newId == null) continue;
                    String newPath = pathMap.get(arc.manifest.get(se.idref).path);
                    if (firstVolChapter == null) firstVolChapter = newPath;
                    if (firstReadingHref[0] == null) firstReadingHref[0] = newPath;
                    spineXml.append("<itemref idref=\"").append(newId).append('"');
                    if (!se.linear) spineXml.append(" linear=\"no\"");
                    if (!se.properties.isBlank()) spineXml.append(" properties=\"").append(Epub.xml(se.properties)).append('"');
                    spineXml.append("/>\n");
                }

                // Remap TOC tree cleanly under the volume node!
                var mappedChildren = remapToc(arc.toc, pathMap);
                if (mappedChildren.isEmpty()) {
                    int idx = 0;
                    for (var se : includedSpine) {
                        var mi = arc.manifest.get(se.idref);
                        String newPath = pathMap.get(mi.path);
                        if (newPath != null) {
                            idx++;
                            mappedChildren.add(new EpubArchive.TocNode(arc.extractTitle(mi.path, "Chapter " + idx), newPath));
                        }
                    }
                }
                if (expanded.size() > 1) {
                    String volTarget = dividerPath != null ? dividerPath : (firstVolChapter != null ? firstVolChapter : "");
                    var volNode = new EpubArchive.TocNode(volTitle, volTarget);
                    volNode.children.addAll(mappedChildren);
                    tocRoots.add(volNode);
                } else {
                    tocRoots.addAll(mappedChildren);
                }
            } else if (e.equals(".md") || e.equals(".markdown") || e.equals(".txt")) {
                hasSharedCss = true;
                String rawText = BookFiles.readText(p);
                Epub.Chapter ch = Manuscript.fromFile(p.getFileName().toString(), rawText);
                String chTitle = !source.customTitle().isBlank() ? source.customTitle() : ch.title();
                // Resolve local relative Markdown images inside the source folder!
                String body = ch.body();
                var images = new LinkedHashMap<String, Epub.Image>();
                if (ch.markdown()) {
                    body = embedLocalMarkdownImages(body, p.getParent(), images);
                }
                var ready = new Epub.Chapter(chTitle, body, ch.markdown(), images);
                addRenderedChapter(ready, lang, opts, entries, manifestXml, spineXml, tocRoots, serial, firstReadingHref);
            } else if (e.equals(".pdf")) {
                hasSharedCss = true;
                var pdfImported = BookFiles.readPdf(p, source.rasterizePdf());
                String pdfTitle = !source.customTitle().isBlank() ? source.customTitle() : stem(p);
                var groupNode = pdfImported.chapters().size() > 1 ? new EpubArchive.TocNode(pdfTitle, "") : null;
                for (var ch : pdfImported.chapters()) {
                    var targetList = groupNode != null ? groupNode.children : tocRoots;
                    addRenderedChapter(ch, lang, opts, entries, manifestXml, spineXml, targetList, serial, firstReadingHref);
                    if (groupNode != null && groupNode.path.isEmpty() && !groupNode.children.isEmpty()) {
                        groupNode.path = groupNode.children.get(0).path;
                    }
                }
                if (groupNode != null && !groupNode.children.isEmpty()) tocRoots.add(groupNode);
            } else if (Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp").contains(e)) {
                hasSharedCss = true;
                var img = BookFiles.readImage(p);
                String key = "img-" + UUID.randomUUID() + img.extension();
                String label = !source.customTitle().isBlank() ? source.customTitle() : stem(p);
                var ch = new Epub.Chapter(label, "![" + label + "](" + key + ")", false,
                    Map.of(key, img), Epub.ChapterRole.PLATE, true);
                addRenderedChapter(ch, lang, opts, entries, manifestXml, spineXml, tocRoots, serial, firstReadingHref);
            } else {
                throw new IOException("Unsupported input format: " + p.getFileName());
            }
        }

        if (tocRoots.isEmpty()) throw new IOException("No readable chapters were produced.");
        if (hasSharedCss) {
            entries.put("EPUB/book.css", Epub.stylesheet(opts).getBytes(StandardCharsets.UTF_8));
            manifestXml.insert(0, "<item id=\"css\" href=\"book.css\" media-type=\"text/css\"/>\n");
        }

        // Write nav.xhtml with landmarks.
        var navHtml = buildOmnibusNav(lang, tocRoots, cover != null, firstReadingHref[0]);
        entries.put("EPUB/nav.xhtml", navHtml.getBytes(StandardCharsets.UTF_8));
        manifestXml.insert(0, "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");

        String spineAttr = "";
        if (opts.includeNcx()) {
            String ncx = EpubArchive.buildNcxXml("EPUB/toc.ncx", config.metadata().id(), title, tocRoots);
            entries.put("EPUB/toc.ncx", ncx.getBytes(StandardCharsets.UTF_8));
            manifestXml.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
            spineAttr = " toc=\"ncx\"";
        }

        var finalMeta = new Epub.Metadata(
            config.metadata().id().isBlank() ? "urn:uuid:" + UUID.randomUUID() : config.metadata().id(),
            title, config.metadata().subtitle(), config.metadata().author(), lang,
            config.metadata().description(), config.metadata().publisher(), config.metadata().subjects(),
            config.metadata().series(), config.metadata().seriesIndex(), config.metadata().rights(), config.metadata().date());

        String opfXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + String.format("""
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"
                     version="3.0" unique-identifier="book-id">
              <metadata>%s</metadata>
              <manifest>%s</manifest>
              <spine%s>%s</spine>
            </package>
            """, EpubArchive.buildOpfMetadataXml(finalMeta, lang, cover != null && opts.includeNcx() ? "cover-img" : null),
                manifestXml, spineAttr, spineXml);

        BookFiles.atomicWrite(resolvedOut, dest -> {
            try (var zip = new ZipOutputStream(dest)) {
                Epub.writeMimetype(zip);
                Epub.put(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + """
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
                Epub.put(zip, "EPUB/book.opf", opfXml);
                for (var entry : entries.entrySet()) {
                    Epub.put(zip, entry.getKey(), entry.getValue());
                }
            }
        });
    }

    /**
     * Splits an existing EPUB into multiple valid, resource-pruned EPUB 3.3 books!
     */
    record SplitPart(String title, List<String> spineIdrefs) {}

    static List<Path> split(Path sourceEpub, Path outputDir, List<SplitPart> parts) throws Exception {
        if (parts.isEmpty()) throw new IOException("Define at least one output volume.");
        Files.createDirectories(outputDir);
        var arc = EpubArchive.load(sourceEpub);
        var written = new ArrayList<Path>();
        int idx = 0;
        for (var part : parts) {
            idx++;
            if (part.spineIdrefs().isEmpty()) continue;
            var neededPaths = new LinkedHashSet<String>();
            for (String idref : part.spineIdrefs()) {
                var mi = arc.manifest.get(idref);
                if (mi != null) traceReferencedPaths(arc, mi.path, neededPaths);
            }
            // Keep cover image if present in original book.
            if (arc.coverItemId != null) {
                var ci = arc.manifest.get(arc.coverItemId);
                if (ci != null) neededPaths.add(ci.path);
            }

            var sub = new EpubArchive();
            sub.opfPath = "EPUB/book.opf";
            sub.metadata = new Epub.Metadata(
                "urn:uuid:" + UUID.randomUUID(),
                part.title().isBlank() ? arc.metadata.title() + " — Part " + idx : part.title().strip(),
                arc.metadata.subtitle(), arc.metadata.author(),
                arc.metadata.language().isBlank() ? "en" : arc.metadata.language(),
                arc.metadata.description(), arc.metadata.publisher(), arc.metadata.subjects(),
                arc.metadata.title(), String.valueOf(idx), arc.metadata.rights(), arc.metadata.date());

            var includedHtmlPaths = new HashSet<String>();
            for (String idref : part.spineIdrefs()) {
                var mi = arc.manifest.get(idref);
                if (mi != null) includedHtmlPaths.add(mi.path);
            }

            for (var mi : arc.manifest.values()) {
                if (!neededPaths.contains(mi.path)) continue;
                if (!arc.files.containsKey(mi.path)) continue;
                if (mi.hasProperty("nav") || "application/x-dtbncx+xml".equals(mi.mediaType)) continue;
                String newPath = mi.path.startsWith("EPUB/") ? mi.path : "EPUB/" + mi.path;
                sub.files.put(newPath, arc.files.get(mi.path));
                sub.manifest.put(mi.id, new EpubArchive.ManifestItem(mi.id, newPath, mi.mediaType,
                    stripProperties(mi.properties, Set.of("nav"))));
                if (mi.id.equals(arc.coverItemId)) sub.coverItemId = mi.id;
            }
            for (var se : arc.spine) {
                if (part.spineIdrefs().contains(se.idref) && sub.manifest.containsKey(se.idref)) {
                    sub.spine.add(new EpubArchive.SpineEntry(se.idref, se.linear, se.properties));
                }
            }
            var pathRemap = new HashMap<String, String>();
            for (String p : includedHtmlPaths) pathRemap.put(p, p.startsWith("EPUB/") ? p : "EPUB/" + p);
            sub.toc.addAll(remapToc(arc.toc, pathRemap));
            if (sub.toc.isEmpty()) {
                int c = 0;
                for (var se : sub.spine) {
                    var mi = sub.manifest.get(se.idref);
                    if (mi != null && mi.isHtml()) {
                        c++;
                        sub.toc.add(new EpubArchive.TocNode(sub.extractTitle(mi.path, "Chapter " + c), mi.path));
                    }
                }
            }

            String safeName = sub.metadata.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").strip();
            if (safeName.isEmpty()) safeName = "part-" + idx;
            if (safeName.length() > 64) safeName = safeName.substring(0, 64);
            Path outFile = outputDir.resolve(String.format(Locale.ROOT, "%02d-%s.epub", idx, safeName));
            sub.saveAsEpub3(outFile, true);
            written.add(outFile);
        }
        return written;
    }

    static List<SplitPart> autoSplitByParts(EpubArchive arc) {
        var spinePaths = new ArrayList<String>();
        var pathToIdref = new LinkedHashMap<String, String>();
        for (var se : arc.spine) {
            var mi = arc.manifest.get(se.idref);
            if (mi != null && mi.isHtml() && !mi.hasProperty("nav")) {
                spinePaths.add(mi.path);
                pathToIdref.putIfAbsent(mi.path, se.idref);
            }
        }
        if (arc.toc.size() < 2) {
            // Fall back to splitting individual spine items.
            var single = new ArrayList<SplitPart>();
            int i = 0;
            for (String p : spinePaths) {
                i++;
                single.add(new SplitPart(arc.extractTitle(p, "Part " + i), List.of(pathToIdref.get(p))));
            }
            return single;
        }
        // Group spine ranges starting at each top-level TOC entry.
        record Anchor(int spineIndex, String label) {}
        var anchors = new ArrayList<Anchor>();
        for (var node : arc.toc) {
            String target = BookFiles.stripFragment(node.path == null ? "" : node.path);
            int idx = spinePaths.indexOf(target);
            if (idx >= 0) anchors.add(new Anchor(idx, node.label));
        }
        anchors.sort(Comparator.comparingInt(Anchor::spineIndex));
        // Deduplicate same spineIndex.
        var dedup = new ArrayList<Anchor>();
        for (var a : anchors) {
            if (dedup.isEmpty() || dedup.get(dedup.size() - 1).spineIndex() != a.spineIndex()) dedup.add(a);
        }
        var parts = new ArrayList<SplitPart>();
        for (int i = 0; i < dedup.size(); i++) {
            int start = dedup.get(i).spineIndex();
            int end = (i + 1 < dedup.size()) ? dedup.get(i + 1).spineIndex() : spinePaths.size();
            var ids = new ArrayList<String>();
            for (int k = start; k < end; k++) ids.add(pathToIdref.get(spinePaths.get(k)));
            parts.add(new SplitPart(dedup.get(i).label(), ids));
        }
        return parts;
    }

    static List<SplitPart> autoSplitByChunkSize(EpubArchive arc, int chaptersPerBook) {
        int size = Math.max(1, chaptersPerBook);
        var validIds = new ArrayList<String>();
        for (var se : arc.spine) {
            var mi = arc.manifest.get(se.idref);
            if (mi != null && mi.isHtml() && !mi.hasProperty("nav")) validIds.add(se.idref);
        }
        var parts = new ArrayList<SplitPart>();
        int partNum = 0;
        for (int i = 0; i < validIds.size(); i += size) {
            partNum++;
            var slice = validIds.subList(i, Math.min(validIds.size(), i + size));
            parts.add(new SplitPart(arc.metadata.title() + " — Volume " + partNum, List.copyOf(slice)));
        }
        return parts;
    }

    /**
     * Watches input folders/files using native Java WatchService and rebuilds automatically on change!
     */
    static void watchAndBuild(List<SourceItem> sources, Path output, MergeConfig config,
                              Consumer<String> logger) throws Exception {
        merge(sources, output, config);
        logger.accept("Built " + output);
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            var watchedDirs = new HashSet<Path>();
            for (var s : sources) {
                Path p = s.path().toAbsolutePath().normalize();
                if (Files.isDirectory(p)) {
                    try (var walk = Files.walk(p)) {
                        walk.filter(Files::isDirectory).forEach(watchedDirs::add);
                    }
                } else if (p.getParent() != null) {
                    watchedDirs.add(p.getParent());
                }
            }
            for (Path dir : watchedDirs) {
                dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            }
            logger.accept("Watching " + watchedDirs.size() + " folder(s) for changes (Ctrl+C to stop)...");
            Path outNorm = output.toAbsolutePath().normalize();
            while (true) {
                var key = watcher.take();
                boolean relevant = false;
                Path dir = (Path) key.watchable();
                for (var ev : key.pollEvents()) {
                    if (ev.context() instanceof Path changed) {
                        Path full = dir.resolve(changed).toAbsolutePath().normalize();
                        if (!full.equals(outNorm) && !changed.toString().startsWith(".quireloom-")) {
                            relevant = true;
                        }
                    }
                }
                key.reset();
                if (relevant) {
                    Thread.sleep(200); // Debounce rapid editor writes.
                    try {
                        merge(sources, output, config);
                        logger.accept("Rebuilt " + output);
                    } catch (Exception error) {
                        logger.accept("Build error: " + error.getMessage());
                    }
                }
            }
        }
    }

    private static void traceReferencedPaths(EpubArchive arc, String path, Set<String> visited) {
        if (!visited.add(path)) return;
        byte[] data = arc.files.get(path);
        if (data == null) return;
        var mi = arc.findManifestByPath(path);
        if (mi == null) return;
        if (mi.isHtml() || mi.isCss() || "image/svg+xml".equals(mi.mediaType)) {
            String text = new String(data, StandardCharsets.UTF_8);
            var m = (mi.isCss() ? CSS_URL : HTML_REF).matcher(text);
            while (m.find()) {
                String ref = m.group(1).strip();
                if (ref.isEmpty() || ref.startsWith("http://") || ref.startsWith("https://")
                        || ref.startsWith("mailto:") || ref.startsWith("data:")) continue;
                try {
                    String target = BookFiles.resolveHref(path, ref);
                    if (arc.files.containsKey(target)) {
                        var targetMi = arc.findManifestByPath(target);
                        // Don't pull in unrelated HTML chapters via cross-links!
                        if (targetMi == null || !targetMi.isHtml()) {
                            traceReferencedPaths(arc, target, visited);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
    }

    private static List<SourceItem> expandInputs(List<SourceItem> sources, Path output) throws IOException {
        var result = new ArrayList<SourceItem>();
        for (var src : sources) {
            Path p = src.path().toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                List<Path> children;
                try (var stream = Files.list(p)) {
                    children = stream.filter(Files::isRegularFile)
                        .filter(f -> SUPPORTED_EXT.contains(ext(f)) && !f.toAbsolutePath().normalize().equals(output))
                        .sorted((a, b) -> Manuscript.compareNames(a.getFileName().toString(), b.getFileName().toString()))
                        .collect(java.util.stream.Collectors.toList());
                }
                for (Path child : children) {
                    result.add(new SourceItem(child, "", src.includeDivider(), src.rasterizePdf(), null));
                }
            } else {
                if (p.equals(output)) throw new IOException("Select an output file different from every input.");
                if (Files.notExists(p)) throw new IOException("Input file not found: " + p);
                result.add(src);
            }
        }
        return result;
    }

    private static String embedLocalMarkdownImages(String body, Path baseDir, Map<String, Epub.Image> images) throws IOException {
        if (baseDir == null) return body;
        Path root = baseDir.toAbsolutePath().normalize();
        var matcher = MD_IMAGE.matcher(body);
        var out = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String ref = matcher.group(2);
            if (ref.matches("(?i)https?://.+|data:.+") || Epub.ILLUSTRATION.matcher(matcher.group(0)).matches()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            Path asset = root.resolve(ref).normalize();
            if (!asset.startsWith(root)) throw new IOException("Inline image escapes project folder: " + ref);
            if (Files.notExists(asset)) throw new IOException("Inline image not found: " + ref);
            var img = BookFiles.readImage(asset);
            String key = "img-" + UUID.randomUUID() + img.extension();
            images.put(key, img);
            matcher.appendReplacement(out, Matcher.quoteReplacement("\n\n![" + alt + "](" + key + ")\n\n"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void addRenderedChapter(Epub.Chapter ch, String lang, Epub.Options opts,
                                           Map<String, byte[]> entries, StringBuilder manifestXml,
                                           StringBuilder spineXml, List<EpubArchive.TocNode> toc,
                                           int[] serial, String[] firstReadingHref) {
        for (var entry : ch.images().entrySet()) {
            String path = "EPUB/" + entry.getKey();
            if (!entries.containsKey(path)) {
                entries.put(path, entry.getValue().bytes());
                String imgId = "img-" + (++serial[0]);
                manifestXml.append("<item id=\"").append(imgId).append("\" href=\"").append(Epub.xml(entry.getKey()))
                    .append("\" media-type=\"").append(Epub.xml(entry.getValue().mime())).append("\"/>\n");
            }
        }
        String pageId = "ch-" + (++serial[0]);
        String fileName = pageId + ".xhtml";
        String fullPath = "EPUB/" + fileName;
        String xhtml = wrapXhtml(ch.title(), lang, "book.css", Epub.render(ch, opts));
        entries.put(fullPath, xhtml.getBytes(StandardCharsets.UTF_8));
        manifestXml.append("<item id=\"").append(pageId).append("\" href=\"").append(fileName)
            .append("\" media-type=\"application/xhtml+xml\"/>\n");
        spineXml.append("<itemref idref=\"").append(pageId).append("\"/>\n");
        toc.add(new EpubArchive.TocNode(ch.title(), fullPath));
        if (firstReadingHref[0] == null) firstReadingHref[0] = fullPath;
    }

    private static List<EpubArchive.TocNode> remapToc(List<EpubArchive.TocNode> nodes, Map<String, String> pathMap) {
        var result = new ArrayList<EpubArchive.TocNode>();
        for (var node : nodes) {
            String mapped = "";
            if (node.path != null && !node.path.isBlank()) {
                int hash = node.path.indexOf('#');
                String base = hash >= 0 ? node.path.substring(0, hash) : node.path;
                String frag = hash >= 0 ? node.path.substring(hash) : "";
                String newBase = pathMap.get(base);
                if (newBase != null) mapped = newBase + frag;
            }
            var remappedChildren = remapToc(node.children, pathMap);
            if (!mapped.isEmpty() || !remappedChildren.isEmpty()) {
                var copy = new EpubArchive.TocNode(node.label, mapped);
                copy.children.addAll(remappedChildren);
                result.add(copy);
            }
        }
        return result;
    }

    private static String buildOmnibusNav(String lang, List<EpubArchive.TocNode> tocRoots,
                                          boolean hasCover, String startPath) {
        var sb = new StringBuilder();
        appendTocOl(sb, tocRoots);
        var landmarkItems = new StringBuilder();
        if (hasCover) landmarkItems.append("<li><a epub:type=\"cover\" href=\"cover.xhtml\">Cover</a></li>");
        if (startPath != null) {
            landmarkItems.append("<li><a epub:type=\"bodymatter\" href=\"")
                .append(Epub.xml(EpubArchive.relativeHref("EPUB/nav.xhtml", startPath)))
                .append("\">Begin Reading</a></li>");
        }
        String landmarks = landmarkItems.length() == 0 ? ""
            : "<nav epub:type=\"landmarks\" id=\"landmarks\" hidden=\"hidden\"><h2>Guide</h2><ol>" + landmarkItems + "</ol></nav>";
        return wrapXhtml("Contents", lang, null,
            "<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1>" + sb + "</nav>" + landmarks);
    }

    private static void appendTocOl(StringBuilder sb, List<EpubArchive.TocNode> nodes) {
        sb.append("<ol>");
        for (var node : nodes) {
            sb.append("<li>");
            if (node.path != null && !node.path.isBlank()) {
                sb.append("<a href=\"").append(Epub.xml(EpubArchive.relativeHrefWithFragment("EPUB/nav.xhtml", node.path)))
                    .append("\">").append(Epub.xml(node.label)).append("</a>");
            } else {
                sb.append("<span>").append(Epub.xml(node.label)).append("</span>");
            }
            if (!node.children.isEmpty()) appendTocOl(sb, node.children);
            sb.append("</li>");
        }
        sb.append("</ol>");
    }

    private static String wrapXhtml(String title, String lang, String cssHref, String body) {
        String css = cssHref == null ? "" : "<link rel=\"stylesheet\" type=\"text/css\" href=\"" + cssHref + "\"/>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" + String.format("""
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="%s" xml:lang="%s">
              <head><title>%s</title>%s</head>
              <body>%s</body>
            </html>
            """, Epub.xml(lang), Epub.xml(lang), Epub.xml(title), css, body);
    }

    private static String stripProperties(String props, Set<String> drop) {
        if (props == null || props.isBlank()) return "";
        var kept = new ArrayList<String>();
        for (String p : props.split("\\s+")) {
            if (!p.isBlank() && !drop.contains(p)) kept.add(p);
        }
        return String.join(" ", kept);
    }

    private static String ext(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String stem(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
