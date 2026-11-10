package com.ozak.inventory;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** One durable unresolved command; never contains authentication material. */
final class OperatorJournal {
    private final AtomicFile file;
    private JSONObject state;
    OperatorJournal(File dir)throws Exception{file=new AtomicFile(new File(dir,"operator-pending.json"));state=file.getBaseFile().exists()?new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8)):new JSONObject();}
    boolean pending(){return state.has("command");}
    boolean belongs(long actor){return !pending()||state.optLong("actor")==actor;}
    JSONObject command()throws JSONException{return new JSONObject(state.getJSONObject("command").toString());}
    String order(){return state.optString("order_number","Saved order");}
    void begin(long actor,JSONObject order,JSONObject command)throws Exception{
        if(pending())throw new IllegalStateException("Unresolved command");
        JSONObject next=new JSONObject().put("actor",actor).put("order_number",order.getString("order_number")).put("command",new JSONObject(command.toString()));save(next);state=next;
    }
    void resolve()throws Exception{JSONObject next=new JSONObject();save(next);state=next;}
    private void save(JSONObject next)throws Exception{FileOutputStream stream=null;try{stream=file.startWrite();stream.write(next.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception e){if(stream!=null)file.failWrite(stream);throw e;}}
}
