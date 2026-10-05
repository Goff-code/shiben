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
    private static final Typeface MEDIUM=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private static final Typeface REGULAR=Typeface.create("sans-serif",Typeface.NORMAL);

    /** All glyphs share a 28-unit viewport and reusable paths. */
    private static void drawGlyph(Canvas c,String kind,Paint p,Path path,RectF bounds){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        path.rewind();
        switch(kind){
            case "book":
                path.moveTo(14,6);path.cubicTo(10,3,6,3,3,5);path.lineTo(3,24);
                path.cubicTo(7,22,11,22,14,25);path.cubicTo(17,22,21,22,25,24);
                path.lineTo(25,5);path.cubicTo(21,3,17,3,14,6);path.lineTo(14,25);
                c.drawPath(path,p);break;
            case "cube":
                path.moveTo(14,2.5f);path.lineTo(25,8.5f);path.lineTo(25,20);
                path.lineTo(14,26);path.lineTo(3,20);path.lineTo(3,8.5f);path.close();
                path.moveTo(3,8.5f);path.lineTo(14,14.5f);path.lineTo(25,8.5f);
                path.moveTo(14,14.5f);path.lineTo(14,26);c.drawPath(path,p);break;
            case "code":
                c.drawLine(8,8,2,14,p);c.drawLine(2,14,8,20,p);
                c.drawLine(20,8,26,14,p);c.drawLine(26,14,20,20,p);
                c.drawLine(17,4,11,24,p);break;
            case "calendar":
                c.drawRoundRect(3,5,25,25,2.5f,2.5f,p);
                c.drawLine(3,11,25,11,p);c.drawLine(9,2.5f,9,7.5f,p);c.drawLine(19,2.5f,19,7.5f,p);
                p.setStyle(Paint.Style.FILL);
                for(int y=15;y<=21;y+=6)for(int x=8;x<=20;x+=6)c.drawCircle(x,y,.9f,p);
                break;
            case "database":
                bounds.set(4,3,24,11);c.drawOval(bounds,p);
                c.drawLine(4,7,4,21,p);c.drawLine(24,7,24,21,p);
                bounds.set(4,10,24,18);c.drawArc(bounds,0,180,false,p);
                bounds.set(4,17,24,25);c.drawArc(bounds,0,180,false,p);break;
            case "document":case "empty-document":
                path.moveTo(7,3);path.lineTo(18,3);path.lineTo(24,9);path.lineTo(24,23);
                path.quadTo(24,25,22,25);path.lineTo(7,25);path.quadTo(5,25,5,23);
                path.lineTo(5,5);path.quadTo(5,3,7,3);c.drawPath(path,p);
                c.drawLine(10,10,19,10,p);c.drawLine(10,15,19,15,p);c.drawLine(10,20,16,20,p);break;
            case "focus":c.drawCircle(14,14,10,p);p.setStyle(Paint.Style.FILL);c.drawCircle(14,14,4.5f,p);break;
            case "tasks":c.drawRoundRect(4,4,24,24,2,2,p);c.drawLine(8,14,12,18,p);c.drawLine(12,18,20,10,p);break;
            case "today":p.setStrokeWidth(3);c.drawLine(6,23,6,17,p);c.drawLine(14,23,14,10,p);c.drawLine(22,23,22,4,p);break;
            case "back":c.drawLine(22,14,5,14,p);c.drawLine(5,14,12,7,p);c.drawLine(5,14,12,21,p);break;
            case "plus":c.drawLine(14,5,14,23,p);c.drawLine(5,14,23,14,p);break;
            case "sort":c.drawLine(5,7,23,7,p);c.drawLine(5,14,23,14,p);c.drawLine(5,21,23,21,p);break;
            case "chevron":c.drawLine(11,7,18,14,p);c.drawLine(18,14,11,21,p);break;
            case "down":c.drawLine(7,11,14,18,p);c.drawLine(14,18,21,11,p);break;
            case "history-add":c.drawCircle(12,12,9,p);c.drawLine(12,6,12,12,p);c.drawLine(12,12,17,15,p);c.drawCircle(21,21,5,p);c.drawLine(21,18,21,24,p);c.drawLine(18,21,24,21,p);break;
            case "history":c.drawCircle(14,14,10,p);c.drawLine(14,7,14,14,p);c.drawLine(14,14,20,18,p);break;
            case "export":c.drawRoundRect(5,3,22,25,2,2,p);c.drawLine(9,11,18,11,p);c.drawLine(9,16,18,16,p);c.drawLine(9,21,15,21,p);break;
            case "info":c.drawCircle(14,14,10,p);c.drawLine(14,13,14,20,p);p.setStyle(Paint.Style.FILL);c.drawCircle(14,8,1,p);break;
            case "more":p.setStyle(Paint.Style.FILL);for(int x=5;x<=23;x+=9)c.drawCircle(x,14,1.8f,p);break;
            case "check":c.drawLine(5,14,11,20,p);c.drawLine(11,20,23,7,p);break;
            case "bell":bounds.set(7,4,21,21);c.drawArc(bounds,180,180,false,p);c.drawLine(7,12,5,22,p);c.drawLine(21,12,23,22,p);c.drawLine(5,22,23,22,p);c.drawLine(12,26,16,26,p);break;
            case "drag":p.setStyle(Paint.Style.FILL);for(int y=6;y<=22;y+=8){c.drawCircle(10,y,1.5f,p);c.drawCircle(18,y,1.5f,p);}break;
            default:c.drawRoundRect(4,4,24,24,3,3,p);
        }
    }
    static class Icon extends View {
        final String kind;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int color;
        private final Path path=new Path();private final RectF bounds=new RectF();
        Icon(Context c,String kind,int color){super(c);this.kind=kind==null?"":kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float s=Math.min(getWidth(),getHeight())/28f;
            c.save();c.translate((getWidth()-28*s)/2,(getHeight()-28*s)/2);c.scale(s,s);
            p.setColor(color);drawGlyph(c,kind,p,path,bounds);c.restore();
        }
    }
    /** A decorative title cue only: it does not create or change task categories. */
    static final class TaskBadge extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path=new Path();private final RectF bounds=new RectF();
        private final String kind;private final int background;
        TaskBadge(Context c,String title){
            super(c);String name=title==null?"":title.toLowerCase(Locale.ROOT);
            if(name.contains("阅读")||name.contains("读书")||name.contains("看书")||wordStartsWith(name,"read")||wordStartsWith(name,"book")){
                kind="book";background=0xffD0EDE2;
            }else if(name.contains("web3")||name.contains("web 3")){
                kind="cube";background=0xffC9EAFB;
            }else if(name.contains("英语")||name.contains("english")){
                kind="english";background=0xffDFE9FA;
            }else if(name.contains("代码")||name.contains("编程")||name.contains("视频")||wordStartsWith(name,"code")||wordStartsWith(name,"coding")){
                kind="code";background=0xffFFE4BF;
            }else{kind="document";background=0xffE9EEEA;}
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private static boolean wordStartsWith(String text,String prefix){
            int from=0,index;
            while((index=text.indexOf(prefix,from))>=0){
                if(index==0||!Character.isLetter(text.charAt(index-1)))return true;
                from=index+prefix.length();
            }
            return false;
        }
        @Override protected void onMeasure(int w,int h){int preferred=dp(getContext(),44);setMeasuredDimension(resolveSize(preferred,w),resolveSize(preferred,h));}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float side=Math.min(getWidth(),getHeight()),cx=getWidth()/2f,cy=getHeight()/2f;
            p.setStyle(Paint.Style.FILL);p.setColor(background);c.drawCircle(cx,cy,side*.5f,p);p.setColor(INK);
            if("english".equals(kind)){
                p.setTypeface(MEDIUM);p.setTextSize(side*.43f);p.setTextAlign(Paint.Align.CENTER);
                c.drawText("En",cx,cy-(p.ascent()+p.descent())/2,p);
            }else{
                float glyph=side*.54f;c.save();c.translate(cx-glyph/2,cy-glyph/2);c.scale(glyph/28f,glyph/28f);
                drawGlyph(c,kind,p,path,bounds);c.restore();
            }
        }
    }
    static class Ring extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);double fraction;String main="0%",subtitle="";int color=TEAL;
        private final RectF ringBounds=new RectF();private final Path sprout=new Path();
        private final float density;private boolean percentLabel=true;
        Ring(Context c){
            super(c);density=c.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            sprout.moveTo(0,5);sprout.cubicTo(-8,5,-12,-1,-12,-8);sprout.cubicTo(-4,-8,0,-3,0,5);
            sprout.moveTo(0,1);sprout.cubicTo(0,-8,5,-12,13,-12);sprout.cubicTo(13,-4,9,2,0,1);
        }
        void set(double fraction,String main,String subtitle,boolean paused){
            this.fraction=Double.isFinite(fraction)?Math.max(0,Math.min(1,fraction)):0;
            this.main=main==null?"":main;this.subtitle=subtitle==null?"":subtitle;
            this.color=paused?0xffA4AFB8:TEAL;
            percentLabel=this.main.length()>1&&this.main.endsWith("%");
            setContentDescription(this.main+(this.subtitle.isEmpty()?"":"，"+this.subtitle));invalidate();
        }
        @Override protected void onMeasure(int w,int h){
            int width=MeasureSpec.getSize(w),height=MeasureSpec.getSize(h);
            if(MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED)height=dp(getContext(),248);
            setMeasuredDimension(width,height);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float extent=Math.min(getWidth(),getHeight());
            boolean miniature=extent<120*density;float side=Math.max(0,extent-(miniature?8:16)*density);
            if(side<=3*density)return;
            float cx=getWidth()/2f,cy=getHeight()/2f,stroke=Math.max(3*density,side*(miniature?.078f:.055f));
            float radius=(side-stroke)/2;ringBounds.set(cx-radius,cy-radius,cx+radius,cy+radius);
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xffDCE1DF);c.drawOval(ringBounds,p);p.setColor(color);
            if(fraction>0)c.drawArc(ringBounds,-90,(float)(fraction*360),false,p);
            p.setStyle(Paint.Style.FILL);p.setTypeface(MEDIUM);p.setColor(INK);
            float size=side*(main.length()>6?.18f:.275f);
            p.setTextSize(size);
            float baseline=cy-(p.ascent()+p.descent())/2-(miniature?0:side*.065f);
            drawMain(c,cx,baseline,size,side*.70f);
            if(!miniature){
                if(!subtitle.isEmpty()){
                    p.setTypeface(REGULAR);p.setTextSize(Math.min(14*density,side*.063f));p.setColor(MUTED);p.setTextAlign(Paint.Align.CENTER);
                    float width=p.measureText(subtitle);if(width>side*.72f)p.setTextSize(p.getTextSize()*side*.72f/width);
                    c.drawText(subtitle,cx,cy+side*.14f,p);
                }
                float leafScale=side*.0041f;c.save();c.translate(cx,cy+side*.275f);c.scale(leafScale,leafScale);
                p.setStyle(Paint.Style.FILL);p.setColor(color==TEAL?0xff208E77:color);c.drawPath(sprout,p);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);c.drawLine(0,2,0,13,p);c.restore();
            }
        }
        private void drawMain(Canvas c,float cx,float baseline,float size,float maxWidth){
            if(percentLabel){
                int end=main.length()-1;p.setTextSize(size);float numberWidth=p.measureText(main,0,end);
                p.setTextSize(size*.61f);float suffixWidth=p.measureText("%");float gap=size*.025f;
                float total=numberWidth+gap+suffixWidth;
                if(total>maxWidth){float fit=maxWidth/total;size*=fit;numberWidth*=fit;gap*=fit;total=maxWidth;}
                float left=cx-total/2;p.setTextAlign(Paint.Align.LEFT);p.setTextSize(size);
                c.drawText(main,0,end,left,baseline,p);p.setTextSize(size*.61f);c.drawText("%",left+numberWidth+gap,baseline,p);
            }else{
                p.setTextSize(size);float width=p.measureText(main);if(width>maxWidth)p.setTextSize(size*maxWidth/width);
                p.setTextAlign(Paint.Align.CENTER);c.drawText(main,cx,baseline,p);
            }
        }
    }
    /** Countdown fraction is remaining/planned; a ready timer is a full ring. */
    static final class HomeRing extends Ring {
        HomeRing(Context c){super(c);}
        @Override protected void onDraw(Canvas c){
            float side=Math.max(0,Math.min(Math.min(getWidth(),getHeight())-dp(getContext(),18),dp(getContext(),248))),cx=getWidth()/2f,cy=getHeight()/2f;
            if(side<8)return;float radius=side*.46f,stroke=Math.max(3,side*.034f);
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(0x94FFFFFF);c.drawCircle(cx,cy,radius,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(0xffDDE2E0);c.drawCircle(cx,cy,radius,p);
            p.setShader(new LinearGradient(cx-radius,cy-radius,cx+radius,cy+radius,color==TEAL?0xff056C73:color,color==TEAL?0xff239C88:color,Shader.TileMode.CLAMP));
            if(fraction>0)c.drawArc(new RectF(cx-radius,cy-radius,cx+radius,cy+radius),-90,(float)(fraction*360),false,p);p.setShader(null);
            float y=cy-side*.23f;float leaf=side*.033f;p.setStyle(Paint.Style.FILL);p.setColor(color==TEAL?0xff208E77:color);Path sprout=new Path();sprout.moveTo(cx,y+leaf);sprout.cubicTo(cx-leaf*1.8f,y+leaf,cx-leaf*1.6f,y-leaf,cx-leaf*1.6f,y-leaf);sprout.cubicTo(cx,y-leaf,cx,y,cx,y+leaf);sprout.moveTo(cx,y+leaf*.4f);sprout.cubicTo(cx,y-leaf*1.5f,cx+leaf*1.7f,y-leaf*1.4f,cx+leaf*1.7f,y-leaf*1.4f);sprout.cubicTo(cx+leaf*1.8f,y+leaf*.3f,cx+leaf*.5f,y+leaf*.6f,cx,y+leaf*.4f);c.drawPath(sprout,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,side*.01f));c.drawLine(cx,y+leaf*.5f,cx,y+leaf*2,p);
            p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);p.setColor(INK);p.setTextSize(side*(main.length()>6?.17f:.22f));c.drawText(main,cx,cy+side*.09f,p);
            if(side>=dp(getContext(),155)){p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(Math.min(dp(getContext(),12),side*.053f));p.setColor(MUTED);c.drawText(subtitle.isEmpty()?"专注，遇见更好的自己":subtitle,cx,cy+side*.21f,p);}
        }
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
