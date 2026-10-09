package com.sysprobe.svc.util;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Created by blanke on 2017/10/5.
 */

public class ColorUtils {
    public static int getDarkerColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = hsv[2] * factor;
        return Color.HSVToColor(hsv);
    }
    
//    // 性能不行
//    //图片色阶调整，调整rgb的分量
//    public static Bitmap img_color_gradation(Bitmap imgsrc,float factor) {
//        try {
//            //直接创建一个位图
////            Bitmap back = Bitmap.createBitmap(imgsrc);
//            Bitmap back=Bitmap.createBitmap(imgsrc.getWidth(), imgsrc.getHeight(),Bitmap.Config.ARGB_8888);
//
//            int width = imgsrc.getWidth();
//            int height = imgsrc.getHeight();
//            for (int i = 0; i < height; i++) {
//                for (int j = 0; j < width; j++) {
//                    int color = imgsrc.getPixel(j, i);
////                    int red= color.getRed()+r; if(red>255) red=255; if(red<0) red=0;
////                    int green= color.getGreen()+g; if(green>255) green=255; if(green<0) green=0;
////                    int blue= color.getBlue()+b; if(blue>255) blue=255; if(blue<0) blue=0;
////                    color = new Color(red,green,blue);
//                    color=getDarkerColor(color,factor);
////                    int x=color.getRGB();
//                    back.setPixel(j,i,color);
//                }
//            }
//            return back;
//        } catch (Exception e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
}
