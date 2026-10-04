package cn.focusledger;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import cn.focusledger.core.*;
import java.io.*;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;

/** Real native UI and timer checks. Run only on a fresh disposable emulator. */
public final class LedgerInstrumentation extends Instrumentation {
    private String suite="all"; private int passed; private final StringBuilder log=new StringBuilder();
    private volatile Activity screen; private Context context; private LedgerStore store; private TimerCoordinator timer;
    private void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;log.append("PASS ").append(name).append('\n');Bundle s=new Bundle();s.putString("stream","PASS "+name+"\n");sendStatus(0,s);}
    @Override public void onCreate(Bundle args){super.onCreate(args);suite=args==null?"all":args.getString("suite","all");start();}
    @Override public void callActivityOnResume(Activity a){super.callActivityOnResume(a);screen=a;}
    @Override public void onStart(){Bundle result=new Bundle();try{
        context=getTargetContext();
        if("store".equals(suite)||"export".equals(suite)){DailyArchive.get(context);if("store".equals(suite))StoreV2Instrumentation.run(context,name->check(true,name));else ExportInstrumentationChecks.run(context,name->check(true,name));result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;}
        if("saf".equals(suite)){screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();desc("今日");desc("更多");desc("导出记录");click("预览导出");waitFor("保存文件",30000);click("保存文件");Bundle ready=new Bundle();ready.putString("stream","SAF_PICKER_READY\n");sendStatus(0,ready);saveThroughSystemPicker();waitFor("文件已保存",180000);check(true,"actual SAF picker returns successful native save dialog");String source=field("pendingFile",String.class);File folder=new File(context.getFilesDir(),"qa-v2");folder.mkdirs();java.nio.file.Files.copy(new File(source).toPath(),new File(folder,"saf-source.md").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);screenshot("16-saf-save-success");result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;}
        if ("layout".equals(suite)) {
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); idle();
            final boolean[] clicked={false};
            runOnMainSync(()->{View tab=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&"任务".contentEquals(v.getContentDescription()));if(tab!=null){clicked[0]=true;tab.performClick();}});
            idle(); check(clicked[0]&&has("当天任务"),"layout check opened actual task list");
            List<String> labels=new ArrayList<>(); List<Boolean> fits=new ArrayList<>();
            runOnMainSync(()->{walk(screen.getWindow().getDecorView(),v->{if(v instanceof TextView&&((TextView)v).getText().toString().matches("[0-9]{1,3}%")){TextView t=(TextView)v;labels.add(t.getText().toString());fits.add(t.getLineCount()==1&&t.getLayout()!=null&&t.getLayout().getLineWidth(0)<=t.getWidth()+0.5);}return false;});});
            check(!labels.isEmpty(),"layout checks include actual percentage labels");
            for(int i=0;i<labels.size();i++)check(fits.get(i),"percentage stays one line and fits width at current font scale: "+labels.get(i));
            screenshot("14-layout-tasks");desc("专注");final boolean[] visible={false};
            runOnMainSync(()->{View button=findText(rootViews(),"开始专注");android.graphics.Rect rect=new android.graphics.Rect();visible[0]=button!=null&&button.getGlobalVisibleRect(rect)&&rect.height()==button.getHeight();});
            check(visible[0],"focus primary action fully visible at current screen/font size");screenshot("15-layout-focus");
            result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;
        }
        check("时本".contentEquals(context.getApplicationInfo().loadLabel(context.getPackageManager())),"installed Chinese app label");
        check(context.checkSelfPermission("android.permission.INTERNET")!=0,"no Internet permission");
        check((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)==0,"automatic cloud backup disabled");
        screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        timer=TimerCoordinator.get(context);store=timer.store();
        check(store.getTasks(LocalDate.now()).isEmpty(),"fresh installation has no demo tasks");
        check(has("先添加一个任务")&&has("时本"),"empty native focus screen");
        screenshot("01-focus-empty");
        click("添加任务");setEditor("Web3",100);recreate();
        check("Web3".equals(field("editorName",EditText.class).getText().toString()),"task draft survives Activity recreation");
        check(field("editorProgress",SeekBar.class).getProgress()==100,"draft progress survives Activity recreation");
        click("保存任务");check(store.getTasks(LocalDate.now()).size()==1,"first task created through native editor");
        addTask("英语",40);addTask("锻炼",40);addTask("读书",0);
        check(store.getTasks(LocalDate.now()).size()==4,"four user-created task rows");
        List<LedgerStore.Task> tasks=store.getTasks(LocalDate.now());String firstId=tasks.get(0).id;
        check(Math.abs(store.getDaySnapshot(LocalDate.now()).totalProgress-65)<.001,"priority progress displays exact 65 percent fixture");
        screenshot("02-tasks");
        desc("英语，进度");setSeek(77);click("取消");check(progress(tasks.get(1).id)==40,"cancelled progress change is not saved");
        desc("英语，进度");setSeek(55);recreate();check(has("55%"),"progress dialog draft survives Activity recreation");click("保存进度");check(progress(tasks.get(1).id)==55,"partial progress saved independently");
        desc("英语，进度");setSeek(100);runOnMainSync(()->{View save=findText(rootViews(),"保存进度");save.performClick();check(walk(screen.getWindow().getDecorView(),UiKit.Fireworks.class::isInstance)!=null,"100 percent triggers celebration");});idle();check(progress(tasks.get(1).id)==100,"full progress persisted");SystemClock.sleep(2300);check(count(UiKit.Fireworks.class)==0,"celebration removed after two seconds");
        desc("英语，进度");setSeek(40);click("保存进度");
        desc("新增任务");setEditor("未保存草稿",0);back();click("继续编辑");check(has("新增任务"),"cancelled leave keeps editor");back();click("放弃");check(store.getTasks(LocalDate.now()).size()==4,"discarded draft creates no task");
        desc("调整优先级");click("↓");click("完成");check(store.getTasks(LocalDate.now()).get(0).id.equals(tasks.get(1).id),"native reorder changes priority");
        desc("调整优先级");runOnMainSync(()->{View target=findText(rootViews(),"Web3");View row=(View)target.getParent();View up=findText(Collections.singletonList(row),"↑");up.performClick();});idle();click("完成");check(store.getTasks(LocalDate.now()).get(0).id.equals(firstId),"priority can be restored with accessible up action");
        addTask("删除验证",0);String removedId=store.getTasks(LocalDate.now()).get(4).id;runOnMainSync(()->{View row=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith("删除验证，进度"));row.performLongClick();});idle();click("删除任务");click("取消");check(store.getTasks(LocalDate.now()).size()==5,"cancelled task deletion retains row");click("删除任务");click("删除");check(store.getTasks(LocalDate.now()).size()==4&&store.getTask(removedId).archived,"confirmed task deletion archives data");
        desc("今日");check(has("今日进度")&&has("按任务优先级计算"),"today tab native navigation");screenshot("03-today-65");
        desc("更多");check(has("历史记录")&&has("导出记录")&&has("提醒设置"),"more menu only reached from today");screenshot("04-more");
        desc("提醒设置");check(has("声音")&&has("振动"),"reminder settings available");screenshot("05-settings");back();desc("本地存储与备份");check(has("历史记录保存在本机"),"local backup explanation available");back();
        desc("历史记录");check(has("本地记录，不会自动过期"),"history list contains persisted day");screenshot("06-history");back();desc("导出记录");check(has("按日")&&has("按月")&&has("按年")&&has("自定义范围")&&has("全部记录"),"all five export scopes visible in native view tree");screenshot("07-export");
        click("预览导出");waitFor("保存文件",20000);check(has("Markdown 预览"),"Markdown export produces preview");screenshot("08-markdown-preview");click("复制内容");check(readClipboard().getItemAt(0).getText().toString().contains("Web3"),"complete Markdown copied to clipboard");back();
        click("○ PNG 图片");click("预览导出");waitFor("保存文件",30000);check(has("PNG 预览"),"PNG export produces native image preview");screenshot("09-png-preview");
        desc("专注");desc("任务，");runOnMainSync(()->{ListView list=findType(ListView.class);list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1));});click("确定");String timerTaskId=tasks.get(1).id;check(has("开始专注"),"focus ready with selected task");screenshot("10-focus-ready");
        desc("时长，");runOnMainSync(()->{NumberPicker p=findType(NumberPicker.class);check(p!=null&&p.getMinValue()==3,"duration selector enforces 15 minute minimum");p.setValue(3);});click("确定");
        click("开始专注");SystemClock.sleep(1400);check(timer.engine().isActive()&&timer.actualMs()>=1000,"native Start begins real elapsed countdown");if(Build.VERSION.SDK_INT>=33)check(!timer.notificationsGranted(),"timer works with notification permission denied");screenshot("11-focus-running");
        boolean duplicate=false;try{timer.start(store.getTask(timerTaskId),15);}catch(IllegalStateException expected){duplicate=true;}check(duplicate,"duplicate active timer rejected");
        click("暂停");long paused=timer.actualMs();SystemClock.sleep(1200);check(timer.actualMs()==paused&&store.loadTimer().paused,"pause durable and does not accrue");screenshot("12-focus-paused");recreate();check(has("已暂停")&&has("继续专注"),"paused timer survives Activity recreation");
        click("继续专注");SystemClock.sleep(1100);click("结束");click("继续专注");check(timer.engine().isActive(),"cancelled finish keeps timer active");click("结束");click("结束并保存");check(!timer.engine().isActive()&&store.getSessions().size()==1,"early finish persists exactly one session");Session early=store.getSessions().get(0);check(early.actualMs>=2000&&early.actualMs<120000,"early finish keeps actual time only");check(progress(timerTaskId)==40,"timer does not change partial manual progress");
        store.persistTimer(new TimerEngine().snapshot(),early);check(store.getSessions().size()==1,"terminal session persistence is idempotent");
        // Move the earlier test record away from the accelerated timer interval so start-time sort remains chronological.
        long priorDay=LocalDate.now().minusDays(1).atTime(8,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.saveEditedSession(Session.manual(early.id,early.taskId,early.taskTitle,early.projectId,priorDay,early.actualMs,early.zoneId,true));
        // Explicit accelerated fixture only in test APK: 14m55s elapsed, then real final seconds.
        runOnMainSync(()->timer.start(store.getTask(timerTaskId),15));TimerEngine.State state=timer.engine().snapshot();long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();state.startedWallMs=wall-895000;state.accumulatedMs=895000;state.segments=new ArrayList<>();state.segments.add(new Segment(wall-895000,wall,895000));state.segmentStartWallMs=wall;state.segmentStartElapsedMs=elapsed;state.checkpointWallMs=wall;state.checkpointElapsedMs=elapsed;
        Field engine=TimerCoordinator.class.getDeclaredField("engine");engine.setAccessible(true);engine.set(timer,new TimerEngine(state));store.persistTimer(state,null);timer.schedule();runOnMainSync(()->screen.moveTaskToBack(true));SystemClock.sleep(7000);
        // Returning synchronizes elapsed state even without optional exact-alarm permission.
        if(Build.VERSION.SDK_INT<31)check(!timer.engine().isActive(),"API29 timer expires while still in background");
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));idle();runOnMainSync(timer::refresh);
        check(!timer.engine().isActive(),"expired background timer reconciles without overcounting");Session expired=store.getSession(state.sessionId);check(expired!=null&&expired.actualMs==900000&&"expired".equals(expired.reason),"expiry caps exact planned duration");check(store.getSessions().size()==2,"expiry creates one terminal record");check(progress(timerTaskId)==40,"expiry keeps independent partial manual progress");waitFor("本次专注完成",10000);screenshot("13-focus-complete");click("返回专注");
        check(has("开始专注"),"completion acknowledgement returns to idle");
        // Export specialist's runtime checks are discovered only from this separate test APK.
        ExportInstrumentationChecks.run(context,name->check(true,name));
        StoreV2Instrumentation.run(context,name->check(true,name));
        result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream",log+"FAIL "+error+"\n"+android.util.Log.getStackTraceString(error));try{screenshot("99-failure");}catch(Throwable ignored){}finish(Activity.RESULT_CANCELED,result);}}
    private void idle(){waitForIdleSync();SystemClock.sleep(180);}
    private List<View> rootViews(){return WindowInspector.getGlobalWindowViews();}
    private View findText(List<View> roots,String text){for(int r=roots.size()-1;r>=0;r--){View found=walk(roots.get(r),v->v instanceof TextView&&text.contentEquals(((TextView)v).getText()));if(found!=null)return found;}return null;}
    private View walk(View v,java.util.function.Predicate<View> match){if(match.test(v))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=walk(g.getChildAt(i),match);if(found!=null)return found;}}return null;}
    private boolean has(String text){final boolean[] found={false};runOnMainSync(()->found[0]=findText(rootViews(),text)!=null);return found[0];}
    private void click(String text){runOnMainSync(()->{View v=findText(rootViews(),text);if(v==null)throw new AssertionError("Missing native control: "+text);while(!v.isClickable()&&v.getParent() instanceof View)v=(View)v.getParent();v.performClick();});idle();}
    private void desc(String prefix){runOnMainSync(()->{View v=walk(screen.getWindow().getDecorView(),x->x.getContentDescription()!=null&&x.getContentDescription().toString().startsWith(prefix));if(v==null)throw new AssertionError("Missing accessible control: "+prefix);v.performClick();});idle();}
    private void back(){runOnMainSync(()->screen.onBackPressed());idle();}
    @SuppressWarnings("unchecked") private <T>T field(String name,Class<T> type){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(screen);}catch(Exception e){throw new RuntimeException(e);}}
    private <T extends View>T findType(Class<T> type){for(View root:rootViews()){View v=walk(root,type::isInstance);if(v!=null)return type.cast(v);}return null;}
    private int count(Class<? extends View> type){final int[] n={0};runOnMainSync(()->{for(View r:rootViews())walk(r,v->{if(type.isInstance(v))n[0]++;return false;});});return n[0];}
    private void setEditor(String title,int value){runOnMainSync(()->{field("editorName",EditText.class).setText(title);field("editorProgress",SeekBar.class).setProgress(value);});idle();}
    private void addTask(String title,int value){desc("新增任务");setEditor(title,value);click("保存任务");}
    private void setSeek(int value){runOnMainSync(()->{SeekBar bar=findType(SeekBar.class);if(bar==null)throw new AssertionError("No native progress slider");bar.setProgress(value);});idle();}
    private int progress(String id){for(LedgerStore.DayTask t:store.getDaySnapshot(LocalDate.now()).items)if(t.taskId.equals(id))return t.progress;throw new AssertionError("Missing day task");}
    private void recreate(){Activity previous=screen;runOnMainSync(previous::recreate);for(int i=0;i<100&&screen==previous;i++)SystemClock.sleep(100);idle();check(screen!=previous,"Activity recreation completed");}
    private void waitFor(String text,long timeout){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){idle();if(has(text))return;SystemClock.sleep(200);}throw new AssertionError("Timed out waiting for "+text);}
    private void saveThroughSystemPicker(){long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&"com.android.documentsui".contentEquals(root.getPackageName())){java.util.List<android.view.accessibility.AccessibilityNodeInfo> fields=root.findAccessibilityNodeInfosByViewId("android:id/title");for(android.view.accessibility.AccessibilityNodeInfo field:fields)if("android.widget.EditText".contentEquals(field.getClassName())){Bundle text=new Bundle();text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"shiben-qa-"+SystemClock.uptimeMillis()+".md");check(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,text),"native SAF filename set for isolated test export");java.util.List<android.view.accessibility.AccessibilityNodeInfo> save=root.findAccessibilityNodeInfosByText("SAVE");if(save.isEmpty())throw new AssertionError("Native system SAVE button not found");check(save.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"native system Save action dispatched");return;}}SystemClock.sleep(150);}throw new AssertionError("Native DocumentsUI picker was not ready");}
    private void awaitAppFocus(){long end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("System UI isn’t responding").isEmpty()||root!=null&&!root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()){java.util.List<android.view.accessibility.AccessibilityNodeInfo> waits=root.findAccessibilityNodeInfosByText("Wait");if(!waits.isEmpty()){android.view.accessibility.AccessibilityNodeInfo n=waits.get(0);while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null){n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);log.append("ENV: waited for software emulator System UI ANR\n");}}}final boolean[] focused={false};runOnMainSync(()->{for(View own:rootViews())if(own.isShown()&&own.hasWindowFocus())focused[0]=true;});if(focused[0])return;SystemClock.sleep(300);}throw new AssertionError("Application did not regain foreground focus after emulator System UI interruption");}
    private ClipData readClipboard(){awaitAppFocus();final ClipData[] data={null};runOnMainSync(()->data[0]=((ClipboardManager)screen.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip());if(data[0]==null)throw new AssertionError("Clipboard is unavailable after app regained focus");return data[0];}
    private void screenshot(String name)throws IOException{idle();if(!name.startsWith("99"))awaitAppFocus();Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("Screenshot unavailable");File folder=new File(context.getFilesDir(),"qa-v2");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
}
