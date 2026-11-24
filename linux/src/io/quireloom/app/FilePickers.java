package io.quireloom.app;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

/**
 * Native-first file and folder pickers for desktop (GTK/XDG portal via zenity on Linux,
 * with a themed, home/Documents-defaulting JFileChooser fallback that remembers the last folder).
 */
final class FilePickers {
    private static final String PREF_LAST_DIR = "lastDirectory";
    private static volatile Boolean zenityAvailable;

    private FilePickers() {}

    private static Preferences prefs() {
        return Preferences.userRoot().node("io/quireloom/desktop");
    }

    static Path resolveInitialDirectory(Path preferred) {
        if (isUsableDirectory(preferred)) return preferred.toAbsolutePath().normalize();
        String saved = prefs().get(PREF_LAST_DIR, "");
        if (!saved.isBlank()) {
            try {
                Path p = Path.of(saved);
                if (isUsableDirectory(p)) return p.toAbsolutePath().normalize();
            } catch (Exception ignored) {}
        }
        Path home = userHome();
        Path docs = home.resolve("Documents");
        if (isUsableDirectory(docs)) return docs;
        if (isUsableDirectory(home)) return home;
        return Path.of(".").toAbsolutePath().normalize();
    }

    static void rememberDirectory(Path path) {
        if (path == null) return;
        Path dir = Files.isDirectory(path) ? path : path.getParent();
        if (isUsableDirectory(dir)) {
            prefs().put(PREF_LAST_DIR, dir.toAbsolutePath().normalize().toString());
        }
    }

    private static Path userHome() {
        String home = System.getProperty("user.home", "");
        return home.isBlank() ? Path.of(".") : Path.of(home);
    }

    private static boolean isUsableDirectory(Path dir) {
        if (dir == null) return false;
        Path norm = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(norm)) return false;
        // Avoid dropping the user into filesystem root "/" when picking books or exports.
        return norm.getParent() != null;
    }

    static Path pickOpenFile(Component parent, String title, Path preferredDir,
                             String filterLabel, String... extensions) {
        var result = pickOpenFilesInternal(parent, title, preferredDir, false, null, filterLabel, extensions);
        return result.isEmpty() ? null : result.get(0);
    }

    static Path pickOpenFileWithNote(Component parent, String title, Path preferredDir, String accessoryHtml,
                                     String filterLabel, String... extensions) {
        var result = pickOpenFilesInternal(parent, title, preferredDir, false, accessoryHtml, filterLabel, extensions);
        return result.isEmpty() ? null : result.get(0);
    }

    static List<Path> pickOpenFiles(Component parent, String title, Path preferredDir, String accessoryHtml,
                                    String filterLabel, String... extensions) {
        return pickOpenFilesInternal(parent, title, preferredDir, true, accessoryHtml, filterLabel, extensions);
    }

    static Path pickSaveFile(Component parent, String title, Path preferredDir, String suggestedFilename,
                             String defaultExt, String filterLabel, String... extensions) {
        Path startDir = resolveInitialDirectory(preferredDir);
        String name = (suggestedFilename == null || suggestedFilename.isBlank()) ? "book" : suggestedFilename;
        if (defaultExt != null && !defaultExt.isBlank()
                && !name.toLowerCase(Locale.ROOT).endsWith("." + defaultExt.toLowerCase(Locale.ROOT))) {
            name = name + "." + defaultExt;
        }
        Path suggested = startDir.resolve(name);

        if (shouldUseZenity()) {
            var cmd = new ArrayList<String>();
            cmd.add("zenity");
            cmd.add("--file-selection");
            cmd.add("--save");
            cmd.add("--title=" + title);
            cmd.add("--filename=" + suggested);
            addZenityFilters(cmd, filterLabel, extensions);
            var res = runZenity(parent, cmd);
            if (res.handled) {
                if (res.cancelled || res.output.isBlank()) return null;
                Path chosen = ensureExtension(Path.of(res.output.strip()), defaultExt);
                rememberDirectory(chosen);
                return chosen;
            }
        }

        var chooser = newSwingChooser(title, startDir, filterLabel, extensions);
        chooser.setSelectedFile(suggested.toFile());
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        Path chosen = ensureExtension(chooser.getSelectedFile().toPath(), defaultExt);
        if (Files.exists(chosen, LinkOption.NOFOLLOW_LINKS) && !GraphicsEnvironment.isHeadless()) {
            int ans = JOptionPane.showConfirmDialog(parent,
                "Replace the existing file \"" + chosen.getFileName() + "\"?",
                title, JOptionPane.OK_CANCEL_OPTION);
            if (ans != JOptionPane.OK_OPTION) return null;
        }
        rememberDirectory(chosen);
        return chosen;
    }

    static Path pickDirectory(Component parent, String title, Path preferredDir) {
        Path startDir = resolveInitialDirectory(preferredDir);
        if (shouldUseZenity()) {
            var cmd = List.of(
                "zenity", "--file-selection", "--directory",
                "--title=" + title,
                "--filename=" + dirHint(startDir)
            );
            var res = runZenity(parent, cmd);
            if (res.handled) {
                if (res.cancelled || res.output.isBlank()) return null;
                Path chosen = Path.of(res.output.strip());
                rememberDirectory(chosen);
                return chosen;
            }
        }

        var chooser = new JFileChooser(startDir.toFile());
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setFileHidingEnabled(true);
        chooser.setPreferredSize(new Dimension(820, 540));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        Path chosen = chooser.getSelectedFile().toPath();
        rememberDirectory(chosen);
        return chosen;
    }

    private static List<Path> pickOpenFilesInternal(Component parent, String title, Path preferredDir, boolean multiple,
                                                    String accessoryHtml, String filterLabel, String... extensions) {
        Path startDir = resolveInitialDirectory(preferredDir);
        if (shouldUseZenity()) {
            var cmd = new ArrayList<String>();
            cmd.add("zenity");
            cmd.add("--file-selection");
            if (multiple) {
                cmd.add("--multiple");
                cmd.add("--separator=\n");
            }
            cmd.add("--title=" + title);
            cmd.add("--filename=" + dirHint(startDir));
            addZenityFilters(cmd, filterLabel, extensions);
            var res = runZenity(parent, cmd);
            if (res.handled) {
                if (res.cancelled || res.output.isBlank()) return List.of();
                var paths = new ArrayList<Path>();
                for (String line : res.output.split("\n")) {
                    if (!line.isBlank()) paths.add(Path.of(line.strip()));
                }
                if (!paths.isEmpty()) rememberDirectory(paths.get(0));
                return paths;
            }
        }

        var chooser = newSwingChooser(title, startDir, filterLabel, extensions);
        chooser.setMultiSelectionEnabled(multiple);
        if (accessoryHtml != null && !accessoryHtml.isBlank()) {
            var note = new JLabel(accessoryHtml);
            note.setBorder(new javax.swing.border.EmptyBorder(12, 10, 12, 8));
            chooser.setAccessory(note);
        }
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return List.of();
        List<Path> picked = multiple
            ? Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList()
            : List.of(chooser.getSelectedFile().toPath());
        if (!picked.isEmpty()) rememberDirectory(picked.get(0));
        return picked;
    }

    private static JFileChooser newSwingChooser(String title, Path startDir, String filterLabel, String... extensions) {
        var chooser = new JFileChooser(startDir.toFile());
        chooser.setDialogTitle(title);
        chooser.setFileHidingEnabled(true);
        chooser.setPreferredSize(new Dimension(820, 540));
        if (extensions != null && extensions.length > 0 && filterLabel != null && !filterLabel.isBlank()) {
            chooser.setFileFilter(new FileNameExtensionFilter(filterLabel, extensions));
        }
        return chooser;
    }

    private static void addZenityFilters(List<String> cmd, String filterLabel, String... extensions) {
        if (extensions != null && extensions.length > 0 && filterLabel != null && !filterLabel.isBlank()) {
            var sb = new StringBuilder(filterLabel).append(" |");
            for (String ext : extensions) {
                String clean = ext.startsWith(".") ? ext.substring(1) : ext;
                sb.append(" *.").append(clean.toLowerCase(Locale.ROOT));
                String upper = clean.toUpperCase(Locale.ROOT);
                if (!upper.equals(clean.toLowerCase(Locale.ROOT))) {
                    sb.append(" *.").append(upper);
                }
            }
            cmd.add("--file-filter=" + sb);
        }
        cmd.add("--file-filter=All files | *");
    }

    private static String dirHint(Path dir) {
        String s = dir.toAbsolutePath().normalize().toString();
        return s.endsWith(File.separator) ? s : s + File.separator;
    }

    private static Path ensureExtension(Path chosen, String defaultExt) {
        if (defaultExt == null || defaultExt.isBlank()) return chosen;
        String extWithDot = "." + defaultExt.toLowerCase(Locale.ROOT);
        String filename = chosen.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(extWithDot)) {
            return chosen.resolveSibling(filename + extWithDot);
        }
        return chosen;
    }

    private static boolean shouldUseZenity() {
        if (GraphicsEnvironment.isHeadless()) return false;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return false;
        String display = System.getenv("DISPLAY");
        String wayland = System.getenv("WAYLAND_DISPLAY");
        if ((display == null || display.isBlank()) && (wayland == null || wayland.isBlank())) return false;
        Boolean cached = zenityAvailable;
        if (cached != null) return cached;
        boolean found = false;
        try {
            Process p = new ProcessBuilder("zenity", "--version").redirectErrorStream(true).start();
            found = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ignored) {
            found = false;
        }
        zenityAvailable = found;
        return found;
    }

    private record ZenityResult(boolean handled, boolean cancelled, String output) {}

    private static ZenityResult runZenity(Component parent, List<String> cmd) {
        Window owner = parent == null ? null : (parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent));
        var resultRef = new AtomicReference<ZenityResult>();
        Runnable runDialog = () -> {
            try {
                var proc = new ProcessBuilder(cmd)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                byte[] outBytes = proc.getInputStream().readAllBytes();
                int exit = proc.waitFor();
                if (exit == 0) {
                    resultRef.set(new ZenityResult(true, false, new String(outBytes, StandardCharsets.UTF_8)));
                } else if (exit == 1 || exit == 5) {
                    resultRef.set(new ZenityResult(true, true, ""));
                } else {
                    resultRef.set(new ZenityResult(false, false, ""));
                }
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                resultRef.set(new ZenityResult(false, false, ""));
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            if (owner != null) owner.setEnabled(false);
            var thread = new Thread(() -> {
                try { runDialog.run(); }
                finally { loop.exit(); }
            }, "quireloom-file-picker");
            thread.setDaemon(true);
            thread.start();
            try {
                loop.enter();
            } finally {
                if (owner != null) {
                    owner.setEnabled(true);
                    owner.toFront();
                }
            }
        } else {
            runDialog.run();
        }
        var res = resultRef.get();
        return res == null ? new ZenityResult(false, false, "") : res;
    }
}
