package com.ozak.inventory;

import android.os.*;
import android.webkit.WebView;
import androidx.webkit.*;
import org.json.*;
import java.util.*;

/** Same-origin transport only. Authentication stays entirely inside the WebView. */
final class OperatorBridge {
    interface Listener {
        void ready(JSONObject session);
        void result(JSONObject command,int status,JSONObject body);
        void disconnected(String reason);
    }
    static final Set<String> READS=new HashSet<>(Arrays.asList("SESSION","ORDERS","ROUTE","PROGRESS","PACKAGES","DELIVERED_ITEMS","SUPPLEMENT_PROPOSAL","REQUEST_STATUS","BOARD_CONTEXT"));
    static final Map<String,String> WRITES=new HashMap<>();
    static {WRITES.put("PCB_ENTRY","pcb-entry");WRITES.put("STAGE_PASSAGE","stage-passages");WRITES.put("PACKAGE_RECEIPT","package-receipts");WRITES.put("SUPPLEMENT_RESERVE","supplement-reserve");WRITES.put("SUPPLEMENT_DELIVER","supplement-deliver");
        // Proposed server-side reel check. Only usable when the server advertises "reel-checks" in enabled_operations.
        WRITES.put("REEL_CHECK","reel-checks");}
    private final WebView web; private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<String> retired=new HashSet<>(), permissions=new HashSet<>();
    private JavaScriptReplyProxy proxy;
    private String session,correlation; private JSONObject command; private boolean supported,destroyed;
    OperatorBridge(WebView web,Listener listener){
        this.web=web;this.listener=listener;
        supported=WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if(supported)WebViewCompat.addWebMessageListener(web,"EISProductionNative",Collections.singleton(BridgePolicy.ORIGIN),(v,m,origin,main,reply)->{
            if(destroyed||v!=web||!BridgePolicy.accepts(main,origin.toString(),web.getUrl()))return;
            try{if(m.getType()!=WebMessageCompat.TYPE_STRING||m.getData()==null||m.getData().length()>1048576)throw new JSONException("message");receive(new JSONObject(m.getData()),reply);}
            catch(Exception e){disconnect("Invalid server response. Pending results remain unknown.");}
        });
    }
    boolean connected(){return !destroyed&&supported&&session!=null&&proxy!=null&&BridgePolicy.page(web.getUrl());}
    boolean busy(){return correlation!=null;}
    boolean allowed(String operation){return READS.contains(operation)||(WRITES.containsKey(operation)&&permissions.contains(WRITES.get(operation)));}
    void disconnect(String reason){if(session!=null)retired.add(session);session=null;proxy=null;correlation=null;command=null;permissions.clear();handler.removeCallbacksAndMessages(null);listener.disconnected(reason);}
    void location(){if(!BridgePolicy.page(web.getUrl()))disconnect("Sign in and approve the app connection. Saved requests are retained.");}
    void destroy(){destroyed=true;disconnect("Connection closed. You can return later.");if(supported)WebViewCompat.removeWebMessageListener(web,"EISProductionNative");}
    private void receive(JSONObject m,JavaScriptReplyProxy reply)throws Exception{
        if(m.getInt("protocol")!=1||!BridgePolicy.uuid(m.getString("session")))throw new JSONException("protocol");
        String incoming=m.getString("session");if(retired.contains(incoming))return;
        if("READY".equals(m.optString("type"))){
            if(incoming.equals(session))return;
            JSONObject data=m.getJSONObject("data"),user=data.getJSONObject("user");
            if(data.getInt("protocol")!=1||!"SAME_ORIGIN_WEBVIEW".equals(data.getString("authentication"))||!"en".equals(data.getString("language"))||!"ltr".equals(data.getString("direction"))||user.getLong("id")<=0)throw new JSONException("session");
            boolean role=false;JSONArray roles=user.getJSONArray("roles");for(int i=0;i<roles.length();i++)role|="ADMIN".equals(roles.getString(i))||"PRODUCTION_OPERATOR".equals(roles.getString(i));
            if(!role)throw new JSONException("permission");
            disconnect("New connection approved.");session=incoming;proxy=reply;
            JSONArray ops=data.getJSONArray("enabled_operations");for(int i=0;i<ops.length();i++)permissions.add(ops.getString(i));
            listener.ready(data);return;
        }
        if(!incoming.equals(session))return;
        if("DISCONNECTED".equals(m.optString("type"))){disconnect("Signed out. Saved requests are retained.");return;}
        if(correlation==null||!correlation.equals(m.optString("correlation_id")))return;
        Object code=m.opt("http_status");
        if(!(code instanceof Number)||m.optInt("http_status")<100||m.optInt("http_status")==401||m.optInt("http_status")==403){disconnect("Connection unavailable or sign-in expired. Result unknown; reconnect to recover.");return;}
        if(!(m.get("ok") instanceof Boolean))throw new JSONException("result");
        JSONObject body=m.getBoolean("ok")?m.getJSONObject("data"):new JSONObject().put("error",m.getJSONObject("error"));
        JSONObject sent=command;command=null;correlation=null;handler.removeCallbacksAndMessages(null);
        listener.result(sent,m.getInt("http_status"),body);
    }
    boolean send(JSONObject c){
        if(!connected()||busy()||!allowed(c.optString("operation")))return false;
        try{command=new JSONObject(c.toString());correlation=UUID.randomUUID().toString();
            JSONObject envelope=new JSONObject(c.toString()).put("protocol",1).put("session",session).put("correlation_id",correlation);
            final String id=correlation;handler.postDelayed(()->{if(id.equals(correlation))disconnect("Timeout: result unknown. The original request is saved.");},35000);
            proxy.postMessage(envelope.toString());return true;
        }catch(Exception e){disconnect("Could not confirm sending. Recover the saved request.");return false;}
    }
}
