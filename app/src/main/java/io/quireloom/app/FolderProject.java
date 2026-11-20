package io.quireloom.app;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class FolderProject {
    record Entry(Path relative, Uri uri, long modified, long size) {}
    static List<Entry> scan(ContentResolver resolver, Uri tree, Uri output) throws IOException {
        var entries = new ArrayList<Entry>();
        var visited = new HashSet<String>();
        walk(resolver, tree, DocumentsContract.getTreeDocumentId(tree), Paths.get(""), output, visited, entries);
        entries.sort(Comparator.comparing(entry -> entry.relative().toString()));
        return entries;
    }
    private static void walk(ContentResolver resolver, Uri tree, String id, Path parent, Uri output,
                             Set<String> visited, List<Entry> entries) throws IOException {
        if (!visited.add(id)) return;
        if (visited.size() > 4096 || parent.getNameCount() > 40) throw new IOException("Folder tree is too large");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        String[] columns = {Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,Document.COLUMN_SIZE};
        try (var cursor=resolver.query(children,columns,null,null,null)) {
            if (cursor==null) throw new IOException("Cannot read the selected folder");
            while(cursor.moveToNext()){
                String child=cursor.getString(0), name=cursor.getString(1);
                if(name==null || name.isBlank() || name.contains("/") || name.equals(".") || name.equals("..")) continue;
                if(output!=null && Objects.equals(output.getAuthority(),tree.getAuthority()) &&
                    DocumentsContract.getDocumentId(output).equals(child)) continue;
                Path rel=parent.resolve(name);
                if(Document.MIME_TYPE_DIR.equals(cursor.getString(2))) walk(resolver,tree,child,rel,output,visited,entries);
                else if(name.matches("(?is).+\\.(epub|txt|md|markdown|pdf|jpeg|jpg|png|gif|webp)")){
                    entries.add(new Entry(rel,DocumentsContract.buildDocumentUriUsingTree(tree,child),cursor.getLong(3),cursor.getLong(4)));
                    if(entries.size()>10000) throw new IOException("Folder has too many files");
                }
            }
        }
    }
    static void build(ContentResolver resolver,List<Entry> entries,Path cache,Path output,Draft settings)throws Exception{
        var root=Files.createTempDirectory(cache,"folder");
        try {
            long total=0;
            for(var entry:entries){
                Path file=root.resolve(entry.relative());Files.createDirectories(file.getParent());
                try(var in=resolver.openInputStream(entry.uri());var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){
                    if(in==null) throw new IOException("Cannot read "+entry.relative());
                    byte[] buffer=new byte[65536];int count;
                    while((count=in.read(buffer))!=-1){total+=count;if(total>256L*1024*1024)throw new IOException("Project exceeds 256 MB");out.write(buffer,0,count);}
                }
            }
            EpubMerger.merge(List.of(new EpubMerger.SourceItem(root)),output,
                new EpubMerger.MergeConfig(settings.metadata(),settings.cover(),settings.options(),true));
        } finally {BookFiles.deleteTree(root);}
    }
}
