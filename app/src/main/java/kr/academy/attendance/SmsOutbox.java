package kr.academy.attendance;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable send journal. Never silently retry a failed or uncertain transmission. */
public final class SmsOutbox {
    private static final Object LOCK = new Object();
    private SmsOutbox() {}
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getFilesDir(), "sms-outbox-v1.json")); }
    private static JSONArray read(Context context) throws Exception {
        AtomicFile f = file(context);
        try { return new JSONArray(new String(f.readFully(), StandardCharsets.UTF_8)); }
        catch (FileNotFoundException e) {
            if (f.getBaseFile().exists() || new File(f.getBaseFile()+".bak").exists()) throw e;
            return new JSONArray();
        }
    }
    private static void write(Context context, JSONArray jobs) throws Exception {
        AtomicFile f=file(context); FileOutputStream out=null;
        try { out=f.startWrite();out.write(jobs.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out); }
        catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    public static String today() { return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date()); }
    public static boolean capable(Context c) { return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) || c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY); }
    public static boolean simReady() { int id=SubscriptionManager.getDefaultSmsSubscriptionId();return id>=0 && id!=Integer.MAX_VALUE; }
    private static boolean contains(JSONArray array,String value) {for(int i=0;i<array.length();i++)if(value.equals(array.optString(i)))return true;return false;}
    private static JSONObject student(JSONObject state,String id) throws Exception {
        JSONArray students=state.getJSONArray("students");for(int i=0;i<students.length();i++){JSONObject s=students.getJSONObject(i);if(id.equals(s.getString("id")))return s;}return null;
    }
    private static JSONObject record(JSONObject state,String id,String date) throws Exception {
        JSONArray rows=state.getJSONArray("records");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(id.equals(r.getString("studentId"))&&date.equals(r.getString("date")))return r;}return null;
    }
    private static String message(JSONObject state,JSONObject r) throws Exception {
        JSONObject settings=state.getJSONObject("settings");String time=r.optString("time");
        if(time.isEmpty())time=new SimpleDateFormat("HH:mm",Locale.US).format(Date.from(Instant.parse(r.getString("updatedAt"))));
        Map<String,String> v=new HashMap<>();v.put("학원명",settings.getString("academy"));v.put("학생이름",r.getString("studentName"));v.put("반이름",r.getString("className"));v.put("날짜",r.getString("date"));v.put("시간",time);v.put("선생님",settings.optString("teacher"));v.put("메모",r.optString("note"));
        String status=r.getString("status");v.put("상태","present".equals(status)?"출석":"late".equals(status)?"지각":"absent".equals(status)?"결석":"공결");
        return SmsRules.render(settings.getJSONObject("sms").getString("template"),v);
    }
    public static JSONObject send(Context c,JSONObject state,JSONArray changes) throws Exception {
        JSONObject config=state.getJSONObject("settings").getJSONObject("sms");
        if(!config.optBoolean("enabled"))return new JSONObject().put("queued",0).put("skipped",changes.length()).put("failed",0);
        int queued=0,skipped=0,failed=0;
        for(int i=0;i<changes.length();i++){
            JSONObject change=changes.getJSONObject(i);String id=change.getString("studentId"),date=change.getString("date"),status=change.getString("status");
            JSONObject s=student(state,id),r=record(state,id,date);
            if(!today().equals(date)||s==null||!s.optBoolean("active")||r==null||!status.equals(r.getString("status"))||!change.getString("updatedAt").equals(r.getString("updatedAt"))||!contains(config.getJSONArray("statuses"),status)){skipped++;continue;}
            Set<String> seen=new HashSet<>();JSONArray guardians=s.getJSONArray("guardians");
            for(int j=0;j<guardians.length();j++){
                JSONObject g=guardians.getJSONObject(j);String phone=SmsRules.phone(g.optString("phone"));
                if(!g.optBoolean("notify")||phone.isEmpty()||!seen.add(phone))continue;
                JSONObject job=new JSONObject().put("id",UUID.randomUUID().toString()).put("key",SmsRules.key(id,date,status,phone)).put("studentId",id).put("studentName",r.getString("studentName")).put("date",date).put("attendanceStatus",status).put("phone",phone).put("guardianName",g.getString("name")).put("message",message(state,r)).put("createdAt",System.currentTimeMillis()).put("state","sending").put("error","").put("results",new JSONObject()).put("parts",0);
                synchronized(LOCK){
                    JSONArray jobs=read(c);boolean duplicate=false;for(int k=0;k<jobs.length();k++)if(job.getString("key").equals(jobs.getJSONObject(k).getString("key"))){duplicate=true;break;}
                    if(duplicate){skipped++;continue;}
                    JSONArray recent=new JSONArray();long cutoff=System.currentTimeMillis()-90L*24*60*60*1000;
                    for(int k=0;k<jobs.length();k++){JSONObject old=jobs.getJSONObject(k);if(old.optLong("createdAt")>=cutoff || today().equals(old.optString("date")))recent.put(old);}
                    if(recent.length()>=100000)throw new Exception("문자 기록 저장 한도를 초과했어요.");
                    recent.put(job);write(c,recent); // Journal must be durable BEFORE talking to the modem.
                }
                if(transmit(c,job))queued++;else failed++;
            }
        }
        return new JSONObject().put("queued",queued).put("skipped",skipped).put("failed",failed);
    }
    private static boolean transmit(Context c,JSONObject job) throws Exception {
        String error=null;SmsManager manager=null;ArrayList<String> parts=null;
        if(!capable(c))error="이 기기는 SMS 발송을 지원하지 않아요.";
        else if(c.checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)error="문자 권한이 없어요. 설정에서 권한을 허용해 주세요.";
        else if(!simReady())error="휴대폰 설정에서 기본 SMS용 SIM을 선택해 주세요.";
        else if(job.getString("message").trim().isEmpty()||job.getString("message").length()>1000)error="문자 내용이 비어 있거나 너무 길어요.";
        else {
            manager=SmsManager.getSmsManagerForSubscriptionId(SubscriptionManager.getDefaultSmsSubscriptionId());
            parts=manager.divideMessage(job.getString("message"));
            if(parts.size()>10)error="문구가 너무 길어요. 10개 이하의 SMS 분량으로 줄여 주세요.";
        }
        if(error!=null){fail(c,job.getString("id"),error);return false;}
        final int count=parts.size();
        synchronized(LOCK){JSONArray jobs=read(c);for(int i=0;i<jobs.length();i++)if(job.getString("id").equals(jobs.getJSONObject(i).getString("id")))jobs.getJSONObject(i).put("parts",count);write(c,jobs);}
        ArrayList<PendingIntent> sent=new ArrayList<>();
        for(int i=0;i<count;i++){
            Intent intent=new Intent(c,SmsResultReceiver.class).setAction(c.getPackageName()+".SMS_SENT").setData(Uri.parse("oneul://sms/"+job.getString("id")+"/"+i)).putExtra("jobId",job.getString("id")).putExtra("part",i);
            sent.add(PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        }
        try { manager.sendMultipartTextMessage(job.getString("phone"),null,parts,sent,null);return true; }
        catch(SecurityException e){fail(c,job.getString("id"),"Android가 문자 발송을 허용하지 않았어요. 앱 권한을 확인해 주세요.");return false;}
        catch(Exception e){setState(c,job.getString("id"),"unknown","발송 요청 결과를 확인할 수 없어요. 수신 여부를 확인한 뒤 다시 시도해 주세요.");return false;}
    }
    private static void fail(Context c,String id,String error) throws Exception {setState(c,id,"failed",error);}
    private static void setState(Context c,String id,String state,String error) throws Exception {synchronized(LOCK){JSONArray jobs=read(c);for(int i=0;i<jobs.length();i++){JSONObject j=jobs.getJSONObject(i);if(id.equals(j.getString("id")))j.put("state",state).put("error",error);}write(c,jobs);}}
    public static void result(Context c,String id,int part,int code) throws Exception {
        synchronized(LOCK){JSONArray jobs=read(c);for(int i=0;i<jobs.length();i++){
            JSONObject j=jobs.getJSONObject(i);if(!id.equals(j.getString("id"))||part<0||part>=j.optInt("parts"))continue;
            JSONObject results=j.getJSONObject("results");results.put(String.valueOf(part),code);
            int failureCode=Activity.RESULT_OK;java.util.Iterator<String> keys=results.keys();while(keys.hasNext()){int result=results.getInt(keys.next());if(result!=Activity.RESULT_OK){failureCode=result;break;}}
            if(failureCode!=Activity.RESULT_OK)j.put("state","failed").put("error",reason(failureCode));
            else if(results.length()==j.getInt("parts"))j.put("state","sent").put("error","");
        }write(c,jobs);}
    }
    private static String reason(int code){
        if(code==SmsManager.RESULT_ERROR_NO_SERVICE)return "통신 서비스에 연결되어 있지 않아요.";
        if(code==SmsManager.RESULT_ERROR_RADIO_OFF)return "비행기 모드 또는 휴대폰 통신 상태를 확인해 주세요.";
        if(code==SmsManager.RESULT_ERROR_LIMIT_EXCEEDED)return "휴대폰의 문자 발송 한도에 도달했어요. 잠시 후 확인해 주세요.";
        if(code==SmsManager.RESULT_ERROR_NULL_PDU)return "문자 내용을 전송 데이터로 만들지 못했어요.";
        return "통신사가 발송 실패를 반환했어요. 일부 분할 문자는 도착했을 수 있어요. (코드 "+code+")";
    }
    public static JSONObject logs(Context c) throws Exception {
        synchronized(LOCK){JSONArray jobs=read(c),recent=new JSONArray();for(int i=jobs.length()-1;i>=Math.max(0,jobs.length()-200);i--){JSONObject j=new JSONObject(jobs.getJSONObject(i).toString());if("sending".equals(j.optString("state"))&&System.currentTimeMillis()-j.optLong("createdAt")>120000)j.put("state","unknown").put("error","발송 결과 응답이 없어요. 수신 여부를 확인해 주세요.");recent.put(j);}return new JSONObject().put("ok",true).put("jobs",recent);}
    }
    public static void retry(Context c,JSONObject state,String oldId) throws Exception {
        JSONObject retry=null;
        synchronized(LOCK){JSONArray jobs=read(c);JSONObject old=null;for(int i=0;i<jobs.length();i++)if(oldId.equals(jobs.getJSONObject(i).getString("id")))old=jobs.getJSONObject(i);
            if(old==null||!("failed".equals(old.optString("state"))||"unknown".equals(old.optString("state"))||("sending".equals(old.optString("state"))&&System.currentTimeMillis()-old.optLong("createdAt")>120000)))throw new Exception("다시 보낼 수 있는 기록이 아니에요.");
            if(!today().equals(old.getString("date")))throw new Exception("지난 날짜의 문자는 다시 보내지 않아요.");
            JSONObject config=state.getJSONObject("settings").getJSONObject("sms");
            if(!config.optBoolean("enabled"))throw new Exception("문자 자동 발송을 먼저 켜 주세요.");
            if(!contains(config.getJSONArray("statuses"),old.getString("attendanceStatus")))throw new Exception("문자를 보내도록 설정된 출결 상태가 아니에요.");
            JSONObject s=student(state,old.getString("studentId")),r=record(state,old.getString("studentId"),old.getString("date"));boolean allowed=false;
            if(s!=null&&s.optBoolean("active")&&r!=null&&old.getString("attendanceStatus").equals(r.getString("status"))){JSONArray gs=s.getJSONArray("guardians");for(int i=0;i<gs.length();i++)if(gs.getJSONObject(i).optBoolean("notify")&&old.getString("phone").equals(SmsRules.phone(gs.getJSONObject(i).optString("phone"))))allowed=true;}
            if(!allowed)throw new Exception("학생 상태나 수신 연락처가 바뀌었어요. 현재 등록 정보를 확인해 주세요.");
            for(int i=0;i<jobs.length();i++){JSONObject j=jobs.getJSONObject(i);if(old.getString("key").equals(j.getString("key"))&&!oldId.equals(j.getString("id"))&&j.optLong("createdAt")>=old.optLong("createdAt"))throw new Exception("이미 재전송한 기록이에요. 최신 결과를 확인해 주세요.");}
            retry=new JSONObject(old.toString()).put("id",UUID.randomUUID().toString()).put("createdAt",System.currentTimeMillis()).put("state","sending").put("error","").put("parts",0).put("results",new JSONObject());jobs.put(retry);write(c,jobs);
        }
        transmit(c,retry);
    }
}
