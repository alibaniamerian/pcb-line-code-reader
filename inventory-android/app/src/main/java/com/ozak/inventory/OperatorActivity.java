package com.ozak.inventory;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Operator-driven workflow. No background writes or automatic replay. */
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
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);WebView.setWebContentsDebuggingEnabled(false);
        try{journal=new OperatorJournal(getFilesDir());}catch(Exception e){storageFailed=true;}
        root=new LinearLayout(this);root.setOrientation(1);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setPadding(20,16,20,16);root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(20+i.getSystemWindowInsetLeft(),16+i.getSystemWindowInsetTop(),20+i.getSystemWindowInsetRight(),16+i.getSystemWindowInsetBottom());return i;});
        banner=new TextView(this);banner.setTextSize(16);root.addView(banner);
        LinearLayout nav=new LinearLayout(this);Button exit=new Button(this);exit.setText("Leave controls");exit.setOnClickListener(v->finish());nav.addView(exit,new LinearLayout.LayoutParams(0,-2,1));
        Button login=new Button(this);login.setText("Connection / sign in");login.setOnClickListener(v->showConnection(true));nav.addView(login,new LinearLayout.LayoutParams(0,-2,1));root.addView(nav);
        connection=new LinearLayout(this);connection.setOrientation(1);web=new WebView(this);connection.addView(web,new LinearLayout.LayoutParams(-1,0,1));root.addView(connection,new LinearLayout.LayoutParams(-1,0,1));
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
    private void showConnection(boolean reload){connection.setVisibility(View.VISIBLE);scroll.setVisibility(View.GONE);if(reload&&bridge!=null&&!bridge.connected())web.loadUrl(BridgePolicy.PAGE);}
    private void showControls(){connection.setVisibility(View.GONE);scroll.setVisibility(View.VISIBLE);render();}
    private boolean pending(){return journal!=null&&journal.pending();}
    private boolean available(){return bridge!=null&&bridge.connected()&&!bridge.busy();}
    private boolean canWrite(String op){return available()&&!storageFailed&&!pending()&&OperatorRules.order(order)&&OperatorRules.route(route,order.optLong("order_id"))&&order.optBoolean("accepting_scans")&&bridge.allowed(op);}
    private void say(String text){notice=text;if(banner!=null)banner.setText(text);}
    @Override public void ready(JSONObject s){try{session=s;actor=s.getJSONObject("user").getLong("id");retryAvailable=false;say("Connected. Choose an order; no order is selected automatically.");showControls();if(!pending())loadOrders(false);}catch(Exception e){disconnected("Invalid session details.");}}
    @Override public void disconnected(String reason){retryAvailable=false;say(reason);if(!destroying&&body!=null)render();}
    private JSONObject command(String op)throws JSONException{JSONObject c=new JSONObject().put("operation",op);if(order!=null&&!"ORDERS".equals(op)&&!"REQUEST_STATUS".equals(op))c.put("order_id",order.getLong("order_id"));return c;}
    private void read(String op,JSONObject query){if(!available())return;try{JSONObject c=command(op);if(query!=null)c.put("query",query);if(bridge.send(c)){say("Loading "+op.toLowerCase(Locale.US).replace('_',' ')+"…");render();}}catch(Exception e){say("Could not prepare the request.");}}
    private void loadOrders(boolean more){if(!more)orders=new JSONArray();try{read("ORDERS",new JSONObject().put("after",more?nextOrders:0).put("limit",50));}catch(Exception e){say("Invalid order pagination.");}}
    private void select(JSONObject selected){if(!available())return;order=selected;route=null;proposal=null;packages=new JSONArray();progress=new JSONArray();page="order";read("ROUTE",null);}
    private void showPage(String p){page=p;proposal=null;showControls();if(!available())return;if("reels".equals(p))read("PACKAGES",null);if("supplement".equals(p))read("DELIVERED_ITEMS",null);if("progress".equals(p)){progress=new JSONArray();read("PROGRESS",null);}}
    private void text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,10,0,10);body.addView(t);}
    private void button(String title,boolean enabled,Runnable action){Button b=new Button(this);b.setText(title);b.setEnabled(enabled);b.setOnClickListener(v->action.run());body.addView(b);}
    private void render(){
        if(destroying||body==null)return;body.removeAllViews();banner.setText(notice);
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
            button("PCB stages / line entry",route!=null&&(bridge.allowed("PCB_ENTRY")||bridge.allowed("STAGE_PASSAGE")),()->showPage("pcb"));
            button("Receive reels and packages",route!=null&&bridge.allowed("PACKAGE_RECEIPT"),()->showPage("reels"));
            button("Request Supplemental Reel",route!=null&&bridge.allowed("SUPPLEMENT_RESERVE"),()->showPage("supplement"));
            button("View progress",route!=null,()->showPage("progress"));
            text("Scanning does not consume materials or finish production. Normal warehouse work remains separate.",15);return;
        }
        if("pcb".equals(page)||"reels".equals(page)){
            String op="pcb".equals(page)?"PCB_ENTRY":"PACKAGE_RECEIPT";renderScanChoices(op);
            if("pcb".equals(page)&&StageRules.canSkip(route,rejectedStage,stageError,order.optLong("order_id")))button("Review skipped stages and continue",canWrite("STAGE_PASSAGE"),this::confirmSkip);
            if("reels".equals(page)){button("Refresh received / pending packages",available(),()->read("PACKAGES",null));renderPackages();}
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
                button("Scan at "+station.optString("name"),canWrite(stageOperation),()->scan(stageOperation,step,station));}
        }
        if(choices.isEmpty())text("No enabled stage for this operation was returned by the server.",16);
    }
    private void scan(String op,JSONObject step,JSONObject station){try{
        scanOrder=order.getLong("order_id");scanActor=actor;scannedStep=new JSONObject(step.toString());boardDraft=null;boardContext=null;rejectedStage=null;stageError=null;
        scanOperation=op;scanContext=new JSONObject().put("route_version",route.getInt("route_version")).put("step_id",step.getString("step_id")).put("physical_station_id",station.getString("station_id")).put("selected_side",step.get("side"));
        Intent intent=new Intent().setClassName(this,"org.datamatrix.linereader.InventoryScannerActivity").putExtra("format",StageRules.boardOperation(op)?"DATA_MATRIX":"QR_CODE");startActivityForResult(intent,82);
    }catch(Exception e){say("Scanner unavailable in this build.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=82||result!=RESULT_OK||data==null||scanContext==null)return;
        try{String raw=data.getStringExtra("payload"),format=data.getStringExtra("format");if(raw==null||raw.isEmpty()||raw.length()>4096)throw new JSONException("scan");
            if(order==null||order.optLong("order_id")!=scanOrder||actor!=scanActor){say("Order or operator changed. Scan again; nothing submitted.");return;}
            String expected=StageRules.boardOperation(scanOperation)?"DATA_MATRIX":"QR_CODE";if(!expected.equals(format)){say("Wrong code format for this control. Nothing submitted.");return;}
            JSONObject payload=new JSONObject(scanContext.toString()).put(StageRules.boardOperation(scanOperation)?"pcb_code":"scan_text",raw);String operation=scanOperation;scanContext=null;
            if(StageRules.boardOperation(operation)){boardDraft=payload;if(!available()){say("Code read locally. Reconnect and scan again before submitting.");return;}read("BOARD_CONTEXT",new JSONObject().put("pcb_code",raw));return;}
            new AlertDialog.Builder(this).setTitle("Code read locally — not yet accepted").setMessage(raw+"\n\nSubmit this code for "+order.getString("order_number")+"?")
                .setNegativeButton("Cancel",null).setPositiveButton("Submit scan",(d,w)->submit(operation,payload)).show();
        }catch(Exception e){say("Invalid local scan. Nothing submitted.");}}
    private String packageText(JSONObject p){return p.optString("package_code")+" · "+p.optString("part_number")+" / "+p.optString("ozcode")+"\n"+p.optString("description")+"\n"+p.optInt("current_quantity")+" "+p.optString("unit")+" · "+p.optString("warehouse")+" / "+p.optString("location");}
    private static String stageLabel(JSONObject step){return step.opt("label") instanceof String&&!step.optString("label").isEmpty()?step.optString("label"):step.optString("step_id");}
    private static String nullable(JSONObject data,String key){return data.has(key)&&!data.isNull(key)?String.valueOf(data.opt(key)):"None";}
    private void boardForm(){
        if(boardDraft==null||boardContext==null||scannedStep==null)return;
        final JSONObject draft=boardDraft,step=scannedStep;final String operation=scanOperation;final long owner=scanActor,orderId=scanOrder;
        LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(24,12,24,12);
        TextView info=new TextView(this);info.setText("Local read: "+draft.optString("pcb_code")+"\nSelected: "+stageLabel(step)+" / "+nullable(step,"side")+"\nExpected by server: "+nullable(boardContext,"next_expected_step")+" / "+nullable(boardContext,"expected_side")+"\nQuality: "+nullable(boardContext,"quality_status")+"\nContext is not server acceptance.");form.addView(info);
        Spinner outcome=new Spinner(this);boolean resultAllowed=step.optBoolean("requires_result")||step.optBoolean("allows_paste_measurement");
        outcome.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{step.optBoolean("requires_result")?"Select PASS or FAIL":"Passage only (no quality result)","PASS","FAIL"}));if(resultAllowed)form.addView(outcome);
        EditText notes=new EditText(this);notes.setHint("Notes (required for FAIL)");notes.setMaxLines(5);if("STAGE_PASSAGE".equals(operation))form.addView(notes);
        EditText thickness=new EditText(this);thickness.setHint("Paste thickness in micrometres (optional)");thickness.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);if(step.optBoolean("allows_paste_measurement"))form.addView(thickness);
        TextView validation=new TextView(this);form.addView(validation);ScrollView container=new ScrollView(this);container.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Confirm PCB stage").setView(container).setNegativeButton("Cancel",null).setPositiveButton("Submit stage",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            if(order==null||order.optLong("order_id")!=orderId||actor!=owner||route==null||route.optInt("route_version")!=draft.optInt("route_version")){validation.setText("Order, operator or route changed. Cancel and scan again.");return;}
            String selected=resultAllowed&&outcome.getSelectedItemPosition()>0?outcome.getSelectedItem().toString():null;
            String note=notes.getText().toString().trim(),measure=thickness.getText().toString().trim();String problem=StageRules.validate(step,selected,note,measure);if(problem!=null){validation.setText(problem);return;}
            JSONObject payload=new JSONObject(draft.toString());if("STAGE_PASSAGE".equals(operation)){payload.put("outcome",selected==null?JSONObject.NULL:selected).put("notes",note).put("allow_skip",false).put("skip_reason","");if(!measure.isEmpty())payload.put("paste_thickness_um",Double.parseDouble(measure));}
            dialog.dismiss();submit(operation,payload);
        }catch(Exception e){validation.setText("Could not prepare the stage; nothing submitted.");}}));dialog.show();
    }
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
    private void renderPackages(){int remaining=0;for(int i=0;i<packages.length();i++)if(!"RECEIVED".equals(packages.optJSONObject(i).optString("receipt_status")))remaining++;
        text("Server packages: "+packages.length()+" · Pending receipt: "+remaining,18);
        for(int i=0;i<packages.length();i++){JSONObject p=packages.optJSONObject(i);text(packageText(p)+"\nReceipt: "+p.optString("receipt_status")+" · Handover: "+p.optString("handover_status")+(p.isNull("blocking_reason")?"":"\n"+p.optString("blocking_reason")),16);
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
    private void submit(String operation,JSONObject payload){if(!canWrite(operation)){say("Submission unavailable. Nothing new was sent; you may leave and return later.");return;}try{
        payload.put("request_id",UUID.randomUUID().toString());JSONObject c=command(operation).put("payload",payload);journal.begin(actor,order,c);retryAvailable=false;
        say("Awaiting server confirmation. Reading a code is not acceptance.");bridge.send(c);render();
    }catch(Exception e){storageFailed=true;say("Could not safely save the request. New submissions stopped; you may leave.");render();}}
    private void recover(){if(!available()||!pending()||!journal.belongs(actor))return;try{retryAvailable=false;JSONObject c=new JSONObject().put("operation","REQUEST_STATUS").put("request_id",journal.command().getJSONObject("payload").getString("request_id"));bridge.send(c);say("Checking the original saved request; no new operation is being created.");render();}catch(Exception e){say("Saved request could not be checked.");}}
    private void retry(){if(!available()||!pending()||!journal.belongs(actor)||!retryAvailable)return;try{JSONObject c=journal.command();if(!bridge.allowed(c.getString("operation"))){say("This operation is no longer permitted for this user.");return;}retryAvailable=false;bridge.send(c);say("Retrying the exact saved request. Awaiting server confirmation.");render();}catch(Exception e){say("Original request remains unresolved.");}}
    private void append(JSONArray target,JSONArray source)throws JSONException{for(int i=0;i<source.length();i++)target.put(source.getJSONObject(i));}
    @Override public void result(JSONObject sent,int status,JSONObject data){try{
        String op=sent.getString("operation");
        if("REQUEST_STATUS".equals(op)){
            if(!pending()||!journal.belongs(actor))throw new JSONException("journal");
            if(status==404&&"REQUEST_NOT_FOUND".equals(data.getJSONObject("error").getString("code"))){retryAvailable=true;say("No committed result found. Nothing was resent. Choose Retry original saved request if needed.");render();return;}
            if(status!=200||!"COMMITTED".equals(data.getString("state"))||!sent.getString("request_id").equals(data.getString("request_id")))throw new JSONException("recovery");
            acceptWrite(journal.command(),data.getInt("http_status"),data.getJSONObject("response"));return;
        }
        if(OperatorBridge.WRITES.containsKey(op)){acceptWrite(sent,status,data);return;}
        if(status!=200){say(error(data));render();return;}
        if("BOARD_CONTEXT".equals(op)){
            if(boardDraft==null||order==null||route==null||actor!=scanActor||order.optLong("order_id")!=scanOrder||!StageRules.contextMatches(sent,data,route,scanOrder)){
                boardDraft=null;say("Board context changed or did not match. Refresh the route and scan again.");if(order!=null&&available())read("ROUTE",null);return;
            }
            if(!sent.getJSONObject("query").getString("pcb_code").equals(boardDraft.getString("pcb_code")))throw new JSONException("board identity");
            boardContext=data;say("Board context received. Confirm the stage and result; nothing recorded yet.");render();boardForm();return;
        }
        if("ORDERS".equals(op)){JSONArray found=data.getJSONArray("items");for(int i=0;i<found.length();i++)if(!OperatorRules.order(found.getJSONObject(i)))throw new JSONException("order contract");append(orders,found);nextOrders=data.get("next_after");}
        else if("ROUTE".equals(op)){if(order==null||!OperatorRules.route(data,order.getLong("order_id")))throw new JSONException("route contract");route=data;}
        else if("PACKAGES".equals(op))packages=data.getJSONArray("items");
        else if("DELIVERED_ITEMS".equals(op))items=data.getJSONArray("items");
        else if("SUPPLEMENT_PROPOSAL".equals(op)){data.getString("proposal");data.getJSONObject("package");proposal=data;}
        else if("PROGRESS".equals(op)){append(progress,data.getJSONArray("items"));nextProgress=data.get("next_after");}
        say("Server data loaded.");render();
    }catch(Exception e){say("Invalid response. No acceptance confirmed; saved requests remain available for recovery.");render();}}
    private String error(JSONObject data){JSONObject e=data.optJSONObject("error");return e==null?"Server request failed.":e.optString("message","Request rejected.")+" ("+e.optString("code")+")";}
    private void acceptWrite(JSONObject sent,int status,JSONObject data)throws Exception{
        if(!pending()||!journal.belongs(actor)||!OperatorRules.same(sent,journal.command()))throw new JSONException("pending identity");
        if(status>=500||status<200){say("Server result unknown. Check the saved request before retrying.");render();return;}
        if(status>=200&&status<300){String id=sent.getJSONObject("payload").getString("request_id"),outcome=data.getString("outcome");
            if(!OperatorRules.success(sent,actor,data))throw new JSONException("acceptance");
            journal.resolve();proposal=null;retryAvailable=false;say("Server confirmed: "+("ACCEPTED_NEW".equals(outcome)?"accepted":"already recorded; no duplicate event")+".");
        }else{data.getJSONObject("error").getString("code");journal.resolve();retryAvailable=false;say("Server rejected this request: "+error(data));
            if("STEP_OUT_OF_ORDER".equals(data.getJSONObject("error").getString("code"))&&"STAGE_PASSAGE".equals(sent.optString("operation"))){rejectedStage=new JSONObject(sent.toString());stageError=data.getJSONObject("error");say(notice+"\nMissing: "+nullable(stageError.optJSONObject("details")==null?new JSONObject():stageError.optJSONObject("details"),"missing_step_ids"));}
        }
        if(status>=200&&status<300&&StageRules.boardOperation(sent.optString("operation"))){JSONObject state=data.optJSONObject("data");if(state!=null){say(notice+"\nQuality: "+nullable(state,"quality_status")+" · Route: "+(state.has("route_completed")?(state.optBoolean("route_completed")?"Complete":"Incomplete"):"Not provided"));}rejectedStage=null;stageError=null;boardDraft=null;boardContext=null;}
        lastOutcome=notice;render();
        if(order!=null&&order.optLong("order_id")==sent.optLong("order_id")){
            if("ROUTE_VERSION_CHANGED".equals(data.optJSONObject("error")==null?"":data.optJSONObject("error").optString("code"))){route=null;read("ROUTE",null);}
            else if("reels".equals(page))read("PACKAGES",null);
        }
    }
    @Override public void onBackPressed(){if(connection.getVisibility()==View.VISIBLE&&bridge.connected()){showControls();return;}if(!"orders".equals(page)){page="orders";render();}else finish();}
    @Override protected void onDestroy(){destroying=true;if(bridge!=null)bridge.destroy();if(web!=null)web.destroy();super.onDestroy();}
}
