package cn.focusledger;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.util.*;
import org.json.*;
import cn.focusledger.core.Session;

final class SyncData {
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE sync_meta (id INTEGER PRIMARY KEY CHECK(id=1), sequence INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO sync_meta(id,sequence) VALUES(1,0)");
        db.execSQL("CREATE TABLE sync_receipts (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, result TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sync_hashes (key TEXT PRIMARY KEY NOT NULL, hash TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sync_changes (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, entity_id TEXT NOT NULL, UNIQUE(kind,entity_id))");
        for(String table:new String[]{"tasks","sessions","daily_snapshots","daily_task_snapshots"}){
            String key=table.startsWith("daily_")?"day":"id";
            for(String action:new String[]{"INSERT","UPDATE","DELETE"}){
                String row=action.equals("DELETE")?"OLD":"NEW";
                db.execSQL("CREATE TRIGGER sync_"+table+"_"+action+" AFTER "+action+" ON "+table+" BEGIN INSERT OR REPLACE INTO sync_changes(kind,entity_id) VALUES('"+table+"',"+row+"."+key+"); END");
            }
        }
    }
    static String hash(String s) throws Exception {byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    static JSONObject task(LedgerStore.Task t,LedgerStore.DaySnapshot day)throws Exception{
        int progress=-1;for(LedgerStore.DayTask item:day.items)if(item.taskId.equals(t.id)){progress=item.progress;break;}
        return new JSONObject().put("id",t.id).put("title",t.title).put("rank",t.sort+1).put("archived",t.archived).put("day",day.day.toString()).put("progress",progress<0?JSONObject.NULL:progress);
    }
    static JSONObject day(LedgerStore.DaySnapshot d)throws Exception{
        JSONArray tasks=new JSONArray();for(LedgerStore.DayTask t:d.items)tasks.put(new JSONObject().put("id",t.taskId).put("title",t.title).put("rank",t.rank<0?JSONObject.NULL:t.rank).put("progress",t.progress<0?JSONObject.NULL:t.progress).put("weight",Double.isFinite(t.weight)?t.weight:JSONObject.NULL).put("actualMs",t.actualMs).put("archived",t.archived));
        return new JSONObject().put("day",d.day.toString()).put("tasks",tasks).put("totalActualMs",d.totalActualMs).put("totalManualMs",d.totalManualMs).put("totalProgress",Double.isFinite(d.totalProgress)?d.totalProgress:JSONObject.NULL).put("completeSnapshot",d.completeSnapshot).put("grade",d.settlementGrade);
    }
    static JSONObject session(Session s)throws Exception{return new JSONObject().put("id",s.id).put("taskId",s.taskId).put("title",s.taskTitle).put("startedWallMs",s.startedWallMs).put("endedWallMs",s.endedWallMs).put("actualMs",s.actualMs).put("plannedMs",s.plannedMs).put("zone",s.zoneId).put("manual",s.manual).put("edited",s.edited).put("deleted",false);}
    static JSONObject apply(LedgerStore store,JSONObject command)throws Exception{
        synchronized(store){SQLiteDatabase db=store.getWritableDatabase();String id=command.getString("id"),payload=command.toString();
            try(Cursor c=db.rawQuery("SELECT payload,result FROM sync_receipts WHERE id=?",new String[]{id})){if(c.moveToFirst()){if(!c.getString(0).equals(payload))throw new IllegalArgumentException("操作编号重复但内容不同");return new JSONObject(c.getString(1));}}
            JSONObject result=new JSONObject().put("id",id);String status="applied";
            db.beginTransaction();try{
                if(!LocalDate.now().toString().equals(command.getString("day"))){status="expired";}
                else {
                    String type=command.getString("type");
                    if(type.equals("create_task")){LedgerStore.Task t=store.saveSimpleTask(command.getString("title"),0,store.getTasks(LocalDate.now()).size()+1);result.put("taskId",t.id);}
                    else {
                        String tid=command.getString("task_id");LedgerStore.Task t=store.getTask(tid);
                        if(t==null||t.archived||!hash(task(t,store.getDaySnapshot(LocalDate.now())).toString()).equals(command.getString("expected_revision")))status="conflict";
                        else if(type.equals("update_task"))store.updateSimpleTask(tid,command.optString("title",t.title),command.optInt("rank",t.sort+1));
                        else if(type.equals("set_task_progress"))store.setTaskProgress(tid,command.getInt("progress"));
                        else throw new IllegalArgumentException("不支持的操作");
                    }
                }
                result.put("status",status);db.execSQL("INSERT INTO sync_receipts(id,payload,result) VALUES(?,?,?)",new Object[]{id,payload,result.toString()});db.setTransactionSuccessful();
            }finally{db.endTransaction();}return result;
        }
    }
}
