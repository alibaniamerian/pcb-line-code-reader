package com.ozak.inventory;
import org.json.*;
import java.util.*;
/** Synthetic single-user stage API acceptance; excluded from production. */
final class StageScenario {
 final JSONObject state; StageScenario(JSONObject s){state=s;}
 int step(){return state.optInt("step");} boolean done(){return step()>=36;}
 static void require(boolean b,String s){SandboxScenario.require(b,s);}
 static boolean same(Object a,Object b)throws JSONException{return SandboxScenario.same(a,b);}
 static JSONObject copy(JSONObject o)throws JSONException{return new JSONObject(o.toString());}
 String title(){String[] a={"Synthetic order","Eight-stage route","Package baseline","Unregistered context","PCB entry","Entry retry","Duplicate PCB","Wrong side","Wrong station","Bottom P&P measurement","Bottom oven","Required inspection result","Required FAIL notes","Bottom inspection FAIL","Top stencil","Top P&P passage only","Top oven","Top inspection PASS","Persistent FAIL and eight columns","Stage retry","Reject changed result","Completed board context","Second PCB entry","Reject skipped stages","Confirm observation skip","Reject backfill","Unrecorded cells remain","Receive first reel","Reel retry","Other reel unchanged","Recover rejected post-skip passage","Exact rejected-stage retry","Confirm continued skip / lose response","Recovered stage retry","Final PCB progress","Final package stock"};return done()?"Complete":a[step()];}
 JSONObject read(String op)throws JSONException{JSONObject c=new JSONObject().put("operation",op);if(!op.equals("ORDERS"))c.put("order_id",state.getLong("order"));return c;}
 JSONObject scan(int i,String code)throws JSONException{
  JSONObject s=i<0?state.getJSONObject("packageStep"):state.getJSONArray("steps").getJSONObject(i);String station=null;JSONArray ts=state.getJSONArray("stations");
  for(int k=0;k<ts.length();k++){JSONObject t=ts.getJSONObject(k);JSONArray ids=t.getJSONArray("step_ids");for(int j=0;j<ids.length();j++)if(t.getBoolean("enabled")&&ids.getString(j).equals(s.getString("step_id")))station=t.getString("station_id");}
  require(station!=null,"Route station");String op=s.getString("operation");JSONObject p=new JSONObject().put("request_id",UUID.randomUUID().toString()).put("route_version",state.getInt("version")).put("step_id",s.getString("step_id")).put("physical_station_id",station).put("selected_side",s.get("side")).put(i<0?"scan_text":"pcb_code",code);
  if(op.equals("STAGE_PASSAGE"))p.put("outcome",JSONObject.NULL).put("notes","").put("allow_skip",false).put("skip_reason","");return read(op).put("payload",p);
 }
 JSONObject stage(int i,String code,String outcome,String notes)throws JSONException{JSONObject c=scan(i,code);c.getJSONObject("payload").put("outcome",outcome==null?JSONObject.NULL:outcome).put("notes",notes);return c;}
 JSONObject command()throws JSONException{
  if(state.has("current"))return state.getJSONObject("current");JSONObject c;switch(step()){
   case 0:c=read("ORDERS");break;case 1:c=read("ROUTE");break;
   case 2:case 29:case 35:c=read("PACKAGES");break;
   case 3:case 21:c=read("BOARD_CONTEXT").put("query",new JSONObject().put("pcb_code","ZZZ00001"));break;
   case 4:case 6:c=scan(0,"ZZZ00001");break;case 5:c=copy(state.getJSONObject("entryCommand"));break;
   case 7:c=scan(1,"ZZZ00001");c.getJSONObject("payload").put("selected_side","TOP");break;
   case 8:c=scan(1,"ZZZ00001");c.getJSONObject("payload").put("physical_station_id",state.getJSONObject("entryCommand").getJSONObject("payload").getString("physical_station_id"));break;
   case 9:c=stage(1,"ZZZ00001","PASS","");c.getJSONObject("payload").put("paste_thickness_um",120.5);break;
   case 10:c=scan(2,"ZZZ00001");break;case 11:c=scan(3,"ZZZ00001");break;
   case 12:c=stage(3,"ZZZ00001","FAIL","");break;case 13:c=stage(3,"ZZZ00001","FAIL","Synthetic solder bridge");break;
   case 14:c=scan(4,"ZZZ00001");break;case 15:c=scan(5,"ZZZ00001");break;case 16:c=scan(6,"ZZZ00001");break;
   case 17:c=stage(7,"ZZZ00001","PASS","");break;case 18:case 26:case 34:c=read("PROGRESS");break;
   case 19:c=copy(state.getJSONObject("finalCommand"));break;case 20:c=stage(7,"ZZZ00001","FAIL","Changed result must be rejected");break;
   case 22:c=scan(0,"ZZZ00002");break;case 23:c=stage(3,"ZZZ00002","PASS","");break;
   case 24:c=copy(state.getJSONObject("skipRejected"));c.getJSONObject("payload").put("request_id",UUID.randomUUID().toString()).put("allow_skip",true).put("skip_reason","Explicit synthetic observation skip");break;
   case 25:c=scan(1,"ZZZ00002");break;case 27:c=scan(-1,"OZAK POWER\nID=LEG-TEST-01\n");break;
   case 28:c=copy(state.getJSONObject("receiptCommand"));break;case 30:c=scan(4,"ZZZ00002");break;case 31:c=copy(state.getJSONObject("postSkipRejected"));break;case 32:c=scan(4,"ZZZ00002");c.getJSONObject("payload").put("allow_skip",true).put("skip_reason","Explicit synthetic continuation with still-missing earlier stages");break;case 33:c=copy(state.getJSONObject("recoveredCommand"));break;
   default:throw new IllegalStateException("Complete");
  }state.put("current",c);return c;
 }
 void accepted(int h,JSONObject d)throws JSONException{JSONObject c=state.getJSONObject("current");require(h==200||h==201,"Success status");require(c.getJSONObject("payload").getString("request_id").equals(d.getString("request_id")),"Request identity");require(d.getLong("order_id")==state.getLong("order")&&d.getLong("recorded_by_user_id")==state.getLong("actor"),"Same user/order");require("ACCEPTED_NEW".equals(d.getString("outcome"))||"ALREADY_RECORDED".equals(d.getString("outcome")),"Outcome");}
 void error(int h,JSONObject d,String code)throws JSONException{require(h>=400&&h<500&&code.equals(d.getJSONObject("error").getString("code")),code);}
 JSONObject find(JSONArray a,String key,String value)throws JSONException{JSONObject found=null;for(int i=0;i<a.length();i++)if(value.equals(a.getJSONObject(i).getString(key))){require(found==null,"Unique row");found=a.getJSONObject(i);}require(found!=null,"Row found");return found;}
 void fullFail(JSONObject row)throws JSONException{JSONArray cells=row.getJSONArray("steps");require(cells.length()==8&&row.getBoolean("route_completed")&&row.getBoolean("has_failure")&&"FAIL".equals(row.getString("quality_status")),"FAIL persists after full route");for(int i=0;i<8;i++){JSONObject cell=cells.getJSONObject(i);require(cell.getString("step_id").equals(state.getJSONArray("steps").getJSONObject(i).getString("step_id")),"Column order");require(cell.getLong("recorded_by_user_id")==state.getLong("actor")&&!cell.isNull("recorded_at"),"User/time audit");}require("FAIL".equals(cells.getJSONObject(3).getString("status"))&&"PASS".equals(cells.getJSONObject(7).getString("status")),"Earlier FAIL retained");require(cells.getJSONObject(1).getDouble("paste_thickness_um")==120.5&&"RECORDED".equals(cells.getJSONObject(5).getString("status")),"Measurement and passage distinct");}
 String accept(int h,JSONObject d)throws JSONException{int n=step();JSONObject c=state.getJSONObject("current");switch(n){
  case 0:{require(h==200,"Orders");JSONArray a=d.getJSONArray("items");require(a.length()==1&&a.getJSONObject(0).getString("order_number").equals("TEST-MOBILE-001"),"Only synthetic order");state.put("order",a.getJSONObject(0).getLong("order_id"));break;}
  case 1:{require(h==200&&"OBSERVATION".equals(d.getString("mode"))&&!d.getBoolean("scans_required_for_production"),"Observation mode");List<JSONObject> list=new ArrayList<>();JSONArray a=d.getJSONArray("steps");for(int i=0;i<a.length();i++){JSONObject s=a.getJSONObject(i);if("PCB".equals(s.getString("scope")))list.add(s);else if("PACKAGE_RECEIPT".equals(s.getString("operation")))state.put("packageStep",s);}list.sort(Comparator.comparingInt(s->s.optInt("sequence")));require(list.size()==8,"Eight steps");JSONArray ordered=new JSONArray();for(int i=0;i<8;i++){JSONObject s=list.get(i);require(s.getString("side").equals(i<4?"BOTTOM":"TOP"),"Ordered sides");ordered.put(s);}state.put("steps",ordered).put("stations",d.getJSONArray("stations")).put("version",d.getInt("route_version"));break;}
  case 2:require(h==200,"Packages");state.put("baseline",d.getJSONArray("items"));break;
  case 3:require(h==200&&!d.getBoolean("registered")&&d.getString("next_expected_step").equals(state.getJSONArray("steps").getJSONObject(0).getString("step_id")),"Unregistered context");break;
  case 4:accepted(h,d);state.put("entryCommand",copy(c)).put("entryResult",d);break;
  case 5:accepted(h,d);require(same(d,state.getJSONObject("entryResult")),"Exact entry retry");break;
  case 6:accepted(h,d);require("ALREADY_RECORDED".equals(d.getString("outcome"))&&d.getString("event_id").equals(state.getJSONObject("entryResult").getString("event_id")),"Duplicate entry");break;
  case 7:error(h,d,"SIDE_MISMATCH");break;case 8:error(h,d,"STATION_NOT_ALLOWED");break;
  case 11:error(h,d,"RESULT_REQUIRED");break;case 12:error(h,d,"FAILURE_NOTE_REQUIRED");break;
  case 17:accepted(h,d);state.put("finalCommand",copy(c)).put("finalResult",d);break;
  case 18:require(h==200&&d.getJSONArray("items").length()==1,"One PCB row");fullFail(find(d.getJSONArray("items"),"pcb_code","ZZZ00001"));break;
  case 19:accepted(h,d);require(same(d,state.getJSONObject("finalResult")),"Exact stage retry");break;
  case 20:error(h,d,"STAGE_ALREADY_RECORDED");break;
  case 21:require(h==200&&d.getBoolean("registered")&&d.isNull("next_expected_step")&&"FAIL".equals(d.getString("quality_status")),"Completed failed context");break;
  case 23:error(h,d,"STEP_OUT_OF_ORDER");require(d.getJSONObject("error").getJSONObject("details").getJSONArray("missing_step_ids").length()==2,"Missing step details");state.put("skipRejected",copy(c));break;
  case 24:accepted(h,d);require(state.optBoolean("skipApproved"),"Explicit skip approval");require(!c.getJSONObject("payload").getString("request_id").equals(state.getJSONObject("skipRejected").getJSONObject("payload").getString("request_id")),"Fresh skip UUID");break;
  case 25:error(h,d,"BACKFILL_NOT_ALLOWED");break;
  case 26:case 34:{require(h==200&&d.getJSONArray("items").length()==2,"Exactly two rows");fullFail(find(d.getJSONArray("items"),"pcb_code","ZZZ00001"));JSONObject b=find(d.getJSONArray("items"),"pcb_code","ZZZ00002");JSONArray a=b.getJSONArray("steps");require("NOT_RECORDED".equals(a.getJSONObject(1).getString("status"))&&"NOT_RECORDED".equals(a.getJSONObject(2).getString("status")),"Skipped cells not completed");require(a.getJSONObject(3).getJSONArray("skipped_step_ids").length()==2&&!a.getJSONObject(3).getString("skip_reason").isEmpty()&&!b.getBoolean("route_completed"),"Skip reason and incomplete route");break;}
  case 27:accepted(h,d);state.put("receiptCommand",copy(c)).put("receiptResult",d);break;
  case 28:accepted(h,d);require(same(d,state.getJSONObject("receiptResult")),"Exact receipt retry");break;
  case 29:case 35:{require(h==200,"Packages");JSONArray a=d.getJSONArray("items"),b=state.getJSONArray("baseline");require(a.length()==b.length(),"Allocation count");require("RECEIVED".equals(find(a,"package_code","LEG-TEST-01").getString("receipt_status")),"First received");require(same(find(a,"package_code","LEG-TEST-02"),find(b,"package_code","LEG-TEST-02")),"Other reel unchanged");for(int i=0;i<a.length();i++)require(a.getJSONObject(i).getInt("current_quantity")==100&&a.getJSONObject(i).getInt("consumed_quantity")==0,"No stock consumption");break;}
  case 30:error(h,d,"STEP_OUT_OF_ORDER");state.put("postSkipRejected",copy(c)).put("postSkipError",d);break;case 32:accepted(h,d);require(state.optBoolean("recoveryVerified")&&state.optBoolean("skipApproved32"),"Restart and explicit continuation");require(!c.getJSONObject("payload").getString("request_id").equals(state.getJSONObject("postSkipRejected").getJSONObject("payload").getString("request_id")),"Fresh continued-skip UUID");state.put("recoveredCommand",copy(c)).put("recoveredResult",d);break;
  case 31:error(h,d,"STEP_OUT_OF_ORDER");require(same(d,state.getJSONObject("postSkipError")),"Exact rejected retry");break;case 33:accepted(h,d);require(same(d,state.getJSONObject("recoveredResult")),"Recovered exact retry");break;
  default:accepted(h,d);
 }String label="PASS "+(n+1)+" - "+title();state.remove("current");state.put("step",n+1);return label;}
}
