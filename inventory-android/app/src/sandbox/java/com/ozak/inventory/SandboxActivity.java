package com.ozak.inventory;

import android.app.*;
import android.os.*;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.AtomicFile;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Debug-only isolated acceptance harness. No production origin and no native HTTP client. */
public final class SandboxActivity extends Activity {
    private static final String ORIGIN="http://127.0.0.1:"+SandboxBuild.PORT, PAGE=ORIGIN+"/mobile/production-connect";
    private static final Set<String> ALLOWED=new HashSet<>(Arrays.asList("ORDERS","ROUTE","PACKAGES","PCB_ENTRY","PACKAGE_RECEIPT","PROGRESS","DELIVERED_ITEMS","SUPPLEMENT_PROPOSAL","SUPPLEMENT_RESERVE","SUPPLEMENT_DELIVER","REQUEST_STATUS"));
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WebView web;private TextView status, scanSummary;private Button run,recover;
    private SandboxScenario suite;private AtomicFile journal;
    private JavaScriptReplyProxy reply;private String session,correlation;private boolean recovering,connected;
    private boolean failed,needsRecovery;private int connectionGeneration;
    private final Set<String> retired=new HashSet<>();
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0||!getPackageName().equals(SandboxBuild.APP_ID)){finish();return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(false);
        journal=new AtomicFile(new File(getFilesDir(),"sandbox-journal.json"));
        try{JSONObject state=new JSONObject();if(journal.getBaseFile().exists())state=new JSONObject(new String(journal.readFully(),StandardCharsets.UTF_8));state.put("advanced",SandboxBuild.ADVANCED);suite=new SandboxScenario(state);needsRecovery=state.has("current");}catch(Exception e){failed=true;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);root.setPadding(16,16,16,16);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(16+i.getSystemWindowInsetLeft(),16+i.getSystemWindowInsetTop(),16+i.getSystemWindowInsetRight(),16+i.getSystemWindowInsetBottom());return i;});
        TextView title=new TextView(this);title.setText("ISOLATED API TEST — TEST-MOBILE-001 ONLY");title.setTextSize(18);root.addView(title);
        status=new TextView(this);status.setTextSize(14);status.setPadding(0,12,0,12);root.addView(status);
        LinearLayout controls=new LinearLayout(this);run=new Button(this);run.setText("Run next test phase");recover=new Button(this);recover.setText("Recover saved request");controls.addView(run,new LinearLayout.LayoutParams(0,-2,1));controls.addView(recover,new LinearLayout.LayoutParams(0,-2,1));root.addView(controls);
        Button reload=new Button(this);reload.setText("Reconnect to local sandbox");root.addView(reload);
        if(SandboxBuild.ADVANCED){LinearLayout scanners=new LinearLayout(this);Button pcb=new Button(this),qr=new Button(this);pcb.setText("Scan PCB label");qr.setText("Scan reel label");scanners.addView(pcb,new LinearLayout.LayoutParams(0,-2,1));scanners.addView(qr,new LinearLayout.LayoutParams(0,-2,1));root.addView(scanners);pcb.setOnClickListener(v->scan("DATA_MATRIX"));qr.setOnClickListener(v->scan("QR_CODE"));}
        if(SandboxBuild.ADVANCED){scanSummary=new TextView(this);scanSummary.setTextSize(14);scanSummary.setPadding(0,8,0,8);root.addView(scanSummary);refreshScans();}
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        run.setOnClickListener(v->{if(!failed)advance();});recover.setOnClickListener(v->recover());reload.setOnClickListener(v->{disconnect("Reconnecting. Approve in the local page.");web.loadUrl(PAGE);});
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){return true;}@Override public void onPermissionRequest(PermissionRequest r){r.deny();}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){if(request.isForMainFrame())disconnect("Page changed. Approve again.");return !local(request.getUrl().toString());}
            @Override public void onPageStarted(WebView view,String url,Bitmap icon){disconnect("Sign in as mobile-test, then approve the local app connection.");}
            @Override public void doUpdateVisitedHistory(WebView view,String url,boolean reload){if(!PAGE.equals(url))disconnect("Sign in and return to the local connection page.");}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())disconnect("Local server unavailable. Pending results are unknown.");}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                // The web auth interceptor can navigate before its bridge error arrives.
                // Observe only the exact pending synthetic operation; never read headers/body.
                if(SandboxBuild.ADVANCED&&suite!=null&&suite.step()==28&&response.getStatusCode()==401&&suite.state.has("current")){
                    JSONObject pending=suite.state.optJSONObject("current");
                    JSONObject payload=pending==null?null:pending.optJSONObject("payload");
                    String pcbUrl=ORIGIN+"/production-control/v1/orders/"+suite.state.optLong("order")+"/pcb-entry";
                    String recoveryUrl=payload==null?"":ORIGIN+"/production-control/v1/requests/"+payload.optString("request_id");
                    String url=request.getUrl().toString();
                    if(("POST".equals(request.getMethod())&&pcbUrl.equals(url))||("GET".equals(request.getMethod())&&recoveryUrl.equals(url))){
                        try{suite.state.put("auth401",true);suite.state.put("auth401Source","HTTP_CALLBACK");save();}
                        catch(Exception e){failed=true;disconnect("FAIL: could not retain authentication test evidence.");}
                    }
                }
                if(request.isForMainFrame())disconnect("Local page failed. Pending results are unknown.");
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler h,SslError e){h.cancel();disconnect("TLS error. Connection stopped.");}
        });
        if(failed){show("Journal could not be read. Testing stopped; saved data was not overwritten.");return;}
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){failed=true;show("Secure messaging unsupported. No fallback.");return;}
        WebViewCompat.addWebMessageListener(web,"EISProductionNative",Collections.singleton(ORIGIN),(view,message,source,main,proxy)->{
            if(!main||!ORIGIN.equals(source.toString())||!PAGE.equals(web.getUrl()))return;
            try{if(message.getType()!=WebMessageCompat.TYPE_STRING||message.getData()==null||message.getData().length()>262144)throw new IllegalStateException();receive(new JSONObject(message.getData()),proxy);}catch(Exception e){failed=true;disconnect("FAIL: invalid response or assertion. Test stopped; journal retained.");}
        });
        web.loadUrl(PAGE);
    }
    private boolean local(String url){try{Uri u=Uri.parse(url);return "http".equals(u.getScheme())&&"127.0.0.1".equals(u.getHost())&&u.getPort()==SandboxBuild.PORT&&u.getUserInfo()==null;}catch(Exception e){return false;}}
    private void show(String text){status.setText(text);run.setEnabled(!failed&&connected&&correlation==null&&!needsRecovery&&suite!=null&&!suite.done());recover.setEnabled(!failed&&connected&&correlation==null&&needsRecovery);}
    private void save()throws IOException {FileOutputStream out=null;try{out=journal.startWrite();out.write(suite.state.toString().getBytes(StandardCharsets.UTF_8));journal.finishWrite(out);}catch(IOException e){if(out!=null)journal.failWrite(out);throw e;}}
    private void disconnect(String reason){connectionGeneration++;if(session!=null)retired.add(session);session=null;reply=null;connected=false;correlation=null;handler.removeCallbacksAndMessages(null);if(suite!=null&&suite.state.has("current"))needsRecovery=true;show(reason);}
    private void receive(JSONObject m,JavaScriptReplyProxy proxy)throws Exception {
        SandboxScenario.require(m.get("protocol") instanceof Number&&m.getInt("protocol")==1,"Protocol");
        String incoming=m.getString("session");SandboxScenario.require(BridgePolicy.uuid(incoming),"Session UUID");if(retired.contains(incoming))return;
        if("READY".equals(m.optString("type"))){
            if(incoming.equals(session))return;
            JSONObject data=m.getJSONObject("data"),user=data.getJSONObject("user");
            SandboxScenario.require(data.getInt("protocol")==1&&"SAME_ORIGIN_WEBVIEW".equals(data.getString("authentication"))&&"mobile-test".equals(user.getString("username"))&&!data.getBoolean("later_stages_enabled"),"Only local synthetic operator");
            SandboxScenario.require("en".equals(data.getString("language"))&&"ltr".equals(data.getString("direction")),"Language");
            long actor=user.getLong("id");SandboxScenario.require(actor>0&&(!suite.state.has("actor")||suite.state.getLong("actor")==actor),"Recovery actor");
            suite.state.put("actor",actor);save();disconnect("New sandbox connection");session=incoming;reply=proxy;connected=true;
            show("Sandbox connected. "+(needsRecovery?"Recover the saved request before continuing.":"Ready: "+suite.title()));return;
        }
        if(!incoming.equals(session))return;
        if("DISCONNECTED".equals(m.optString("type"))){disconnect("Page disconnected; pending result remains unknown.");return;}
        if(correlation==null||!correlation.equals(m.optString("correlation_id")))return;
        correlation=null;handler.removeCallbacksAndMessages(null);
        // Fault injection: intentionally discard the entire response to the final initial receipt.
        if(suite.step()==22&&!recovering&&!suite.state.optBoolean("responseDropped")){
            suite.state.put("responseDropped",true);save();needsRecovery=true;
            disconnect("UNKNOWN — response deliberately dropped. Restart server with --resume AND restart this app; reconnect, then recover saved request.");return;
        }
        Object statusValue=m.opt("http_status");
        if(suite.step()==26&&(!(statusValue instanceof Number)||m.optInt("http_status")==0)){suite.state.put("transportFailure26",true);save();}
        if(suite.step()==28&&m.optInt("http_status")==401){suite.state.put("auth401",true);save();}
        if(!(statusValue instanceof Number)||m.optInt("http_status")==401||m.optInt("http_status")==403){disconnect("Connection uncertain or expired. Sign in and recover the original request.");return;}
        SandboxScenario.require(m.get("ok") instanceof Boolean,"Response flag");
        int http=m.getInt("http_status");JSONObject data=m.getBoolean("ok")?m.getJSONObject("data"):new JSONObject().put("error",m.getJSONObject("error"));
        if(recovering){
            recovering=false;
            if(http==404&&"REQUEST_NOT_FOUND".equals(data.getJSONObject("error").getString("code"))){suite.state.put("notFound"+suite.step(),true);save();needsRecovery=false;send(suite.state.getJSONObject("current"),false);return;}
            SandboxScenario.require(http==200&&"COMMITTED".equals(data.getString("state")),"Committed recovery response");
            SandboxScenario.require(data.getString("request_id").equals(suite.state.getJSONObject("current").getJSONObject("payload").getString("request_id")),"Recovery request identity");
            http=data.getInt("http_status");data=data.getJSONObject("response");
            suite.state.put("recoveryVerified",true);
        }
        String passed=suite.accept(http,data);JSONArray results=suite.state.optJSONArray("results");if(results==null)results=new JSONArray();results.put(passed);suite.state.put("results",results);save();needsRecovery=false;
        show(passed+(suite.done()?"\nALL "+(SandboxBuild.ADVANCED?32:26)+" API CHECKS PASSED. Synthetic data only.":"\nNext: "+suite.title()));
        if(!suite.done()&&suite.step()!=14&&suite.step()!=18&&suite.step()!=26&&suite.step()!=28)handler.postDelayed(this::advance,350);
    }
    private void advance(){
        if(!connected||correlation!=null||failed||needsRecovery||suite.done())return;
        try{
            if(SandboxBuild.ADVANCED&&(!suite.state.has("cameraPCB")||!suite.state.has("cameraQR"))){show("Scan both synthetic labels with the camera first. No request sent.");return;}
            if(suite.step()==26&&!suite.state.optBoolean("networkArmed")){suite.command();suite.state.put("networkArmed",true);save();show("ARMED — saved but NOT sent. Unplug USB, then press Run next test phase on the phone. Wait for unknown result, reconnect USB. Do not recover until server/app restart.");return;}
            if(suite.step()==14){JSONObject p=suite.state.getJSONObject("supplement");new AlertDialog.Builder(this).setTitle("Confirm synthetic whole-package reservation")
                .setMessage(p.getString("package_code")+" / "+p.getInt("current_quantity")+" "+p.getString("unit")+"\n"+p.getString("warehouse")+" / "+p.getString("location")+"\nTEST-MOBILE-001 only. This does not deliver or consume stock.")
                .setNegativeButton("Cancel",null).setPositiveButton("Reserve synthetic package",(d,w)->issue()).show();return;}
            if(suite.step()==18){new AlertDialog.Builder(this).setTitle("Confirm synthetic physical delivery")
                .setMessage("Simulate arrival at TEST LINE for this reserved supplement only? Machine receipt remains a separate scan. No real reel is moved.")
                .setNegativeButton("Cancel",null).setPositiveButton("Confirm synthetic delivery",(d,w)->issue()).show();return;}
            issue();
        }catch(Exception e){failed=true;disconnect("FAIL: test configuration or journal error.");}
    }
    private void refreshScans(){if(scanSummary!=null&&suite!=null)scanSummary.setText("LOCAL CAMERA READS (not server acceptance)\nPCB: "+(suite.state.has("cameraPCB")?"ZZZ00002 — read":"Not read")+"\nReel: "+(suite.state.has("cameraQR")?"LEG-TEST-01 — read":"Not read"));}
    private void scan(String format){if(correlation!=null||needsRecovery){show("Resolve pending request before scanning.");return;}startActivityForResult(new android.content.Intent(this,CameraScanActivity.class).putExtra("format",format),90);}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request!=90||result!=RESULT_OK||data==null)return;try{String format=data.getStringExtra("format"),payload=data.getStringExtra("payload");if("DATA_MATRIX".equals(format)){SandboxScenario.require("ZZZ00002".equals(payload),"Wrong synthetic PCB label");suite.state.put("cameraPCB",payload);}else {SandboxScenario.require(payload!=null&&payload.startsWith("OZAK POWER\nID=LEG-TEST-01\n"),"Wrong synthetic reel label");suite.state.put("cameraQR",payload);}suite.state.put("cameraReadAt"+format,System.currentTimeMillis());save();refreshScans();new AlertDialog.Builder(this).setTitle("Code read locally").setMessage(("DATA_MATRIX".equals(format)?"PCB: ZZZ00002":"Reel: LEG-TEST-01")+"\nSaved for this synthetic test. This is not server acceptance.").setPositiveButton("OK",null).show();show("Local "+format+" decode verified. PCB: "+suite.state.has("cameraPCB")+" / QR: "+suite.state.has("cameraQR")+". No server acceptance implied.");}catch(Exception e){show("Wrong synthetic label or storage failure. No request sent.");}}
    private void issue(){try{JSONObject c=suite.command();save();send(c,false);}catch(Exception e){failed=true;disconnect("FAIL: could not save request before sending. No new command sent.");}}
    private void recover(){
        if(!connected||correlation!=null||failed||!needsRecovery)return;
        try{JSONObject current=suite.state.getJSONObject("current");if(!current.has("payload")){needsRecovery=false;send(current,false);return;}
            send(new JSONObject().put("operation","REQUEST_STATUS").put("request_id",current.getJSONObject("payload").getString("request_id")),true);
        }catch(Exception e){failed=true;disconnect("FAIL: recovery journal invalid.");}
    }
    private void send(JSONObject command,boolean recovery)throws Exception {
        SandboxScenario.require(connected&&PAGE.equals(web.getUrl())&&ALLOWED.contains(command.getString("operation")),"Local allowlist");
        if(command.has("order_id"))SandboxScenario.require(command.getLong("order_id")==suite.state.getLong("order"),"Validated synthetic order");
        JSONObject envelope=SandboxScenario.copy(command);correlation=UUID.randomUUID().toString();recovering=recovery;
        envelope.put("protocol",1).put("session",session).put("correlation_id",correlation);
        final int generation=connectionGeneration;final String id=correlation;
        handler.postDelayed(()->{if(generation==connectionGeneration&&id.equals(correlation))disconnect("Timeout — result unknown. Reconnect and recover saved request.");},35000);
        show((recovery?"Recovering saved request":"Running: "+suite.title())+". Synthetic API only.");
        reply.postMessage(envelope.toString());
    }
    @Override protected void onDestroy(){disconnect("App closed; saved request retained.");if(web!=null){web.destroy();}super.onDestroy();}
}
