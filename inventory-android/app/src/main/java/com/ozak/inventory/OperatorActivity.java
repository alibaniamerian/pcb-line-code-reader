package com.ozak.inventory;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import org.datamatrix.linereader.InlineScanner;

/**
 * Operator-driven workflow. The operator chooses the stage and station; the embedded camera then
 * records each valid code automatically (no extra confirmation). No background writes or automatic replay.
 */
public final class OperatorActivity extends Activity implements OperatorBridge.Listener {
    private LinearLayout root,body,connection; private TextView banner; private ScrollView scroll;
    private WebView web; private OperatorBridge bridge; private OperatorJournal journal;
    private JSONObject session,order,route,proposal,scanContext;
    private JSONObject scannedStep,boardDraft,boardContext,rejectedStage,stageError;
    private long scanOrder,scanActor;
    private JSONArray orders=new JSONArray(),packages=new JSONArray(),progress=new JSONArray(),items=new JSONArray();
    private long actor; private Object nextOrders=JSONObject.NULL,nextProgress=JSONObject.NULL;
    private String page="orders",notice="Sign in and approve the app connection.",scanOperation,lastOutcome="";
    private boolean storageFailed,retryAvailable,destroying;
    // Embedded scanner: one active stage/station context at a time.
    private InlineScanner scanner; private LinearLayout livePanel,liveResult; private TextView liveTitle,liveStatus; private EditText liveNotes,liveThickness;
    private String liveOp,lastCode=""; private JSONObject liveStep,liveStation; private long lastSeen; private boolean scanBusy;
    private static final long SAME_CODE_HOLD_MS=2500;
    // Reel gate (app-side only): PCB stages unlock when every server reference reel is RECEIVED, or by a local override.
    private long packagesOrder; private final List<String[]> wrongReels=new ArrayList<>();
    /** step_id|pcb_code confirmed by the server on this phone for the selected order; avoids resending (each new request is an audit record). */
    private final Set<String> recordedHere=new HashSet<>();
    /**
     * Server reel checks (REEL_CHECK) are used only when the server advertises "reel-checks". The mode is fixed per
     * READY session and survives disconnects, so a lost connection never falls back to phone-only verification.
     */
    private boolean serverCheckMode,pollInFlight,resumed; private long pollBackoffUntil;
    private final Handler poller=new Handler(Looper.getMainLooper());
    private static final long POLL_MS=10000;
    private final Runnable pollTick=()->{
        schedulePoll(POLL_MS);
        if(!resumed||!serverCheckMode||order==null||!("order".equals(page)||"reels".equals(page))||connection.getVisibility()==View.VISIBLE)return;
        if(SystemClock.elapsedRealtime()<pollBackoffUntil||!available()||scanBusy||pending())return;
        try{if(bridge.send(command("PACKAGES")))pollInFlight=true;}catch(Exception ignored){}
    };
    private void schedulePoll(long ms){poller.removeCallbacks(pollTick);poller.postDelayed(pollTick,ms);}
    /**
     * The bridge passes only the response body, not HTTP headers. Honours a Retry-After value (seconds) if the bridge
     * exposes it as error.retry_after / error.retry_after_seconds / error.details.retry_after(_seconds); else the fallback.
     */
    static long retryAfterMs(JSONObject body,long fallback){
        JSONObject e=body==null?null:body.optJSONObject("error");if(e==null)return fallback;JSONObject d=e.optJSONObject("details");
        for(Object src:new Object[]{e,d})if(src instanceof JSONObject)for(String k:new String[]{"retry_after_seconds","retry_after"}){
            double s=((JSONObject)src).optDouble(k,Double.NaN);if(!Double.isNaN(s)&&s>0)return (long)Math.min(s*1000,600000);}
        return fallback;
    }
    private static final int GREEN=0xff2e7d32,GREEN_BG=0xffe8f5e9,RED=0xffc62828,RED_BG=0xffffebee;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);WebView.setWebContentsDebuggingEnabled(false);
        try{journal=new OperatorJournal(getFilesDir());}catch(Exception e){storageFailed=true;}
        root=new LinearLayout(this);root.setOrientation(1);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setPadding(20,16,20,16);root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(20+i.getSystemWindowInsetLeft(),16+i.getSystemWindowInsetTop(),20+i.getSystemWindowInsetRight(),16+i.getSystemWindowInsetBottom());return i;});
        banner=new TextView(this);banner.setTextSize(16);root.addView(banner);
        LinearLayout nav=new LinearLayout(this);Button exit=new Button(this);exit.setText("Leave controls");exit.setOnClickListener(v->finish());nav.addView(exit,new LinearLayout.LayoutParams(0,-2,1));
        Button login=new Button(this);login.setText("Connection / sign in");login.setOnClickListener(v->showConnection(true));nav.addView(login,new LinearLayout.LayoutParams(0,-2,1));root.addView(nav);
        connection=new LinearLayout(this);connection.setOrientation(1);web=new WebView(this);connection.addView(web,new LinearLayout.LayoutParams(-1,0,1));root.addView(connection,new LinearLayout.LayoutParams(-1,0,1));
        buildLivePanel();root.addView(livePanel);
        scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        bridge=new OperatorBridge(web,this);
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){return true;}@Override public void onPermissionRequest(PermissionRequest r){r.deny();}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){if(r.isForMainFrame())bridge.disconnect("Page changed; approve the connection again.");Uri u=r.getUrl();return !"https".equals(u.getScheme())||u.getUserInfo()!=null;}
            @Override public void onPageStarted(WebView v,String url,Bitmap icon){bridge.disconnect("Sign in and approve the app connection.");}
            @Override public void doUpdateVisitedHistory(WebView v,String url,boolean reload){bridge.location();}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())bridge.disconnect("Connection interrupted. You may leave and return later.");}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame())bridge.disconnect("Sign-in page unavailable. Saved requests are retained.");}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e){h.cancel();bridge.disconnect("Secure connection could not be verified.");}
        });
        showConnection(true);web.loadUrl(BridgePolicy.PAGE);
    }
    private void buildLivePanel(){
        livePanel=new LinearLayout(this);livePanel.setOrientation(1);livePanel.setVisibility(View.GONE);
        liveTitle=new TextView(this);liveTitle.setTextSize(15);liveTitle.setPadding(0,8,0,8);livePanel.addView(liveTitle);
        scanner=new InlineScanner(this);scanner.setListener(new InlineScanner.Listener(){
            @Override public void onCode(String payload,String format){liveCode(payload,format);}
            @Override public void onCameraProblem(String message){if(liveOp!=null)liveState(InlineScanner.BAD,message);}
        });livePanel.addView(scanner);
        liveStatus=new TextView(this);liveStatus.setTextSize(18);liveStatus.setTextColor(Color.WHITE);liveStatus.setPadding(16,12,16,12);liveStatus.setMinLines(2);livePanel.addView(liveStatus,new LinearLayout.LayoutParams(-1,-2));
        liveThickness=new EditText(this);liveThickness.setHint("Paste thickness µm for the next board (optional)");liveThickness.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);livePanel.addView(liveThickness);
        liveResult=new LinearLayout(this);liveResult.setOrientation(1);liveResult.setVisibility(View.GONE);
        liveNotes=new EditText(this);liveNotes.setHint("Notes (required for FAIL)");liveNotes.setMaxLines(4);liveResult.addView(liveNotes);
        LinearLayout outcomes=new LinearLayout(this);
        Button pass=new Button(this);pass.setText("PASS");pass.setTextColor(0xff1b5e20);pass.setOnClickListener(v->liveSubmitBoard("PASS"));outcomes.addView(pass,new LinearLayout.LayoutParams(0,-2,1));
        Button fail=new Button(this);fail.setText("FAIL");fail.setTextColor(0xffb71c1c);fail.setOnClickListener(v->liveSubmitBoard("FAIL"));outcomes.addView(fail,new LinearLayout.LayoutParams(0,-2,1));
        Button discard=new Button(this);discard.setText("Discard read");discard.setOnClickListener(v->{scanBusy=false;boardDraft=null;boardContext=null;hideResult();liveState(InlineScanner.IDLE,"Read discarded. Nothing recorded. Place the next code inside the square.");});outcomes.addView(discard,new LinearLayout.LayoutParams(0,-2,1));
        liveResult.addView(outcomes);livePanel.addView(liveResult);
        Button stop=new Button(this);stop.setText("Stop camera");stop.setOnClickListener(v->{stopLive();render();});livePanel.addView(stop);
    }
    private void liveState(int state,String message){
        scanner.setState(state);liveStatus.setText(message);
        liveStatus.setBackgroundColor(state==InlineScanner.OK?0xff1b5e20:state==InlineScanner.BAD?0xffb71c1c:state==InlineScanner.WAIT?0xff8d6e00:0xff37474f);
    }
    private void hideResult(){liveResult.setVisibility(View.GONE);liveNotes.setText("");}
    /** Opens the embedded camera for one server-authorized stage and station. */
    /** Board scans write to the server; reel scans are verified on the phone only and need just the order and route. */
    private boolean canScan(String op){return StageRules.boardOperation(op)?canWrite(op):serverCheckMode?canWrite("REEL_CHECK"):order!=null&&route!=null;}
    private void startLive(String op,JSONObject step,JSONObject station){
        if(!canScan(op)){say(pending()?"Resolve the saved request before scanning.":"Scanning is unavailable: not connected or not permitted.");render();return;}
        if(reelLockedFor(op)){say("PCB stages locked: "+reelLock());render();return;}
        try{
            scanOrder=order.getLong("order_id");scanActor=actor;scanOperation=op;scannedStep=new JSONObject(step.toString());
            scanContext=new JSONObject().put("route_version",route.getInt("route_version")).put("step_id",step.getString("step_id")).put("physical_station_id",station.getString("station_id")).put("selected_side",step.get("side"));
        }catch(Exception e){say("Invalid stage details from server. Refresh the route.");return;}
        liveOp=op;liveStep=scannedStep;liveStation=station;boardDraft=null;boardContext=null;rejectedStage=null;stageError=null;scanBusy=false;lastCode="";
        boolean board=StageRules.boardOperation(op);
        liveTitle.setText((board?"Scanning: "+step.optInt("sequence")+". "+stageLabel(step)+" · Side: "+nullable(step,"side")+" · "+station.optString("name"):"Reel check · "+order.optString("order_number"))+"\n"+(board?"PCB DataMatrix — each valid board is recorded on the server automatically.":(serverCheckMode?"Reel QR label — each valid reel is checked on the server and shared with other operators.":"Reel QR label — LOCAL VERIFICATION on this phone only; nothing is sent to the server. Use one phone per order.")));
        liveThickness.setText("");liveThickness.setVisibility("STAGE_PASSAGE".equals(op)&&step.optBoolean("allows_paste_measurement")?View.VISIBLE:View.GONE);
        hideResult();livePanel.setVisibility(View.VISIBLE);
        liveState(InlineScanner.IDLE,"Place the "+(board?"PCB code":"reel label")+" inside the square.");
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},83);
        scanner.start(board?"DATA_MATRIX":"QR_CODE");render();
    }
    private void startReelScan(){
        try{startLive("PACKAGE_RECEIPT",new JSONObject().put("step_id","reel-check").put("sequence",0).put("label","Reel check").put("side",JSONObject.NULL),new JSONObject().put("station_id","order").put("name","this order"));}
        catch(Exception e){say("Could not start the reel scan.");}
    }
    private void stopLive(){
        if(scanner!=null)scanner.stop();if(livePanel!=null){livePanel.setVisibility(View.GONE);hideResult();}
        liveOp=null;liveStep=null;liveStation=null;scanContext=null;scanBusy=false;boardDraft=null;boardContext=null;
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);if(code!=83||liveOp==null)return;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){scanner.pause();scanner.resume();liveState(InlineScanner.IDLE,"Place the code inside the square.");}
        else liveState(InlineScanner.BAD,"Camera permission is required to scan.");
    }
    /**
     * Mirrors the server label parser (contract 1.2.0): first non-blank line exactly "OZAK POWER", exactly one line
     * starting with case-sensitive "ID=", code 1–40 characters. Anything else is rejected by the server (422 INVALID_QR),
     * so it is rejected locally without creating a request.
     */
    static String reelId(String raw){
        if(raw==null)return null;String[] lines=raw.split("\\r?\\n",-1);int first=0;while(first<lines.length&&lines[first].trim().isEmpty())first++;
        if(first>=lines.length||!"OZAK POWER".equals(lines[first].trim()))return null;
        String id=null;int count=0;for(String line:lines){String t=line.trim();if(t.startsWith("ID=")){count++;id=t.substring(3).trim();}}
        return count==1&&id!=null&&!id.isEmpty()&&id.length()<=40?id:null;
    }
    private JSONObject packageByCode(String code){for(int i=0;i<packages.length();i++){JSONObject p=packages.optJSONObject(i);if(p!=null&&code.equals(p.optString("package_code")))return p;}return null;}
    /** Called for every decoded frame; ignores a code held in view and anything while a request is in flight. */
    private void liveCode(String raw,String format){
        if(liveOp==null||raw==null)return;long now=SystemClock.elapsedRealtime();
        if(scanBusy){if(raw.equals(lastCode))lastSeen=now;return;}
        boolean held=raw.equals(lastCode)&&now-lastSeen<SAME_CODE_HOLD_MS;lastCode=raw;lastSeen=now;if(held)return;
        boolean board=StageRules.boardOperation(liveOp);
        if(raw.isEmpty()||raw.length()>4096||!(board?"DATA_MATRIX":"QR_CODE").equals(format)){liveState(InlineScanner.BAD,"Wrong code type — expected a "+(board?"PCB DataMatrix":"reel QR label")+". Nothing recorded.");if(!board){wrongReel(raw.length()>40?raw.substring(0,40)+"…":raw,"Wrong code type");render();}return;}
        if(reelLockedFor(liveOp)){stopLive();say("PCB stages locked: "+reelLock());render();return;}
        if(order==null||order.optLong("order_id")!=scanOrder||actor!=scanActor){stopLive();say("Order or operator changed. Choose the stage again; nothing recorded.");render();return;}
        if(!board){verifyReel(raw);return;}
        if(!canWrite(liveOp)){liveState(InlineScanner.BAD,pending()?"A saved request is unresolved. Check its status first. Nothing recorded.":"Not connected or not permitted. Nothing recorded.");return;}
        try{
            JSONObject payload=new JSONObject(scanContext.toString()).put("pcb_code",raw);
            {
                if(recordedHere.contains(liveStep.optString("step_id")+"|"+raw)){liveState(InlineScanner.OK,"Already recorded at this stage: "+raw+" (not resent).\nScan the next board.");return;}
                boardDraft=payload;boardContext=null;scanBusy=true;liveState(InlineScanner.WAIT,"Read "+raw+" — checking with server…");
                if(!read("BOARD_CONTEXT",new JSONObject().put("pcb_code",raw))){scanBusy=false;boardDraft=null;liveState(InlineScanner.BAD,"Could not reach the server. Nothing recorded.");}
            }
        }catch(Exception e){scanBusy=false;liveState(InlineScanner.BAD,"Invalid read. Nothing recorded.");}
    }
    /**
     * Reel check against the server reference list. Server mode: sends REEL_CHECK (only when advertised) and turns green
     * after the server confirms. Local mode: green is stored on this phone only and nothing is sent.
     */
    private void verifyReel(String raw){
        String id=reelId(raw);
        if(id==null){String shown=raw.split("\\r?\\n")[0];shown=shown.length()>40?shown.substring(0,40)+"…":shown;liveState(InlineScanner.BAD,"Not an OZAK package label (needs \"OZAK POWER\" and one ID= line).");wrongReel(shown,"Not an OZAK package label");render();return;}
        if(!reelsKnown()){liveState(InlineScanner.BAD,"Reference list not loaded yet. Refresh the packages and scan again.");return;}
        JSONObject known=packageByCode(id);
        if(known==null){liveState(InlineScanner.BAD,"Not a reel of this order: "+id);wrongReel(id,"Not in this order's reference list");render();return;}
        if(isGreen(known,verified())){
            String by=serverCheckMode?" by user "+nullable(known,"checked_by_user_id")+" at "+nullable(known,"checked_at"):"";
            liveState(InlineScanner.BAD,"Already checked: "+id+by);wrongReel(id,"Duplicate — already checked"+by);render();return;}
        if(serverCheckMode){
            if(!canWrite("REEL_CHECK")){liveState(InlineScanner.BAD,pending()?"A saved request is unresolved. Check its status first. Nothing sent.":"Not connected or not permitted. Nothing sent.");return;}
            try{
                JSONObject payload=new JSONObject().put("route_version",route.getInt("route_version")).put("scan_text",raw);
                scanBusy=true;liveState(InlineScanner.WAIT,"Read "+id+" — checking on the server…");
                if(!submit("REEL_CHECK",payload)){scanBusy=false;liveState(InlineScanner.BAD,"Not checked: submission unavailable.");}
            }catch(Exception e){scanBusy=false;liveState(InlineScanner.BAD,"Invalid read. Nothing sent.");}
            return;
        }
        markVerified(id);int left=missingReels().size();
        liveState(InlineScanner.OK,"Verified ✓ "+id+" · "+known.optString("part_number")+"\n"+(packages.length()-left)+" / "+packages.length()+(left==0?" — all reels verified, PCB stages unlocked.":" verified · "+left+" left"));
        render();
    }
    private String expectation(JSONObject context,JSONObject step){
        if(context==null||context.isNull("next_expected_step"))return "";
        String expected=context.optString("next_expected_step");
        return expected.equals(step.optString("step_id"))?"":"\nNote: server expects "+expected+" / "+nullable(context,"expected_side")+".";
    }
    /** Board context arrived for the live read: record immediately, or wait only for a required PASS/FAIL. */
    private void liveBoardReady(){
        String code=boardDraft.optString("pcb_code");
        if(liveStep.optBoolean("requires_result")){liveState(InlineScanner.OK,"Read "+code+expectation(boardContext,liveStep)+"\nTap PASS or FAIL to record.");liveResult.setVisibility(View.VISIBLE);return;}
        liveSubmitBoard(null);
    }
    private void liveSubmitBoard(String outcome){
        if(liveOp==null||boardDraft==null||liveStep==null){hideResult();return;}
        String code=boardDraft.optString("pcb_code"),note=liveNotes.getText().toString().trim(),measure=liveThickness.getText().toString().trim();
        String problem=StageRules.validate(liveStep,outcome,note,measure);
        if(problem!=null){if(outcome!=null){liveState(InlineScanner.BAD,problem);return;}scanBusy=false;boardDraft=null;liveState(InlineScanner.BAD,problem+" Nothing recorded for "+code+".");return;}
        if(route==null||route.optInt("route_version")!=boardDraft.optInt("route_version")||order==null||order.optLong("order_id")!=scanOrder||actor!=scanActor){scanBusy=false;boardDraft=null;hideResult();liveState(InlineScanner.BAD,"Route, order or operator changed. Choose the stage again. Nothing recorded.");return;}
        try{
            JSONObject payload=new JSONObject(boardDraft.toString());
            if("STAGE_PASSAGE".equals(liveOp)){payload.put("outcome",outcome==null?JSONObject.NULL:outcome).put("notes",note).put("allow_skip",false).put("skip_reason","");if(!measure.isEmpty())payload.put("paste_thickness_um",Double.parseDouble(measure));}
            liveResult.setVisibility(View.GONE);liveState(InlineScanner.WAIT,"Recording "+code+(outcome==null?"":" · "+outcome)+"…");
            if(!submit(liveOp,payload)){scanBusy=false;boardDraft=null;liveState(InlineScanner.BAD,"Not recorded: submission unavailable.");}
        }catch(Exception e){scanBusy=false;boardDraft=null;liveState(InlineScanner.BAD,"Could not prepare the stage. Nothing recorded.");}
    }
    /** Final server answer for a live write: green when recorded, red otherwise; camera stays on. */
    private void liveFinish(JSONObject sent,int status,JSONObject data){
        String op=sent.optString("operation");boolean reelCheck="REEL_CHECK".equals(op);
        if(liveOp==null||!(liveOp.equals(op)||(reelCheck&&"PACKAGE_RECEIPT".equals(liveOp))))return;
        JSONObject p=sent.optJSONObject("payload");String code=p==null?"":p.has("pcb_code")?p.optString("pcb_code"):String.valueOf(reelId(p.optString("scan_text")));
        scanBusy=false;hideResult();liveThickness.setText("");
        if(status>=200&&status<300&&p!=null&&p.has("pcb_code"))recordedHere.add(p.optString("step_id")+"|"+p.optString("pcb_code"));
        if(status>=200&&status<300&&reelCheck){JSONObject d=data.optJSONObject("data");
            if("ALREADY_RECORDED".equals(data.optString("outcome")))liveState(InlineScanner.OK,"Already checked: "+code+"\nby user "+(d==null?"?":nullable(d,"checked_by_user_id"))+" at "+(d==null?"?":nullable(d,"checked_at")));
            else liveState(InlineScanner.OK,"Checked ✓ "+code+" (server)\nScan the next reel.");}
        else if(status>=200&&status<300)liveState(InlineScanner.OK,("ALREADY_RECORDED".equals(data.optString("outcome"))?"Already recorded (no duplicate): ":"Recorded ✓ ")+code+"\nScan the next code.");
        else if(status>=500||status<200)liveState(InlineScanner.BAD,"Result unknown for "+code+". Check the saved request before scanning again.");
        else{liveState(InlineScanner.BAD,"Rejected: "+code+"\n"+error(data)+" — nothing recorded.");if(!StageRules.boardOperation(op))wrongReel(code,"Rejected by server: "+error(data));}
    }
    private void showConnection(boolean reload){if(liveOp!=null)stopLive();connection.setVisibility(View.VISIBLE);scroll.setVisibility(View.GONE);if(reload&&bridge!=null&&!bridge.connected())web.loadUrl(BridgePolicy.PAGE);}
    private void showControls(){connection.setVisibility(View.GONE);scroll.setVisibility(View.VISIBLE);render();}
    private boolean pending(){return journal!=null&&journal.pending();}
    private boolean available(){return bridge!=null&&bridge.connected()&&!bridge.busy();}
    private boolean canWrite(String op){return available()&&!storageFailed&&!pending()&&OperatorRules.order(order)&&OperatorRules.route(route,order.optLong("order_id"))&&order.optBoolean("accepting_scans")&&bridge.allowed(op);}
    private void say(String text){notice=text;if(banner!=null)banner.setText(text);}
    @Override public void ready(JSONObject s){try{session=s;actor=s.getJSONObject("user").getLong("id");retryAvailable=false;pollInFlight=false;
        // On a mode change, drop the cached list so PACKAGES is reloaded; PENDING rows then need fresh checks (no upload of local greens).
        boolean server=bridge.allowed("REEL_CHECK");if(server!=serverCheckMode){serverCheckMode=server;packagesOrder=0;packages=new JSONArray();if(liveOp!=null&&!StageRules.boardOperation(liveOp))stopLive();}say("Connected. Choose an order; no order is selected automatically.");showControls();if(!pending())loadOrders(false);}catch(Exception e){disconnected("Invalid session details.");}}
    @Override public void disconnected(String reason){retryAvailable=false;pollInFlight=false;say(reason);
        if(liveOp!=null&&scanBusy&&!destroying){scanBusy=false;boardDraft=null;hideResult();liveState(InlineScanner.BAD,reason);}
        if(!destroying&&body!=null)render();}
    private JSONObject command(String op)throws JSONException{JSONObject c=new JSONObject().put("operation",op);if(order!=null&&!"ORDERS".equals(op)&&!"REQUEST_STATUS".equals(op))c.put("order_id",order.getLong("order_id"));return c;}
    private boolean read(String op,JSONObject query){if(!available())return false;try{JSONObject c=command(op);if(query!=null)c.put("query",query);if(bridge.send(c)){say("Loading "+op.toLowerCase(Locale.US).replace('_',' ')+"…");render();return true;}}catch(Exception e){say("Could not prepare the request.");}return false;}
    private void loadOrders(boolean more){if(!more)orders=new JSONArray();try{read("ORDERS",new JSONObject().put("after",more?nextOrders:0).put("limit",50));}catch(Exception e){say("Invalid order pagination.");}}
    private void select(JSONObject selected){if(!available())return;stopLive();packagesOrder=0;wrongReels.clear();recordedHere.clear();order=selected;route=null;proposal=null;packages=new JSONArray();progress=new JSONArray();page="order";read("ROUTE",null);}
    private void showPage(String p){page=p;proposal=null;showControls();if(!available())return;if("reels".equals(p))read("PACKAGES",null);if("supplement".equals(p))read("DELIVERED_ITEMS",null);if("progress".equals(p)){progress=new JSONArray();read("PROGRESS",null);}}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,10,0,10);body.addView(t);return t;}
    private TextView colored(String s,int size,int color,int background){TextView t=text(s,size);t.setTextColor(color);if(background!=0){t.setBackgroundColor(background);t.setPadding(16,12,16,12);}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,4,0,4);t.setLayoutParams(lp);return t;}
    // ---- Reel gate ----
    private boolean reelsKnown(){return order!=null&&packagesOrder==order.optLong("order_id");}
    // Reel verification is local to this phone: scanning a reel of the reference list marks it green; nothing is sent to the server.
    private android.content.SharedPreferences verifyPrefs(){return getSharedPreferences("reel_verify",MODE_PRIVATE);}
    private JSONObject verified(){if(order==null)return new JSONObject();try{return new JSONObject(verifyPrefs().getString("order_"+order.optLong("order_id"),"{}"));}catch(Exception e){return new JSONObject();}}
    private void markVerified(String code){try{JSONObject v=verified();v.put(code,new JSONObject().put("actor",actor).put("at",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date())));verifyPrefs().edit().putString("order_"+order.optLong("order_id"),v.toString()).commit();}catch(Exception e){say("Could not save the reel verification on this phone.");}}
    /** Server mode: green only when the server reports CHECKED. Local mode: received on the server, or verified on this phone. */
    private boolean isGreen(JSONObject p,JSONObject v){
        if(p==null)return false;
        if(serverCheckMode)return "CHECKED".equals(p.optString("check_status"));
        return "RECEIVED".equals(p.optString("receipt_status"))||v.has(p.optString("package_code"));
    }
    private List<String> missingReels(){List<String> out=new ArrayList<>();JSONObject v=verified();for(int i=0;i<packages.length();i++){JSONObject p=packages.optJSONObject(i);if(p!=null&&!isGreen(p,v))out.add(p.optString("package_code"));}return out;}
    private int receivedReels(){return packages.length()-missingReels().size();}
    private android.content.SharedPreferences gatePrefs(){return getSharedPreferences("reel_gate",MODE_PRIVATE);}
    private JSONObject reelOverride(){if(order==null)return null;try{String v=gatePrefs().getString("order_"+order.optLong("order_id"),null);return v==null?null:new JSONObject(v);}catch(Exception e){return null;}}
    /** An override only covers the reels that were missing when it was given; a newly added reel locks again. */
    private boolean overrideCovers(List<String> missing){JSONObject o=reelOverride();if(o==null)return false;JSONArray a=o.optJSONArray("missing");Set<String> allowed=new HashSet<>();for(int i=0;a!=null&&i<a.length();i++)allowed.add(a.optString(i));return allowed.containsAll(missing)&&(packages.length()>0||o.optBoolean("empty_list"));}
    /** Null when PCB stages may be scanned; otherwise the reason they are locked. */
    private String reelLock(){
        if(order==null)return "No order selected.";
        if(!reelsKnown())return "Reel reference list not loaded from the server yet.";
        List<String> missing=missingReels();
        if(packages.length()==0)return overrideCovers(missing)?null:"No active package allocations returned for this order — order data is incomplete. Report it, or override with a reason.";
        if(missing.isEmpty()||overrideCovers(missing))return null;
        return missing.size()+" of "+packages.length()+" reels not yet verified.";
    }
    private boolean reelLockedFor(String op){return StageRules.boardOperation(op)&&reelLock()!=null;}
    private void wrongReel(String label,String reason){
        String time=new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        if(!wrongReels.isEmpty()&&wrongReels.get(0)[0].equals(label)&&wrongReels.get(0)[1].equals(reason)){wrongReels.get(0)[2]=time;}
        else{wrongReels.add(0,new String[]{label,reason,time});while(wrongReels.size()>50)wrongReels.remove(wrongReels.size()-1);}
    }
    private void renderReelGate(){
        if(serverCheckMode)colored("Server reel check — shared with all operators (refreshes every 10 s).",15,0xff1565c0,0xffe3f2fd);
        else colored("LOCAL VERIFICATION — stored on this phone only, not on the server. Use one phone per order.",15,0xff8d6e00,0xfffff8e1);
        if(!reelsKnown()){text("Reels: loading the reference list…",16);return;}
        if(serverCheckMode&&verified().length()>0)colored(verified().length()+" phone-only checks from local mode are not used; re-scan those reels.",15,RED,0);
        List<String> missing=missingReels();int total=packages.length();
        if(total>0&&missing.isEmpty())colored("Reels: all "+total+" verified ✓ — PCB stages unlocked.",17,GREEN,GREEN_BG);
        else colored("Reels verified: "+(total-missing.size())+" / "+total+(total==0?" (no reference list)":""),17,RED,RED_BG);
        JSONObject o=reelOverride();String lock=reelLock();
        if(lock!=null){colored("PCB stages locked: "+lock,16,RED,0);button("Override reel lock (this phone only)…",true,()->confirmReelOverride(missing));}
        else if(o!=null&&!(total>0&&missing.isEmpty()))colored("Reel lock overridden on this phone by user "+o.optLong("actor")+" at "+o.optString("at")+"\nReason: "+o.optString("reason"),15,RED,0);
        if(o!=null)button("Remove reel-lock override",true,()->{gatePrefs().edit().remove("order_"+order.optLong("order_id")).apply();if(liveOp!=null&&reelLockedFor(liveOp))stopLive();render();});
    }
    private void confirmReelOverride(List<String> missing){
        EditText reason=new EditText(this);reason.setHint("Reason for starting PCB stages without all reels");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Override reel lock?")
            .setMessage((missing.isEmpty()?"No reference reel list was returned by the server.":"Not yet received: "+String.join(", ",missing))+"\n\nThe override is stored only on this phone and is not sent to the server. A reel added later locks the stages again.")
            .setView(reason).setNegativeButton("Cancel",null).setPositiveButton("Override",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String r=reason.getText().toString().trim();if(r.isEmpty()||r.length()>500){reason.setError("Enter a reason of 1–500 characters.");return;}
            try{JSONObject o=new JSONObject().put("actor",actor).put("reason",r).put("at",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date())).put("missing",new JSONArray(missing)).put("empty_list",packages.length()==0);
                gatePrefs().edit().putString("order_"+order.optLong("order_id"),o.toString()).apply();dialog.dismiss();say("Reel lock overridden on this phone.");render();
            }catch(Exception e){reason.setError("Could not save the override.");}
        }));dialog.show();
    }
    private void button(String title,boolean enabled,Runnable action){Button b=new Button(this);b.setText(title);b.setEnabled(enabled);b.setOnClickListener(v->action.run());body.addView(b);}
    private void render(){
        if(destroying||body==null)return;body.removeAllViews();banner.setText(notice);
        if(liveOp!=null&&(order==null||!("pcb".equals(page)||"reels".equals(page))||reelLockedFor(liveOp)))stopLive();
        if(!lastOutcome.isEmpty())text("Previous server result:\n"+lastOutcome,17);
        if(storageFailed)text("Saved-request storage is unavailable. New submissions are disabled; you may leave.",16);
        if(pending()){
            text("Unresolved request — "+journal.order()+". This does not block the normal warehouse production workflow. You can leave and return later.",16);
            if(journal.belongs(actor)){
                button("Check saved request status",available(),this::recover);
                if(retryAvailable)button("Retry original saved request",available(),()->new AlertDialog.Builder(this).setTitle("Retry the same request?").setMessage("The server found no committed result. Reuse the original request ID and exact payload?").setNegativeButton("Cancel",null).setPositiveButton("Retry original",(d,w)->retry()).show());
            }else text("Sign in as the original operator to recover this request.",16);
        }
        if(!"orders".equals(page))button("Back to orders",true,()->{page="orders";render();});
        if("orders".equals(page)){
            text("Active production orders",24);button("Refresh orders",available(),()->loadOrders(false));
            if(orders.length()==0)text("No orders loaded. Only orders returned by the server appear here.",16);
            for(int i=0;i<orders.length();i++){JSONObject o=orders.optJSONObject(i);if(o==null)continue;button(o.optString("order_number")+" · "+display(o,"product_part_number")+"\n"+o.optString("status")+" · Qty "+o.optInt("quantity")+" · "+display(o,"warehouse_code"),available(),()->select(o));}
            if(nextOrders!=JSONObject.NULL)button("Load more orders",available(),()->loadOrders(true));return;
        }
        if(order==null)return;
        text(order.optString("order_number"),24);
        text("Product: "+display(order,"product_part_number")+"\nProduct OZCODE: "+display(order,"product_ozcode"),18);
        if(order.opt("product_description") instanceof String&&!order.optString("product_description").trim().isEmpty())text(order.optString("product_description"),16);
        text("Quantity: "+order.optInt("quantity")+"\nWarehouse: "+display(order,"warehouse_name")+" ("+display(order,"warehouse_code")+")\nStatus: "+order.optString("status"),16);
        JSONObject control=route!=null?route:order;
        text("Control mode: "+control.optString("mode","Not provided")+"\nScans required for production: "+(control.optBoolean("scans_required_for_production")?"Yes":"No"),16);
        if("OBSERVATION".equals(control.optString("mode")))text("OBSERVATION: missing scans or connection loss do not block normal warehouse production. You can leave and resume later. Incorrect codes are still rejected.",16);
        button("Order overview",true,()->{page="order";render();});
        if("order".equals(page)){
            button("Refresh order route",available(),()->read("ROUTE",null));
            renderReelGate();button("Refresh reel status",available(),()->read("PACKAGES",null));
            button("PCB stages / line entry"+(reelLock()==null?"":" (locked)"),route!=null&&reelLock()==null&&(bridge.allowed("PCB_ENTRY")||bridge.allowed("STAGE_PASSAGE")),()->showPage("pcb"));
            button("Receive reels and packages",route!=null&&bridge.allowed("PACKAGE_RECEIPT"),()->showPage("reels"));
            button("Request Supplemental Reel",route!=null&&bridge.allowed("SUPPLEMENT_RESERVE"),()->showPage("supplement"));
            button("View progress",route!=null,()->showPage("progress"));
            text("Scanning does not consume materials or finish production. Normal warehouse work remains separate.",15);return;
        }
        if("pcb".equals(page)||"reels".equals(page)){
            if("pcb".equals(page)&&reelLock()!=null){colored("PCB stages locked: "+reelLock()+"\nReceive all reels first, or override from Order overview.",17,RED,RED_BG);return;}
            String op="pcb".equals(page)?"PCB_ENTRY":"PACKAGE_RECEIPT";
            if("reels".equals(page)){ // reel checks are order-level: not tied to a route step or station
                button(liveOp!=null?"● Scanning reels":"Scan reels of this order",canScan(op),this::startReelScan);
                button("Refresh reel list",available(),()->read("PACKAGES",null));renderPackages();return;
            }
            renderScanChoices(op);
            if("pcb".equals(page)&&StageRules.canSkip(route,rejectedStage,stageError,order.optLong("order_id")))button("Review skipped stages and continue",canWrite("STAGE_PASSAGE"),this::confirmSkip);
        }else if("supplement".equals(page))renderSupplement();else if("progress".equals(page))renderProgress();
    }
    private List<JSONObject> steps(String operation){List<JSONObject> out=new ArrayList<>();if(route==null)return out;JSONArray a=route.optJSONArray("steps");if(a!=null)for(int i=0;i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&s.optBoolean("enabled")&&operation.equals(s.optString("operation")))out.add(s);}out.sort(Comparator.comparingInt(s->s.optInt("sequence")));return out;}
    private boolean contains(JSONArray a,String id){if(a!=null)for(int i=0;i<a.length();i++)if(id.equals(a.optString(i)))return true;return false;}
    private void renderScanChoices(String operation){
        text("Choose the server-authorized stage and station",18);List<JSONObject> choices=steps(operation);
        if("PCB_ENTRY".equals(operation)){choices.addAll(steps("STAGE_PASSAGE"));choices.sort(Comparator.comparingInt(s->s.optInt("sequence")));}
        for(JSONObject step:choices){String stageOperation=step.optString("operation");String side=step.isNull("side")?"Not applicable":step.optString("side");text(step.optInt("sequence")+". "+stageLabel(step)+" · Side: "+side,16);
            JSONArray stations=route.optJSONArray("stations");if(stations==null)continue;
            for(int j=0;j<stations.length();j++){JSONObject station=stations.optJSONObject(j);if(station==null||!station.optBoolean("enabled")||!contains(station.optJSONArray("step_ids"),step.optString("step_id")))continue;
                boolean active=liveOp!=null&&liveStep!=null&&liveStation!=null&&step.optString("step_id").equals(liveStep.optString("step_id"))&&station.optString("station_id").equals(liveStation.optString("station_id"));
                button((active?"● Scanning at ":"Scan at ")+station.optString("name"),canScan(stageOperation),()->startLive(stageOperation,step,station));}
        }
        if(choices.isEmpty())text("No enabled stage for this operation was returned by the server.",16);
    }
    private String packageText(JSONObject p){return p.optString("package_code")+" · "+p.optString("part_number")+" / "+p.optString("ozcode")+"\n"+p.optString("description")+"\n"+p.optInt("current_quantity")+" "+p.optString("unit")+" · "+p.optString("warehouse")+" / "+p.optString("location");}
    private static String stageLabel(JSONObject step){return step.opt("label") instanceof String&&!step.optString("label").isEmpty()?step.optString("label"):step.optString("step_id");}
    private static String nullable(JSONObject data,String key){return data.has(key)&&!data.isNull(key)?String.valueOf(data.opt(key)):"None";}
    private void confirmSkip(){
        if(!StageRules.canSkip(route,rejectedStage,stageError,order.optLong("order_id")))return;
        final JSONObject rejected=rejectedStage,details=stageError.optJSONObject("details");final long owner=actor;EditText reason=new EditText(this);reason.setHint("Reason for proceeding without earlier stage records");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Confirm observation-stage skip")
            .setMessage("Missing stages: "+nullable(details,"missing_step_ids")+"\nExpected: "+nullable(details,"next_expected_step")+" / "+nullable(details,"expected_side")+"\nEarlier stages will remain NOT_RECORDED. This creates a new request, not a retry or repair.")
            .setView(reason).setNegativeButton("Cancel",null).setPositiveButton("Confirm skip and submit",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            if(actor!=owner||!StageRules.canSkip(route,rejected,stageError,order.optLong("order_id"))||route.optInt("route_version")!=rejected.getJSONObject("payload").getInt("route_version")){reason.setError("Route or operator changed. Cancel and scan again.");return;}
            JSONObject payload=StageRules.skippedPayload(rejected,reason.getText().toString());dialog.dismiss();rejectedStage=null;stageError=null;submit("STAGE_PASSAGE",payload);
        }catch(Exception e){reason.setError("Enter a reason of 1–1000 characters.");}}));dialog.show();
    }
    /** Reference list: green = received (server), black = still to scan, red = wrong scans on this phone (not recorded). */
    private void renderPackages(){
        renderReelGate();
        if(!wrongReels.isEmpty()){colored("Wrong scans — not recorded ("+wrongReels.size()+")",17,RED,0);
            for(String[] w:wrongReels)colored("✗ "+w[0]+"\n"+w[1]+" · "+w[2],15,RED,RED_BG);
            button("Clear wrong-scan list",true,()->{wrongReels.clear();render();});}
        JSONObject v=verified();
        if(reelsKnown()){text("Reference reels",18);
            if(!serverCheckMode&&v.length()>0)button("Reset reel verification on this phone…",true,()->new AlertDialog.Builder(this).setTitle("Reset reel verification?").setMessage("All "+v.length()+" reels verified on this phone for "+order.optString("order_number")+" turn black again and PCB stages lock again. Nothing changes on the server.")
                .setNegativeButton("Cancel",null).setPositiveButton("Reset",(d,w)->{verifyPrefs().edit().remove("order_"+order.optLong("order_id")).commit();wrongReels.clear();if(liveOp!=null&&reelLockedFor(liveOp))stopLive();render();}).show());}
        for(int i=0;i<packages.length();i++){JSONObject p=packages.optJSONObject(i);boolean received=isGreen(p,v);JSONObject mark=v.optJSONObject(p.optString("package_code"));
            String state=serverCheckMode?(received?"Checked on server by user "+nullable(p,"checked_by_user_id")+" · "+nullable(p,"checked_at"):"Not yet checked")
                :mark!=null?"Local verification on this phone · "+mark.optString("at"):"RECEIVED".equals(p.optString("receipt_status"))?"Received (server)":"Not yet scanned";
            colored((received?"✓ ":"○ ")+packageText(p)+"\n"+state+(p.optBoolean("supplemental")?" · Supplemental":"")+(p.optBoolean("transfer_required")?"\n⚠ Warehouse transfer required":""),16,received?GREEN:Color.BLACK,received?GREEN_BG:0xfff5f5f5);
            if(p.optBoolean("supplemental")&&!"DELIVERED".equals(p.optString("handover_status")))button("Confirm supplemental handover",canWrite("SUPPLEMENT_DELIVER")&&!p.optBoolean("transfer_required"),()->confirmDelivery(p));
            if(p.optBoolean("transfer_required"))text("Warehouse transfer required through the existing inventory workflow.",15);
        }
    }
    private void renderSupplement(){
        text("Find an item already delivered to this order",18);EditText query=new EditText(this);query.setHint("Part number, OZCODE or description");body.addView(query);
        button("Search delivered items",available(),()->{try{read("DELIVERED_ITEMS",new JSONObject().put("q",query.getText().toString()));}catch(Exception e){say("Search unavailable.");}});
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;text(item.optString("part_number")+" / "+item.optString("ozcode")+"\n"+item.optString("description"),16);
            button("Request Supplemental Reel",canWrite("SUPPLEMENT_RESERVE"),()->new AlertDialog.Builder(this).setTitle("Request a package suggestion?").setMessage("Ask the server for a package of "+item.optString("part_number")+"? This does not reserve or consume stock.")
                .setNegativeButton("Cancel",null).setPositiveButton("Get suggestion",(d,w)->{try{read("SUPPLEMENT_PROPOSAL",new JSONObject().put("item_id",item.getLong("item_id")));}catch(Exception e){say("Invalid item.");}}).show());}
        if(proposal!=null){JSONObject p=proposal.optJSONObject("package");text("Suggested package — not reserved",20);text(packageText(p)+"\nExpires: "+proposal.optString("expires_at"),16);
            if(proposal.optBoolean("transfer_required"))text("Transfer required via the existing warehouse process.",15);
            button("Reserve entire suggested package",canWrite("SUPPLEMENT_RESERVE"),()->confirmReservation(proposal));}
        button("View reserved packages / handover",route!=null,()->showPage("reels"));
    }
    private void confirmReservation(JSONObject suggestion){new AlertDialog.Builder(this).setTitle("Confirm whole-package reservation").setMessage(packageText(suggestion.optJSONObject("package"))+"\n\nReserve the entire package for "+order.optString("order_number")+"? This is a real reservation; it does not deliver or consume the package.")
        .setNegativeButton("Cancel",null).setPositiveButton("Confirm reservation",(d,w)->{try{submit("SUPPLEMENT_RESERVE",new JSONObject().put("route_version",route.getInt("route_version")).put("proposal",suggestion.getString("proposal")));}catch(Exception e){say("Suggestion is unavailable. Request a new one.");}}).show();}
    private void confirmDelivery(JSONObject p){EditText line=new EditText(this);line.setHint("Actual receiving line name");new AlertDialog.Builder(this).setTitle("Confirm physical supplemental handover").setMessage(packageText(p)+"\n\nConfirm only after physical delivery. Machine receipt requires its own scan; no material is consumed.").setView(line)
        .setNegativeButton("Cancel",null).setPositiveButton("Confirm handover",(d,w)->{try{String name=line.getText().toString().trim();if(name.isEmpty()||name.length()>120){say("A valid receiving line name is required.");return;}submit("SUPPLEMENT_DELIVER",new JSONObject().put("route_version",route.getInt("route_version")).put("allocation_id",p.getLong("allocation_id")).put("line_name",name));}catch(Exception e){say("Invalid handover details.");}}).show();}
    private void renderProgress(){
        button("Refresh progress",available(),()->{progress=new JSONArray();read("PROGRESS",null);});
        HorizontalScrollView horizontal=new HorizontalScrollView(this);TableLayout table=new TableLayout(this);horizontal.addView(table);body.addView(horizontal);
        List<JSONObject> columns=new ArrayList<>();if(route!=null){JSONArray a=route.optJSONArray("steps");for(int i=0;a!=null&&i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&"PCB".equals(s.optString("scope")))columns.add(s);}columns.sort(Comparator.comparingInt(s->s.optInt("sequence")));}
        TableRow head=new TableRow(this);cell(head,"PCB");for(JSONObject s:columns)cell(head,s.optInt("sequence")+". "+stageLabel(s)+(s.isNull("side")?"":" / "+s.optString("side")));cell(head,"Quality / route completion");cell(head,"Expected stage / side");table.addView(head);
        Set<String> seen=new HashSet<>();for(int i=0;i<progress.length();i++){
            JSONObject p=progress.optJSONObject(i);if(p==null||!seen.add(p.optString("pcb_code")))continue;TableRow row=new TableRow(this);cell(row,p.optString("pcb_code"));JSONArray states=p.optJSONArray("steps");
            for(JSONObject s:columns){JSONObject found=null;for(int j=0;states!=null&&j<states.length();j++){JSONObject v=states.optJSONObject(j);if(v!=null&&s.optString("step_id").equals(v.optString("step_id")))found=v;}
                final JSONObject detail=found;TextView entry=new TextView(this);entry.setText(detail==null?"Not confirmed":detail.optString("status"));entry.setPadding(16,14,16,14);
                if(detail!=null){if("FAIL".equals(detail.optString("status")))entry.setTextColor(0xffb00020);entry.setOnClickListener(v->stageDetails(p.optString("pcb_code"),detail));}row.addView(entry);
            }
            cell(row,nullable(p,"quality_status")+" / "+(p.has("route_completed")?(p.optBoolean("route_completed")?"Complete":"Incomplete"):"Not provided"));
            cell(row,nullable(p,"next_expected_step")+" / "+nullable(p,"expected_side"));table.addView(row);
        }
        if(nextProgress!=JSONObject.NULL)button("Load more PCB rows",available(),()->{try{read("PROGRESS",new JSONObject().put("after",nextProgress).put("limit",50));}catch(Exception e){say("Invalid pagination.");}});
    }
    private static String display(JSONObject data,String key){Object value=data.opt(key);return value instanceof String&&!((String)value).trim().isEmpty()?(String)value:"Not provided";}
    private void stageDetails(String pcb,JSONObject detail){new AlertDialog.Builder(this).setTitle(pcb+" — "+stageLabel(detail)).setMessage("Side: "+nullable(detail,"side")+"\nStatus: "+nullable(detail,"status")+"\nQuality result: "+nullable(detail,"outcome")+"\nRecorded by user: "+nullable(detail,"recorded_by_user_id")+"\nTime: "+nullable(detail,"recorded_at")+"\nPaste thickness (µm): "+nullable(detail,"paste_thickness_um")+"\nNotes: "+nullable(detail,"notes")+"\nSkipped stages: "+nullable(detail,"skipped_step_ids")+"\nSkip reason: "+nullable(detail,"skip_reason")+"\n\nRepair and historical correction are not enabled.").setPositiveButton("Close",null).show();}
    private void cell(TableRow row,String value){TextView t=new TextView(this);t.setText(value);t.setPadding(16,14,16,14);row.addView(t);}
    /** Returns false only when nothing was saved or sent. Once journaled, the request is recoverable even if sending fails. */
    private boolean submit(String operation,JSONObject payload){if(!canWrite(operation)){say("Submission unavailable. Nothing new was sent; you may leave and return later.");return false;}try{
        payload.put("request_id",UUID.randomUUID().toString());JSONObject c=command(operation).put("payload",payload);journal.begin(actor,order,c);retryAvailable=false;
        say("Awaiting server confirmation. Reading a code is not acceptance.");bridge.send(c);render();return true;
    }catch(Exception e){storageFailed=true;say("Could not safely save the request. New submissions stopped; you may leave.");render();return false;}}
    private void recover(){if(!available()||!pending()||!journal.belongs(actor))return;try{retryAvailable=false;JSONObject c=new JSONObject().put("operation","REQUEST_STATUS").put("request_id",journal.command().getJSONObject("payload").getString("request_id"));bridge.send(c);say("Checking the original saved request; no new operation is being created.");render();}catch(Exception e){say("Saved request could not be checked.");}}
    private void retry(){if(!available()||!pending()||!journal.belongs(actor)||!retryAvailable)return;try{JSONObject c=journal.command();if(!bridge.allowed(c.getString("operation"))){say("This operation is no longer permitted for this user.");return;}retryAvailable=false;bridge.send(c);say("Retrying the exact saved request. Awaiting server confirmation.");render();}catch(Exception e){say("Original request remains unresolved.");}}
    private void append(JSONArray target,JSONArray source)throws JSONException{for(int i=0;i<source.length();i++)target.put(source.getJSONObject(i));}
    @Override public void result(JSONObject sent,int status,JSONObject data){try{
        String op=sent.getString("operation");
        if("PACKAGES".equals(op)&&pollInFlight){ // background refresh: quiet, never treats a failure as an empty list
            pollInFlight=false;
            if(status!=200){pollBackoffUntil=SystemClock.elapsedRealtime()+(status==429?retryAfterMs(data,60000):30000);return;}
            if(order==null||sent.optLong("order_id")!=order.optLong("order_id"))return;
            JSONArray items=data.getJSONArray("items");pollBackoffUntil=0;
            if(reelsKnown()&&items.toString().equals(packages.toString()))return;
            packages=items;packagesOrder=order.optLong("order_id");render();return;
        }
        if("REQUEST_STATUS".equals(op)){
            if(!pending()||!journal.belongs(actor))throw new JSONException("journal");
            if(status==404&&"REQUEST_NOT_FOUND".equals(data.getJSONObject("error").getString("code"))){retryAvailable=true;say("No committed result found. Nothing was resent. Choose Retry original saved request if needed.");render();return;}
            if(status!=200||!"COMMITTED".equals(data.getString("state"))||!sent.getString("request_id").equals(data.getString("request_id")))throw new JSONException("recovery");
            acceptWrite(journal.command(),data.getInt("http_status"),data.getJSONObject("response"));return;
        }
        if(OperatorBridge.WRITES.containsKey(op)){acceptWrite(sent,status,data);return;}
        if(status!=200){
            if("BOARD_CONTEXT".equals(op)&&liveOp!=null){scanBusy=false;boardDraft=null;liveState(InlineScanner.BAD,"Not valid for this order: "+sent.optJSONObject("query").optString("pcb_code")+"\n"+error(data)+" — nothing recorded.");}
            say(error(data));render();return;
        }
        if("BOARD_CONTEXT".equals(op)){
            if(liveOp==null||boardDraft==null||order==null||route==null||actor!=scanActor||order.optLong("order_id")!=scanOrder||!StageRules.contextMatches(sent,data,route,scanOrder)){
                boardDraft=null;scanBusy=false;if(liveOp!=null)liveState(InlineScanner.BAD,"Board does not match this order or route. Nothing recorded.");
                say("Board context changed or did not match. Refresh the route and scan again.");if(order!=null&&available())read("ROUTE",null);return;
            }
            if(!sent.getJSONObject("query").getString("pcb_code").equals(boardDraft.getString("pcb_code")))throw new JSONException("board identity");
            boardContext=data;say("Board context received.");render();liveBoardReady();return;
        }
        if("ORDERS".equals(op)){JSONArray found=data.getJSONArray("items");for(int i=0;i<found.length();i++)if(!OperatorRules.order(found.getJSONObject(i)))throw new JSONException("order contract");append(orders,found);nextOrders=data.get("next_after");}
        else if("ROUTE".equals(op)){if(order==null||!OperatorRules.route(data,order.getLong("order_id")))throw new JSONException("route contract");route=data;
            if(!reelsKnown()){say("Server data loaded.");render();read("PACKAGES",null);return;}}
        else if("PACKAGES".equals(op)){if(order==null||sent.optLong("order_id")!=order.optLong("order_id"))return;packages=data.getJSONArray("items");packagesOrder=order.optLong("order_id");}
        else if("DELIVERED_ITEMS".equals(op))items=data.getJSONArray("items");
        else if("SUPPLEMENT_PROPOSAL".equals(op)){data.getString("proposal");data.getJSONObject("package");proposal=data;}
        else if("PROGRESS".equals(op)){append(progress,data.getJSONArray("items"));nextProgress=data.get("next_after");}
        say("Server data loaded.");render();
    }catch(Exception e){
        if(liveOp!=null&&scanBusy){scanBusy=false;boardDraft=null;hideResult();liveState(InlineScanner.BAD,"Invalid server response. Not confirmed — check the saved request.");}
        say("Invalid response. No acceptance confirmed; saved requests remain available for recovery.");render();}}
    private String error(JSONObject data){JSONObject e=data.optJSONObject("error");return e==null?"Server request failed.":e.optString("message","Request rejected.")+" ("+e.optString("code")+")";}
    private void acceptWrite(JSONObject sent,int status,JSONObject data)throws Exception{
        if(!pending()||!journal.belongs(actor)||!OperatorRules.same(sent,journal.command()))throw new JSONException("pending identity");
        if(status>=500||status<200){say("Server result unknown. Check the saved request before retrying.");liveFinish(sent,status,data);render();return;}
        if(status>=200&&status<300){String id=sent.getJSONObject("payload").getString("request_id"),outcome=data.getString("outcome");
            if(!OperatorRules.success(sent,actor,data))throw new JSONException("acceptance");
            journal.resolve();proposal=null;retryAvailable=false;say("Server confirmed: "+("ACCEPTED_NEW".equals(outcome)?"accepted":"already recorded; no duplicate event")+".");
        }else{data.getJSONObject("error").getString("code");journal.resolve();retryAvailable=false;say("Server rejected this request: "+error(data));
            if("STEP_OUT_OF_ORDER".equals(data.getJSONObject("error").getString("code"))&&"STAGE_PASSAGE".equals(sent.optString("operation"))){rejectedStage=new JSONObject(sent.toString());stageError=data.getJSONObject("error");say(notice+"\nMissing: "+nullable(stageError.optJSONObject("details")==null?new JSONObject():stageError.optJSONObject("details"),"missing_step_ids"));}
        }
        if(status>=200&&status<300&&StageRules.boardOperation(sent.optString("operation"))){JSONObject state=data.optJSONObject("data");if(state!=null){say(notice+"\nQuality: "+nullable(state,"quality_status")+" · Route: "+(state.has("route_completed")?(state.optBoolean("route_completed")?"Complete":"Incomplete"):"Not provided"));}rejectedStage=null;stageError=null;boardDraft=null;boardContext=null;}
        lastOutcome=notice;liveFinish(sent,status,data);render();
        if(order!=null&&order.optLong("order_id")==sent.optLong("order_id")){
            if("ROUTE_VERSION_CHANGED".equals(data.optJSONObject("error")==null?"":data.optJSONObject("error").optString("code"))){route=null;read("ROUTE",null);}
            else if(!StageRules.boardOperation(sent.optString("operation")))read("PACKAGES",null);
        }
    }
    @Override public void onBackPressed(){if(connection.getVisibility()==View.VISIBLE&&bridge.connected()){showControls();return;}if(liveOp!=null){stopLive();render();return;}if(!"orders".equals(page)){page="orders";render();}else finish();}
    @Override protected void onResume(){super.onResume();resumed=true;if(scanner!=null)scanner.resume();schedulePoll(POLL_MS);}
    @Override protected void onPause(){resumed=false;poller.removeCallbacks(pollTick);if(scanner!=null)scanner.pause();super.onPause();}
    @Override protected void onDestroy(){destroying=true;poller.removeCallbacksAndMessages(null);if(scanner!=null)scanner.release();if(bridge!=null)bridge.destroy();if(web!=null)web.destroy();super.onDestroy();}
}
