package cn.focusledger;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
public final class SyncActivity extends Activity {
 private TextView status;private final Handler handler=new Handler();
 private final Runnable refresh=new Runnable(){public void run(){if(status!=null)status.setText(SyncIdentity.status(SyncActivity.this));handler.postDelayed(this,1500);}};
 @Override public void onCreate(Bundle b){super.onCreate(b);accept(getIntent());render();}
 private void accept(Intent intent){Uri uri=intent.getData();if(uri==null)return;try{if(!"shiben".equals(uri.getScheme())||!"connect".equals(uri.getHost()))throw new IllegalArgumentException();SyncIdentity.accept(this,uri.getFragment());SyncEngine.request(this);}catch(Exception e){new AlertDialog.Builder(this).setTitle("连接未完成").setMessage("连接信息无效或已过期，请从时本重新发起连接。").setPositiveButton("知道了",null).show();}setIntent(new Intent(this,SyncActivity.class));}
 private void render(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(36,48,36,36);layout.setBackgroundColor(0xffF6F8F5);TextView title=new TextView(this);title.setText("AI 连接");title.setTextSize(28);layout.addView(title);TextView info=new TextView(this);info.setText("连接后，全部任务、每日进度和专注记录会同步到你的私有服务。离线时仍可使用；AI 修改需手机联网执行。后台同步受系统省电调度影响。");info.setPadding(0,32,0,32);layout.addView(info);status=new TextView(this);status.setText(SyncIdentity.status(this));layout.addView(status);
  button(layout,"连接 ChatGPT",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(SyncIdentity.begin(this))));}catch(Exception e){Toast.makeText(this,SyncIdentity.connected(this)?"已连接，请先断开":"无法打开连接页面",Toast.LENGTH_LONG).show();}});
  button(layout,"立即同步",()->{if(SyncIdentity.connected(this))SyncEngine.request(this);else Toast.makeText(this,"请先连接",Toast.LENGTH_SHORT).show();});
  button(layout,"断开连接",()->new AlertDialog.Builder(this).setTitle("断开 AI 连接？").setMessage("撤销手机授权并取消待执行操作。本机记录保留；云端已同步数据保留。若离线，请随后在服务首页撤销授权。").setNegativeButton("取消",null).setPositiveButton("断开",(d,w)->SyncEngine.disconnect(this,()->runOnUiThread(()->status.setText(SyncIdentity.status(this))))).show());
  button(layout,"打开服务首页",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(SyncIdentity.ORIGIN))));
  button(layout,"返回",this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
 }
 private void button(LinearLayout l,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());l.addView(b);}
 @Override protected void onResume(){super.onResume();handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
