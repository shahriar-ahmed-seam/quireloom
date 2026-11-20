package io.quireloom.app;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

public final class DesktopApp {
    static final String VERSION = "1.0.0";
    private static final Map<Path, JFrame> windows = new LinkedHashMap<>();
    private static final Map<Path, Workspace> workspaces = new LinkedHashMap<>();
    private static Path directory;
    private static File lastFolder;
    private static final int RECENT_LIMIT = 10;

    public static void main(String[] args) {
        if (args.length >= 1) {
            String cmd = args[0];
            if (cmd.equals("--version") || cmd.equals("-v")) {
                System.out.println("Quireloom " + VERSION + " for Linux");
                return;
            }
            if (cmd.equals("--help") || cmd.equals("-h") || cmd.equals("help")) {
                printCliHelp();
                return;
            }
            if (Set.of("build", "merge", "split", "check", "fix", "info").contains(cmd)) {
                System.exit(runCli(cmd, Arrays.copyOfRange(args, 1, args.length)));
                return;
            }
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Quireloom GUI needs a graphical desktop session. Run 'quireloom --help' for CLI commands.");
            System.exit(1);
        }
        try {
            SwingUtilities.invokeLater(() -> launch(args));
        } catch (AWTError | LinkageError error) {
            System.err.println("Quireloom needs a Java desktop runtime and access to a graphical session.");
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    // --- Unified Zero-Dependency CLI ---

    private static void printCliHelp() {
        System.out.println("""
            Quireloom %s — Offline EPUB Studio & Publishing CLI

            Usage:
              quireloom [files...]                         Open Quireloom desktop studio
              quireloom build <inputs...> [options]        Build EPUB 3.3 from folder/MD/TXT/PDF/images/EPUBs
              quireloom merge <epubs...> [options]         Losslessly merge EPUB 2/3 books into an omnibus
              quireloom split <in.epub> -o <dir> [options] Split an EPUB into standalone volumes
              quireloom check <book.epub>                  Run EPUB Doctor preflight validation
              quireloom fix <in.epub> -o <out.epub> [opts] Auto-repair, upgrade to EPUB 3.3 & optimize images
              quireloom info <book.epub>                   Print book metadata, stats & table of contents

            Common build / merge options:
              -o, --output <file>         Output .epub path
              --title <text>              Book or omnibus title
              --author <text>             Author / editor name
              --lang <tag>                BCP-47 language tag (default: en)
              --cover <image>             Custom cover image (PNG/JPEG)
              --theme <name>              CLASSIC | MODERN | LITERARY | TECHNICAL | POETRY | MINIMAL
              --smart-quotes              Enable smart curly quotes, dashes & ellipses
              --pdf-images                Rasterize PDF pages as images instead of text extraction
              --no-dividers               Omit title divider pages between merged volumes
              --watch                     Rebuild automatically when source files change (build)

            Split options:
              --by <parts|size>           Split by top-level TOC parts (default) or every N chapters
              --size <N>                  Chapters per volume when using --by size (default: 5)

            Fix options:
              --optimize-images           Downscale & compress large embedded images
              --max-dim <px>              Max image dimension in pixels (default: 1600)
            """.formatted(VERSION));
    }

    static int runCli(String cmd, String[] args) {
        try {
            return switch (cmd) {
                case "build", "merge" -> cliBuildOrMerge(cmd.equals("merge"), args);
                case "split" -> cliSplit(args);
                case "check" -> cliCheck(args);
                case "fix" -> cliFix(args);
                case "info" -> cliInfo(args);
                default -> 1;
            };
        } catch (Exception error) {
            System.err.println("Error: " + error.getMessage());
            return 1;
        }
    }

    private static int cliBuildOrMerge(boolean isMerge, String[] args) throws Exception {
        var inputs = new ArrayList<Path>();
        Path output = null;
        String title = null;
        String author = "";
        String lang = "en";
        Path coverPath = null;
        Epub.BookTheme theme = Epub.BookTheme.CLASSIC;
        boolean smartQuotes = false;
        boolean pdfImages = false;
        boolean dividers = true;
        boolean watch = false;
        boolean stopFlags = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!stopFlags && a.equals("--")) { stopFlags = true; continue; }
            if (!stopFlags && a.startsWith("-")) {
                switch (a) {
                    case "-o", "--output" -> output = Path.of(nextArg(args, ++i, a));
                    case "--title" -> title = nextArg(args, ++i, a);
                    case "--author" -> author = nextArg(args, ++i, a);
                    case "--lang", "--language" -> lang = nextArg(args, ++i, a);
                    case "--cover" -> coverPath = Path.of(nextArg(args, ++i, a));
                    case "--theme" -> theme = Epub.BookTheme.fromName(nextArg(args, ++i, a));
                    case "--smart-quotes" -> smartQuotes = true;
                    case "--pdf-images" -> pdfImages = true;
                    case "--no-dividers" -> dividers = false;
                    case "--watch" -> watch = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + a);
                }
            } else {
                inputs.add(Path.of(a));
            }
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("Provide at least one input file or folder.");
        Path first = inputs.get(0).toAbsolutePath().normalize();
        if (output == null) {
            String stem = first.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            output = first.getParent().resolve(stem + (isMerge ? "-merged.epub" : ".epub"));
        }
        if (title == null || title.isBlank()) {
            title = isMerge && inputs.size() > 1 ? "Collected Volumes" : first.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        }
        Epub.Image cover = coverPath == null ? null : BookFiles.readImage(coverPath);
        var sources = new ArrayList<EpubMerger.SourceItem>();
        for (Path p : inputs) sources.add(new EpubMerger.SourceItem(p, "", dividers, pdfImages, null));
        var config = new EpubMerger.MergeConfig(
            Epub.Metadata.basic("urn:uuid:" + UUID.randomUUID(), title, author, lang),
            cover,
            new Epub.Options(theme, "* * *", smartQuotes, false, false, false, true, ""),
            true);
        if (watch) {
            EpubMerger.watchAndBuild(sources, output, config, System.out::println);
        } else {
            EpubMerger.merge(sources, output, config);
            System.out.println("Built " + output.toAbsolutePath().normalize());
        }
        return 0;
    }

    private static int cliSplit(String[] args) throws Exception {
        Path input = null;
        Path outDir = null;
        String by = "parts";
        int size = 5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--output" -> outDir = Path.of(nextArg(args, ++i, args[i]));
                case "--by" -> by = nextArg(args, ++i, args[i]);
                case "--size" -> size = Integer.parseInt(nextArg(args, ++i, args[i]));
                default -> { if (input == null) input = Path.of(args[i]); }
            }
        }
        if (input == null) throw new IllegalArgumentException("Provide an input EPUB to split.");
        if (outDir == null) outDir = input.toAbsolutePath().getParent().resolve(
            input.getFileName().toString().replaceFirst("(?i)\\.epub$", "") + "-parts");
        var arc = EpubArchive.load(input);
        var parts = "size".equalsIgnoreCase(by)
            ? EpubMerger.autoSplitByChunkSize(arc, size)
            : EpubMerger.autoSplitByParts(arc);
        var created = EpubMerger.split(input, outDir, parts);
        for (Path p : created) System.out.println("Created " + p);
        return 0;
    }

    private static int cliCheck(String[] args) {
        if (args.length < 1) throw new IllegalArgumentException("Provide an EPUB file to check.");
        var report = EpubDoctor.checkEpub(Path.of(args[0]));
        System.out.println("EPUB Doctor — " + report.targetName());
        for (var e : report.stats().entrySet()) System.out.printf("  %-18s %s%n", e.getKey() + ":", e.getValue());
        System.out.println();
        for (var f : report.findings()) {
            System.out.printf("  [%-7s] %-14s %s%s%n", f.severity(), f.category(), f.message(),
                f.detail().isBlank() ? "" : " — " + f.detail());
        }
        System.out.printf("%nSummary: %d passed, %d warnings, %d errors%n",
            report.passes(), report.warnings(), report.errors());
        return report.healthy() ? 0 : 1;
    }

    private static int cliFix(String[] args) throws Exception {
        Path input = null;
        Path output = null;
        boolean optImages = false;
        int maxDim = 1600;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--output" -> output = Path.of(nextArg(args, ++i, args[i]));
                case "--optimize-images" -> optImages = true;
                case "--max-dim" -> maxDim = Integer.parseInt(nextArg(args, ++i, args[i]));
                default -> { if (input == null) input = Path.of(args[i]); }
            }
        }
        if (input == null) throw new IllegalArgumentException("Provide an input EPUB to repair.");
        if (output == null) {
            String base = input.getFileName().toString().replaceFirst("(?i)\\.epub$", "");
            output = input.toAbsolutePath().getParent().resolve(base + "-fixed.epub");
        }
        var summary = EpubDoctor.repair(input, output,
            new EpubDoctor.RepairOptions(true, true, optImages, maxDim, 0.84f, true));
        System.out.println("Repaired " + output.toAbsolutePath().normalize()
            + " (orphans removed: " + summary.orphansRemoved()
            + ", images optimized: " + summary.imagesOptimized()
            + ", saved: " + EpubDoctor.formatBytes(summary.bytesSaved()) + ")");
        return 0;
    }

    private static int cliInfo(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Provide an EPUB file.");
        Path path = Path.of(args[0]);
        var arc = EpubArchive.load(path);
        System.out.println("Title:      " + arc.metadata.title());
        if (!arc.metadata.subtitle().isBlank()) System.out.println("Subtitle:   " + arc.metadata.subtitle());
        System.out.println("Author:     " + arc.metadata.author());
        System.out.println("Language:   " + arc.metadata.language());
        System.out.println("Version:    EPUB " + arc.epubVersion);
        if (!arc.metadata.series().isBlank()) {
            System.out.println("Series:     " + arc.metadata.series()
                + (arc.metadata.seriesIndex().isBlank() ? "" : " #" + arc.metadata.seriesIndex()));
        }
        System.out.println("Identifier: " + arc.metadata.id());
        System.out.println("Files:      " + arc.files.size() + " (" + EpubDoctor.formatBytes(Files.size(path)) + ")");
        System.out.println("Spine:      " + arc.spine.size() + " items");
        System.out.println("Table of Contents:");
        printToc(arc.toc, "  ");
        return 0;
    }

    private static void printToc(List<EpubArchive.TocNode> nodes, String indent) {
        for (var n : nodes) {
            System.out.println(indent + "- " + n.label + (n.path.isBlank() ? "" : " (" + n.path + ")"));
            if (!n.children.isEmpty()) printToc(n.children, indent + "  ");
        }
    }

    private static String nextArg(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        return args[index];
    }

    // --- Desktop GUI ---

    static void configureTheme() {
        UiTheme.applyGlobal();
    }

    static void toggleDarkMode(boolean dark) {
        UiTheme.setDark(dark);
        for (var entry : workspaces.entrySet()) {
            entry.getValue().refreshThemeColors();
        }
        for (var frame : windows.values()) {
            SwingUtilities.updateComponentTreeUI(frame);
            frame.repaint();
        }
    }

    private static void launch(String[] args) {
        configureTheme();
        String xdg = System.getenv("XDG_DATA_HOME");
        Path data = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
            ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".local", "share");
        directory = data.resolve("quireloom");
        try {
            Files.createDirectories(directory.resolve("books"));
            Files.createDirectories(directory.resolve("locks"));
            if (args.length == 0) {
                open(directory.resolve("draft.ql"), List.of(), "Manuscript");
            } else {
                var extra = new ArrayList<Path>();
                for (String arg : args) {
                    Path path = Path.of(arg);
                    if (isBook(path)) openBook(path);
                    else extra.add(path);
                }
                if (!extra.isEmpty()) open(directory.resolve("draft.ql"), extra, "Manuscript");
            }
        } catch (Exception error) {
            failure(null, error);
            if (windows.isEmpty()) {
                try { open(directory.resolve("draft.ql"), List.of(), "Manuscript"); }
                catch (Exception ignored) { System.exit(1); }
            }
        }
    }

    private static boolean isBook(Path path) {
        return path.getFileName().toString().matches("(?is).+\\.(epub|ql)");
    }

    static Path epubDraft(Path library, Path epub) throws IOException {
        String canonical = epub.toRealPath().toString();
        return library.resolve("books").resolve(UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)) + ".ql");
    }

    private static void openBook(Path input) throws IOException {
        Path source = input.toRealPath();
        String name = source.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".ql")) {
            open(source, List.of(), name);
        } else {
            Path draft = epubDraft(directory, source);
            Path stampFile = draft.resolveSibling(draft.getFileName() + ".stamp");
            String currentStamp = Files.getLastModifiedTime(source).toMillis() + ":" + Files.size(source);
            boolean reimport = !Files.exists(draft);
            if (!reimport && Files.exists(stampFile)) {
                String savedStamp = Files.readString(stampFile, StandardCharsets.UTF_8).strip();
                if (!savedStamp.equals(currentStamp) && !GraphicsEnvironment.isHeadless()) {
                    Object[] opts = {"Resume Saved Edits", "Reload Updated EPUB", "Open Original in Package Studio"};
                    int pick = JOptionPane.showOptionDialog(null,
                        name + " has changed on disk since you last opened it.\nHow would you like to open it?",
                        "EPUB Changed on Disk", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, opts, opts[0]);
                    if (pick == 1) {
                        Files.deleteIfExists(draft);
                        reimport = true;
                    } else if (pick == 2) {
                        PackageStudioFrame.open(null, source);
                        remember(source);
                        return;
                    }
                }
            }
            if (reimport) Files.writeString(stampFile, currentStamp, StandardCharsets.UTF_8);
            open(draft, reimport ? List.of(source) : List.of(), name);
        }
        remember(source);
    }

    private static Preferences preferences() {
        return Preferences.userRoot().node("io/quireloom/desktop");
    }

    private static List<String> recent() {
        var paths = new ArrayList<String>();
        for (int i = 0; i < RECENT_LIMIT; i++) {
            String value = preferences().get("book" + i, "");
            if (!value.isEmpty()) paths.add(value);
        }
        return paths;
    }

    private static void remember(Path path) {
        var paths = recent();
        paths.remove(path.toString());
        paths.add(0, path.toString());
        for (int i = 0; i < Math.min(paths.size(), RECENT_LIMIT); i++) preferences().put("book" + i, paths.get(i));
    }

    private static void open(Path path, List<Path> inputs, String label) throws IOException {
        path = path.toAbsolutePath().normalize();
        JFrame existing = windows.get(path);
        if (existing != null) {
            existing.setExtendedState(existing.getExtendedState() & ~Frame.ICONIFIED);
            existing.toFront();
            existing.requestFocus();
            return;
        }
        // Keep lock files cleanly in Quireloom's own locks directory — never clutter user folders!
        Path lockDir = directory.resolve("locks");
        Files.createDirectories(lockDir);
        String lockName = UUID.nameUUIDFromBytes(path.toString().getBytes(StandardCharsets.UTF_8)) + ".lock";
        Path lock = lockDir.resolve(lockName);
        var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() == null) throw new IOException(label + " is already open in another Quireloom window.");
            show(path, channel, lock, inputs, label);
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    static void chooseBook(Component parent, boolean create) {
        var picker = new JFileChooser(lastFolder);
        picker.setDialogTitle(create ? "Create a New Quireloom Draft" : "Open Book");
        if (create) {
            picker.setFileFilter(new FileNameExtensionFilter("Quireloom drafts (.ql)", "ql"));
        } else {
            picker.setFileFilter(new FileNameExtensionFilter("EPUB books and Quireloom drafts", "epub", "ql"));
            picker.setAccessory(new JLabel("<html>EPUB opens as a rich editable manuscript<br>preserving formatting, cover &amp; images.<br>For raw XHTML/CSS editing, use<br>Publishing → Package Studio.</html>"));
        }
        int choice = create ? picker.showSaveDialog(parent) : picker.showOpenDialog(parent);
        if (choice != JFileChooser.APPROVE_OPTION) return;
        lastFolder = picker.getCurrentDirectory();
        Path path = picker.getSelectedFile().toPath();
        try {
            if (create) {
                if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".ql")) path = Path.of(path + ".ql");
                Files.createFile(path);
                try { Draft.empty().save(path); }
                catch (IOException error) { Files.deleteIfExists(path); throw error; }
            }
            if (!isBook(path)) throw new IOException("Choose an EPUB or .ql draft.");
            openBook(path);
        } catch (FileAlreadyExistsException error) {
            failure(parent, new IOException("A draft with that name already exists. Choose Open Book to resume it."));
        } catch (IOException error) {
            failure(parent, error);
        }
    }

    private static void failure(Component parent, Exception error) {
        if (GraphicsEnvironment.isHeadless()) System.err.println(error.getMessage());
        else JOptionPane.showMessageDialog(parent, error.getMessage(), "Could not open book", JOptionPane.ERROR_MESSAGE);
    }

    private static void show(Path path, FileChannel lockFile, Path lockPath, List<Path> inputs, String label) throws IOException {
        Draft draft;
        boolean readable = true;
        try {
            draft = Draft.read(path);
        } catch (IOException error) {
            if (!path.equals(directory.resolve("draft.ql"))) throw error;
            draft = Draft.empty();
            readable = false;
        }
        var workspace = new Workspace(path, draft, readable);
        var window = new JFrame(label + " — Quireloom");
        windows.put(path, window);
        workspaces.put(path, workspace);
        try (var icon = DesktopApp.class.getResourceAsStream("icon.png")) {
            if (icon != null) window.setIconImage(ImageIO.read(icon));
        } catch (IOException ignored) {}

        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Runnable closeWindow = () -> workspace.close(() -> {
            windows.remove(path);
            workspaces.remove(path);
            window.dispose();
            try { lockFile.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(lockPath); } catch (IOException ignored) {}
        });
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeWindow.run(); }
        });
        workspace.addPropertyChangeListener("bookTitle", event -> {
            String t = event.getNewValue().toString().strip();
            window.setTitle((t.isEmpty() ? label : t + " (" + label + ")") + " — Quireloom");
        });
        if (!draft.title().isBlank()) window.setTitle(draft.title() + " (" + label + ") — Quireloom");
        window.setContentPane(workspace);

        var menus = new PublishingMenu(window, workspace);
        var file = new JMenu("File");
        var newItem = new JMenuItem("New Book…");
        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        newItem.addActionListener(e -> chooseBook(window, true));
        file.add(newItem);

        var openItem = new JMenuItem("Open Book…");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> chooseBook(window, false));
        file.add(openItem);

        var recentBooks = new JMenu("Open Recent");
        file.add(recentBooks);
        file.addMenuListener(new MenuListener() {
            public void menuSelected(MenuEvent event) {
                recentBooks.removeAll();
                for (String value : recent()) {
                    Path source = Path.of(value);
                    var item = new JMenuItem(source.getFileName() + "  —  " + source.getParent());
                    item.addActionListener(e -> {
                        try { openBook(source); }
                        catch (IOException error) { failure(window, error); }
                    });
                    recentBooks.add(item);
                }
                recentBooks.setEnabled(recentBooks.getItemCount() > 0);
            }
            public void menuDeselected(MenuEvent e) {}
            public void menuCanceled(MenuEvent e) {}
        });

        file.addSeparator();
        var saveItem = new JMenuItem("Save Draft Now");
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> workspace.saveNow());
        file.add(saveItem);

        var metaItem = new JMenuItem("Book Metadata & Series…");
        metaItem.addActionListener(e -> workspace.openMetadataDialog());
        file.add(metaItem);

        file.addSeparator();
        var exportEpub = new JMenuItem("Export EPUB 3.3…");
        exportEpub.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK));
        exportEpub.addActionListener(e -> workspace.chooseExport());
        file.add(exportEpub);

        var exportHtml = new JMenuItem("Export HTML Web Reader…");
        exportHtml.addActionListener(e -> workspace.chooseExportHtml());
        file.add(exportHtml);

        var exportMd = new JMenuItem("Export Markdown Folder…");
        exportMd.addActionListener(e -> workspace.chooseExportMarkdownFolder());
        file.add(exportMd);

        file.addSeparator();
        var closeItem = new JMenuItem("Close Window");
        closeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        closeItem.addActionListener(e -> closeWindow.run());
        file.add(closeItem);
        menus.add(file, 0);

        var books = new JMenu("Window");
        books.addMenuListener(new MenuListener() {
            public void menuSelected(MenuEvent event) {
                books.removeAll();
                for (var entry : windows.values()) {
                    var item = new JCheckBoxMenuItem(entry.getTitle(), entry == window);
                    item.addActionListener(e -> {
                        entry.setExtendedState(entry.getExtendedState() & ~Frame.ICONIFIED);
                        entry.toFront();
                    });
                    books.add(item);
                }
            }
            public void menuDeselected(MenuEvent e) {}
            public void menuCanceled(MenuEvent e) {}
        });
        menus.add(books);

        window.setJMenuBar(menus);
        window.setMinimumSize(new Dimension(780, 500));
        window.setSize(1120, 740);
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        if (!inputs.isEmpty()) workspace.importPaths(inputs);
    }
}
