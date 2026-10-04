package cn.focusledger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.util.Locale;

/** Original small vector drawings: no remote assets, icon font or tracking dependency. */
final class UiKit {
    static final int PAPER=0xffFAF8F3, INK=0xff192432, TEAL=0xff146873, MUTED=0xff797E84,
            LINE=0xffDEDEDA, TRACK=0xffE5E5E3, PALE=0xffDFECEC, RED=0xffBA5046;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(int color,int stroke,float radius,Context c){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));
        if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;
    }
    static class Icon extends View {
        final String kind; final Paint p=new Paint(3); int color;
        Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas original){super.onDraw(original);Canvas c=original;c.save();float s=Math.min(getWidth(),getHeight())/28f;c.translate((getWidth()-28*s)/2,(getHeight()-28*s)/2);c.scale(s,s);p.setColor(color);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(kind){
                case "focus":c.drawCircle(14,14,10,p);p.setStyle(Paint.Style.FILL);c.drawCircle(14,14,4.5f,p);break;
                case "tasks":c.drawRoundRect(new RectF(4,4,24,24),2,2,p);c.drawLine(8,14,12,18,p);c.drawLine(12,18,20,10,p);break;
                case "today":p.setStrokeWidth(3);c.drawLine(6,23,6,17,p);c.drawLine(14,23,14,10,p);c.drawLine(22,23,22,4,p);break;
                case "back":c.drawLine(22,14,5,14,p);c.drawLine(5,14,12,7,p);c.drawLine(5,14,12,21,p);break;
                case "plus":c.drawLine(14,5,14,23,p);c.drawLine(5,14,23,14,p);break;
                case "sort":c.drawLine(5,7,23,7,p);c.drawLine(5,14,23,14,p);c.drawLine(5,21,23,21,p);break;
                case "chevron":c.drawLine(11,7,18,14,p);c.drawLine(18,14,11,21,p);break;
                case "down":c.drawLine(7,11,14,18,p);c.drawLine(14,18,21,11,p);break;
                case "history":c.drawCircle(14,14,10,p);c.drawLine(14,7,14,14,p);c.drawLine(14,14,20,18,p);break;
                case "export":c.drawRoundRect(new RectF(5,3,22,25),2,2,p);c.drawLine(9,11,18,11,p);c.drawLine(9,16,18,16,p);c.drawLine(9,21,15,21,p);break;
                case "info":c.drawCircle(14,14,10,p);c.drawLine(14,13,14,20,p);p.setStyle(Paint.Style.FILL);c.drawCircle(14,8,1,p);break;
                case "more":p.setStyle(Paint.Style.FILL);for(int x=5;x<=23;x+=9)c.drawCircle(x,14,1.8f,p);break;
                case "check":c.drawLine(5,14,11,20,p);c.drawLine(11,20,23,7,p);break;
                case "bell":c.drawArc(new RectF(7,4,21,21),180,180,false,p);c.drawLine(7,12,5,22,p);c.drawLine(21,12,23,22,p);c.drawLine(5,22,23,22,p);c.drawLine(12,26,16,26,p);break;
                case "drag":p.setStyle(Paint.Style.FILL);for(int y=6;y<=22;y+=8){c.drawCircle(10,y,1.5f,p);c.drawCircle(18,y,1.5f,p);}break;
                default:c.drawRoundRect(new RectF(4,4,24,24),3,3,p);
            }c.restore();
        }
    }
    static class Ring extends View {
        final Paint p=new Paint(3); double fraction; String main="0%",subtitle=""; int color=TEAL;
        Ring(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        void set(double fraction,String main,String subtitle,boolean paused){this.fraction=Double.isFinite(fraction)?Math.max(0,Math.min(1,fraction)):0;this.main=main;this.subtitle=subtitle;this.color=paused?0xffA4AFB8:TEAL;setContentDescription(main+(subtitle.isEmpty()?"":"，"+subtitle));invalidate();}
        protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),height=MeasureSpec.getSize(h);if(MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED)height=dp(getContext(),248);setMeasuredDimension(width,height);}
        protected void onDraw(Canvas c){super.onDraw(c);float side=Math.max(0,Math.min(getWidth(),getHeight())-dp(getContext(),16));float cx=getWidth()/2f,cy=getHeight()/2f;float stroke=Math.max(dp(getContext(),5),side*.033f);RectF r=new RectF(cx-side/2+stroke,cy-side/2+stroke,cx+side/2-stroke,cy+side/2-stroke);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(TRACK);c.drawOval(r,p);p.setColor(color);if(fraction>0)c.drawArc(r,-90,(float)(fraction*360),false,p);p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);p.setColor(INK);float size=side*(main.length()>6?.18f:.255f);p.setTextSize(size);float y=cy-(p.ascent()+p.descent())/2-(subtitle.isEmpty()?0:side*.07f);c.drawText(main,cx,y,p);if(!subtitle.isEmpty()){p.setTextSize(Math.min(dp(getContext(),15),side*.07f));p.setColor(MUTED);c.drawText(subtitle,cx,cy+side*.20f,p);}}
    }
    static class Bar extends View {
        final Paint p=new Paint(3);final int progress;
        Bar(Context c,int progress){super(c);this.progress=progress;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){float y=getHeight()/2f,h=dp(getContext(),6);p.setColor(TRACK);c.drawRoundRect(new RectF(0,y-h/2,getWidth(),y+h/2),h,h,p);p.setColor(TEAL);if(progress>0)c.drawRoundRect(new RectF(0,y-h/2,getWidth()*Math.min(progress,100)/100f,y+h/2),h,h,p);}
    }
    static class Fireworks extends View {
        final Paint p=new Paint(3); final ValueAnimator anim=ValueAnimator.ofFloat(0,1); float phase;
        Fireworks(Context c){super(c);setClickable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);anim.setDuration(2000);anim.addUpdateListener(a->{phase=(float)a.getAnimatedValue();invalidate();});}
        protected void onAttachedToWindow(){super.onAttachedToWindow();anim.start();}
        protected void onDetachedFromWindow(){anim.cancel();super.onDetachedFromWindow();}
        protected void onDraw(Canvas c){if(phase>=1)return;float unit=Math.min(getWidth(),getHeight());for(int b=0;b<5;b++){float x=getWidth()*(.14f+.18f*b),y=getHeight()*(.25f+.10f*(b%3));float t=Math.max(0,Math.min(1,(phase-b*.045f)*1.25f));p.setColor(b%2==0?TEAL:0xffD3A344);p.setAlpha((int)(255*(1-t)));p.setStrokeWidth(dp(getContext(),2));p.setStrokeCap(Paint.Cap.ROUND);for(int ray=0;ray<12;ray++){double angle=ray*Math.PI/6;float r=unit*.15f*t;float tail=r*(.55f+.4f*t);c.drawLine(x+(float)Math.cos(angle)*tail,y+(float)Math.sin(angle)*tail,x+(float)Math.cos(angle)*r,y+(float)Math.sin(angle)*r,p);}}}
    }
}
