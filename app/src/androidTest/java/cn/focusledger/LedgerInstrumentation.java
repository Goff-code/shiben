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
    private final List<String> geometryFailures=new ArrayList<>();
    private String suite="all"; private int passed; private final StringBuilder log=new StringBuilder();
    private volatile Activity screen; private Context context; private LedgerStore store; private TimerCoordinator timer;
    private void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;log.append("PASS ").append(name).append('\n');Bundle s=new Bundle();s.putString("stream","PASS "+name+"\n");sendStatus(0,s);}
    @Override public void onCreate(Bundle args){super.onCreate(args);suite=args==null?"all":args.getString("suite","all");start();}
    @Override public void callActivityOnResume(Activity a){super.callActivityOnResume(a);screen=a;}
    @Override public void onStart(){Bundle result=new Bundle();try{
        context=getTargetContext();
        if("store".equals(suite)||"export".equals(suite)){DailyArchive.get(context);if("store".equals(suite))StoreV2Instrumentation.run(context,name->check(true,name));else ExportInstrumentationChecks.run(context,name->check(true,name));result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;}
        if("presentation".equals(suite)){store=TimerCoordinator.get(context).store();check(store.getTasks(LocalDate.now()).isEmpty()&&store.getSessions().isEmpty(),"presentation uses isolated empty fixture");store.saveSimpleTask("阅读",100,1);store.saveSimpleTask("Web3",60,2);store.saveSimpleTask("英语",0,3);store.saveSimpleTask("代码视频",0,4);screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();tab("专注");desc("任务，");runOnMainSync(()->{ListView list=findType(ListView.class);list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0));});click("确定");screenshot("20-presentation-focus");runOnMainSync(()->{View tab=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&"任务".contentEquals(v.getContentDescription()));tab.performClick();});idle();check(has("每日进度从 0% 开始，任务列表会保留"),"daily-reset help is present in current native task list");screenshot("21-presentation-tasks");tab("今日");check(store.getDaySnapshot(LocalDate.now()).totalProgress==65,"presentation total is calculated65percent, not a hardcoded display");screenshot("22-presentation-today");result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;}
        if("empty_history".equals(suite)){screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();store=TimerCoordinator.get(context).store();check(store.getTasks(LocalDate.now()).isEmpty()&&store.getSessions().isEmpty(),"fresh history UI fixture has no tasks or sessions");tab("今日");desc("更多");desc("历史记录");check(has("还没有历史记录"),"untouched installation shows actual empty history screen");check(store.getRecordedDays().isEmpty(),"empty daily header does not become recorded history");screenshot("19-empty-history");result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;}
        if("upgrade_seed".equals(suite)||"upgrade_verify".equals(suite)){
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();timer=TimerCoordinator.get(context);store=timer.store();tab("专注");File expectation=new File(context.getFilesDir(),"qa-upgrade-state.json");
            if("upgrade_seed".equals(suite)){check(!store.getTasks(LocalDate.now()).isEmpty(),"upgrade fixture has existing user-created tasks");
                // Enrich only this disposable test fixture: real session, prior-day record and tombstones.
                boolean priorExists=android.database.DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM sessions WHERE id=?",new String[]{"qa-upgrade-prior"})>0;
                boolean deletedExists=android.database.DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM sessions WHERE id=?",new String[]{"qa-upgrade-deleted"})>0;
                boolean archivedExists=android.database.DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT COUNT(*) FROM tasks WHERE title=? AND archived=1",new String[]{"升级保留已归档"})>0;
                if(!priorExists||!deletedExists||!archivedExists){
                    runOnMainSync(()->{if(timer.engine().isActive())timer.finish();});
                    LedgerStore.Task current=store.getTasks(LocalDate.now()).get(0);
                    long prior=LocalDate.now().minusDays(1).atTime(8,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if(!priorExists)store.addManualSession(Session.manual("qa-upgrade-prior",current.id,current.title,current.projectId,prior,125000,ZoneId.systemDefault().getId(),true));
                    if(!deletedExists)store.addManualSession(Session.manual("qa-upgrade-deleted",current.id,current.title,current.projectId,prior+3600000,43000,ZoneId.systemDefault().getId(),true));store.deleteSession("qa-upgrade-deleted");
                    if(!archivedExists){LedgerStore.Task archived=null;for(LedgerStore.Task candidate:store.getTasks(LocalDate.now()))if("升级保留已归档".equals(candidate.title))archived=candidate;if(archived==null)archived=store.saveSimpleTask("升级保留已归档",35,store.getTasks(LocalDate.now()).size()+1);store.archiveTask(archived.id);}
                    DailyArchive.schedule(context);SystemClock.sleep(2500);
                }
                check(!store.getSessions().isEmpty()&&store.getRecordedDays().size()>=2,"upgrade fixture contains actual focus history and prior-day records");runOnMainSync(()->{if(!timer.engine().isActive())timer.start(store.getTasks(LocalDate.now()).get(0),15);});SystemClock.sleep(1200);runOnMainSync(timer::pause);TimerEngine.State state=store.loadTimer();org.json.JSONObject expected=new org.json.JSONObject();expected.put("sessionId",state.sessionId);expected.put("taskId",state.taskId);expected.put("plannedMs",state.plannedMs);expected.put("actualMs",state.accumulatedMs);expected.put("taskCount",store.getTasks(LocalDate.now()).size());expected.put("sessionCount",store.getSessions().size());expected.put("totalProgress",store.getDaySnapshot(LocalDate.now()).totalProgress);context.getSharedPreferences("shiben-settings",Context.MODE_PRIVATE).edit().putBoolean("reminders",true).putBoolean("sound",false).putBoolean("vibration",false).commit();expected.put("settings",new org.json.JSONObject(context.getSharedPreferences("shiben-settings",Context.MODE_PRIVATE).getAll()));try(FileOutputStream out=new FileOutputStream(expectation)){out.write(expected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}check(state.paused,"paused timer persisted before package update");waitFor("已暂停",15000);screenshot("17-upgrade-paused-v3");}
            else{check(context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode==4,"visual patch versionCode4 is installed");org.json.JSONObject expected=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(expectation.toPath()),java.nio.charset.StandardCharsets.UTF_8));TimerEngine.State state=store.loadTimer();check(state!=null&&state.paused,"paused timer survives in-place package update");check(expected.getString("sessionId").equals(state.sessionId)&&expected.getString("taskId").equals(state.taskId),"timer and task identities unchanged after update");check(expected.getLong("plannedMs")==state.plannedMs&&expected.getLong("actualMs")==state.accumulatedMs,"planned and accumulated timer duration unchanged");check(expected.getInt("taskCount")==store.getTasks(LocalDate.now()).size(),"task list count survives update");check(expected.getInt("sessionCount")==store.getSessions().size(),"session history count survives update");check(Math.abs(expected.getDouble("totalProgress")-store.getDaySnapshot(LocalDate.now()).totalProgress)<1e-9,"weighted manual progress survives update");if(expected.has("settings")){org.json.JSONObject settings=expected.getJSONObject("settings");for(Iterator<String> keys=settings.keys();keys.hasNext();){String key=keys.next();check(Objects.equals(settings.get(key),context.getSharedPreferences("shiben-settings",Context.MODE_PRIVATE).getAll().get(key)),"reminder preference survives update: "+key);}}waitFor("已暂停",15000);screenshot("18-upgrade-paused-v4");}
            result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;
        }
        if("saf".equals(suite)){screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();tab("今日");desc("更多");desc("导出记录");click("预览导出");waitFor("保存文件",30000);click("保存文件");Bundle ready=new Bundle();ready.putString("stream","SAF_PICKER_READY\n");sendStatus(0,ready);saveThroughSystemPicker();waitForSavedFile();check(true,"actual SAF picker returns successful native save dialog");String source=field("pendingFile",String.class);File folder=new File(context.getFilesDir(),"qa-v2");folder.mkdirs();java.nio.file.Files.copy(new File(source).toPath(),new File(folder,"saf-source.md").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);screenshot("16-saf-save-success");String copyId=field("copyJobId",String.class),destination=field("copyDestination",String.class),delivered=field("copyDeliveredId",String.class);click("返回");recreate();check(copyId!=null&&copyId.equals(field("copyJobId",String.class)),"save job identity survives Activity recreation");check(destination!=null&&destination.equals(field("copyDestination",String.class)),"chosen document URI survives Activity recreation");check(delivered!=null&&delivered.equals(field("copyDeliveredId",String.class))&&!has("文件已保存"),"completed save feedback is not duplicated after recreation");String oldExport=field("exportJobId",String.class);back();click("预览导出");waitFor("保存文件",30000);String freshExport=field("exportJobId",String.class);check(!oldExport.equals(freshExport)&&jobListener(oldExport)==null,"repeated export detaches previous terminal Activity listener");recreate();check(listenerCapturesActivity(jobListener(freshExport)),"recreated preview attaches only the current Activity");String oldCopy=field("copyJobId",String.class);runOnMainSync(()->((MainActivity)screen).onActivityResult(401,Activity.RESULT_OK,new Intent().setData(android.net.Uri.parse(destination))));waitFor("文件已保存",30000);check(!oldCopy.equals(field("copyJobId",String.class))&&jobListener(oldCopy)==null,"repeated authorized document copy detaches previous Activity listener");click("返回");result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;}
        if ("png_probe".equals(suite)) {
            store=TimerCoordinator.get(context).store();if(store.getTasks(LocalDate.now()).isEmpty()){store.saveSimpleTask("Web3",100,1);store.saveSimpleTask("英语",40,2);store.saveSimpleTask("锻炼",40,3);store.saveSimpleTask("读书",0,4);}
            check(store.getTasks(LocalDate.now()).size()==4&&store.getSessions().isEmpty(),"PNG probe uses isolated four-task/no-session fixture");
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();tab("今日");desc("更多");desc("导出记录");click("○ PNG 图片");click("预览导出");
            String jobId=field("exportJobId",String.class);long began=SystemClock.elapsedRealtime();boolean dumped=false;ExportWorker.Job job=ExportWorker.get(context).getJob(jobId);
            while(!job.isTerminal()&&SystemClock.elapsedRealtime()-began<240000){
                long elapsed=SystemClock.elapsedRealtime()-began;Bundle update=new Bundle();update.putString("stream","PNG_PROBE elapsed="+elapsed+" state="+job.state+" progress="+job.completed+"/"+job.total+" message="+job.message+"\n");sendStatus(0,update);
                if(elapsed>30000&&!dumped){dumped=true;StringBuilder traces=new StringBuilder();for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().equals("main")||entry.getKey().getName().equals("RenderThread")||entry.getKey().getName().startsWith("shiben-")){traces.append(entry.getKey().getName()).append(' ').append(entry.getKey().getState()).append('\n');for(StackTraceElement frame:entry.getValue())traces.append("  ").append(frame).append('\n');}Bundle trace=new Bundle();trace.putString("stream",traces.toString());sendStatus(0,trace);}
                SystemClock.sleep(2000);
            }
            check(job.state==ExportWorker.State.SUCCEEDED,"native PNG probe result after "+(SystemClock.elapsedRealtime()-began)+"ms: "+job.state+" "+job.error);waitFor("保存文件",60000);check(has("PNG 预览"),"PNG result visible and saveable after "+(SystemClock.elapsedRealtime()-began)+"ms");screenshot("37-png-probe-complete");result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;
        }
        if ("dialogs".equals(suite)) {
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();
            store=TimerCoordinator.get(context).store();timer=TimerCoordinator.get(context);
            check(!timer.engine().isActive(),"dialog fixture starts without an active timer");
            String longTitle="这是一个用于验证小屏大字体弹窗可读性与编辑输入空间的长任务名称";
            longTitle=longTitle.substring(0,30);
            LedgerStore.Task existing=null;for(LedgerStore.Task candidate:store.getTasks(LocalDate.now()))if(candidate.title.equals(longTitle))existing=candidate;final LedgerStore.Task task=existing==null?store.saveSimpleTask(longTitle,40,store.getTasks(LocalDate.now()).size()+1):existing;
            tab("任务");desc(task.title+"，进度");setSeek(55);checkDialogFit("long-progress");screenshot("31-dialog-long-progress");click("取消");check(progress(task.id)==40,"cancelled long-title progress dialog keeps value");
            runOnMainSync(()->{View row=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(task.title+"，进度"));row.performLongClick();});idle();
            awaitAppFocus();runOnMainSync(()->{EditText input=field("editorName",EditText.class);input.requestFocus();((android.view.inputmethod.InputMethodManager)screen.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_FORCED);});
            long imeDeadline=SystemClock.elapsedRealtime()+20000;while(keyboardHeight()<UiKit.dp(context,100)&&SystemClock.elapsedRealtime()<imeDeadline)SystemClock.sleep(250);idle();screenshot("32-editor-keyboard-top");
            check(keyboardHeight()>UiKit.dp(context,100),"software keyboard is actually displayed for editor check; obscured pixels="+keyboardHeight());
            final boolean[] titleKept={false};runOnMainSync(()->{EditText input=field("editorName",EditText.class);android.graphics.Rect rect=new android.graphics.Rect();geometryCheck(input.getGlobalVisibleRect(rect)&&rect.height()==input.getHeight(),"keyboard-open task input remains fully visible");titleKept[0]=input.getText().toString().equals(task.title);});check(titleKept[0],"keyboard-open editor retains complete 30-character title");
            reveal("保存任务");checkControlVisible("保存任务","keyboard-open save action");screenshot("33-editor-keyboard-save");
            runOnMainSync(()->((android.view.inputmethod.InputMethodManager)screen.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(screen.getWindow().getDecorView().getWindowToken(),0));SystemClock.sleep(700);idle();
            reveal("删除任务");click("删除任务");checkDialogFit("long-delete");screenshot("34-dialog-long-delete");click("取消");check(!store.getTask(task.id).archived,"cancelled long-title deletion keeps task");back();click("放弃");
            tab("专注");desc("任务，");runOnMainSync(()->{ListView list=findType(ListView.class);int index=list.getAdapter().getCount()-1;list.performItemClick(null,index,list.getAdapter().getItemId(index));});click("确定");click("开始专注");SystemClock.sleep(1200);click("结束");checkDialogFit("long-finish");screenshot("35-dialog-long-finish");click("继续专注");check(timer.engine().isActive(),"cancelled long-title finish dialog leaves timer active");click("结束");click("结束并保存");check(!timer.engine().isActive(),"long-title timer still saves after confirmation");
            store.archiveTask(task.id);check(geometryFailures.isEmpty(),"no keyboard/dialog geometry failures: "+geometryFailures);
            result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;
        }
        if ("visual".equals(suite)) {
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); idle();
            store=TimerCoordinator.get(context).store();
            check(store.getTasks(LocalDate.now()).size()==4,"visual review uses four-task fixture");
            checkSecondaryScreens();
            check(geometryFailures.isEmpty(),"no text/card geometry failures: "+geometryFailures);
            result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;
        }
        if ("home_layout".equals(suite)) {
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();
            timer=TimerCoordinator.get(context);check(!timer.engine().isActive(),"home layout diagnostic starts without an active timer");tab("专注");
            checkHomeTextFit();screenshot("38-home-metrics-ready");click("开始专注");SystemClock.sleep(1200);click("暂停");waitFor("已暂停",10000);
            checkHomeTextFit();screenshot("39-home-metrics-paused");click("结束");click("结束并保存");
            check(geometryFailures.isEmpty(),"no home geometry failures: "+geometryFailures);
            result.putString("stream",log+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);finish(Activity.RESULT_OK,result);return;
        }
        if ("layout".equals(suite)) {
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); idle();
            final boolean[] clicked={false};
            runOnMainSync(()->{View tab=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&"任务".contentEquals(v.getContentDescription()));if(tab!=null){clicked[0]=true;tab.performClick();}});
            idle(); check(clicked[0]&&has("当天任务"),"layout check opened actual task list");
            List<String> labels=new ArrayList<>(); List<Boolean> fits=new ArrayList<>();
            runOnMainSync(()->{walk(screen.getWindow().getDecorView(),v->{if(v instanceof TextView&&((TextView)v).getText().toString().matches("[0-9]{1,3}%")){TextView t=(TextView)v;labels.add(t.getText().toString());fits.add(t.getLineCount()==1&&t.getLayout()!=null&&t.getLayout().getLineWidth(0)<=t.getWidth()+0.5);}return false;});});
            check(!labels.isEmpty(),"layout checks include actual percentage labels");
            for(int i=0;i<labels.size();i++)check(fits.get(i),"percentage stays one line and fits width at current font scale: "+labels.get(i));
            checkScreenFit("tasks");checkBackdrop("tasks");checkTabs();screenshot("14-layout-tasks");tab("今日");checkScreenFit("today");checkBackdrop("today");checkTabs();screenshot("24-layout-today");tab("专注");final boolean[] visible={false};
            runOnMainSync(()->{View button=findText(rootViews(),"开始专注");android.graphics.Rect rect=new android.graphics.Rect();visible[0]=button!=null&&button.getGlobalVisibleRect(rect)&&rect.height()==button.getHeight();});
            check(count(ScrollView.class)==0,"home is fixed and contains no ScrollView");
            runOnMainSync(()->{UiKit.HomeRing home=findType(UiKit.HomeRing.class);check(home!=null&&home.getHeight()>=UiKit.dp(context,80),"home timer has usable visible space");check(home.fraction==1,"ready ring represents entire selected duration remaining");for(String prefix:new String[]{"任务，","时长，"}){View selector=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(prefix));android.graphics.Rect bounds=new android.graphics.Rect();check(selector!=null&&selector.getGlobalVisibleRect(bounds)&&bounds.height()==selector.getHeight(),"home selector fully visible: "+prefix);}});
            checkBackdrop("focus");checkTabs();check(visible[0],"focus primary action fully visible at current screen/font size");screenshot("15-layout-focus");checkHomeTextFit();
            click("开始专注");SystemClock.sleep(1200);click("暂停");waitFor("已暂停",10000);checkHomeTextFit();
            runOnMainSync(()->{UiKit.HomeRing home=findType(UiKit.HomeRing.class);check(home!=null&&home.fraction>0&&home.fraction<=1&&home.color!=UiKit.TEAL,"paused home ring has real remaining fraction and paused color");});
            screenshot("23-layout-paused");click("结束");click("结束并保存");
            checkSecondaryScreens();
            check(geometryFailures.isEmpty(),"no text/card geometry failures: "+geometryFailures);
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
        click("添加任务");setEditor("Web3",100);screenshot("25-editor-draft");recreate();
        check("Web3".equals(field("editorName",EditText.class).getText().toString()),"task draft survives Activity recreation");
        check(field("editorProgress",SeekBar.class).getProgress()==100,"draft progress survives Activity recreation");
        click("保存任务");check(store.getTasks(LocalDate.now()).size()==1,"first task created through native editor");
        addTask("英语",40);addTask("锻炼",40);addTask("读书",0);
        check(store.getTasks(LocalDate.now()).size()==4,"four user-created task rows");
        List<LedgerStore.Task> tasks=store.getTasks(LocalDate.now());String firstId=tasks.get(0).id;
        check(Math.abs(store.getDaySnapshot(LocalDate.now()).totalProgress-65)<.001,"priority progress displays exact 65 percent fixture");
        screenshot("02-tasks");
        desc("英语，进度");setSeek(77);screenshot("26-progress-dialog");click("取消");check(progress(tasks.get(1).id)==40,"cancelled progress change is not saved");
        desc("英语，进度");setSeek(55);recreate();check(has("55%"),"progress dialog draft survives Activity recreation");click("保存进度");check(progress(tasks.get(1).id)==55,"partial progress saved independently");
        desc("英语，进度");setSeek(100);runOnMainSync(()->{View save=findText(rootViews(),"保存进度");save.performClick();check(walk(screen.getWindow().getDecorView(),UiKit.Fireworks.class::isInstance)!=null,"100 percent triggers celebration");});idle();check(progress(tasks.get(1).id)==100,"full progress persisted");SystemClock.sleep(2300);check(count(UiKit.Fireworks.class)==0,"celebration removed after two seconds");
        desc("英语，进度");setSeek(40);click("保存进度");
        desc("新增任务");setEditor("未保存草稿",0);back();click("继续编辑");check(has("新增任务"),"cancelled leave keeps editor");back();click("放弃");check(store.getTasks(LocalDate.now()).size()==4,"discarded draft creates no task");
        desc("调整优先级");screenshot("27-priority");click("↓");click("完成");check(store.getTasks(LocalDate.now()).get(0).id.equals(tasks.get(1).id),"native reorder changes priority");
        desc("调整优先级");runOnMainSync(()->{View target=findText(rootViews(),"Web3");View row=(View)target.getParent();View up=findText(Collections.singletonList(row),"↑");up.performClick();});idle();click("完成");check(store.getTasks(LocalDate.now()).get(0).id.equals(firstId),"priority can be restored with accessible up action");
        addTask("删除验证",0);String removedId=store.getTasks(LocalDate.now()).get(4).id;runOnMainSync(()->{View row=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith("删除验证，进度"));row.performLongClick();});idle();click("删除任务");click("取消");check(store.getTasks(LocalDate.now()).size()==5,"cancelled task deletion retains row");click("删除任务");click("删除");check(store.getTasks(LocalDate.now()).size()==4&&store.getTask(removedId).archived,"confirmed task deletion archives data");
        tab("今日");check(has("今日进度")&&has("按任务优先级计算"),"today tab native navigation");screenshot("03-today-65");
        desc("更多");desc("进度如何计算");check(has("专注计时不会自动改变任务进度"),"priority explanation is reachable from today");screenshot("28-weights");back();tab("今日");
        desc("更多");check(has("历史记录")&&has("导出记录")&&has("提醒设置"),"more menu only reached from today");screenshot("04-more");
        desc("提醒设置");check(has("声音")&&has("振动"),"reminder settings available");screenshot("05-settings");back();desc("本地存储与备份");check(has("历史记录保存在本机"),"local backup explanation available");back();
        desc("历史记录");check(has("本地记录，不会自动过期"),"history list contains persisted day");screenshot("06-history");back();desc("导出记录");check(has("按日")&&has("按月")&&has("按年")&&has("自定义范围")&&has("全部记录"),"all five export scopes visible in native view tree");screenshot("07-export");
        click("预览导出");waitFor("保存文件",20000);check(has("Markdown 预览"),"Markdown export produces preview");screenshot("08-markdown-preview");click("复制内容");check(readClipboard().getItemAt(0).getText().toString().contains("Web3"),"complete Markdown copied to clipboard");back();
        click("○ PNG 图片");click("预览导出");waitFor("保存文件",30000);check(has("PNG 预览"),"PNG export produces native image preview");screenshot("09-png-preview");
        tab("专注");desc("任务，");runOnMainSync(()->{ListView list=findType(ListView.class);list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1));});click("确定");String timerTaskId=tasks.get(1).id;check(has("开始专注"),"focus ready with selected task");screenshot("10-focus-ready");
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

    private void waitForSavedFile(){long began=SystemClock.elapsedRealtime(),end=began+180000,last=0;while(SystemClock.elapsedRealtime()<end){idle();if(has("文件已保存"))return;final String[] state=new String[3];runOnMainSync(()->{state[0]=field("copyJobId",String.class);state[1]=field("copyDestination",String.class);state[2]=field("pendingFile",String.class);});ExportWorker.Job job=state[0]==null?null:ExportWorker.get(context).getJob(state[0]);long elapsed=SystemClock.elapsedRealtime()-began;if(elapsed-last>3000){last=elapsed;Bundle update=new Bundle();update.putString("stream","SAF_STATE elapsed="+elapsed+" copyJob="+state[0]+" destination="+state[1]+" source="+state[2]+" state="+(job==null?"none":job.state)+" error="+(job==null?"none":job.error)+"\n");sendStatus(0,update);}if(job!=null&&(job.state==ExportWorker.State.FAILED||job.state==ExportWorker.State.CANCELLED))throw new AssertionError("Native SAF copy failed: "+job.state+" "+job.error);SystemClock.sleep(200);}throw new AssertionError("Timed out waiting for actual SAF save feedback; see SAF_STATE diagnostics");}
    private int keyboardHeight(){final int[] obscured={0};runOnMainSync(()->{android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();screen.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);android.graphics.Rect frame=new android.graphics.Rect();screen.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);obscured[0]=metrics.heightPixels-frame.bottom;});return obscured[0];}
    private void reveal(String text){runOnMainSync(()->{View target=findText(rootViews(),text);if(target==null)throw new AssertionError("Missing reachable control: "+text);android.graphics.Rect bounds=new android.graphics.Rect(0,0,target.getWidth(),target.getHeight());target.requestRectangleOnScreen(bounds,true);});idle();}
    private void checkControlVisible(String text,String name){runOnMainSync(()->{View target=findText(rootViews(),text);android.graphics.Rect bounds=new android.graphics.Rect();geometryCheck(target!=null&&target.getGlobalVisibleRect(bounds)&&bounds.width()==target.getWidth()&&bounds.height()==target.getHeight(),name+" fully visible");});}
    private void checkDialogFit(String name){runOnMainSync(()->{
        View dialog=null;List<View> roots=rootViews();for(int i=roots.size()-1;i>=0;i--){View candidate=roots.get(i);if(candidate!=screen.getWindow().getDecorView()&&candidate.isShown()){dialog=candidate;break;}}
        check(dialog!=null,name+" has a real dialog window");final int[] labels={0};
        walk(dialog,v->{if(v instanceof TextView&&v.getVisibility()==View.VISIBLE){TextView text=(TextView)v;if(text.getText().length()==0)return false;labels[0]++;android.text.Layout layout=text.getLayout();String label=text.getText().toString().replace('\n',' ');geometryCheck(layout!=null,name+" text is laid out: "+label);if(layout!=null){geometryCheck(layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1,name+" text fits height: "+label);for(int i=0;i<layout.getLineCount();i++)geometryCheck(layout.getEllipsisCount(i)>0||layout.getLineMax(i)<=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+1.5f,name+" text fits line: "+label);}
            if(text instanceof Button){android.graphics.Rect bounds=new android.graphics.Rect();geometryCheck(text.getGlobalVisibleRect(bounds)&&bounds.height()==text.getHeight()&&bounds.width()==text.getWidth(),name+" action fully visible: "+label);}}
            return false;
        });check(labels[0]>0,name+" checks actual dialog text and actions");
    });}
    private void geometryCheck(boolean ok,String label){if(ok){check(true,label);return;}geometryFailures.add(label);log.append("GEOMETRY_FAIL ").append(label).append('\n');Bundle status=new Bundle();status.putString("stream","GEOMETRY_FAIL "+label+"\n");sendStatus(0,status);}
    /** Geometry checks are performed on laid-out native controls, not source-code dimensions. */
    private void checkScreenFit(String name){runOnMainSync(()->{
        View body=field("body",LinearLayout.class); final int[] textCount={0};
        walk(body,v->{
            if(v.getVisibility()!=View.VISIBLE)return false;
            if(v instanceof TextView && !(v instanceof EditText)){
                TextView t=(TextView)v; if(t.getText().length()==0||t.getWidth()==0)return false;
                android.text.Layout layout=t.getLayout();textCount[0]++;
                String label=t.getText().toString().replace('\n',' '); if(label.length()>65)label=label.substring(0,65);
                geometryCheck(layout!=null,name+" text has native layout: "+label);if(layout==null)return false;
                int width=t.getWidth()-t.getCompoundPaddingLeft()-t.getCompoundPaddingRight();
                int height=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom();
                geometryCheck(layout.getHeight()<=height+1,name+" text fits height: "+label);
                for(int line=0;line<layout.getLineCount();line++)
                    geometryCheck(layout.getEllipsisCount(line)>0||layout.getLineMax(line)<=width+1.5f,name+" text fits line width: "+label+" ["+line+"; line="+layout.getLineMax(line)+", box="+width+"]");
            }
            if(v instanceof ViewGroup&&v!=body&&v.getBackground()!=null&&v.getWidth()>0){
                int[] a=new int[2],b=new int[2];v.getLocationOnScreen(a);body.getLocationOnScreen(b);
                geometryCheck(a[0]>=b[0]-1&&a[0]+v.getWidth()<=b[0]+body.getWidth()+1,name+" card stays inside content width");
            }
            return false;
        });
        geometryCheck(textCount[0]>0,name+" checks inspect actual rendered text");
    });}
    private void checkBackdrop(String name){runOnMainSync(()->{
        View image=walk(screen.getWindow().getDecorView(),v->"screen-backdrop".equals(v.getTag()));
        check(image instanceof ImageView&&((ImageView)image).getDrawable()!=null,name+" uses actual image backdrop");
        View parent=(View)image.getParent();check(image.getWidth()==parent.getWidth()&&image.getHeight()==parent.getHeight(),name+" backdrop fills its screen container");
    });}
    private void tab(String name){runOnMainSync(()->{View tab=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&(v.getContentDescription().toString().equals(name)||v.getContentDescription().toString().equals(name+"，已选中")));if(tab==null)throw new AssertionError("Missing bottom tab: "+name);tab.performClick();});idle();}
    private void checkTabs(){runOnMainSync(()->{for(String label:new String[]{"专注","任务","今日"}){
        View tab=walk(screen.getWindow().getDecorView(),v->v.getContentDescription()!=null&&(v.getContentDescription().toString().equals(label)||v.getContentDescription().toString().startsWith(label+"，已选中")));
        android.graphics.Rect bounds=new android.graphics.Rect();
        check(tab!=null&&tab.getGlobalVisibleRect(bounds)&&bounds.width()==tab.getWidth()&&bounds.height()==tab.getHeight(),"bottom tab fully visible: "+label);
    }});}
    private void checkSecondaryScreens()throws IOException{
        tab("任务");desc("新增任务");checkScreenFit("editor");screenshot("25-visual-editor");back();click("放弃");
        desc("调整优先级");checkScreenFit("priority");screenshot("27-visual-priority");click("完成");
        desc("英语，进度");setSeek(55);check(has("55%"),"visual progress slider remains interactive");screenshot("26-visual-progress");click("取消");
        tab("今日");desc("更多");desc("进度如何计算");checkScreenFit("weights");screenshot("28-visual-weights");back();tab("今日");
        desc("更多");checkScreenFit("more");screenshot("04-visual-more");
        desc("提醒设置");checkScreenFit("settings");screenshot("05-visual-settings");back();
        desc("本地存储与备份");checkScreenFit("backup");screenshot("29-visual-backup");back();
        desc("历史记录");checkScreenFit("history");screenshot("06-visual-history");
        runOnMainSync(()->{LinearLayout body=field("body",LinearLayout.class);View card=walk(body,v->v!=body&&v.isClickable()&&v instanceof LinearLayout);if(card==null)throw new AssertionError("Missing history card");card.performClick();});idle();
        check(has("导出这一天"),"history card opens daily detail");checkScreenFit("detail");screenshot("30-visual-detail");back();back();
        desc("导出记录");checkScreenFit("export");screenshot("07-visual-export");click("预览导出");waitFor("保存文件",60000);
        checkScreenFit("preview");screenshot("08-visual-markdown");back();tab("专注");
    }
    private void checkHomeTextFit(){runOnMainSync(()->{View home=field("body",LinearLayout.class);walk(home,v->{if(v instanceof TextView){TextView text=(TextView)v;if(text.getText().length()>0){
        android.text.Layout layout=text.getLayout();android.graphics.Paint.FontMetrics fm=text.getPaint().getFontMetrics();
        android.graphics.Rect global=new android.graphics.Rect(),glyph=new android.graphics.Rect();int[] location=new int[2];boolean visible=text.getGlobalVisibleRect(global);text.getLocationOnScreen(location);String value=text.getText().toString();text.getPaint().getTextBounds(value,0,value.length(),glyph);
        StringBuilder metrics=new StringBuilder("HOME_METRICS text=").append(value).append(" view=").append(text.getWidth()).append('x').append(text.getHeight()).append(" layoutHeight=").append(layout==null?-1:layout.getHeight()).append(" textSizePx=").append(text.getTextSize()).append(" includeFontPadding=").append(text.getIncludeFontPadding()).append(" padding=").append(text.getPaddingTop()).append(',').append(text.getPaddingBottom()).append(" compoundPadding=").append(text.getCompoundPaddingTop()).append(',').append(text.getCompoundPaddingBottom()).append(" extendedPadding=").append(text.getExtendedPaddingTop()).append(',').append(text.getExtendedPaddingBottom()).append(" totalPadding=").append(text.getTotalPaddingTop()).append(',').append(text.getTotalPaddingBottom()).append(" fontMetrics=").append(fm.top).append(',').append(fm.ascent).append(',').append(fm.descent).append(',').append(fm.bottom).append(" baseline=").append(text.getBaseline()).append(" glyphBounds=").append(glyph).append(" glyphScreenY=").append(location[1]+text.getBaseline()+glyph.top).append("..").append(location[1]+text.getBaseline()+glyph.bottom).append(" visible=").append(visible).append(" globalRect=").append(global).append(" screenOrigin=").append(location[0]).append(',').append(location[1]);
        if(layout!=null)for(int line=0;line<layout.getLineCount();line++)metrics.append(" line[").append(line).append("] top/ascent/baseline/descent/bottom=").append(layout.getLineTop(line)).append('/').append(layout.getLineAscent(line)).append('/').append(layout.getLineBaseline(line)).append('/').append(layout.getLineDescent(line)).append('/').append(layout.getLineBottom(line));
        log.append(metrics).append('\n');Bundle status=new Bundle();status.putString("stream",metrics+"\n");sendStatus(0,status);
        geometryCheck(layout!=null&&layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1,"home text fits available height: "+text.getText());
    }}return false;});});}
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
    private Object jobListener(String id){try{ExportWorker.Job job=ExportWorker.get(context).getJob(id);if(job==null)return null;Field f=ExportWorker.Job.class.getDeclaredField("listener");f.setAccessible(true);return f.get(job);}catch(Exception e){throw new RuntimeException(e);}}
    private boolean listenerCapturesActivity(Object listener){if(listener==null)return false;try{for(Field f:listener.getClass().getDeclaredFields()){f.setAccessible(true);if(f.get(listener)==screen)return true;}}catch(Exception e){throw new RuntimeException(e);}return false;}
    private android.view.accessibility.AccessibilityNodeInfo accessibilityNode(android.view.accessibility.AccessibilityNodeInfo root,java.util.function.Predicate<android.view.accessibility.AccessibilityNodeInfo> match){if(root==null)return null;if(match.test(root))return root;for(int i=0;i<root.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=accessibilityNode(root.getChild(i),match);if(child!=null)return child;}return null;}
    private boolean accessibleClick(android.view.accessibility.AccessibilityNodeInfo node){while(node!=null&&!node.isClickable())node=node.getParent();return node!=null&&node.isEnabled()&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);}
    private void saveThroughSystemPicker()throws IOException{
        long began=SystemClock.elapsedRealtime(),end=began+90000,stableSince=0;String filename="shiben-qa-"+SystemClock.uptimeMillis()+".md";boolean named=false,drawerOpened=false,downloadsChosen=false;
        while(SystemClock.elapsedRealtime()<end){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&"com.android.documentsui".contentEquals(root.getPackageName())){
                android.view.accessibility.AccessibilityNodeInfo field=accessibilityNode(root,n->"android.widget.EditText".contentEquals(n.getClassName()));
                if(field!=null&&!filename.contentEquals(field.getText()==null?"":field.getText())){Bundle value=new Bundle();value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename);if(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,value)){if(!named)check(true,"native SAF filename set for isolated test export");named=true;SystemClock.sleep(300);continue;}}
                android.view.accessibility.AccessibilityNodeInfo save=accessibilityNode(root,n->n.getText()!=null&&"SAVE".equalsIgnoreCase(n.getText().toString()));
                android.view.accessibility.AccessibilityNodeInfo toolbar=accessibilityNode(root,n->n.getText()!=null&&"Downloads".contentEquals(n.getText())&&(n.getViewIdResourceName()==null||n.getViewIdResourceName().isEmpty()));
                android.view.accessibility.AccessibilityNodeInfo drawerDownloads=accessibilityNode(root,n->n.getText()!=null&&"Downloads".contentEquals(n.getText())&&n.isVisibleToUser()&&n.getViewIdResourceName()!=null&&n.getViewIdResourceName().endsWith(":id/title"));
                boolean writable=toolbar!=null&&drawerDownloads==null&&save!=null&&save.isEnabled()&&save.isVisibleToUser();
                // Do not choose the same root while DocumentsUI's initial async root load is pending.
                // AOSP29 can otherwise push the same root twice and crash in DocumentStack.push.
                if(writable){
                    if(stableSince==0)stableSince=SystemClock.elapsedRealtime();
                    if(named&&SystemClock.elapsedRealtime()-stableSince>=1800){
                        check(true,"native system Downloads confirmed stable and writable");
                        if(!accessibleClick(save))throw new AssertionError("Stable native SAVE button rejected the click");
                        check(true,"native system Save action dispatched");File folder=new File(context.getFilesDir(),"qa-v2");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,"saf-filename.txt"))){out.write(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));}return;
                    }
                }else stableSince=0;
                // Only use navigation if the initial location did not become writable on its own.
                if(!writable&&SystemClock.elapsedRealtime()-began>30000&&!downloadsChosen){
                    if(drawerDownloads!=null&&accessibleClick(drawerDownloads)){downloadsChosen=true;SystemClock.sleep(1000);continue;}
                    if(!drawerOpened){android.view.accessibility.AccessibilityNodeInfo drawer=accessibilityNode(root,n->n.getContentDescription()!=null&&(n.getContentDescription().toString().contains("Show roots")||n.getContentDescription().toString().contains("Show navigation drawer")));if(drawer!=null&&accessibleClick(drawer)){drawerOpened=true;SystemClock.sleep(500);continue;}}
                }
            }
            SystemClock.sleep(250);
        }
        throw new AssertionError("Native DocumentsUI Downloads destination and enabled SAVE button were not stable");
    }
    private void awaitAppFocus(){long end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("System UI isn’t responding").isEmpty()||root!=null&&!root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()){java.util.List<android.view.accessibility.AccessibilityNodeInfo> waits=root.findAccessibilityNodeInfosByText("Wait");if(!waits.isEmpty()){android.view.accessibility.AccessibilityNodeInfo n=waits.get(0);while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null){n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);log.append("ENV: waited for software emulator System UI ANR\n");}}}final boolean[] focused={false};runOnMainSync(()->{for(View own:rootViews())if(own.isShown()&&own.hasWindowFocus())focused[0]=true;});if(focused[0])return;SystemClock.sleep(300);}throw new AssertionError("Application did not regain foreground focus after emulator System UI interruption");}
    private ClipData readClipboard(){awaitAppFocus();final ClipData[] data={null};runOnMainSync(()->data[0]=((ClipboardManager)screen.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip());if(data[0]==null)throw new AssertionError("Clipboard is unavailable after app regained focus");return data[0];}
    private void screenshot(String name)throws IOException{idle();if(!name.startsWith("99"))awaitAppFocus();Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("Screenshot unavailable");File folder=new File(context.getFilesDir(),"qa-v2");folder.mkdirs();File output=new File(folder,name+".png"),temporary=new File(folder,name+".png.tmp");try(FileOutputStream out=new FileOutputStream(temporary)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();java.nio.file.Files.move(temporary.toPath(),output.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
}
