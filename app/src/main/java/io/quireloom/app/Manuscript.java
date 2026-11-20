package io.quireloom.app;

import java.math.BigInteger;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Manuscript {
    private static final Pattern CHAPTER = Pattern.compile(
        "(?iu)^(?:#{1,6} +(.+?)(?:\\s+#+)?|((?:chapter|part|volume|book|act|section|অধ্যায়|পর্ব|খণ্ড) +[\\p{Nd}IVXLCDM]+(?:\\s*[:.\\-–—]\\s*.*)?|prologue|epilogue|foreword|preface|afterword|introduction|interlude|appendix))$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$");
    private static final Pattern NAME_RUN = Pattern.compile("[0-9]+|[^0-9]+");
    private static final Pattern ITEM = Pattern.compile("^( *)([-+*]|[0-9]+[.)]) +(.+)$");
    private static final Pattern FOOTNOTE_DEF = Pattern.compile("^\\[\\^([A-Za-z0-9_-]+)\\]:\\s*(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*");

    static Epub.Chapter fromFile(String name, String source) {
        String body = normalize(source).strip();
        String title = name.replaceFirst("(?i)\\.(md|markdown|txt)$", "");
        boolean markdown = !name.toLowerCase(Locale.ROOT).endsWith(".txt");
        String[] first = body.split("\n", 2);
        var heading = HEADING.matcher(first[0].strip());
        if (markdown && heading.matches() && heading.group(1).length() == 1) {
            title = heading.group(2).strip();
            body = first.length == 2 ? first[1].strip() : "";
        }
        return new Epub.Chapter(title, body, markdown);
    }

    static List<Epub.Chapter> fromPaste(String source, boolean split, boolean markdown) {
        String body = normalize(source).strip();
        if (body.isEmpty()) return List.of();
        if (!split) return List.of(new Epub.Chapter("Untitled chapter", body, markdown));
        var chapters = new ArrayList<Epub.Chapter>();
        String title = null;
        var content = new StringBuilder();
        int fence = 0;
        int fenceWidth = 0;
        for (String line : body.split("\n", -1)) {
            String trimmed = line.strip();
            var heading = CHAPTER.matcher(trimmed);
            if (fence == 0 && heading.matches()) {
                if (!content.toString().isBlank() || title != null) {
                    chapters.add(new Epub.Chapter(title == null ? "Introduction" : title,
                        content.toString().strip(), markdown));
                }
                title = (heading.group(1) != null ? heading.group(1) : heading.group(2)).strip();
                content.setLength(0);
            } else {
                content.append(line).append('\n');
            }
            if (markdown && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                int marker = trimmed.charAt(0);
                if (fence == 0) {
                    fence = marker;
                    fenceWidth = countLeading(trimmed, marker);
                } else if (fence == marker && trimmed.length() >= fenceWidth
                        && trimmed.chars().allMatch(c -> c == marker)) {
                    fence = 0;
                }
            }
        }
        chapters.add(new Epub.Chapter(title == null ? "Untitled chapter" : title,
            content.toString().strip(), markdown));
        return chapters;
    }

    static int compareNames(String left, String right) {
        var a = NAME_RUN.matcher(left);
        var b = NAME_RUN.matcher(right);
        while (a.find()) {
            if (!b.find()) return 1;
            String x = a.group();
            String y = b.group();
            int order = x.charAt(0) >= '0' && x.charAt(0) <= '9' &&
                y.charAt(0) >= '0' && y.charAt(0) <= '9'
                ? new BigInteger(x).compareTo(new BigInteger(y))
                : x.compareToIgnoreCase(y);
            if (order != 0) return order;
        }
        return b.find() ? -1 : left.compareToIgnoreCase(right);
    }

    static int wordCount(String text) {
        if (text == null || text.isBlank()) return 0;
        String cleaned = Epub.ILLUSTRATION.matcher(text).replaceAll(" ");
        int count = 0;
        var matcher = WORD.matcher(cleaned);
        while (matcher.find()) count++;
        return count;
    }

    static String polish(String source, boolean smartQuotes) {
        String normalized = normalize(source);
        String[] lines = normalized.split("\n", -1);
        var out = new StringBuilder(normalized.length());
        int fence = 0;
        int fenceWidth = 0;
        int blankRun = 0;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                int marker = trimmed.charAt(0);
                if (fence == 0) {
                    fence = marker;
                    fenceWidth = countLeading(trimmed, marker);
                } else if (fence == marker && trimmed.length() >= fenceWidth
                        && trimmed.chars().allMatch(c -> c == marker)) {
                    fence = 0;
                }
                blankRun = 0;
                if (i > 0) out.append('\n');
                out.append(raw.stripTrailing());
                continue;
            }
            if (fence != 0) {
                if (i > 0) out.append('\n');
                out.append(raw);
                continue;
            }
            if (trimmed.isEmpty()) {
                blankRun++;
                if (blankRun <= 1 && out.length() > 0) out.append('\n');
                continue;
            }
            blankRun = 0;
            if (out.length() > 0) out.append('\n');
            if (Epub.ILLUSTRATION.matcher(raw).matches()) {
                out.append(raw.stripTrailing());
            } else {
                boolean hardBreak = raw.endsWith("  ") && i + 1 < lines.length && !lines[i + 1].isBlank();
                String line = raw.stripTrailing();
                if (smartQuotes) line = smartifyMarkdownLine(line);
                out.append(line);
                if (hardBreak) out.append("  ");
            }
        }
        return out.toString().strip();
    }

    static String html(String source, boolean markdown) {
        return html(source, markdown, false, "<hr/>", false);
    }

    static String html(String source, boolean markdown, boolean smartTypography, String sceneBreakHtml, boolean dropCaps) {
        String normalized = normalize(source);
        if (!markdown) {
            return renderBlocks(normalized.split("\n", -1), false, smartTypography, sceneBreakHtml,
                Map.of(), new FootnoteState(), dropCaps);
        }
        String[] allLines = normalized.split("\n", -1);
        var definitions = new LinkedHashMap<String, String>();
        var kept = new ArrayList<String>(allLines.length);
        int fence = 0;
        int fenceWidth = 0;
        for (int i = 0; i < allLines.length; i++) {
            String line = allLines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                int marker = trimmed.charAt(0);
                if (fence == 0) {
                    fence = marker;
                    fenceWidth = countLeading(trimmed, marker);
                } else if (fence == marker && trimmed.length() >= fenceWidth
                        && trimmed.chars().allMatch(c -> c == marker)) {
                    fence = 0;
                }
                kept.add(line);
                continue;
            }
            if (fence == 0) {
                var def = FOOTNOTE_DEF.matcher(line);
                if (def.matches()) {
                    String id = def.group(1);
                    var body = new StringBuilder(def.group(2).strip());
                    while (i + 1 < allLines.length && (allLines[i + 1].startsWith("    ") || allLines[i + 1].startsWith("\t"))) {
                        if (body.length() > 0) body.append(' ');
                        body.append(allLines[++i].strip());
                    }
                    definitions.put(id, body.toString());
                    continue;
                }
            }
            kept.add(line);
        }
        var footnotes = new FootnoteState();
        String rendered = renderBlocks(kept.toArray(String[]::new), true, smartTypography, sceneBreakHtml,
            definitions, footnotes, dropCaps);
        if (!footnotes.order.isEmpty()) {
            var fnOut = new StringBuilder(rendered);
            fnOut.append("<section epub:type=\"footnotes\" class=\"footnotes\"><hr class=\"footnotes-sep\"/><ol>");
            for (int idx = 0; idx < footnotes.order.size(); idx++) {
                String id = footnotes.order.get(idx);
                int num = idx + 1;
                String defText = definitions.getOrDefault(id, "");
                fnOut.append("<li id=\"fn-").append(num).append("\" epub:type=\"footnote\"><p>")
                    .append(inline(defText, smartTypography, Map.of(), footnotes))
                    .append(" <a href=\"#fnref-").append(num)
                    .append("\" class=\"footnote-backref\" aria-label=\"Back to text\">&#8617;</a></p></li>");
            }
            fnOut.append("</ol></section>");
            return fnOut.toString();
        }
        return rendered;
    }

    private static final class FootnoteState {
        final List<String> order = new ArrayList<>();
        final Map<String, Integer> numberById = new HashMap<>();
        final Map<String, Integer> refCount = new HashMap<>();
        boolean dropCapUsed;
    }

    private record ListFrame(int indent, String tag) {}

    private static String renderBlocks(String[] lines, boolean markdown, boolean smartTypography,
                                       String sceneBreakHtml, Map<String, String> definitions,
                                       FootnoteState footnotes, boolean dropCaps) {
        var out = new StringBuilder();
        var paragraph = new ArrayList<String>();
        var listStack = new ArrayDeque<ListFrame>();

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.strip();
            boolean rule = isRule(line);
            var item = markdown && !rule ? ITEM.matcher(raw) : null;
            boolean isListItem = item != null && item.matches();

            if (isListItem) {
                flush(out, paragraph, markdown, smartTypography, definitions, footnotes, dropCaps);
                int indent = item.group(1).length();
                String tag = Character.isDigit(item.group(2).charAt(0)) ? "ol" : "ul";
                if (listStack.isEmpty()) {
                    out.append('<').append(tag).append("><li>");
                    listStack.push(new ListFrame(indent, tag));
                } else if (indent > listStack.peek().indent()) {
                    out.append('<').append(tag).append("><li>");
                    listStack.push(new ListFrame(indent, tag));
                } else {
                    while (listStack.size() > 1 && indent < listStack.peek().indent()) {
                        var closed = listStack.pop();
                        out.append("</li></").append(closed.tag()).append('>');
                    }
                    if (!listStack.peek().tag().equals(tag)) {
                        var closed = listStack.pop();
                        out.append("</li></").append(closed.tag()).append('>');
                        out.append('<').append(tag).append("><li>");
                        listStack.push(new ListFrame(indent, tag));
                    } else {
                        out.append("</li><li>");
                    }
                }
                out.append(renderTaskItem(item.group(3), smartTypography, definitions, footnotes));
                continue;
            }

            if (!listStack.isEmpty()) {
                closeLists(out, listStack);
            }

            if (line.isBlank()) {
                flush(out, paragraph, markdown, smartTypography, definitions, footnotes, dropCaps);
            } else if (rule) {
                flush(out, paragraph, markdown, smartTypography, definitions, footnotes, dropCaps);
                out.append(sceneBreakHtml == null || sceneBreakHtml.isEmpty() ? "<hr/>" : sceneBreakHtml);
            } else if (markdown && (line.startsWith("```") || line.startsWith("~~~"))) {
                flush(out, paragraph, true, smartTypography, definitions, footnotes, dropCaps);
                int marker = line.charAt(0);
                int width = countLeading(line, marker);
                String lang = line.substring(width).strip().replaceAll("[^A-Za-z0-9_+.-]", "");
                out.append("<pre><code");
                if (!lang.isEmpty()) out.append(" class=\"language-").append(Epub.xml(lang)).append('"');
                out.append('>');
                while (++i < lines.length) {
                    String candidate = lines[i].strip();
                    if (candidate.length() >= width && candidate.chars().allMatch(c -> c == marker)) break;
                    out.append(Epub.xml(lines[i])).append('\n');
                }
                out.append("</code></pre>");
            } else if (markdown && isTableStart(lines, i)) {
                flush(out, paragraph, true, smartTypography, definitions, footnotes, dropCaps);
                i = renderTable(lines, i, out, smartTypography, definitions, footnotes);
            } else if (markdown && line.startsWith(">")) {
                flush(out, paragraph, true, smartTypography, definitions, footnotes, dropCaps);
                var quoteLines = new ArrayList<String>();
                while (i < lines.length && lines[i].strip().startsWith(">")) {
                    String q = lines[i].strip().substring(1);
                    if (q.startsWith(" ")) q = q.substring(1);
                    quoteLines.add(q);
                    i++;
                }
                i--;
                out.append("<blockquote>")
                    .append(renderBlocks(quoteLines.toArray(String[]::new), true, smartTypography,
                        sceneBreakHtml, definitions, footnotes, false))
                    .append("</blockquote>");
            } else if (markdown && HEADING.matcher(line).matches()) {
                flush(out, paragraph, true, smartTypography, definitions, footnotes, dropCaps);
                var heading = HEADING.matcher(line);
                heading.matches();
                int level = Math.min(6, heading.group(1).length() + 1);
                out.append("<h").append(level).append('>')
                    .append(inline(heading.group(2), smartTypography, definitions, footnotes))
                    .append("</h").append(level).append('>');
            } else {
                paragraph.add(raw);
            }
        }
        closeLists(out, listStack);
        flush(out, paragraph, markdown, smartTypography, definitions, footnotes, dropCaps);
        return out.toString();
    }

    private static void closeLists(StringBuilder out, Deque<ListFrame> stack) {
        while (!stack.isEmpty()) {
            var frame = stack.pop();
            out.append("</li></").append(frame.tag()).append('>');
        }
    }

    private static String renderTaskItem(String text, boolean smartTypography,
                                         Map<String, String> definitions, FootnoteState footnotes) {
        if (text.startsWith("[ ] ")) {
            return "<span class=\"task\">&#9744;</span> " + inline(text.substring(4), smartTypography, definitions, footnotes);
        }
        if (text.startsWith("[x] ") || text.startsWith("[X] ")) {
            return "<span class=\"task done\">&#9745;</span> " + inline(text.substring(4), smartTypography, definitions, footnotes);
        }
        return inline(text, smartTypography, definitions, footnotes);
    }

    private static boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length) return false;
        String header = lines[index].strip();
        String sep = lines[index + 1].strip();
        return header.contains("|") && sep.contains("|") && TABLE_SEP.matcher(sep).matches();
    }

    private static int renderTable(String[] lines, int start, StringBuilder out, boolean smartTypography,
                                   Map<String, String> definitions, FootnoteState footnotes) {
        List<String> headers = splitTableRow(lines[start]);
        List<String> sepCells = splitTableRow(lines[start + 1]);
        String[] aligns = new String[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            String spec = c < sepCells.size() ? sepCells.get(c).strip() : "";
            boolean left = spec.startsWith(":");
            boolean right = spec.endsWith(":");
            aligns[c] = (left && right) ? "center" : right ? "right" : left ? "left" : "";
        }
        out.append("<table><thead><tr>");
        for (int c = 0; c < headers.size(); c++) {
            out.append("<th").append(alignAttr(aligns[c])).append('>')
                .append(inline(headers.get(c), smartTypography, definitions, footnotes)).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        int i = start + 2;
        while (i < lines.length) {
            String row = lines[i].strip();
            if (row.isEmpty() || !row.contains("|")) break;
            List<String> cells = splitTableRow(row);
            out.append("<tr>");
            for (int c = 0; c < headers.size(); c++) {
                String cell = c < cells.size() ? cells.get(c) : "";
                out.append("<td").append(alignAttr(aligns[c])).append('>')
                    .append(inline(cell, smartTypography, definitions, footnotes)).append("</td>");
            }
            out.append("</tr>");
            i++;
        }
        out.append("</tbody></table>");
        return i - 1;
    }

    private static String alignAttr(String align) {
        return align.isEmpty() ? "" : " style=\"text-align:" + align + "\"";
    }

    private static List<String> splitTableRow(String line) {
        String s = line.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length() - 1);
        var cells = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                current.append('|');
                i++;
            } else if (c == '|') {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().strip());
        return cells;
    }

    private static boolean isRule(String line) {
        char symbol = 0;
        int marks = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') continue;
            if (c == '-' || c == '*' || c == '_') {
                if (symbol == 0) symbol = c;
                else if (symbol != c) return false;
                marks++;
            } else {
                return false;
            }
        }
        return marks >= 3;
    }

    private static void flush(StringBuilder out, List<String> lines, boolean markdown,
                              boolean smartTypography, Map<String, String> definitions,
                              FootnoteState footnotes, boolean dropCaps) {
        if (lines.isEmpty()) return;
        boolean applyDropCap = dropCaps && !footnotes.dropCapUsed;
        if (applyDropCap) footnotes.dropCapUsed = true;
        out.append(applyDropCap ? "<p class=\"first-para\">" : "<p>");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i > 0) out.append(!markdown || lines.get(i - 1).endsWith("  ") ? "<br/>" : " ");
            if (markdown) {
                out.append(inline(line.strip(), smartTypography, definitions, footnotes));
            } else {
                out.append(Epub.xml(smartTypography ? smartifyText(line) : line));
            }
        }
        out.append("</p>");
        lines.clear();
    }

    private static String inline(String line, boolean smartTypography,
                                 Map<String, String> definitions, FootnoteState footnotes) {
        return inlineRange(line, 0, line.length(), smartTypography, definitions, footnotes, 0);
    }

    private static String inlineRange(String s, int start, int end, boolean smartTypography,
                                      Map<String, String> definitions, FootnoteState footnotes, int depth) {
        var out = new StringBuilder(end - start + 16);
        var plain = new StringBuilder();
        int i = start;
        while (i < end) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < end && isEscapable(s.charAt(i + 1))) {
                plain.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int ticks = 0;
                while (i + ticks < end && s.charAt(i + ticks) == '`') ticks++;
                int close = findCodeClose(s, i + ticks, end, ticks);
                if (close >= 0) {
                    flushPlain(out, plain, smartTypography);
                    String code = s.substring(i + ticks, close);
                    if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
                        code = code.substring(1, code.length() - 1);
                    }
                    out.append("<code>").append(Epub.xml(code)).append("</code>");
                    i = close + ticks;
                    continue;
                }
                for (int t = 0; t < ticks; t++) plain.append('`');
                i += ticks;
                continue;
            }
            if (depth < 6 && (c == '*' || c == '_' || c == '~')) {
                if (c == '~' && i + 1 < end && s.charAt(i + 1) == '~') {
                    int close = findDelimClose(s, i + 2, end, "~~", false);
                    if (close > i + 2) {
                        flushPlain(out, plain, smartTypography);
                        out.append("<del>").append(inlineRange(s, i + 2, close, smartTypography, definitions, footnotes, depth + 1)).append("</del>");
                        i = close + 2;
                        continue;
                    }
                } else if (c == '*' || c == '_') {
                    boolean underscore = c == '_';
                    boolean wordBefore = underscore && i > start && isWordChar(s.charAt(i - 1));
                    if (!wordBefore) {
                        String triple = underscore ? "___" : "***";
                        String doub = underscore ? "__" : "**";
                        String sing = underscore ? "_" : "*";
                        if (i + 2 < end && s.startsWith(triple, i)) {
                            int close = findDelimClose(s, i + 3, end, triple, underscore);
                            if (close > i + 3) {
                                flushPlain(out, plain, smartTypography);
                                out.append("<strong><em>")
                                    .append(inlineRange(s, i + 3, close, smartTypography, definitions, footnotes, depth + 1))
                                    .append("</em></strong>");
                                i = close + 3;
                                continue;
                            }
                        }
                        if (i + 1 < end && s.startsWith(doub, i)) {
                            int close = findDelimClose(s, i + 2, end, doub, underscore);
                            if (close > i + 2) {
                                flushPlain(out, plain, smartTypography);
                                out.append("<strong>")
                                    .append(inlineRange(s, i + 2, close, smartTypography, definitions, footnotes, depth + 1))
                                    .append("</strong>");
                                i = close + 2;
                                continue;
                            }
                        }
                        int close = findDelimClose(s, i + 1, end, sing, underscore);
                        if (close > i + 1) {
                            flushPlain(out, plain, smartTypography);
                            out.append("<em>")
                                .append(inlineRange(s, i + 1, close, smartTypography, definitions, footnotes, depth + 1))
                                .append("</em>");
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }
            if (c == '[' && i + 2 < end && s.charAt(i + 1) == '^' && !definitions.isEmpty()) {
                int close = s.indexOf(']', i + 2);
                if (close > i + 2 && close < end) {
                    String fnId = s.substring(i + 2, close);
                    if (definitions.containsKey(fnId)) {
                        flushPlain(out, plain, smartTypography);
                        Integer num = footnotes.numberById.get(fnId);
                        if (num == null) {
                            num = footnotes.order.size() + 1;
                            footnotes.order.add(fnId);
                            footnotes.numberById.put(fnId, num);
                        }
                        int seen = footnotes.refCount.merge(fnId, 1, Integer::sum);
                        String refId = seen == 1 ? "fnref-" + num : "fnref-" + num + "-" + seen;
                        out.append("<a epub:type=\"noteref\" class=\"noteref\" id=\"").append(refId)
                            .append("\" href=\"#fn-").append(num).append("\"><sup>").append(num).append("</sup></a>");
                        i = close + 1;
                        continue;
                    }
                }
            }
            if ((c == '[' || (c == '!' && i + 1 < end && s.charAt(i + 1) == '[')) && depth < 6) {
                boolean bang = c == '!';
                int openBracket = bang ? i + 1 : i;
                int closeBracket = findClosingBracket(s, openBracket + 1, end);
                if (closeBracket > openBracket + 1 && closeBracket + 1 < end && s.charAt(closeBracket + 1) == '(') {
                    int closeParen = findClosingParen(s, closeBracket + 2, end);
                    if (closeParen > closeBracket + 2) {
                        String rawUrl = s.substring(closeBracket + 2, closeParen).strip();
                        String url = externalUrl(rawUrl);
                        flushPlain(out, plain, smartTypography);
                        String labelHtml = inlineRange(s, openBracket + 1, closeBracket, smartTypography, definitions, footnotes, depth + 1);
                        if (!bang && url != null) {
                            out.append("<a href=\"").append(Epub.xml(url)).append("\">").append(labelHtml).append("</a>");
                        } else {
                            out.append(labelHtml);
                        }
                        i = closeParen + 1;
                        continue;
                    }
                }
            }
            plain.append(c);
            i++;
        }
        flushPlain(out, plain, smartTypography);
        return out.toString();
    }

    private static void flushPlain(StringBuilder out, StringBuilder plain, boolean smartTypography) {
        if (plain.length() == 0) return;
        String text = plain.toString();
        out.append(Epub.xml(smartTypography ? smartifyText(text) : text));
        plain.setLength(0);
    }

    private static int findCodeClose(String s, int from, int end, int ticks) {
        int i = from;
        while (i < end) {
            if (s.charAt(i) == '`') {
                int run = 0;
                while (i + run < end && s.charAt(i + run) == '`') run++;
                if (run == ticks) return i;
                i += run;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int findDelimClose(String s, int from, int end, String delim, boolean underscore) {
        int len = delim.length();
        if (from >= end || Character.isWhitespace(s.charAt(from))) return -1;
        int i = from;
        while (i + len <= end) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < end) {
                i += 2;
                continue;
            }
            if (c == '`') {
                int ticks = 0;
                while (i + ticks < end && s.charAt(i + ticks) == '`') ticks++;
                int close = findCodeClose(s, i + ticks, end, ticks);
                i = close >= 0 ? close + ticks : i + ticks;
                continue;
            }
            if (s.startsWith(delim, i)) {
                boolean spaceBefore = Character.isWhitespace(s.charAt(i - 1));
                boolean wordAfter = underscore && i + len < end && isWordChar(s.charAt(i + len));
                // Avoid splitting a longer run of the same char (e.g. closing * inside **).
                boolean extraSame = len == 1 && i + 1 < end && s.charAt(i + 1) == delim.charAt(0)
                    && (i == from || s.charAt(i - 1) != delim.charAt(0));
                if (!spaceBefore && !wordAfter && !extraSame) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static int findClosingBracket(String s, int from, int end) {
        // Bounded scan: if another '[' appears before ']', skip fast to avoid O(n^2) on "[".repeat(350_000).
        int i = from;
        while (i < end) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < end) {
                i += 2;
                continue;
            }
            if (c == '[') return -1;
            if (c == ']') return i;
            i++;
        }
        return -1;
    }

    private static int findClosingParen(String s, int from, int end) {
        for (int i = from; i < end; i++) {
            char c = s.charAt(i);
            if (c == ')' ) return i;
            if (c == '(' || c == '[' || c == ']' || Character.isWhitespace(c)) return -1;
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static boolean isEscapable(char c) {
        return "\\`*_{}[]()#+-.!~|".indexOf(c) >= 0;
    }

    private static int countLeading(String text, int ch) {
        int n = 0;
        while (n < text.length() && text.charAt(n) == ch) n++;
        return n;
    }

    static String smartifyText(String text) {
        var out = new StringBuilder(text.length());
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '-' && i + 2 < n && text.charAt(i + 1) == '-' && text.charAt(i + 2) == '-') {
                out.append('\u2014'); // em dash
                i += 2;
            } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                out.append('\u2013'); // en dash
                i += 1;
            } else if (c == '.' && i + 2 < n && text.charAt(i + 1) == '.' && text.charAt(i + 2) == '.') {
                out.append('\u2026'); // ellipsis
                i += 2;
            } else if (c == '"') {
                boolean open = i == 0 || isOpeningContext(text.charAt(i - 1));
                out.append(open ? '\u201C' : '\u201D');
            } else if (c == '\'') {
                boolean open = i == 0 || isOpeningContext(text.charAt(i - 1));
                out.append(open ? '\u2018' : '\u2019');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String smartifyMarkdownLine(String line) {
        // Smartify text while skipping inline code and link URLs.
        var out = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '`') {
                int ticks = 0;
                while (i + ticks < n && line.charAt(i + ticks) == '`') ticks++;
                int close = findCodeClose(line, i + ticks, n, ticks);
                if (close >= 0) {
                    out.append(line, i, close + ticks);
                    i = close + ticks;
                    continue;
                }
            }
            if (c == ']' && i + 1 < n && line.charAt(i + 1) == '(') {
                int close = findClosingParen(line, i + 2, n);
                if (close > i + 2) {
                    out.append(line, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }
            int next = i + 1;
            while (next < n && line.charAt(next) != '`' && line.charAt(next) != ']') next++;
            out.append(smartifyText(line.substring(i, next)));
            i = next;
        }
        return out.toString();
    }

    private static boolean isOpeningContext(char prev) {
        return Character.isWhitespace(prev) || prev == '(' || prev == '[' || prev == '{'
            || prev == '\u2014' || prev == '\u2013' || prev == '-' || prev == '\u201C' || prev == '\u2018';
    }

    private static String externalUrl(String text) {
        if (text.startsWith("#") && text.length() > 1 && text.matches("#[A-Za-z0-9._:-]+")) return text;
        if (!text.matches("(?i)https?://.+|mailto:.+")) return null;
        try {
            var uri = URI.create(text);
            if (!"mailto".equalsIgnoreCase(uri.getScheme()) && uri.getRawAuthority() == null) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String normalize(String source) {
        return source.replaceFirst("^\\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
    }
}
