package com.ozak.inventory;
import org.json.*;

final class StageRules {
    static boolean boardOperation(String op){return "PCB_ENTRY".equals(op)||"STAGE_PASSAGE".equals(op);}
    static boolean contextMatches(JSONObject command,JSONObject context,JSONObject route,long order)throws JSONException {
        return command.getLong("order_id")==order&&context.getLong("order_id")==order
            &&command.getJSONObject("query").getString("pcb_code").equals(context.getString("pcb_code"))
            &&context.getInt("route_version")==route.getInt("route_version")
            &&context.opt("registered") instanceof Boolean&&context.optJSONArray("steps")!=null
            &&context.has("next_expected_step")&&context.has("expected_side");
    }
    static String validate(JSONObject step,String outcome,String notes,String thickness){
        if(step.optBoolean("requires_result")&&outcome==null)return "Select PASS or FAIL for this inspection.";
        if(outcome!=null&&!"PASS".equals(outcome)&&!"FAIL".equals(outcome))return "Invalid result.";
        if(outcome!=null&&!step.optBoolean("requires_result")&&!step.optBoolean("allows_paste_measurement"))return "This stage records passage only.";
        if(notes.length()>2000)return "Notes must be at most 2000 characters.";
        if("FAIL".equals(outcome)&&notes.trim().isEmpty())return "Describe the observed defect for FAIL.";
        if(!thickness.isEmpty()){
            if(!step.optBoolean("allows_paste_measurement"))return "Paste measurement is not allowed at this stage.";
            try{double value=Double.parseDouble(thickness);if(Double.isNaN(value)||Double.isInfinite(value)||value<0||value>1000000)return "Enter a finite thickness from 0 to 1000000 micrometres.";}catch(Exception e){return "Enter a numeric thickness in micrometres.";}
        }
        return null;
    }
    static boolean canSkip(JSONObject route,JSONObject rejected,JSONObject error,long selectedOrder){return route!=null&&"OBSERVATION".equals(route.optString("mode"))&&rejected!=null&&"STAGE_PASSAGE".equals(rejected.optString("operation"))&&selectedOrder==rejected.optLong("order_id")&&error!=null&&"STEP_OUT_OF_ORDER".equals(error.optString("code"))&&error.optJSONObject("details")!=null;}
    static JSONObject skippedPayload(JSONObject rejected,String reason)throws JSONException {
        reason=reason.trim();if(reason.isEmpty()||reason.length()>1000)throw new JSONException("A reason of 1–1000 characters is required.");
        JSONObject payload=new JSONObject(rejected.getJSONObject("payload").toString());payload.remove("request_id");payload.put("allow_skip",true).put("skip_reason",reason);return payload;
    }
}
