package io.quireloom.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

final class CoverDesigner {
    private static final String[] TEMPLATES = {
        "Classic Literary", "Minimalist Modern", "Swiss Typographic", "Split Folio", "Nocturne Sky", "Photo Overlay"
    };
    private record Palette(String name, int primary, int secondary, int accent, int text) {}
    private static final Palette[] PALETTES = {
        new Palette("Forest & Parchment", 0xff1e4035, 0xff132b23, 0xffd4af6a, 0xfff7f2e7),
        new Palette("Midnight & Gold", 0xff141b2d, 0xff0b0f1a, 0xffe0b45c, 0xfff5f3ee),
        new Palette("Warm Cream & Ink", 0xfff4efe4, 0xffe6dec9, 0xff285648, 0xff1d2822),
        new Palette("Terracotta & Sand", 0xff8e3b2e, 0xff5b2118, 0xfff0c987, 0xfffaf4ec),
        new Palette("Deep Plum & Silver", 0xff37223d, 0xff1e1124, 0xffc9b8d9, 0xfff7f3fa),
        new Palette("Ocean Slate & Coral", 0xff1f3647, 0xff11202b, 0xffe87a5d, 0xfff4f7f9),
        new Palette("Obsidian & Crimson", 0xff18181b, 0xff09090b, 0xffc83e37, 0xfffafafa),
        new Palette("Sage & Espresso", 0xffd8e2d8, 0xffbccbbb, 0xff3d2e24, 0xff231b16)
    };
    private static final int WIDTH = 1600, HEIGHT = 2560;
    private record Spec(int template, Palette palette, String title, String subtitle, String author,
                        String series, String publisher, boolean serif, Epub.Image photo) {}

    interface Apply { void cover(Epub.Image image) throws Exception; }
    private final Studio studio;
    private final Draft draft;
    private final Apply apply;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile Epub.Image photo;
    private ImageView preview;
    private Bitmap previewBitmap;
    private Runnable updatePreview;
    private boolean open;

    CoverDesigner(Studio studio, Draft draft, Apply apply) {
        this.studio = studio;
        this.draft = draft;
        this.apply = apply;
        photo = draft.cover();
    }

    void setPhoto(Epub.Image image) {
        photo = image;
        studio.runOnUiThread(() -> { if (open) updatePreview.run(); });
    }

    void show() {
        studio.runOnUiThread(() -> {
            var form = new StudioForm(studio.dialogContext());
            var template = form.select("Template", TEMPLATES, 0);
            var palette = form.select("Palette", java.util.Arrays.stream(PALETTES).map(Palette::name).toArray(String[]::new), 0);
            preview = new ImageView(studio);
            preview.setContentDescription("Cover design preview");
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            form.fields.addView(preview, new LinearLayout.LayoutParams(-1, form.dp(230)));
            var title = form.text("Title", draft.title(), false);
            var subtitle = form.text("Subtitle", draft.metadata().subtitle(), false);
            var author = form.text("Author", draft.author(), false);
            var series = form.text("Series badge", draft.metadata().series(), false);
            var publisher = form.text("Publisher", draft.metadata().publisher(), false);
            var serif = form.check("Serif title", true);
            var choosePhoto = new Button(studio.dialogContext());
            choosePhoto.setAllCaps(false);
            choosePhoto.setText("Choose background photo");
            choosePhoto.setOnClickListener(v -> studio.pick("coverPhoto"));
            form.fields.addView(choosePhoto);
            java.util.function.Supplier<Spec> spec = () -> new Spec(template.getSelectedItemPosition(),
                PALETTES[palette.getSelectedItemPosition()], title.getText().toString().strip(),
                subtitle.getText().toString().strip(), author.getText().toString().strip(),
                series.getText().toString().strip(), publisher.getText().toString().strip(),
                serif.isChecked(), photo);

            Runnable draw = () -> {
                Spec next = spec.get();
                choosePhoto.setVisibility(next.template() == 5 ? android.view.View.VISIBLE : android.view.View.GONE);
                studio.task(() -> {
                    Bitmap image = render(next, 240, 384);
                    studio.runOnUiThread(() -> {
                        if (!open) { image.recycle(); return; }
                        Bitmap previous = previewBitmap;
                        previewBitmap = image;
                        preview.setImageBitmap(image);
                        if (previous != null) previous.recycle();
                    });
                });
            };
            updatePreview = () -> {
                handler.removeCallbacks(draw);
                handler.postDelayed(draw, 140);
            };
            var selection = new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                    updatePreview.run();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            };
            template.setOnItemSelectedListener(selection);
            palette.setOnItemSelectedListener(selection);
            var textChanges = new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview.run(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            };
            for (var field : java.util.List.of(title, subtitle, author, series, publisher)) {
                field.addTextChangedListener(textChanges);
            }
            serif.setOnCheckedChangeListener((button, checked) -> updatePreview.run());

            open = true;
            var dialog = new android.app.AlertDialog.Builder(studio.dialogContext()).setTitle("Cover Designer")
                .setView(form.scroll()).setPositiveButton("Apply cover", null)
                .setNeutralButton("Save PNG", null).setNegativeButton("Cancel", null).create();
            dialog.setOnDismissListener(d -> {
                open = false;
                handler.removeCallbacks(draw);
                preview.setImageDrawable(null);
                if (previewBitmap != null) previewBitmap.recycle();
                previewBitmap = null;
            });
            dialog.show();
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Spec value = spec.get();
                if (!valid(value, title)) return;
                dialog.dismiss();
                studio.task(() -> apply.cover(encode(value)));
            });
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                Spec value = spec.get();
                if (!valid(value, title)) return;
                studio.task(() -> studio.makeOutput(".png", PublishingDesk.filename(value.title()) + " cover.png",
                    file -> java.nio.file.Files.write(file, encode(value).bytes())));
            });
            updatePreview.run();
        });
    }

    private boolean valid(Spec spec, android.widget.EditText title) {
        if (spec.title().isBlank()) {
            title.setError("Enter a cover title");
            title.requestFocus();
            return false;
        }
        if (spec.template() == 5 && spec.photo() == null) {
            studio.error("Photo Overlay", new IOException("Choose a background photo."));
            return false;
        }
        return true;
    }

    private static Epub.Image encode(Spec spec) throws IOException {
        var bitmap = render(spec, WIDTH, HEIGHT);
        try {
            var out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Could not render the cover.");
            if (out.size() > BookFiles.COVER_LIMIT) throw new IOException("The cover exceeds 16 MB.");
            return new Epub.Image("image/png", out.toByteArray());
        } finally { bitmap.recycle(); }
    }

    private static Bitmap render(Spec s, int width, int height) {
        var image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        var canvas = new Canvas(image);
        canvas.scale(width / 1600f, height / 2560f);
        var pen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        var p = s.palette();
        pen.setShader(new LinearGradient(0, 0, 640, HEIGHT, p.primary(), p.secondary(), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, pen);
        pen.setShader(null);
        int ink = p.text(), accent = p.accent();
        boolean centered = s.template() != 1 && s.template() != 2;
        int left = s.template() == 1 ? 220 : 162;
        int lineWidth = WIDTH - left - 162;
        float titleTop = 520;
        switch (s.template()) {
            case 0 -> {
                pen.setColor(accent);
                pen.setStyle(Paint.Style.STROKE);
                pen.setStrokeWidth(5);
                canvas.drawRect(88, 88, 1512, 2472, pen);
                pen.setStrokeWidth(2);
                canvas.drawRect(112, 112, 1488, 2448, pen);
                pen.setStyle(Paint.Style.FILL);
                titleTop = 612;
            }
            case 1 -> {
                pen.setColor(accent);
                canvas.drawRect(0, 0, 70, HEIGHT, pen);
                canvas.drawRect(left, 1650, left + 275, 1662, pen);
            }
            case 2 -> {
                pen.setColor(accent);
                canvas.drawRect(0, 1550, WIDTH, 1618, pen);
                pen.setAlpha(65);
                canvas.drawCircle(1260, 1630, 480, pen);
                titleTop = 441;
            }
            case 3 -> {
                pen.setColor(p.secondary());
                canvas.drawRect(0, 1620, WIDTH, HEIGHT, pen);
                pen.setColor(accent);
                canvas.drawRect(0, 1608, WIDTH, 1624, pen);
                pen.setAlpha(40);
                canvas.drawRoundRect(580, 243, 1020, 683, 80, 80, pen);
            }
            case 4 -> {
                pen.setColor(accent);
                pen.setAlpha(96);
                pen.setStyle(Paint.Style.STROKE);
                pen.setStrokeWidth(3);
                for (int r : new int[]{325, 540, 752}) canvas.drawCircle(800, 1620, r, pen);
                pen.setStyle(Paint.Style.FILL);
                var points = new Random(43);
                for (int n = 0; n < 96; n++) {
                    canvas.drawCircle(points.nextInt(WIDTH), 900 + points.nextInt(HEIGHT - 900), 2 + points.nextInt(6), pen);
                }
                ornament(canvas, pen, accent, 1620);
            }
            case 5 -> {
                if (s.photo() != null) {
                    drawPhoto(canvas, pen, s.photo(), width, height);
                    pen.setAlpha(255);
                    pen.setShader(new LinearGradient(0, 0, 0, HEIGHT, new int[]{0xd0000000, 0x88000000, 0xe5000000},
                        new float[]{0, .57f, 1}, Shader.TileMode.CLAMP));
                    canvas.drawRect(0, 0, WIDTH, HEIGHT, pen);
                    pen.setShader(null);
                    ink = Color.WHITE;
                }
            }
        }
        String title = s.title().isBlank() ? "Untitled manuscript" : s.title();
        var titleFace = Typeface.create((s.serif() && s.template() != 2) ? "serif" : "sans-serif", Typeface.BOLD);
        if (s.template() == 2) title = title.toUpperCase(Locale.ROOT);
        drawText(canvas, s.series().toUpperCase(Locale.ROOT), left, 305, lineWidth, 46, 118, accent,
            Typeface.DEFAULT_BOLD, centered);
        float after = drawText(canvas, title, left, titleTop, lineWidth, 165, 740, ink, titleFace, centered);
        if (s.template() == 0) ornament(canvas, pen, accent, after + 103);
        drawText(canvas, s.subtitle(), left, after + (s.template() == 0 ? 175 : 75), lineWidth, 66, 240,
            ink, Typeface.create("serif", Typeface.ITALIC), centered);
        drawText(canvas, s.author(), left, 1970, lineWidth, 72, 170, ink, Typeface.create("serif", Typeface.NORMAL), centered);
        drawText(canvas, s.publisher().toUpperCase(Locale.ROOT), left, 2250, lineWidth, 40, 118, accent,
            Typeface.DEFAULT_BOLD, centered);
        return image;
    }

    private static void drawPhoto(Canvas canvas, Paint pen, Epub.Image image, int width, int height) {
        byte[] bytes = image.bytes();
        var options = BookFiles.dimensions(bytes);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.min(options.outWidth, options.outHeight) / options.inSampleSize > Math.min(width, height) * 2
            || Math.max(options.outWidth, options.outHeight) / options.inSampleSize > Math.max(width, height) * 3) {
            options.inSampleSize *= 2;
        }
        var photo = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (photo == null) return;
        try {
            float scale = Math.max(WIDTH / (float) photo.getWidth(), HEIGHT / (float) photo.getHeight());
            float x = (WIDTH - photo.getWidth() * scale) / 2;
            float y = (HEIGHT - photo.getHeight() * scale) / 2;
            canvas.drawBitmap(photo, null, new RectF(x, y, WIDTH - x, HEIGHT - y), pen);
        } finally { photo.recycle(); }
    }

    private static void ornament(Canvas canvas, Paint pen, int color, float y) {
        pen.setColor(color);
        pen.setStrokeWidth(3);
        canvas.drawLine(500, y, 758, y, pen);
        canvas.drawLine(842, y, 1100, y, pen);
        canvas.save();
        canvas.rotate(45, 800, y);
        canvas.drawRect(782, y - 18, 818, y + 18, pen);
        canvas.restore();
    }

    private static float drawText(Canvas canvas, String text, int x, float y, int width, int size,
                                  int maxHeight, int color, Typeface typeface, boolean centered) {
        if (text.isBlank()) return y;
        var paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(typeface);
        paint.setColor(color);
        StaticLayout layout;
        do {
            paint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false).setAlignment(centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.1f).build();
            size -= 4;
        } while (layout.getHeight() > maxHeight && size >= 24);
        canvas.save();
        canvas.clipRect(x, y, x + width, y + maxHeight);
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
        return y + Math.min(layout.getHeight(), maxHeight);
    }
}
