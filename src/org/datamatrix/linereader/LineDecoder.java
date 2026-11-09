package org.datamatrix.linereader;

import com.google.zxing.*;
import com.google.zxing.common.*;
import java.util.*;

/** Only the cropped square enters this decoder; the chosen format is exclusive. */
final class LineDecoder {
    static String decode(byte[] pixels,int width,int height){
        return decode(pixels,width,height,BarcodeFormat.DATA_MATRIX);
    }
    static String decode(byte[] pixels,int width,int height,BarcodeFormat format){
        if(format!=BarcodeFormat.DATA_MATRIX&&format!=BarcodeFormat.QR_CODE)throw new IllegalArgumentException("Unsupported scan format");
        EnumMap<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(format));
        hints.put(DecodeHintType.TRY_HARDER,true);
        String found=attempt(pixels,width,height,hints);if(found!=null)return found;
        // Local illumination correction preserves observed marks; the decoder still
        // validates every returned payload. Never synthesize individual cells.
        for(int window:new int[]{15,31}){
            found=attempt(adaptive(pixels,width,height,window),width,height,hints);
            if(found!=null)return found;
        }
        return null;
    }
    static byte[] adaptive(byte[] input,int width,int height,int window){
        long[] integral=new long[(width+1)*(height+1)];
        for(int y=0;y<height;y++){long sum=0;for(int x=0;x<width;x++){sum+=input[y*width+x]&255;integral[(y+1)*(width+1)+x+1]=integral[y*(width+1)+x+1]+sum;}}
        byte[] output=new byte[input.length];int radius=window/2;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int x0=Math.max(0,x-radius),x1=Math.min(width,x+radius+1),y0=Math.max(0,y-radius),y1=Math.min(height,y+radius+1);
            long sum=integral[y1*(width+1)+x1]-integral[y0*(width+1)+x1]-integral[y1*(width+1)+x0]+integral[y0*(width+1)+x0];
            output[y*width+x]=(byte)((input[y*width+x]&255)<sum/((x1-x0)*(y1-y0))-3?0:255);
        }
        return output;
    }
    static String attempt(byte[] pixels,int width,int height,EnumMap<DecodeHintType,Object> hints){
        LuminanceSource source=new PlanarYUVLuminanceSource(pixels,width,height,0,0,width,height,false);
        for(int inverted=0;inverted<2;inverted++){
            LuminanceSource image=inverted==0?source:source.invert();
            for(int threshold=0;threshold<2;threshold++){
                MultiFormatReader reader=new MultiFormatReader();
                try{return reader.decode(new BinaryBitmap(threshold==0?new HybridBinarizer(image):new GlobalHistogramBinarizer(image)),hints).getText();}
                catch(ReaderException ignored){}finally{reader.reset();}
            }
        }
        return null;
    }
}
