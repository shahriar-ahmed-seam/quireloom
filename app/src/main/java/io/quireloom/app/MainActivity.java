package io.quireloom.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int IMPORT = 1, COVER = 2, EXPORT = 3;
    private static final int PAPER = 0xfff7f3ea, INK = 0xff24372e, GREEN = 0xff285648;
    private final List<Epub.Chapter> chapters = new ArrayList<>();
    private String id = "urn:uuid:" + UUID.randomUUID();
    private String title = "", author = "", language = "en";
    private Epub.Image cover;
    private LinearLayout root, chapterList, coverRow;
    private AtomicFile draft;
    private boolean draftReadable = true;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable saveSoon = this::save;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        draft = new AtomicFile(new File(getFilesDir(), "draft.json"));
        load();
        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        root = column();
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() |
                    android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        var wordmark = text("Quireloom", 37);
        wordmark.setTypeface(Typeface.SERIF, Typeface.BOLD);
        root.addView(wordmark);
        var subtitle = text("EPUB workspace", 15);
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle);
        var actions = row();
        actions.addView(button("Add files", () -> pick(IMPORT, "*/*", true)), weight());
        actions.addView(button("Paste text", this::paste), weight());
        root.addView(actions);

        var details = card();
        details.addView(text("Book details", 20));
        var bookTitle = field("Book title", title, false);
        var bookAuthor = field("Author", author, false);
        var bookLanguage = field("Language tag (e.g. en, bn)", language, false);
        bookTitle.setId(android.view.View.generateViewId());
        bookAuthor.setId(android.view.View.generateViewId());
        bookLanguage.setId(android.view.View.generateViewId());
        details.addView(bookTitle);
        details.addView(bookAuthor);
        details.addView(bookLanguage);
        watch(bookTitle, value -> title = value);
        watch(bookAuthor, value -> author = value);
        watch(bookLanguage, value -> language = value);
        coverRow = row();
        details.addView(coverRow);
        drawCover();
        root.addView(details);

        chapterList = column();
        root.addView(chapterList);
        drawChapters();
        var export = button("Export EPUB", this::export);
        export.setBackground(background(GREEN, 22));
        export.setTextColor(Color.WHITE);
        root.addView(export);
    }

    private void pick(int request, String type, boolean multiple) {
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type)
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        if (request == COVER) intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg"});
        startActivityForResult(intent, request);
    }

    private void paste() {
        var fields = card();
        var input = field("Paste your manuscript", "", true);
        var split = new CheckBox(this);
        split.setText("Detect chapter headings");
        split.setChecked(true);
        var markdown = new CheckBox(this);
        markdown.setText("Interpret as Markdown");
        fields.addView(input);
        fields.addView(split);
        fields.addView(markdown);
        var dialog = new AlertDialog.Builder(this).setTitle("Paste text").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Add chapters", null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            var added = Manuscript.fromPaste(input.getText().toString(), split.isChecked(), markdown.isChecked());
            if (added.isEmpty()) {
                input.setError("Paste some text first");
                return;
            }
            chapters.addAll(added);
            changed();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void drawCover() {
        coverRow.removeAllViews();
        if (cover != null) {
            var image = new ImageView(this);
            var opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(cover.bytes(), 0, cover.bytes().length, opts);
            opts.inSampleSize = Math.max(1, Math.max(opts.outWidth, opts.outHeight) / 200);
            opts.inJustDecodeBounds = false;
            image.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(cover.bytes(), 0, cover.bytes().length, opts));
            image.setContentDescription("Book cover");
            coverRow.addView(image, new LinearLayout.LayoutParams(dp(49), dp(66)));
        }
        coverRow.addView(button(cover == null ? "Add cover" : "Change cover", () -> pick(COVER, "image/*", false)), weight());
        if (cover != null) coverRow.addView(button("Remove", () -> {
            cover = null;
            drawCover();
            scheduleSave();
        }));
    }

    private void drawChapters() {
        chapterList.removeAllViews();
        var label = text("Chapters · " + chapters.size(), 21);
        label.setPadding(0, dp(15), 0, dp(7));
        chapterList.addView(label);
        if (chapters.isEmpty()) {
            var empty = card();
            empty.addView(text("Add Markdown files or paste text to start your book.", 16));
            chapterList.addView(empty);
        }
        for (int i = 0; i < chapters.size(); i++) {
            int index = i;
            var chapter = chapters.get(i);
            var tile = card();
            tile.addView(text((i + 1) + "   " + chapter.title(), 18));
            var controls = row();
            controls.addView(button("Preview", () -> preview(chapter)), weight());
            controls.addView(button("Edit", () -> edit(index)), weight());
            controls.addView(button("Move", () -> move(index)), weight());
            tile.addView(controls);
            chapterList.addView(tile);
        }
    }

    private void preview(Epub.Chapter chapter) {
        var web = new WebView(this);
        web.getSettings().setBlockNetworkLoads(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.loadDataWithBaseURL(null, "<meta charset='utf-8'><meta name='viewport' content='width=device-width'>" +
            "<style>" + Epub.CSS + "</style>" + Epub.render(chapter), "text/html", "UTF-8", null);
        var dialog = new AlertDialog.Builder(this).setTitle("Chapter preview").setView(web)
            .setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(event -> {
            if (web.getParent() instanceof android.view.ViewGroup parent) parent.removeView(web);
            web.destroy();
        });
        dialog.show();
        dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void edit(int index) {
        var chapter = chapters.get(index);
        var fields = card();
        var name = field("Chapter title", chapter.title(), false);
        var body = field("Chapter text", chapter.body(), true);
        var markdown = new CheckBox(this);
        markdown.setText("Interpret as Markdown");
        markdown.setChecked(chapter.markdown());
        fields.addView(name);
        fields.addView(body);
        fields.addView(markdown);
        var dialog = new AlertDialog.Builder(this).setTitle("Edit chapter").setView(fields)
            .setPositiveButton("Save", null).setNeutralButton("Delete", (d, w) ->
                new AlertDialog.Builder(this).setTitle("Delete this chapter?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (confirm, which) -> {
                        chapters.remove(index);
                        changed();
                    }).show())
            .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().strip();
            if (value.isEmpty()) {
                name.setError("Enter a chapter title");
                return;
            }
            chapters.set(index, new Epub.Chapter(value, body.getText().toString(), markdown.isChecked(),
                chapter.images(), chapter.role(), chapter.hideHeading()));
            changed();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void move(int index) {
        var position = field("Position 1–" + chapters.size(), String.valueOf(index + 1), false);
        position.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        var dialog = new AlertDialog.Builder(this).setTitle("Move chapter").setView(position)
            .setNegativeButton("Cancel", null).setPositiveButton("Move", null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int to = Integer.parseInt(position.getText().toString()) - 1;
                if (to < 0 || to >= chapters.size()) throw new NumberFormatException();
                chapters.add(to, chapters.remove(index));
                changed();
                dialog.dismiss();
            } catch (NumberFormatException error) {
                position.setError("Enter a position from 1 to " + chapters.size());
            }
        }));
        dialog.show();
    }

    private void export() {
        if (chapters.isEmpty() || title.isBlank()) {
            toast("Add a book title and at least one chapter.");
            return;
        }
        if (!language.isBlank() && !language.strip().matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) {
            toast("Enter a language tag such as en, en-GB, or bn.");
            return;
        }
        String filename = title.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/epub+zip")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, filename + ".epub"), EXPORT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == IMPORT) {
            var uris = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) uris.add(data.getData());
            io.execute(() -> importFiles(uris));
        } else if (data.getData() != null && request == COVER) {
            Uri uri = data.getData();
            io.execute(() -> importCover(uri));
        } else if (data.getData() != null && request == EXPORT) {
            var snapshot = List.copyOf(chapters);
            String name = title.strip(), byline = author.strip(), lang = language.isBlank() ? "en" : language.strip();
            var art = cover;
            Uri uri = data.getData();
            io.execute(() -> {
                try (var stream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new IOException("Cannot open destination");
                    Epub.write(stream, id, name, byline, lang, snapshot, art);
                    runOnUiThread(() -> toast("EPUB exported"));
                } catch (IOException error) {
                    runOnUiThread(() -> toast("Export failed. The destination may contain an incomplete file."));
                }
            });
        }
    }

    private void importFiles(List<Uri> uris) {
        record Source(String name, Epub.Chapter chapter) {}
        var imported = new ArrayList<Source>();
        var failed = new ArrayList<String>();
        for (Uri uri : uris) {
            String name = uri.getLastPathSegment();
            try {
                name = displayName(uri);
                if (!name.matches("(?is).+\\.(md|markdown|txt)")) throw new IOException("Unsupported extension");
                String body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(read(uri, 4 * 1024 * 1024))).toString();
                imported.add(new Source(name, Manuscript.fromFile(name, body)));
            } catch (IOException | RuntimeException error) {
                failed.add(name == null ? "Unknown file" : name);
            }
        }
        imported.sort((a, b) -> Manuscript.compareNames(a.name(), b.name()));
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            for (Source source : imported) chapters.add(source.chapter());
            if (!imported.isEmpty()) changed();
            if (failed.isEmpty()) toast("Imported " + imported.size() + " chapter(s)");
            else new AlertDialog.Builder(this).setTitle("Some files could not be imported")
                .setMessage("Use UTF-8 .md, .markdown, or .txt files up to 4 MB each.\n\n" + String.join("\n", failed))
                .setPositiveButton("OK", null).show();
        });
    }

    private void importCover(Uri uri) {
        try {
            byte[] image = read(uri, 8 * 1024 * 1024);
            var opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, opts);
            if (!"image/jpeg".equals(opts.outMimeType) && !"image/png".equals(opts.outMimeType)) {
                throw new IOException("Unsupported image");
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) throw new IOException("Invalid image");
            var selected = new Epub.Image(opts.outMimeType, image);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                cover = selected;
                drawCover();
                scheduleSave();
            });
        } catch (IOException | RuntimeException error) {
            runOnUiThread(() -> toast("Select a readable JPG or PNG up to 8 MB."));
        }
    }

    private String displayName(Uri uri) throws IOException {
        try (var cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        throw new IOException("File name unavailable");
    }

    private byte[] read(Uri uri, int limit) throws IOException {
        try (InputStream stream = getContentResolver().openInputStream(uri);
             var output = new java.io.ByteArrayOutputStream()) {
            if (stream == null) throw new IOException("File cannot be opened");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("File is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void load() {
        try {
            var json = new JSONObject(new String(draft.readFully(), StandardCharsets.UTF_8));
            id = json.getString("id");
            title = json.optString("title");
            author = json.optString("author");
            language = json.optString("language", "en");
            var list = json.getJSONArray("chapters");
            for (int i = 0; i < list.length(); i++) {
                var chapter = list.getJSONObject(i);
                chapters.add(new Epub.Chapter(chapter.getString("title"), chapter.getString("body"),
                    chapter.optBoolean("markdown", true)));
            }
            if (json.has("cover")) cover = new Epub.Image(json.getString("mime"), Base64.decode(json.getString("cover"), Base64.DEFAULT));
        } catch (java.io.FileNotFoundException ignored) {
            // First launch has no draft.
        } catch (Exception error) {
            draftReadable = false;
            toast("The saved draft could not be opened. Autosave is disabled for this session.");
        }
    }

    private void changed() {
        drawChapters();
        scheduleSave();
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveSoon);
        handler.postDelayed(saveSoon, 500);
    }

    private void save() {
        if (!draftReadable) return;
        java.io.FileOutputStream output = null;
        try {
            var list = new JSONArray();
            for (var chapter : chapters) list.put(new JSONObject().put("title", chapter.title())
                .put("body", chapter.body()).put("markdown", chapter.markdown()));
            var json = new JSONObject().put("id", id).put("title", title).put("author", author)
                .put("language", language).put("chapters", list);
            if (cover != null) json.put("mime", cover.mime()).put("cover", Base64.encodeToString(cover.bytes(), Base64.NO_WRAP));
            output = draft.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            draft.finishWrite(output);
        } catch (Exception error) {
            if (output != null) draft.failWrite(output);
            toast("Could not save the draft.");
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(saveSoon);
        save();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(saveSoon);
        io.shutdown();
        super.onDestroy();
    }

    private void watch(EditText input, Consumer<String> update) {
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                update.accept(text.toString());
                scheduleSave();
            }
            public void afterTextChanged(Editable text) {}
        });
    }

    private LinearLayout column() {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        var layout = new LinearLayout(this);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        var layout = column();
        layout.setPadding(dp(13), dp(10), dp(13), dp(10));
        layout.setBackground(background(0xfffffcf5, 15));
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        layout.setLayoutParams(params);
        return layout;
    }

    private GradientDrawable background(int color, int radius) {
        var shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(String value, int size) {
        var text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(INK);
        return text;
    }

    private EditText field(String hint, String value, boolean multiline) {
        var input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(!multiline);
        if (multiline) {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(4);
            input.setMaxLines(10);
            input.setGravity(android.view.Gravity.TOP);
        }
        return input;
    }

    private Button button(String label, Runnable action) {
        var button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(GREEN);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
