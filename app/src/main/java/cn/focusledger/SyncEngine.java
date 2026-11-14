package cn.focusledger;

import android.content.Context;
import android.app.job.*;
import android.content.ComponentName;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.net.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class SyncEngine {
    private static final ExecutorService executor=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean queued=new AtomicBoolean();
    static final int JOB=31081;
    static volatile long appliedVersion;
    static void request(Context context){
        Context app=context.getApplicationContext();if(!SyncIdentity.connected(app))return;
        schedule(app);
        if(queued.compareAndSet(false,true))executor.execute(()->{try{sync(app);}catch(Exception e){SyncIdentity.status(app,"同步未完成，将自动重试："+safe(e));}finally{queued.set(false);}});
    }
    static String safe(Exception e){return e instanceof SecurityException?"连接授权已失效，请重新连接":e instanceof IOException?"网络暂时不可用":e instanceof JSONException?"服务响应无效":"同步失败，请稍后重试";}
    static void schedule(Context c){JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(js.getPendingJob(JOB)==null)js.schedule(new JobInfo.Builder(JOB,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}
    static void runJob(Context c,Runnable done){executor.execute(()->{try{if(SyncIdentity.connected(c))sync(c);}catch(Exception e){SyncIdentity.status(c,"等待重试："+safe(e));}finally{done.run();}});}
    static JSONObject post(Context c,JSONObject body)throws Exception{
        JSONObject identity=SyncIdentity.credentials(c);HttpURLConnection connection=(HttpURLConnection)new URL(SyncIdentity.ORIGIN+"/api/device/sync").openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer "+identity.getString("token"));connection.setRequestProperty("OAI-Sites-Authorization","Bearer "+identity.getString("gate"));
        byte[] bytes=body.toString().getBytes("UTF-8");connection.setFixedLengthStreamingMode(bytes.length);
        try{try(OutputStream out=connection.getOutputStream()){out.write(bytes);}int status=connection.getResponseCode();if(status==401||status==403)throw new SecurityException();if(status!=200)throw new IOException("Sync HTTP "+status);
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>1048576)throw new IOException("Response too large");}return new JSONObject(out.toString("UTF-8"));}
        }finally{connection.disconnect();}
    }
    private static JSONObject row(String kind,String id,JSONObject payload)throws Exception{String text=payload.toString();return new JSONObject().put("kind",kind).put("id",id).put("payload",text).put("hash",SyncData.hash(text));}
    static void sync(Context c)throws Exception{
        if(!SyncIdentity.connected(c))return;
        SyncIdentity.status(c,"正在同步…");upload(c);
        JSONArray commands=post(c,new JSONObject().put("action","poll")).getJSONArray("commands");
        LedgerStore store=TimerCoordinator.get(c).store();
        for(int i=0;i<commands.length();i++){
            if(!SyncIdentity.connected(c))return;
            JSONObject cmd=commands.getJSONObject(i),result;
            try{result=SyncData.apply(store,cmd);}catch(IllegalArgumentException|IllegalStateException e){result=new JSONObject().put("id",cmd.getString("id")).put("status","failed");}
            post(c,new JSONObject().put("action","receipt").put("id",result.getString("id")).put("status",result.getString("status")).put("result",result));
        }
        if(commands.length()>0){appliedVersion++;upload(c);}
        SyncIdentity.status(c,"同步完成 · "+java.time.LocalDateTime.now().withNano(0));
    }
    static void upload(Context c)throws Exception{
        TimerCoordinator timer=TimerCoordinator.get(c);LedgerStore store=timer.store();List<JSONObject> rows=new ArrayList<>();long sequence,watermark;String zone=ZoneId.systemDefault().getId();LocalDate today=LocalDate.now();
        synchronized(timer){synchronized(store){
            store.ensureToday();SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try{db.execSQL("UPDATE sync_meta SET sequence=sequence+1 WHERE id=1");try(Cursor cur=db.rawQuery("SELECT sequence FROM sync_meta WHERE id=1",null)){cur.moveToFirst();sequence=cur.getLong(0);}try(Cursor cur=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM sync_changes",null)){cur.moveToFirst();watermark=cur.getLong(0);}
                LedgerStore.DaySnapshot day=store.getDaySnapshot(today);
                for(LedgerStore.Task task:store.getAllTasks())rows.add(row("task",task.id,SyncData.task(task,day)));
                List<LocalDate> days=store.getRecordedDays();if(!days.contains(today))days.add(today);
                for(LedgerStore.DaySnapshot snapshot:timer.snapshotsForDays(days))rows.add(row("day",snapshot.day.toString(),SyncData.day(snapshot)));
                for(cn.focusledger.core.Session session:store.getSessions())rows.add(row("session",session.id,SyncData.session(session)));
                try(Cursor cur=db.rawQuery("SELECT id FROM sessions WHERE deleted=1",null)){while(cur.moveToNext()){String id=cur.getString(0);rows.add(row("session",id,new JSONObject().put("id",id).put("deleted",true)));}}
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }}
        post(c,new JSONObject().put("action","begin").put("sequence",sequence).put("day",today.toString()).put("zone",zone));
        JSONArray batch=new JSONArray();int size=0;
        for(JSONObject row:rows){String key=row.getString("kind")+":"+row.getString("id"),hash=row.getString("hash");boolean unchanged;
            synchronized(store){try(Cursor cur=store.getReadableDatabase().rawQuery("SELECT hash FROM sync_hashes WHERE key=?",new String[]{key})){unchanged=cur.moveToFirst()&&hash.equals(cur.getString(0));}}
            if(unchanged)continue;
            int len=row.toString().length();if(len>400000)throw new IOException("Record too large");
            if(batch.length()>=50||size+len>400000){post(c,new JSONObject().put("action","chunk").put("sequence",sequence).put("rows",batch));batch=new JSONArray();size=0;}
            batch.put(row);size+=len;
        }
        if(batch.length()>0)post(c,new JSONObject().put("action","chunk").put("sequence",sequence).put("rows",batch));
        post(c,new JSONObject().put("action","finish").put("sequence",sequence));
        synchronized(store){SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            for(JSONObject row:rows)db.execSQL("INSERT OR REPLACE INTO sync_hashes(key,hash) VALUES(?,?)",new Object[]{row.getString("kind")+":"+row.getString("id"),row.getString("hash")});
            db.delete("sync_changes","id<=?",new String[]{String.valueOf(watermark)});db.setTransactionSuccessful();
        }finally{db.endTransaction();}}
    }
    static void disconnect(Context c,Runnable done){
        SyncIdentity.disable(c);((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB);
        executor.execute(()->{boolean revoked=false;try{post(c,new JSONObject().put("action","revoke"));revoked=true;}catch(Exception ignored){}finally{
            SyncIdentity.clear(c);if(!revoked)SyncIdentity.status(c,"本机已断开。云端撤销未完成，请在服务首页撤销原设备授权。");
            synchronized(TimerCoordinator.get(c).store()){TimerCoordinator.get(c).store().getWritableDatabase().delete("sync_hashes",null,null);}done.run();
        }});
    }
}
