package cn.focusledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.function.Consumer;

/** Representative lettering study only. Does not alter the ledger, gallery or production template. */
public final class LetteringStudyChecks {
 private LetteringStudyChecks() {}
 public static void run(Context target, Context test, Consumer<String> report) throws Exception {
  File folder = new File(target.getFilesDir(), "qa-lettering-study"); folder.mkdirs();
  Bitmap background = GraffitiPoster.decodeBackground(target.getResources(), null);
  try {
   for(int i=0;i<3;i++) {
    boolean changed=i==1; LocalDate date=LocalDate.of(2026,10,changed?7:6);
    double progress=changed?91.25:92.75; long millis=changed?22094000:11057000;
    LedgerStore.DaySnapshot snapshot=new LedgerStore.DaySnapshot(date,Arrays.asList(
      new LedgerStore.DayTask("private-1", "PRIVATE NAME NEVER DRAWN", 1, 100, .5, millis/2, false, null),
      new LedgerStore.DayTask("private-2", "PRIVATE SECOND NAME", 2, changed?85:91, .25, millis/4, false, null),
      new LedgerStore.DayTask("private-3", "PRIVATE THIRD NAME", 3, 80, .25, millis-millis/2-millis/4, false, null)),progress,true,millis,"A-");
    Bitmap output=Bitmap.createBitmap(1080,1440,Bitmap.Config.ARGB_8888);
    try {
     LetteringStudyPoster.drawDay(target,test.getAssets(),new Canvas(output),background,snapshot,
             i==2?"lettering/grade_a_minus_v2.png":"lettering/grade_a_minus.png",null);
     File file=new File(folder,i==0?"lettering-study-a-minus.png":i==1?"lettering-study-live-data.png":"lettering-study-alt-v2.png");
     try(FileOutputStream stream=new FileOutputStream(file)) {
      if(!output.compress(Bitmap.CompressFormat.PNG,100,stream))throw new AssertionError("PNG encoding failed");
      stream.getFD().sync();
     }
     report.accept("actual Android Canvas lettering preview with live snapshot fields: "+file.getAbsolutePath());
    } finally {output.recycle();}
   }
  } finally {background.recycle();}
  report.accept("only fixed title and A-minus use offline transparent sprites; dates/weekday/progress/ring/duration are live");
  report.accept("study is test-APK-only; production export renderer, database and gallery are unchanged");
 }
}
