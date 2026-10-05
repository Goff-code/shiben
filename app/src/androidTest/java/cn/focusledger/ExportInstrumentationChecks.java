package cn.focusledger;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import cn.focusledger.core.Session;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Destructive fixtures ONLY in a disposable emulator, invoked by the test APK's runner. */
public final class ExportInstrumentationChecks {
    private ExportInstrumentationChecks() {}
    private static void check(boolean okay, String name, Consumer<String> report) {
        if (!okay) throw new AssertionError(name); report.accept(name);
    }
    private static ExportWorker.Job await(ExportWorker worker, ExportWorker.Request request, Consumer<String> report) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] mainThread = {true};
        ExportWorker.Job job = worker.start(request, value -> {
            mainThread[0] &= Looper.myLooper() == Looper.getMainLooper();
            if(value.isTerminal()) done.countDown();
        });
        if(!done.await(90, TimeUnit.SECONDS)) { job.cancel(); throw new AssertionError("export timed out"); }
        worker.detach(job.id);
        check(mainThread[0],"export callbacks return on main thread",report);
        check(job.state==ExportWorker.State.SUCCEEDED,"export succeeded: "+request.format+" / "+job.message,report);
        return job;
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    /** Produces a deterministic image from the real export renderer for pixel review in QA only. */
    private static void writeApprovedPreviewFixture(Context context, ExportWorker.Job job, Consumer<String> report) throws Exception {
        List<LedgerStore.DayTask> rows=java.util.Arrays.asList(
                new LedgerStore.DayTask("reading","阅读",1,100,.5,0,false,null),
                new LedgerStore.DayTask("web3","Web3",2,60,.25,0,false,null),
                new LedgerStore.DayTask("english","英语",3,0,.125,0,false,null),
                new LedgerStore.DayTask("code","代码视频",4,0,.125,0,false,null));
        LedgerStore.DaySnapshot sample=new LedgerStore.DaySnapshot(LocalDate.of(2026,10,5),rows,65,true,8_100_000,"D");
        java.lang.reflect.Method planner=ExportWorker.class.getDeclaredMethod("planPages",LedgerStore.DaySnapshot.class);
        planner.setAccessible(true);List<?> pages=(List<?>)planner.invoke(null,sample);
        check(pages.size()==1,"approved four-task preview fits a single full PNG",report);
        java.lang.reflect.Method render=ExportWorker.class.getDeclaredMethod("renderPage",LedgerStore.DaySnapshot.class,
                pages.get(0).getClass(),int.class,int.class,android.graphics.Bitmap.class,File.class,ExportWorker.Job.class);
        render.setAccessible(true);
        android.graphics.Bitmap background=BitmapFactory.decodeResource(context.getResources(),R.drawable.export_today_blurred);
        File image=new File(context.getFilesDir(),"qa-export-preview-2026-10-05.png");
        try { render.invoke(null,sample,pages.get(0),1,1,background,image,job); }
        finally { background.recycle(); }
        DailyArchive.writeAtomicUtf8(new File(context.getFilesDir(),"qa-export-preview-2026-10-05.md"),
                V2Reports.dailyMarkdown(sample).getBytes(StandardCharsets.UTF_8));
        check(image.isFile()&&image.length()>0,"real renderer writes approved sample for visual review",report);
        android.graphics.Paint paint=new android.graphics.Paint();paint.setTextSize(38);
        String emoji="🧠阅读🧠";
        check(String.join("",ExportWorker.wrapText(emoji,paint,1)).equals(emoji),"PNG wrapping preserves surrogate pairs at narrow widths",report);
    }
    public static void run(Context context, Consumer<String> report) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new AssertionError("Run export checks off main thread");
        ExportWorker worker=ExportWorker.get(context);
        LocalDate today=LocalDate.now();
        List<String> titles=new ArrayList<>();
        try(LedgerStore store=new LedgerStore(context)) {
            store.ensureToday();
            for(int i=0;i<42;i++) {
                String title="导出分页测试 "+i+" · 阅读🧠"; titles.add(title);
                store.saveSimpleTask(title, i%101, store.getTasks(today).size()+1);
            }
            String longTitle="长任务名称开始 "+String.join("", java.util.Collections.nCopies(150,"完整保留阅读🧠"))+" 长任务名称结束";
            titles.add(longTitle); store.saveSimpleTask(longTitle,75,store.getTasks(today).size()+1);
        }
        ExportWorker.Job md=await(worker,new ExportWorker.Request(V2Reports.DateRange.day(today),ExportWorker.Format.MARKDOWN),report);
        String markdown=read(md.result.file);
        for(String title:titles) check(markdown.contains(V2Reports.markdownText(title)),"Markdown retains task: "+title.substring(0,Math.min(24,title.length())),report);
        check(md.result.markdownForCopy!=null&&md.result.markdownForCopy.equals(markdown),"copy contains complete Markdown",report);
        check(md.result.file.length()>0,"Markdown generated file is nonempty",report);
        writeApprovedPreviewFixture(context, md, report);
        check(markdown.startsWith("# "+today+"｜时本\n"),"Markdown uses approved date title",report);
        check(markdown.split("(?m)^## ",-1).length-1==4,"daily Markdown has exactly four approved sections",report);
        check(markdown.contains("## 今日投资注意力时长总计\n")&&markdown.contains("## 总完成度\n")
                &&markdown.contains("## 当日任务与完成度\n")&&markdown.contains("## 当日结算等级\n"),
                "daily Markdown includes attention, progress, all tasks, and settlement grade",report);
        check(!markdown.contains("权重")&&!markdown.contains("毫秒")&&!markdown.contains("口径")
                &&!markdown.contains("SQLite")&&!markdown.contains("专注明细"),"Markdown excludes technical metadata",report);

        try(LedgerStore store=new LedgerStore(context)) {
            java.lang.reflect.Method method=ExportWorker.class.getDeclaredMethod("planPages",LedgerStore.DaySnapshot.class);
            method.setAccessible(true);
            List<?> planned=(List<?>)method.invoke(null,store.getDaySnapshot(today));
            java.util.Map<String,StringBuilder> names=new java.util.LinkedHashMap<>();
            for(Object page:planned) {
                java.lang.reflect.Field height=page.getClass().getDeclaredField("height");height.setAccessible(true);
                check(height.getInt(page)<=ExportWorker.PAGE_MAX_HEIGHT-92,"PNG planned content fits inside page without cropping",report);
                java.lang.reflect.Field rows=page.getClass().getDeclaredField("rows");rows.setAccessible(true);
                for(Object row:(List<?>)rows.get(page)) {
                    java.lang.reflect.Field taskField=row.getClass().getDeclaredField("task");taskField.setAccessible(true);
                    LedgerStore.DayTask task=(LedgerStore.DayTask)taskField.get(row);
                    java.lang.reflect.Field lines=row.getClass().getDeclaredField("lines");lines.setAccessible(true);
                    StringBuilder builder=names.get(task.title);if(builder==null){builder=new StringBuilder();names.put(task.title,builder);}
                    for(Object line:(List<?>)lines.get(row))builder.append((String)line);
                }
            }
            for(String title:titles)check(names.containsKey(title)&&title.contentEquals(names.get(title)),
                    "PNG planner preserves full task name: "+title.substring(0,Math.min(24,title.length())),report);
        }
        ExportWorker.Job png=await(worker,new ExportWorker.Request(V2Reports.DateRange.day(today),ExportWorker.Format.PNG),report);
        check("application/zip".equals(png.result.mimeType)&&png.result.pageCount>1,"long daily PNG exports paginated ZIP",report);
        int pages=0;
        try(ZipFile zip=new ZipFile(png.result.file)) {
            java.util.Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement(); if(!entry.getName().endsWith(".png")) continue;
                pages++; BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
                try(java.io.InputStream input=zip.getInputStream(entry)) { BitmapFactory.decodeStream(input,null,options); }
                check(options.outWidth==ExportWorker.PAGE_WIDTH&&options.outHeight<=ExportWorker.PAGE_MAX_HEIGHT,
                        "PNG page uses bounded bitmap dimensions: "+pages,report);
            }
            check(zip.getEntry("导出说明.txt")!=null,"ZIP includes pagination instructions",report);
        }
        check(pages==png.result.pageCount,"all rendered pages are in ZIP",report);
        check(png.result.previewImage!=null&&png.result.previewImage.isFile(),"PNG preview is first real output page",report);
        android.graphics.Bitmap preview=BitmapFactory.decodeFile(png.result.previewImage.getAbsolutePath());
        try {
            int border=preview.getPixel(10,10), panel=preview.getPixel(80,250);
            check(border!=panel,"PNG uses leaves backdrop and distinct legible panel",report);
            check(android.graphics.Color.red(panel)>230&&android.graphics.Color.green(panel)>230
                    &&android.graphics.Color.blue(panel)>230,"PNG content panel is near white",report);
        } finally { preview.recycle(); }

        // Re-observation is the same contract used by an Activity after rotation.
        CountDownLatch observed=new CountDownLatch(1);
        worker.observe(png.id,job->{if(job.state==ExportWorker.State.SUCCEEDED&&job.result==png.result)observed.countDown();});
        check(observed.await(5,TimeUnit.SECONDS),"completed export result survives observer replacement",report);worker.detach(png.id);

        DailyArchive archive=DailyArchive.get(context); archive.schedule();
        File daily=archive.getDailyFile(today); long until=SystemClock.elapsedRealtime()+30000;
        boolean archived=false;
        while(SystemClock.elapsedRealtime()<until) {
            if(daily.isFile()) { String content=read(daily); archived=content.contains(V2Reports.markdownText(titles.get(titles.size()-1))); }
            if(archived)break;SystemClock.sleep(100);
        }
        check(archived,"automatic daily Markdown includes latest full task list",report);
        check(daily.getAbsolutePath().startsWith(context.getFilesDir().getAbsolutePath()),"daily archive is durable filesDir, not cache",report);

        // Pick a distant deterministic date; no overlap with timer test fixtures.
        LocalDate old=LocalDate.of(2001,1,2);
        Session oldSession=Session.manual("export-test-old","export-old-task","旧版导出测试",null,
                old.atTime(12,0).toInstant(ZoneOffset.UTC).toEpochMilli(),3000,"UTC",false);
        try(LedgerStore store=new LedgerStore(context)) { if(store.getSession(oldSession.id)==null)store.addManualSession(oldSession); }
        ExportWorker.Job combined=await(worker,new ExportWorker.Request(V2Reports.DateRange.custom(old,today),ExportWorker.Format.MARKDOWN),report);
        String all=read(combined.result.file);
        check(combined.result.dayCount>=2&&"text/markdown".equals(combined.result.mimeType),"multi-day Markdown is one combined file",report);
        check(all.contains(old.toString())&&all.contains(today.toString())&&all.contains("旧版导出测试"),"combined Markdown keeps both inclusive endpoints",report);
        check(all.split("(?m)^# ",-1).length-1==combined.result.dayCount
                &&all.split("(?m)^## ",-1).length-1==combined.result.dayCount*4,
                "multi-day Markdown repeats only the five approved daily categories",report);
        check(!all.contains("# 时本记录")&&!all.contains("范围："),"multi-day Markdown adds no report metadata",report);
        ExportWorker.Job multiple=await(worker,new ExportWorker.Request(V2Reports.DateRange.custom(old,today),ExportWorker.Format.PNG),report);
        check(multiple.result.dayCount>=2&&"application/zip".equals(multiple.result.mimeType),"multiple days PNG produce ZIP",report);

        CountDownLatch failed=new CountDownLatch(1);
        ExportWorker.Job copy=worker.copyToDocument(md.result.file,Uri.parse("content://cn.goff.shiben.no-such-provider/document/blocked"),job->{if(job.isTerminal())failed.countDown();});
        check(failed.await(15,TimeUnit.SECONDS),"SAF write failure returns promptly",report);
        check(copy.state==ExportWorker.State.FAILED&&copy.error!=null,"SAF failure is explicit, never claims saved",report);worker.detach(copy.id);
        check(md.result.file.isFile()&&md.result.file.length()>0,"SAF failure preserves source export",report);
        boolean denied=false;
        try { archive.setBackupTree(Uri.parse("content://cn.goff.shiben.no-such-provider/tree/folder"),0); }
        catch(IllegalArgumentException expected){denied=true;}
        check(denied,"external archive refuses URI without picker-granted permission",report);

        // A correction/deletion must replace the old readable report, never preserve stale totals.
        File oldDaily=archive.getDailyFile(old); archive.schedule();
        until=SystemClock.elapsedRealtime()+30000;
        boolean oldArchived=false;
        while(SystemClock.elapsedRealtime()<until) {
            if(oldDaily.isFile())try{oldArchived=read(oldDaily).contains("旧版导出测试");}catch(java.io.IOException retry){}
            if(oldArchived)break;SystemClock.sleep(100);
        }
        check(oldArchived,"historical Markdown retained before record deletion",report);
        try(LedgerStore store=new LedgerStore(context)){store.deleteSession(oldSession.id);}
        until=SystemClock.elapsedRealtime()+30000;boolean removed=false;
        while(SystemClock.elapsedRealtime()<until) {
            if(oldDaily.isFile())try{String content=read(oldDaily);removed=content.contains("## 今日投资注意力时长总计\n0秒\n")&&content.contains("## 当日结算等级\n未知\n")&&!content.contains("毫秒");}catch(java.io.IOException retry){}
            if(removed)break;SystemClock.sleep(100);
        }
        check(removed&&oldDaily.isFile(),"daily archive survives and updates focus totals after last session deletion",report);

        ExportWorker.Job cancel=worker.start(new ExportWorker.Request(V2Reports.DateRange.all(),ExportWorker.Format.PNG),null);cancel.cancel();
        until=SystemClock.elapsedRealtime()+10000;
        while(!cancel.isTerminal()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        check(cancel.state==ExportWorker.State.CANCELLED,"cancelled export reaches terminal cancellation",report);
        worker.detach(cancel.id);
    }
}
