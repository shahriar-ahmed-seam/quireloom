package io.quireloom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class Studio extends Activity {
    static final String ORIGIN = "https://quireloom.invalid";
    private static final int OPEN = 1, EXPORT = 2, TREE = 3;
    private static final Map<String, String> STUDIO_FILES = Map.of(
        "/studio.html", "text/html", "/studio.css", "text/css", "/studio.js", "text/javascript",
        "/quireloom.png", "image/png");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Epub.Image> illustrations = new ConcurrentHashMap<>();
    private final Map<String, Epub.Image> thumbnails = new ConcurrentHashMap<>();
    private final PublishingDesk publishing = new PublishingDesk(this);
    private WebView web;
    private Path library, current;
    private Draft draft;
    private volatile Epub.Image coverThumb;
    private int generation;
    private boolean foreground;
    private boolean darkInterface;
    private boolean ready;
    private boolean initialized;
    private Runnable deferredPickerResult;
    private String persistedState;
    private long persistedRevision = -1;
    private String pickerAction = "";
    private int insertionChapter, insertionCaret;
    private Path pendingOutput;
    private java.util.function.Consumer<Boolean> exportCallback;
    private volatile Uri launchBook;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (isDebugBuild()) WebView.setWebContentsDebuggingEnabled(true);
        darkInterface = darkSystem();
        library = getFilesDir().toPath().resolve("books");
        if (state != null) {
            pickerAction = state.getString("picker", "");
            insertionChapter = state.getInt("chapter");
            insertionCaret = state.getInt("caret");
            String file = state.getString("output");
            if (file != null) pendingOutput = getCacheDir().toPath().resolve(file);
            publishing.restorePicker(state);
        } else if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            launchBook = getIntent().getData();
        }

        web = offlineView();
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Mobile");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return studioResource(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!url.equals(ORIGIN + "/studio.html") || ready) return;
                ready = true;
                task(() -> {
                    loadLibrary();
                    publish(true);
                    runOnUiThread(() -> {
                        initialized = true;
                        if (deferredPickerResult != null) {
                            Runnable result = deferredPickerResult;
                            deferredPickerResult = null;
                            result.run();
                        }
                    });
                    openLaunchBook();
                });
            }
        });
        var root = new android.widget.FrameLayout(this);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            view.setPadding(system.left, system.top, system.right, Math.max(system.bottom, keyboard));
            if (ready) js("imeVisible(" + insets.isVisible(WindowInsets.Type.ime()) + ")");
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        web.loadUrl(ORIGIN + "/studio.html");
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
    }

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
        launchBook = intent.getData();
        if (ready) js("openExternalBook()");
    }

    private void openLaunchBook() throws Exception {
        Uri uri = launchBook;
        launchBook = null;
        if (uri != null) open(uri);
    }

    private void loadLibrary() throws Exception {
        Files.createDirectories(library);
        current = library.resolve(getPreferences(MODE_PRIVATE).getString("current", "first.ql"));
        try {
            draft = Draft.read(current);
            if (Files.notExists(current)) migratePreviousDraft();
        } catch (Exception failure) {
            error("Saved draft", failure);
            current = newBookPath();
            draft = Draft.empty();
            getPreferences(MODE_PRIVATE).edit().putString("current", current.getFileName().toString()).apply();
        }
    }

    private void migratePreviousDraft() throws Exception {
        Path previous = getFilesDir().toPath().resolve("draft.json");
        if (Files.notExists(previous) || getPreferences(MODE_PRIVATE).getBoolean("migrated", false)) return;
        var old = new JSONObject(new String(Files.readAllBytes(previous), StandardCharsets.UTF_8));
        var rows = old.getJSONArray("chapters");
        var chapters = new ArrayList<Epub.Chapter>();
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.getJSONObject(i);
            chapters.add(new Epub.Chapter(row.getString("title"), row.getString("body"), row.optBoolean("markdown", true)));
        }
        Epub.Image cover = old.has("cover") ? new Epub.Image(old.getString("mime"),
            android.util.Base64.decode(old.getString("cover"), android.util.Base64.DEFAULT)) : null;
        draft = new Draft(old.getString("id"), old.optString("title"), old.optString("author"),
            old.optString("language", "en"), chapters, cover);
        draft.save(current);
        getPreferences(MODE_PRIVATE).edit().putBoolean("migrated", true).apply();
    }

    private WebResourceResponse studioResource(Uri uri) {
        if ("https".equals(uri.getScheme()) && "quireloom.invalid".equals(uri.getHost())) {
            String path = uri.getPath();
            String mime = path == null ? null : STUDIO_FILES.get(path);
            try {
                if (mime != null) {
                    InputStream stream = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mime, "UTF-8", stream);
                }
                if ("/cover".equals(path)) return imageResponse(coverThumb);
                if (path != null && path.startsWith("/illustration/")) {
                    String key = path.substring("/illustration/".length());
                    Epub.Image image = thumbnails.computeIfAbsent(key, k -> thumbnail(illustrations.get(k)));
                    return imageResponse(image);
                }
            } catch (IOException ignored) {
                // Fall through to the empty response.
            }
        }
        return emptyResponse();
    }

    private static Epub.Image thumbnail(Epub.Image image) {
        if (image == null) return null;
        try { return BookFiles.optimizeImage(image, 720, .85f); }
        catch (IOException error) { return image; }
    }

    static WebResourceResponse imageResponse(Epub.Image image) {
        return image == null ? emptyResponse() :
            new WebResourceResponse(image.mime(), null, new ByteArrayInputStream(image.bytes()));
    }

    static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Map.of(),
            new ByteArrayInputStream(new byte[0]));
    }

    WebView offlineView() {
        var view = new WebView(this);
        var settings = view.getSettings();
        settings.setBlockNetworkLoads(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        view.setBackgroundColor(Color.TRANSPARENT);
        return view;
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (ready) js("systemTheme(" + darkSystem() + ")");
    }

    private boolean darkSystem() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setChrome(boolean dark) {
        darkInterface = dark;
        int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : bars, bars);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor(dark ? "#171e2a" : "#f7f3ea"));
    }

    android.content.Context dialogContext() {
        return new android.view.ContextThemeWrapper(this, darkInterface ? R.style.StudioDark : R.style.AppTheme);
    }

    void js(String script) {
        runOnUiThread(() -> {
            if (!isDestroyed()) web.evaluateJavascript(script, null);
        });
    }

    void notice(String message) {
        js("notice(" + JSONObject.quote(message) + ")");
    }

    void error(String title, Throwable error) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            new AlertDialog.Builder(dialogContext()).setTitle(title)
                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                .setPositiveButton("OK", null).show();
        });
    }

    interface Task { void run() throws Exception; }

    void task(Task work) {
        worker.execute(() -> {
            try { work.run(); }
            catch (Exception failure) { error("Quireloom", failure); }
            finally { js("busy(false)"); }
        });
    }

    private void publish(boolean resetHistory) throws Exception {
        generation++;
        persistedState = null;
        persistedRevision = -1;
        coverThumb = thumbnail(draft.cover());
        for (var chapter : draft.chapters()) illustrations.putAll(chapter.images());
        var data = new JSONObject();
        data.put("generation", generation);
        data.put("meta", MobileBook.metadata(draft.metadata()));
        data.put("design", MobileBook.design(draft.options()));
        data.put("chapters", MobileBook.chapters(draft.chapters()));
        data.put("cover", coverThumb != null);
        js("systemTheme(" + darkSystem() + ");loadBook(" + data + "," + resetHistory + ")");
    }

    private boolean update(String json) throws Exception {
        if (draft == null) return false;
        if (json.equals(persistedState)) return true;
        var data = new JSONObject(json);
        if (data.getInt("generation") != generation) return false;
        long revision = data.optLong("revision", 0);
        if (revision < persistedRevision) return false;
        Draft edited = MobileBook.read(data, draft, illustrations);
        edited.save(current);
        draft = edited;
        persistedState = json;
        persistedRevision = revision;
        js("saved(" + generation + "," + revision + ")");
        return true;
    }

    public final class Bridge {
        @JavascriptInterface public void chrome(boolean dark) {
            runOnUiThread(() -> setChrome(dark));
        }

        @JavascriptInterface public void theme(boolean dark) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
        }

        @JavascriptInterface public String themePreference() {
            var preferences = getPreferences(MODE_PRIVATE);
            return preferences.contains("dark") ? String.valueOf(preferences.getBoolean("dark", false)) : "system";
        }

        @JavascriptInterface public String render(String row, String design) {
            try {
                var chapter = MobileBook.chapter(new JSONArray(row), illustrations);
                var options = MobileBook.design(new JSONArray(design));
                return new JSONObject().put("css", Epub.stylesheet(options)).put("html", Epub.render(chapter, options))
                    .toString();
            } catch (Exception error) {
                return "{}";
            }
        }

        @JavascriptInterface public String chaptersFromText(String text, boolean split, boolean markdown) {
            return MobileBook.chapters(Manuscript.fromPaste(text, split, markdown)).toString();
        }

        @JavascriptInterface public String polish(String text, boolean smartQuotes) {
            return Manuscript.polish(text, smartQuotes);
        }

        @JavascriptInterface public void save(String json) {
            worker.execute(() -> {
                try { update(json); }
                catch (Exception failure) {
                    notice("Draft could not be saved. " + failure.getMessage());
                }
            });
        }

        @JavascriptInterface public void go(String command, String state, int chapter, int caret) {
            task(() -> {
                if (!update(state)) return;
                switch (command) {
                    case "new" -> activate(newBookPath(), Draft.empty());
                    case "library" -> showLibrary();
                    case "external" -> openLaunchBook();
                    case "preview" -> preview(draft);
                    case "chapterPreview" -> {
                        if (chapter >= 0 && chapter < draft.chapters().size()) {
                            preview(new Draft(draft.metadata(), List.of(draft.chapters().get(chapter)), null, draft.options()));
                        }
                    }
                    case "doctor" -> publishing.report("Draft preflight", EpubDoctor.checkDraft(draft), null);
                    case "designCover" -> publishing.designCover(draft);
                    case "removeCover" -> setCover(null);
                    case "exportCover" -> exportCover();
                    case "epub", "html", "markdownZip", "backup" -> publishing.export(draft, command);
                    case "markdown", "folder", "watch" -> {
                        publishing.selectFolder(command, draft);
                        pickTree(command);
                    }
                    case "stopWatch" -> publishing.stopWatch();
                    case "open", "import", "inline", "plates", "cover", "merge", "split", "package", "check" -> {
                        insertionChapter = chapter;
                        insertionCaret = caret;
                        pick(command);
                    }
                    default -> throw new IOException("Unknown action.");
                }
            });
        }
    }

    private Path newBookPath() { return library.resolve(UUID.randomUUID() + ".ql"); }

    private void activate(Path path, Draft book) throws Exception {
        book.save(path);
        finishActivation(path, book);
    }

    private void finishActivation(Path path, Draft book) throws Exception {
        current = path;
        draft = book;
        illustrations.clear();
        thumbnails.clear();
        getPreferences(MODE_PRIVATE).edit().putString("current", path.getFileName().toString()).apply();
        publish(true);
    }

    private void showLibrary() throws Exception {
        record Book(Path path, String label, long modified) {}
        var books = new ArrayList<Book>();
        try (var paths = Files.newDirectoryStream(library, "*.ql")) {
            for (Path path : paths) {
                try {
                    var saved = Draft.read(path);
                    String title = saved.title().isBlank() ? "Untitled manuscript" : saved.title();
                    books.add(new Book(path, (path.equals(current) ? "✓  " : "") + title + "  ·  "
                        + saved.chapters().size() + " chapters", Files.getLastModifiedTime(path).toMillis()));
                } catch (IOException ignored) {
                    // Keep unreadable files available on disk for recovery.
                }
            }
        }
        books.sort(java.util.Comparator.comparingLong(Book::modified).reversed());
        runOnUiThread(() -> new AlertDialog.Builder(dialogContext()).setTitle("My books")
            .setItems(books.stream().map(Book::label).toArray(String[]::new),
                (dialog, i) -> task(() -> finishActivation(books.get(i).path(), Draft.read(books.get(i).path()))))
            .setNegativeButton("Close", null).show());
    }

    private void open(Uri uri) throws Exception {
        Path folder = Files.createTempDirectory(getCacheDir().toPath(), "open");
        try {
            Path input = stage(uri, folder, 0);
            String filename = input.getFileName().toString();
            String extension = filename.toLowerCase(Locale.ROOT);
            if (extension.endsWith(".ql")) {
                var saved = Draft.read(input);
                String inputDigest = digest(input);
                try (var paths = Files.newDirectoryStream(library, "*.ql")) {
                    for (Path path : paths) {
                        if (digest(path).equals(inputDigest)) {
                            finishActivation(path, saved);
                            return;
                        }
                    }
                }
                activate(newBookPath(), saved);
            } else if (extension.endsWith(".epub")) {
                openEpub(uri, input, filename);
            } else {
                activate(newBookPath(), importedDraft(BookFiles.read(input)));
            }
        } finally { BookFiles.deleteTree(folder); }
    }

    private void openEpub(Uri uri, Path input, String filename) throws Exception {
        Path path = library.resolve(UUID.nameUUIDFromBytes(Epub.bytes(uri.toString())) + ".ql");
        Path stampFile = path.resolveSibling(path.getFileName() + ".stamp");
        String stamp = digest(input);
        if (Files.exists(path)) {
            Draft previous = Draft.read(path);
            if (Files.exists(stampFile) && stamp.equals(new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8))) {
                finishActivation(path, previous);
                return;
            }
            Path savedInput = temp(".epub");
            Files.copy(input, savedInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            runOnUiThread(() -> {
                var dialog = new AlertDialog.Builder(dialogContext()).setTitle("EPUB source changed · " + filename)
                    .setItems(new String[]{"Resume saved edits", "Reload EPUB into the draft", "Open original in Package Studio"},
                        (selection, choice) -> task(() -> {
                            try {
                                switch (choice) {
                                    case 0 -> finishActivation(path, previous);
                                    case 1 -> importSource(savedInput, path, stampFile, stamp);
                                    case 2 -> publishing.openPackage(savedInput, filename);
                                }
                            } finally { Files.deleteIfExists(savedInput); }
                        }))
                    .setNegativeButton("Cancel", (d, w) -> savedInput.toFile().delete()).create();
                dialog.setOnCancelListener(d -> savedInput.toFile().delete());
                dialog.show();
            });
            return;
        }
        importSource(input, path, stampFile, stamp);
    }

    private void importSource(Path input, Path path, Path stampFile, String stamp) throws Exception {
        activate(path, importedDraft(BookFiles.readEpub(input, true)));
        Files.write(stampFile, Epub.bytes(stamp));
    }

    private static Draft importedDraft(BookFiles.Imported source) {
        var metadata = MobileBook.fillMetadata(Draft.empty().metadata(), source.metadata());
        if (!source.metadata().language().isBlank()) {
            var m = source.metadata();
            metadata = new Epub.Metadata(metadata.id(), m.title(), m.subtitle(), m.author(), m.language(),
                m.description(), m.publisher(), m.subjects(), m.series(), m.seriesIndex(), m.rights(), m.date());
        }
        return new Draft(metadata, source.chapters(), source.cover(), Epub.Options.editorial());
    }

    private static String digest(Path input) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(input)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        return android.util.Base64.encodeToString(sha.digest(), android.util.Base64.NO_WRAP);
    }

    private void preview(Draft book) {
        String html = Epub.exportSingleHtml(book.metadata(), book.chapters(), book.cover(), book.options());
        runOnUiThread(() -> {
            var view = offlineView();
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    return true;
                }
            });
            view.loadDataWithBaseURL("https://reader.quireloom.invalid/", html, "text/html", "UTF-8", null);
            var dialog = new AlertDialog.Builder(dialogContext()).setTitle("Reader preview").setView(view)
                .setPositiveButton("Close", null).create();
            dialog.setOnDismissListener(d -> view.destroy());
            dialog.show();
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        });
    }

    void setCover(Epub.Image image) throws Exception {
        saveDraft(new Draft(draft.metadata(), draft.chapters(), image, draft.options()));
    }

    private void saveDraft(Draft changed) throws Exception {
        changed.save(current);
        draft = changed;
        publish(false);
    }

    private void exportCover() throws Exception {
        if (draft.cover() == null) throw new IOException("Choose or design a cover first.");
        var image = draft.cover();
        makeOutput(image.extension(), "cover" + image.extension(), path -> Files.write(path, image.bytes()));
    }

    void pick(String action) {
        runOnUiThread(() -> {
            pickerAction = action;
            boolean image = List.of("cover", "inline", "plates", "packageCover", "coverPhoto").contains(action);
            boolean epub = List.of("split", "package", "check").contains(action);
            var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(image ? "image/*" : epub ? "application/epub+zip" : "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, List.of("import", "plates", "inline", "merge").contains(action));
            startActivityForResult(intent, OPEN);
        });
    }

    void pickTree(String action) {
        runOnUiThread(() -> {
            pickerAction = action;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), TREE);
        });
    }

    String displayName(Uri uri) {
        try (var cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0).replace('/', '_').replace((char) 92, '_');
            }
        }
        return "book.epub";
    }

    Path stage(Uri uri, Path directory, int index) throws IOException {
        Path parent = Files.createDirectory(directory.resolve("input" + index));
        String name = displayName(uri);
        if (name.isBlank() || name.equals(".") || name.equals("..")) throw new IOException("Invalid filename.");
        Path path = parent.resolve(name);
        try (var in = getContentResolver().openInputStream(uri); var out = Files.newOutputStream(path)) {
            if (in == null) throw new IOException("Could not read " + name);
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if ((total += count) > BookFiles.DRAFT_LIMIT) throw new IOException(name + " exceeds 256 MB.");
                out.write(buffer, 0, count);
            }
        }
        return path;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("picker", pickerAction);
        state.putInt("chapter", insertionChapter);
        state.putInt("caret", insertionCaret);
        if (pendingOutput != null) state.putString("output", pendingOutput.getFileName().toString());
        publishing.savePicker(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (!initialized) {
            deferredPickerResult = () -> handlePickerResult(request, result, data);
            return;
        }
        handlePickerResult(request, result, data);
    }

    private void handlePickerResult(int request, int result, Intent data) {
        if (request == EXPORT) {
            Path output = pendingOutput;
            pendingOutput = null;
            var callback = exportCallback;
            exportCallback = null;
            if (output != null) task(() -> {
                boolean completed = false;
                try {
                    if (result == RESULT_OK && data != null) {
                        try (var stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                            if (stream == null) throw new IOException("Could not open the export destination.");
                            Files.copy(output, stream);
                        }
                        completed = true;
                        publishing.exportFinished(data.getData(), data.getFlags());
                        if (callback == null) notice("Export saved");
                        else runOnUiThread(() -> Toast.makeText(this, "Export saved", Toast.LENGTH_SHORT).show());
                    } else publishing.cancelPendingWatch();
                } finally {
                    Files.deleteIfExists(output);
                    if (callback != null) callback.accept(completed);
                }
            });
            return;
        }
        String action = pickerAction;
        if (result != RESULT_OK || data == null) {
            publishing.cancelPicker(action);
            return;
        }
        if (request == TREE) {
            task(() -> publishing.folderSelected(action, data.getData(), data.getFlags(), draft));
        } else if (request == OPEN) {
            var uris = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) return;
            js("busy(true)");
            task(() -> importSelection(action, uris));
        }
    }

    private void importSelection(String action, List<Uri> uris) throws Exception {
        if (action.equals("open")) {
            open(uris.get(0));
            return;
        }
        Path directory = Files.createTempDirectory(getCacheDir().toPath(), "import");
        boolean retainWorkspace = false;
        try {
            var paths = new ArrayList<Path>();
            long total = 0;
            for (Uri uri : uris) {
                Path path = stage(uri, directory, paths.size());
                total += Files.size(path);
                if (total > BookFiles.DRAFT_LIMIT) throw new IOException("Selected files exceed 256 MB.");
                paths.add(path);
            }
            if (!action.equals("merge")) {
                paths.sort((a, b) -> Manuscript.compareNames(a.getFileName().toString(), b.getFileName().toString()));
            }
            switch (action) {
                case "cover" -> setCover(BookFiles.readImage(paths.get(0)));
                case "inline", "plates", "import" -> addChapters(action, paths);
                default -> {
                    publishing.process(action, paths, directory, draft);
                    retainWorkspace = List.of("merge", "split", "check").contains(action);
                }
            }
        } finally { if (!retainWorkspace) BookFiles.deleteTree(directory); }
    }

    private void addChapters(String action, List<Path> paths) throws Exception {
        var chapters = new ArrayList<>(draft.chapters());
        var metadata = draft.metadata();
        var cover = draft.cover();
        var added = new ArrayList<Epub.Chapter>();
        for (Path path : paths) {
            var source = BookFiles.read(path);
            if (source.fromEpub()) {
                if (chapters.isEmpty() && added.isEmpty() && metadata.title().isBlank()) {
                    metadata = importedDraft(source).metadata();
                } else metadata = MobileBook.fillMetadata(metadata, source.metadata());
                if (cover == null) cover = source.cover();
            }
            added.addAll(source.chapters());
            if (chapters.size() + added.size() > BookFiles.CHAPTER_LIMIT && !action.equals("inline")) {
                throw new IOException("A book can contain up to 2,000 chapters.");
            }
        }
        if (action.equals("inline")) {
            if (insertionChapter < 0 || insertionChapter >= chapters.size()) throw new IOException("Select a chapter first.");
            var old = chapters.get(insertionChapter);
            var images = new LinkedHashMap<>(old.images());
            var markers = new ArrayList<String>();
            for (var chapter : added) {
                images.putAll(chapter.images());
                markers.add(chapter.body());
            }
            int at = Math.max(0, Math.min(insertionCaret, old.body().length()));
            String body = old.body().substring(0, at) + "\n\n" + String.join("\n\n", markers)
                + "\n\n" + old.body().substring(at);
            chapters.set(insertionChapter, new Epub.Chapter(old.title(), body, old.markdown(), images,
                old.role(), old.hideHeading()));
        } else chapters.addAll(added);
        saveDraft(new Draft(metadata, chapters, cover, draft.options()));
        notice(action.equals("inline") ? "Illustrations inserted" : added.size() + " chapters imported");
    }

    Draft currentDraft() { return draft; }

    Path temp(String suffix) throws IOException {
        return Files.createTempFile(getCacheDir().toPath(), "quireloom-", suffix);
    }

    interface Output { void write(Path path) throws Exception; }

    void makeOutput(String suffix, String filename, Output write) throws Exception {
        makeOutput(suffix, filename, write, null);
    }

    void makeOutput(String suffix, String filename, Output write, java.util.function.Consumer<Boolean> callback)
        throws Exception {
        Path output = temp(suffix);
        try { write.write(output); }
        catch (Exception failure) {
            Files.deleteIfExists(output);
            throw failure;
        }
        send(output, filename, callback);
    }

    private void send(Path output, String filename, java.util.function.Consumer<Boolean> callback) {
        runOnUiThread(() -> {
            if (pendingOutput != null || isFinishing() || isDestroyed()) {
                output.toFile().delete();
                return;
            }
            pendingOutput = output;
            exportCallback = callback;
            String mime = switch (filename.substring(filename.lastIndexOf('.') + 1)) {
                case "epub" -> "application/epub+zip";
                case "html" -> "text/html";
                case "zip" -> "application/zip";
                case "png" -> "image/png";
                case "jpg", "jpeg" -> "image/jpeg";
                case "webp" -> "image/webp";
                case "gif" -> "image/gif";
                default -> "application/octet-stream";
            };
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, filename), EXPORT);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        scheduleWatch();
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(watch);
        if (ready) web.evaluateJavascript("flush()", null);
        super.onPause();
    }

    private final Runnable watch = () -> worker.execute(() -> {
        try { publishing.checkWatch(); }
        catch (Exception failure) {
            publishing.stopWatch();
            error("Folder watch stopped", failure);
        } finally { runOnUiThread(this::scheduleWatch); }
    });

    void scheduleWatch() {
        handler.removeCallbacks(watch);
        if (foreground && !isDestroyed() && publishing.isWatching()) handler.postDelayed(watch, 4000);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(watch);
        worker.shutdown();
        web.removeJavascriptInterface("Mobile");
        web.destroy();
        super.onDestroy();
    }

    private void navigateBack() {
        if (ready) web.evaluateJavascript("back()", result -> {
            if (!"true".equals(result)) finish();
        });
        else finish();
    }
}
