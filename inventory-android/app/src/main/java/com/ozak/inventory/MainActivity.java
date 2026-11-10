package com.ozak.inventory;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;

/** Read-only production bridge and local scanner preview. No production writes. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://inventory.askpart.com";
    private LinearLayout root;
    private WebView web;
    private TextView status;
    private boolean loginVisible;
    private boolean failed;
    private ProductionBridge bridge;
    private TextView bridgeStatus;
    private Button sessionCheck, ordersCheck;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        WebView.setWebContentsDebuggingEnabled(false);
        root = new LinearLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 16, 24, 16);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(android.view.WindowInsets.Type.systemBars()) : null;
            if (bars != null) v.setPadding(24 + bars.left, 16 + bars.top, 24 + bars.right, 16 + bars.bottom);
            return insets;
        });
        setContentView(root);
        if (state != null && state.getBoolean("loginVisible")) showLogin(); else showHome();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setPadding(0, 12, 0, 12);
        root.addView(view); return view;
    }
    private void button(String text, Runnable action, boolean enabled) {
        Button b = new Button(this); b.setText(text); b.setEnabled(enabled);
        b.setOnClickListener(v -> action.run()); root.addView(b);
    }
    private void detachWeb() {
        if (web != null && web.getParent() != null) ((android.view.ViewGroup) web.getParent()).removeView(web);
    }
    private void showHome() {
        if (bridge != null) bridge.visible(false);
        detachWeb(); root.removeAllViews(); loginVisible = false;
        LinearLayout container = root;
        ScrollView scroll = new ScrollView(this);
        container.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        label("OZAK Inventory", 26);
        label("Production Control", 20);
        label("Open production controls to sign in and select an authorized order.", 16);
        button("Production controls", () -> startActivity(new android.content.Intent(this, OperatorActivity.class)), true);
        android.content.Intent scanner = new android.content.Intent().setClassName(this,"org.datamatrix.linereader.InventoryScannerActivity");
        button("Open PCB / QR Scanner", () -> startActivityForResult(scanner,81), scanner.resolveActivity(getPackageManager()) != null);
        label("Select the order, stage and station from the server's available options.", 16);
        label("These two checks can be completed in either order. Scanning does not record material consumption or finished production.", 16);
        button("Clear Local Login Data", () -> new AlertDialog.Builder(this)
            .setMessage("Clear this app's cookies and login data? This does not sign you out on the server.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d,w) -> clearSession()).show(), true);
        root = container;
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=81||result!=RESULT_OK||data==null)return;
        String payload=data.getStringExtra("payload"),format=data.getStringExtra("format");
        if(payload==null||!("DATA_MATRIX".equals(format)||"QR_CODE".equals(format)))return;
        new AlertDialog.Builder(this).setTitle("Code read locally")
            .setMessage(format+"\n"+payload+"\n\nNo production record was sent. Server acceptance is required.")
            .setPositiveButton("OK",null).show();
    }
    private void clearSession() {
        if (bridge != null) { bridge.destroy(); bridge = null; }
        if (web != null) { detachWeb(); web.stopLoading(); web.clearCache(true); web.clearHistory(); web.destroy(); web = null; }
        WebStorage.getInstance().deleteAllData();
        root.removeAllViews(); loginVisible = false;
        label("Clearing local login data…", 16);
        CookieManager.getInstance().removeAllCookies(done -> {
            CookieManager.getInstance().flush();
            if (!isFinishing() && !isDestroyed()) showHome();
        });
    }
    private boolean safeNavigation(String url) {
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
    }
    private void showLogin() {
        detachWeb();
        root.removeAllViews(); loginVisible = true;
        button("Back to Stations", this::showHome, true);
        status = label("Sign in to continue. Production actions are not enabled yet.", 14);
        bridgeStatus = label("Open the connection page and approve this app. Live writes are disabled.", 14);
        LinearLayout checks = new LinearLayout(this);
        sessionCheck = new Button(this); sessionCheck.setText("Check Session"); sessionCheck.setEnabled(false);
        ordersCheck = new Button(this); ordersCheck.setText("Check Orders"); ordersCheck.setEnabled(false);
        checks.addView(sessionCheck,new LinearLayout.LayoutParams(0,-2,1));
        checks.addView(ordersCheck,new LinearLayout.LayoutParams(0,-2,1)); root.addView(checks);
        sessionCheck.setOnClickListener(v -> { if (bridge != null) bridge.read("SESSION"); });
        ordersCheck.setOnClickListener(v -> { if (bridge != null) bridge.read("ORDERS"); });
        button("Reload Inventory", () -> web.loadUrl(ORIGIN), true);
        boolean first = web == null;
        if (first) createWeb();
        if (bridge != null) bridge.visible(true);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        if (first) web.loadUrl(ORIGIN);
    }
    private void createWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        bridge = new ProductionBridge(web, (message, enabled) -> {
            if (bridgeStatus != null) bridgeStatus.setText(message);
            if (sessionCheck != null) sessionCheck.setEnabled(enabled);
            if (ordersCheck != null) ordersCheck.setEnabled(enabled);
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && bridge != null) bridge.navigation();
                if (safeNavigation(request.getUrl().toString())) return false;
                status.setText("This link type is not supported in the pilot."); return true;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (bridge != null) bridge.navigation();
                failed = false;
                // Show host only: never expose callback queries, tokens, or full URLs.
                status.setText("Loading: " + Uri.parse(url).getHost());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (bridge != null) bridge.checkLocation();
                CookieManager.getInstance().flush();
                if (!failed) status.setText("Site: " + Uri.parse(url).getHost() + "\nProduction actions are not enabled yet.");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (bridge != null) bridge.invalidate("Secure connection failed. Operations are disabled.");
                handler.cancel(); failed = true; status.setText("The secure connection could not be verified. Loading stopped.");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && bridge != null) bridge.invalidate("Connection interrupted. Approve again after signing in.");
                if (req.isForMainFrame()) { failed = true; status.setText("Could not load the page. Check your connection and try again."); }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse res) {
                if (req.isForMainFrame() && bridge != null) bridge.invalidate("Page request failed. Operations are disabled.");
                if (req.isForMainFrame()) { failed = true; status.setText("Server response: " + res.getStatusCode() + ". Sign-in has not been confirmed."); }
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (bridge != null) bridge.checkLocation();
            }
        });
    }
    @Override public void onBackPressed() {
        if (loginVisible && web != null && web.canGoBack()) web.goBack();
        else if (loginVisible) showHome(); else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("loginVisible", loginVisible);
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        if (bridge != null) { bridge.destroy(); bridge = null; }
        if (web != null) { detachWeb(); web.destroy(); web = null; }
        super.onDestroy();
    }
}
