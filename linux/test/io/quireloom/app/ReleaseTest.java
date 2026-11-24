package io.quireloom.app;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ReleaseTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createDirectories(Path.of(args[0]));
        Path md = directory.resolve("Chapter 2.MD");
        Path txt = directory.resolve("Chapter 10.txt");
        Path epub = directory.resolve("Sample library.epub");
        Path broken = directory.resolve("Damaged.epub");
        Files.writeString(md, "\uFEFF# The Tidal Library #\r\n\r\n## Reading room ##\r\n"
            + "**Bold** and *quiet* and ***both*** and ~~old~~, ``a ` < b`` and \\*escaped\\*. "
            + "[**Visit**](https://example.org/পথ?a=1&b=2)[^fn1]\r\n"
            + "[Bad link](https://example.org/%oops)\r\n\r\n"
            + "- tea\r\n  - oolong\r\n- [x] maps\r\n\r\n"
            + "1. Open\r\n2. Read\r\n\r\n"
            + "| Ship | Port |\r\n| --- | ---: |\r\n| *Argo* | 42 |\r\n\r\n"
            + "> Line one of quote\r\n> Line two of quote\r\n\r\n"
            + "~~~xml\r\n<h1>Literal</h1>\r\n~~~\r\n---\r\n"
            + "Three scripts বাংলা 日本語 🙂.  \r\nSecond line.\r\n\r\n"
            + "[^fn1]: A **rich** EPUB 3 footnote.\r\n");
        Files.writeString(txt, "# Literal heading\rFirst & <second>\r\rA *literal* star and বই.");
        Files.writeString(broken, "This is only a text file.");
        fixtureEpub2(epub, "Text/Second%20page.xhtml");

        Path cover = directory.resolve("cover.png");
        var swatch = new BufferedImage(1200, 1800, BufferedImage.TYPE_INT_RGB);
        var graphics = swatch.createGraphics();
        graphics.setColor(new Color(0x285648));
        graphics.fillRect(0, 0, 1200, 1800);
        graphics.dispose();
        ImageIO.write(swatch, "png", cover.toFile());
        Path coverJpg = directory.resolve("cover.jpg");
        ImageIO.write(swatch, "jpeg", coverJpg.toFile());

        // 1. Markdown & TXT rendering tests (including nested formatting, tables, footnotes, nested lists, quotes, escapes).
        var m = BookFiles.read(md).chapters().get(0);
        var t = BookFiles.read(txt).chapters().get(0);
        check(m.markdown() && m.title().equals("The Tidal Library"), "Markdown BOM, CRLF, heading trim, uppercase extension");
        check(!t.markdown() && t.title().equals("Chapter 10"), "TXT title and literal mode");
        String markup = Epub.render(m);
        for (String fragment : List.of(
            "<strong>Bold</strong>", "<em>quiet</em>", "<strong><em>both</em></strong>", "<del>old</del>",
            "<code>a ` &lt; b</code>", "*escaped*", "<h3>Reading room</h3>",
            "<ul><li>tea<ul><li>oolong</li></ul></li>", "&#9745;</span> maps", "<ol><li>Open</li><li>Read</li></ol>",
            "<table><thead><tr><th>Ship</th><th style=\"text-align:right\">Port</th></tr></thead>",
            "<blockquote><p>Line one of quote Line two of quote</p></blockquote>",
            "<pre><code class=\"language-xml\">&lt;h1&gt;Literal&lt;/h1&gt;",
            "<hr/>", "বাংলা 日本語 🙂.<br/>",
            "https://example.org/%E0%A6%AA%E0%A6%A5?a=1&amp;b=2", "<strong>Visit</strong>", "Bad link",
            "epub:type=\"noteref\"", "epub:type=\"footnotes\"", "A <strong>rich</strong> EPUB 3 footnote.")) {
            check(markup.contains(fragment), "Markdown export includes: " + fragment);
        }
        check(!markup.contains("%oops"), "Malformed URI is omitted");
        check(!Manuscript.html("-*_", true).contains("<hr/>"), "Mixed rule chars rejected");
        String smart = Manuscript.html("\"Hello,\" she said --- wait... `\"code\"`", true, true, "<hr/>", false);
        check(smart.contains("\u201CHello,\u201D") && smart.contains("\u2014") && smart.contains("\u2026")
            && smart.contains("<code>&quot;code&quot;</code>"), "Smart typography preserves code spans");
        check(Epub.render(t).contains("# Literal heading<br/>First &amp; &lt;second&gt;")
            && Epub.render(t).contains("*literal*"), "TXT newlines and XML escaping");

        // 2. Rich EPUB 2 import test (metadata, spine order, entities, formatting preserved!).
        var e = BookFiles.read(epub);
        check(e.title().equals("Sample & Sons") && e.author().equals("N. লেখক") && e.language().equals("bn"), "EPUB 2 metadata");
        check(e.chapters().stream().map(Epub.Chapter::title).toList().equals(List.of("Second section", "First section")),
            "EPUB uses spine order and XHTML heading/title");
        check(e.chapters().get(0).body().contains("The tide") && e.chapters().get(0).body().contains("returned")
            && e.chapters().get(1).body().contains("Text & *tea*. Café\u00a0©.")
            && !e.chapters().get(1).body().contains("ignored"), "Rich EPUB import preserves emphasis and skips hidden/script");

        // 3. Paste splitting & polish tests.
        var pasted = Manuscript.fromPaste("Opening\n\n# Chapter 1\n~~~md\n# Code\n~~~\n# Chapter 2\nEnd", true, true);
        check(pasted.stream().map(Epub.Chapter::title).toList().equals(List.of("Introduction", "Chapter 1", "Chapter 2"))
            && pasted.get(1).body().contains("# Code"), "Paste splitting respects fenced code");
        check(Manuscript.fromPaste("Chapter I\nA\nঅধ্যায় ২\nB\nPrologue\nC", true, false).size() == 3, "Plain multilingual chapter detection");
        check(Manuscript.fromPaste("  \n\t", true, false).isEmpty(), "Empty paste");
        check(Manuscript.fromPaste("# Chapter 1\nbody", false, true).size() == 1, "Unsplit paste");
        check(Manuscript.polish("Hello   \n\n\n\n\"World\" -- test...", true).equals("Hello\n\n\u201CWorld\u201D \u2013 test\u2026"),
            "Manuscript polish cleans spaces and typography");

        // 4. Error handling & image verification.
        expectFailure(() -> BookFiles.read(broken), "Damaged EPUB");
        Path missing = directory.resolve("Missing-spine-file.epub");
        fixtureEpub2(missing, "Text/absent.xhtml");
        expectFailure(() -> BookFiles.read(missing), "Missing EPUB resource");
        Path invalid = directory.resolve("Invalid UTF-8.txt");
        Files.write(invalid, new byte[]{(byte) 0xc3, 0x28});
        expectFailure(() -> BookFiles.read(invalid), "Invalid UTF-8");
        Path oversized = directory.resolve("Too large.txt");
        Files.writeString(oversized, "x".repeat(BookFiles.TEXT_LIMIT + 1));
        expectFailure(() -> BookFiles.read(oversized), "Oversized TXT");
        expectFailure(() -> BookFiles.cover(txt), "Invalid cover");
        check(BookFiles.cover(cover).mime().equals("image/png") && BookFiles.cover(coverJpg).mime().equals("image/jpeg"),
            "PNG and JPEG covers");

        // 5. Draft persistence (extended metadata, options, roles, cover equality bug fix, orphan image pruning!).
        Path draftPath = directory.resolve("draft.ql");
        check(Draft.read(draftPath).chapters().isEmpty(), "First-run draft");
        var richMeta = new Epub.Metadata("urn:uuid:11111111-2222-3333-4444-555555555555",
            "The Paper & Tide", "Chronicles of the Bay", "A. লেখক", "en-GB",
            "An unforgettable maritime tale.", "Quire Press", "Fiction, Maritime",
            "Tidal Cycle", "1", "CC-BY 4.0", "2026-11-20");
        var richOpts = new Epub.Options(Epub.BookTheme.LITERARY, "❦", true, true, true, true, true, "p { orphans: 2; }");
        var partCh = new Epub.Chapter("Part I: Harbours", "Where the tide begins.", true, Map.of(), Epub.ChapterRole.PART, false);
        var orphanKey = "img-" + UUID.randomUUID() + ".png";
        var withOrphan = new Epub.Chapter("Tidal Chapter", m.body(), true, Map.of(orphanKey, BookFiles.cover(cover)));
        var draft = new Draft(richMeta, List.of(partCh, withOrphan, t), BookFiles.cover(cover), richOpts);
        draft.save(draftPath);
        Draft restored = Draft.read(draftPath);
        check(restored.equals(draft.pruned()), "Draft equals works WITH cover & extended metadata/options!");
        check(restored.chapters().get(1).images().isEmpty(), "Orphan unreferenced image pruned on save");
        check(restored.chapters().get(0).role() == Epub.ChapterRole.PART, "Chapter role persisted");
        check(restored.metadata().series().equals("Tidal Cycle") && restored.options().theme() == Epub.BookTheme.LITERARY,
            "Extended metadata and typography options persisted");

        Path corruptDraft = directory.resolve("corrupt.ql");
        Files.write(corruptDraft, new byte[]{1, 2, 3});
        expectFailure(() -> Draft.read(corruptDraft), "Corrupt draft fails visibly");

        // 6. Export rich EPUB 3.3 (title page, copyright, part TOC, landmarks, NCX, footnotes, tables, cover).
        Path output = directory.resolve("Export-with-PNG.epub");
        restored.export(output);
        try (var zip = new ZipFile(output.toFile())) {
            var entries = zip.entries();
            var mime = entries.nextElement();
            check(mime.getName().equals("mimetype") && mime.getMethod() == ZipEntry.STORED, "OCF first/mimetype storage");
            check(zip.getEntry("EPUB/cover.png") != null && zip.getEntry("EPUB/nav.xhtml") != null
                && zip.getEntry("EPUB/toc.ncx") != null && zip.getEntry("EPUB/titlepage.xhtml") != null
                && zip.getEntry("EPUB/copyright.xhtml") != null, "Cover, nav, NCX, titlepage & copyright packaged");
        }
        // Rich round-trip import preserves cover & extended metadata!
        var roundTrip = BookFiles.read(output);
        check(roundTrip.cover() != null && roundTrip.metadata().series().equals("Tidal Cycle")
            && roundTrip.metadata().subtitle().equals("Chronicles of the Bay"),
            "Rich EPUB round-trip restores cover, series & subtitle");

        // Export HTML Web Reader & Markdown Folder.
        Path htmlExport = directory.resolve("reader.html");
        restored.exportHtml(htmlExport);
        check(Files.readString(htmlExport).contains("The Paper &amp; Tide") && Files.readString(htmlExport).contains("data:image/png;base64,"),
            "Self-contained HTML web reader export works");
        Path mdExportDir = directory.resolve("md-export");
        BookFiles.exportMarkdownFolder(restored, mdExportDir);
        check(Files.exists(mdExportDir.resolve("assets/cover.png")), "Markdown folder export writes cover and chapters");

        // 7. Cover Designer Studio test (renders all 6 templates + exports EPUB with designed cover!).
        for (var tmpl : CoverDesigner.Template.values()) {
            var spec = new CoverDesigner.Spec(tmpl, CoverDesigner.PALETTES.get(0),
                "The Tidal Library", "A Maritime Anthology", "A. লেখক", "BOOK 1", "QUIRE PRESS", true, swatch);
            var designed = CoverDesigner.render(spec, 400, 640);
            check(designed.getWidth() == 400 && designed.getHeight() == 640, "Cover template renders: " + tmpl);
        }
        var designedCover = CoverDesigner.renderEpubImage(new CoverDesigner.Spec(
            CoverDesigner.Template.NOCTURNE, CoverDesigner.PALETTES.get(1),
            "Starlight Atlas", "Selected Works", "N. Writer", "VOLUME I", "QUIRELOOM", true, null));
        var jpg = new Draft(Epub.Metadata.basic("urn:uuid:22222222-2222-2222-2222-222222222222", "Starlight Atlas", "N. Writer", "fr"),
            List.of(t), designedCover, Epub.Options.defaults());
        Path designedEpub = directory.resolve("Export-designed-cover.epub");
        jpg.export(designedEpub);

        // 8. Atomic write safety & validation tests.
        byte[] before = Files.readAllBytes(output);
        expectFailure(() -> BookFiles.atomicWrite(output, out -> {
            out.write(10);
            throw new IOException("Test interrupted write");
        }), "Failed write preserves existing export");
        check(Arrays.equals(before, Files.readAllBytes(output)), "Atomic write left output unchanged");
        try (var paths = Files.list(directory)) {
            check(paths.noneMatch(file -> file.getFileName().toString().startsWith(".quireloom-")),
                "Failed write removed temporary file");
        }
        expectFailure(() -> draft.export(directory), "Export to directory rejected");
        expectFailure(() -> Draft.empty().export(output), "Missing metadata rejected");
        expectFailure(() -> new Draft("urn:uuid:1", "Book", "", "en_English", List.of(t), null).export(output),
            "Malformed language tag rejected");

        // 9. Pathological performance stress test (350k brackets & dashes + 46k paragraphs).
        String large = "[".repeat(350_000) + "\n\n" + "-".repeat(350_000) + "\n\n"
            + "A multilingual paragraph with **emphasis** and বাংলা.\n\n".repeat(46_000);
        Path largeFile = directory.resolve("Long manuscript.markdown");
        Files.writeString(largeFile, large);
        var longChapter = BookFiles.read(largeFile).chapters().get(0);
        var stress = new Draft("urn:uuid:33333333-3333-3333-3333-333333333333", "Large synthetic manuscript", "", "en", List.of(longChapter), null);
        stress.export(directory.resolve("Export-large.epub"));
        check(Epub.render(longChapter).contains("<hr/>"), "Large chapter and long punctuation runs stay fast");

        // 10. Illustrated chapters round-trip (including re-import of image-only plate chapter!).
        String key = "img-" + UUID.randomUUID() + ".png";
        var art = BookFiles.cover(cover);
        String marker = "![City & harbour](" + key + ")";
        var illustrated = new Epub.Chapter("Voyage", "Before\n\n" + marker + "\n\nAfter", true, Map.of(key, art));
        var plate = new Epub.Chapter("Plate", marker, false, Map.of(key, art), Epub.ChapterRole.PLATE, true);
        Path illustratedDraft = directory.resolve("illustrated.ql");
        new Draft("urn:uuid:44444444-4444-4444-4444-444444444444", "Illustrations", "", "en",
            List.of(illustrated, plate), null).save(illustratedDraft);
        var reopened = Draft.read(illustratedDraft);
        check(Arrays.equals(art.bytes(), reopened.chapters().get(1).images().get(key).bytes()), "Image chapter survives reopening");
        Path illustratedExport = directory.resolve("Export-illustrated.epub");
        reopened.export(illustratedExport);
        try (var archive = new ZipFile(illustratedExport.toFile())) {
            check(Arrays.equals(archive.getInputStream(archive.getEntry("EPUB/" + key)).readAllBytes(), art.bytes()), "Embedded image matches original");
            String page = new String(archive.getInputStream(archive.getEntry("EPUB/chapter-1.xhtml")).readAllBytes(), StandardCharsets.UTF_8);
            check(page.indexOf("Before") < page.indexOf("<img") && page.indexOf("<img") < page.indexOf("After"), "Image remains at insertion point");
            check(page.contains("City &amp; harbour"), "Illustration description is escaped");
        }
        var reimportedIllus = BookFiles.read(illustratedExport);
        check(reimportedIllus.chapters().size() == 2 && !reimportedIllus.chapters().get(0).images().isEmpty()
            && !reimportedIllus.chapters().get(1).images().isEmpty(),
            "Re-importing illustrated EPUB preserves inline images AND image-only plate chapters!");

        // 11. ZERO-CALIBRE Lossless EPUB 2 + EPUB 3 + Folder Merger test!
        Path folderBook = Files.createDirectories(directory.resolve("folder-book"));
        Files.createDirectories(folderBook.resolve("art"));
        Files.copy(cover, folderBook.resolve("art/map.png"));
        Files.writeString(folderBook.resolve("01-opening.md"), "# Map Chapter\n\nA local image:\n\n![Harbour Map](art/map.png)\n");
        Path omnibus = directory.resolve("Export-omnibus.epub");
        EpubMerger.merge(
            List.of(
                new EpubMerger.SourceItem(epub),              // EPUB 2 with XHTML 1.1 entities!
                new EpubMerger.SourceItem(illustratedExport), // EPUB 3 with embedded images!
                new EpubMerger.SourceItem(folderBook)         // Folder with Markdown + local subfolder image!
            ),
            omnibus,
            EpubMerger.MergeConfig.basic("Grand Collected Omnibus", "Various Authors", "en"));
        var omnibusDoctor = EpubDoctor.checkEpub(omnibus);
        check(omnibusDoctor.healthy(), "Merged EPUB 2+3+Folder omnibus passes EPUB Doctor: " + omnibusDoctor.findings());

        // 12. Smart Resource-Pruning EPUB Splitter test!
        Path splitDir = directory.resolve("split-out");
        var omnibusArc = EpubArchive.load(omnibus);
        var splitParts = EpubMerger.autoSplitByParts(omnibusArc);
        check(splitParts.size() >= 2, "Omnibus splits into top-level volumes (" + splitParts.size() + ")");
        var splitFiles = EpubMerger.split(omnibus, splitDir, splitParts);
        check(splitFiles.size() >= 2, "Splitter generated standalone EPUB files");
        for (Path sf : splitFiles) {
            check(EpubDoctor.checkEpub(sf).healthy(), "Split EPUB passes EPUB Doctor: " + sf.getFileName());
        }

        // 13. Native Lossless Package Studio (EpubArchive) editing + Find/Replace + Optimize + Doctor Auto-Fix!
        var studioArc = EpubArchive.load(epub);
        var repRes = studioArc.findAndReplace("returned", "surged back", false, true, false);
        check(repRes.totalReplacements() == 1, "Package Studio cross-file find/replace works");
        Path editedEpub = directory.resolve("Export-studio-edited.epub");
        studioArc.saveAsEpub3(editedEpub, true);
        check(BookFiles.read(editedEpub).chapters().get(0).body().contains("surged back"),
            "Package Studio edits persisted in valid EPUB 3.3");

        Path repairedEpub = directory.resolve("Export-repaired.epub");
        var repairSummary = EpubDoctor.repair(epub, repairedEpub,
            new EpubDoctor.RepairOptions(true, true, true, 1200, 0.82f, true));
        check(repairSummary.upgradedFromEpub2() && EpubDoctor.checkEpub(repairedEpub).healthy(),
            "EPUB Doctor Auto-Fix upgrades EPUB 2 cleanly to EPUB 3.3");

        // 14. CLI end-to-end check (check, info, build, split, fix, and .ql draft export/check/info).
        check(DesktopApp.runCli("check", new String[]{omnibus.toString()}) == 0, "CLI check succeeds on valid omnibus");
        check(DesktopApp.runCli("info", new String[]{omnibus.toString()}) == 0, "CLI info succeeds");
        check(DesktopApp.runCli("check", new String[]{draftPath.toString()}) == 0, "CLI check succeeds on .ql draft");
        check(DesktopApp.runCli("info", new String[]{draftPath.toString()}) == 0, "CLI info succeeds on .ql draft");
        Path cliExported = directory.resolve("Export-cli-ql.epub");
        check(DesktopApp.runCli("export", new String[]{draftPath.toString(), "-o", cliExported.toString()}) == 0
            && EpubDoctor.checkEpub(cliExported).healthy(), "CLI export .ql draft produces healthy EPUB");
        Path cliBuilt = directory.resolve("Export-cli-build.epub");
        check(DesktopApp.runCli("build", new String[]{folderBook.toString(), "--title", "CLI Folder", "-o", cliBuilt.toString()}) == 0
            && EpubDoctor.checkEpub(cliBuilt).healthy(), "CLI build from folder produces healthy EPUB");
        Path cliFixed = directory.resolve("Export-cli-fixed.epub");
        check(DesktopApp.runCli("fix", new String[]{epub.toString(), "-o", cliFixed.toString()}) == 0
            && EpubDoctor.checkEpub(cliFixed).healthy(), "CLI fix upgrades EPUB 2 cleanly");

        // 15. Regression checks for PART + BACKMATTER TOC, leading frontmatter split, Package delete/TOC rebuild, PNG optimization, and FilePickers root avoidance.
        var partBook = new Draft(
            Epub.Metadata.basic("urn:uuid:55555555-2222-3333-4444-555555555555", "Part & Backmatter", "Author", "en"),
            List.of(
                new Epub.Chapter("Preface", "Front.", true, Map.of(), Epub.ChapterRole.FRONTMATTER, false),
                new Epub.Chapter("Part I", "Intro", true, Map.of(), Epub.ChapterRole.PART, false),
                new Epub.Chapter("Chapter 1", "Body", true, Map.of(), Epub.ChapterRole.CHAPTER, false),
                new Epub.Chapter("Acknowledgments", "Thanks.", true, Map.of(), Epub.ChapterRole.BACKMATTER, false)
            ), null, Epub.Options.editorial());
        Path partEpub = directory.resolve("Export-parts-backmatter.epub");
        partBook.export(partEpub);
        try (var zip = new ZipFile(partEpub.toFile())) {
            String navXml = new String(zip.getInputStream(zip.getEntry("EPUB/nav.xhtml")).readAllBytes(), StandardCharsets.UTF_8);
            check(navXml.contains("</ol></li><li><a href=\"chapter-4.xhtml\">Acknowledgments</a>"),
                "BACKMATTER closes open PART in nav.xhtml");
            String ncxXml = new String(zip.getInputStream(zip.getEntry("EPUB/toc.ncx")).readAllBytes(), StandardCharsets.UTF_8);
            check(ncxXml.contains("</navPoint><navPoint id=\"np-4\"") && ncxXml.contains("Acknowledgments"),
                "BACKMATTER closes open PART in toc.ncx");
        }

        // Verify autoSplitByParts keeps leading frontmatter (Preface before Part I).
        Path leadingEpub = directory.resolve("Leading-split.epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(leadingEpub))) {
            entry(zip, "mimetype", "application/epub+zip");
            entry(zip, "META-INF/container.xml", """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""");
            entry(zip, "EPUB/book.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Split Test</dc:title><dc:language>en</dc:language>
                    <dc:identifier id="id">urn:uuid:66666666-2222-3333-4444-555555555555</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="pre" href="preface.xhtml" media-type="application/xhtml+xml"/>
                    <item id="p1" href="part1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="p2" href="part2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="pre"/><itemref idref="p1"/><itemref idref="p2"/></spine>
                </package>""");
            entry(zip, "EPUB/nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>TOC</title></head>
                  <body><nav epub:type="toc"><ol>
                    <li><a href="part1.xhtml">Part 1</a></li>
                    <li><a href="part2.xhtml">Part 2</a></li>
                  </ol></nav></body>
                </html>""");
            entry(zip, "EPUB/preface.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Preface</title></head><body><p>Lead</p></body></html>");
            entry(zip, "EPUB/part1.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Part 1</title></head><body><p>One</p></body></html>");
            entry(zip, "EPUB/part2.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Part 2</title></head><body><p>Two</p></body></html>");
        }
        var leadParts = EpubMerger.autoSplitByParts(EpubArchive.load(leadingEpub));
        check(leadParts.size() == 2 && leadParts.get(0).spineIdrefs().equals(List.of("pre", "p1")),
            "autoSplitByParts keeps leading frontmatter spine items in first volume");

        // Verify Package removeFile regenerates nav/ncx and Doctor detects/repairs broken nav links.
        var editArc = EpubArchive.load(partEpub);
        editArc.removeFile("EPUB/chapter-3.xhtml");
        Path editedPartEpub = directory.resolve("Export-removed-chapter.epub");
        editArc.saveAsEpub3(editedPartEpub, true);
        var editedDocReport = EpubDoctor.checkEpub(editedPartEpub);
        check(editedDocReport.healthy() && editedDocReport.findings().stream().noneMatch(f -> f.message().contains("Broken TOC link")),
            "EpubArchive.removeFile removes deleted file from nav.xhtml and toc.ncx");

        // Verify PNG optimization in EpubArchive preserves PNG mime type.
        var largePngImg = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        var lg = largePngImg.createGraphics();
        for (int y = 0; y < 1200; y += 20) {
            lg.setColor(new Color((y * 7) % 255, (y * 13) % 255, (y * 3) % 255));
            lg.fillRect(0, y, 1200, 20);
        }
        lg.dispose();
        Path bigPngPath = directory.resolve("big.png");
        ImageIO.write(largePngImg, "png", bigPngPath.toFile());
        String bigKey = "img-" + UUID.randomUUID() + ".png";
        var imgBook = new Draft(Epub.Metadata.basic("urn:uuid:77777777-2222-3333-4444-555555555555", "PNG Check", "A", "en"),
            List.of(new Epub.Chapter("Ch", "![Pic](" + bigKey + ")", true, Map.of(bigKey, BookFiles.cover(bigPngPath)))),
            null, Epub.Options.editorial());
        Path imgEpub = directory.resolve("Export-png-opt.epub");
        imgBook.export(imgEpub);
        var optArc = EpubArchive.load(imgEpub);
        optArc.optimizeAllImages(400, 0.8f);
        Path optImgEpub = directory.resolve("Export-png-opt-done.epub");
        optArc.saveAsEpub3(optImgEpub, true);
        var optLoaded = EpubArchive.load(optImgEpub);
        check(optLoaded.manifest.values().stream().filter(i -> i.path.endsWith(".png"))
            .allMatch(i -> "image/png".equals(i.mediaType)), "EpubArchive.optimizeAllImages keeps PNG media-type matching .png filename");

        // Verify FilePickers never defaults to root "/".
        Path pickedInit = FilePickers.resolveInitialDirectory(Path.of("/"));
        check(pickedInit.getParent() != null && Files.isDirectory(pickedInit),
            "FilePickers never starts in root /");

        // 16. Desktop Workspace UI + Untitled Blank Mode + Undo + Split/Merge + Book Switch + Light & Dark visual renders!
        onEdt(() -> {
            var untitled = new Workspace(null, Draft.empty(), false);
            check(untitled.isCleanEmpty(), "Untitled startup workspace begins clean and empty");
            untitled.close(() -> {});
            return null;
        });
        exerciseBookSwitch(directory, epub);
        exerciseWorkspace(directory, List.of(txt, broken, md, epub), designedCover);
        System.out.println(checks + " assertions passed");
    }

    private static void exerciseBookSwitch(Path directory, Path first) throws Exception {
        var second = Files.createDirectories(directory.resolve("second")).resolve(first.getFileName());
        Files.copy(first, second);
        Files.createDirectories(directory.resolve("books"));
        var a = DesktopApp.epubDraft(directory, first);
        var b = DesktopApp.epubDraft(directory, second);
        check(!a.equals(b), "Equal filenames in different folders have separate drafts");
        var alias = Files.createSymbolicLink(directory.resolve("alias.epub"), first.toAbsolutePath());
        check(a.equals(DesktopApp.epubDraft(directory, alias)), "Reopening an EPUB through a symlink resumes its draft");
        var firstWindow = onEdt(() -> new Workspace(a, Draft.empty(), true));
        var secondWindow = onEdt(() -> new Workspace(b, Draft.empty(), true));
        onEdt(() -> { firstWindow.importPaths(List.of(first)); secondWindow.importPaths(List.of(second)); return null; });
        waitIdle(firstWindow);
        waitIdle(secondWindow);
        var closed = new CountDownLatch(2);
        onEdt(() -> {
            find(firstWindow, JTextArea.class, "Chapter text").setText("First book revision");
            find(secondWindow, JTextArea.class, "Chapter text").setText("Second book revision");
            firstWindow.close(closed::countDown);
            secondWindow.close(closed::countDown);
            return null;
        });
        check(closed.await(8, TimeUnit.SECONDS), "Both book windows flush their edits");
        check(Draft.read(a).chapters().get(0).body().equals("First book revision"), "First book resumes its own edits");
        check(Draft.read(b).chapters().get(0).body().equals("Second book revision"), "Second book resumes its own edits");
    }

    private static void exerciseWorkspace(Path directory, List<Path> files, Epub.Image sampleCover) throws Exception {
        Path workspaceDraft = directory.resolve("workspace.ql");
        var work = onEdt(() -> {
            UiTheme.setDark(false);
            DesktopApp.configureTheme();
            return new Workspace(workspaceDraft, Draft.empty(), true);
        });
        onEdt(() -> { work.importPaths(files); return null; });
        waitIdle(work);
        onEdt(() -> {
            var snapshot = work.snapshot();
            check(snapshot.chapters().stream().map(Epub.Chapter::title).toList()
                .equals(List.of("The Tidal Library", "Chapter 10", "Second section", "First section")),
                "Desktop mixed import sorts names naturally and keeps partial successes");
            check(snapshot.title().equals("Sample & Sons") && snapshot.author().equals("N. লেখক"), "Desktop fills book metadata");
            work.setCover(sampleCover);
            var list = find(work, JList.class, "Chapters");
            check(list.getSelectedIndex() == 0, "Desktop selects imported chapter");

            find(work, JTextField.class, "Chapter title").setText("Edited title");
            var bodyArea = find(work, JTextArea.class, "Chapter text");
            bodyArea.setText("Revised **prose** with café 😀.");
            // Verify Undo / Redo works!
            work.undoText();
            check(!bodyArea.getText().equals("Revised **prose** with café 😀."), "Undo reverts chapter text edit");
            work.redoText();
            check(bodyArea.getText().equals("Revised **prose** with café 😀."), "Redo restores chapter text edit");

            find(work, JButton.class, "Move down").doClick();
            check(work.snapshot().chapters().get(1).title().equals("Edited title") && list.getSelectedIndex() == 1,
                "Desktop edits and reorders through controls");

            // Verify duplicate + structural undo!
            int beforeDup = work.snapshot().chapters().size();
            work.duplicateChapter();
            check(work.snapshot().chapters().size() == beforeDup + 1, "Duplicate chapter adds copy");
            work.undoStructure();
            check(work.snapshot().chapters().size() == beforeDup, "Structure undo reverses duplicate");

            var tabs = find(work, JTabbedPane.class, null);
            tabs.setSelectedIndex(1);
            check(find(work, JEditorPane.class, "Chapter preview").getText().contains("<strong>prose</strong>"),
                "Desktop renders chapter preview");
            tabs.setSelectedIndex(0);
            find(work, JButton.class, "New chapter").doClick();
            check(work.snapshot().chapters().size() == 5, "Desktop adds empty chapter");
            find(work, JTextField.class, "Chapter title").setText("Afterword");
            find(work, JCheckBox.class, "Interpret as Markdown").doClick();
            find(work, JTextArea.class, "Chapter text").setText("Fin.\nEnd.");
            check(!work.snapshot().chapters().get(4).markdown(), "Desktop changes chapter text mode");

            // Render Light & Dark screenshots with Side-by-Side preview enabled!
            list.setSelectedIndex(1);
            work.splitPreview(true);
            renderScreenshot(work, directory.resolve("desktop-light.png"));
            UiTheme.setDark(true);
            work.refreshThemeColors();
            renderScreenshot(work, directory.resolve("desktop-dark.png"));
            UiTheme.setDark(false);
            work.refreshThemeColors();

            work.exportTo(directory.resolve("Export-desktop.epub"));
            return null;
        });
        waitIdle(work);
        check(BookFiles.read(directory.resolve("Export-desktop.epub")).chapters().get(1).title().equals("Edited title"),
            "Desktop export reflects edits and ordering");
        var done = new CountDownLatch(1);
        onEdt(() -> { work.close(done::countDown); return null; });
        check(done.await(8, TimeUnit.SECONDS), "Desktop closes after saving");
        var stored = Draft.read(workspaceDraft);
        check(stored.chapters().get(4).body().equals("Fin.\nEnd."), "Last life-cycle edits are durable");
        onEdt(() -> {
            var reopened = new Workspace(workspaceDraft, stored, true);
            check(reopened.snapshot().equals(stored), "Desktop reopens saved manuscript (including cover!)");
            reopened.close(() -> {});
            return null;
        });
    }

    private static void renderScreenshot(Workspace work, Path out) throws IOException {
        work.setSize(1120, 740);
        layout(work);
        var screenshot = new BufferedImage(1120, 740, BufferedImage.TYPE_INT_RGB);
        var g = screenshot.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        work.printAll(g);
        g.dispose();
        ImageIO.write(screenshot, "png", out.toFile());
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (var child : parent.getComponents()) if (child instanceof Container container) layout(container);
    }

    private static void waitIdle(Workspace workspace) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (onEdt(workspace::isBusy)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Desktop operation timed out");
            Thread.sleep(25);
        }
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        var job = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(job);
        return job.get();
    }

    private static <T extends JComponent> T find(Container root, Class<T> type, String name) {
        T found = search(root, type, name);
        if (found == null) throw new AssertionError("Missing UI control: " + name);
        return found;
    }

    private static <T extends JComponent> T search(Container root, Class<T> type, String name) {
        for (Component item : root.getComponents()) {
            if (type.isInstance(item) && (name == null || name.equals(item.getAccessibleContext().getAccessibleName()))) {
                return type.cast(item);
            }
            if (item instanceof Container child) {
                var candidate = search(child, type, name);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private static void check(boolean passed, String detail) {
        if (!passed) throw new AssertionError(detail);
        checks++;
    }

    private static void expectFailure(Callable<?> action, String detail) throws Exception {
        try {
            action.call();
            throw new AssertionError(detail + " was accepted");
        } catch (IOException expected) {
            checks++;
        }
    }

    @FunctionalInterface
    interface IoAction { void run() throws Exception; }

    private static void expectFailure(IoAction action, String detail) throws Exception {
        expectFailure(() -> { action.run(); return null; }, detail);
    }

    private static void fixtureEpub2(Path path, String secondPath) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "mimetype", "application/epub+zip");
            entry(zip, "META-INF/container.xml", """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="Library/volume.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """);
            entry(zip, "Library/volume.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Sample &amp; Sons</dc:title><dc:creator>N. লেখক</dc:creator><dc:language>bn</dc:language>
                    <dc:identifier id="id">urn:uuid:00000000-0000-0000-0000-000000000001</dc:identifier>
                  </metadata><manifest>
                    <item id="one" href="Text/first.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="%s" media-type="application/xhtml+xml"/>
                    <item id="ncx" href="contents.ncx" media-type="application/x-dtbncx+xml"/>
                  </manifest><spine toc="ncx"><itemref idref="two"/><itemref idref="one"/></spine>
                </package>
                """.formatted(secondPath));
            entry(zip, "Library/contents.ncx", """
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head><meta name="dtb:uid" content="urn:uuid:00000000-0000-0000-0000-000000000001"/>
                    <meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/>
                    <meta name="dtb:maxPageNumber" content="0"/>
                  </head>
                  <docTitle><text>Sample</text></docTitle><navMap>
                    <navPoint id="two" playOrder="1"><navLabel><text>Second</text></navLabel><content src="Text/Second%20page.xhtml"/></navPoint>
                    <navPoint id="one" playOrder="2"><navLabel><text>First</text></navLabel><content src="Text/first.xhtml"/></navPoint>
                  </navMap>
                </ncx>
                """);
            entry(zip, "Library/Text/first.xhtml", """
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>First section</title></head>
                <body><p>Text &amp; <em>tea</em>. Caf&eacute;&nbsp;&copy;.</p><script>ignored</script></body></html>
                """);
            entry(zip, "Library/Text/Second page.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Heading wins</title></head>
                <body><h1>Second section</h1><p>The tide<br/>returned.</p><p>বাংলা ও 日本語.</p></body></html>
                """);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        var entry = new ZipEntry(name);
        if (name.equals("mimetype")) {
            var checksum = new java.util.zip.CRC32();
            checksum.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCrc(checksum.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
