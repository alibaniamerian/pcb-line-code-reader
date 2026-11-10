package com.ozak.inventory;

import org.json.*;
import java.util.*;

/** Synthetic API acceptance script. Never part of the production APK. */
final class SandboxScenario {
    final JSONObject state;
    SandboxScenario(JSONObject state){this.state=state;}
    int step(){return state.optInt("step",0);}
    boolean done(){return step()>=(state.optBoolean("advanced")?32:26);}
    static void require(boolean ok,String check){if(!ok)throw new IllegalStateException(check);}
    static boolean same(Object a,Object b)throws JSONException {
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;Iterator<String> keys=x.keys();while(keys.hasNext()){String k=keys.next();if(!y.has(k)||!same(x.get(k),y.get(k)))return false;}return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
        return Objects.equals(a,b);
    }
    String title(){String[] names={"Read synthetic order","Read independent route","Snapshot packages","Enter ZZZ00002","Retry exact PCB request","Enter ZZZ00001","Repeat physical PCB","Reject capacity overflow","Verify one row per PCB","Receive LEG-TEST-01","Retry exact receipt","Verify other reel unchanged","Search delivered items","Request supplemental proposal","Confirm whole-package reservation","Retry exact reservation","Verify reservation without consumption","Reject receipt before delivery","Confirm synthetic physical delivery","Verify independent delivery","Receive reserved supplement","Verify stock unchanged","Lose response for LEG-TEST-02","Retry recovered request","Verify final package quantities","Verify final PCB progress","Physical USB disconnect / NOT_FOUND recovery","Retry network-recovered request","Expired Inventory login / reauthentication","Retry expiry-recovered request","Final PCB rows after recovery","Final stock after recovery"};return done()?"Complete":names[step()];}
    JSONObject command()throws JSONException {
        JSONObject existing=state.optJSONObject("current");if(existing!=null)return existing;
        int n=step();JSONObject c;
        switch(n){
            case 0:c=read("ORDERS");break;
            case 1:c=read("ROUTE");break;
            case 2:case 11:case 16:case 19:case 21:case 24:c=read("PACKAGES");break;
            case 3:c=scan("PCB_ENTRY",state.optBoolean("advanced")?state.getString("cameraPCB"):"ZZZ00002");break;
            case 4:c=copy(state.getJSONObject("pcbCommand"));break;
            case 5:c=scan("PCB_ENTRY","ZZZ00001");break;
            case 6:c=scan("PCB_ENTRY","ZZZ00002");break;
            case 7:c=scan("PCB_ENTRY","ZZZ00003");break;
            case 8:case 25:c=read("PROGRESS");break;
            case 9:c=scan("PACKAGE_RECEIPT","LEG-TEST-01");if(state.optBoolean("advanced"))c.getJSONObject("payload").put("scan_text",state.getString("cameraQR"));break;
            case 10:c=copy(state.getJSONObject("receiptCommand"));break;
            case 12:c=read("DELIVERED_ITEMS").put("query",new JSONObject().put("q","TESTRES Synthetic"));break;
            case 13:c=read("SUPPLEMENT_PROPOSAL").put("query",new JSONObject().put("item_id",state.getLong("item")));break;
            case 14:c=write("SUPPLEMENT_RESERVE",base().put("proposal",state.getJSONObject("proposal").getString("proposal")));break;
            case 15:c=copy(state.getJSONObject("reserveCommand"));break;
            case 17:case 20:c=scan("PACKAGE_RECEIPT",state.getJSONObject("supplement").getString("package_code"));break;
            case 18:c=write("SUPPLEMENT_DELIVER",base().put("allocation_id",state.getLong("allocation")).put("line_name","TEST LINE"));break;
            case 22:c=scan("PACKAGE_RECEIPT","LEG-TEST-02");break;
            case 23:c=copy(state.getJSONObject("recoveredCommand"));break;
            case 26:case 28:c=scan("PCB_ENTRY","ZZZ00002");break;
            case 27:c=copy(state.getJSONObject("networkCommand"));break;
            case 29:c=copy(state.getJSONObject("expiryCommand"));break;
            case 30:c=read("PROGRESS");break;
            case 31:c=read("PACKAGES");break;
            default:throw new IllegalStateException("No further command");
        }
        state.put("current",c);return c;
    }
    private JSONObject read(String op)throws JSONException {JSONObject c=new JSONObject().put("operation",op);if(!op.equals("ORDERS"))c.put("order_id",state.getLong("order"));return c;}
    private JSONObject base()throws JSONException {return new JSONObject().put("request_id",UUID.randomUUID().toString()).put("route_version",state.getLong("version"));}
    private JSONObject write(String op,JSONObject payload)throws JSONException{return read(op).put("payload",payload);}
    private JSONObject scan(String op,String code)throws JSONException {
        JSONObject step=state.getJSONObject(op.equals("PCB_ENTRY")?"pcbStep":"packageStep");
        JSONObject payload=base().put("step_id",step.getString("step_id")).put("physical_station_id",state.getString(op.equals("PCB_ENTRY")?"pcbStation":"packageStation")).put("selected_side",step.get("side"));
        if(op.equals("PCB_ENTRY"))payload.put("pcb_code",code);
        else payload.put("scan_text","OZAK POWER\nID="+code+"\nPN=TEST-RESISTOR\nOZ=TESTRES\nQ=100\nT=REEL\nN=LEGACY\nW=TEST\nL=TEST-A01\nLN=Synthetic location\nLOT=UNKNOWN\nDC=UNKNOWN\nORIGIN=LEGACY_STOCK");
        return write(op,payload);
    }
    static JSONObject copy(JSONObject value)throws JSONException{return new JSONObject(value.toString());}
    private JSONObject packageByCode(JSONArray items,String code)throws JSONException {for(int i=0;i<items.length();i++){JSONObject p=items.getJSONObject(i);if(code.equals(p.getString("package_code")))return p;}throw new IllegalStateException("Expected synthetic package missing");}
    private void quantities(JSONArray items)throws JSONException {
        JSONArray baseline=state.getJSONArray("baseline");
        for(int i=0;i<baseline.length();i++){JSONObject old=baseline.getJSONObject(i),p=packageByCode(items,old.getString("package_code"));require(p.getInt("current_quantity")==old.getInt("current_quantity")&&p.getInt("consumed_quantity")==0,"Original reel stock unchanged");}
        if(state.has("supplement")&&step()>=16){JSONObject original=state.getJSONObject("supplement"),p=packageByCode(items,original.getString("package_code"));require(p.getInt("current_quantity")==original.getInt("current_quantity")&&p.getInt("consumed_quantity")==0&&p.getInt("planned_quantity")==0,"Supplement stock and consumption unchanged");}
    }
    String accept(int status,JSONObject data)throws JSONException {
        JSONObject command=state.getJSONObject("current");JSONObject payload=command.optJSONObject("payload");int n=step();
        if(n==7||n==17){String expected=n==7?"PCB_CAPACITY_EXCEEDED":"HANDOVER_REQUIRED";require(status==409&&expected.equals(data.getJSONObject("error").getString("code")),"Expected domain rejection");}
        else {
            require(status==200,"Expected valid API response");
            if(payload!=null){require(payload.getString("request_id").equals(data.getString("request_id"))&&data.getLong("order_id")==state.getLong("order"),"Response matches saved write request");require(data.getLong("recorded_by_user_id")==state.getLong("actor"),"Response actor matches sandbox user");require(!data.getString("event_id").isEmpty(),"Event identity present");}
            switch(n){
                case 0:{JSONArray orders=data.getJSONArray("items");require(orders.length()==1&&data.isNull("next_after"),"Only one synthetic order allowed");JSONObject o=orders.getJSONObject(0);require("TEST-MOBILE-001".equals(o.getString("order_number"))&&o.getBoolean("accepting_scans"),"Authorized synthetic order");state.put("order",o.getLong("order_id"));break;}
                case 1:{require(!data.getBoolean("later_stages_enabled")&&!data.getBoolean("rework_enabled"),"Later stages disabled");state.put("version",data.getLong("route_version"));JSONArray steps=data.getJSONArray("steps"),stations=data.getJSONArray("stations");require(steps.length()==2,"Only two initial controls");for(int i=0;i<steps.length();i++){JSONObject s=steps.getJSONObject(i);boolean pcb=s.getString("operation").equals("PCB_ENTRY");require(pcb||s.getString("operation").equals("PACKAGE_RECEIPT"),"Expected control");require(s.getBoolean("enabled")&&s.getJSONArray("dependencies").length()==0&&s.isNull("side"),"Independent controls with null side");state.put(pcb?"pcbStep":"packageStep",s);for(int j=0;j<stations.length();j++){JSONObject station=stations.getJSONObject(j);JSONArray ids=station.getJSONArray("step_ids");for(int k=0;k<ids.length();k++)if(station.getBoolean("enabled")&&s.getString("step_id").equals(ids.getString(k)))state.put(pcb?"pcbStation":"packageStation",station.getString("station_id"));}}require(state.getJSONObject("pcbStep").getInt("sequence")==state.getJSONObject("packageStep").getInt("sequence"),"Parallel sequence");break;}
                case 2:{JSONArray items=data.getJSONArray("items");require(items.length()==2,"Fresh sandbox packages");for(int i=0;i<items.length();i++){JSONObject p=items.getJSONObject(i);require(p.getInt("current_quantity")==100&&p.getString("receipt_status").equals("PENDING")&&p.getString("handover_status").equals("DELIVERED"),"Fresh delivered package");}state.put("baseline",items);break;}
                case 3:require(data.getString("outcome").equals("ACCEPTED_NEW"),"New PCB accepted");state.put("pcbCommand",copy(command)).put("pcbResult",data);break;
                case 4:require(same(data,state.getJSONObject("pcbResult")),"Exact same PCB retry result");break;
                case 5:require(data.getString("outcome").equals("ACCEPTED_NEW"),"Reverse-order PCB accepted");break;
                case 6:require(data.getString("outcome").equals("ALREADY_RECORDED")&&data.getString("event_id").equals(state.getJSONObject("pcbResult").getString("event_id")),"Repeated PCB retains event");break;
                case 8:case 25:case 30:{JSONArray items=data.getJSONArray("items");require(items.length()==2,"One row per PCB");Set<String> codes=new HashSet<>();for(int i=0;i<items.length();i++){JSONObject p=items.getJSONObject(i);codes.add(p.getString("pcb_code"));require(p.isNull("next_expected_step")&&p.isNull("expected_side"),"No later stage expected");}require(codes.contains("ZZZ00001")&&codes.contains("ZZZ00002"),"Both synthetic PCBs");break;}
                case 9:require(data.getString("outcome").equals("ACCEPTED_NEW"),"First reel receipt");state.put("receiptCommand",copy(command)).put("receiptResult",data);break;
                case 10:require(same(data,state.getJSONObject("receiptResult")),"Exact same receipt retry result");break;
                case 11:{JSONArray items=data.getJSONArray("items");quantities(items);require(items.length()==2&&packageByCode(items,"LEG-TEST-01").getString("receipt_status").equals("RECEIVED")&&same(packageByCode(items,"LEG-TEST-02"),packageByCode(state.getJSONArray("baseline"),"LEG-TEST-02")),"Other reel completely unchanged");break;}
                case 12:{JSONArray items=data.getJSONArray("items");require(items.length()==1&&items.getJSONObject(0).getString("ozcode").equals("TESTRES"),"Delivered item search");state.put("item",items.getJSONObject(0).getLong("item_id"));break;}
                case 13:{JSONObject p=data.getJSONObject("package");require(!data.getBoolean("reserved")&&!data.getBoolean("transfer_required")&&p.getLong("item_id")==state.getLong("item")&&(p.getString("package_code").equals("LEG-TEST-03")||p.getString("package_code").equals("LEG-TEST-04")),"Proposal for synthetic item");state.put("proposal",data).put("supplement",p);break;}
                case 14:require(data.getString("outcome").equals("ACCEPTED_NEW"),"Supplement reserved");state.put("allocation",data.getJSONObject("data").getLong("allocation_id")).put("reserveCommand",copy(command)).put("reserveResult",data);break;
                case 15:require(same(data,state.getJSONObject("reserveResult")),"Exact same reservation retry result");break;
                case 16:{JSONArray items=data.getJSONArray("items");quantities(items);JSONObject p=packageByCode(items,state.getJSONObject("supplement").getString("package_code"));require(p.getString("receipt_status").equals("PENDING")&&p.getString("handover_status").equals("RESERVED"),"Reservation is not receipt or delivery");state.put("beforeDelivery",items);break;}
                case 18:require(data.getJSONObject("data").getString("status").equals("DELIVERED"),"Explicit synthetic handover");break;
                case 19:{JSONArray items=data.getJSONArray("items");quantities(items);for(String code:new String[]{"LEG-TEST-01","LEG-TEST-02"})require(same(packageByCode(items,code),packageByCode(state.getJSONArray("beforeDelivery"),code)),"Delivery leaves original reels unchanged");require(packageByCode(items,state.getJSONObject("supplement").getString("package_code")).getString("receipt_status").equals("PENDING"),"Delivery still requires scan");break;}
                case 20:require(data.getString("outcome").equals("ACCEPTED_NEW")&&data.getJSONObject("data").getString("package_code").equals(state.getJSONObject("supplement").getString("package_code")),"Exact reserved supplement received");break;
                case 21:{JSONArray items=data.getJSONArray("items");quantities(items);require(packageByCode(items,state.getJSONObject("supplement").getString("package_code")).getString("receipt_status").equals("RECEIVED")&&packageByCode(items,"LEG-TEST-02").getString("receipt_status").equals("PENDING"),"Supplement receipt independent");break;}
                case 22:require(data.getString("outcome").equals("ACCEPTED_NEW"),"Recovered receipt accepted once");state.put("recoveredCommand",copy(command)).put("recoveredResult",data);break;
                case 23:require(same(data,state.getJSONObject("recoveredResult")),"Recovered request retry returns same result");break;
                case 24:case 31:{JSONArray items=data.getJSONArray("items");quantities(items);require(items.length()==3,"Only three allocated packages");for(int i=0;i<items.length();i++)require(items.getJSONObject(i).getString("receipt_status").equals("RECEIVED"),"All intended packages received");break;}
                case 26:require(state.optBoolean("notFound26")&&state.optBoolean("transportFailure26"),"Real transport failure then NOT_FOUND");require(data.getString("outcome").equals("ALREADY_RECORDED")&&data.getString("event_id").equals(state.getJSONObject("pcbResult").getString("event_id")),"Network recovery keeps PCB event");state.put("networkCommand",copy(command)).put("networkResult",data);break;
                case 27:require(same(data,state.getJSONObject("networkResult")),"Network retry returns same result");break;
                case 28:require(state.optBoolean("notFound28")&&state.optBoolean("auth401"),"Expiry must return 401 then NOT_FOUND");require(data.getString("outcome").equals("ALREADY_RECORDED"),"Expired request recovered once");state.put("expiryCommand",copy(command)).put("expiryResult",data);break;
                case 29:require(same(data,state.getJSONObject("expiryResult")),"Expiry retry returns same result");break;
            }
        }
        String result="PASS "+(n+1)+" — "+title();state.remove("current");state.put("step",n+1);return result;
    }
}
