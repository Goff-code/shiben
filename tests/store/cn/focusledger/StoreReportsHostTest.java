package cn.focusledger;
import cn.focusledger.core.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
public final class StoreReportsHostTest {
  private static int checks;
  private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); checks++; }
  public static void main(String[] args) throws Exception {
    LedgerStore.Task recurring=new LedgerStore.Task("t","Task","p",25,0,1|64,null,false);
    check(recurring.scheduledOn(LocalDate.of(2026,10,5)),"Monday mask");
    check(recurring.scheduledOn(LocalDate.of(2026,10,4)),"Sunday mask");
    check(!recurring.scheduledOn(LocalDate.of(2026,10,3)),"Saturday excluded");
    recurring.oneOffDate="2026-10-03";
    check(recurring.scheduledOn(LocalDate.of(2026,10,3)),"one-off date");
    check(!recurring.scheduledOn(LocalDate.of(2026,10,5)),"one-off ignores recurrence mask");
    Method safe=Reports.class.getDeclaredMethod("safeSpreadsheetCell",String.class);safe.setAccessible(true);
    for(String payload:new String[]{"=1+1","+SUM(A1:A2)","-1+2","@SUM(A1:A2)","   =1+1","\tSUM(A1:A2)","\r=1+1","\n=1+1","\ufeff=1+1"})
      check(((String)safe.invoke(null,payload)).startsWith("'"),"formula blocked "+payload);
    check(safe.invoke(null,"Web3").equals("Web3"),"ordinary text preserved");
    Method row=Reports.class.getDeclaredMethod("appendCsvRow",StringBuilder.class,String[].class);row.setAccessible(true);
    StringBuilder csv=new StringBuilder();row.invoke(null,csv,new String[]{"quoted\"title","a,b","two\nlines",null,"=1+1"});
    check(csv.toString().equals("\"quoted\"\"title\",\"a,b\",\"two\nlines\",\"\",\"'=1+1\"\r\n"),"RFC4180 escaping and CRLF");
    Method read=LedgerStore.class.getDeclaredMethod("deserialize",byte[].class,Class.class);read.setAccessible(true);
    Session original=Session.manual("s","t","=formula","p",Instant.parse("2026-10-03T23:50:00Z").toEpochMilli(),20*60000L,"UTC",false);
    Session copy=(Session)read.invoke(null,LedgerStore.serialize(original),Session.class);
    check(copy.id.equals(original.id)&&copy.actualMs==1200000L,"session blob round-trip");
    check(DailyTotals.totalsByDay(copy).get(LocalDate.of(2026,10,3))==600000L,"first midnight part");
    check(DailyTotals.totalsByDay(copy).get(LocalDate.of(2026,10,4))==600000L,"second midnight part");
    boolean immutable=false;try{copy.segments.add(new Segment(0,1,1));}catch(UnsupportedOperationException expected){immutable=true;}
    check(immutable,"deserialized segments immutable");
    TimerEngine.State state=new TimerEngine.State();state.sessionId="active";state.taskId="t";
    TimerEngine.State copied=(TimerEngine.State)read.invoke(null,LedgerStore.serialize(state),TimerEngine.State.class);
    check(copied.sessionId.equals("active"),"timer blob round-trip");
    Method same=LedgerStore.class.getDeclaredMethod("sameIntervals",Session.class,Session.class);same.setAccessible(true);
    Session renamed=new Session(copy.id,copy.taskId,"renamed",copy.projectId,copy.plannedMs,copy.actualMs,copy.startedWallMs,copy.endedWallMs,copy.segments,copy.reason,true,true,copy.zoneId);
    check((Boolean)same.invoke(null,copy,renamed),"metadata-only correction retains intervals");
    Session moved=Session.manual("s","t","rename","p",copy.startedWallMs+1,copy.actualMs,"UTC",true);
    check(!(Boolean)same.invoke(null,copy,moved),"changed intervals identified");
    check(Reports.duration(25*60000L).equals("25分"),"duration minutes");
    check(Reports.duration(3661000L).equals("1小时 1分 1秒"),"duration hours");
    System.out.println("PASS ("+checks+" assertions): actual Java recurrence, CSV formula safety/RFC4180, session/timer serialization, midnight accounting, immutable restored sessions, edited-interval identity, duration formatting");
  }
}
