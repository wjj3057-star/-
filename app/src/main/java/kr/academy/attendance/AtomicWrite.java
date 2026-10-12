package kr.academy.attendance;

import android.util.AtomicFile;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** A write is acknowledged only after sync and verification of the committed bytes. */
public final class AtomicWrite {
    private AtomicWrite() {}
    public static void write(AtomicFile file,byte[] bytes) throws IOException {
        FileOutputStream out=null;
        try {
            out=file.startWrite();
            out.write(bytes);
            // AtomicFile.finishWrite logs some sync/rename failures instead of throwing.
            out.getFD().sync();
            file.finishWrite(out);out=null;
            try(InputStream in=file.openRead()){
                byte[] buffer=new byte[8192];int count,offset=0;
                while((count=in.read(buffer))!=-1){
                    if(offset+count>bytes.length)throw new IOException("Saved file verification failed");
                    for(int i=0;i<count;i++)if(buffer[i]!=bytes[offset+i])throw new IOException("Saved file verification failed");
                    offset+=count;
                }
                if(offset!=bytes.length)throw new IOException("Saved file verification failed");
            }
        }catch(IOException|RuntimeException error){
            if(out!=null)file.failWrite(out);
            throw error;
        }
    }
}
