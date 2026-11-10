package org.datamatrix.linereader;

import android.content.Context;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.*;

/** Localization and surface correction from observed pixels; ECC200 verifies text. */
final class MarkedDecoder {
    static String decode(Context context,byte[] pixels,int width,int height)throws Exception{
        if(!Python.isStarted())Python.start(new AndroidPlatform(context.getApplicationContext()));
        JSONArray grids=new JSONArray(Python.getInstance().getModule("line_grid").callAttr("grids",pixels,width,height).toString());
        for(int i=0;i<grids.length();i++){
            JSONObject grid=grids.getJSONObject(i);GridDecoder.decode(grid);
            if(grid.optBoolean("decoded"))return grid.getString("text");
        }
        return null;
    }
}

