package com.ozak.inventory;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import java.util.*;

/** No credential extraction, arbitrary URLs, JavaScript evaluation or write operations. */
final class ProductionBridge {
    interface Observer { void changed(String message,boolean canRead); }
    private final WebView web;
    private final Observer observer;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<String> retired=new HashSet<>();
    private JavaScriptReplyProxy reply;
    private String session, pendingId, pendingOperation;
    private boolean visible=true, supported, destroyed;
    private long generation;
    ProductionBridge(WebView web,Observer observer){
        this.web=web;this.observer=observer;
        try {
            supported=WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
            if(supported)WebViewCompat.addWebMessageListener(web,"EISProductionNative",
                Collections.singleton(BridgePolicy.ORIGIN),(view,message,source,mainFrame,proxy)->{
                    if(destroyed||!visible||view!=web||!BridgePolicy.accepts(mainFrame,source.toString(),web.getUrl()))return;
                    try {
                        if(message.getType()!=WebMessageCompat.TYPE_STRING)throw new IllegalArgumentException();
                        String raw=message.getData();
                        if(raw==null||raw.length()>262144)throw new IllegalArgumentException();
                        receive(new JSONObject(raw),proxy);
                    }catch(Exception e){invalidate("Invalid bridge response. Reload and approve the connection again.");}
                });
        }catch(RuntimeException e){supported=false;}
        publish(supported?"Approve the connection in the page. Live writes are disabled.":"Secure messaging is unavailable. Update Android System WebView; operations are disabled.");
    }
    private void publish(String message){observer.changed(message,ready()&&pendingId==null);}
    private boolean ready(){return supported&&!destroyed&&visible&&session!=null&&reply!=null&&BridgePolicy.page(web.getUrl());}
    void invalidate(String reason){
        generation++;
        if(session!=null)retired.add(session);
        session=null;reply=null;pendingId=null;pendingOperation=null;
        handler.removeCallbacksAndMessages(null);
        publish(supported?reason:"Secure messaging is unavailable. Operations are disabled.");
    }
    void visible(boolean value){visible=value;if(!value)invalidate("Connection closed. Reopen the connection page to approve again.");}
    void navigation(){invalidate("Page changed. Approve a new connection on the connection page.");}
    void checkLocation(){if(!BridgePolicy.page(web.getUrl()))invalidate("Connection page is not active. Operations are disabled.");}
    void destroy(){destroyed=true;invalidate("Connection closed.");if(supported)WebViewCompat.removeWebMessageListener(web,"EISProductionNative");}
    private static boolean integer(Object value,int expected){return value instanceof Number&&((Number)value).doubleValue()==expected;}
    private static boolean validSession(JSONObject data)throws JSONException {
        if(!integer(data.get("protocol"),1)||!"en".equals(data.get("language"))||!"ltr".equals(data.get("direction"))
            ||!"SAME_ORIGIN_WEBVIEW".equals(data.get("authentication"))||!Boolean.FALSE.equals(data.get("later_stages_enabled")))return false;
        JSONObject user=data.getJSONObject("user");
        Object id=user.get("id");
        if(!(id instanceof Number)||((Number)id).longValue()<=0||!(user.get("username") instanceof String)||user.getString("username").isEmpty())return false;
        JSONArray roles=user.getJSONArray("roles");data.getJSONArray("enabled_operations");
        for(int i=0;i<roles.length();i++)if("ADMIN".equals(roles.get(i))||"PRODUCTION_OPERATOR".equals(roles.get(i)))return true;
        return false;
    }
    private void receive(JSONObject message,JavaScriptReplyProxy proxy)throws JSONException {
        if(!integer(message.get("protocol"),1))throw new JSONException("protocol");
        Object incoming=message.get("session");
        if(!(incoming instanceof String)||!BridgePolicy.uuid((String)incoming))throw new JSONException("session");
        String incomingSession=(String)incoming;
        if(retired.contains(incomingSession))return;
        if("READY".equals(message.optString("type"))){
            if(session!=null&&session.equals(incomingSession))return;
            if(!validSession(message.getJSONObject("data")))throw new JSONException("ready");
            invalidate("Establishing connection…");
            session=incomingSession;reply=proxy;
            publish("App connection approved. Read-only checks available; all live writes are disabled.");return;
        }
        if(session==null||!session.equals(incomingSession))return;
        if("DISCONNECTED".equals(message.optString("type"))){invalidate("Disconnected by the page. Approve again to reconnect.");return;}
        if(pendingId==null||!pendingId.equals(message.optString("correlation_id")))return;
        Object ok=message.get("ok"),status=message.opt("http_status");
        if(!(ok instanceof Boolean))throw new JSONException("ok");
        if(Boolean.FALSE.equals(ok)){
            // Even a domain error in this read-only pilot disables the channel until reapproved.
            invalidate("Connection check failed. Sign in and approve again. No production action was sent.");return;
        }
        if(!integer(status,200))throw new JSONException("status");
        JSONObject data=message.getJSONObject("data");
        String result;
        if("SESSION".equals(pendingOperation)){
            if(!validSession(data))throw new JSONException("session data");
            result="SESSION verified through the authenticated WebView. All live writes remain disabled.";
        }else if("ORDERS".equals(pendingOperation)){
            JSONArray items=data.getJSONArray("items");
            if(items.length()>50||!data.has("next_after"))throw new JSONException("orders");
            for(int i=0;i<items.length();i++){
                JSONObject order=items.getJSONObject(i);
                if(!(order.get("order_id") instanceof Number)||order.getLong("order_id")<=0
                    ||!(order.get("order_number") instanceof String)||!(order.get("accepting_scans") instanceof Boolean))throw new JSONException("order");
            }
            Object next=data.get("next_after");
            if(next!=JSONObject.NULL&&(!(next instanceof Number)||((Number)next).longValue()<0))throw new JSONException("pagination");
            result="ORDERS verified: "+items.length()+" on this page"+(next==JSONObject.NULL?".":"; more pages available.")+" No order is authorized for live testing.";
        }else throw new JSONException("operation");
        pendingId=null;pendingOperation=null;handler.removeCallbacksAndMessages(null);publish(result);
    }
    void read(String operation){
        if(!BridgePolicy.operation(operation)){invalidate("Operation blocked by the read-only app policy.");return;}
        if(!ready()||pendingId!=null)return;
        try {
            pendingId=UUID.randomUUID().toString();pendingOperation=operation;
            JSONObject message=new JSONObject().put("protocol",1).put("session",session)
                .put("correlation_id",pendingId).put("operation",operation);
            if(operation.equals("ORDERS"))message.put("query",new JSONObject().put("after",0).put("limit",50));
            final long ticket=generation;final String request=pendingId;
            publish("Checking "+operation+" through the WebView…");
            handler.postDelayed(()->{if(ticket==generation&&request.equals(pendingId))invalidate("Connection check timed out. Reload and approve again. No production action was sent.");},35000);
            reply.postMessage(message.toString());
        }catch(Exception e){invalidate("Connection unavailable. Reload and approve again.");}
    }
}
