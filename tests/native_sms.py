"""Exercise production SMS code with JVM Android fakes; no contacts or SMS are used."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
"android/Manifest.java": """package android; public class Manifest { public static class permission { public static final String SEND_SMS="sms"; } }""",
"android/app/Activity.java": """package android.app; public class Activity { public static final int RESULT_OK=-1; }""",
"android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager { public static final String FEATURE_TELEPHONY="phone",FEATURE_TELEPHONY_MESSAGING="sms"; public static final int PERMISSION_GRANTED=0; public boolean hasSystemFeature(String s){return true;} }""",
"android/content/Context.java": """package android.content;
import java.io.File; import android.content.pm.PackageManager;
public class Context { public int permission=0; private final File dir; public Context(File d){dir=d;} public File getFilesDir(){return dir;} public Context getApplicationContext(){return this;} public String getPackageName(){return "test";} public PackageManager getPackageManager(){return new PackageManager();} public int checkSelfPermission(String p){return permission;} }""",
"android/net/Uri.java": """package android.net; public class Uri { public static Uri parse(String s){return new Uri();} }""",
"android/content/Intent.java": """package android.content;
import java.util.HashMap; import android.net.Uri;
public class Intent { private String action; private final HashMap<String,Object> extra=new HashMap<>(); public Intent(){} public Intent(Context c,Class<?> k){} public Intent setAction(String a){action=a;return this;} public String getAction(){return action;} public Intent setData(Uri u){return this;} public Intent putExtra(String k,String v){extra.put(k,v);return this;} public Intent putExtra(String k,int v){extra.put(k,v);return this;} public String getStringExtra(String k){return (String)extra.get(k);} public int getIntExtra(String k,int fallback){return extra.containsKey(k)?(Integer)extra.get(k):fallback;} }""",
"android/content/BroadcastReceiver.java": """package android.content;
import java.util.concurrent.CountDownLatch;
public abstract class BroadcastReceiver {
 public static class PendingResult { int code; public final CountDownLatch done=new CountDownLatch(1); public void finish(){done.countDown();} }
 private PendingResult pending=new PendingResult(); public PendingResult async;
 public void setResultCode(int c){pending.code=c;} public int getResultCode(){return pending==null?0:pending.code;}
 public PendingResult goAsync(){async=pending;pending=null;return async;}
 public abstract void onReceive(Context c,Intent i);
}""",
"android/app/PendingIntent.java": """package android.app;
import android.content.*;
public class PendingIntent { public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2; public final Intent intent; private PendingIntent(Intent i){intent=i;} public static PendingIntent getBroadcast(Context c,int r,Intent i,int f){return new PendingIntent(i);} }""",
"android/telephony/SubscriptionManager.java": """package android.telephony; public class SubscriptionManager { public static int getDefaultSmsSubscriptionId(){return 1;} }""",
"android/telephony/SmsManager.java": """package android.telephony;
import java.util.*; import android.app.PendingIntent;
public class SmsManager {
 public static final int RESULT_ERROR_NO_SERVICE=4,RESULT_ERROR_RADIO_OFF=2,RESULT_ERROR_LIMIT_EXCEEDED=5,RESULT_ERROR_NULL_PDU=3;
 public static final List<ArrayList<PendingIntent>> sent=new ArrayList<>(); public static Runnable onSend; public static int partCount=1,preparationFailures=0; public static boolean failAfterHandoff=false;
 public static SmsManager getSmsManagerForSubscriptionId(int i){return new SmsManager();}
 public ArrayList<String> divideMessage(String m){if(preparationFailures>0){preparationFailures--;throw new IllegalStateException("SIM unavailable");}ArrayList<String> p=new ArrayList<>();for(int i=0;i<partCount;i++)p.add(m);return p;}
 public void sendMultipartTextMessage(String p,String sc,ArrayList<String> text,ArrayList<PendingIntent> result,ArrayList<PendingIntent> delivery){sent.add(result);if(failAfterHandoff)throw new IllegalStateException("unknown modem result");if(onSend!=null)onSend.run();}
}""",
"android/util/AtomicFile.java": """package android.util;
import java.io.*;
public class AtomicFile {
 private final File file; public static Runnable afterWrite; public static boolean failWrites=false; public AtomicFile(File f){file=f;} public File getBaseFile(){return file;}
 public InputStream openRead() throws IOException {return new FileInputStream(file);}
 public byte[] readFully() throws IOException {try(InputStream in=new FileInputStream(file)){return in.readAllBytes();}}
 public FileOutputStream startWrite() throws IOException {if(failWrites)throw new IOException("Disk full");file.getParentFile().mkdirs();return new FileOutputStream(file);}
 public void finishWrite(FileOutputStream out) throws IOException {out.close();if(afterWrite!=null)afterWrite.run();}
 public void failWrite(FileOutputStream out) {try{out.close();}catch(IOException ignored){}}
}""",
"NativeSmsTest.java": r"""import kr.academy.attendance.*;
import android.content.*; import android.telephony.SmsManager; import android.util.AtomicFile;
import org.json.*; import java.nio.file.*; import java.util.concurrent.TimeUnit;
public class NativeSmsTest {
 static class Reader implements SmsOutbox.StateReader {
  JSONObject state; boolean valid=true; final Object monitor=new Object();
  Reader(JSONObject s){state=s;} public Object lock(){return monitor;} public JSONObject read(){return valid?state:null;}
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static JSONObject fixture(int guardians){
  JSONArray gs=new JSONArray();for(int i=0;i<guardians;i++)gs.put(new JSONObject().put("name","Parent "+i).put("phone","0101234567"+i).put("notify",true));
  return new JSONObject().put("settings",new JSONObject().put("academy","Academy").put("teacher","T").put("sms",new JSONObject().put("enabled",true).put("statuses",new JSONArray().put("present")).put("template","{학생이름} {상태}")))
   .put("students",new JSONArray().put(new JSONObject().put("id","s").put("active",true).put("guardians",gs)))
   .put("records",new JSONArray().put(new JSONObject().put("studentId","s").put("studentName","Student").put("className","All").put("date",SmsOutbox.today()).put("status","present").put("note","").put("time","16:00").put("updatedAt","2026-10-10T07:00:00Z")));
 }
 static JSONArray changes(){return new JSONArray().put(new JSONObject().put("studentId","s").put("date",SmsOutbox.today()).put("status","present"));}
 static Context context(Path root,String name)throws Exception{SmsManager.sent.clear();SmsManager.onSend=null;SmsManager.partCount=1;SmsManager.preparationFailures=0;SmsManager.failAfterHandoff=false;AtomicFile.afterWrite=null;AtomicFile.failWrites=false;return new Context(Files.createDirectories(root.resolve(name)).toFile());}
 static JSONObject latest(Context c)throws Exception{return SmsOutbox.logs(c).getJSONArray("jobs").getJSONObject(0);}
 static void callback(Context c,int message,int part,int code)throws Exception{
  SmsResultReceiver receiver=new SmsResultReceiver();receiver.setResultCode(code);
  receiver.onReceive(c,SmsManager.sent.get(message).get(part).intent);
  check(receiver.async!=null&&receiver.async.done.await(5,TimeUnit.SECONDS),"async callback finished");
 }
 public static void main(String[] args)throws Exception{
  Path root=Paths.get(args[0]);
  Context c=context(root,"success");Reader reader=new Reader(fixture(1));SmsManager.partCount=2;
  SmsOutbox.send(c,reader,changes());callback(c,0,0,-1);check(latest(c).getString("state").equals("sending"),"wait for all parts");
  callback(c,0,1,-1);check(latest(c).getString("state").equals("sent"),"success code must survive goAsync");
  c=context(root,"failure");reader=new Reader(fixture(1));SmsOutbox.send(c,reader,changes());callback(c,0,0,4);
  check(latest(c).getString("state").equals("failed"),"real failure preserved");
  c=context(root,"zero");reader=new Reader(fixture(1));SmsOutbox.send(c,reader,changes());callback(c,0,0,0);
  check(latest(c).getString("state").equals("failed"),"new real zero result not relabeled as legacy");

  c=context(root,"partial");reader=new Reader(fixture(1));SmsManager.partCount=3;
  SmsOutbox.send(c,reader,changes());callback(c,0,2,4);
  check(latest(c).getString("state").equals("sending"),"failed part must wait for remaining callbacks");
  try{SmsOutbox.retry(c,reader,latest(c).getString("id"));throw new AssertionError("early retry accepted");}catch(Exception expected){}
  callback(c,0,0,-1);callback(c,0,1,-1);
  check(latest(c).getString("state").equals("partial"),"mixed results reported as partial failure");
  check(latest(c).getInt("sentParts")==2&&latest(c).getInt("failedParts")==1,"part counts");
  callback(c,0,1,-1);check(latest(c).getInt("sentParts")==2,"duplicate callback does not double count");
  SmsOutbox.retry(c,reader,latest(c).getString("id"));check(SmsManager.sent.size()==2,"explicit retry accepted after all callbacks");
  c=context(root,"late");reader=new Reader(fixture(1));SmsManager.partCount=2;SmsOutbox.send(c,reader,changes());callback(c,0,1,-1);
  Path lateFile=c.getFilesDir().toPath().resolve("sms-outbox-v1.json");JSONArray lateJobs=new JSONArray(Files.readString(lateFile));
  lateJobs.getJSONObject(0).put("createdAt",System.currentTimeMillis()-180000);Files.writeString(lateFile,lateJobs.toString());
  check(latest(c).getString("state").equals("unknown"),"timeout is uncertain, not a known failure");
  callback(c,0,0,-1);check(latest(c).getString("state").equals("sent"),"late success resolves uncertainty");
  check(SmsManager.sent.size()==1,"no automatic resend");
  c=context(root,"retry-permission");reader=new Reader(fixture(1));SmsOutbox.send(c,reader,changes());callback(c,0,0,4);
  String failedId=latest(c).getString("id");c.permission=-1;
  try{SmsOutbox.retry(c,reader,failedId);throw new AssertionError("failed retry reported as accepted");}catch(Exception expected){check(expected.getMessage().contains("완료하지 못"),"retry error visible");}
  check(SmsManager.sent.size()==1&&latest(c).getString("state").equals("failed"),"no modem send after revoked permission");

  for(String mode:new String[]{"disable","epoch","optout","status","archive","note"}){
   c=context(root,mode);final Reader current=new Reader(fixture(2));
   SmsManager.onSend=()->{
    JSONObject next=new JSONObject(current.state.toString());
    if(mode.equals("disable"))next.getJSONObject("settings").getJSONObject("sms").put("enabled",false);
    if(mode.equals("epoch"))current.valid=false; // off then on invalidated this request
    if(mode.equals("optout"))next.getJSONArray("students").getJSONObject(0).getJSONArray("guardians").getJSONObject(1).put("notify",false);
    if(mode.equals("status"))next.getJSONArray("records").getJSONObject(0).put("status","absent");
    if(mode.equals("archive"))next.getJSONArray("students").getJSONObject(0).put("active",false);
    if(mode.equals("note"))next.getJSONArray("records").getJSONObject(0).put("note","edited");
    current.state=next;
   };
   SmsOutbox.send(c,current,changes());check(SmsManager.sent.size()==(mode.equals("note")?2:1),mode+" dispatch count");
   if(!mode.equals("note"))check(latest(c).getString("state").equals("cancelled"),mode+" cancellation recorded");
   if(mode.equals("disable")){
    SmsManager.onSend=null;current.state=fixture(2);SmsOutbox.send(c,new Reader(current.state),changes());
    check(SmsManager.sent.size()==2,"cancelled entry must not suppress a later eligible send");
   }
  }
  c=context(root,"lastcheck");final Reader finalReader=new Reader(fixture(1));
  AtomicFile.afterWrite=()->finalReader.valid=false;
  SmsOutbox.send(c,finalReader,changes());check(SmsManager.sent.isEmpty(),"recheck after journaling, before modem");
  AtomicFile.afterWrite=null;

  c=context(root,"legacy");reader=new Reader(fixture(1));SmsOutbox.send(c,reader,changes());callback(c,0,0,0);
  Path journal=c.getFilesDir().toPath().resolve("sms-outbox-v1.json");
  JSONArray jobs=new JSONArray(Files.readString(journal));jobs.getJSONObject(0).remove("resultVersion");Files.writeString(journal,jobs.toString());
  check(latest(c).getString("state").equals("unknown"),"legacy detached result is uncertain, not known failure");
  check(latest(c).getString("error").contains("이전 버전"),"legacy result explains uncertainty");
  c=context(root,"clock-order");reader=new Reader(fixture(1));SmsOutbox.send(c,reader,changes());callback(c,0,0,4);
  Path clockFile=c.getFilesDir().toPath().resolve("sms-outbox-v1.json");JSONArray clockJobs=new JSONArray(Files.readString(clockFile));
  clockJobs.getJSONObject(0).put("createdAt",System.currentTimeMillis()+3600000);Files.writeString(clockFile,clockJobs.toString());
  SmsOutbox.retry(c,reader,latest(c).getString("id"));callback(c,1,0,4);
  SmsOutbox.retry(c,reader,latest(c).getString("id"));check(SmsManager.sent.size()==3,"latest retry uses journal order, not wall clock");

  c=context(root,"late-original-success");reader=new Reader(fixture(1));SmsOutbox.send(c,reader,changes());
  Path successFile=c.getFilesDir().toPath().resolve("sms-outbox-v1.json");JSONArray successJobs=new JSONArray(Files.readString(successFile));
  successJobs.getJSONObject(0).put("createdAt",System.currentTimeMillis()-180000);Files.writeString(successFile,successJobs.toString());
  SmsOutbox.retry(c,reader,latest(c).getString("id"));callback(c,1,0,4);callback(c,0,0,-1);
  check(!latest(c).optBoolean("canRetry",true),"a confirmed earlier success blocks a duplicate retry");
  try{SmsOutbox.retry(c,reader,latest(c).getString("id"));throw new AssertionError("confirmed SMS retransmitted");}catch(Exception expected){}
  check(SmsManager.sent.size()==2,"late success cannot cause a third send");
  c=context(root,"prepare-failure");reader=new Reader(fixture(2));SmsManager.preparationFailures=1;
  JSONObject preparationResult=SmsOutbox.send(c,reader,changes());
  check(preparationResult.getInt("failed")==1&&preparationResult.getInt("queued")==1,"preflight exception does not abort remaining recipients");
  check(SmsOutbox.logs(c).getJSONArray("jobs").getJSONObject(1).getString("state").equals("failed"),"preflight failure is not left pending");
  c=context(root,"empty-parts");reader=new Reader(fixture(1));SmsManager.partCount=0;SmsOutbox.send(c,reader,changes());
  check(latest(c).getString("state").equals("failed")&&SmsManager.sent.isEmpty(),"empty division never reaches modem");
  c=context(root,"handoff-unknown");reader=new Reader(fixture(1));SmsManager.failAfterHandoff=true;SmsOutbox.send(c,reader,changes());
  check(latest(c).getString("state").equals("unknown"),"a modem handoff exception is uncertain, not definite failure");
  System.out.println("Native SMS passed: async success/failure, out-of-order multipart/partial, duplicate/late callbacks, timeout, retry failure, legacy logs, bulk cancellation, opt-out and dedup.");
 }
}"""
}

SOURCES["StoreTest.java"] = r"""import kr.academy.attendance.*;
import android.content.Context;import android.util.AtomicFile;import org.json.*;import java.nio.file.*;
public class StoreTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static JSONObject data(boolean enabled){return new JSONObject().put("schema",2).put("classes",new JSONArray()).put("students",new JSONArray()).put("records",new JSONArray()).put("settings",new JSONObject().put("theme","dark").put("sms",new JSONObject().put("enabled",enabled)));}
 static boolean save(AttendanceStore store,JSONObject data){return new JSONObject(store.saveState(data.toString())).getBoolean("ok");}
 public static void main(String[] args)throws Exception{
  Path root=Paths.get(args[0]);Files.createDirectories(root);Context context=new Context(root.toFile());AttendanceStore store=AttendanceStore.get(context);
  check(new JSONObject(store.readState()).getBoolean("ok"),"first launch is empty, not corrupt");check(save(store,data(true)),"initial save");
  SmsOutbox.StateReader pending=store.smsStateReader();
  AttendanceStore recreated=AttendanceStore.get(new Context(root.toFile()));
  check(store==recreated,"Activities share one store and lock");check(save(recreated,data(false)),"disable after recreation");check(save(recreated,data(true)),"re-enable");check(pending.read()==null,"pending requests remain cancelled across Activity recreation");
  check(recreated.smsStateReader().read().getJSONObject("settings").getJSONObject("sms").getBoolean("enabled"),"new requests see current settings");
  Path file=root.resolve("attendance-v1.json");String previous=Files.readString(file);
  JSONObject invalid=data(true);invalid.getJSONObject("settings").remove("sms");check(!save(store,invalid),"missing SMS settings rejected before write");check(Files.readString(file).equals(previous),"invalid save never overwrites durable state");
  AtomicFile.failWrites=true;check(!save(store,data(false)),"write error reported");AtomicFile.failWrites=false;
  check(Files.readString(file).equals(previous)&&store.smsStateReader().read().getJSONObject("settings").getJSONObject("sms").getBoolean("enabled"),"failed save retains file and active preferences");
  check(store.theme().equals("dark"),"saved theme available before WebView startup");
  Path broken=root.resolve("broken");Files.createDirectories(broken);Files.writeString(broken.resolve("attendance-v1.json"),"");
  check(!new JSONObject(AttendanceStore.get(new Context(broken.toFile())).readState()).getBoolean("ok"),"empty existing file is not a fresh install");
  System.out.println("Store passed: first run, durable save, recreation sharing, consent cancellation, invalid settings, write failure rollback and corrupt-file protection.");
 }
}"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json-jar", required=True, type=Path)
    args = parser.parse_args()
    jar = args.json_jar.resolve()
    if not jar.is_file():
        parser.error("org.json test dependency is missing")
    with tempfile.TemporaryDirectory(prefix="attendance-sms-") as temp:
        base = Path(temp)
        files = []
        for name, content in SOURCES.items():
            target = base / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            files.append(str(target))
        production = ROOT / "app/src/main/java/kr/academy/attendance"
        files += [str(production / name) for name in ("SmsOutbox.java", "SmsRules.java", "SmsResultReceiver.java", "AttendanceStore.java")]
        classes = base / "classes"
        classes.mkdir()
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes), *files], check=True)
        import os
        subprocess.run(["java", "-cp", os.pathsep.join((str(classes), str(jar))), "NativeSmsTest", str(base / "data")], check=True)
        subprocess.run(["java", "-cp", os.pathsep.join((str(classes), str(jar))), "StoreTest", str(base / "store")], check=True)

if __name__ == "__main__":
    main()
