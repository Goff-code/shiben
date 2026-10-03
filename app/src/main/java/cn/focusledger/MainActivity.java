package cn.focusledger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import cn.focusledger.core.DailyTotals;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A dependency-free, local-only native interface. Stored records are never demo data. */
public final class MainActivity extends Activity {
    private static final int PAPER = Color.rgb(246,244,239), INK = Color.rgb(23,44,43),
            JADE = Color.rgb(35,105,90), MUTED = Color.rgb(100,118,111),
            LINE = Color.rgb(223,229,222), PALE = Color.rgb(231,239,231),
            AMBER = Color.rgb(153,101,46), RED = Color.rgb(165,66,52);
    private static final int REQUEST_EXPORT = 401, REQUEST_NOTIFICATIONS = 402;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter INPUT_CLOCK = DateTimeFormatter.ofPattern("HH:mm[:ss]", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TimerCoordinator coordinator;
    private LedgerStore store;
    private LinearLayout root, body, tabs;
    private ScrollView scroll;
    private LocalDate selectedDate = LocalDate.now();
    private int selectedTab = 0;
    private boolean weekView;
    private boolean hasResumed;
    private final int[] scrollPositions = new int[3];
    private TextView timerClock, timerStatus, timerElapsed;
    private TimerDial timerDial;
    private String renderedTimerId;
    private boolean renderedPaused;
    private String pendingExport, pendingExportKind;
    private AlertDialog editingDialog;
    private String editingKind, editingId;
    private java.util.function.Supplier<Bundle> editingDraft;
    private Bundle pendingDialogDraft;
    private String pendingDialogKind, pendingDialogId;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                Session completed = coordinator.refresh();
                TimerEngine.State state = coordinator.engine().snapshot();
                String nowId = state == null ? null : state.sessionId;
                boolean paused = state != null && state.paused;
                if (completed != null || !same(renderedTimerId, nowId) || renderedPaused != paused) {
                    render(true);
                    if (completed != null) toast("本次专注已记录。任务是否完成，由你勾选。");
                } else updateTimer();
            } catch (RuntimeException ex) { /* A later resume can retry; dialogs stay open. */ }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        coordinator = TimerCoordinator.get(this);
        store = coordinator.store();
        if (state != null) {
            try { selectedDate = LocalDate.parse(state.getString("date", LocalDate.now().toString()), DATE); } catch (RuntimeException ignored) { }
            selectedTab = state.getInt("tab", 0);
            weekView = state.getBoolean("week", false);
            pendingExportKind = state.getString("exportKind");
            pendingDialogKind = state.getString("dialogKind"); pendingDialogId = state.getString("dialogId"); pendingDialogDraft = state.getBundle("dialogDraft");
            int[] saved = state.getIntArray("scroll");
            if (saved != null && saved.length == 3) System.arraycopy(saved, 0, scrollPositions, 0, 3);
        }
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = column();
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                root.setPadding(system.left, system.top, system.right, system.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        render(false);
        if (pendingDialogKind != null) root.post(this::restoreDialog);
    }

    @Override protected void onResume() {
        super.onResume();
        if (coordinator != null) {
            try { coordinator.restoreFromForeground(); } catch (RuntimeException ex) { toast("计时恢复暂时失败，请稍后重试。"); }
            render(hasResumed);
            hasResumed=true;
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
    }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (scroll != null) scrollPositions[selectedTab] = scroll.getScrollY();
        state.putString("date", selectedDate.toString());
        state.putInt("tab", selectedTab); state.putBoolean("week", weekView);
        state.putIntArray("scroll", scrollPositions);
        state.putString("exportKind", pendingExportKind);
        if (editingDialog != null && editingDialog.isShowing() && editingDraft != null) {
            state.putString("dialogKind", editingKind); state.putString("dialogId", editingId); state.putBundle("dialogDraft", editingDraft.get());
        }
        super.onSaveInstanceState(state);
    }

    private void render(boolean keepScroll) {
        if (root == null) return;
        if (keepScroll && scroll != null) scrollPositions[selectedTab] = scroll.getScrollY();
        root.removeAllViews(); timerClock = null; timerStatus = null; timerElapsed = null; timerDial = null;
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(dp(22), dp(20), dp(22), dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout brand = row(); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = label("▤", 21, JADE, true); logo.setContentDescription("专注账本标志"); logo.setGravity(Gravity.CENTER);
        logo.setBackground(shape(PALE, 12)); brand.addView(logo, new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView brandText = label("专注账本", 16, INK, true); brandText.setPadding(dp(10),0,0,0); brand.addView(brandText);
        TextView privateTag = label("本地 · 私人", 11, MUTED, false); privateTag.setGravity(Gravity.END);
        brand.addView(privateTag, new LinearLayout.LayoutParams(0,-2,1)); body.addView(brand);
        gap(body,22);
        if (selectedTab == 0) renderToday(); else if (selectedTab == 1) renderStatistics(); else renderSettings();
        buildTabs();
        TimerEngine.State state = coordinator.engine().snapshot();
        renderedTimerId = state == null ? null : state.sessionId; renderedPaused = state != null && state.paused;
        updateTimer();
        scroll.post(() -> scroll.scrollTo(0,scrollPositions[selectedTab]));
    }

    private void buildTabs() {
        View divider = new View(this); divider.setBackgroundColor(LINE); root.addView(divider, new LinearLayout.LayoutParams(-1,dp(1)));
        tabs = row(); tabs.setBackgroundColor(PAPER); tabs.setPadding(dp(10),dp(6),dp(10),dp(6));
        String[] names = {"今日", "统计", "设置"}; String[] glyphs = {"◷", "▥", "⚙"};
        for (int i=0;i<3;i++) {
            final int target=i;
            LinearLayout cell = column(); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(4),dp(7),dp(4),dp(7));
            cell.setMinimumHeight(dp(58)); cell.setClickable(true); cell.setFocusable(true);
            cell.setContentDescription(names[i] + (i==selectedTab ? "，已选中" : "，切换页面"));
            cell.setBackground(shape(i==selectedTab?PALE:PAPER,16));
            cell.addView(label(glyphs[i],21,i==selectedTab?JADE:MUTED,false));
            cell.addView(label(names[i],12,i==selectedTab?JADE:MUTED,i==selectedTab));
            cell.setOnClickListener(v -> switchTab(target));
            tabs.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
    }
    private void switchTab(int tab) {
        if (scroll != null) scrollPositions[selectedTab]=scroll.getScrollY();
        selectedTab=tab; render(false);
    }

    private void renderToday() {
        body.addView(label("把时间，交给重要的事",25,INK,true)); gap(body,7);
        body.addView(label("一段专注，一笔真实的投入。",13,MUTED,false)); gap(body,18);
        addDateNavigation(false);
        DayStats today = aggregate(selectedDate,selectedDate);
        LinearLayout summary = card(Color.WHITE);
        summary.addView(label(selectedDate.equals(LocalDate.now())?"今天 · 已记录":"当天 · 已记录",12,MUTED,false)); gap(summary,9);
        LinearLayout numbers=row(); numbers.setGravity(Gravity.CENTER_VERTICAL);
        TextView total=label(duration(today.total),32,INK,true); numbers.addView(total,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout count=column(); count.setGravity(Gravity.END); count.addView(label(String.valueOf(today.count),26,JADE,true)); count.addView(label("次专注",12,MUTED,false)); numbers.addView(count);
        summary.addView(numbers); gap(summary,12);
        summary.addView(label("只统计实际投入，暂停时间不计入。",12,MUTED,false)); addCard(summary);
        if (!getPreferences(MODE_PRIVATE).getBoolean("introDismissed",false) && !coordinator.notificationsGranted()) {
            LinearLayout intro=card(PALE);
            intro.addView(label("让到点提醒更可靠",16,JADE,true)); gap(intro,6);
            intro.addView(label("允许通知后，锁屏也能看到倒计时。你也可以稍后再设置。",13,INK,false)); gap(intro,10);
            LinearLayout actions=row(); Button setup=button("设置提醒",true); setup.setOnClickListener(v->switchTab(2)); actions.addView(setup,new LinearLayout.LayoutParams(0,-2,1));
            gapHorizontal(actions,8); Button later=button("稍后",false); later.setOnClickListener(v->{getPreferences(MODE_PRIVATE).edit().putBoolean("introDismissed",true).apply();render(true);}); actions.addView(later,new LinearLayout.LayoutParams(0,-2,1));intro.addView(actions);addCard(intro);
        }
        if (coordinator.engine().isActive()) addTimerCard();
        LinearLayout heading=row(); heading.setGravity(Gravity.CENTER_VERTICAL); heading.addView(label("当天任务",20,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button add=button("＋ 添加",false); add.setContentDescription("添加专注任务"); add.setOnClickListener(v->showTaskDialog(null)); heading.addView(add); body.addView(heading); gap(body,10);
        List<LedgerStore.Task> tasks=store.getTasks(selectedDate);
        if (tasks.isEmpty()) {
            LinearLayout empty=card(Color.WHITE); empty.addView(label("留一点时间给自己",19,INK,true)); gap(empty,8);
            empty.addView(label("添加一个任务，再开始第一段专注。也可以创建每周重复的日常计划。",14,MUTED,false)); gap(empty,15);
            Button first=button("添加我的任务",true); first.setOnClickListener(v->showTaskDialog(null)); empty.addView(first); addCard(empty);
        } else for (LedgerStore.Task task:tasks) addTaskCard(task);
        Button manage=button("查看全部任务",false); manage.setOnClickListener(v->showAllTasks()); body.addView(manage);
        gap(body,8); body.addView(label("勾选表示你认为任务完成；倒计时结束不会自动勾选。",12,MUTED,false));
    }

    private void addDateNavigation(boolean stats) {
        LinearLayout nav=row(); nav.setGravity(Gravity.CENTER_VERTICAL);
        Button previous=button("‹",false); previous.setTextSize(25); previous.setContentDescription(stats&&weekView?"上一周":"前一天");
        previous.setOnClickListener(v->{selectedDate=selectedDate.minusDays(stats&&weekView?7:1);scrollPositions[selectedTab]=0;render(false);});
        nav.addView(previous,new LinearLayout.LayoutParams(dp(48),-2));
        Button date=button(dateLabel(selectedDate),false); date.setContentDescription("选择日期，当前"+selectedDate);
        date.setOnClickListener(v->new DatePickerDialog(this,(picker,y,m,d)->{selectedDate=LocalDate.of(y,m+1,d);scrollPositions[selectedTab]=0;render(false);},selectedDate.getYear(),selectedDate.getMonthValue()-1,selectedDate.getDayOfMonth()).show());
        nav.addView(date,new LinearLayout.LayoutParams(0,-2,1));
        Button next=button("›",false); next.setTextSize(25); next.setContentDescription(stats&&weekView?"下一周":"后一天");next.setOnClickListener(v->{selectedDate=selectedDate.plusDays(stats&&weekView?7:1);scrollPositions[selectedTab]=0;render(false);});nav.addView(next,new LinearLayout.LayoutParams(dp(48),-2));body.addView(nav);
        if (!selectedDate.equals(LocalDate.now())) { Button now=button("回到今天",false);now.setOnClickListener(v->{selectedDate=LocalDate.now();render(true);});body.addView(now); }
        gap(body,12);
    }

    private void addTaskCard(LedgerStore.Task task) {
        LinearLayout taskCard=card(Color.WHITE);
        LinearLayout titleRow=row(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox done=new CheckBox(this); done.setButtonTintList(android.content.res.ColorStateList.valueOf(JADE));done.setMinimumWidth(dp(48));done.setMinimumHeight(dp(48));done.setContentDescription("标记任务完成："+task.title);done.setChecked(store.taskDone(task.id,selectedDate));
        done.setOnCheckedChangeListener((v,checked)->{store.setTaskDone(task.id,selectedDate,checked);render(true);});titleRow.addView(done,new LinearLayout.LayoutParams(dp(48),-2));
        LinearLayout words=column();TextView title=label(task.title,17,INK,true); if(done.isChecked()) title.setPaintFlags(title.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);words.addView(title);gap(words,4);
        LedgerStore.Project project=store.getProject(task.projectId);words.addView(label(projectName(task.projectId)+"  ·  "+task.plannedMinutes+" 分钟",12,project==null?MUTED:project.color,false));titleRow.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=button("⋯",false);menu.setTextSize(24);menu.setContentDescription("编辑或排序任务："+task.title);menu.setOnClickListener(v->taskMenu(task));titleRow.addView(menu,new LinearLayout.LayoutParams(dp(48),-2));taskCard.addView(titleRow);gap(taskCard,9);
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER_VERTICAL);TextView repeat=label(scheduleLabel(task),11,MUTED,false);controls.addView(repeat,new LinearLayout.LayoutParams(0,-2,1));
        Button start=button("开始专注",true);start.setTextSize(13);start.setContentDescription("开始专注："+task.title);start.setOnClickListener(v->showDurationDialog(task));controls.addView(start);taskCard.addView(controls);addCard(taskCard);
    }

    private void addTimerCard() {
        TimerEngine.State state=coordinator.engine().snapshot();if(state==null)return;
        LinearLayout card=card(INK);TextView tag=label(state.paused?"已暂停":"正在专注",12,Color.rgb(171,208,184),true);card.addView(tag);gap(card,5);card.addView(label(state.taskTitle,20,PAPER,true));
        FrameLayout dial=new FrameLayout(this);timerDial=new TimerDial(this);timerDial.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);dial.addView(timerDial,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout center=column();center.setGravity(Gravity.CENTER);timerClock=label("",40,PAPER,true);timerClock.setGravity(Gravity.CENTER);timerClock.setContentDescription("剩余时间");center.addView(timerClock);timerStatus=label("",12,Color.rgb(171,208,184),false);center.addView(timerStatus);dial.addView(center,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams dialParams=new LinearLayout.LayoutParams(dp(210),dp(210));dialParams.gravity=Gravity.CENTER_HORIZONTAL;card.addView(dial,dialParams);
        timerElapsed=label("",12,Color.rgb(200,217,205),false);timerElapsed.setGravity(Gravity.CENTER);card.addView(timerElapsed);gap(card,14);
        LinearLayout controls=row();Button pause=button(state.paused?"继续专注":"暂停",true);pause.setOnClickListener(v->{try{if(coordinator.engine().isPaused())coordinator.resume();else coordinator.pause();render(true);}catch(RuntimeException ex){error(ex);}});controls.addView(pause,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(controls,10);
        Button finish=button("结束并记录",false);finish.setBackground(shape(Color.rgb(48,68,63),14));finish.setTextColor(PAPER);finish.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("结束这段专注？").setMessage("会记录已经投入的时间。任务完成状态仍由你勾选。").setNegativeButton("继续保留",null).setPositiveButton("结束并记录",(d,w)->{try{coordinator.finish();render(true);toast("本次实际投入已记录");}catch(RuntimeException ex){error(ex);}}).show());controls.addView(finish,new LinearLayout.LayoutParams(0,-2,1));card.addView(controls);gap(card,12);card.addView(label("暂停时不计时。离开应用后，计时仍会继续。",11,Color.rgb(200,217,205),false));if(!coordinator.warning().isEmpty()){gap(card,8);card.addView(label(coordinator.warning(),12,Color.rgb(232,206,150),false));}addCard(card);
    }

    private void updateTimer() {
        if(timerClock==null)return;TimerEngine.State state=coordinator.engine().snapshot();if(state==null)return;
        long remaining=Math.max(0,coordinator.remainingMs()),actual=Math.max(0,coordinator.actualMs());
        timerClock.setText(countdown(remaining));timerClock.setContentDescription("剩余"+duration(remaining));
        timerStatus.setText(state.paused?"暂停中 · 时间不会增加":"距离本段结束");timerElapsed.setText("已投入 "+duration(actual)+"  /  计划 "+duration(state.plannedMs));
        if(timerDial!=null)timerDial.setProgress(state.plannedMs<=0?0:Math.min(1f,(float)actual/state.plannedMs));
    }

    private void showDurationDialog(LedgerStore.Task task) {
        if(coordinator.engine().isActive()){toast("已有一段专注进行中，请先结束再开始新任务。");if(selectedTab!=0)switchTab(0);return;}
        Bundle draft=takeDialogDraft("duration",task.id);LinearLayout form=dialogForm();form.addView(label(task.title,18,INK,true));gap(form,8);form.addView(label("至少 15 分钟，以 5 分钟递增；也可直接输入。",13,MUTED,false));gap(form,12);
        LinearLayout row=row();row.setGravity(Gravity.CENTER_VERTICAL);Button minus=button("−5",false);EditText minutes=input("分钟",draft==null?String.valueOf(Math.max(15,task.plannedMinutes)):draft.getString("minutes"),InputType.TYPE_CLASS_NUMBER);minutes.setGravity(Gravity.CENTER);Button plus=button("＋5",false);row.addView(minus,new LinearLayout.LayoutParams(dp(64),-2));row.addView(minutes,new LinearLayout.LayoutParams(0,-2,1));row.addView(plus,new LinearLayout.LayoutParams(dp(64),-2));form.addView(row);
        minus.setOnClickListener(v->{try{minutes.setText(String.valueOf(Math.max(15,Integer.parseInt(minutes.getText().toString())-5)));}catch(NumberFormatException ex){minutes.setText("15");}});plus.setOnClickListener(v->{try{long n=Long.parseLong(minutes.getText().toString());minutes.setText(String.valueOf(Math.min(2147483645L,n+5)));}catch(NumberFormatException ex){minutes.setText("25");}});
        gap(form,12);LinearLayout presets=row();for(int p:new int[]{25,45,60}){Button b=button(p+" 分钟",false);b.setOnClickListener(v->minutes.setText(String.valueOf(p)));presets.addView(b,new LinearLayout.LayoutParams(0,-2,1));}form.addView(presets);gap(form,12);form.addView(label("计时结束只记录时间，不自动标记任务完成。",12,MUTED,false));
        AlertDialog dialog=dialog("开始一段专注",form,"开始",()->{});dialog.setOnShowListener(v->{styleDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{int n;try{n=Integer.parseInt(minutes.getText().toString().trim());if(n<15||n%5!=0)throw new NumberFormatException();}catch(NumberFormatException ex){minutes.setError("请输入至少 15 的分钟数，且为 5 的倍数");return;}try{coordinator.start(task,n);dialog.dismiss();selectedDate=LocalDate.now();scrollPositions[0]=0;switchTab(0);}catch(RuntimeException ex){error(ex);}});});rememberDialog(dialog,"duration",task.id,()->{Bundle b=new Bundle();b.putString("minutes",minutes.getText().toString());return b;});dialog.show();
    }

    private void taskMenu(LedgerStore.Task task) {
        new AlertDialog.Builder(this).setTitle(task.title).setItems(new String[]{"编辑任务","上移","下移","删除任务"},(d,i)->{if(i==0)showTaskDialog(task);else if(i==1||i==2){store.moveTask(task.id,i==1?-1:1);render(true);}else new AlertDialog.Builder(this).setTitle("删除任务？").setMessage("任务会从计划中移除，已有专注记录会保留。").setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->{store.deleteTask(task.id);render(true);}).show();}).show();
    }
    private void showAllTasks() {
        List<LedgerStore.Task> list=new ArrayList<>();for(LedgerStore.Task t:store.getAllTasks())if(!t.archived)list.add(t);
        if(list.isEmpty()){showTaskDialog(null);return;}String[] names=new String[list.size()];for(int i=0;i<list.size();i++)names[i]=list.get(i).title+"\n"+scheduleLabel(list.get(i));new AlertDialog.Builder(this).setTitle("全部任务").setItems(names,(d,i)->taskMenu(list.get(i))).setNegativeButton("关闭",null).setPositiveButton("添加",(d,w)->showTaskDialog(null)).show();
    }

    private void showTaskDialog(LedgerStore.Task existing) {
        List<LedgerStore.Project> projects=activeProjects();if(projects.isEmpty()){toast("先添加一个分类，再创建任务。");showProjectDialog(null);return;}
        Bundle draft=takeDialogDraft("task",existing==null?null:existing.id);LinearLayout form=dialogForm();EditText title=input("任务名称",draft!=null?draft.getString("title"):existing==null?"":existing.title,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);form.addView(fieldLabel("任务"));form.addView(title);gap(form,14);
        form.addView(fieldLabel("所属分类"));Spinner project=projectSpinner(projects,draft!=null?draft.getString("project"):existing==null?null:existing.projectId);form.addView(project);gap(form,14);form.addView(fieldLabel("默认时长（分钟）"));EditText minutes=input("至少 15 分钟",draft!=null?draft.getString("minutes"):String.valueOf(existing==null?25:existing.plannedMinutes),InputType.TYPE_CLASS_NUMBER);form.addView(minutes);gap(form,14);
        RadioGroup mode=new RadioGroup(this);mode.setOrientation(LinearLayout.HORIZONTAL);RadioButton once=new RadioButton(this);once.setId(View.generateViewId());once.setText("一次性");once.setTextColor(INK);once.setMinHeight(dp(48));RadioButton repeated=new RadioButton(this);repeated.setId(View.generateViewId());repeated.setText("每周重复");repeated.setTextColor(INK);repeated.setMinHeight(dp(48));mode.addView(once,new RadioGroup.LayoutParams(0,-2,1));mode.addView(repeated,new RadioGroup.LayoutParams(0,-2,1));form.addView(mode);
        LinearLayout dateRow=row();EditText date=input("yyyy-MM-dd",draft!=null?draft.getString("date"):existing!=null&&existing.oneOffDate!=null?existing.oneOffDate:selectedDate.toString(),InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_DATE);dateRow.addView(date,new LinearLayout.LayoutParams(0,-2,1));Button choose=button("选日期",false);choose.setOnClickListener(v->pickDate(date));dateRow.addView(choose);form.addView(dateRow);
        LinearLayout weekdayForm=column();CheckBox[] days=new CheckBox[7];String[] dayNames={"周一","周二","周三","周四","周五","周六","周日"};for(int group=0;group<2;group++){LinearLayout r=row();int from=group*4,to=Math.min(7,from+4);for(int i=from;i<to;i++){days[i]=new CheckBox(this);days[i].setText(dayNames[i]);days[i].setTextSize(12);days[i].setTextColor(INK);days[i].setMinHeight(dp(48));days[i].setButtonTintList(android.content.res.ColorStateList.valueOf(JADE));days[i].setChecked(draft!=null?(draft.getInt("mask")&(1<<i))!=0:existing!=null?(existing.weekMask&(1<<i))!=0:i<5);r.addView(days[i],new LinearLayout.LayoutParams(0,-2,1));}weekdayForm.addView(r);}form.addView(weekdayForm);
        mode.setOnCheckedChangeListener((g,id)->{dateRow.setVisibility(id==once.getId()?View.VISIBLE:View.GONE);weekdayForm.setVisibility(id==repeated.getId()?View.VISIBLE:View.GONE);});mode.check((draft!=null?draft.getBoolean("repeat"):existing!=null&&existing.oneOffDate==null)?repeated.getId():once.getId());gap(form,8);form.addView(label("任务按你安排的顺序显示，可在任务菜单中上移、下移。",12,MUTED,false));
        AlertDialog dialog=dialog(existing==null?"添加任务":"编辑任务",form,"保存",()->{});dialog.setOnShowListener(v->{styleDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{String value=title.getText().toString().trim();if(value.isEmpty()){title.setError("给任务起个名字");return;}int n;try{n=Integer.parseInt(minutes.getText().toString().trim());if(n<15||n%5!=0)throw new NumberFormatException();}catch(NumberFormatException ex){minutes.setError("请输入至少 15 的分钟数，且为 5 的倍数");return;}String dateString=null;int mask=0;if(mode.getCheckedRadioButtonId()==once.getId()){try{dateString=LocalDate.parse(date.getText().toString().trim(),DATE).toString();}catch(RuntimeException ex){date.setError("日期格式为 yyyy-MM-dd，例如 2026-10-03");return;}}else{for(int i=0;i<7;i++)if(days[i].isChecked())mask|=1<<i;if(mask==0){toast("至少选择一个重复的星期");return;}}LedgerStore.Task task=new LedgerStore.Task();task.id=existing==null?UUID.randomUUID().toString():existing.id;task.title=value;task.projectId=projects.get(project.getSelectedItemPosition()).id;task.plannedMinutes=n;task.sort=existing==null?store.getAllTasks().size():existing.sort;task.weekMask=mask;task.oneOffDate=dateString;task.archived=false;try{store.saveTask(task);dialog.dismiss();render(true);}catch(RuntimeException ex){error(ex);}});});rememberDialog(dialog,"task",existing==null?null:existing.id,()->{Bundle b=new Bundle();b.putString("title",title.getText().toString());b.putString("project",projects.get(project.getSelectedItemPosition()).id);b.putString("minutes",minutes.getText().toString());b.putString("date",date.getText().toString());b.putBoolean("repeat",mode.getCheckedRadioButtonId()==repeated.getId());int mask=0;for(int i=0;i<7;i++)if(days[i].isChecked())mask|=1<<i;b.putInt("mask",mask);return b;});dialog.show();
    }

    private void renderStatistics() {
        body.addView(label("每一段投入，都有迹可循",25,INK,true));gap(body,8);body.addView(label("用真实时长，看见时间的去向。",13,MUTED,false));gap(body,18);
        LinearLayout period=row();Button day=button("当天",!weekView),week=button("本周",weekView);day.setOnClickListener(v->{weekView=false;render(true);});week.setOnClickListener(v->{weekView=true;render(true);});period.addView(day,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(period,8);period.addView(week,new LinearLayout.LayoutParams(0,-2,1));body.addView(period);gap(body,8);addDateNavigation(true);
        LocalDate from=weekView?selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)):selectedDate,to=weekView?from.plusDays(6):selectedDate;
        DayStats stats=aggregate(from,to);LinearLayout summary=card(INK);summary.addView(label(weekView?from+" 至 "+to:dateLabel(from),12,Color.rgb(186,212,193),false));gap(summary,12);summary.addView(label(duration(stats.total),36,PAPER,true));gap(summary,8);summary.addView(label("实际投入  ·  "+stats.count+" 次专注",13,Color.rgb(200,217,205),false));if(coordinator.engine().isActive()){gap(summary,10);summary.addView(label("进行中的一段，结束后计入统计。",11,Color.rgb(200,217,205),false));}addCard(summary);
        LinearLayout categories=card(Color.WHITE);categories.addView(label("分类投入",19,INK,true));gap(categories,16);
        if(stats.total==0)categories.addView(label("还没有已记录的时间。开始一段专注，或补记一次投入。",14,MUTED,false));else for(Map.Entry<String,Long> entry:stats.byProject.entrySet()){
            LedgerStore.Project project=store.getProject(entry.getKey());int color=project==null?JADE:project.color;float share=(float)entry.getValue()/stats.total;
            LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);TextView name=label(projectName(entry.getKey()),14,INK,true);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));line.addView(label(duration(entry.getValue())+"  ·  "+Math.round(100*share)+"%",12,MUTED,false));categories.addView(line);gap(categories,7);
            LinearLayout track=row();track.setBackground(shape(PALE,4));View fill=new View(this);fill.setBackground(shape(color,4));track.addView(fill,new LinearLayout.LayoutParams(0,dp(8),Math.max(0.001f,share)));View rest=new View(this);track.addView(rest,new LinearLayout.LayoutParams(0,dp(8),Math.max(0.001f,1-share)));track.setContentDescription(projectName(entry.getKey())+"，"+duration(entry.getValue())+"，占比"+Math.round(100*share)+"% ");categories.addView(track);gap(categories,17);
        }addCard(categories);
        LinearLayout actions=row();Button add=button("＋ 补记专注",true);add.setOnClickListener(v->showSessionDialog(null));actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(actions,8);Button copy=button("复制当天 Markdown",false);copy.setTextSize(12);copy.setOnClickListener(v->copyDaily());actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));body.addView(actions);gap(body,22);
        body.addView(label("专注明细",20,INK,true));gap(body,8);body.addView(label("点击记录可修改或删除。跨午夜的投入按记录时区拆到各日。",12,MUTED,false));gap(body,12);
        if(stats.sessions.isEmpty()){LinearLayout empty=card(Color.WHITE);empty.addView(label("这一页，等待你的投入",17,INK,true));gap(empty,8);empty.addView(label("不需要补上漂亮数字，只记下真正发生的时间。",13,MUTED,false));addCard(empty);}else for(Session session:stats.sessions)addSessionCard(session,stats.sessionTotals.get(session.id));
    }

    private DayStats aggregate(LocalDate from,LocalDate to) {
        DayStats result=new DayStats();for(Session session:store.getSessions()){long total=0;for(Map.Entry<LocalDate,Long> e:DailyTotals.totalsByDay(session).entrySet())if(!e.getKey().isBefore(from)&&!e.getKey().isAfter(to))total+=e.getValue();boolean zeroInPeriod=session.actualMs==0&&!Instant.ofEpochMilli(session.startedWallMs).atZone(safeZone(session.zoneId)).toLocalDate().isBefore(from)&&!Instant.ofEpochMilli(session.startedWallMs).atZone(safeZone(session.zoneId)).toLocalDate().isAfter(to);if(total<=0&&!zeroInPeriod)continue;result.total+=total;result.count++;result.sessions.add(session);result.sessionTotals.put(session.id,total);Long old=result.byProject.get(session.projectId);result.byProject.put(session.projectId,(old==null?0:old)+total);}return result;
    }
    private static final class DayStats { long total;int count;final Map<String,Long> byProject=new LinkedHashMap<>(),sessionTotals=new LinkedHashMap<>();final List<Session> sessions=new ArrayList<>(); }

    private void addSessionCard(Session session,Long inPeriod) {
        LinearLayout card=card(Color.WHITE);LinearLayout top=row();top.setGravity(Gravity.TOP);LinearLayout words=column();words.addView(label(session.taskTitle,16,INK,true));gap(words,4);words.addView(label(projectName(session.projectId),12,MUTED,false));top.addView(words,new LinearLayout.LayoutParams(0,-2,1));top.addView(label(duration(inPeriod==null?session.actualMs:inPeriod),16,JADE,true));card.addView(top);gap(card,10);
        ZoneId zone=safeZone(session.zoneId);LocalDateTime start=Instant.ofEpochMilli(session.startedWallMs).atZone(zone).toLocalDateTime(),end=Instant.ofEpochMilli(session.endedWallMs).atZone(zone).toLocalDateTime();String range=start.toLocalDate()+"  "+start.toLocalTime().format(CLOCK)+"–"+(start.toLocalDate().equals(end.toLocalDate())?"":end.toLocalDate()+" ")+end.toLocalTime().format(CLOCK);card.addView(label(range,12,MUTED,false));gap(card,6);
        String flags=session.manual?"手动补记":"倒计时记录";if(session.edited)flags+=" · 已修改";flags+=" · "+reasonLabel(session.reason);card.addView(label(flags,11,MUTED,false));card.setClickable(true);card.setFocusable(true);card.setContentDescription(session.taskTitle+"，"+duration(session.actualMs)+"，"+range+"，"+flags+"，点击编辑记录");card.setOnClickListener(v->showSessionMenu(session));addCard(card);
    }
    private void showSessionMenu(Session session) {new AlertDialog.Builder(this).setTitle(session.taskTitle).setItems(new String[]{"修改这条记录","删除这条记录"},(d,i)->{if(i==0)showSessionDialog(session);else new AlertDialog.Builder(this).setTitle("删除专注记录？").setMessage("这条时间记录将从统计中移除。此操作无法撤销。").setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->{store.deleteSession(session.id);render(true);}).show();}).show();}

    private void showSessionDialog(Session original) {
        List<LedgerStore.Project> projects=activeProjects();
        if(original!=null){LedgerStore.Project old=store.getProject(original.projectId);if(old!=null&&old.archived)projects.add(old);}
        if(projects.isEmpty()){toast("请先添加分类。");showProjectDialog(null);return;}ZoneId zone=original==null?ZoneId.systemDefault():safeZone(original.zoneId);LocalDateTime initial=original==null?LocalDateTime.of(selectedDate,LocalTime.now().withSecond(0).withNano(0)):Instant.ofEpochMilli(original.startedWallMs).atZone(zone).toLocalDateTime();
        Bundle draft=takeDialogDraft("session",original==null?null:original.id);LinearLayout form=dialogForm();form.addView(fieldLabel("这段时间做了什么"));EditText title=input("专注事项",draft!=null?draft.getString("title"):original==null?"":original.taskTitle,InputType.TYPE_CLASS_TEXT);form.addView(title);gap(form,14);form.addView(fieldLabel("分类"));Spinner project=projectSpinner(projects,draft!=null?draft.getString("project"):original==null?null:original.projectId);form.addView(project);gap(form,14);
        form.addView(fieldLabel("开始日期"));LinearLayout dateRow=row();EditText date=input("yyyy-MM-dd",draft!=null?draft.getString("date"):initial.toLocalDate().toString(),InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_DATE);dateRow.addView(date,new LinearLayout.LayoutParams(0,-2,1));Button chooseDate=button("选日期",false);chooseDate.setOnClickListener(v->pickDate(date));dateRow.addView(chooseDate);form.addView(dateRow);gap(form,14);
        form.addView(fieldLabel("开始时间（24 小时制）"));LinearLayout timeRow=row();EditText time=input("HH:mm 或 HH:mm:ss",draft!=null?draft.getString("time"):initial.toLocalTime().format(INPUT_CLOCK),InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_TIME);timeRow.addView(time,new LinearLayout.LayoutParams(0,-2,1));Button chooseTime=button("选时间",false);chooseTime.setOnClickListener(v->{LocalTime value;try{value=LocalTime.parse(time.getText().toString(),INPUT_CLOCK);}catch(RuntimeException ex){value=LocalTime.now();}new TimePickerDialog(this,(p,h,m)->time.setText(String.format(Locale.ROOT,"%02d:%02d",h,m)),value.getHour(),value.getMinute(),true).show();});timeRow.addView(chooseTime);form.addView(timeRow);gap(form,14);
        form.addView(fieldLabel("实际投入时长（分钟，可用小数）"));String initialMinutes=original==null?"25":String.format(Locale.ROOT,"%.6f",original.actualMs/60000d).replaceAll("0+$","").replaceAll("\\.$","");EditText minutes=input("例如 20 或 20.5",draft!=null?draft.getString("minutes"):initialMinutes,InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);form.addView(minutes);gap(form,14);
        form.addView(label("记录时区："+zone.getId()+"。补记不受倒计时的 15 分钟限制。",12,MUTED,false));gap(form,8);form.addView(label(original==null?"记录不能与已有实际投入重叠。若正在计时，请先结束这一段再补记。":"修改时间或时长会整理为一段连续投入；只改名称或分类会保留暂停分段。修改后会标记“已修改”。",12,AMBER,false));
        AlertDialog dialog=dialog(original==null?"补记一段专注":"修改专注记录",form,"保存",()->{});dialog.setOnShowListener(v->{styleDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{String value=title.getText().toString().trim();if(value.isEmpty()){title.setError("请填写专注事项");return;}LocalDate d;LocalTime t;try{d=LocalDate.parse(date.getText().toString().trim(),DATE);}catch(RuntimeException ex){date.setError("请输入有效日期，格式 yyyy-MM-dd");return;}try{t=LocalTime.parse(time.getText().toString().trim(),INPUT_CLOCK);}catch(RuntimeException ex){time.setError("请输入有效时间，格式 HH:mm 或 HH:mm:ss");return;}long ms;try{if(original!=null&&minutes.getText().toString().trim().equals(initialMinutes)){ms=original.actualMs;}else{double n=Double.parseDouble(minutes.getText().toString().trim());if(!Double.isFinite(n)||n<=0||n>10080)throw new NumberFormatException();ms=Math.round(n*60000);if(ms<1000)throw new NumberFormatException();}}catch(NumberFormatException ex){minutes.setError("请输入大于 0 的分钟数，最短 1 秒，最长 7 天");return;}LocalDateTime local=LocalDateTime.of(d,t);if(zone.getRules().getValidOffsets(local).isEmpty()){time.setError("这个时间在所选时区不存在，请调整时间");return;}boolean unchangedStart=original!=null&&date.getText().toString().trim().equals(initial.toLocalDate().toString())&&time.getText().toString().trim().equals(initial.toLocalTime().format(INPUT_CLOCK));long start=unchangedStart?original.startedWallMs:local.atZone(zone).toInstant().toEpochMilli();if((original==null||start!=original.startedWallMs||ms!=original.actualMs)&&(start>System.currentTimeMillis()||start+ms>System.currentTimeMillis()+1000)){minutes.setError("只能记录已经发生的实际投入");return;}
                try{String id=original==null?UUID.randomUUID().toString():original.id;Session manual=Session.manual(id,original==null?"manual-"+id:original.taskId,value,projects.get(project.getSelectedItemPosition()).id,start,ms,zone.getId(),original!=null);boolean unchangedInterval=original!=null&&start==original.startedWallMs&&ms==original.actualMs;Session session=original==null?manual:new Session(manual.id,original.taskId,value,manual.projectId,original.plannedMs,manual.actualMs,manual.startedWallMs,unchangedInterval?original.endedWallMs:manual.endedWallMs,unchangedInterval?original.segments:manual.segments,original.reason,original.manual,true,zone.getId());if(overlapsActive(start,start+ms)){minutes.setError("请先结束正在进行或暂停的专注，再补记或修改记录");return;}if(original==null)store.addManualSession(session);else store.saveEditedSession(session);dialog.dismiss();render(true);toast(original==null?"补记已保存":"修改已保存");}catch(RuntimeException ex){minutes.setError(ex.getMessage()==null?"记录未保存，请检查时间是否重叠":ex.getMessage());}});});rememberDialog(dialog,"session",original==null?null:original.id,()->{Bundle b=new Bundle();b.putString("title",title.getText().toString());b.putString("project",projects.get(project.getSelectedItemPosition()).id);b.putString("date",date.getText().toString());b.putString("time",time.getText().toString());b.putString("minutes",minutes.getText().toString());return b;});dialog.show();
    }
    private boolean overlapsActive(long start,long end) {
        TimerEngine.State state=coordinator.engine().snapshot();if(state==null||state.sessionId==null)return false;
        // Store conservatively excludes all edits while an unfinished record exists.
        return true;
    }

    private void renderSettings() {
        body.addView(label("按你的节奏来",25,INK,true));gap(body,8);body.addView(label("分类、提醒和你的数据，都在这里。",13,MUTED,false));gap(body,22);
        LinearLayout projectCard=card(Color.WHITE);LinearLayout projectHeading=row();projectHeading.setGravity(Gravity.CENTER_VERTICAL);projectHeading.addView(label("项目与分类",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));Button add=button("＋ 新建",false);add.setOnClickListener(v->showProjectDialog(null));projectHeading.addView(add);projectCard.addView(projectHeading);gap(projectCard,8);
        List<LedgerStore.Project> projects=activeProjects();for(LedgerStore.Project project:projects){Button b=button("●  "+project.name+"    编辑",false);b.setTextColor(project.color);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setContentDescription("编辑分类"+project.name);b.setOnClickListener(v->showProjectDialog(project));projectCard.addView(b);}projectCard.addView(label("可以改名、换颜色或归档。历史记录会保留。",12,MUTED,false));addCard(projectCard);
        LinearLayout permissions=card(Color.WHITE);permissions.addView(label("专注提醒",19,INK,true));gap(permissions,12);
        permissions.addView(label("通知权限  ·  "+(coordinator.notificationsGranted()?"已允许":"尚未允许"),14,INK,true));gap(permissions,6);permissions.addView(label("在锁屏显示计时状态，并在到点时提醒。关闭通知不影响本地记录。",12,MUTED,false));gap(permissions,8);Button notify=button(coordinator.notificationsGranted()?"管理通知":"允许通知提醒",false);notify.setOnClickListener(v->notificationSettings());permissions.addView(notify);gap(permissions,16);
        permissions.addView(label("准时提醒  ·  "+(coordinator.exactAlarmGranted()?"已允许":"可选"),14,INK,true));gap(permissions,6);permissions.addView(label("允许“闹钟和提醒”可提高息屏到点提醒的准时性。系统仍可能延迟提醒；记录按实际计时计算。",12,MUTED,false));gap(permissions,8);Button alarms=button("设置闹钟与提醒权限",false);alarms.setOnClickListener(v->{if(Build.VERSION.SDK_INT>=31){try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}catch(RuntimeException ex){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}}else toast("当前 Android 版本无需额外授予此权限");});permissions.addView(alarms);addCard(permissions);
        LinearLayout reliability=card(PALE);reliability.addView(label("息屏与后台运行",17,JADE,true));gap(reliability,8);reliability.addView(label("realme 等机型可能限制后台活动。可在系统应用管理中允许后台运行，避免“清理后台”。若提醒被延迟，重新打开应用会按保存的计时恢复。",13,INK,false));gap(reliability,10);reliability.addView(label("强行停止应用会中断提醒；设备重启会结束未完成的一段，保留已保存的有效投入。系统权限无法保证所有机型提醒完全准时。",12,MUTED,false));gap(reliability,12);Button battery=button("打开系统电池设置",false);battery.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException ex){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}});reliability.addView(battery);addCard(reliability);
        LinearLayout data=card(Color.WHITE);data.addView(label("数据与你",19,INK,true));gap(data,8);data.addView(label("所有任务与时间记录保存在这台设备上。无账号、无广告、无统计 SDK，不读取其他应用的使用行为。",13,MUTED,false));gap(data,12);
        Button copy=button("复制 "+selectedDate+" 的 Markdown",false);copy.setOnClickListener(v->copyDaily());data.addView(copy);Button csv=button("导出全部专注 CSV",false);csv.setOnClickListener(v->export("csv"));data.addView(csv);Button backup=button("导出完整 JSON 备份",false);backup.setOnClickListener(v->export("json"));data.addView(backup);gap(data,8);data.addView(label("导出到你选择的位置。卸载会清除本地数据，建议定期保留备份。本版本的 JSON 用于数据留存，不提供自动导入。",12,AMBER,false));addCard(data);
        LinearLayout attribution=card(Color.WHITE);attribution.addView(label("时间归属规则",16,INK,true));gap(attribution,8);attribution.addView(label("每段专注记住开始时的时区，跨午夜会拆分到对应日期。修改系统时区不会重写历史；实际时长以计时记录为准，暂停不计入。",13,MUTED,false));gap(attribution,8);attribution.addView(label("当前设备时区："+ZoneId.systemDefault().getId(),12,MUTED,false));addCard(attribution);TextView footer=label("专注账本 · 本地原生版\n让投入有记录，让完成由你决定。",12,MUTED,false);footer.setGravity(Gravity.CENTER);body.addView(footer);
    }

    private void showProjectDialog(LedgerStore.Project original) {
        Bundle draft=takeDialogDraft("project",original==null?null:original.id);LinearLayout form=dialogForm();form.addView(fieldLabel("分类名称"));EditText name=input("例如：工作、运动、创作",draft!=null?draft.getString("name"):original==null?"":original.name,InputType.TYPE_CLASS_TEXT);form.addView(name);gap(form,16);form.addView(fieldLabel("分类颜色"));int[] colors={JADE,Color.rgb(63,103,141),Color.rgb(140,91,111),Color.rgb(152,105,53),Color.rgb(96,93,141),Color.rgb(99,119,55)};final int[] selected={draft!=null?draft.getInt("color"):original==null?JADE:original.color};LinearLayout palette=column();List<Button> choices=new ArrayList<>();for(int line=0;line<2;line++){LinearLayout r=row();for(int index=line*3;index<line*3+3;index++){final int color=colors[index];Button b=button("●",false);b.setTextColor(color);b.setTextSize(25);b.setContentDescription("分类颜色 "+(index+1));b.setBackground(shape(selected[0]==color?PALE:PAPER,14));choices.add(b);b.setOnClickListener(v->{selected[0]=color;for(int k=0;k<choices.size();k++)choices.get(k).setBackground(shape(colors[k]==color?PALE:PAPER,14));});r.addView(b,new LinearLayout.LayoutParams(0,-2,1));}palette.addView(r);}form.addView(palette);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(original==null?"新建分类":"编辑分类").setView(wrapForm(form)).setNegativeButton("取消",null).setPositiveButton("保存",null).create();if(original!=null)dialog.setButton(AlertDialog.BUTTON_NEUTRAL,"归档",(d,w)->new AlertDialog.Builder(this).setTitle("归档“"+original.name+"”？").setMessage("历史记录保留；此分类下的任务也会一起归档，不再显示在当天计划中。").setNegativeButton("取消",null).setPositiveButton("归档",(x,y)->{store.archiveProject(original.id);render(true);}).show());dialog.setOnShowListener(v->{styleDialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("请填写分类名称");return;}try{if(original==null)store.addProject(value,selected[0]);else{original.name=value;original.color=selected[0];store.updateProject(original);}dialog.dismiss();render(true);}catch(RuntimeException ex){name.setError(ex.getMessage());}});});rememberDialog(dialog,"project",original==null?null:original.id,()->{Bundle b=new Bundle();b.putString("name",name.getText().toString());b.putInt("color",selected[0]);return b;});dialog.show();
    }

    private void notificationSettings() {
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATIONS);return;}
        Intent intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);intent.putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());try{startActivity(intent);}catch(RuntimeException ex){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==REQUEST_NOTIFICATIONS){render(true);toast(coordinator.notificationsGranted()?"通知提醒已允许":"仍可计时与记录；通知可稍后在设置中开启。");}}
    private void copyDaily(){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("专注账本 "+selectedDate,Reports.dailyMarkdown(store,selectedDate)));toast("已复制 "+selectedDate+" 的 Markdown");}
    private void export(String kind){try{pendingExportKind=kind;pendingExport="csv".equals(kind)?"\uFEFF"+Reports.csv(store):Reports.backupJson(store);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("csv".equals(kind)?"text/csv":"application/json");intent.putExtra(Intent.EXTRA_TITLE,"专注账本-"+LocalDate.now()+"."+kind);startActivityForResult(intent,REQUEST_EXPORT);}catch(RuntimeException ex){pendingExport=null;pendingExportKind=null;toast("无法打开保存位置，请检查系统文件应用。");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==REQUEST_EXPORT){if(pendingExport==null&&pendingExportKind!=null)pendingExport="csv".equals(pendingExportKind)?"\uFEFF"+Reports.csv(store):Reports.backupJson(store);if(result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingExport!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new java.io.IOException("没有可写入的文件");out.write(pendingExport.getBytes(StandardCharsets.UTF_8));out.flush();toast("文件已导出");}catch(Exception ex){toast("导出失败："+(ex.getMessage()==null?"无法写入文件":ex.getMessage()));}}pendingExport=null;pendingExportKind=null;}}

    private void rememberDialog(AlertDialog dialog,String kind,String id,java.util.function.Supplier<Bundle> draft) {
        editingDialog=dialog;editingKind=kind;editingId=id;editingDraft=draft;
        dialog.setOnDismissListener(d->{if(editingDialog==dialog){editingDialog=null;editingKind=null;editingId=null;editingDraft=null;}});
    }
    private Bundle takeDialogDraft(String kind,String id){if(same(kind,pendingDialogKind)&&same(id,pendingDialogId)){Bundle result=pendingDialogDraft;pendingDialogDraft=null;pendingDialogKind=null;pendingDialogId=null;return result;}return null;}
    private void restoreDialog(){
        String kind=pendingDialogKind,id=pendingDialogId;
        if("task".equals(kind)||"duration".equals(kind)){LedgerStore.Task task=id==null?null:store.getTask(id);if("duration".equals(kind)){if(task!=null&&!coordinator.engine().isActive())showDurationDialog(task);}else showTaskDialog(task);}
        else if("project".equals(kind))showProjectDialog(id==null?null:store.getProject(id));
        else if("session".equals(kind)){Session record=null;for(Session s:store.getSessions())if(same(id,s.id)){record=s;break;}showSessionDialog(record);}
        pendingDialogKind=null;pendingDialogId=null;pendingDialogDraft=null;
    }

    private List<LedgerStore.Project> activeProjects(){List<LedgerStore.Project> result=new ArrayList<>();for(LedgerStore.Project p:store.getProjects())if(!p.archived)result.add(p);return result;}
    private Spinner projectSpinner(List<LedgerStore.Project> projects,String selected){Spinner spinner=new Spinner(this);spinner.setMinimumHeight(dp(52));String[] names=new String[projects.size()];int index=0;for(int i=0;i<projects.size();i++){names[i]=projects.get(i).name;if(same(projects.get(i).id,selected))index=i;}ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);spinner.setAdapter(adapter);spinner.setSelection(index);spinner.setContentDescription("选择所属分类");return spinner;}
    private String projectName(String id){LedgerStore.Project project=store.getProject(id);return project==null?"未分类":project.name+(project.archived?"（已归档）":"");}
    private String scheduleLabel(LedgerStore.Task task){if(task.oneOffDate!=null)return "一次性 · "+task.oneOffDate;String[] names={"一","二","三","四","五","六","日"};StringBuilder b=new StringBuilder("每周 ");for(int i=0;i<7;i++)if((task.weekMask&(1<<i))!=0){if(b.length()>3)b.append("、");b.append(names[i]);}return b.toString();}
    private String reasonLabel(String reason){if("expired".equals(reason))return "到点结束";if("early_end".equals(reason))return "主动结束";if("interrupted_restart".equals(reason))return "重启恢复";if("manual".equals(reason))return "手动录入";return "已记录";}
    private void pickDate(EditText field){LocalDate date;try{date=LocalDate.parse(field.getText().toString().trim(),DATE);}catch(RuntimeException ex){date=selectedDate;}new DatePickerDialog(this,(p,y,m,d)->field.setText(LocalDate.of(y,m+1,d).toString()),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();}
    private String dateLabel(LocalDate date){String[] day={"周一","周二","周三","周四","周五","周六","周日"};return date.getMonthValue()+" 月 "+date.getDayOfMonth()+" 日  "+day[date.getDayOfWeek().getValue()-1]+(date.equals(LocalDate.now())?" · 今天":"");}
    private static ZoneId safeZone(String id){try{return ZoneId.of(id);}catch(RuntimeException ex){return ZoneId.systemDefault();}}
    private String duration(long ms){long seconds=Math.max(0,ms)/1000,minutes=seconds/60;if(minutes>=60)return minutes/60+" 小时 "+minutes%60+" 分";if(minutes>0)return minutes+" 分钟";return seconds+" 秒";}
    private String countdown(long ms){long s=(Math.max(0,ms)+999)/1000;return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);}
    private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void error(RuntimeException error){toast(error.getMessage()==null?"操作未完成，请稍后重试。":error.getMessage());}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView label(String value,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));t.setIncludeFontPadding(true);t.setLineSpacing(dp(2),1.08f);return t;}
    private TextView fieldLabel(String name){TextView t=label(name,13,MUTED,true);t.setPadding(0,0,0,dp(6));return t;}
    private Button button(String text,boolean primary){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?PAPER:JADE);b.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(dp(48));b.setPadding(dp(14),dp(10),dp(14),dp(10));b.setBackground(shape(primary?JADE:Color.TRANSPARENT,14));b.setStateListAnimator(null);return b;}
    private EditText input(String hint,String value,int type){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setInputType(type);e.setSingleLine(true);e.setMinimumHeight(dp(52));e.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable bg=shape(PAPER,12);bg.setStroke(dp(1),LINE);e.setBackground(bg);e.setContentDescription(hint);return e;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(int color){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(shape(color,22));return c;}
    private void addCard(LinearLayout card){body.addView(card,new LinearLayout.LayoutParams(-1,-2));gap(body,14);}
    private void gap(LinearLayout parent,int height){View v=new View(this);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private void gapHorizontal(LinearLayout parent,int width){View v=new View(this);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);parent.addView(v,new LinearLayout.LayoutParams(dp(width),1));}
    private LinearLayout dialogForm(){LinearLayout form=column();form.setPadding(dp(22),dp(12),dp(22),dp(16));return form;}
    private ScrollView wrapForm(LinearLayout form){ScrollView s=new ScrollView(this);s.setFillViewport(false);s.addView(form);return s;}
    private AlertDialog dialog(String title,LinearLayout form,String positive,Runnable action){return new AlertDialog.Builder(this).setTitle(title).setView(wrapForm(form)).setNegativeButton("取消",null).setPositiveButton(positive,(d,w)->action.run()).create();}
    private void styleDialog(AlertDialog dialog){if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(shape(PAPER,24));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(JADE);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(MUTED);}
    private final class TimerDial extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF rect=new RectF();private float progress;
        TimerDial(Context context){super(context);}
        void setProgress(float value){progress=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float stroke=dp(7),pad=dp(15);rect.set(pad,pad,getWidth()-pad,getHeight()-pad);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Color.rgb(48,72,63));canvas.drawOval(rect,paint);paint.setColor(Color.rgb(173,205,159));canvas.drawArc(rect,-90,360*progress,false,paint);}
    }
}
