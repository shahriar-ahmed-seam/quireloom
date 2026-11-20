package io.quireloom.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Built-in EPUB Preflight Validator ("EPUB Doctor") and One-Click Auto-Fixer.
 */
final class EpubDoctor {
    enum Severity { PASS, INFO, WARNING, ERROR }

    record Finding(Severity severity, String category, String message, String detail) {}

    record Report(String targetName, List<Finding> findings, Map<String, String> stats) {
        long errors() { return findings.stream().filter(f -> f.severity() == Severity.ERROR).count(); }
        long warnings() { return findings.stream().filter(f -> f.severity() == Severity.WARNING).count(); }
        long passes() { return findings.stream().filter(f -> f.severity() == Severity.PASS).count(); }
        boolean healthy() { return errors() == 0; }
    }

    record RepairOptions(boolean upgradeToEpub3, boolean removeOrphans, boolean optimizeImages,
                         int maxImageDimension, float jpegQuality, boolean includeNcx) {
        static RepairOptions standard() {
            return new RepairOptions(true, true, false, 1600, 0.84f, true);
        }
    }

    static Report checkDraft(Draft draft) {
        var findings = new ArrayList<Finding>();
        var stats = new LinkedHashMap<String, String>();

        if (draft.title().isBlank()) {
            findings.add(new Finding(Severity.ERROR, "Metadata", "Book title is missing", "Set a title before exporting."));
        } else {
            findings.add(new Finding(Severity.PASS, "Metadata", "Book title is set", draft.title()));
        }

        if (draft.author().isBlank()) {
            findings.add(new Finding(Severity.WARNING, "Metadata", "Author name is blank", "Most e-readers sort libraries by author."));
        } else {
            findings.add(new Finding(Severity.PASS, "Metadata", "Author is set", draft.author()));
        }

        String lang = draft.language().isBlank() ? "en" : draft.language().strip();
        try {
            new Locale.Builder().setLanguageTag(lang);
            findings.add(new Finding(Severity.PASS, "Metadata", "Valid BCP-47 language tag", lang));
        } catch (IllformedLocaleException e) {
            findings.add(new Finding(Severity.ERROR, "Metadata", "Invalid language tag: " + lang, "Use tags like en, en-GB, bn, fr, ja."));
        }

        if (draft.metadata().description().isBlank()) {
            findings.add(new Finding(Severity.INFO, "Metadata", "No book description / blurb", "Add one under File / Book Metadata for store listings."));
        }

        // Cover diagnostics.
        if (draft.cover() == null) {
            findings.add(new Finding(Severity.WARNING, "Cover", "No cover image attached", "Use Cover Studio or Add Cover to create a bookshelf cover."));
        } else {
            checkImageDimensions("Cover image", draft.cover(), true, findings);
        }

        // Chapter diagnostics.
        if (draft.chapters().isEmpty()) {
            findings.add(new Finding(Severity.ERROR, "Structure", "Book has no chapters", "Add or import at least one chapter."));
        }
        int totalWords = 0;
        int totalImages = 0;
        int orphanImages = 0;
        var seenTitles = new HashMap<String, Integer>();
        for (int i = 0; i < draft.chapters().size(); i++) {
            var ch = draft.chapters().get(i);
            int num = i + 1;
            int words = Manuscript.wordCount(ch.body());
            totalWords += words;
            if (ch.title().isBlank()) {
                findings.add(new Finding(Severity.ERROR, "Chapters", "Chapter " + num + " has no title", "Every TOC entry requires a non-empty title."));
            } else {
                seenTitles.merge(ch.title().strip().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            if (ch.body().isBlank() && ch.role() != Epub.ChapterRole.PART) {
                findings.add(new Finding(Severity.WARNING, "Chapters", "Chapter " + num + " (\"" + ch.title() + "\") is empty", "Add text or mark as a Part divider."));
            }
            var referenced = ch.referencedImages();
            for (String key : referenced) {
                if (!ch.images().containsKey(key)) {
                    findings.add(new Finding(Severity.ERROR, "Images", "Missing image asset in \"" + ch.title() + "\"", key));
                }
            }
            for (var entry : ch.images().entrySet()) {
                totalImages++;
                if (!referenced.contains(entry.getKey())) {
                    orphanImages++;
                } else {
                    checkImageDimensions("Illustration in \"" + ch.title() + "\"", entry.getValue(), false, findings);
                }
            }
            // Check missing alt text on illustrations.
            var m = Epub.ILLUSTRATION.matcher(ch.body());
            while (m.find()) {
                if (m.group(1).isBlank()) {
                    findings.add(new Finding(Severity.INFO, "Accessibility",
                        "Illustration without alt description in \"" + ch.title() + "\"",
                        "Add descriptive alt text inside ![description](...) for screen readers."));
                }
            }
        }
        for (var e : seenTitles.entrySet()) {
            if (e.getValue() > 1) {
                findings.add(new Finding(Severity.WARNING, "Structure",
                    "Duplicate chapter title (\"" + e.getKey() + "\" appears " + e.getValue() + " times)",
                    "Distinct titles make the Table of Contents clearer."));
            }
        }
        if (orphanImages > 0) {
            findings.add(new Finding(Severity.INFO, "Optimization",
                orphanImages + " unreferenced image(s) will be cleaned automatically on save", ""));
        }

        int minutes = Math.max(1, (int) Math.round(totalWords / 250.0));
        stats.put("Chapters", String.valueOf(draft.chapters().size()));
        stats.put("Total words", String.format("%,d", totalWords));
        stats.put("Est. reading time", minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + " min");
        stats.put("Embedded images", String.valueOf(totalImages));
        stats.put("Theme", draft.options().theme().label);
        return new Report(draft.title().isBlank() ? "Untitled Draft" : draft.title(), findings, stats);
    }

    static Report checkEpub(Path path) {
        var findings = new ArrayList<Finding>();
        var stats = new LinkedHashMap<String, String>();
        String name = path.getFileName().toString();
        try {
            long fileSize = Files.size(path);
            stats.put("File size", formatBytes(fileSize));
            try (var zip = new ZipFile(path.toFile())) {
                // 1. Check mimetype is first entry and STORED uncompressed.
                var en = zip.entries();
                if (!en.hasMoreElements()) {
                    findings.add(new Finding(Severity.ERROR, "OCF", "Archive is empty", ""));
                    return new Report(name, findings, stats);
                }
                ZipEntry first = en.nextElement();
                if (!"mimetype".equals(first.getName())) {
                    findings.add(new Finding(Severity.ERROR, "OCF", "mimetype is not the first ZIP entry",
                        "Found \"" + first.getName() + "\" first. Run Auto-Fix to repair."));
                } else if (first.getMethod() != ZipEntry.STORED) {
                    findings.add(new Finding(Severity.ERROR, "OCF", "mimetype entry is compressed",
                        "EPUB OCF requires STORED (uncompressed) mimetype. Run Auto-Fix."));
                } else {
                    String content = new String(zip.getInputStream(first).readAllBytes(), StandardCharsets.US_ASCII);
                    if (!"application/epub+zip".equals(content)) {
                        findings.add(new Finding(Severity.ERROR, "OCF", "Invalid mimetype content", "Expected exact \"application/epub+zip\"."));
                    } else {
                        findings.add(new Finding(Severity.PASS, "OCF", "Valid uncompressed mimetype at start of archive", ""));
                    }
                }
            }

            // 2. Load full package structure & verify OPF/manifest/spine/XHTML.
            var arc = EpubArchive.load(path);
            stats.put("EPUB version", arc.epubVersion);
            stats.put("Title", arc.metadata.title().isBlank() ? "(none)" : arc.metadata.title());
            stats.put("Author", arc.metadata.author().isBlank() ? "(none)" : arc.metadata.author());
            stats.put("Language", arc.metadata.language().isBlank() ? "(none)" : arc.metadata.language());
            stats.put("Spine items", String.valueOf(arc.spine.size()));
            stats.put("Manifest items", String.valueOf(arc.manifest.size()));
            stats.put("TOC entries", String.valueOf(countToc(arc.toc)));

            if (!arc.epubVersion.startsWith("3")) {
                findings.add(new Finding(Severity.WARNING, "Version", "Legacy EPUB " + arc.epubVersion + " package",
                    "Can be upgraded cleanly to EPUB 3.3 using Auto-Fix."));
            } else {
                findings.add(new Finding(Severity.PASS, "Version", "EPUB " + arc.epubVersion + " package", ""));
            }

            if (arc.metadata.title().isBlank()) findings.add(new Finding(Severity.ERROR, "Metadata", "Missing dc:title", ""));
            if (arc.metadata.language().isBlank()) findings.add(new Finding(Severity.ERROR, "Metadata", "Missing dc:language", ""));
            if (arc.coverItemId == null) {
                findings.add(new Finding(Severity.WARNING, "Cover", "No cover-image declared in manifest", ""));
            } else {
                var ci = arc.manifest.get(arc.coverItemId);
                if (ci != null && arc.files.containsKey(ci.path)) {
                    checkImageDimensions("Cover (" + ci.path + ")", new Epub.Image(ci.mediaType, arc.files.get(ci.path)), true, findings);
                }
            }

            boolean hasNav = arc.manifest.values().stream().anyMatch(m -> m.hasProperty("nav"));
            if (!hasNav && arc.epubVersion.startsWith("3")) {
                findings.add(new Finding(Severity.ERROR, "Navigation", "EPUB 3 missing navigation document (properties=\"nav\")", "Run Auto-Fix to generate nav.xhtml."));
            } else if (hasNav) {
                findings.add(new Finding(Severity.PASS, "Navigation", "EPUB 3 navigation document present", ""));
            }

            // Check manifest files exist & find orphan files not in manifest.
            var declaredPaths = new HashSet<String>();
            declaredPaths.add("META-INF/container.xml");
            declaredPaths.add(arc.opfPath);
            int missingManifest = 0;
            for (var item : arc.manifest.values()) {
                declaredPaths.add(item.path);
                if (!arc.files.containsKey(item.path)) {
                    missingManifest++;
                    findings.add(new Finding(Severity.ERROR, "Manifest", "Missing archive file: " + item.path, "Declared as id=\"" + item.id + "\"."));
                }
            }
            if (missingManifest == 0) {
                findings.add(new Finding(Severity.PASS, "Manifest", "All " + arc.manifest.size() + " manifest items exist in archive", ""));
            }

            int orphans = 0;
            for (String p : arc.files.keySet()) {
                if (!declaredPaths.contains(p) && !p.startsWith("META-INF/")) orphans++;
            }
            if (orphans > 0) {
                findings.add(new Finding(Severity.WARNING, "Packaging",
                    orphans + " unmanifested orphan file(s) inside ZIP",
                    "Run Auto-Fix to strip orphan files."));
            }

            // Parse every XHTML spine document for XML validity, broken links, missing images, alt text & word count.
            var parser = BookFiles.newXmlParser();
            int xmlErrors = 0;
            int brokenLinks = 0;
            int missingAlts = 0;
            int totalWords = 0;
            for (var se : arc.spine) {
                var item = arc.manifest.get(se.idref);
                if (item == null || !item.isHtml() || !arc.files.containsKey(item.path)) continue;
                try {
                    byte[] raw = arc.files.get(item.path);
                    Document doc = parser.parse(new ByteArrayInputStream(BookFiles.injectHtmlEntitiesIfNeeded(raw)));
                    Element body = BookFiles.firstElementAnyNs(doc, "body");
                    if (body != null) totalWords += Manuscript.wordCount(body.getTextContent());

                    for (var img : BookFiles.elementsByLocalName(doc, "img")) {
                        String src = img.getAttribute("src").strip();
                        if (!img.hasAttribute("alt")) missingAlts++;
                        if (!src.isEmpty() && !src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("data:")) {
                            try {
                                String target = BookFiles.resolveHref(item.path, src);
                                if (!arc.files.containsKey(target)) {
                                    brokenLinks++;
                                    findings.add(new Finding(Severity.ERROR, "Broken Asset",
                                        "Missing image \"" + src + "\" in " + item.path, ""));
                                }
                            } catch (IOException ex) {
                                brokenLinks++;
                            }
                        }
                    }
                    for (var a : BookFiles.elementsByLocalName(doc, "a")) {
                        String href = a.getAttribute("href").strip();
                        if (href.isEmpty() || href.startsWith("#") || href.startsWith("http://")
                                || href.startsWith("https://") || href.startsWith("mailto:")) continue;
                        try {
                            String target = BookFiles.resolveHref(item.path, href);
                            if (!arc.files.containsKey(target)) {
                                brokenLinks++;
                                findings.add(new Finding(Severity.WARNING, "Broken Link",
                                    "Unresolved link \"" + href + "\" in " + item.path, ""));
                            }
                        } catch (IOException ex) {
                            brokenLinks++;
                        }
                    }
                } catch (Exception ex) {
                    xmlErrors++;
                    findings.add(new Finding(Severity.ERROR, "XHTML", "XML parse error in " + item.path, ex.getMessage()));
                }
            }
            if (xmlErrors == 0) findings.add(new Finding(Severity.PASS, "XHTML", "All spine XHTML chapters are well-formed XML", ""));
            if (brokenLinks == 0) findings.add(new Finding(Severity.PASS, "Links", "No broken internal links or missing images detected", ""));
            if (missingAlts > 0) {
                findings.add(new Finding(Severity.WARNING, "Accessibility",
                    missingAlts + " <img> element(s) missing alt attribute", "Run Auto-Fix to ensure valid alt attributes."));
            }
            // Check oversized images.
            long imageBytes = 0;
            int hugeImages = 0;
            for (var item : arc.manifest.values()) {
                if (item.isImage() && arc.files.containsKey(item.path)) {
                    int len = arc.files.get(item.path).length;
                    imageBytes += len;
                    if (len > 1_500_000) hugeImages++;
                }
            }
            if (hugeImages > 0) {
                findings.add(new Finding(Severity.WARNING, "Optimization",
                    hugeImages + " image(s) larger than 1.5 MB (" + formatBytes(imageBytes) + " total media)",
                    "Use Optimize Images in Auto-Fix or Package Studio for faster e-reader loading."));
            }
            stats.put("Total words", String.format("%,d", totalWords));
            int mins = Math.max(1, (int) Math.round(totalWords / 250.0));
            stats.put("Est. reading time", mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + " min");
        } catch (Exception error) {
            findings.add(new Finding(Severity.ERROR, "Archive", "Cannot inspect EPUB", error.getMessage()));
        }
        return new Report(name, findings, stats);
    }

    record RepairSummary(int orphansRemoved, int imagesOptimized, long bytesSaved, boolean upgradedFromEpub2) {}

    static RepairSummary repair(Path input, Path output, RepairOptions options) throws Exception {
        var arc = EpubArchive.load(input);
        boolean upgraded = !arc.epubVersion.startsWith("3");
        int orphansRemoved = 0;
        if (options.removeOrphans()) {
            var manifestPaths = new HashSet<String>();
            for (var item : arc.manifest.values()) manifestPaths.add(item.path);
            var toDrop = new ArrayList<String>();
            for (String p : arc.files.keySet()) {
                if (!manifestPaths.contains(p) && !p.startsWith("META-INF/") && !p.equals(arc.opfPath)) {
                    toDrop.add(p);
                }
            }
            for (String p : toDrop) {
                arc.files.remove(p);
                orphansRemoved++;
            }
        }
        int optImages = 0;
        long bytesSaved = 0;
        if (options.optimizeImages()) {
            var res = arc.optimizeAllImages(options.maxImageDimension(), options.jpegQuality());
            optImages = res.imagesOptimized();
            bytesSaved = res.bytesSaved();
        }
        arc.saveAsEpub3(output, options.includeNcx());
        return new RepairSummary(orphansRemoved, optImages, bytesSaved, upgraded);
    }

    private static void checkImageDimensions(String label, Epub.Image image, boolean isCover, List<Finding> findings) {
        if (image.bytes().length > 2_000_000) {
            findings.add(new Finding(Severity.WARNING, "Optimization",
                label + " is large (" + formatBytes(image.bytes().length) + ")",
                "Consider optimizing under 2 MB for responsive e-ink rendering."));
        }
        try {
            var img = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (img == null) return;
            int w = img.getWidth();
            int h = img.getHeight();
            if (isCover) {
                if (w < 600 || h < 900) {
                    findings.add(new Finding(Severity.WARNING, "Cover",
                        "Cover resolution is low (" + w + "×" + h + ")",
                        "Recommended eBook cover size is at least 1200×1800 (e.g. 1600×2560)."));
                } else {
                    findings.add(new Finding(Severity.PASS, "Cover", "Crisp cover resolution (" + w + "×" + h + ")", ""));
                }
                double ratio = (double) h / w;
                if (ratio < 1.15 || ratio > 1.85) {
                    findings.add(new Finding(Severity.INFO, "Cover",
                        String.format("Unusual cover aspect ratio (%.2f:1)", ratio),
                        "Standard eBook covers are portrait around 1.5:1 to 1.6:1."));
                }
            }
        } catch (IOException ignored) {}
    }

    private static int countToc(List<EpubArchive.TocNode> nodes) {
        int c = 0;
        for (var n : nodes) c += 1 + countToc(n.children);
        return c;
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
