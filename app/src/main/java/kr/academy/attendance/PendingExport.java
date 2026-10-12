package kr.academy.attendance;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Keep document-picker payloads in private cache, not Activity fields or a Bundle. */
public final class PendingExport {
    public static final int MAX_BYTES=25*1024*1024;
    private final File directory;
    public PendingExport(File directory){this.directory=directory;}
    private File file(String token) throws IOException {
        if(token==null||!token.matches("attendance-export-[a-f0-9-]{36}\\.tmp"))throw new IOException("Invalid export token");
        return new File(directory,token);
    }
    public String prepare(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("Export is too large");
        String token="attendance-export-"+UUID.randomUUID()+".tmp";
        try{AtomicWrite.write(new AtomicFile(file(token)),bytes);return token;}
        catch(IOException|RuntimeException e){discard(token);throw e;}
    }
    public InputStream open(String token) throws IOException {
        File path=file(token);
        if(path.length()>MAX_BYTES)throw new IOException("Export is too large");
        return new FileInputStream(path);
    }
    public void discard(String token){
        try{File path=file(token);path.delete();new File(path+".new").delete();new File(path+".bak").delete();}catch(IOException ignored){}
    }
}
