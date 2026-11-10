import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
public class MakeSyntheticLabels {
    static void image(Path dir,String name,String text,BarcodeFormat format)throws Exception {
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,4);
        BitMatrix bits=new MultiFormatWriter().encode(text,format,360,360,hints);
        BufferedImage image=new BufferedImage(440,440,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<440;y++)for(int x=0;x<440;x++)image.setRGB(x,y,(x>=40&&y>=40&&x<400&&y<400&&bits.get(x-40,y-40))?0:0xffffff);
        ImageIO.write(image,"png",dir.resolve(name+".png").toFile());
    }
    public static void main(String[] args)throws Exception {
        Path dir=Paths.get(args[0]);Files.createDirectories(dir);
        image(dir,"pcb-ZZZ00002","ZZZ00002",BarcodeFormat.DATA_MATRIX);
        String qr="OZAK POWER\nID=LEG-TEST-01\nPN=TEST-RESISTOR\nOZ=TESTRES\nQ=100\nT=REEL\nN=LEGACY\nW=TEST\nL=TEST-A01\nLN=Synthetic location\nLOT=UNKNOWN\nDC=UNKNOWN\nORIGIN=LEGACY_STOCK";
        image(dir,"reel-LEG-TEST-01",qr,BarcodeFormat.QR_CODE);
        Files.write(dir.resolve("index.html"),("<!doctype html><html lang='en'><meta charset='utf-8'><title>OZAK Synthetic Labels</title><style>body{font:20px sans-serif;text-align:center;background:white;color:black}section{display:inline-block;margin:16px;vertical-align:top}img{width:440px;height:440px;image-rendering:pixelated}@media print{button{display:none}}</style><h1>TEST LABELS — NO REAL STOCK</h1><p>Use only with OZAK Sandbox NEXT. Show one code to the camera at a time.</p><section><h2>PCB / DataMatrix</h2><img src='pcb-ZZZ00002.png'><p>ZZZ00002</p></section><section><h2>Reel / QR</h2><img src='reel-LEG-TEST-01.png'><p>LEG-TEST-01 / 100 PCS</p></section><p><button onclick='print()'>Print synthetic labels</button></p></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Synthetic DataMatrix and QR labels generated.");
    }
}
