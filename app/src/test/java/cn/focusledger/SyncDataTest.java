package cn.focusledger;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class SyncDataTest {
 Context context; LedgerStore store;
 @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("focus-ledger.db");store=new LedgerStore(context);store.ensureToday();}
 @After public void finish(){store.close();}
 JSONObject create(String title)throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("type","create_task").put("day",LocalDate.now().toString()).put("title",title);}
 long count(String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();return c.getLong(0);}}
 @Test public void retriesReturnSameTaskAndReceipt()throws Exception{
  JSONObject command=create("一次任务"),a=SyncData.apply(store,command),b=SyncData.apply(store,command);
  assertEquals("applied",a.getString("status"));assertEquals(a.toString(),b.toString());assertEquals(1,count("tasks"));assertEquals(1,count("sync_receipts"));
  command.put("title","不同内容");try{SyncData.apply(store,command);fail();}catch(IllegalArgumentException expected){}assertEquals(1,count("tasks"));
 }
 @Test public void failureRollsBackTaskAndReceipt()throws Exception{
  store.getWritableDatabase().execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON sync_receipts BEGIN SELECT RAISE(ABORT,'test failure'); END");
  try{SyncData.apply(store,create("回滚"));fail();}catch(android.database.SQLException expected){}
  assertEquals(0,count("tasks"));assertEquals(0,count("sync_receipts"));
 }
 @Test public void revisionsAndDayPreventStaleWrites()throws Exception{
  String id=SyncData.apply(store,create("原任务")).getString("taskId");
  String revision=SyncData.hash(SyncData.task(store.getTask(id),store.getDaySnapshot(LocalDate.now())).toString());
  JSONObject edit=new JSONObject().put("id",UUID.randomUUID().toString()).put("type","set_task_progress").put("day",LocalDate.now().toString()).put("task_id",id).put("expected_revision",revision).put("progress",100);
  assertEquals("applied",SyncData.apply(store,edit).getString("status"));assertEquals(100,SyncData.task(store.getTask(id),store.getDaySnapshot(LocalDate.now())).getInt("progress"));
  edit.put("id",UUID.randomUUID().toString()).put("progress",20);assertEquals("conflict",SyncData.apply(store,edit).getString("status"));
  JSONObject expired=create("昨天").put("day",LocalDate.now().minusDays(1).toString());assertEquals("expired",SyncData.apply(store,expired).getString("status"));assertEquals(1,count("tasks"));
 }
 @Test public void titleAndPriorityUseExistingBusinessRules()throws Exception{
  String first=SyncData.apply(store,create("第一项")).getString("taskId"),second=SyncData.apply(store,create("第二项")).getString("taskId");
  String revision=SyncData.hash(SyncData.task(store.getTask(second),store.getDaySnapshot(LocalDate.now())).toString());
  JSONObject edit=new JSONObject().put("id",UUID.randomUUID().toString()).put("type","update_task").put("day",LocalDate.now().toString()).put("task_id",second).put("expected_revision",revision).put("title","改名").put("rank",1);
  assertEquals("applied",SyncData.apply(store,edit).getString("status"));assertEquals("改名",store.getTask(second).title);assertEquals(0,store.getTask(second).sort);assertEquals(1,store.getTask(first).sort);
 }
 @Test public void v3UpgradePreservesTasksAndProgress()throws Exception{
  LedgerStore.Task task=store.saveSimpleTask("旧记录",42,1);SQLiteDatabase db=store.getWritableDatabase();
  java.util.List<String> triggers=new java.util.ArrayList<>();try(Cursor c=db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'sync_%'",null)){while(c.moveToNext())triggers.add(c.getString(0));}for(String name:triggers)db.execSQL("DROP TRIGGER "+name);
  for(String name:new String[]{"sync_meta","sync_receipts","sync_hashes","sync_changes"})db.execSQL("DROP TABLE "+name);
  db.setVersion(3);String before=store.getDaySnapshot(LocalDate.now()).items.get(0).title;store.close();store=new LedgerStore(context);db=store.getWritableDatabase();
  assertEquals(4,db.getVersion());assertEquals("旧记录",store.getTask(task.id).title);assertEquals(before,store.getDaySnapshot(LocalDate.now()).items.get(0).title);assertEquals(42,SyncData.task(store.getTask(task.id),store.getDaySnapshot(LocalDate.now())).getInt("progress"));
  assertEquals(1,count("sync_meta"));store.setTaskProgress(task.id,60);assertTrue(count("sync_changes")>0);
 }
}
