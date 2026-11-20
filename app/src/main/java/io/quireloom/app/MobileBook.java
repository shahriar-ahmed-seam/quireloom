package io.quireloom.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MobileBook {
    private MobileBook() {}

    static JSONArray metadata(Epub.Metadata m) {
        return new JSONArray(List.of(m.title(), m.author(), m.language(), m.subtitle(),
            m.description(), m.publisher(), m.subjects(), m.series(), m.seriesIndex(), m.rights(), m.date()));
    }

    static Epub.Metadata metadata(JSONArray m, String id) throws JSONException {
        return new Epub.Metadata(id, m.getString(0), m.getString(3), m.getString(1), m.getString(2),
            m.getString(4), m.getString(5), m.getString(6), m.getString(7), m.getString(8),
            m.getString(9), m.getString(10));
    }

    static JSONArray design(Epub.Options o) {
        return new JSONArray(List.of(o.theme().name(), o.sceneBreak(), o.smartTypography(),
            o.dropCaps(), o.includeTitlePage(), o.includeCopyright(), o.includeNcx(), o.customCss()));
    }

    static Epub.Options design(JSONArray o) throws JSONException {
        return new Epub.Options(Epub.BookTheme.fromName(o.getString(0)), o.getString(1), o.getBoolean(2),
            o.getBoolean(3), o.getBoolean(4), o.getBoolean(5), o.getBoolean(6), o.getString(7));
    }

    static JSONArray chapter(Epub.Chapter c) {
        return new JSONArray(List.of(c.title(), c.body(), c.markdown(), c.role().name(), c.hideHeading()));
    }

    static JSONArray chapters(List<Epub.Chapter> chapters) {
        var rows = new JSONArray();
        for (var chapter : chapters) rows.put(chapter(chapter));
        return rows;
    }

    static Epub.Chapter chapter(JSONArray row, Map<String, Epub.Image> assets) throws JSONException {
        String body = row.getString(1);
        var images = new HashMap<String, Epub.Image>();
        var matches = Epub.ILLUSTRATION.matcher(body);
        while (matches.find()) {
            var image = assets.get(matches.group(2));
            if (image != null) images.put(matches.group(2), image);
        }
        return new Epub.Chapter(row.getString(0), body, row.getBoolean(2), images,
            Epub.ChapterRole.fromName(row.getString(3)), row.getBoolean(4));
    }

    static Draft read(JSONObject data, Draft previous, Map<String, Epub.Image> assets) throws JSONException {
        var rows = data.getJSONArray("chapters");
        if (rows.length() > BookFiles.CHAPTER_LIMIT) throw new JSONException("A book can contain up to 2,000 chapters.");
        var chapters = new ArrayList<Epub.Chapter>(rows.length());
        for (int i = 0; i < rows.length(); i++) chapters.add(chapter(rows.getJSONArray(i), assets));
        return new Draft(metadata(data.getJSONArray("meta"), previous.id()), chapters, previous.cover(),
            design(data.getJSONArray("design")));
    }

    static Epub.Metadata fillMetadata(Epub.Metadata a, Epub.Metadata b) {
        return new Epub.Metadata(a.id(), first(a.title(), b.title()), first(a.subtitle(), b.subtitle()),
            first(a.author(), b.author()), first(a.language(), b.language()), first(a.description(), b.description()),
            first(a.publisher(), b.publisher()), first(a.subjects(), b.subjects()), first(a.series(), b.series()),
            first(a.seriesIndex(), b.seriesIndex()), first(a.rights(), b.rights()), first(a.date(), b.date()));
    }

    private static String first(String a, String b) {
        return a.isBlank() ? b : a;
    }
}
