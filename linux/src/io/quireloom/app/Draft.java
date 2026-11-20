package io.quireloom.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

record Draft(
    Epub.Metadata metadata,
    List<Epub.Chapter> chapters,
    Epub.Image cover,
    Epub.Options options
) {
    private static final int MAGIC = 0x514c0001;
    static final int FIELD_LIMIT = 16384;

    Draft(String id, String title, String author, String language, List<Epub.Chapter> chapters, Epub.Image cover) {
        this(Epub.Metadata.basic(id, title, author, language), chapters, cover, Epub.Options.defaults());
    }

    Draft {
        metadata = metadata == null ? Epub.Metadata.basic("urn:uuid:" + UUID.randomUUID(), "", "", "en") : metadata;
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        options = options == null ? Epub.Options.defaults() : options;
    }

    String id() { return metadata.id(); }
    String title() { return metadata.title(); }
    String author() { return metadata.author(); }
    String language() { return metadata.language(); }

    static Draft empty() {
        return new Draft(
            Epub.Metadata.basic("urn:uuid:" + UUID.randomUUID(), "", "", "en"),
            List.of(), null, Epub.Options.editorial());
    }

    Draft pruned() {
        var cleaned = new ArrayList<Epub.Chapter>(chapters.size());
        for (var ch : chapters) cleaned.add(ch.pruneUnusedImages());
        return new Draft(metadata, cleaned, cover, options);
    }

    static Draft read(Path path) throws IOException {
        if (Files.notExists(path)) return empty();
        if (Files.size(path) > (long) BookFiles.DRAFT_LIMIT + BookFiles.COVER_LIMIT) {
            throw new IOException("Draft exceeds the size limit.");
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC) throw new IOException("Unknown draft format.");
            String id = string(input, FIELD_LIMIT);
            String title = string(input, FIELD_LIMIT);
            String author = string(input, FIELD_LIMIT);
            String language = string(input, FIELD_LIMIT);
            int count = input.readInt();
            if (count < 0 || count > BookFiles.CHAPTER_LIMIT) throw new IOException("Invalid chapter count.");
            var chapters = new ArrayList<Epub.Chapter>(count);
            for (int i = 0; i < count; i++) {
                chapters.add(new Epub.Chapter(
                    string(input, FIELD_LIMIT),
                    string(input, BookFiles.TEXT_LIMIT * 4),
                    input.readBoolean()));
            }
            Epub.Image cover = null;
            if (input.readBoolean()) {
                String mime = string(input, FIELD_LIMIT);
                if (!isSupportedImageMime(mime)) throw new IOException("Invalid cover format.");
                cover = new Epub.Image(mime, bytes(input, BookFiles.COVER_LIMIT));
            }
            Epub.Metadata meta = Epub.Metadata.basic(id, title, author, language);
            Epub.Options opts = Epub.Options.defaults();

            int extension;
            while ((extension = input.read()) != -1) {
                if (extension == 1) {
                    for (int i = 0; i < chapters.size(); i++) {
                        int size = input.readInt();
                        if (size < 0 || size > 10000) throw new IOException("Invalid illustration count.");
                        var images = new LinkedHashMap<String, Epub.Image>();
                        for (int j = 0; j < size; j++) {
                            String file = string(input, FIELD_LIMIT);
                            String mime = guessMime(file);
                            images.put(file, new Epub.Image(mime, bytes(input, BookFiles.COVER_LIMIT)));
                        }
                        var c = chapters.get(i);
                        chapters.set(i, new Epub.Chapter(c.title(), c.body(), c.markdown(), images, c.role(), c.hideHeading()));
                    }
                } else if (extension == 2) {
                    String subtitle = string(input, FIELD_LIMIT);
                    String description = string(input, BookFiles.TEXT_LIMIT);
                    String publisher = string(input, FIELD_LIMIT);
                    String subjects = string(input, FIELD_LIMIT);
                    String series = string(input, FIELD_LIMIT);
                    String seriesIndex = string(input, FIELD_LIMIT);
                    String rights = string(input, FIELD_LIMIT);
                    String date = string(input, FIELD_LIMIT);
                    meta = new Epub.Metadata(id, title, subtitle, author, language,
                        description, publisher, subjects, series, seriesIndex, rights, date);

                    Epub.BookTheme theme = Epub.BookTheme.fromName(string(input, FIELD_LIMIT));
                    String sceneBreak = string(input, FIELD_LIMIT);
                    boolean smartTypo = input.readBoolean();
                    boolean dropCaps = input.readBoolean();
                    boolean titlePage = input.readBoolean();
                    boolean copyrightPage = input.readBoolean();
                    boolean includeNcx = input.readBoolean();
                    String customCss = string(input, BookFiles.TEXT_LIMIT);
                    opts = new Epub.Options(theme, sceneBreak, smartTypo, dropCaps,
                        titlePage, copyrightPage, includeNcx, customCss);

                    int roleCount = input.readInt();
                    if (roleCount != chapters.size()) throw new IOException("Invalid chapter metadata count.");
                    for (int i = 0; i < roleCount; i++) {
                        Epub.ChapterRole role = Epub.ChapterRole.fromName(string(input, FIELD_LIMIT));
                        boolean hideHeading = input.readBoolean();
                        var c = chapters.get(i);
                        chapters.set(i, new Epub.Chapter(c.title(), c.body(), c.markdown(), c.images(), role, hideHeading));
                    }
                } else {
                    throw new IOException("Unknown draft extension: " + extension);
                }
            }
            return new Draft(meta, List.copyOf(chapters), cover, opts);
        }
    }

    void save(Path path) throws IOException {
        Draft clean = pruned();
        BookFiles.atomicWrite(path, destination -> {
            try (var output = new DataOutputStream(new BufferedOutputStream(destination))) {
                output.writeInt(MAGIC);
                string(output, clean.metadata.id());
                string(output, clean.metadata.title());
                string(output, clean.metadata.author());
                string(output, clean.metadata.language());
                output.writeInt(clean.chapters.size());
                long remaining = BookFiles.DRAFT_LIMIT - 256_000L;
                for (var chapter : clean.chapters) {
                    string(output, chapter.title());
                    byte[] body = chapter.body().getBytes(StandardCharsets.UTF_8);
                    remaining -= body.length + chapter.title().getBytes(StandardCharsets.UTF_8).length + 9L;
                    if (remaining < 0) throw new IOException("The draft exceeds 256 MB.");
                    output.writeInt(body.length);
                    output.write(body);
                    output.writeBoolean(chapter.markdown());
                }
                for (var chapter : clean.chapters) {
                    for (var image : chapter.images().values()) {
                        remaining -= image.bytes().length + 100L;
                        if (remaining < 0) throw new IOException("The illustrated draft exceeds 256 MB.");
                    }
                }
                output.writeBoolean(clean.cover != null);
                if (clean.cover != null) {
                    string(output, clean.cover.mime());
                    output.writeInt(clean.cover.bytes().length);
                    output.write(clean.cover.bytes());
                }
                if (clean.chapters.stream().anyMatch(c -> !c.images().isEmpty())) {
                    output.writeByte(1);
                    for (var c : clean.chapters) {
                        output.writeInt(c.images().size());
                        for (var entry : c.images().entrySet()) {
                            string(output, entry.getKey());
                            byte[] data = entry.getValue().bytes();
                            output.writeInt(data.length);
                            output.write(data);
                        }
                    }
                }
                // Extension 2: extended metadata, typography/design options, and chapter roles.
                output.writeByte(2);
                string(output, clean.metadata.subtitle());
                longString(output, clean.metadata.description());
                string(output, clean.metadata.publisher());
                string(output, clean.metadata.subjects());
                string(output, clean.metadata.series());
                string(output, clean.metadata.seriesIndex());
                string(output, clean.metadata.rights());
                string(output, clean.metadata.date());

                string(output, clean.options.theme().name());
                string(output, clean.options.sceneBreak());
                output.writeBoolean(clean.options.smartTypography());
                output.writeBoolean(clean.options.dropCaps());
                output.writeBoolean(clean.options.includeTitlePage());
                output.writeBoolean(clean.options.includeCopyright());
                output.writeBoolean(clean.options.includeNcx());
                longString(output, clean.options.customCss());

                output.writeInt(clean.chapters.size());
                for (var c : clean.chapters) {
                    string(output, c.role().name());
                    output.writeBoolean(c.hideHeading());
                }
            }
        });
    }

    void export(Path destination) throws IOException {
        String lang = exportLanguage();
        var exportMeta = new Epub.Metadata(
            metadata.id(), metadata.title().strip(), metadata.subtitle().strip(),
            metadata.author().strip(), lang, metadata.description().strip(),
            metadata.publisher().strip(), metadata.subjects().strip(),
            metadata.series().strip(), metadata.seriesIndex().strip(),
            metadata.rights().strip(), metadata.date().strip());
        BookFiles.atomicWrite(destination, output ->
            Epub.write(output, exportMeta, chapters, cover, options));
    }

    void exportHtml(Path destination) throws IOException {
        String lang = exportLanguage();
        var exportMeta = new Epub.Metadata(
            metadata.id(), metadata.title().strip(), metadata.subtitle().strip(),
            metadata.author().strip(), lang, metadata.description().strip(),
            metadata.publisher().strip(), metadata.subjects().strip(),
            metadata.series().strip(), metadata.seriesIndex().strip(),
            metadata.rights().strip(), metadata.date().strip());
        String html = Epub.exportSingleHtml(exportMeta, chapters, cover, options);
        BookFiles.atomicWrite(destination, output -> output.write(html.getBytes(StandardCharsets.UTF_8)));
    }

    String exportLanguage() throws IOException {
        if (title().isBlank() || chapters.isEmpty()) throw new IOException("Add a book title and at least one chapter.");
        if (chapters.stream().anyMatch(chapter -> chapter.title().isBlank())) {
            throw new IOException("Each chapter needs a title.");
        }
        String lang = language().isBlank() ? "en" : language().strip();
        try {
            new Locale.Builder().setLanguageTag(lang);
        } catch (IllformedLocaleException error) {
            throw new IOException("Enter a language tag such as en, en-GB, or bn.", error);
        }
        return lang;
    }

    private static boolean isSupportedImageMime(String mime) {
        return List.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml").contains(mime);
    }

    private static String guessMime(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    private static String string(DataInputStream input, int limit) throws IOException {
        return new String(bytes(input, limit), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > limit) throw new IOException("Invalid draft field length.");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void string(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FIELD_LIMIT) throw new IOException("A title or metadata field is too long.");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void longString(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BookFiles.TEXT_LIMIT) throw new IOException("A metadata field or custom CSS is too long.");
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
