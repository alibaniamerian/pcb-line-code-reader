package com.ozak.inventory;
import org.json.*;
import java.util.*;

/** Contract v1.1 validation; no order, station or product identity is compiled in. */
final class OperatorRules {
    static boolean mode(JSONObject value){return value!=null&&("OBSERVATION".equals(value.optString("mode"))||"CONTROL".equals(value.optString("mode")))&&value.opt("scans_required_for_production") instanceof Boolean;}
    static boolean order(JSONObject o){return mode(o)&&o.optLong("order_id")>0&&!o.optString("order_number").isEmpty()&&o.optLong("product_item_id")>0&&o.optLong("warehouse_id")>0&&o.optInt("quantity")>0&&o.opt("accepting_scans") instanceof Boolean;}
    static boolean route(JSONObject r,long order){return mode(r)&&r.optLong("order_id")==order&&r.optInt("route_version")>0&&r.optJSONArray("steps")!=null&&r.optJSONArray("stations")!=null;}
    static boolean same(Object a,Object b)throws JSONException {
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;Iterator<String> keys=x.keys();while(keys.hasNext()){String k=keys.next();if(!y.has(k)||!same(x.get(k),y.get(k)))return false;}return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
        return Objects.equals(a,b);
    }
    static boolean success(JSONObject command,long actor,JSONObject result)throws JSONException {
        String outcome=result.optString("outcome");return command.getJSONObject("payload").getString("request_id").equals(result.optString("request_id"))
            &&command.getLong("order_id")==result.optLong("order_id")&&actor==result.optLong("recorded_by_user_id")
            &&!result.optString("event_id").isEmpty()&&!result.optString("recorded_at").isEmpty()
            &&("ACCEPTED_NEW".equals(outcome)||"ALREADY_RECORDED".equals(outcome));
    }
}
