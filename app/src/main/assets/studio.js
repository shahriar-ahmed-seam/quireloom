"use strict";

const $ = id => document.getElementById(id);
const pages = [
    ["book", "Book", "▤"], ["write", "Write", "✎"],
    ["design", "Design", "◇"], ["publish", "Publish", "⇧"]
];
const roles = [
    ["CHAPTER", "Chapter"], ["PART", "Part divider"], ["FRONTMATTER", "Frontmatter"],
    ["BACKMATTER", "Backmatter"], ["PLATE", "Illustration plate"]
];
const themes = [
    ["CLASSIC", "Classic Book"], ["MODERN", "Modern Editorial"], ["LITERARY", "Literary Paper"],
    ["TECHNICAL", "Technical & Docs"], ["POETRY", "Poetry & Verse"], ["MINIMAL", "Minimal E-Ink"]
];
const bodyField = $("manuscript");
const modal = $("modal");
let book, active = "book", selected = 0, isBusy = true, dirty = false, systemDark = false;
let themeChoice = Mobile.themePreference();
let saveTimer, previewTimer, toastTimer, typing;
let undo = [], redo = [];
let findSettings = {};
let readWords = new WeakMap();
let previewKey = "";
let drag = null;
let pendingExternalOpen = false;

function element(tag, value, className) {
    const node = document.createElement(tag);
    if (value !== null) node.textContent = value;
    if (className) node.className = className;
    return node;
}

function button(root, label, action, className = "button") {
    const node = element("button", label, className);
    node.type = "button";
    node.addEventListener("click", action);
    root.append(node);
    return node;
}

function systemTheme(dark) {
    systemDark = dark;
    applyTheme();
}

function applyTheme() {
    const dark = themeChoice === "system" ? systemDark : themeChoice === "true";
    document.documentElement.classList.toggle("dark", dark);
    $("themeToggle").textContent = dark ? "☀" : "◐";
    $("themeToggle").setAttribute("aria-label", dark ? "Use light theme" : "Use dark theme");
    Mobile.chrome(dark);
    previewKey = "";
    renderLive();
}

$("themeToggle").addEventListener("click", () => {
    themeChoice = String(!document.documentElement.classList.contains("dark"));
    Mobile.theme(themeChoice === "true");
    applyTheme();
});

function notice(message) {
    clearTimeout(toastTimer);
    $("toast").textContent = message;
    $("toast").hidden = false;
    toastTimer = setTimeout(() => { $("toast").hidden = true; }, 3300);
}

function busy(state) {
    isBusy = state;
    $("workspace").setAttribute("aria-busy", String(state));
    $("working").hidden = !state;
    if (book) $("workingText").textContent = "Working…";
    if (!state && pendingExternalOpen) queueMicrotask(openExternalBook);
}

function imeVisible(visible) {
    document.body.classList.toggle("keyboard", visible);
}

function watching(state) {
    $("stopWatch").disabled = !state;
}

function loadBook(data, switched) {
    clearTimeout(saveTimer);
    if (switched) {
        undo = [];
        redo = [];
        selected = 0;
        readWords = new WeakMap();
    } else if (book && (JSON.stringify(book.chapters) !== JSON.stringify(data.chapters) ||
                        JSON.stringify(book.meta) !== JSON.stringify(data.meta))) {
        remember();
    }
    book = { ...data, revision: 0 };
    dirty = false;
    selected = Math.min(selected, Math.max(0, book.chapters.length - 1));
    typing = null;
    previewKey = "";
    show(switched ? "book" : active, switched);
    busy(false);
}

function flush() {
    clearTimeout(saveTimer);
    if (book && dirty) Mobile.save(JSON.stringify(book));
}

function saved(generation, revision) {
    if (book?.generation === generation && book?.revision === revision) dirty = false;
}

function later() {
    book.revision++;
    dirty = true;
    clearTimeout(saveTimer);
    clearTimeout(previewTimer);
    saveTimer = setTimeout(flush, 600);
    previewTimer = setTimeout(renderLive, 260);
}

function go(command) {
    if (!book || isBusy) return;
    clearTimeout(saveTimer);
    typing = null;
    busy(true);
    Mobile.go(command, JSON.stringify(book), chapter() ? selected : -1, bodyField.selectionStart);
}

function openExternalBook() {
    pendingExternalOpen = !book || isBusy;
    if (!pendingExternalOpen) go("external");
}

function snapshot() {
    return { data: JSON.stringify([book.meta, book.design, book.chapters]), selected };
}

function trimHistory(stack) {
    let size = 0;
    for (let i = stack.length - 1; i >= 0; i--) {
        size += stack[i].data.length;
        if ((size > 7_000_000 && i < stack.length - 1) || stack.length - i > 45) {
            stack.splice(0, i + 1);
            break;
        }
    }
}

function remember() {
    undo.push(snapshot());
    trimHistory(undo);
    redo = [];
    updateHistory();
}

function change(action) {
    typing = null;
    remember();
    action();
    refresh();
    later();
}

function historyStep(backwards) {
    const source = backwards ? undo : redo;
    const destination = backwards ? redo : undo;
    if (!book || !source.length) return;
    typing = null;
    destination.push(snapshot());
    trimHistory(destination);
    const state = source.pop();
    [book.meta, book.design, book.chapters] = JSON.parse(state.data);
    selected = Math.min(state.selected, Math.max(0, book.chapters.length - 1));
    show(active, false);
    later();
}

function updateHistory() {
    $("bookHistory").hidden = !undo.length && !redo.length;
    document.querySelectorAll("[data-history]").forEach(control => {
        control.disabled = control.dataset.history === "undo" ? !undo.length : !redo.length;
    });
}

function trackField(node) {
    node.addEventListener("beforeinput", event => {
        if (!book || event.inputType === "historyUndo" || event.inputType === "historyRedo") return;
        const now = Date.now();
        if (!typing || typing.target !== node || now - typing.time > 850) remember();
        typing = { target: node, time: now };
    });
    node.addEventListener("blur", () => { if (typing?.target === node) typing = null; });
}

function chapter() { return book?.chapters[selected]; }

function show(name, scroll = true) {
    if (!book) return;
    if (name === "write" && !chapter()) {
        notice("Add a chapter to start writing.");
        name = "book";
    }
    if (active !== name) {
        typing = null;
        focusMode(false);
    }
    active = name;
    for (const [key] of pages) {
        $(key + "Page").hidden = key !== name;
        $("tab-" + key).setAttribute("aria-current", key === name ? "page" : "false");
    }
    refresh();
    if (scroll) window.scrollTo(0, 0);
}

for (const [key, title, glyph] of pages) {
    const tab = button($("tabs"), null, () => show(key), "tab");
    const icon = element("span", glyph, "tab-icon");
    icon.setAttribute("aria-hidden", "true");
    tab.append(icon, element("span", title));
    tab.id = "tab-" + key;
}

function countWords(row) {
    const text = row[1], previous = readWords.get(row);
    if (previous?.text === text) return previous.count;
    const prose = text.replace(/^!\[[^\n]*\]\(img-[^)\n]+\)$/gm, "")
        .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1");
    const count = (prose.match(/[\p{L}\p{M}\p{N}]+(?:['’\-][\p{L}\p{M}\p{N}]+)*/gu) || []).length;
    readWords.set(row, { text, count });
    return count;
}

const chapterLabel = row => row[0].trim() || "Untitled chapter";
const plural = (count, word) => count.toLocaleString() + " " + word + (count === 1 ? "" : "s");
const totalWords = () => book.chapters.reduce((sum, row) => sum + countWords(row), 0);
const minutes = words => Math.ceil(words / 250) + " min read";
const roleLabel = name => roles.find(([key]) => key === name)?.[1] || "Chapter";

function refresh() {
    if (!book) return;
    updateHistory();
    if (active === "book") showBook();
    if (active === "write" && chapter()) {
        const row = chapter();
        $("chapterTitle").value = row[0];
        if (bodyField.value !== row[1]) bodyField.value = row[1];
        $("markdown").checked = row[2];
        $("chapterRole").value = row[3];
        $("hideHeading").checked = row[4];
        currentStats();
        renderLive();
    }
    if (active === "design") showDesign();
}

function showBook() {
    const count = book.chapters.length, words = totalWords();
    $("bookTitle").textContent = book.meta[0].trim() || "Untitled manuscript";
    $("bookAuthor").textContent = book.meta[1];
    $("bookAuthor").hidden = !book.meta[1].trim();
    $("bookStats").textContent = plural(words, "word") + " · " + minutes(words);
    $("chapterCount").textContent = plural(count, "chapter");
    $("coverImage").hidden = !book.cover;
    $("coverPlaceholder").hidden = book.cover;
    if (book.cover) $("coverImage").src = "/cover?v=" + book.generation;
    $("emptyBook").hidden = count > 0;
    const rows = $("chapterList");
    rows.replaceChildren();
    book.chapters.forEach((chapter, index) => {
        const row = element("li", null, "chapter-row");
        row.dataset.index = index;
        const handle = button(row, "⠿", () => {}, "icon drag");
        handle.setAttribute("aria-label", "Reorder " + chapterLabel(chapter));
        reorderHandle(handle, row, index);
        const title = button(row, null, () => { selected = index; show("write"); }, "chapter-entry");
        const details = element("span", null);
        details.append(element("b", chapterLabel(chapter)),
            element("span", roleLabel(chapter[3]) + " · " + plural(countWords(chapter), "word"), "muted"));
        title.append(element("span", String(index + 1).padStart(2, "0"), "chapter-number"), details);
        const more = button(row, "⋯", () => { selected = index; chapterMenu(); }, "icon");
        more.setAttribute("aria-label", "Actions for " + chapterLabel(chapter));
        rows.append(row);
    });
}

function reorderHandle(handle, row, source) {
    handle.addEventListener("click", event => {
        if (event.detail === 0) { selected = source; moveDialog(); }
    });
    handle.addEventListener("pointerdown", event => {
        if (!event.isPrimary || event.button !== 0) return;
        handle.setPointerCapture(event.pointerId);
        drag = { source, target: source, row };
        row.classList.add("dragged");
    });
    handle.addEventListener("pointermove", event => {
        if (!drag || drag.row !== row) return;
        if (event.clientY < 85) window.scrollBy(0, -14);
        if (event.clientY > window.innerHeight - 85) window.scrollBy(0, 14);
        const target = document.elementFromPoint(event.clientX, event.clientY)?.closest(".chapter-row");
        if (!target) return;
        $("chapterList").children[drag.target]?.classList.remove("target");
        drag.target = Number(target.dataset.index);
        target.classList.add("target");
    });
    function finish(commit) {
        if (!drag || drag.row !== row) return;
        const { source, target } = drag;
        drag = null;
        row.classList.remove("dragged");
        $("chapterList").children[target]?.classList.remove("target");
        if (commit && source !== target) moveChapter(source, target);
    }
    handle.addEventListener("pointerup", () => finish(true));
    handle.addEventListener("pointercancel", () => finish(false));
    handle.addEventListener("lostpointercapture", () => finish(false));
}

function moveChapter(from, to) {
    if (from === to || !book.chapters[to]) return;
    change(() => {
        const [row] = book.chapters.splice(from, 1);
        book.chapters.splice(to, 0, row);
        selected = to;
    });
}

function moveDialog() {
    const content = openModal("Move chapter");
    const position = field(content, "Reading position", "number", selected + 1);
    position.required = true;
    position.min = 1;
    position.max = book.chapters.length;
    modalButton("Move", () => {
        const to = position.valueAsNumber - 1;
        if (!Number.isInteger(to) || !book.chapters[to]) { position.reportValidity(); return; }
        modal.close();
        moveChapter(selected, to);
    }, true);
}

function appendChapters(rows) {
    if (!rows.length) return;
    if (rows.length + book.chapters.length > 2000) { notice("A book can contain up to 2,000 chapters."); return; }
    change(() => {
        selected = book.chapters.length;
        book.chapters.push(...rows);
    });
    show("write");
}

function addChapter() {
    appendChapters([["Chapter " + (book.chapters.length + 1), "", true, "CHAPTER", false]]);
}

function currentStats() {
    if (!chapter()) return;
    const words = countWords(chapter());
    const total = totalWords();
    $("chapterStats").textContent = plural(words, "word") + " in chapter · " + plural(total, "word") + " in book";
}

function showPacing() {
    const content = openModal("Book statistics & pacing");
    const words = totalWords();
    content.append(element("p", plural(book.chapters.length, "chapter") + " · " + plural(words, "word") + " · " + minutes(words)));
    const max = Math.max(1, ...book.chapters.map(row => countWords(row)));
    for (const row of book.chapters) {
        const value = countWords(row);
        const line = element("div", null, "pace-row");
        const meter = element("meter", null);
        meter.max = max;
        meter.value = value;
        meter.setAttribute("aria-label", chapterLabel(row) + " word count");
        line.append(element("span", chapterLabel(row)), element("span", value.toLocaleString()), meter);
        content.append(line);
    }
    modalButton("Done", () => modal.close(), true);
}

function field(root, label, type, value, options = []) {
    const wrapper = element("label", label, type === "checkbox" ? "check" : "field");
    const input = element(type === "select" || type === "textarea" ? type : "input", null);
    if (type === "textarea") input.rows = 4;
    else if (type === "select") for (const [key, name] of options) input.add(new Option(name, key));
    else input.type = type;
    if (type === "checkbox") {
        input.checked = value;
        wrapper.prepend(input);
    } else {
        input.value = value;
        wrapper.append(input);
    }
    root.append(wrapper);
    return input;
}

function showDesign() {
    const meta = $("metadata");
    const design = $("designOptions");
    meta.replaceChildren();
    design.replaceChildren();
    const fields = [
        [meta, book.meta, [
            ["Title", "text"], ["Author", "text"], ["Language", "text"], ["Subtitle", "text"],
            ["Description", "textarea"], ["Publisher", "text"], ["Subjects", "text"], ["Series", "text"],
            ["Series index", "text"], ["Rights", "text"], ["Publication date", "text"]
        ]],
        [design, book.design, [
            ["Theme", "select"], ["Scene break ornament", "text"], ["Smart typography", "checkbox"],
            ["Drop caps", "checkbox"], ["Title page", "checkbox"], ["Copyright page", "checkbox"],
            ["EPUB 2 NCX fallback", "checkbox"], ["Custom CSS", "textarea"]
        ]]
    ];
    for (const [root, values, definitions] of fields) {
        definitions.forEach(([label, type], index) => {
            const input = field(root, label, type, values[index], themes);
            if (type === "textarea") input.parentElement.classList.add("wide");
            if (label === "Custom CSS") input.classList.add("code");
            if (label === "Language") input.placeholder = "en";
            if (label === "Scene break ornament") {
                input.setAttribute("list", "sceneOrnaments");
                input.placeholder = "Horizontal rule";
            }
            if (type === "text" || type === "textarea") trackField(input);
            input.addEventListener("input", () => {
                if (type === "checkbox" || type === "select") remember();
                values[index] = type === "checkbox" ? input.checked : input.value;
                later();
            });
        });
    }
    for (const key of ["removeCover", "exportCover"]) {
        document.querySelector('[data-command="' + key + '"]').disabled = !book.cover;
    }
}

function openModal(title) {
    if (modal.open) modal.close();
    $("modalTitle").textContent = title;
    $("modalContent").replaceChildren();
    $("modalActions").replaceChildren();
    modal.showModal();
    return $("modalContent");
}

function modalButton(title, action, primary = false) {
    return button($("modalActions"), title, action, primary ? "button primary" : "button");
}

function confirmAction(title, message, label, action) {
    openModal(title).append(element("p", message));
    modalButton("Cancel", () => modal.close());
    modalButton(label, () => { modal.close(); action(); }, true);
}

function pasteManuscript() {
    const content = openModal("Paste manuscript");
    const text = field(content, "Manuscript", "textarea", "");
    const split = field(content, "Split by chapter headings", "checkbox", true);
    const markdown = field(content, "Interpret as Markdown", "checkbox", true);
    modalButton("Add chapters", () => {
        if (!text.value.trim()) return;
        const chapters = JSON.parse(Mobile.chaptersFromText(text.value, split.checked, markdown.checked));
        modal.close();
        appendChapters(chapters);
    }, true);
}

function focusMode(on) {
    document.body.classList.toggle("focus", on);
    $("focusToggle").setAttribute("aria-pressed", String(on));
    $("focusToggle").textContent = on ? "Exit focus" : "Focus";
}

for (const [key, label] of roles) $("chapterRole").add(new Option(label, key));
for (const [id, index] of [["chapterTitle", 0], ["manuscript", 1], ["markdown", 2], ["chapterRole", 3], ["hideHeading", 4]]) {
    const node = $(id);
    const toggle = node.type === "checkbox" || node.tagName === "SELECT";
    if (!toggle) trackField(node);
    node.addEventListener("input", () => {
        if (!chapter()) return;
        if (toggle) remember();
        chapter()[index] = node.type === "checkbox" ? node.checked : node.value;
        if (index === 1) currentStats();
        later();
    });
}

function replaceSelection(text, scope, selectFrom, selectTo) {
    const start = bodyField.selectionStart, end = bodyField.selectionEnd;
    const value = bodyField.value;
    const from = scope === "line" ? value.lastIndexOf("\n", start - 1) + 1 : start;
    change(() => {
        chapter()[1] = value.slice(0, from) + text + value.slice(scope === "line" ? from : end);
        chapter()[2] = true;
    });
    bodyField.focus({ preventScroll: true });
    bodyField.setSelectionRange(from + selectFrom, from + selectTo);
}

function wrap(before, after, placeholder) {
    const text = bodyField.value.slice(bodyField.selectionStart, bodyField.selectionEnd) || placeholder;
    replaceSelection(before + text + after, "selection", before.length, before.length + text.length);
}

function prefix(value) { replaceSelection(value, "line", value.length, value.length); }
function insert(value) { replaceSelection(value, "selection", value.length, value.length); }

function addNote() {
    let number = 1;
    const existing = new Set([...bodyField.value.matchAll(/\[\^([0-9]+)\]/g)].map(match => Number(match[1])));
    while (existing.has(number)) number++;
    const reference = "[^" + number + "]";
    const at = bodyField.selectionStart;
    change(() => {
        chapter()[1] = bodyField.value.slice(0, at) + reference + bodyField.value.slice(at)
            + "\n\n" + reference + ": Footnote text";
        chapter()[2] = true;
    });
    bodyField.focus();
    bodyField.setSelectionRange(bodyField.value.length - 13, bodyField.value.length);
}

const formats = [
    ["Bold", () => wrap("**", "**", "bold text")], ["Italic", () => wrap("*", "*", "italic text")],
    ["Heading", () => prefix("## ")], ["Link", () => wrap("[", "](https://)", "link text")],
    ["Quote", () => prefix("> ")], ["List", () => prefix("- ")], ["Nested list", () => prefix("  - ")],
    ["Numbered", () => prefix("1. ")], ["Task", () => prefix("- [ ] ")],
    ["Table", () => insert("\n\n| A | B |\n|---|---|\n|   |   |\n\n")],
    ["Scene break", () => insert("\n\n---\n\n")], ["Footnote", addNote],
    ["Illustration", () => go("inline")],
    ["Polish", () => change(() => { chapter()[1] = Mobile.polish(chapter()[1], book.design[2]); })]
];
for (const [label, action] of formats) {
    const control = button($("formatBar"), label, action, "text-button");
    control.addEventListener("pointerdown", event => event.preventDefault());
}

function chapterMenu() {
    if (!chapter()) return;
    const content = openModal(chapterLabel(chapter()));
    const actions = [
        ["Duplicate chapter", () => change(() => {
            if (book.chapters.length >= 2000) return notice("A book can contain up to 2,000 chapters.");
            const copy = [...chapter()];
            copy[0] = chapterLabel(copy) + " (copy)";
            book.chapters.splice(selected + 1, 0, copy);
            selected++;
        })],
        ["Move chapter", moveDialog, book.chapters.length < 2],
        ["Split at cursor", splitChapter, active !== "write"],
        ["Split by headings", splitByHeadings],
        ["Merge with next", mergeChapters, selected + 1 >= book.chapters.length],
        ["Delete chapter", () => confirmAction("Delete chapter?", chapterLabel(chapter()), "Delete", () => {
            change(() => {
                book.chapters.splice(selected, 1);
                selected = Math.min(selected, Math.max(0, book.chapters.length - 1));
            });
            if (!chapter()) show("book");
        })]
    ];
    for (const [title, action, disabled] of actions) {
        button(content, title, () => { modal.close(); action(); }).disabled = !!disabled;
    }
}

function splitChapter() {
    const at = bodyField.selectionStart, row = chapter();
    if (at <= 0 || at >= row[1].length) return notice("Place the cursor inside the manuscript to split the chapter.");
    if (book.chapters.length >= 2000) return notice("A book can contain up to 2,000 chapters.");
    const marker = [...row[1].matchAll(/^!\[[^\]\n]*\]\(img-[^)\n]+\)$/gm)]
        .find(match => at > match.index && at < match.index + match[0].length);
    if (marker) return notice("Place the cursor outside the illustration.");
    change(() => {
        const next = [chapterLabel(row) + " (continued)", row[1].slice(at).trimStart(), row[2], "CHAPTER", false];
        row[1] = row[1].slice(0, at).trimEnd();
        book.chapters.splice(++selected, 0, next);
    });
}

function splitByHeadings() {
    const row = chapter();
    const sections = JSON.parse(Mobile.chaptersFromText(row[1], true, row[2]));
    if (sections.length < 2) return notice("This chapter has fewer than two sections.");
    if (book.chapters.length + sections.length - 1 > 2000) return notice("A book can contain up to 2,000 chapters.");
    if (sections[0][0] === "Introduction" && !/^#\s+Introduction/i.test(row[1].trimStart())) sections[0][0] = row[0];
    sections[0][3] = row[3];
    sections[0][4] = row[4];
    change(() => book.chapters.splice(selected, 1, ...sections));
}

function mergeChapters() {
    change(() => {
        const [next] = book.chapters.splice(selected + 1, 1);
        chapter()[1] += "\n\n" + next[1];
        chapter()[2] ||= next[2];
    });
}

function pattern(query, regex, matchCase, wholeWord) {
    const safe = regex ? query : query.replace(/[.*+?^$(){}|[\]\\]/g, "\\$&");
    const expr = wholeWord ? "(?<![\\p{L}\\p{M}\\p{N}_])(?:" + safe + ")(?![\\p{L}\\p{M}\\p{N}_])" : safe;
    return new RegExp(expr, "gu" + (matchCase ? "" : "i"));
}

function textSegments(text, take) {
    const image = /^!\[[^\]\n]*\]\(img-[^)\n]+\)$/gm;
    let start = 0, result = "";
    for (const match of text.matchAll(image)) {
        result += take(text.slice(start, match.index), start) + match[0];
        start = match.index + match[0].length;
    }
    return result + take(text.slice(start), start);
}

function chapterMatches(expression, text) {
    const spans = [];
    textSegments(text, (part, offset) => {
        for (const match of part.matchAll(expression)) spans.push([offset + match.index, offset + match.index + match[0].length]);
        return part;
    });
    return spans;
}

function findDialog(all) {
    if (!all && !chapter()) return;
    const content = openModal(all ? "Find & replace across book" : "Find & replace in chapter");
    const query = field(content, "Find", "text", findSettings.query || "");
    const replacement = field(content, "Replace with", "text", findSettings.replacement || "");
    const regex = field(content, "Regular expression", "checkbox", !!findSettings.regex);
    const matchCase = field(content, "Match case", "checkbox", !!findSettings.matchCase);
    const wholeWord = field(content, "Whole word", "checkbox", !!findSettings.wholeWord);
    const titles = all ? field(content, "Include chapter titles", "checkbox", !!findSettings.titles) : null;
    const summary = element("p", "", "muted");
    summary.setAttribute("role", "status");
    content.append(summary);

    function read() {
        findSettings = { query: query.value, replacement: replacement.value, regex: regex.checked,
            matchCase: matchCase.checked, wholeWord: wholeWord.checked, titles: titles?.checked };
        return query.value ? pattern(query.value, regex.checked, matchCase.checked, wholeWord.checked) : null;
    }
    function compute() {
        const expr = read();
        if (!expr) return null;
        let total = 0;
        const fn = (text, protect) => {
            const replace = part => {
                total += [...part.matchAll(expr)].length;
                return part.replace(expr, regex.checked ? replacement.value : () => replacement.value);
            };
            return protect ? textSegments(text, replace) : replace(text);
        };
        const rows = book.chapters.map((row, index) => {
            if (!all && index !== selected) return row;
            const title = titles?.checked ? fn(row[0], false) : row[0];
            return [title.trim() || row[0], fn(row[1], true), row[2], row[3], row[4]];
        });
        return { rows, total };
    }
    function refreshMatches() {
        try {
            const result = compute();
            summary.textContent = result ? plural(result.total, "match").replace("matchs", "matches") : "";
        } catch (error) { summary.textContent = error.message; }
    }
    for (const input of [query, replacement, regex, matchCase, wholeWord, titles].filter(Boolean)) {
        input.addEventListener("input", refreshMatches);
    }
    if (!all) {
        modalButton("Find next", () => {
            try {
                const expr = read();
                if (!expr) return;
                const spans = chapterMatches(expr, chapter()[1]);
                const caret = Math.max(bodyField.selectionEnd, bodyField.selectionStart + 1);
                const next = spans.find(([start]) => start >= caret) || spans[0];
                if (!next) return;
                modal.close();
                show("write", false);
                bodyField.focus();
                bodyField.setSelectionRange(...next);
            } catch (error) { summary.textContent = error.message; }
        });
        modalButton("Replace selected", () => {
            try {
                const expr = read();
                if (!expr) return;
                const a = bodyField.selectionStart, b = bodyField.selectionEnd;
                const match = chapterMatches(expr, chapter()[1]).find(([start, end]) => a === start && b === end);
                if (!match) { summary.textContent = "Find and select a matching passage first."; return; }
                const original = chapter()[1];
                const value = original.slice(a, b).replace(expr, regex.checked ? replacement.value : () => replacement.value);
                change(() => { chapter()[1] = original.slice(0, a) + value + original.slice(b); });
                modal.close();
                bodyField.focus();
                bodyField.setSelectionRange(a, a + value.length);
            } catch (error) { summary.textContent = error.message; }
        });
    }
    modalButton("Replace all", () => {
        try {
            const result = compute();
            if (!result?.total) { summary.textContent = "No matches"; return; }
            const message = "Apply " + result.total + (result.total === 1 ? " replacement?" : " replacements?");
            confirmAction("Apply replacements", message, "Replace", () => {
                change(() => { book.chapters = result.rows; });
                notice("Replaced " + result.total + (result.total === 1 ? " match" : " matches"));
            });
        } catch (error) { summary.textContent = error.message; }
    }, true);
    refreshMatches();
}

function renderFrame(frame, row) {
    if (!book || !frame.contentDocument?.body) return;
    const doc = frame.contentDocument;
    const scroll = doc.scrollingElement.scrollTop;
    const data = JSON.parse(Mobile.render(JSON.stringify(row), JSON.stringify(book.design)));
    if (!data.html) return;
    let style = doc.getElementById("bookStyle");
    if (!style) {
        style = doc.createElement("style");
        style.id = "bookStyle";
        doc.head.append(style);
        doc.addEventListener("click", event => {
            const link = event.target.closest("a");
            if (link && !link.hash.startsWith("#fn") && !link.hash.startsWith("#ref")) event.preventDefault();
        });
    }
    const dark = document.documentElement.classList.contains("dark");
    style.textContent = "html{background:" + (dark ? "#222b38;color:#f0ece0" : "#fffdf7;color:#242d27")
        + "}body{font-size:17px;overflow-wrap:anywhere}img{max-width:100%;height:auto}pre{overflow:auto}\n" + data.css;
    const template = doc.createElement("template");
    template.innerHTML = data.html;
    for (const img of template.content.querySelectorAll("img")) {
        const key = img.getAttribute("src");
        if (key?.startsWith("img-")) img.src = location.origin + "/illustration/" + encodeURIComponent(key);
    }
    const images = new Map([...doc.body.querySelectorAll("img")].map(img => [img.src + "\n" + img.alt, img]));
    for (const img of template.content.querySelectorAll("img")) {
        const existing = images.get(img.src + "\n" + img.alt);
        if (existing) img.replaceWith(existing);
    }
    doc.body.replaceChildren(template.content);
    doc.scrollingElement.scrollTop = scroll;
    requestAnimationFrame(() => { if (doc.scrollingElement) doc.scrollingElement.scrollTop = scroll; });
}

function renderLive() {
    const frame = $("livePreview");
    if (active !== "write" || !chapter() || frame.hidden || !frame.contentDocument?.body) return;
    const key = JSON.stringify([chapter(), book.design, book.generation]);
    if (key === previewKey) return;
    renderFrame(frame, chapter());
    previewKey = key;
}

function toggleLive() {
    const frame = $("livePreview"), show = frame.hidden;
    frame.hidden = !show;
    $("liveToggle").setAttribute("aria-pressed", String(show));
    $("liveToggle").textContent = show ? "Hide preview" : "Live preview";
    $("editorLayout").classList.toggle("with-preview", show);
    if (show) renderLive();
}

function previewDesign() {
    const content = openModal("Typography preview");
    const frame = element("iframe", null, "modal-sample");
    frame.title = "Book typography sample";
    frame.setAttribute("sandbox", "allow-same-origin");
    frame.addEventListener("load", () => renderFrame(frame, ["The Lantern Keeper",
        '"Listen carefully," she whispered -- the storm was rolling in...\n\n---\n\n> Every *page* holds a **world**.',
        true, "CHAPTER", false]));
    frame.src = "about:blank";
    content.append(frame);
    modalButton("Done", () => modal.close(), true);
}

function coverMenu() {
    const content = openModal("Book cover");
    for (const [label, name] of [["Cover Designer", "designCover"], ["Choose cover image", "cover"],
        ["Export cover image", "exportCover"], ["Remove cover", "removeCover"]]) {
        button(content, label, () => { modal.close(); command(name); }).disabled =
            !book.cover && (name === "removeCover" || name === "exportCover");
    }
}

function command(name) {
    if (name === "removeCover") confirmAction("Remove the cover?", "The current draft will have no cover image.", "Remove", () => go(name));
    else go(name);
}

function toolGroup(title, items) {
    const panel = element("section", null, "panel tool-panel");
    const grid = element("div", null, "action-grid");
    panel.append(element("h2", title), grid);
    for (const [label, action] of items) {
        const control = button(grid, label, typeof action === "function" ? action : () => go(action));
        if (action === "stopWatch") { control.id = "stopWatch"; control.disabled = true; }
    }
    $("publishingTools").append(panel);
}

toolGroup("Manuscript", [
    ["Read full book", "preview"], ["Find & replace across book", () => findDialog(true)], ["Check draft", "doctor"]
]);
toolGroup("Export", [
    ["EPUB 3.3", "epub"], ["HTML reader", "html"], ["Markdown folder", "markdown"],
    ["Markdown ZIP", "markdownZip"], ["Portable draft (.ql)", "backup"]
]);
toolGroup("EPUB tools", [
    ["Omnibus Merger", "merge"], ["Split EPUB", "split"], ["Package Studio", "package"], ["EPUB Doctor", "check"]
]);
toolGroup("Folder projects", [
    ["Build from folder", "folder"], ["Watch while app is open", "watch"], ["Stop watching", "stopWatch"]
]);

for (const control of document.querySelectorAll("[data-command]")) {
    control.addEventListener("click", () => command(control.dataset.command));
}
for (const control of document.querySelectorAll("[data-history]")) {
    control.addEventListener("click", () => historyStep(control.dataset.history === "undo"));
}
$("home").onclick = $("backToBook").onclick = () => show("book");
$("showPacing").onclick = showPacing;
$("coverTile").onclick = coverMenu;
$("addChapter").onclick = addChapter;
$("pasteBook").onclick = pasteManuscript;
$("closeModal").onclick = () => modal.close();
$("findChapter").onclick = () => findDialog(false);
$("chapterMenu").onclick = chapterMenu;
$("liveToggle").onclick = toggleLive;
$("livePreview").addEventListener("load", renderLive);
$("styleSample").onclick = previewDesign;
$("focusToggle").onclick = () => focusMode(!document.body.classList.contains("focus"));

document.addEventListener("keydown", event => {
    if (!book || modal.open) return;
    if (event.key === "Escape" && document.body.classList.contains("focus")) { focusMode(false); return; }
    if (event.key === "F11" && active === "write") {
        event.preventDefault();
        focusMode(!document.body.classList.contains("focus"));
        return;
    }
    if (!(event.ctrlKey || event.metaKey)) return;
    const key = event.key.toLowerCase();
    if (key === "z" || key === "y") { event.preventDefault(); historyStep(key === "z" && !event.shiftKey); }
    if (key === "h" || key === "f") {
        event.preventDefault();
        if (event.shiftKey && key === "h") findDialog(true);
        else if (active === "write") findDialog(false);
    }
    if (active !== "write") return;
    if (key === "p" && event.shiftKey) { event.preventDefault(); toggleLive(); }
    if (key === "b" || key === "i" || key === "k") {
        event.preventDefault();
        formats[key === "b" ? 0 : key === "i" ? 1 : 3][1]();
    }
});

document.addEventListener("visibilitychange", () => { if (document.hidden) flush(); });

function back() {
    if (modal.open) { modal.close(); return true; }
    if (document.body.classList.contains("focus")) { focusMode(false); return true; }
    if (active !== "book") { show("book"); return true; }
    flush();
    return false;
}
