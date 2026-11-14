package cn.focusledger;
import android.app.job.JobService;
import android.app.job.JobParameters;
public final class SyncJob extends JobService {
 @Override public boolean onStartJob(JobParameters p){SyncEngine.runJob(getApplicationContext(),()->jobFinished(p,false));return true;}
 @Override public boolean onStopJob(JobParameters p){return true;}
}
