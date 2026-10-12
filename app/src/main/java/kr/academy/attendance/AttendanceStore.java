package kr.academy.attendance;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** One store/lock/consent generation per app process, shared across Activity recreation. */
public final class AttendanceStore {
    public static final int MAX_BYTES=24*1024*1024;
    private static AttendanceStore instance;
    private final AtomicFile file;
    private JSONObject snapshot;
    private long smsEpoch;
    private AttendanceStore(File path){file=new AtomicFile(path);}
    public static synchronized AttendanceStore get(Context context){
        File path=new File(context.getFilesDir(),"attendance-v1.json");
        if(instance==null||!instance.file.getBaseFile().equals(path))instance=new AttendanceStore(path);
        return instance;
    }
    private static String envelope(boolean ok,String data,String error){
        try{return new JSONObject().put("ok",ok).put("data",data).put("error",error).toString();}
        catch(Exception ignored){return "{\"ok\":false}";}
    }
    private String readFile() throws Exception {
        try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1){
                if(out.size()+count>MAX_BYTES)throw new IllegalArgumentException("State too large");
                out.write(buffer,0,count);
            }
            if(out.size()==0)throw new IllegalArgumentException("Empty saved state");
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    public synchronized String readState(){
        try{return envelope(true,readFile(),"");}
        catch(FileNotFoundException e){
            boolean exists=file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists();
            return envelope(!exists,"",exists?"저장된 데이터를 읽지 못했어요.":"");
        }catch(Exception e){return envelope(false,"","저장된 데이터를 읽지 못했어요.");}
    }
    public synchronized String saveState(String raw){
        try{
            if(raw==null)return envelope(false,"","저장할 데이터가 없어요.");
            byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES)return envelope(false,"","저장 데이터가 24MB 한도를 넘었어요. 현재 데이터는 유지됩니다.");
            JSONObject next=new JSONObject(raw);
            if(next.getInt("schema")!=2||next.getJSONArray("students").length()>5000||next.getJSONArray("classes").length()>201||next.getJSONArray("records").length()>100000)
                return envelope(false,"","학생 또는 출석 기록의 저장 한도를 확인해 주세요.");
            // Read every required field before committing. Invalid settings must not
            // overwrite valid data and only then return an error to the WebView.
            JSONObject settings=next.getJSONObject("settings");
            boolean enabled=settings.getJSONObject("sms").getBoolean("enabled");
            AtomicWrite.write(file,bytes);
            snapshot=next;
            if(!enabled)smsEpoch++;
            return envelope(true,"","");
        }catch(Exception e){return envelope(false,"","저장을 완료하지 못했어요. 기기의 저장 공간을 확인한 뒤 다시 시도해 주세요.");}
    }
    private synchronized JSONObject snapshot() throws Exception {
        if(snapshot==null)snapshot=new JSONObject(readFile());
        return snapshot;
    }
    public synchronized String theme(){
        try{return snapshot().getJSONObject("settings").optString("theme","system");}
        catch(Exception ignored){return "system";}
    }
    public synchronized SmsOutbox.StateReader smsStateReader(){
        final long epoch=smsEpoch;
        return new SmsOutbox.StateReader(){
            public Object lock(){return AttendanceStore.this;}
            public JSONObject read() throws Exception {
                synchronized(AttendanceStore.this){return epoch==smsEpoch?snapshot():null;}
            }
        };
    }
}
