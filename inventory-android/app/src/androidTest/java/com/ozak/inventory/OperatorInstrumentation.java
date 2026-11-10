package com.ozak.inventory;
import android.app.*;
import android.os.Bundle;
import java.io.File;
import java.util.UUID;
import org.json.*;

/** Runs on the phone without opening a network connection or touching production data. */
public final class OperatorInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        JSONObject order=new JSONObject().put("order_id",987).put("order_number","SYNTHETIC-OPERATOR").put("product_item_id",12).put("warehouse_id",34).put("quantity",2).put("accepting_scans",true).put("mode","OBSERVATION").put("scans_required_for_production",false);
        check(OperatorRules.order(order),"Observation order accepted without scans");
        JSONObject malformed=new JSONObject(order.toString());malformed.remove("mode");check(!OperatorRules.order(malformed),"Missing mode is not invented");
        JSONObject route=new JSONObject().put("order_id",987).put("route_version",4).put("mode","OBSERVATION").put("scans_required_for_production",false).put("steps",new JSONArray()).put("stations",new JSONArray());
        check(OperatorRules.route(route,987),"Server route identity");check(!OperatorRules.route(route,988),"Wrong order route rejected");
        String id=UUID.randomUUID().toString();JSONObject command=new JSONObject().put("operation","PCB_ENTRY").put("order_id",987).put("payload",new JSONObject().put("request_id",id).put("route_version",4).put("step_id","server-stage").put("physical_station_id","server-station").put("selected_side",JSONObject.NULL).put("pcb_code","ZZZ00002"));
        File dir=new File(getTargetContext().getCacheDir(),"operator-journal-test-"+UUID.randomUUID());if(!dir.mkdir())throw new Exception("Test storage");
        OperatorJournal journal=new OperatorJournal(dir);check(!journal.pending(),"Fresh journal");journal.begin(7,order,command);
        OperatorJournal restarted=new OperatorJournal(dir);check(restarted.pending(),"Unresolved request survives reopen");check(restarted.belongs(7)&&!restarted.belongs(8),"Recovery actor binding");
        check(OperatorRules.same(command,restarted.command()),"Exact payload and request ID preserved");
        boolean blocked=false;try{restarted.begin(7,order,command);}catch(IllegalStateException e){blocked=true;}check(blocked,"No new command replaces unresolved command");
        JSONObject response=new JSONObject().put("request_id",id).put("order_id",987).put("recorded_by_user_id",7).put("recorded_at","2026-11-10T00:00:00Z").put("event_id","synthetic-event").put("outcome","ACCEPTED_NEW");
        check(OperatorRules.success(command,7,response),"Valid correlated acceptance");
        response.put("request_id",UUID.randomUUID().toString());check(!OperatorRules.success(command,7,response),"Unrelated success rejected");
        response.put("request_id",id).put("recorded_by_user_id",8);check(!OperatorRules.success(command,7,response),"Wrong actor rejected");
        response.put("recorded_by_user_id",7).put("outcome","ALREADY_RECORDED");check(OperatorRules.success(command,7,response),"Duplicate result identified");
        restarted.resolve();check(!new OperatorJournal(dir).pending(),"Resolution durable");
        check(!OperatorBridge.WRITES.containsKey("USAGE_PLAN"),"No consumption or usage-plan operation");
        check(OperatorBridge.READS.contains("BOARD_CONTEXT")&&OperatorBridge.WRITES.containsKey("STAGE_PASSAGE"),"Published stage operations only");
        JSONObject inspection=new JSONObject().put("requires_result",true).put("allows_paste_measurement",false);
        JSONObject stencilExit=new JSONObject().put("requires_result",false).put("allows_paste_measurement",true);
        JSONObject passage=new JSONObject().put("requires_result",false).put("allows_paste_measurement",false);
        check(StageRules.validate(inspection,null,"","")!=null,"Inspection cannot silently become PASS");
        check(StageRules.validate(inspection,"FAIL","   ","")!=null,"FAIL requires defect notes");
        check(StageRules.validate(inspection,"FAIL","Bridge observed","")==null,"Explicit FAIL with notes");
        check(StageRules.validate(passage,null,"","")==null,"Passage without quality result");
        check(StageRules.validate(passage,"PASS","","")!=null,"Passage is not PASS");
        check(StageRules.validate(stencilExit,null,"","120.5")==null,"Optional measured thickness");
        check(StageRules.validate(stencilExit,null,"","NaN")!=null&&StageRules.validate(stencilExit,null,"","-1")!=null,"Invalid measurements rejected");
        JSONObject stageCommand=new JSONObject(command.toString()).put("operation","STAGE_PASSAGE");
        JSONObject error=new JSONObject().put("code","STEP_OUT_OF_ORDER").put("details",new JSONObject().put("missing_step_ids",new JSONArray().put("earlier-server-step")));
        check(StageRules.canSkip(route,stageCommand,error,987),"Observation skip requires server rejection");
        JSONObject controlled=new JSONObject(route.toString()).put("mode","CONTROL");check(!StageRules.canSkip(controlled,stageCommand,error,987),"CONTROL cannot skip");
        check(!StageRules.canSkip(route,stageCommand,error,988),"Skip bound to same order");
        JSONObject skip=StageRules.skippedPayload(stageCommand,"Operator observed missed scan");check(!skip.has("request_id")&&skip.getBoolean("allow_skip"),"Changed payload cannot reuse rejected UUID");
        check(stageCommand.getJSONObject("payload").getString("request_id").equals(id),"Rejected command remains unchanged");
        boolean reasonRequired=false;try{StageRules.skippedPayload(stageCommand," ");}catch(JSONException e){reasonRequired=true;}check(reasonRequired,"Skip needs explicit reason");
        JSONObject contextCommand=new JSONObject().put("order_id",987).put("query",new JSONObject().put("pcb_code","ZZZ00002"));
        JSONObject context=new JSONObject().put("order_id",987).put("pcb_code","ZZZ00002").put("route_version",4).put("registered",true).put("steps",new JSONArray()).put("next_expected_step","server-stage").put("expected_side","BOTTOM");
        check(StageRules.contextMatches(contextCommand,context,route,987),"Correlated board context");
        context.put("pcb_code","ZZZ00003");check(!StageRules.contextMatches(contextCommand,context,route,987),"Other PCB context rejected");
        context.put("pcb_code","ZZZ00002").put("route_version",5);check(!StageRules.contextMatches(contextCommand,context,route,987),"Stale route context rejected");
        OperatorJournal stageJournal=new OperatorJournal(new File(dir,"stage"));
        new File(dir,"stage").mkdirs();stageJournal.begin(7,order,stageCommand);
        check(OperatorRules.same(stageCommand,new OperatorJournal(new File(dir,"stage")).command()),"Stage retry preserves exact command after reopen");
        result.putString("stream","PASS: "+checks+" operator contract, durable-journal and response-identity checks. No network operations.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e.getClass().getSimpleName()+" / "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
