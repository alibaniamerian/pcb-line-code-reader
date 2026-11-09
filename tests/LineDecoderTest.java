package org.datamatrix.linereader;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.util.Arrays;

public final class LineDecoderTest {
    public static void main(String[] args)throws Exception{
        String text="PCB-000123";
        BitMatrix matrix=new MultiFormatWriter().encode(text,BarcodeFormat.DATA_MATRIX,180,180);
        int size=240;
        for(int inverted=0;inverted<2;inverted++)for(int rotation=0;rotation<4;rotation++){
            byte[] pixels=new byte[size*size];Arrays.fill(pixels,(byte)(inverted==0?230:25));
            for(int y=0;y<180;y++)for(int x=0;x<180;x++){
                int px=x,py=y;for(int r=0;r<rotation;r++){int tmp=px;px=179-py;py=tmp;}
                pixels[(py+30)*size+px+30]=(byte)(matrix.get(x,y)?(inverted==0?25:230):(inverted==0?230:25));
            }
            String found=LineDecoder.decode(pixels,size,size);
            if(!text.equals(found))throw new AssertionError("Failed rotation="+rotation+", polarity="+inverted+": "+found);
        }
        byte[] blank=new byte[size*size];Arrays.fill(blank,(byte)180);
        if(LineDecoder.decode(blank,size,size)!=null)throw new AssertionError("False read on blank");
        BitMatrix qr=new MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,size,size);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)blank[y*size+x]=(byte)(qr.get(x,y)?0:255);
        if(LineDecoder.decode(blank,size,size)!=null)throw new AssertionError("Accepted QR instead of DataMatrix");
        for(int inverted=0;inverted<2;inverted++)for(int rotation=0;rotation<4;rotation++){
            byte[] pixels=new byte[size*size];
            for(int y=0;y<size;y++)for(int x=0;x<size;x++){
                int px=x,py=y;for(int r=0;r<rotation;r++){int tmp=px;px=size-1-py;py=tmp;}
                pixels[py*size+px]=(byte)(qr.get(x,y)?(inverted==0?20:235):(inverted==0?235:20));
            }
            if(!text.equals(LineDecoder.decode(pixels,size,size,BarcodeFormat.QR_CODE)))throw new AssertionError("QR orientation/polarity failed");
        }
        Arrays.fill(blank,(byte)255);
        for(int y=0;y<180;y++)for(int x=0;x<180;x++)blank[(y+30)*size+x+30]=(byte)(matrix.get(x,y)?0:255);
        if(LineDecoder.decode(blank,size,size,BarcodeFormat.QR_CODE)!=null)throw new AssertionError("QR mode accepted DataMatrix");
        Arrays.fill(blank,(byte)180);
        if(LineDecoder.decode(blank,size,size,BarcodeFormat.QR_CODE)!=null)throw new AssertionError("QR mode read blank image");
        System.out.println("PASS: DataMatrix and QR exact payloads, four rotations, both polarities, exclusive formats and blank rejection.");
    }
}
