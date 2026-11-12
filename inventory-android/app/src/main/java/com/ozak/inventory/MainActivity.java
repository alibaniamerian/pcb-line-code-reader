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
    private int topInset, bottomInset; private LinearLayout hero;
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
        Ui.systemBars(this); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            topInset = bars.top; bottomInset = bars.bottom;
            if (loginVisible) v.setPadding(Ui.dp(this, 16) + bars.left, Ui.dp(this, 12) + bars.top, Ui.dp(this, 16) + bars.right, Ui.dp(this, 12) + bars.bottom);
            else { v.setPadding(bars.left, 0, bars.right, 0); if (hero != null) hero.setPadding(Ui.dp(this, 22), bars.top + Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 64)); }
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
        detachWeb(); root.removeAllViews(); loginVisible = false; root.setPadding(0, 0, 0, 0);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); scroll.addView(page);
        hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setBackgroundColor(Ui.NAVY);
        hero.setPadding(Ui.dp(this, 22), topInset + Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 64));
        ImageView logo = new ImageView(this); logo.setImageResource(R.mipmap.ic_launcher_foreground); logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(Ui.shape(this, android.graphics.Color.WHITE, 16, 0)); logo.setClipToOutline(true);
        hero.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        hero.addView(Ui.text(this, "OZAK Inventory", 23, android.graphics.Color.WHITE, 800), Ui.block(this, 14));
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        hero.addView(Ui.text(this, "Production control · " + version, 13, 0xBFFFFFFF, 500), Ui.block(this, 2));
        page.addView(hero);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 20) + bottomInset);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = -Ui.dp(this, 44); page.addView(content, cp);
        homeCard(content, "🏭", Ui.BLUE_BG, "Production controls", "Sign in, choose an order, check reels and record PCB stages", true,
            () -> startActivity(new android.content.Intent(this, OperatorActivity.class)));
        android.content.Intent scanner = new android.content.Intent().setClassName(this, "org.datamatrix.linereader.InventoryScannerActivity");
        homeCard(content, "⌗", Ui.ORANGE_BG, "Code scanner", "Read a PCB DataMatrix or QR on this phone. Nothing is sent", scanner.resolveActivity(getPackageManager()) != null,
            () -> startActivityForResult(scanner, 81));
        homeCard(content, "⚙", Ui.GREY_BG, "Settings", "Sign-in data on this phone", true, this::showSettings);
        TextView foot = Ui.text(this, "Check every reel of the order first. PCB stages unlock when all reference reels are green; boards are then recorded automatically. Scanning never consumes material or finishes production.", 12, Ui.MUTED, 500);
        foot.setGravity(android.view.Gravity.CENTER); content.addView(foot, Ui.block(this, 20));
    }
    private void homeCard(LinearLayout parent, String glyph, int bg, String title, String sub, boolean enabled, Runnable open) {
        LinearLayout c = Ui.card(this); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(android.view.Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.pressable(Ui.shape(this, Ui.SURFACE, 20, 0))); c.setClickable(enabled); c.setAlpha(enabled ? 1f : 0.5f);
        c.setOnClickListener(v -> open.run()); c.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16));
        c.addView(Ui.icon(this, glyph, bg, 48));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        t.addView(Ui.text(this, title, 15.5f, Ui.TEXT, 700)); t.addView(Ui.text(this, sub, 12.5f, Ui.MUTED, 500), Ui.block(this, 2));
        c.addView(t, Ui.weight(1)); c.addView(Ui.text(this, "›", 24, Ui.FAINT, 500));
        parent.addView(c, Ui.block(this, 12));
    }
    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("Settings")
            .setItems(new String[]{"Clear local login data"}, (d, which) -> new AlertDialog.Builder(this)
                .setMessage("Clear this app's cookies and login data? This does not sign you out on the server.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (x, y) -> clearSession()).show())
            .setNegativeButton("Close", null).show();
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
        root.removeAllViews(); loginVisible = true; root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12) + topInset, Ui.dp(this, 16), Ui.dp(this, 12) + bottomInset);
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
