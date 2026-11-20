package io.quireloom.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipOutputStream;

final class PackageDocument {
    private PackageDocument() {}

    static String opf(EpubArchive archive, boolean metadataEdited) throws Exception {
        var parser = BookFiles.newXmlParser();
        var opf = parser.parse(new ByteArrayInputStream(archive.files.get(archive.opfPath)));
        var manifest = BookFiles.firstElementAnyNs(opf, "manifest");
        var spine = BookFiles.firstElementAnyNs(opf, "spine");
        if (manifest == null || spine == null) throw new IOException("The package needs a manifest and spine.");
        var entries = new HashMap<String, Element>();
        for (var item : BookFiles.elementsByLocalName(manifest, "item")) {
            entries.put(item.getAttribute("id"), item);
            manifest.removeChild(item);
        }
        boolean epub3 = archive.epubVersion.startsWith("3");
        for (var item : archive.manifest.values()) {
            var entry = entries.get(item.id);
            if (entry == null) entry = element(opf, manifest, "item");
            entry.setAttribute("id", item.id);
            boolean existingHref = false;
            if (entry.hasAttribute("href")) {
                try { existingHref = item.path.equals(BookFiles.resolveHref(archive.opfPath, entry.getAttribute("href"))); }
                catch (IOException ignored) {}
            }
            if (!existingHref) entry.setAttribute("href", EpubArchive.relativeHref(archive.opfPath, item.path));
            entry.setAttribute("media-type", item.mediaType);
            if (epub3) {
                String properties = item.properties.replaceAll("(^|\\s)cover-image(?=\\s|$)", "").strip();
                if (item.id.equals(archive.coverItemId)) properties = (properties + " cover-image").strip();
                attribute(entry, "properties", properties);
            }
            manifest.appendChild(entry);
        }

        var refs = new HashMap<String, ArrayDeque<Element>>();
        for (var ref : BookFiles.elementsByLocalName(spine, "itemref")) {
            refs.computeIfAbsent(ref.getAttribute("idref"), k -> new ArrayDeque<>()).add(ref);
            spine.removeChild(ref);
        }
        for (var row : archive.spine) {
            var existing = refs.get(row.idref);
            var ref = existing == null || existing.isEmpty() ? element(opf, spine, "itemref") : existing.removeFirst();
            ref.setAttribute("idref", row.idref);
            if (!row.linear) ref.setAttribute("linear", "no");
            else if ("no".equals(ref.getAttribute("linear"))) ref.removeAttribute("linear");
            if (epub3) attribute(ref, "properties", row.properties);
            spine.appendChild(ref);
        }

        var metadata = BookFiles.firstElementAnyNs(opf, "metadata");
        if (metadata == null) throw new IOException("The package needs metadata.");
        if (metadataEdited) applyMetadata(opf, metadata, archive.metadata, epub3);
        Element cover = null;
        for (var meta : BookFiles.elementsByLocalName(metadata, "meta")) {
            if ("cover".equals(meta.getAttribute("name"))) { cover = meta; break; }
        }
        if (cover != null || !epub3) {
            if (archive.coverItemId == null) {
                if (cover != null) metadata.removeChild(cover);
            } else {
                if (cover == null) {
                    cover = element(opf, metadata, "meta");
                    cover.setAttribute("name", "cover");
                    metadata.appendChild(cover);
                }
                cover.setAttribute("content", archive.coverItemId);
            }
        }
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var out = new StringWriter();
        transformer.transform(new DOMSource(opf), new StreamResult(out));
        return out.toString();
    }

    private static void applyMetadata(Document doc, Element metadata, Epub.Metadata values, boolean epub3) throws Exception {
        var desired = Map.of("title", values.title(), "creator", values.author(), "language", values.language(),
            "description", values.description(), "publisher", values.publisher(), "rights", values.rights(),
            "date", values.date());
        for (var entry : desired.entrySet()) {
            var elements = BookFiles.elementsByLocalName(metadata, entry.getKey());
            Element node = elements.isEmpty() ? null : elements.get(0);
            if (node == null && !entry.getValue().isBlank()) {
                node = doc.createElementNS(BookFiles.DC, "dc:" + entry.getKey());
                metadata.appendChild(node);
            }
            if (node != null && !entry.getValue().equals(node.getTextContent())) {
                if (entry.getValue().isBlank() && !node.hasAttribute("id")) metadata.removeChild(node);
                else node.setTextContent(entry.getValue());
            }
        }
        var subjects = BookFiles.elementsByLocalName(metadata, "subject");
        var editedSubjects = java.util.Arrays.stream(values.subjects().split("[,;]+"))
            .map(String::strip).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toList());
        if (!subjects.stream().map(Element::getTextContent).collect(java.util.stream.Collectors.toList()).equals(editedSubjects)) {
            for (var subject : subjects) metadata.removeChild(subject);
            for (String subject : editedSubjects) {
                var element = doc.createElementNS(BookFiles.DC, "dc:subject");
                element.setTextContent(subject);
                metadata.appendChild(element);
            }
        }
        if (epub3) {
            updateRefinedText(doc, metadata, "title-type", "subtitle", "title", values.subtitle(), "ql-subtitle");
            updateRefinedText(doc, metadata, "collection-type", "series", "meta", values.series(), "ql-series");
            if (!values.series().isBlank()) {
                for (var meta : BookFiles.elementsByLocalName(metadata, "meta")) {
                    if ("collection-type".equals(meta.getAttribute("property")) && "series".equals(meta.getTextContent())) {
                        setProperty(doc, metadata, meta.getAttribute("refines"), "group-position", values.seriesIndex());
                        break;
                    }
                }
            }
        } else {
            setLegacyMeta(doc, metadata, "calibre:series", values.series());
            setLegacyMeta(doc, metadata, "calibre:series_index", values.seriesIndex());
        }
    }

    private static void updateRefinedText(Document doc, Element metadata, String property, String type,
                                          String tag, String value, String baseId) {
        for (var meta : BookFiles.elementsByLocalName(metadata, "meta")) {
            if (!property.equals(meta.getAttribute("property")) || !type.equals(meta.getTextContent())) continue;
            String ref = meta.getAttribute("refines");
            for (var target : BookFiles.elementsByLocalName(metadata, tag)) {
                if (!ref.equals("#" + target.getAttribute("id"))) continue;
                if (value.isBlank()) {
                    for (var refinement : BookFiles.elementsByLocalName(metadata, "meta")) {
                        if (ref.equals(refinement.getAttribute("refines"))) metadata.removeChild(refinement);
                    }
                    metadata.removeChild(target);
                } else target.setTextContent(value);
                return;
            }
        }
        if (value.isBlank()) return;
        var used = new java.util.HashSet<String>();
        for (Node node = metadata.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el) used.add(el.getAttribute("id"));
        }
        String id = baseId;
        for (int i = 2; used.contains(id); i++) id = baseId + "-" + i;
        Element target = tag.equals("title") ? doc.createElementNS(BookFiles.DC, "dc:title") : element(doc, metadata, "meta");
        if (tag.equals("meta")) target.setAttribute("property", "belongs-to-collection");
        target.setAttribute("id", id);
        target.setTextContent(value);
        metadata.appendChild(target);
        setProperty(doc, metadata, "#" + id, property, type);
    }

    private static void setProperty(Document doc, Element metadata, String refines, String property, String value) {
        for (var meta : BookFiles.elementsByLocalName(metadata, "meta")) {
            if (refines.equals(meta.getAttribute("refines")) && property.equals(meta.getAttribute("property"))) {
                if (value.isBlank()) metadata.removeChild(meta);
                else meta.setTextContent(value);
                return;
            }
        }
        if (!value.isBlank()) {
            var meta = element(doc, metadata, "meta");
            meta.setAttribute("refines", refines);
            meta.setAttribute("property", property);
            meta.setTextContent(value);
            metadata.appendChild(meta);
        }
    }

    private static void setLegacyMeta(Document doc, Element metadata, String name, String value) {
        for (var meta : BookFiles.elementsByLocalName(metadata, "meta")) {
            if (name.equals(meta.getAttribute("name"))) {
                if (value.isBlank()) metadata.removeChild(meta);
                else meta.setAttribute("content", value);
                return;
            }
        }
        if (!value.isBlank()) {
            var meta = element(doc, metadata, "meta");
            meta.setAttribute("name", name);
            meta.setAttribute("content", value);
            metadata.appendChild(meta);
        }
    }

    private static Element element(Document doc, Element parent, String name) {
        return doc.createElementNS(parent.getNamespaceURI(),
            parent.getPrefix() == null ? name : parent.getPrefix() + ":" + name);
    }

    private static void attribute(Element element, String name, String value) {
        if (value.isBlank()) element.removeAttribute(name);
        else element.setAttribute(name, value);
    }

    static void write(EpubArchive archive, Path path, byte[] opf) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            Epub.writeMimetype(zip);
            Epub.put(zip, archive.opfPath, opf);
            for (var entry : archive.files.entrySet()) {
                String name = entry.getKey();
                if (!name.equals(archive.opfPath)) Epub.put(zip, name, entry.getValue());
            }
        }
    }
}
