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
    // App bar and live-result row
    private LinearLayout appBar,liveRow; private TextView appTitle,appSub,onlinePill,liveIcon,liveSub;
    private String reelFilter="all",reelSide=null;
    /** A code read while a read (poll/refresh) was in flight; replayed as soon as the bridge is free. */
    private String[] deferredScan;
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
        if(!resumed||!serverCheckMode||order==null||!("order".equals(page)||"reels".equals(page)||"pcb".equals(page))||connection.getVisibility()==View.VISIBLE)return;
        if(SystemClock.elapsedRealtime()<pollBackoffUntil||!available()||scanBusy||pending())return;
        if(liveOp!=null&&SystemClock.elapsedRealtime()-lastSeen<15000)return; // each check refreshes PACKAGES anyway
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
        Ui.systemBars(this);
        root=new LinearLayout(this);root.setOrientation(1);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);root.setBackgroundColor(Ui.BG);
        buildAppBar();root.addView(appBar);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            v.setPadding(b.left,0,b.right,b.bottom);appBar.setPadding(Ui.dp(this,8),b.top+Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,12));return WindowInsets.CONSUMED;});
        banner=Ui.text(this,"",12.5f,Ui.MUTED,500);banner.setMaxLines(3);banner.setPadding(Ui.dp(this,18),Ui.dp(this,8),Ui.dp(this,18),Ui.dp(this,2));root.addView(banner);
        connection=new LinearLayout(this);connection.setOrientation(1);web=new WebView(this);connection.addView(web,new LinearLayout.LayoutParams(-1,0,1));root.addView(connection,new LinearLayout.LayoutParams(-1,0,1));
        buildLivePanel();root.addView(livePanel);
        scroll=new ScrollView(this);scroll.setClipToPadding(false);body=new LinearLayout(this);body.setOrientation(1);
        int pad=Ui.dp(this,14);body.setPadding(pad,Ui.dp(this,6),pad,Ui.dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
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
    private void buildAppBar(){
        appBar=Ui.row(this);appBar.setBackgroundColor(Ui.NAVY);appBar.setElevation(Ui.dp(this,3));
        TextView back=barIcon("←");back.setOnClickListener(v->onBackPressed());appBar.addView(back);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(1);titles.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0);
        appTitle=Ui.oneLine(Ui.text(this,"Production control",17.5f,Color.WHITE,700));appSub=Ui.oneLine(Ui.text(this,"",12.5f,0xCCFFFFFF,500));
        titles.addView(appTitle);titles.addView(appSub);appBar.addView(titles,Ui.weight(1));
        onlinePill=Ui.text(this,"Offline",11.5f,Color.WHITE,600);onlinePill.setPadding(Ui.dp(this,10),Ui.dp(this,5),Ui.dp(this,10),Ui.dp(this,5));
        onlinePill.setOnClickListener(v->{if(bridge==null||!bridge.connected())showConnection(true);});appBar.addView(onlinePill);
        TextView menu=barIcon("⋮");menu.setOnClickListener(this::showMenu);appBar.addView(menu);
    }
    private TextView barIcon(String glyph){TextView t=Ui.text(this,glyph,19,Color.WHITE,600);t.setGravity(Gravity.CENTER);t.setBackground(Ui.pressable(Ui.shape(this,0x1FFFFFFF,12,0)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,40));p.setMargins(Ui.dp(this,4),0,Ui.dp(this,4),0);t.setLayoutParams(p);return t;}
    private void showMenu(View anchor){
        PopupMenu m=new PopupMenu(this,anchor);m.getMenu().add(0,1,0,"Sign in / connection");m.getMenu().add(0,2,1,"Refresh");m.getMenu().add(0,3,2,"Leave production controls");
        m.setOnMenuItemClickListener(i->{if(i.getItemId()==1)showConnection(true);else if(i.getItemId()==2)refreshPage();else finish();return true;});m.show();
    }
    private void refreshPage(){
        if("orders".equals(page))loadOrders(false);else if("order".equals(page)){if(!read("ROUTE",null))read("PACKAGES",null);}
        else if("reels".equals(page))read("PACKAGES",null);else if("progress".equals(page)){progress=new JSONArray();read("PROGRESS",null);}else if("pcb".equals(page))read("ROUTE",null);
    }
    /** Title, subtitle and connection pill for the current page. */
    private void updateAppBar(){
        if(appBar==null)return;boolean signIn=connection.getVisibility()==View.VISIBLE;String on=order==null?"":order.optString("order_number");
        String t,s;
        if(signIn){t="Sign in";s="Approve the app connection";}
        else switch(page){
            case "order":t=on;s=display(order==null?new JSONObject():order,"product_part_number");break;
            case "reels":t="Reel check";s=on+" · "+(serverCheckMode?"server check":"local verification");break;
            case "pcb":t="PCB stages";s=on+(route==null?"":" · route v"+route.optInt("route_version"));break;
            case "supplement":t="Supplemental reel";s=on;break;
            case "progress":t="Progress";s=on;break;
            default:t="Production orders";s=session==null?"Not signed in":"Signed in · user "+actor;
        }
        appTitle.setText(t);appSub.setText(s);
        boolean online=bridge!=null&&bridge.connected();onlinePill.setText(online?"●  Online":"●  Offline");
        onlinePill.setTextColor(online?0xFFBBF7D0:0xFFFECACA);onlinePill.setBackground(Ui.shape(this,online?0x2E16A34A:0x33DC2626,99,0));
    }
    private void buildLivePanel(){
        livePanel=new LinearLayout(this);livePanel.setOrientation(1);livePanel.setVisibility(View.GONE);
        int pad=Ui.dp(this,14);livePanel.setPadding(pad,Ui.dp(this,8),pad,0);
        liveTitle=Ui.text(this,"",12,Ui.MUTED,500);liveTitle.setVisibility(View.GONE);livePanel.addView(liveTitle);
        scanner=new InlineScanner(this);scanner.setListener(new InlineScanner.Listener(){
            @Override public void onCode(String payload,String format){liveCode(payload,format);}
            @Override public void onCameraProblem(String message){if(liveOp!=null)liveState(InlineScanner.BAD,message);}
        });scanner.setOnClose(()->{stopLive();render();});livePanel.addView(scanner);
        liveRow=Ui.row(this);liveRow.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,14),Ui.dp(this,10));
        liveIcon=Ui.text(this,"•",15,Color.WHITE,800);liveIcon.setGravity(Gravity.CENTER);liveRow.addView(liveIcon,new LinearLayout.LayoutParams(Ui.dp(this,34),Ui.dp(this,34)));
        LinearLayout lt=new LinearLayout(this);lt.setOrientation(1);lt.setPadding(Ui.dp(this,12),0,0,0);
        liveStatus=Ui.text(this,"",14.5f,Ui.TEXT,700);liveSub=Ui.text(this,"",12.5f,Ui.MUTED,500);lt.addView(liveStatus);lt.addView(liveSub);liveRow.addView(lt,Ui.weight(1));
        livePanel.addView(liveRow,Ui.block(this,10));
        liveThickness=Ui.input(this,"Paste thickness µm for the next board (optional)");liveThickness.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);livePanel.addView(liveThickness,Ui.block(this,8));
        liveResult=new LinearLayout(this);liveResult.setOrientation(1);liveResult.setVisibility(View.GONE);
        liveNotes=Ui.input(this,"Notes (required for FAIL)");liveNotes.setMaxLines(4);liveResult.addView(liveNotes,Ui.block(this,8));
        LinearLayout outcomes=Ui.row(this);
        Button pass=Ui.button(this,"✓  PASS",Ui.SUCCESS,true,()->liveSubmitBoard("PASS"));Button fail=Ui.button(this,"✗  FAIL",Ui.DANGER,true,()->liveSubmitBoard("FAIL"));
        LinearLayout.LayoutParams lp=Ui.weight(1);lp.rightMargin=Ui.dp(this,6);outcomes.addView(pass,lp);LinearLayout.LayoutParams rp=Ui.weight(1);rp.leftMargin=Ui.dp(this,6);outcomes.addView(fail,rp);
        liveResult.addView(outcomes,Ui.block(this,8));
        liveResult.addView(Ui.button(this,"Discard read",Ui.TEXT_BTN,true,()->{scanBusy=false;boardDraft=null;boardContext=null;hideResult();liveState(InlineScanner.IDLE,"Read discarded — nothing recorded\nPlace the next code inside the square.");}),Ui.block(this,2));
        livePanel.addView(liveResult);
    }
    /** Result row under the camera: first line is the title, the rest the detail. */
    private void liveState(int state,String message){
        scanner.setState(state);String[] parts=message.split("\n",2);liveStatus.setText(parts[0]);liveSub.setText(parts.length>1?parts[1]:"");liveSub.setVisibility(parts.length>1?View.VISIBLE:View.GONE);
        int fg=state==InlineScanner.OK?Ui.GREEN:state==InlineScanner.BAD?Ui.RED:state==InlineScanner.WAIT?0xFFD97706:Ui.FAINT;
        int bg=state==InlineScanner.OK?Ui.GREEN_BG:state==InlineScanner.BAD?Ui.RED_BG:state==InlineScanner.WAIT?Ui.AMBER_BG:Ui.SURFACE;
        liveIcon.setText(state==InlineScanner.OK?"✓":state==InlineScanner.BAD?"✗":state==InlineScanner.WAIT?"…":"▢");
        liveIcon.setBackground(Ui.shape(this,fg,99,0));liveRow.setBackground(Ui.shape(this,bg,16,state==InlineScanner.IDLE?Ui.LINE:0));
    }
    private void hideResult(){liveResult.setVisibility(View.GONE);liveNotes.setText("");}
    /** Opens the embedded camera for one server-authorized stage and station. */
    /** Board scans write to the server; reel scans are verified on the phone only and need just the order and route. */
    private boolean canScan(String op){return StageRules.boardOperation(op)?canWrite(op):serverCheckMode?canWrite("REEL_CHECK"):order!=null&&route!=null;}
    private void startLive(String op,JSONObject step,JSONObject station){
        if(!canScan(op)){say(pending()?"Resolve the saved request before scanning.":"Scanning is unavailable: not connected or not permitted.");render();return;}
        {String lock=stepLock(step);if(lock!=null){say("Stage locked: "+lock);render();return;}}
        try{
            scanOrder=order.getLong("order_id");scanActor=actor;scanOperation=op;scannedStep=new JSONObject(step.toString());
            scanContext=new JSONObject().put("route_version",route.getInt("route_version")).put("step_id",step.getString("step_id")).put("physical_station_id",station.getString("station_id")).put("selected_side",step.get("side"));
        }catch(Exception e){say("Invalid stage details from server. Refresh the route.");return;}
        liveOp=op;liveStep=scannedStep;liveStation=station;boardDraft=null;boardContext=null;rejectedStage=null;stageError=null;scanBusy=false;lastCode="";
        boolean board=StageRules.boardOperation(op);
        liveTitle.setText((board?"Scanning: "+step.optInt("sequence")+". "+stageLabel(step)+" · Side: "+nullable(step,"side")+" · "+station.optString("name"):"Reel check · "+order.optString("order_number"))+"\n"+(board?"PCB DataMatrix — each valid board is recorded on the server automatically.":(serverCheckMode?"Reel QR label — each valid reel is checked on the server and shared with other operators.":"Reel QR label — LOCAL VERIFICATION on this phone only; nothing is sent to the server. Use one phone per order.")));
        scanner.setBadge(board?"DataMatrix · "+step.optInt("sequence")+" "+stageLabel(step)+" · "+station.optString("name"):"QR · reel label · "+(serverCheckMode?"server":"local"));
        scanner.setPreviewHeightDp(board?220:240);
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
        liveOp=null;liveStep=null;liveStation=null;scanContext=null;scanBusy=false;deferredScan=null;boardDraft=null;boardContext=null;
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
        {String lock=stepLock(liveStep);if(lock!=null){stopLive();say("Stage locked: "+lock);render();return;}}
        if(order==null||order.optLong("order_id")!=scanOrder||actor!=scanActor){stopLive();say("Order or operator changed. Choose the stage again; nothing recorded.");render();return;}
        if(bridge!=null&&bridge.connected()&&bridge.busy()&&!pending()){
            deferredScan=new String[]{raw,format};liveState(InlineScanner.WAIT,"Read — waiting for the server…\nA refresh is still running; this code is sent next.");return;}
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
    private void showConnection(boolean reload){if(liveOp!=null)stopLive();connection.setVisibility(View.VISIBLE);scroll.setVisibility(View.GONE);updateAppBar();if(reload&&bridge!=null&&!bridge.connected())web.loadUrl(BridgePolicy.PAGE);}
    private void showControls(){connection.setVisibility(View.GONE);scroll.setVisibility(View.VISIBLE);render();}
    private boolean pending(){return journal!=null&&journal.pending();}
    private boolean available(){return bridge!=null&&bridge.connected()&&!bridge.busy();}
    private boolean canWrite(String op){return available()&&!storageFailed&&!pending()&&OperatorRules.order(order)&&OperatorRules.route(route,order.optLong("order_id"))&&order.optBoolean("accepting_scans")&&bridge.allowed(op);}
    private void say(String text){notice=text;if(banner!=null){banner.setText(text);banner.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);}updateAppBar();}
    @Override public void ready(JSONObject s){try{session=s;actor=s.getJSONObject("user").getLong("id");retryAvailable=false;pollInFlight=false;
        // On a mode change, drop the cached list so PACKAGES is reloaded; PENDING rows then need fresh checks (no upload of local greens).
        boolean server=bridge.allowed("REEL_CHECK");if(server!=serverCheckMode){serverCheckMode=server;packagesOrder=0;packages=new JSONArray();if(liveOp!=null&&!StageRules.boardOperation(liveOp))stopLive();}say("Connected. Choose an order; no order is selected automatically.");showControls();if(!pending())loadOrders(false);}catch(Exception e){disconnected("Invalid session details.");}}
    @Override public void disconnected(String reason){retryAvailable=false;pollInFlight=false;if(deferredScan!=null&&liveOp!=null){deferredScan=null;liveState(InlineScanner.BAD,"Not sent — connection lost.\nReconnect and scan again.");}deferredScan=null;say(reason);
        if(liveOp!=null&&scanBusy&&!destroying){scanBusy=false;boardDraft=null;hideResult();liveState(InlineScanner.BAD,reason);}
        if(!destroying&&body!=null)render();}
    private JSONObject command(String op)throws JSONException{JSONObject c=new JSONObject().put("operation",op);if(order!=null&&!"ORDERS".equals(op)&&!"REQUEST_STATUS".equals(op))c.put("order_id",order.getLong("order_id"));return c;}
    private boolean read(String op,JSONObject query){if(!available())return false;try{JSONObject c=command(op);if(query!=null)c.put("query",query);if(bridge.send(c)){say("Loading "+op.toLowerCase(Locale.US).replace('_',' ')+"…");render();return true;}}catch(Exception e){say("Could not prepare the request.");}return false;}
    private void loadOrders(boolean more){if(!more)orders=new JSONArray();try{read("ORDERS",new JSONObject().put("after",more?nextOrders:0).put("limit",50));}catch(Exception e){say("Invalid order pagination.");}}
    private void select(JSONObject selected){if(!available())return;stopLive();packagesOrder=0;wrongReels.clear();recordedHere.clear();order=selected;route=null;proposal=null;packages=new JSONArray();progress=new JSONArray();page="order";read("ROUTE",null);}
    private void showPage(String p){page=p;proposal=null;showControls();if(!available())return;if("reels".equals(p)||("pcb".equals(p)&&serverCheckMode))read("PACKAGES",null);if("supplement".equals(p))read("DELIVERED_ITEMS",null);if("progress".equals(p)){progress=new JSONArray();read("PROGRESS",null);}}
    /** Plain body text (size: 24 = page title, 18-20 = section title, else body). */
    private TextView text(String s,int size){
        TextView t=size>=18?Ui.text(this,s,size>=22?20:16,Ui.TEXT,700):Ui.text(this,s,Math.min(size,15)-1,Ui.MUTED,500);
        body.addView(t,Ui.block(this,size>=18?16:8));return t;}
    /** Coloured strip; background 0 = coloured text only. */
    private TextView colored(String s,int size,int color,int background){
        TextView t=background==0?Ui.text(this,s,13.5f,color,600):Ui.notice(this,s,color,background);body.addView(t,Ui.block(this,8));return t;}
    private LinearLayout addCard(int topDp){LinearLayout c=Ui.card(this);body.addView(c,Ui.block(this,topDp));return c;}
    private TextView sectionTitle(String s){TextView t=Ui.text(this,s,13,Ui.MUTED,700);t.setLetterSpacing(0.06f);t.setAllCaps(true);body.addView(t,Ui.block(this,18));return t;}
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
    private String reelLock(){return gateLock(null);}
    private boolean reelLockedFor(String op){return StageRules.boardOperation(op)&&(liveStep!=null&&liveOp!=null&&op.equals(liveOp)?stepLock(liveStep):reelLock())!=null;}
    // ---- Two-sided material gate (contract 1.3: step.material_gate, PACKAGES.placement_sides) ----
    private static Set<String> sidesOf(JSONObject p){Set<String> s=new HashSet<>();JSONArray a=p==null?null:p.optJSONArray("placement_sides");for(int i=0;a!=null&&i<a.length();i++)s.add(a.optString(i));return s;}
    /** A row that carries placement_sides but maps to no side; it blocks every side until resolved. */
    private static boolean unmapped(JSONObject p){return p!=null&&p.has("placement_sides")&&sidesOf(p).isEmpty();}
    /** True when the route uses explicit material gates (the app then never infers policy from step.side). */
    private boolean sideGated(){if(route==null)return false;JSONArray a=route.optJSONArray("steps");for(int i=0;a!=null&&i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&s.has("material_gate"))return true;}return false;}
    /** Sides with a SIDE gate, in route order (the first assembled side first). */
    private List<String> gateSides(){List<String> out=new ArrayList<>();if(route==null)return out;List<JSONObject> st=new ArrayList<>();JSONArray a=route.optJSONArray("steps");
        for(int i=0;a!=null&&i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&s.optBoolean("enabled")&&"SIDE".equals(s.optString("material_gate"))&&!s.isNull("side"))st.add(s);}
        st.sort(Comparator.comparingInt(s->s.optInt("sequence")));for(JSONObject s:st)if(!out.contains(s.optString("side")))out.add(s.optString("side"));return out;}
    /** Packages a side needs (null = all). Unmapped rows are needed by every side. */
    private List<JSONObject> requiredFor(String side){List<JSONObject> out=new ArrayList<>();for(int i=0;i<packages.length();i++){JSONObject p=packages.optJSONObject(i);if(p==null)continue;if(side==null||unmapped(p)||sidesOf(p).contains(side))out.add(p);}return out;}
    private List<String> missingOf(List<JSONObject> req){List<String> out=new ArrayList<>();JSONObject v=verified();for(JSONObject p:req)if(!isGreen(p,v))out.add(p.optString("package_code"));return out;}
    /** Lock reason for one material gate (side null = whole order), or null when open. */
    private String gateLock(String side){
        if(order==null)return "No order selected.";
        if(!reelsKnown())return "Reel reference list not loaded from the server yet.";
        List<JSONObject> req=requiredFor(side);List<String> missing=missingOf(req);
        if(packages.length()==0)return overrideCovers(missing)?null:"No active package allocations returned for this order — order data is incomplete. Report it, or override with a reason.";
        // A package without a side mapping is incomplete order data: it blocks every side even when checked,
        // until the server maps it (or the operator overrides with a reason) — same rule as an empty list.
        List<String> unmappedCodes=new ArrayList<>();for(JSONObject p:req)if(unmapped(p))unmappedCodes.add(p.optString("package_code"));
        List<String> blocking=new ArrayList<>(missing);for(String c:unmappedCodes)if(!blocking.contains(c))blocking.add(c);
        if(blocking.isEmpty()||overrideCovers(blocking))return null;
        if(missing.isEmpty())return unmappedCodes.size()+" package(s) have no side mapping ("+String.join(", ",unmappedCodes)+") — order data is incomplete. Report it, or override with a reason.";
        return missing.size()+" of "+req.size()+(side==null?"":" "+side)+" reels not yet verified."+(unmappedCodes.isEmpty()?"":" "+unmappedCodes.size()+" package(s) have no side mapping — report it.");
    }
    /** Lock reason for a route step. NONE = open, SIDE = that side's reels, ALL or unknown = all reels; no field = legacy whole-order rule. */
    private String stepLock(JSONObject step){
        if(step==null||!StageRules.boardOperation(step.optString("operation")))return null;
        if(!step.has("material_gate"))return reelLock();
        String g=step.optString("material_gate");
        if("NONE".equals(g))return null;
        if("SIDE".equals(g))return step.isNull("side")?"Route error: side gate without a side. Report it.":gateLock(step.optString("side"));
        return gateLock(null);
    }
    private void wrongReel(String label,String reason){
        String time=new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        if(!wrongReels.isEmpty()&&wrongReels.get(0)[0].equals(label)&&wrongReels.get(0)[1].equals(reason)){wrongReels.get(0)[2]=time;}
        else{wrongReels.add(0,new String[]{label,reason,time});while(wrongReels.size()>50)wrongReels.remove(wrongReels.size()-1);}
    }
    /** Reel summary card: count, progress bar, lock state and verification mode. */
    private void renderReelSummary(boolean withModeNotice){
        LinearLayout c=addCard(10);
        LinearLayout top=Ui.row(this);top.addView(Ui.icon(this,"🧵",Ui.GREEN_BG,38));
        LinearLayout tt=new LinearLayout(this);tt.setOrientation(1);tt.setPadding(Ui.dp(this,12),0,0,0);
        tt.addView(Ui.text(this,"REELS",11.5f,Ui.MUTED,700));
        if(!reelsKnown()){tt.addView(Ui.text(this,"Loading reference list…",15,Ui.TEXT,600));top.addView(tt,Ui.weight(1));c.addView(top);return;}
        List<String> missing=missingReels();int total=packages.length(),done=total-missing.size();
        tt.addView(Ui.text(this,done+" / "+total+(total==0?"  no reference list":"  verified"),20,Ui.TEXT,800));top.addView(tt,Ui.weight(1));
        if(!sideGated()){String lock=reelLock();
            top.addView(lock==null?Ui.chip(this,"PCB unlocked",Ui.GREEN,Ui.GREEN_BG):Ui.chip(this,"🔒 PCB locked",0xFF475467,Ui.GREY_BG));}
        c.addView(top);
        View bar=Ui.progress(this,total==0?0:(float)done/total,missing.isEmpty()&&total>0?Ui.GREEN:Ui.NAVY2);LinearLayout.LayoutParams bp=(LinearLayout.LayoutParams)bar.getLayoutParams();bp.topMargin=Ui.dp(this,12);c.addView(bar);
        if(sideGated()){int n=1;for(String side:gateSides()){List<JSONObject> req=requiredFor(side);int miss=missingOf(req).size(),ok=req.size()-miss;String lock=gateLock(side);
            LinearLayout sr=Ui.row(this);sr.addView(Ui.text(this,(n++)+". "+side,13.5f,Ui.TEXT,700),Ui.weight(1));
            sr.addView(Ui.text(this,ok+" / "+req.size()+"   ",13,Ui.MUTED,600));
            sr.addView(lock==null?Ui.chip(this,"Unlocked",Ui.GREEN,Ui.GREEN_BG):Ui.chip(this,miss>0?"🔒 "+miss+" left":"🔒 no side data",miss>0?0xFF475467:Ui.RED,miss>0?Ui.GREY_BG:Ui.RED_BG));c.addView(sr,Ui.block(this,12));
            View b2=Ui.progress(this,req.isEmpty()?1:(float)ok/req.size(),lock==null?Ui.GREEN:Ui.NAVY2);((LinearLayout.LayoutParams)b2.getLayoutParams()).topMargin=Ui.dp(this,6);c.addView(b2);}}
        if(serverCheckMode&&verified().length()>0)c.addView(Ui.text(this,verified().length()+" phone-only checks from local mode are not used; re-scan those reels.",12.5f,Ui.RED,600),Ui.block(this,10));
        if(withModeNotice)c.addView(serverCheckMode?Ui.notice(this,"Server reel check — shared with all operators, refreshed every 10 s.",Ui.NAVY2,Ui.BLUE_BG)
            :Ui.notice(this,"⚠  Local verification — stored on this phone only. Use one phone per order.",Ui.AMBER,Ui.AMBER_BG),Ui.block(this,12));
    }
    /** Lock reason with override / remove-override actions. */
    private void renderLockControls(){
        if(!reelsKnown())return;List<String> missing=missingReels();int total=packages.length();
        JSONObject o=reelOverride();String lock=reelLock();
        if(sideGated()){for(String side:gateSides()){String sl=gateLock(side);if(sl==null)continue;List<String> sm=new ArrayList<>(missingOf(requiredFor(side)));for(JSONObject p:requiredFor(side))if(unmapped(p)&&!sm.contains(p.optString("package_code")))sm.add(p.optString("package_code"));
                colored(side+" stages locked: "+sl,13,Ui.RED,0);button("Override "+side+" reel lock (this phone only)…",Ui.DANGER,true,()->confirmReelOverride(sm));}
            if(o!=null)colored("Reel lock override on this phone by user "+o.optLong("actor")+" at "+o.optString("at")+" · Reason: "+o.optString("reason"),13,Ui.AMBER,Ui.AMBER_BG);}
        else if(lock!=null){colored("PCB stages locked: "+lock,13,Ui.RED,0);button("Override reel lock (this phone only)…",Ui.DANGER,true,()->confirmReelOverride(missing));}
        else if(o!=null&&!(total>0&&missing.isEmpty()))colored("Reel lock overridden on this phone by user "+o.optLong("actor")+" at "+o.optString("at")+"\nReason: "+o.optString("reason"),13,Ui.AMBER,Ui.AMBER_BG);
        if(o!=null)button("Remove reel-lock override",Ui.TEXT_BTN,true,()->{gatePrefs().edit().remove("order_"+order.optLong("order_id")).apply();if(liveOp!=null&&reelLockedFor(liveOp))stopLive();render();});
    }
    private void confirmReelOverride(List<String> missing){
        EditText reason=new EditText(this);reason.setHint("Reason for starting PCB stages without all reels");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Override reel lock?")
            .setMessage((missing.isEmpty()?"No reference reel list was returned by the server.":"Not yet received: "+String.join(", ",missing))+"\n\nThe override is stored only on this phone and is not sent to the server. A reel added later locks the stages again.")
            .setView(reason).setNegativeButton("Cancel",null).setPositiveButton("Override",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String r=reason.getText().toString().trim();if(r.isEmpty()||r.length()>500){reason.setError("Enter a reason of 1–500 characters.");return;}
            try{Set<String> all=new LinkedHashSet<>(missing);JSONObject old=reelOverride();String reasons=r;
                if(old!=null){JSONArray a=old.optJSONArray("missing");for(int i=0;a!=null&&i<a.length();i++)all.add(a.optString(i));reasons=old.optString("reason")+" | "+r;}
                JSONObject o=new JSONObject().put("actor",actor).put("reason",reasons).put("at",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date())).put("missing",new JSONArray(all)).put("empty_list",packages.length()==0||(old!=null&&old.optBoolean("empty_list")));
                gatePrefs().edit().putString("order_"+order.optLong("order_id"),o.toString()).apply();dialog.dismiss();say("Reel lock overridden on this phone.");render();
            }catch(Exception e){reason.setError("Could not save the override.");}
        }));dialog.show();
    }
    private void button(String title,boolean enabled,Runnable action){button(title,Ui.OUTLINE,enabled,action);}
    private Button button(String title,int kind,boolean enabled,Runnable action){Button b=Ui.button(this,title,kind,enabled,action);body.addView(b,Ui.block(this,8));return b;}
    private void render(){
        if(destroying||body==null)return;body.removeAllViews();banner.setText(notice);banner.setVisibility(notice==null||notice.isEmpty()?View.GONE:View.VISIBLE);
        if(liveOp!=null&&(order==null||!("pcb".equals(page)||"reels".equals(page))||reelLockedFor(liveOp)))stopLive();
        updateAppBar();
        if(storageFailed)colored("Saved-request storage is unavailable. New submissions are disabled; you may leave.",13,Ui.RED,Ui.RED_BG);
        if(pending()){
            LinearLayout c=addCard(8);c.setBackground(Ui.shape(this,Ui.AMBER_BG,18,0));c.setElevation(0);
            c.addView(Ui.text(this,"Unresolved request — "+journal.order(),14.5f,Ui.AMBER,700));
            c.addView(Ui.text(this,"New board scans wait until this is resolved. Normal warehouse production is not blocked.",12.5f,Ui.AMBER,500),Ui.block(this,4));
            if(journal.belongs(actor)){
                c.addView(Ui.button(this,"Check saved request status",Ui.PRIMARY,available(),this::recover),Ui.block(this,10));
                if(!available())c.addView(Ui.text(this,"Reconnect first: tap Offline (top right) or ⋮ → Sign in.",12,Ui.AMBER,600),Ui.block(this,6));
                if(retryAvailable)c.addView(Ui.button(this,"Retry original saved request",Ui.OUTLINE,available(),()->new AlertDialog.Builder(this).setTitle("Retry the same request?").setMessage("The server found no committed result. Reuse the original request ID and exact payload?").setNegativeButton("Cancel",null).setPositiveButton("Retry original",(d,w)->retry()).show()),Ui.block(this,8));
            }else c.addView(Ui.text(this,"Sign in as the original operator to recover this request.",12.5f,Ui.AMBER,600),Ui.block(this,8));
        }
        if("orders".equals(page)){renderOrders();return;}
        if(order==null)return;
        if("order".equals(page)){renderDashboard();return;}
        if("pcb".equals(page)){
            if(!sideGated()&&reelLock()!=null){
                LinearLayout c=addCard(10);c.addView(Ui.text(this,"🔒  PCB stages are locked",16,Ui.TEXT,700));
                c.addView(Ui.text(this,reelLock(),13,Ui.MUTED,500),Ui.block(this,6));
                c.addView(Ui.button(this,"Go to reel check",Ui.PRIMARY,true,()->showPage("reels")),Ui.block(this,12));
                renderLockControls();return;
            }
            if(!lastOutcome.isEmpty()&&liveOp==null)colored("Last server result: "+lastOutcome.replace("\n"," · "),13,Ui.MUTED,Ui.GREY_BG);
            if(sideGated())renderReelSummary(false);
            renderScanChoices("PCB_ENTRY");
            if(sideGated())renderLockControls();
            if(StageRules.canSkip(route,rejectedStage,stageError,order.optLong("order_id")))button("Review skipped stages and continue",Ui.DANGER,canWrite("STAGE_PASSAGE"),this::confirmSkip);
            return;
        }
        if("reels".equals(page)){renderPackages();return;}
        if("supplement".equals(page))renderSupplement();else if("progress".equals(page))renderProgress();
    }
    private void renderOrders(){
        LinearLayout head=Ui.row(this);head.addView(Ui.text(this,"Active orders",20,Ui.TEXT,800),Ui.weight(1));
        head.addView(Ui.button(this,"↻  Refresh",Ui.TONAL,available(),()->loadOrders(false)));body.addView(head,Ui.block(this,10));
        if(orders.length()==0){LinearLayout c=addCard(12);c.addView(Ui.text(this,session==null?"Sign in to load orders":"No orders loaded",15,Ui.TEXT,700));
            c.addView(Ui.text(this,session==null?"Open the menu ⋮ → Sign in / connection.":"Only orders returned by the server appear here.",12.5f,Ui.MUTED,500),Ui.block(this,4));
            if(session==null)c.addView(Ui.button(this,"Sign in",Ui.PRIMARY,true,()->showConnection(true)),Ui.block(this,12));}
        for(int i=0;i<orders.length();i++){JSONObject o=orders.optJSONObject(i);if(o==null)continue;
            LinearLayout c=addCard(10);c.setBackground(Ui.pressable(Ui.shape(this,Ui.SURFACE,18,0)));c.setClickable(available());c.setOnClickListener(v->select(o));c.setAlpha(available()?1f:0.55f);
            LinearLayout top=Ui.row(this);top.addView(Ui.text(this,o.optString("order_number"),16.5f,Ui.TEXT,800),Ui.weight(1));
            top.addView(Ui.chip(this,o.optString("status"),Ui.NAVY2,Ui.BLUE_BG));c.addView(top);
            c.addView(Ui.oneLine(Ui.text(this,display(o,"product_part_number"),13.5f,Ui.TEXT,600)),Ui.block(this,6));
            c.addView(Ui.text(this,"Qty "+o.optInt("quantity")+"   ·   "+display(o,"warehouse_code")+"   ·   "+o.optString("mode",""),12.5f,Ui.MUTED,500),Ui.block(this,2));
        }
        if(nextOrders!=JSONObject.NULL)button("Load more orders",Ui.TEXT_BTN,available(),()->loadOrders(true));
    }
    private void renderDashboard(){
        JSONObject control=route!=null?route:order;
        LinearLayout c=addCard(8);
        LinearLayout top=Ui.row(this);LinearLayout tt=new LinearLayout(this);tt.setOrientation(1);
        tt.addView(Ui.oneLine(Ui.text(this,display(order,"product_part_number"),15.5f,Ui.TEXT,700)));
        if(order.opt("product_description") instanceof String&&!order.optString("product_description").trim().isEmpty()){TextView d=Ui.text(this,order.optString("product_description"),12,Ui.MUTED,500);d.setMaxLines(2);d.setEllipsize(android.text.TextUtils.TruncateAt.END);tt.addView(d,Ui.block(this,2));}
        top.addView(tt,Ui.weight(1));String mode=control.optString("mode","");
        if(!mode.isEmpty()){TextView chip=Ui.chip(this,mode,Ui.NAVY2,Ui.BLUE_BG);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=Ui.dp(this,8);top.addView(chip,cp);}
        c.addView(top);
        c.addView(Ui.text(this,"Qty  "+order.optInt("quantity")+"     Warehouse  "+display(order,"warehouse_code")+"     "+order.optString("status")+"     OZ "+display(order,"product_ozcode"),12.5f,Ui.MUTED,600),Ui.block(this,10));
        // tiles
        String lock=reelLock();List<String> missing=reelsKnown()?missingReels():new ArrayList<>();int total=packages.length();
        boolean pcbAllowed=route!=null&&(bridge.allowed("PCB_ENTRY")||bridge.allowed("STAGE_PASSAGE"));
        LinearLayout r1=Ui.row(this),r2=Ui.row(this);
        String reelSub=null,pcbValue,pcbSub;boolean anyOpen;int stageCount=steps("PCB_ENTRY").size()+steps("STAGE_PASSAGE").size();
        if(sideGated()&&reelsKnown()){StringBuilder sb=new StringBuilder();String firstLocked=null;int firstMissing=0;anyOpen=false;
            for(String side:gateSides()){List<JSONObject> req=requiredFor(side);int miss=missingOf(req).size();if(sb.length()>0)sb.append(" · ");sb.append(side.charAt(0)).append(side.substring(1).toLowerCase(Locale.US)).append(" ").append(req.size()-miss).append("/").append(req.size());
                if(gateLock(side)==null)anyOpen=true;else if(firstLocked==null){firstLocked=side;firstMissing=miss;}}
            reelSub=sb.toString();pcbValue=firstLocked==null?stageCount+" stages":anyOpen?"Partly open":"Locked";
            pcbSub=firstLocked==null?"All sides ready":firstLocked+(firstMissing>0?" · "+firstMissing+" reels left":" · side data missing");}
        else{anyOpen=lock==null;pcbValue=lock==null?stageCount+" stages":"Locked";pcbSub=lock==null?"Ready to scan":(reelsKnown()?missing.size()+" reels left":"Waiting for reels");}
        tile(r1,"🧵",Ui.GREEN_BG,"REELS",reelsKnown()?(total-missing.size())+" / "+total:"…",reelsKnown()&&total>0?(float)(total-missing.size())/total:-1,reelSub,route!=null,()->showPage("reels"),true);
        tile(r1,anyOpen?"▦":"🔒",anyOpen?Ui.BLUE_BG:Ui.GREY_BG,"PCB STAGES",pcbValue,-1,pcbSub,pcbAllowed,()->showPage("pcb"),false);
        tile(r2,"＋",Ui.ORANGE_BG,"SUPPLEMENT","Request",-1,"Extra reel for this order",route!=null&&bridge.allowed("SUPPLEMENT_RESERVE"),()->showPage("supplement"),true);
        tile(r2,"↗",Ui.BLUE_BG,"PROGRESS","Boards",-1,"Quantity "+order.optInt("quantity"),route!=null,()->showPage("progress"),false);
        body.addView(r1,Ui.block(this,12));body.addView(r2,Ui.block(this,12));
        body.addView(serverCheckMode?Ui.notice(this,"Server reel check — shared with all operators.",Ui.NAVY2,Ui.BLUE_BG)
            :Ui.notice(this,"⚠  Local verification — reel checks are stored on this phone only. Use one phone per order.",Ui.AMBER,Ui.AMBER_BG),Ui.block(this,14));
        renderLockControls();
        if("OBSERVATION".equals(mode)){TextView t=Ui.text(this,"OBSERVATION: missing scans or connection loss do not block normal warehouse production. Scanning does not consume material.",11.5f,Ui.FAINT,500);body.addView(t,Ui.block(this,14));}
    }
    /** Dashboard tile; progress < 0 hides the bar. */
    private void tile(LinearLayout row,String glyph,int iconBg,String label,String value,float progress,String sub,boolean enabled,Runnable open,boolean left){
        LinearLayout t=Ui.card(this);t.setMinimumHeight(Ui.dp(this,128));t.setBackground(Ui.pressable(Ui.shape(this,enabled?Ui.SURFACE:0xFFFAFBFC,20,0)));
        t.setClickable(enabled);t.setOnClickListener(v->open.run());t.setAlpha(enabled?1f:0.6f);
        t.addView(Ui.icon(this,glyph,iconBg,38));
        t.addView(Ui.text(this,label,11.5f,Ui.MUTED,700),Ui.block(this,10));
        t.addView(Ui.oneLine(Ui.text(this,value,20,Ui.TEXT,800)),Ui.block(this,2));
        if(progress>=0){View bar=Ui.progress(this,progress,progress>=1?Ui.GREEN:Ui.NAVY2);((LinearLayout.LayoutParams)bar.getLayoutParams()).topMargin=Ui.dp(this,8);t.addView(bar);}
        if(sub!=null)t.addView(Ui.oneLine(Ui.text(this,sub,11.5f,Ui.MUTED,500)),Ui.block(this,4));
        LinearLayout.LayoutParams p=Ui.weight(1);if(left)p.rightMargin=Ui.dp(this,6);else p.leftMargin=Ui.dp(this,6);row.addView(t,p);
    }
    private List<JSONObject> steps(String operation){List<JSONObject> out=new ArrayList<>();if(route==null)return out;JSONArray a=route.optJSONArray("steps");if(a!=null)for(int i=0;i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&s.optBoolean("enabled")&&operation.equals(s.optString("operation")))out.add(s);}out.sort(Comparator.comparingInt(s->s.optInt("sequence")));return out;}
    private boolean contains(JSONArray a,String id){if(a!=null)for(int i=0;i<a.length();i++)if(id.equals(a.optString(i)))return true;return false;}
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
    private void renderScanChoices(String operation){
        List<JSONObject> choices=steps(operation);
        if("PCB_ENTRY".equals(operation)){choices.addAll(steps("STAGE_PASSAGE"));choices.sort(Comparator.comparingInt(s->s.optInt("sequence")));}
        if(choices.isEmpty()){LinearLayout c=addCard(10);c.addView(Ui.text(this,"No enabled stage was returned by the server.",14,Ui.MUTED,600));return;}
        if(liveOp==null)sectionTitle("Choose stage and station");
        for(JSONObject step:choices){String stageOperation=step.optString("operation");
            boolean stepLive=liveOp!=null&&liveStep!=null&&step.optString("step_id").equals(liveStep.optString("step_id"));
            LinearLayout c=addCard(10);LinearLayout top=Ui.row(this);
            TextView n=Ui.icon(this,String.valueOf(step.optInt("sequence")),Ui.BLUE_BG,40);n.setTextColor(Ui.NAVY);n.setTypeface(Ui.font(800));n.setTextSize(15);top.addView(n);
            LinearLayout tt=new LinearLayout(this);tt.setOrientation(1);tt.setPadding(Ui.dp(this,12),0,Ui.dp(this,8),0);
            tt.addView(Ui.oneLine(Ui.text(this,stageLabel(step),15,Ui.TEXT,700)));
            String side=step.isNull("side")?"n/a":step.optString("side");
            tt.addView(Ui.oneLine(Ui.text(this,"Side "+side+" · "+("PCB_ENTRY".equals(stageOperation)?"entry":"passage")+(step.optBoolean("requires_result")?" · PASS/FAIL":""),12,Ui.MUTED,500)));
            top.addView(tt,Ui.weight(1));
            String lock=stepLock(step);
            if(stepLive)top.addView(Ui.chip(this,"● Scanning",Ui.GREEN,Ui.GREEN_BG));
            else if(lock!=null)top.addView(Ui.chip(this,"🔒 Locked",0xFF475467,Ui.GREY_BG));
            else if("SIDE".equals(step.optString("material_gate")))top.addView(Ui.chip(this,"Reels ✓",Ui.GREEN,Ui.GREEN_BG));
            c.addView(top);
            if(lock!=null)c.addView(Ui.text(this,lock,12.5f,Ui.RED,600),Ui.block(this,8));
            JSONArray stations=route.optJSONArray("stations");if(stations==null)continue;
            LinearLayout chips=Ui.row(this);HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.addView(chips);
            int count=0;
            for(int j=0;j<stations.length();j++){JSONObject station=stations.optJSONObject(j);if(station==null||!station.optBoolean("enabled")||!contains(station.optJSONArray("step_ids"),step.optString("step_id")))continue;
                boolean active=stepLive&&liveStation!=null&&station.optString("station_id").equals(liveStation.optString("station_id"));
                Button b=Ui.button(this,(active?"●  ":"⌗  ")+station.optString("name"),active?Ui.PRIMARY:Ui.OUTLINE,canScan(stageOperation)&&lock==null,()->startLive(stageOperation,step,station));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.rightMargin=Ui.dp(this,8);chips.addView(b,p);count++;}
            if(count>0)c.addView(hs,Ui.block(this,12));else c.addView(Ui.text(this,"No enabled station for this stage.",12.5f,Ui.MUTED,500),Ui.block(this,8));
        }
    }
    /** Reel page: summary, scan action, filter and the reference list (green = verified, ring = pending, red = wrong scan). */
    private void renderPackages(){
        renderReelSummary(liveOp==null);
        if(liveOp==null)button("⌗   Scan reels of this order",Ui.PRIMARY,canScan("PACKAGE_RECEIPT"),this::startReelScan);
        List<String> sides=sideGated()?gateSides():new ArrayList<>();if(reelSide!=null&&!sides.contains(reelSide))reelSide=null;
        if(!sides.isEmpty()){LinearLayout sideRow=Ui.row(this);List<String> opts=new ArrayList<>();opts.add(null);opts.addAll(sides);
            for(String o:opts){boolean on=o==null?reelSide==null:o.equals(reelSide);String label=o==null?"All sides":o+" ("+requiredFor(o).size()+")";
                TextView chip=Ui.chip(this,label,on?0xFFFFFFFF:Ui.NAVY,on?Ui.NAVY:Ui.BLUE_BG);chip.setTextSize(12.5f);chip.setPadding(Ui.dp(this,12),Ui.dp(this,7),Ui.dp(this,12),Ui.dp(this,7));
                chip.setOnClickListener(x->{reelSide=o;render();});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.rightMargin=Ui.dp(this,8);sideRow.addView(chip,cp);}
            body.addView(sideRow,Ui.block(this,14));}
        List<JSONObject> shown=requiredFor(reelSide);
        JSONObject v=verified();int total=shown.size(),pending=reelsKnown()?missingOf(shown).size():0,done=total-pending,wrong=wrongReels.size();
        LinearLayout seg=Ui.row(this);seg.setBackground(Ui.shape(this,0xFFE9EDF4,12,0));int p3=Ui.dp(this,3);seg.setPadding(p3,p3,p3,p3);
        String[][] tabs={{"all","All "+total},{"pending","Pending "+pending},{"done","Done "+done},{"wrong","Wrong "+wrong}};
        for(String[] tab:tabs){boolean on=tab[0].equals(reelFilter);TextView t=Ui.text(this,tab[1],12.5f,on?Ui.NAVY:("wrong".equals(tab[0])&&wrong>0?Ui.RED:0xFF475467),on?700:600);
            t.setGravity(Gravity.CENTER);t.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));if(on){t.setBackground(Ui.shape(this,Ui.SURFACE,9,0));t.setElevation(Ui.dp(this,1));}
            t.setOnClickListener(x->{reelFilter=tab[0];render();});seg.addView(t,Ui.weight(1));}
        body.addView(seg,Ui.block(this,14));
        LinearLayout list=Ui.card(this);list.setPadding(0,Ui.dp(this,2),0,Ui.dp(this,2));body.addView(list,Ui.block(this,10));
        int rows=0;
        if("all".equals(reelFilter)||"wrong".equals(reelFilter))for(String[] w:wrongReels){reelRow(list,'r',w[0],w[1]+" · "+w[2],null,null);rows++;}
        if(!"wrong".equals(reelFilter))for(JSONObject p:shown){boolean green=isGreen(p,v);
            if("pending".equals(reelFilter)&&green||"done".equals(reelFilter)&&!green)continue;
            JSONObject mark=v.optJSONObject(p.optString("package_code"));
            String state=serverCheckMode?(green?"checked by user "+nullable(p,"checked_by_user_id"):null):(mark!=null?"verified "+mark.optString("at").replaceFirst("^\\d{4}-\\d{2}-\\d{2} ",""):"RECEIVED".equals(p.optString("receipt_status"))?"received (server)":null);
            Set<String> ps=sidesOf(p);String sideTag=!p.has("placement_sides")?"":ps.isEmpty()?"⚠ NO SIDE · ":ps.size()>1?"TOP+BOTTOM · ":ps.iterator().next()+" · ";
            String sub=sideTag+p.optString("part_number")+" · "+p.optString("description")+(state==null?"":"\n"+state)+(p.optBoolean("supplemental")?" · supplemental":"")+(p.optBoolean("transfer_required")?"\n⚠ warehouse transfer required":"");
            reelRow(list,green?'g':'p',p.optString("package_code"),sub,p.optInt("current_quantity")+" "+p.optString("unit"),p.optString("location"));rows++;
            if(p.optBoolean("supplemental")&&!"DELIVERED".equals(p.optString("handover_status"))){Button b=Ui.button(this,"Confirm supplemental handover",Ui.TONAL,canWrite("SUPPLEMENT_DELIVER")&&!p.optBoolean("transfer_required"),()->confirmDelivery(p));
                LinearLayout.LayoutParams bp=Ui.block(this,0);bp.setMargins(Ui.dp(this,14),0,Ui.dp(this,14),Ui.dp(this,10));list.addView(b,bp);}
        }
        if(rows==0){TextView e=Ui.text(this,!reelsKnown()?"Loading…":"wrong".equals(reelFilter)?"No wrong scans":"Nothing in this filter",13,Ui.MUTED,500);e.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16));list.addView(e);}
        LinearLayout actions=Ui.row(this);
        actions.addView(Ui.button(this,"↻ Refresh",Ui.TEXT_BTN,available(),()->read("PACKAGES",null)));
        if(wrong>0)actions.addView(Ui.button(this,"Clear wrong",Ui.TEXT_BTN,true,()->{wrongReels.clear();render();}));
        if(!serverCheckMode&&v.length()>0){Button reset=Ui.button(this,"Reset local…",Ui.TEXT_BTN,true,()->new AlertDialog.Builder(this).setTitle("Reset reel verification?").setMessage("All "+v.length()+" reels verified on this phone for "+order.optString("order_number")+" turn back to pending and PCB stages lock again. Nothing changes on the server.")
                .setNegativeButton("Cancel",null).setPositiveButton("Reset",(d,w)->{verifyPrefs().edit().remove("order_"+order.optLong("order_id")).commit();wrongReels.clear();if(liveOp!=null&&reelLockedFor(liveOp))stopLive();render();}).show());reset.setTextColor(Ui.RED);actions.addView(reset);}
        body.addView(actions,Ui.block(this,8));
    }
    /** One compact list row: status circle, code + detail, quantity + location. */
    private void reelRow(LinearLayout list,char kind,String code,String sub,String qty,String loc){
        if(list.getChildCount()>0)list.addView(Ui.divider(this));
        LinearLayout r=Ui.row(this);r.setPadding(Ui.dp(this,14),Ui.dp(this,10),Ui.dp(this,14),Ui.dp(this,10));
        TextView st=Ui.text(this,kind=='g'?"✓":kind=='r'?"✗":"",12.5f,kind=='g'?Ui.GREEN:Ui.RED,800);st.setGravity(Gravity.CENTER);
        st.setBackground(kind=='g'?Ui.shape(this,Ui.GREEN_BG,99,0):kind=='r'?Ui.shape(this,Ui.RED_BG,99,0):Ui.shape(this,Ui.SURFACE,99,0xFFD0D5DD));
        r.addView(st,new LinearLayout.LayoutParams(Ui.dp(this,26),Ui.dp(this,26)));
        LinearLayout tt=new LinearLayout(this);tt.setOrientation(1);tt.setPadding(Ui.dp(this,12),0,Ui.dp(this,8),0);
        tt.addView(Ui.oneLine(Ui.text(this,code,13.5f,kind=='r'?Ui.RED:Ui.TEXT,700)));
        TextView s=Ui.text(this,sub,11.5f,Ui.MUTED,500);s.setMaxLines(3);s.setEllipsize(android.text.TextUtils.TruncateAt.END);tt.addView(s);
        r.addView(tt,Ui.weight(1));
        if(qty!=null){LinearLayout q=new LinearLayout(this);q.setOrientation(1);q.setGravity(Gravity.END);
            q.addView(Ui.text(this,qty,12.5f,Ui.TEXT,600));q.addView(Ui.text(this,loc==null?"":loc,11,Ui.MUTED,500));r.addView(q);}
        list.addView(r);
    }
    private void renderSupplement(){
        text("Find an item already delivered to this order",18);EditText query=Ui.input(this,"Part number, OZCODE or description");body.addView(query,Ui.block(this,8));
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
        LinearLayout tableCard=addCard(12);tableCard.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,4));HorizontalScrollView horizontal=new HorizontalScrollView(this);TableLayout table=new TableLayout(this);horizontal.addView(table);tableCard.addView(horizontal);
        List<JSONObject> columns=new ArrayList<>();if(route!=null){JSONArray a=route.optJSONArray("steps");for(int i=0;a!=null&&i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&"PCB".equals(s.optString("scope")))columns.add(s);}columns.sort(Comparator.comparingInt(s->s.optInt("sequence")));}
        TableRow head=new TableRow(this);head.setTag(Boolean.TRUE);head.setBackgroundColor(Ui.GREY_BG);cell(head,"PCB");for(JSONObject s:columns)cell(head,s.optInt("sequence")+". "+stageLabel(s)+(s.isNull("side")?"":" / "+s.optString("side")));cell(head,"Quality / route completion");cell(head,"Expected stage / side");table.addView(head);
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
    private void cell(TableRow row,String value){TextView t=Ui.text(this,value,13,isHeader(row)?Ui.MUTED:Ui.TEXT,isHeader(row)?700:500);int h=Ui.dp(this,14),v=Ui.dp(this,10);t.setPadding(h,v,h,v);row.addView(t);}
    private boolean isHeader(TableRow row){return Boolean.TRUE.equals(row.getTag());}
    /** Returns false only when nothing was saved or sent. Once journaled, the request is recoverable even if sending fails. */
    private boolean submit(String operation,JSONObject payload){if(!canWrite(operation)){say("Submission unavailable. Nothing new was sent; you may leave and return later.");return false;}try{
        payload.put("request_id",UUID.randomUUID().toString());JSONObject c=command(operation).put("payload",payload);journal.begin(actor,order,c);retryAvailable=false;
        say("Awaiting server confirmation. Reading a code is not acceptance.");bridge.send(c);render();return true;
    }catch(Exception e){storageFailed=true;say("Could not safely save the request. New submissions stopped; you may leave.");render();return false;}}
    private void recover(){if(!available()||!pending()||!journal.belongs(actor))return;try{retryAvailable=false;JSONObject c=new JSONObject().put("operation","REQUEST_STATUS").put("request_id",journal.command().getJSONObject("payload").getString("request_id"));bridge.send(c);say("Checking the original saved request; no new operation is being created.");render();}catch(Exception e){say("Saved request could not be checked.");}}
    private void retry(){if(!available()||!pending()||!journal.belongs(actor)||!retryAvailable)return;try{JSONObject c=journal.command();if(!bridge.allowed(c.getString("operation"))){say("This operation is no longer permitted for this user.");return;}retryAvailable=false;bridge.send(c);say("Retrying the exact saved request. Awaiting server confirmation.");render();}catch(Exception e){say("Original request remains unresolved.");}}
    private void append(JSONArray target,JSONArray source)throws JSONException{for(int i=0;i<source.length();i++)target.put(source.getJSONObject(i));}
    private void runDeferred(){if(deferredScan==null||liveOp==null){deferredScan=null;return;}String[] d=deferredScan;deferredScan=null;lastCode="";liveCode(d[0],d[1]);}
    @Override public void result(JSONObject sent,int status,JSONObject data){if(deferredScan!=null)poller.post(this::runDeferred);try{
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
    @Override public void onBackPressed(){if(connection.getVisibility()==View.VISIBLE){if(bridge.connected())showControls();else finish();return;}if(liveOp!=null){stopLive();render();return;}
        if("order".equals(page)){page="orders";render();}else if(!"orders".equals(page)){page="order";proposal=null;render();}else finish();}
    @Override protected void onResume(){super.onResume();resumed=true;if(scanner!=null)scanner.resume();schedulePoll(POLL_MS);}
    @Override protected void onPause(){resumed=false;poller.removeCallbacks(pollTick);if(scanner!=null)scanner.pause();super.onPause();}
    @Override protected void onDestroy(){destroying=true;poller.removeCallbacksAndMessages(null);if(scanner!=null)scanner.release();if(bridge!=null)bridge.destroy();if(web!=null)web.destroy();super.onDestroy();}
}
