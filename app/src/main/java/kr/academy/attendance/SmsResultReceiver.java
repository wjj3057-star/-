package kr.academy.attendance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit immutable PendingIntents reach this receiver even after the screen closes. */
public final class SmsResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!(context.getPackageName()+".SMS_SENT").equals(intent.getAction()))return;
        String id=intent.getStringExtra("jobId");if(id==null)return;
        PendingResult pending=goAsync();final int result=getResultCode();
        new Thread(()->{try{SmsOutbox.result(context.getApplicationContext(),id,intent.getIntExtra("part",-1),result);}catch(Exception ignored){}finally{pending.finish();}},"sms-result").start();
    }
}
