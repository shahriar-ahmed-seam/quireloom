package io.quireloom.app;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageStudio {
    private static final String PREVIEW = "https://package.quireloom.invalid";
    private static final String[] METADATA = {"Title", "Author", "Language", "Subtitle", "Description", "Publisher",
        "Subjects", "Series", "Series index", "Rights", "Publication date"};

    private final Studio studio;
    private final String filename;
    private final Runnable release;
    private EpubArchive archive;
    private boolean metadataEdited;
    private boolean structureEdited;
    private boolean dirty;

    PackageStudio(Studio studio, EpubArchive archive, String filename, Runnable release) {
        this.studio = studio;
        this.archive = archive;
        this.filename = filename;
        this.release = release;
    }

    void show() {
        studio.runOnUiThread(() -> {
            if (studio.isFinishing() || studio.isDestroyed()) return;
            String[] menu = {"Browse resources", "Book metadata", "Edit raw OPF", "Spine reading order",
                "Add a resource", "Choose cover image", "Find and replace across files", "Optimize images",
                "Run EPUB Doctor", "Book information", "Save EPUB copy"};
            var dialog = new AlertDialog.Builder(studio.dialogContext()).setTitle("Package Studio")
                .setItems(menu, (d, index) -> {
                    switch (index) {
                        case 0 -> studio.task(this::browse);
                        case 1 -> metadata();
                        case 2 -> studio.task(() -> editText(archive.opfPath, new String(packageOpf(), java.nio.charset.StandardCharsets.UTF_8), true));
                        case 3 -> studio.task(this::spine);
                        case 4 -> studio.pick("packageAsset");
                        case 5 -> studio.pick("packageCover");
                        case 6 -> replace();
                        case 7 -> optimize();
                        case 8 -> studio.task(this::check);
                        case 9 -> studio.task(this::info);
                        case 10 -> studio.task(() -> export(false));
                    }
                }).setNegativeButton("Close", (d, w) -> close()).create();
            dialog.setOnCancelListener(d -> close());
            dialog.show();
        });
    }

    private void close() {
        if (!dirty) { release.run(); return; }
        var warning = new AlertDialog.Builder(studio.dialogContext()).setTitle("Package has unsaved edits")
            .setPositiveButton("Save copy", (d, w) -> studio.task(() -> export(true)))
            .setNegativeButton("Discard", (d, w) -> release.run())
            .setNeutralButton("Keep editing", (d, w) -> show()).create();
        warning.setOnCancelListener(d -> show());
        warning.show();
    }

    private void browse() {
        record Resource(String path, String mime, EpubArchive.ManifestItem item) {}
        var resources = new ArrayList<Resource>();
        for (var path : archive.files.keySet()) {
            if (path.equals(archive.opfPath)) continue;
            var item = archive.findManifestByPath(path);
            resources.add(new Resource(path, item == null ? mediaType(path) : item.mediaType, item));
        }
        resources.sort(java.util.Comparator.comparing(Resource::path));
        studio.runOnUiThread(() -> {
            var dialog = new AlertDialog.Builder(studio.dialogContext()).setTitle("EPUB resources")
                .setItems(resources.stream().map(Resource::path).toArray(String[]::new), (d, index) -> {
                    var resource = resources.get(index);
                    studio.task(() -> resource(resource.path(), resource.mime(), resource.item()));
                }).setNegativeButton("Back", (d, w) -> show());
            dialog.setOnCancelListener(d -> show()).show();
        });
    }

    private void resource(String path, String mime, EpubArchive.ManifestItem item) {
        boolean editable = item != null ? item.isTextEditable()
            : mime.startsWith("text/") || mime.contains("xml");
        byte[] data = archive.files.get(path);
        var choices = new LinkedHashMap<String, Runnable>();
        if (editable) choices.put("Edit source", () -> editText(path, archive.getText(path), false));
        if (mime.startsWith("image/") || "application/xhtml+xml".equals(mime) || "text/html".equals(mime)) {
            choices.put("Preview", () -> preview(path, null, () -> resource(path, mime, item)));
        }
        if (item != null && item.isImage() && !mime.equals("image/svg+xml")) {
            choices.put("Use as cover", () -> studio.task(() -> {

                archive.coverItemId = item.id;
                structureEdited = true;
                dirty = true;
                browse();
            }));
        }
        choices.put("Export resource", () -> studio.task(() -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            studio.makeOutput("." + PublishingDesk.extension(java.nio.file.Paths.get(name)), name, output -> Files.write(output, data));
            resource(path, mime, item);
        }));
        if (!path.startsWith("META-INF/") && (item == null || !item.hasProperty("nav"))) {
            choices.put("Delete resource", () -> new AlertDialog.Builder(studio.dialogContext()).setTitle("Delete this resource?")
                .setMessage(path).setPositiveButton("Delete", (d, w) -> studio.task(() -> {
                    archive.removeFile(path);
                    structureEdited |= item != null;
                    removeTocReferences(archive.toc, path);
                    dirty = true;
                    browse();
                })).setNegativeButton("Cancel", (d, w) -> resource(path, mime, item)).show());
        }
        studio.runOnUiThread(() -> new AlertDialog.Builder(studio.dialogContext()).setTitle(path)
            .setItems(choices.keySet().toArray(String[]::new), (d, n) -> new ArrayList<>(choices.values()).get(n).run())
            .setNegativeButton("Back", (d, w) -> studio.task(this::browse))
            .setOnCancelListener(d -> studio.task(this::browse)).show());
    }

    private static void removeTocReferences(List<EpubArchive.TocNode> nodes, String path) {
        for (var node : nodes) {
            if (node.path != null && BookFiles.stripFragment(node.path).equals(path)) node.path = "";
            removeTocReferences(node.children, path);
        }
        nodes.removeIf(n -> (n.path == null || n.path.isEmpty()) && n.children.isEmpty());
    }

    private void editText(String path, String text, boolean opf) {
        studio.runOnUiThread(() -> {
            var form = new StudioForm(studio.dialogContext());
            var code = form.text("Source", text, true);
            code.setTypeface(Typeface.MONOSPACE);
            code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            code.setText(text);
            code.setMinLines(10);
            code.setHorizontallyScrolling(false);
            var content = new LinearLayout(studio);
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(form.scroll(), new LinearLayout.LayoutParams(-1, 0, 1));
            boolean html = !opf && (path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".svg"));
            var handler = new Handler(Looper.getMainLooper());
            PackagePreview local = html ? new PackagePreview(path) : null;
            WebView rendered = html ? local.view : null;
            Runnable render = () -> {
                local.override = code.getText().toString();
                local.view.loadUrl(local.url);
            };
            if (html) {
                rendered.setVisibility(android.view.View.GONE);
                content.addView(rendered, new LinearLayout.LayoutParams(-1, form.dp(200)));
                var toggle = new Button(studio.dialogContext());
                toggle.setAllCaps(false);
                toggle.setText("Show live preview");
                content.addView(toggle);
                toggle.setOnClickListener(v -> {
                    boolean show = !rendered.isShown();
                    rendered.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                    toggle.setText(show ? "Hide live preview" : "Show live preview");
                    if (show) render.run();
                    else handler.removeCallbacks(render);
                });
                code.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (rendered.isShown()) {
                            handler.removeCallbacks(render);
                            handler.postDelayed(render, 350);
                        }
                    }
                    @Override public void afterTextChanged(android.text.Editable s) {}
                });
            }
            var dialog = new AlertDialog.Builder(studio.dialogContext()).setTitle(path).setView(content)
                .setPositiveButton("Apply edits", null)
                .setNegativeButton("Cancel", (d, w) -> backFromCode(opf)).create();
            dialog.setOnCancelListener(d -> backFromCode(opf));
            if (html) dialog.setOnDismissListener(d -> {
                handler.removeCallbacks(render);
                rendered.destroy();
            });
            dialog.show();
            dialog.getWindow().setLayout(-1, -1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = code.getText().toString();
                studio.task(() -> {
                    if (!value.equals(text)) {
                        if (opf) applyOpf(value);
                        else archive.setText(path, value);
                        dirty = true;
                    }
                    studio.runOnUiThread(dialog::dismiss);
                    backFromCode(opf);
                });
            });
        });
    }

    private void backFromCode(boolean opf) {
        if (opf) show();
        else studio.task(this::browse);
    }

    private void applyOpf(String value) throws Exception {
        Path staged = studio.temp(".epub");
        try {
            PackageDocument.write(archive, staged, Epub.bytes(value));
            archive = EpubArchive.load(staged);
            metadataEdited = false;
            structureEdited = false;
        } finally { Files.deleteIfExists(staged); }
    }

    private void preview(String path, String text, Runnable back) {
        var local = new PackagePreview(path);
        local.override = text;
        local.view.loadUrl(local.url);
        var dialog = new AlertDialog.Builder(studio.dialogContext()).setTitle(path).setView(local.view)
            .setPositiveButton("Back", (d, w) -> back.run()).create();
        dialog.setOnCancelListener(d -> back.run());
        dialog.setOnDismissListener(d -> local.view.destroy());
        dialog.show();
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private final class PackagePreview {
        final WebView view = studio.offlineView();
        final String url;
        volatile String override;

        PackagePreview(String path) {
            var files = new LinkedHashMap<>(archive.files);
            var types = new LinkedHashMap<String, String>();
            for (var item : archive.manifest.values()) types.put(item.path, item.mediaType);
            url = PREVIEW + "/" + Uri.encode(path, "/");
            view.getSettings().setBuiltInZoomControls(true);
            view.getSettings().setDisplayZoomControls(false);
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    var uri = request.getUrl();
                    if (!"https".equals(uri.getScheme()) || !"package.quireloom.invalid".equals(uri.getHost())) {
                        return Studio.emptyResponse();
                    }
                    String p = uri.getPath();
                    if (p == null || p.length() < 2) return Studio.emptyResponse();
                    p = p.substring(1);
                    String edited = override;
                    byte[] data = p.equals(path) && edited != null ? Epub.bytes(edited) : files.get(p);
                    if (data == null) return Studio.emptyResponse();
                    return new WebResourceResponse(types.getOrDefault(p, mediaType(p)), null, 200, "OK", Map.of(
                        "Cache-Control", "no-store",
                        "Content-Security-Policy", "default-src 'none'; img-src 'self' data:; font-src 'self' data:; style-src 'self' 'unsafe-inline'"),
                        new ByteArrayInputStream(data));
                }

                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return true; }
            });
        }
    }

    private void metadata() {
        var form = new StudioForm(studio.dialogContext());
        var before = MobileBook.metadata(archive.metadata);
        var inputs = new ArrayList<android.widget.EditText>();
        for (int i = 0; i < METADATA.length; i++) inputs.add(form.text(METADATA[i], before.optString(i), i == 4));
        form.confirm("Book metadata", "Apply", dialog -> {
            var values = new org.json.JSONArray();
            for (var field : inputs) values.put(field.getText().toString());
            dialog.dismiss();
            studio.task(() -> {
                archive.metadata = MobileBook.metadata(values, archive.metadata.id());
                metadataEdited = true;
                dirty = true;
                show();
            });
        }, this::show);
    }

    private void spine() {
        var entries = new ArrayList<>(archive.spine);
        var names = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            var item = archive.manifest.get(entries.get(i).idref);
            names[i] = (i + 1) + ". " + (item == null ? entries.get(i).idref : archive.extractTitle(item.path, item.path));
        }
        studio.runOnUiThread(() -> new AlertDialog.Builder(studio.dialogContext()).setTitle("Spine reading order")
            .setItems(names, (d, from) -> {
                var form = new StudioForm(studio.dialogContext());
                var dest = form.number("New reading position (1–" + names.length + ")", from + 1);
                form.confirm(names[from], "Move", dialog -> {
                    int to = PublishingDesk.boundedNumber(dest, 1, names.length, 0);
                    if (to == 0) { dest.setError("Enter a valid reading position"); return; }
                    dialog.dismiss();
                    studio.task(() -> {
                        Collections.rotate(archive.spine.subList(Math.min(from, to - 1), Math.max(from, to - 1) + 1),
                            from < to - 1 ? -1 : 1);
                        structureEdited |= to - 1 != from;
                        dirty |= to - 1 != from;
                        spine();
                    });
                }, () -> studio.task(this::spine));
            }).setNegativeButton("Back", (d, w) -> show()).setOnCancelListener(d -> show()).show());
    }

    void acceptFile(String action, Path input) throws Exception {
        String name = input.getFileName().toString();
        String dir = archive.opfPath.substring(0, archive.opfPath.lastIndexOf('/') + 1);
        boolean cover = action.equals("packageCover");
        var old = archive.manifest.get(archive.coverItemId);
        if (cover) {
            var image = BookFiles.readImage(input);
            if (old != null && image.mime().equals(old.mediaType)) {
                archive.files.put(old.path, image.bytes());
            } else {
                String path = uniquePath(dir, "cover" + image.extension());
                archive.coverItemId = archive.addFile(path, image.bytes(), image.mime(), false);
                structureEdited = true;
            }
        } else {
            String type = mediaType(name);
            if (type.equals("application/octet-stream")) throw new IOException("Choose XHTML, CSS, an image, or a font.");
            String path = uniquePath(dir, name);
            byte[] data;
            try (var stream = Files.newInputStream(input)) { data = BookFiles.limited(stream, BookFiles.COVER_LIMIT); }
            boolean html = type.equals("application/xhtml+xml");
            archive.addFile(path, data, type, html);
            structureEdited = true;
        }
        dirty = true;
        show();
    }

    private String uniquePath(String dir, String name) {
        String path = dir + name;
        int n = 2;
        while (archive.files.containsKey(path)) path = dir + n++ + "-" + name;
        return path;
    }

    private static String mediaType(String path) {
        return switch (PublishingDesk.extension(java.nio.file.Paths.get(path))) {
            case "html", "xhtml", "htm" -> "application/xhtml+xml";
            case "css" -> "text/css";
            case "xml", "opf", "ncx" -> "application/xml";
            case "js" -> "application/javascript";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }

    private void replace() {
        var form = new StudioForm(studio.dialogContext());
        var find = form.text("Find", "", false);
        var replacement = form.text("Replace with", "", false);
        var regex = form.check("Regular expression", false);
        var caseSensitive = form.check("Match case", false);
        var css = form.check("Search CSS as well as XHTML", true);
        form.confirm("Find and replace across files", "Replace", dialog -> {
            String query = find.getText().toString();
            if (query.isEmpty()) { find.setError("Enter text to find"); return; }
            String value = replacement.getText().toString();
            boolean expression = regex.isChecked(), matchCase = caseSensitive.isChecked(), includeCss = css.isChecked();
            studio.task(() -> {
                var result = archive.findAndReplace(query, value, expression, matchCase, includeCss);
                dirty |= result.filesChanged() > 0;
                studio.runOnUiThread(() -> {
                    dialog.dismiss();
                    new AlertDialog.Builder(studio.dialogContext()).setTitle("Replacement complete")
                        .setMessage(result.totalReplacements() + " matches across " + result.filesChanged() + " files")
                        .setPositiveButton("Back to package", (d, w) -> show()).setOnCancelListener(d -> show()).show();
                });
            });
        }, this::show);
    }

    private void optimize() {
        var form = new StudioForm(studio.dialogContext());
        var dimension = form.number("Maximum image dimension", 1600);
        var quality = form.number("JPEG quality (1–100)", 85);
        form.confirm("Optimize images", "Optimize", dialog -> {
            int max = PublishingDesk.boundedNumber(dimension, 320, 4096, 0);
            int q = PublishingDesk.boundedNumber(quality, 1, 100, 0);
            if (max == 0) { dimension.setError("Enter 320–4096 pixels"); return; }
            if (q == 0) { quality.setError("Enter a quality from 1 to 100"); return; }
            dialog.dismiss();
            studio.task(() -> {
                var optimized = archive.optimizeAllImages(max, q / 100f);
                dirty |= optimized.imagesOptimized() > 0;
                studio.runOnUiThread(() -> new AlertDialog.Builder(studio.dialogContext()).setTitle("Image optimization")
                    .setMessage(optimized.imagesOptimized() + " images optimized · " + EpubDoctor.formatBytes(optimized.bytesSaved()) + " saved")
                    .setPositiveButton("Back to package", (d, w) -> show()).setOnCancelListener(d -> show()).show());
            });
        }, this::show);
    }

    private void check() throws Exception {
        var output = studio.temp(".epub");
        try {
            PackageDocument.write(archive, output, packageOpf());
            var checked = EpubDoctor.checkEpub(output);
            new PublishingDesk(studio).report("Package preflight",
                new EpubDoctor.Report(filename, checked.findings(), checked.stats()), this::show);
        } finally { Files.deleteIfExists(output); }
    }

    private void info() {
        var text = new StringBuilder(archive.metadata.title()).append('\n')
            .append(archive.metadata.author()).append('\n').append(archive.metadata.language()).append("\n\n")
            .append("EPUB ").append(archive.epubVersion).append(" · ").append(archive.manifest.size())
            .append(" resources · ").append(archive.spine.size()).append(" spine entries\n\n")
            .append("Table of contents\n");
        appendToc(text, archive.toc, "");
        studio.runOnUiThread(() -> {
            var form = new StudioForm(studio.dialogContext());
            form.label(text.toString());
            new AlertDialog.Builder(studio.dialogContext()).setTitle("Book information").setView(form.scroll())
                .setPositiveButton("Back", (d, w) -> show()).setOnCancelListener(d -> show()).show();
        });
    }

    private static void appendToc(StringBuilder text, List<EpubArchive.TocNode> nodes, String indent) {
        for (var node : nodes) {
            text.append(indent).append(node.label).append('\n');
            appendToc(text, node.children, indent + "    ");
        }
    }

    private byte[] packageOpf() throws Exception {
        return metadataEdited || structureEdited ? Epub.bytes(PackageDocument.opf(archive, metadataEdited))
            : archive.files.get(archive.opfPath);
    }

    private void export(boolean closeAfterSave) throws Exception {
        String name = PublishingDesk.stem(filename) + " edited.epub";
        studio.makeOutput(".epub", name,
            output -> PackageDocument.write(archive, output, packageOpf()),
            saved -> {
                if (saved) dirty = false;
                if (saved && closeAfterSave) release.run();
                else show();
            });
    }
}
