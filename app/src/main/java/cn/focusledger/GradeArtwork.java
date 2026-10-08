package cn.focusledger;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact user-provided PNGs. Optical bounds affect placement only; no pixels are trimmed or recolored. */
public final class GradeArtwork {
    public static final long MAX_SPRITE_PIXELS = 512L * 1024;
    public static final float SAFE_LEFT = 408, SAFE_TOP = 420, SAFE_RIGHT = 1056, SAFE_BOTTOM = 889;
    public static final class Spec {
        public final String grade, fileName, sha256;
        public final int width, height, left, top, right, bottom;
        Spec(String grade, String fileName, String sha256, int width, int height,
             int left, int top, int right, int bottom) {
            this.grade=grade; this.fileName=fileName; this.sha256=sha256; this.width=width; this.height=height;
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
        public String assetPath() { return "export/grades/" + fileName; }
    }
    public static final class Placement {
        public final float left, top, width, height, scale;
        Placement(float left, float top, float width, float height, float scale) {
            this.left=left; this.top=top; this.width=width; this.height=height; this.scale=scale;
        }
        public float right() { return left+width; }
        public float bottom() { return top+height; }
    }
    private static final List<Spec> SPECS;
    static {
        List<Spec> specs=new ArrayList<>();
        specs.add(new Spec("SSS+", "SSS-plus.png", "afbb67aa159c7c952dddc61fb7d0f41261b18fbf6c21d379cd87b716777cd92e", 1536, 1024, 30, 81, 1506, 968));
        specs.add(new Spec("SSS", "SSS.png", "de73c13fee15beb2fee2dd0c3578f9120d6b7f7fe6d8376849c01d7cc5f76daf", 1536, 1024, 51, 99, 1480, 933));
        specs.add(new Spec("SS", "SS.png", "3f670eb01b2051c447f3ff48d6211d73a56c620613d9b880f02b90d6e11f8c4e", 1536, 1024, 113, 86, 1453, 938));
        specs.add(new Spec("S", "S.png", "2432bc7aad69e49ca4ec76cbb85ce432f71ec1ca9ba209c9512fe4392db396b6", 1145, 1374, 32, 67, 1124, 1330));
        specs.add(new Spec("A+", "A-plus.png", "4c824bf7eeb601aefac483a8d78cb3da36b243626042339bcbf8a3d639f61604", 1536, 1024, 130, 70, 1456, 967));
        specs.add(new Spec("A", "A.png", "074794fc096db2876e1c15b2d3a0a6561040426d3bccfb98c73805c0033a75d8", 1536, 1024, 395, 52, 1181, 1000));
        specs.add(new Spec("A-", "A-minus.png", "0dbe63ab739f344283dd22347758b524f252957ff30a5fc5a71b9bfa7308fcf9", 1536, 1024, 223, 41, 1390, 991));
        specs.add(new Spec("B+", "B-plus.png", "0b12012a6ff4a5ef403788028356eb6cbf488ed94f9412b9ee4fb237fa963d92", 1536, 1024, 133, 56, 1426, 989));
        specs.add(new Spec("B", "B.png", "ca079785fe7f8e9c66d7a858ff775a9b9f3d328de6f9b5f5bc270d56865fe5e1", 1536, 1024, 323, 28, 1234, 993));
        specs.add(new Spec("B-", "B-minus.png", "6044f42ba69ea37bc06ba7be6224e7f2c251e7db63b12044810a8d19abfda603", 1536, 1024, 113, 31, 1436, 998));
        specs.add(new Spec("C", "C.png", "0e57448e3c9b34f3455decb1f4840df794dcbd49b0817758d3950ff106b90a5d", 1536, 1024, 327, 36, 1258, 982));
        specs.add(new Spec("D", "D.png", "b125e8f1369bee655a12d215d237b0d072456e99a7e3bb00e25e421dcdd9ef2c", 1536, 1024, 300, 74, 1323, 959));
        specs.add(new Spec("E", "E.png", "9972a8da529949bbf10a86e2d2b8fff0f925049281e25c62770a664874a264a2", 1225, 1284, 151, 92, 1191, 1251));
        SPECS=Collections.unmodifiableList(specs);
    }
    private GradeArtwork() {}
    public static List<Spec> all() { return SPECS; }
    public static Spec forGrade(String grade) {
        if ("未知".equals(grade)) return null;
        for(Spec spec:SPECS) if(spec.grade.equals(grade)) return spec;
        throw new IllegalArgumentException("无法识别结算等级");
    }
    /** Normalize visible ink size, then constrain the complete original canvas including glow/spray. */
    public static Placement placement(Spec spec) {
        float scale=Math.min(600f/(spec.right-spec.left),440f/(spec.bottom-spec.top));
        scale=Math.min(scale,Math.min((SAFE_RIGHT-SAFE_LEFT)/spec.width,(SAFE_BOTTOM-SAFE_TOP)/spec.height));
        float width=spec.width*scale,height=spec.height*scale;
        float x=738-(spec.left+spec.right)*.5f*scale,y=666-(spec.top+spec.bottom)*.5f*scale;
        x=Math.max(SAFE_LEFT,Math.min(x,SAFE_RIGHT-width));
        y=Math.max(SAFE_TOP,Math.min(y,SAFE_BOTTOM-height));
        return new Placement(x,y,width,height,scale);
    }
    public static int sampleSize(int width, int height) throws IOException {
        if(width<=0||height<=0)throw new IOException("无法读取导出字形");
        int sample=1;
        while(((width+(long)sample-1)/sample)*((height+(long)sample-1)/sample)>MAX_SPRITE_PIXELS) {
            if(sample>1<<29)throw new IOException("导出字形尺寸过大"); sample*=2;
        }
        return sample;
    }
    private static Bitmap decode(AssetManager assets,String path,int expectedWidth,int expectedHeight,Runnable cancel) throws IOException {
        RangeSummary.check(cancel);
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;options.inScaled=false;
        try(InputStream input=assets.open(path)){BitmapFactory.decodeStream(input,null,options);}
        if(expectedWidth>0&&(options.outWidth!=expectedWidth||options.outHeight!=expectedHeight))
            throw new IOException("导出字形尺寸与原图不一致");
        options.inSampleSize=sampleSize(options.outWidth,options.outHeight);
        options.inJustDecodeBounds=false; options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        RangeSummary.check(cancel);Bitmap result;
        try(InputStream input=assets.open(path)){result=BitmapFactory.decodeStream(input,null,options);}
        if(result==null)throw new IOException("无法读取导出字形");
        if(!result.hasAlpha()||result.getAllocationByteCount()>MAX_SPRITE_PIXELS*4L){result.recycle();throw new IOException("导出字形透明度或内存校验失败");}
        try{RangeSummary.check(cancel);return result;}catch(RuntimeException stopped){result.recycle();throw stopped;}
    }
    public static void draw(AssetManager assets, Canvas canvas, Spec spec, Runnable cancel) throws IOException {
        Placement place=placement(spec); Bitmap image=decode(assets,spec.assetPath(),spec.width,spec.height,cancel);
        try {
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColor(0xffffffff);
            canvas.drawBitmap(image,null,new RectF(place.left,place.top,place.right(),place.bottom()),paint);
            RangeSummary.check(cancel);
        } finally {image.recycle();}
    }
    public static void drawDailyTitle(AssetManager assets,Canvas canvas,Runnable cancel) throws IOException {
        Bitmap title=decode(assets,"export/lettering/title_today.png",1536,1024,cancel);
        try {
            RectF slot=new RectF(36,20,616,386);
            float scale=Math.min(slot.width()/title.getWidth(),slot.height()/title.getHeight());
            float w=title.getWidth()*scale,h=title.getHeight()*scale;
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColor(0xffffffff);
            canvas.drawBitmap(title,null,new RectF(slot.centerX()-w/2,slot.centerY()-h/2,slot.centerX()+w/2,slot.centerY()+h/2),paint);
            RangeSummary.check(cancel);
        } finally {title.recycle();}
    }
}
