package io.quireloom.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.AdapterView;
import android.widget.EditText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PublishingDesk {
    private final Studio studio;
    private Uri pendingWatchTree;
    private Path pendingSplit;
    private Draft folderSettings;
    private List<FolderProject.Entry> pendingEntries;
    private volatile Uri watchTree;
    private Uri watchOutput;
    private Draft watchSettings;
    private List<FolderProject.Entry> seen = List.of();
    private PackageStudio packageStudio;
    private CoverDesigner coverDesigner;

    PublishingDesk(Studio studio) { this.studio = studio; }

    void designCover(Draft book) {
        coverDesigner = new CoverDesigner(studio, book, studio::setCover);
        coverDesigner.show();
    }

    void process(String action, List<Path> paths, Path workspace, Draft draft) throws Exception {
        Path first = paths.get(0);
        if (action.equals("packageAsset") || action.equals("packageCover")) {
            if (packageStudio == null) throw new IOException("Open Package Studio again to add resources.");
            packageStudio.acceptFile(action, first);
            return;
        }
        if (action.equals("coverPhoto")) {
            if (coverDesigner == null) throw new IOException("Open Cover Designer again to choose a photo.");
            coverDesigner.setPhoto(BookFiles.readImage(first));
            return;
        }
        switch (action) {
            case "package" -> openPackage(first, first.getFileName().toString());
            case "merge" -> mergeSetup(paths, workspace, draft);
            case "split" -> splitSetup(first, workspace, EpubArchive.load(first));
            case "check" -> report("EPUB Doctor", EpubDoctor.checkEpub(first), workspace, first);
            default -> throw new IOException("Unknown publishing action.");
        }
    }

    void openPackage(Path path, String filename) throws Exception {
        packageStudio = new PackageStudio(studio, EpubArchive.load(path), filename, () -> packageStudio = null);
        packageStudio.show();
    }

    private static final class Volume {
        final Path path;
        String title;
        boolean divider = true;
        boolean rasterize;
        List<SpineItem> chapters = List.of();
        Set<String> selected;

        Volume(Path path) {
            this.path = path;
            title = stem(path.getFileName().toString());
        }

        EpubMerger.SourceItem source() {
            return new EpubMerger.SourceItem(path, title, divider, rasterize, selected);
        }
    }

    record SpineItem(String id, String path, String title) {}

    static List<SpineItem> readingOrder(EpubArchive archive) {
        var chapters = new ArrayList<SpineItem>();
        for (var entry : archive.spine) {
            var item = archive.manifest.get(entry.idref);
            if (item != null && item.isHtml() && !item.hasProperty("nav")) {
                chapters.add(new SpineItem(item.id, item.path, archive.extractTitle(item.path, item.path)));
            }
        }
        return chapters;
    }

    private void mergeSetup(List<Path> paths, Path workspace, Draft draft) throws Exception {
        var volumes = new ArrayList<Volume>();
        for (Path path : paths) {
            var volume = new Volume(path);
            if (extension(path).equals("epub")) {
                var archive = EpubArchive.load(path);
                if (!archive.metadata.title().isBlank()) volume.title = archive.metadata.title();
                volume.chapters = readingOrder(archive);
            }
            volumes.add(volume);
        }
        var m = draft.metadata();
        var initial = new Epub.Metadata("urn:uuid:" + UUID.randomUUID(),
            m.title().isBlank() ? "Collected Volumes" : m.title(), m.subtitle(), m.author(), m.language(),
            m.description(), m.publisher(), m.subjects(), m.series(), m.seriesIndex(), m.rights(), m.date());
        studio.runOnUiThread(() -> new MergeDialog(workspace, draft, volumes, initial).show());
    }

    private final class MergeDialog {
        final Path workspace;
        final Draft draft;
        final List<Volume> volumes;
        final Epub.Metadata initial;
        StudioForm form;
        EditText title, author, language;
        android.widget.CheckBox useCover, inherit;
        android.widget.LinearLayout rows;

        MergeDialog(Path workspace, Draft draft, List<Volume> volumes, Epub.Metadata initial) {
            this.workspace = workspace;
            this.draft = draft;
            this.volumes = volumes;
            this.initial = initial;
        }

        void show() {
            form = new StudioForm(studio.dialogContext());
            title = form.text("Omnibus title", initial.title(), false);
            author = form.text("Author", initial.author(), false);
            language = form.text("Language", initial.language(), false);
            useCover = form.check("Use current book cover", draft.cover() != null);
            useCover.setEnabled(draft.cover() != null);
            inherit = form.check("Inherit first EPUB cover", true);
            form.label("Volumes in reading order");
            rows = new android.widget.LinearLayout(studio);
            rows.setOrientation(android.widget.LinearLayout.VERTICAL);
            form.fields.addView(rows);
            rebuild();
            form.confirm("Omnibus Merger", "Merge EPUB", dialog -> {
                if (title.getText().toString().isBlank()) {
                    title.setError("Enter a title");
                    return;
                }
                var m = new Epub.Metadata(initial.id(), title.getText().toString(), initial.subtitle(),
                    author.getText().toString(), language.getText().toString(), initial.description(),
                    initial.publisher(), initial.subjects(), initial.series(), initial.seriesIndex(), initial.rights(), initial.date());
                var config = new EpubMerger.MergeConfig(m, useCover.isChecked() ? draft.cover() : null,
                    draft.options(), inherit.isChecked());
                var sources = volumes.stream().map(Volume::source).collect(java.util.stream.Collectors.toList());
                dialog.dismiss();
                studio.js("busy(true)");
                studio.task(() -> {
                    try { studio.makeOutput(".epub", filename(m.title()) + ".epub", p -> EpubMerger.merge(sources, p, config)); }
                    finally { BookFiles.deleteTree(workspace); }
                });
            }, () -> discard(workspace));
        }

        void rebuild() {
            rows.removeAllViews();
            for (int i = 0; i < volumes.size(); i++) {
                int index = i;
                Volume v = volumes.get(i);
                var button = new android.widget.Button(studio.dialogContext());
                button.setAllCaps(false);
                button.setText((i + 1) + ". " + v.title + (v.selected == null ? "" : " · " + v.selected.size() + " selected"));
                button.setOnClickListener(view -> editVolume(index));
                rows.addView(button);
            }
        }

        void editVolume(int index) {
            Volume volume = volumes.get(index);
            var editor = new StudioForm(studio.dialogContext());
            var name = editor.text("Volume title", volume.title, false);
            var divider = extension(volume.path).equals("epub") && volumes.size() > 1
                ? editor.check("Include a volume divider", volume.divider) : null;
            boolean pdf = extension(volume.path).equals("pdf");
            var raster = pdf ? editor.check("Import PDF pages as images", volume.rasterize) : null;
            var order = editor.select("Position", java.util.stream.IntStream.rangeClosed(1, volumes.size())
                .mapToObj(String::valueOf).toArray(String[]::new), index);
            var chapters = new ArrayList<android.widget.CheckBox>();
            if (!volume.chapters.isEmpty()) {
                editor.label("Chapters to include");
                for (var chapter : volume.chapters) {
                    chapters.add(editor.check(chapter.title(), volume.selected == null || volume.selected.contains(chapter.id())));
                }
            }
            editor.confirm(volume.path.getFileName().toString(), "Apply", dialog -> {
                var selected = new LinkedHashSet<String>();
                for (int i = 0; i < chapters.size(); i++) {
                    if (chapters.get(i).isChecked()) selected.add(volume.chapters.get(i).id());
                }
                if (!chapters.isEmpty() && selected.isEmpty()) {
                    studio.error("Select chapters", new IOException("Keep at least one chapter in this volume."));
                    return;
                }
                volume.title = name.getText().toString().strip();
                if (volume.title.isBlank()) volume.title = stem(volume.path.getFileName().toString());
                if (divider != null) volume.divider = divider.isChecked();
                volume.rasterize = pdf && raster.isChecked();
                volume.selected = selected.size() == chapters.size() ? null : selected;
                volumes.remove(index);
                volumes.add(order.getSelectedItemPosition(), volume);
                dialog.dismiss();
                rebuild();
            }, () -> {});
        }
    }

    private void splitSetup(Path input, Path workspace, EpubArchive archive) {
        var parts = EpubMerger.autoSplitByParts(archive);
        var all = EpubMerger.autoSplitByChunkSize(archive, 2000);
        Set<String> assigned = new LinkedHashSet<>();
        for (var part : parts) assigned.addAll(part.spineIdrefs());
        if (!parts.isEmpty() && !all.isEmpty()) {
            var leading = new ArrayList<>(all.get(0).spineIdrefs());
            leading.removeAll(assigned);
            if (!leading.isEmpty()) {
                var first = parts.get(0);
                leading.addAll(first.spineIdrefs());
                var amended = new ArrayList<>(parts);
                amended.set(0, new EpubMerger.SplitPart(first.title(), leading));
                parts = amended;
            }
        }
        var byContents = parts;
        studio.runOnUiThread(() -> {
            var form = new StudioForm(studio.dialogContext());
            var strategy = form.select("Split by", new String[]{"Top-level contents", "Fixed chapter count"},
                byContents.isEmpty() ? 1 : 0);
            var count = form.number("Chapters per volume", 5);
            var destination = form.select("Export format", new String[]{"Individual EPUB files in a folder", "ZIP archive"}, 0);
            var summary = form.label("");
            java.util.function.Supplier<List<EpubMerger.SplitPart>> plan = () -> {
                if (strategy.getSelectedItemPosition() == 0) return byContents;
                return EpubMerger.autoSplitByChunkSize(archive, boundedNumber(count, 1, 500, 5));
            };
            Runnable refresh = () -> {
                count.setEnabled(strategy.getSelectedItemPosition() == 1);
                var lines = new StringJoiner("\n");
                for (var p : plan.get()) lines.add(p.title() + " · " + p.spineIdrefs().size() + " chapters");
                summary.setText(lines.length() == 0 ? "No chapters to split." : lines.toString());
            };
            strategy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int index, long id) { refresh.run(); }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            count.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
            refresh.run();
            form.confirm("EPUB Splitter", "Split", dialog -> {
                var planNow = plan.get();
                if (planNow.isEmpty()) return;
                if (strategy.getSelectedItemPosition() == 1 && boundedNumber(count, 1, 500, 0) == 0) {
                    count.setError("Enter a number from 1 to 500");
                    return;
                }
                boolean zip = destination.getSelectedItemPosition() == 1;
                dialog.dismiss();
                studio.js("busy(true)");
                studio.task(() -> {
                    Path dir = Files.createTempDirectory(studio.getCacheDir().toPath(), "volumes-");
                    try {
                        EpubMerger.split(input, dir, planNow);
                        String name = filename(archive.metadata.title()) + " volumes";
                        if (zip) studio.makeOutput(".zip", name + ".zip", output -> zip(dir, output));
                        else {
                            pendingSplit = dir;
                            studio.pickTree("splitFolder");
                        }
                    } finally {
                        BookFiles.deleteTree(workspace);
                        if (!dir.equals(pendingSplit)) BookFiles.deleteTree(dir);
                    }
                });
            }, () -> discard(workspace));
        });
    }

    void report(String title, EpubDoctor.Report report, Runnable after) {
        showReport(title, report, null, after);
    }

    private void report(String title, EpubDoctor.Report report, Path workspace, Path source) {
        showReport(title, report, () -> repairOptions(workspace, source), () -> discard(workspace));
    }

    private void showReport(String title, EpubDoctor.Report report, Runnable repair, Runnable after) {
        var detail = new StringBuilder(report.targetName()).append("\n\n")
            .append(report.errors()).append(" errors · ").append(report.warnings()).append(" warnings\n\n");
        report.stats().forEach((k, v) -> detail.append(k).append("  ").append(v).append('\n'));
        for (var finding : report.findings()) {
            detail.append("\n").append(finding.severity()).append(" · ").append(finding.category())
                .append("\n").append(finding.message()).append('\n');
            if (finding.detail() != null && !finding.detail().isBlank()) detail.append(finding.detail()).append('\n');
        }
        studio.runOnUiThread(() -> {
            var form = new StudioForm(studio.dialogContext());
            form.label(detail.toString());
            var dialog = new AlertDialog.Builder(studio.dialogContext()).setTitle(title).setView(form.scroll())
                .setNegativeButton("Close", (d, w) -> { if (after != null) after.run(); });
            if (repair != null) dialog.setPositiveButton("Repair a copy", (d, w) -> repair.run());
            dialog.setOnCancelListener(d -> { if (after != null) after.run(); }).show();
        });
    }

    private void repairOptions(Path workspace, Path source) {
        var form = new StudioForm(studio.dialogContext());
        form.label("Upgrade to EPUB 3.3 and normalize XHTML");
        var orphans = form.check("Remove unmanifested files", true);
        var ncx = form.check("Include EPUB 2 navigation fallback", true);
        var optimize = form.check("Optimize oversized images", false);
        var max = form.number("Maximum image dimension", 1600);
        form.confirm("Repair EPUB", "Save repaired copy", dialog -> {
            int pixels = boundedNumber(max, 320, 4096, 0);
            if (optimize.isChecked() && pixels == 0) {
                max.setError("Enter a dimension from 320 to 4096");
                return;
            }
            var opts = new EpubDoctor.RepairOptions(true, orphans.isChecked(), optimize.isChecked(),
                pixels, .84f, ncx.isChecked());
            dialog.dismiss();
            studio.task(() -> {
                try {
                    studio.makeOutput(".epub", stem(source.getFileName().toString()) + " repaired.epub",
                        output -> EpubDoctor.repair(source, output, opts));
                } finally { BookFiles.deleteTree(workspace); }
            });
        }, () -> discard(workspace));
    }

    void export(Draft draft, String format) throws Exception {
        String name = filename(draft.title());
        switch (format) {
            case "epub" -> studio.makeOutput(".epub", name + ".epub", draft::export);
            case "html" -> studio.makeOutput(".html", name + ".html", draft::exportHtml);
            case "backup" -> studio.makeOutput(".ql", name + ".ql", draft::save);
            case "markdownZip" -> studio.makeOutput(".zip", name + " Markdown.zip", output -> {
                Path directory = Files.createTempDirectory(studio.getCacheDir().toPath(), "markdown-");
                try {
                    BookFiles.exportMarkdownFolder(draft, directory);
                    zip(directory, output);
                } finally { BookFiles.deleteTree(directory); }
            });
            default -> throw new IOException("Unknown export format.");
        }
    }

    void selectFolder(String action, Draft settings) {
        if (!action.equals("watch")) cancelPendingWatch();
        folderSettings = settings;
    }

    void folderSelected(String action, Uri tree, int flags, Draft current) throws Exception {
        Draft settings = folderSettings == null ? current : folderSettings;
        folderSettings = null;
        var resolver = studio.getContentResolver();
        if (action.equals("markdown") || action.equals("splitFolder")) {
            Path directory = action.equals("splitFolder") ? pendingSplit :
                Files.createTempDirectory(studio.getCacheDir().toPath(), "markdown-");
            pendingSplit = null;
            if (directory == null) throw new IOException("Split export expired. Split the EPUB again.");
            try {
                if (action.equals("markdown")) BookFiles.exportMarkdownFolder(settings, directory);
                exportDirectory(directory, tree, action.equals("markdown")
                    ? filename(settings.title()) + " Markdown" : "Quireloom volumes");
                studio.notice(action.equals("markdown") ? "Markdown folder exported" : "Volumes saved");
            } finally { BookFiles.deleteTree(directory); }
            return;
        }
        var entries = FolderProject.scan(resolver, tree, null);
        boolean watch = action.equals("watch");
        if (watch) {
            retain(tree, flags);
            pendingWatchTree = tree;
            folderSettings = settings;
            pendingEntries = entries;
        }
        try {
            studio.makeOutput(".epub", filename(settings.title()) + ".epub",
                out -> FolderProject.build(resolver, entries, studio.getCacheDir().toPath(), out, settings));
        } catch (Exception failure) {
            cancelPendingWatch();
            throw failure;
        }
    }

    private void exportDirectory(Path directory, Uri tree, String name) throws IOException {
        var resolver = studio.getContentResolver();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri output = DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (output == null) throw new IOException("Could not create the export folder.");
        try { copyFolder(directory, output); }
        catch (IOException | RuntimeException error) {
            try { DocumentsContract.deleteDocument(resolver, output); }
            catch (Exception ignored) {}
            throw error;
        }
    }

    private void copyFolder(Path directory, Uri output) throws IOException {
        var resolver = studio.getContentResolver();
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                boolean folder = Files.isDirectory(path);
                String mime = folder ? DocumentsContract.Document.MIME_TYPE_DIR : switch (extension(path)) {
                    case "epub" -> "application/epub+zip";
                    case "md", "txt" -> "text/plain";
                    case "png" -> "image/png";
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    case "webp" -> "image/webp";
                    default -> "application/octet-stream";
                };
                Uri document = DocumentsContract.createDocument(resolver, output, mime, name);
                if (document == null) throw new IOException("Could not create " + name);
                if (folder) copyFolder(path, document);
                else try (var stream = resolver.openOutputStream(document, "wt")) {
                    if (stream == null) throw new IOException("Could not write " + name);
                    Files.copy(path, stream);
                }
            }
        }
    }

    void exportFinished(Uri output, int flags) throws Exception {
        if (pendingWatchTree == null) return;
        retain(output, flags);
        watchTree = pendingWatchTree;
        watchOutput = output;
        watchSettings = folderSettings == null ? studio.currentDraft() : folderSettings;
        seen = pendingEntries == null ? FolderProject.scan(studio.getContentResolver(), watchTree, output) :
            pendingEntries.stream().filter(e -> !sameDocument(e.uri(), output)).collect(java.util.stream.Collectors.toList());
        cancelPendingWatch();
        studio.js("watching(true)");
        studio.runOnUiThread(studio::scheduleWatch);
    }

    private static boolean sameDocument(Uri a, Uri b) {
        return Objects.equals(a.getAuthority(), b.getAuthority())
            && DocumentsContract.getDocumentId(a).equals(DocumentsContract.getDocumentId(b));
    }

    private void retain(Uri uri, int flags) {
        int modes = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modes == 0) return;
        try { studio.getContentResolver().takePersistableUriPermission(uri, modes); }
        catch (SecurityException ignored) {}
    }

    boolean isWatching() { return watchTree != null; }

    void checkWatch() throws Exception {
        if (watchTree == null) return;
        var resolver = studio.getContentResolver();
        var entries = FolderProject.scan(resolver, watchTree, watchOutput);
        if (entries.equals(seen)) return;
        Path next = studio.temp(".epub");
        try {
            FolderProject.build(resolver, entries, studio.getCacheDir().toPath(), next, watchSettings);
            try (var stream = resolver.openOutputStream(watchOutput, "wt")) {
                if (stream == null) throw new IOException("The watched EPUB destination is unavailable.");
                Files.copy(next, stream);
            }
            seen = entries;
            studio.notice("Watched EPUB rebuilt");
        } finally { Files.deleteIfExists(next); }
    }

    void stopWatch() {
        watchTree = null;
        watchOutput = null;
        watchSettings = null;
        seen = List.of();
        studio.js("watching(false)");
        studio.notice("Folder watch stopped");
    }

    void cancelPendingWatch() {
        pendingWatchTree = null;
        pendingEntries = null;
        folderSettings = null;
    }

    void cancelPicker(String action) {
        studio.task(() -> {
            if (List.of("watch", "folder", "markdown").contains(action)) cancelPendingWatch();
            if (List.of("packageAsset", "packageCover").contains(action) && packageStudio != null) packageStudio.show();
            if (action.equals("splitFolder") && pendingSplit != null) {
                BookFiles.deleteTree(pendingSplit);
                pendingSplit = null;
            }
        });
    }

    void savePicker(Bundle state) {
        if (pendingSplit != null) state.putString("split", pendingSplit.getFileName().toString());
        if (pendingWatchTree != null) state.putString("watchTree", pendingWatchTree.toString());
    }

    void restorePicker(Bundle state) {
        String split = state.getString("split");
        if (split != null) pendingSplit = studio.getCacheDir().toPath().resolve(split);
        String tree = state.getString("watchTree");
        if (tree != null) pendingWatchTree = Uri.parse(tree);
    }

    private void discard(Path path) {
        studio.task(() -> BookFiles.deleteTree(path));
    }

    static void zip(Path directory, Path output) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(output)); var paths = Files.walk(directory)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().collect(java.util.stream.Collectors.toList())) {
                zip.putNextEntry(new ZipEntry(directory.relativize(file).toString()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    static int boundedNumber(EditText field, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(field.getText().toString());
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException error) { return fallback; }
    }

    static String filename(String title) {
        String value = title.strip().replaceAll("[\\p{Cntrl}/\\\\]", "_");
        if (value.length() > 100) value = value.substring(0, 100).stripTrailing();
        return value.isBlank() || value.equals(".") || value.equals("..") ? "Manuscript" : value;
    }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
