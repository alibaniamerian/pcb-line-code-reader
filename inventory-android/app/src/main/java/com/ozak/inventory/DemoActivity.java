package com.ozak.inventory;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import android.text.*;

/** Isolated interactive mock: no WebView, network client, camera, or production bridge. */
public final class DemoActivity extends Activity {
    private DemoStore store=new DemoStore();
    private LinearLayout body;
    private String screen="orders", message="All records below are synthetic.";
    private int scenario;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(getLastNonConfigurationInstance() instanceof DemoStore)store=(DemoStore)getLastNonConfigurationInstance();
        if(state!=null && getLastNonConfigurationInstance() instanceof DemoStore){screen=state.getString("screen","orders");message=state.getString("message",message);scenario=state.getInt("scenario");}
        else if(state!=null)message="Demo restarted with fresh synthetic data. Previous mock results were not retained.";
        render();
    }
    @Override public Object onRetainNonConfigurationInstance(){return store;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("screen",screen);state.putString("message",message);state.putInt("scenario",scenario);}
    private TextView text(LinearLayout parent,String value,int size) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,12,0,12);parent.addView(t);return t;
    }
    private void text(String value){text(body,value,16);}
    private void button(LinearLayout parent,String title,Runnable click,boolean enabled){
        Button b=new Button(this);b.setText(title);b.setEnabled(enabled);parent.addView(b);b.setOnClickListener(v->click.run());
    }
    private void button(String title,Runnable click){button(body,title,click,true);}
    private void go(String next){screen=next;render();}
    private boolean pending(){return store.pending!=null;}
    private DemoStore.Outcome outcome(){return DemoStore.Outcome.values()[scenario];}
    private void send(String action,String value){message=store.send(action,value,outcome());render();}
    private void render(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,24,24,24);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(24+i.getSystemWindowInsetLeft(),24+i.getSystemWindowInsetTop(),24+i.getSystemWindowInsetRight(),24+i.getSystemWindowInsetBottom());return i;});
        TextView banner=text(root,"MOCK DEMO — No live operations",20);banner.setTextColor(Color.rgb(150,65,0));
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=new LinearLayout(this);body.setOrientation(1);scroll.addView(body);setContentView(root);
        text("Synthetic order DEMO-PO-001 • Target: 2 PCBs");
        text(message);
        if(pending()){
            text("Outcome unknown: "+store.pending.action+" / "+store.pending.value+". Counts are hidden until a response is received.");
            button("Retry same mock request",()->{message=store.retry(outcome());render();});
        }
        text("Simulated response for the next action:");
        Spinner scenarios=new Spinner(this);
        scenarios.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Valid response","Rejected","Timeout after mock commit","Timeout before mock commit"}));
        scenarios.setSelection(scenario);body.addView(scenarios);
        scenarios.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){scenario=pos;}});
        if(screen.equals("orders")) {
            text("Select an order");button("DEMO-PO-001 — Demo controller board",()->go("stations"));
            text("No production orders are loaded. Demo state resets when the app process ends.");
            button("Exit demo",this::finish);return;
        }
        button("Back to "+(screen.equals("stations")?"Orders":"Stations"),()->go(screen.equals("stations")?"orders":"stations"));
        if(screen.equals("stations")){
            text("Independent checks — either can be completed first");
            button("PCB Line Entry",()->go("pcb"));button("Receive Reels and Packages",()->go("reels"));
            button("Delivered Items / Supplemental Reels",()->go("items"));button("PCB Progress",()->go("progress"));
            text("Supplemental requests belong to material receipt. Later production stages and rework remain disabled.");return;
        }
        if(screen.equals("pcb")){
            text("PCB Line Entry");text("Mock server expected step: entry-01 / PCB Line Entry / Side: not applicable");
            text("Fixture serials: AAA00001, AAA00002, AAA00003. The mock server maps exact serials; the app does not infer the item.");
            scanInput("AAA00001","pcb");
            if(!pending())text("Confirmed mock PCB count: "+store.boards.size()+" / 2");return;
        }
        if(screen.equals("reels")){
            text("Receive one exact reserved package at a time");
            text("Each simulated scan submits one request immediately. No batch receipt. Fixture codes are not a real QR format.");
            scanInput("DEMO-R02","receive");
            if(!pending()){
                int received=0,total=0;for(DemoStore.Reel r:store.reels)if(r.reserved){total++;if(r.received)received++;}
                text("Received: "+received+" • Remaining: "+(total-received));
                for(DemoStore.Reel r:store.reels)if(r.reserved)text((r.received?"RECEIVED":"AWAITING RECEIPT")+" — "+r.part+"\n"+r.details());
            }
            text("Try DEMO-S01 before reservation to see an exact-package rejection, even though its item matches DEMO-R01.");return;
        }
        if(screen.equals("items")){
            text("Items delivered to this line");text("Find the item without scanning the reel mounted on the machine.");
            EditText search=new EditText(this);search.setSingleLine();search.setHint("Search part number, OZCODE or description");body.addView(search);
            LinearLayout results=new LinearLayout(this);results.setOrientation(1);body.addView(results);
            fillItems(results,"");search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){fillItems(results,s.toString());}public void afterTextChanged(Editable e){}});return;
        }
        if(screen.equals("progress")){
            text("One row per PCB; columns follow this order's route.");
            text("Illustrative route only. Expected step and side come from the server; later steps are disabled.");
            if(pending())return;
            HorizontalScrollView horizontal=new HorizontalScrollView(this);body.addView(horizontal);
            TableLayout table=new TableLayout(this);horizontal.addView(table);
            row(table,new String[]{"PCB","1. Entry / N/A","2. Paste / Top","3. SPI / Top","4. P&P / Top","5. Oven / Top"});
            for(String board:store.boards)row(table,new String[]{board,"Mock confirmed","Disabled","Disabled","Disabled","Disabled"});
            if(store.boards.isEmpty())text("No PCBs entered in this demo yet.");
        }
    }
    private void scanInput(String sample,String action){
        EditText code=new EditText(this);code.setSingleLine();code.setHint("Synthetic scan payload");code.setText(sample);body.addView(code);
        button(body,"Simulate scan and submit",()->send(action,code.getText().toString()),!pending());
        text("Camera scanning is not connected in this mock.");
    }
    private void fillItems(LinearLayout parent,String query){
        parent.removeAllViews();if(pending()){text(parent,"Resolve the unknown request to refresh delivered items.",16);return;}
        java.util.List<DemoStore.Reel> items=store.deliveredItems(query);
        if(items.isEmpty())text(parent,"No matching delivered items.",16);
        for(DemoStore.Reel r:items){
            text(parent,r.part+" • "+r.ozcode+"\n"+r.description,18);
            button(parent,"Request Supplemental Reel",()->suggest(r),true);
        }
    }
    private void suggest(DemoStore.Reel item){
        DemoStore.Reel candidate=store.suggest(item.ozcode);
        if(candidate==null){new AlertDialog.Builder(this).setMessage("MOCK: No eligible package available.").setPositiveButton("OK",null).show();return;}
        new AlertDialog.Builder(this).setTitle("MOCK — Suggested supplemental package")
            .setMessage(item.part+" • "+item.ozcode+"\n"+candidate.details()+"\n\nReserve the whole package for DEMO-PO-001? Any transfer follows the existing process. This does not empty the previous reel, receive this package, or consume stock.")
            .setNegativeButton("Cancel",null).setPositiveButton("Confirm Mock Reservation",(d,w)->send("reserve",candidate.barcode)).show();
    }
    private void row(TableLayout table,String[] cells){TableRow row=new TableRow(this);table.addView(row);for(String cell:cells){TextView t=new TextView(this);t.setText(cell);t.setPadding(16,16,16,16);row.addView(t);}}
    @Override public void onBackPressed(){if(screen.equals("orders"))super.onBackPressed();else go(screen.equals("stations")?"orders":"stations");}
}
