package org.famical.calendar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

public class MainActivity extends Activity {
    private static final int BRAND = Color.rgb(40,84,73);
    private SharedPreferences preferences;
    private WebView browser;
    private LinearLayout root, errorPanel;
    private FrameLayout content;
    private ScrollView settingsPanel;
    private ProgressBar progress;
    private EditText serverInput;
    private TextView addressError, failureText;
    private String server = "";
    private boolean failed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("famical", MODE_PRIVATE);
        server = preferences.getString("server", "");
        buildScreen();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        if (server.isEmpty()) showSettings(); else loadServer();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildScreen() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246,245,239));
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(i.left,i.top,i.right,i.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12),dp(4),dp(8),dp(4)); toolbar.setBackgroundColor(BRAND);
        TextView title = text("우리 가족 달력",18); title.setTextColor(Color.WHITE);
        toolbar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1)); title.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = button("새로고침"); refresh.setOnClickListener(v -> { if(!server.isEmpty()) loadServer(); });
        Button settings = button("서버"); settings.setOnClickListener(v -> showSettings());
        toolbar.addView(refresh); toolbar.addView(settings); root.addView(toolbar);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        content = new FrameLayout(this); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        browser = new WebView(this);
        WebSettings ws = browser.getSettings();
        ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false); ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setJavaScriptCanOpenWindowsAutomatically(false); ws.setSupportMultipleWindows(false);
        ws.setUserAgentString(ws.getUserAgentString()+" FamicalAndroid/1.0.0");
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser,false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String target=request.getUrl().toString();
                if (ServerAddress.sameOrigin(server,target)) return false;
                if (request.hasGesture() && "https".equalsIgnoreCase(request.getUrl().getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,request.getUrl())); }
                    catch (android.content.ActivityNotFoundException e) { showFailure("링크를 열 브라우저가 없습니다."); }
                } else showFailure("설정한 서버와 다른 주소로 이동했습니다. NAS 주소와 역방향 프록시 설정을 확인해 주세요.");
                return true;
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon) {
                failed=false; progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view,String url) {
                progress.setVisibility(View.GONE); CookieManager.getInstance().flush();
                if(!failed) { errorPanel.setVisibility(View.GONE); browser.setVisibility(View.VISIBLE); }
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {
                if(request.isForMainFrame()) showFailure("서버에 연결할 수 없습니다. 인터넷, NAS 실행 상태와 HTTPS 주소를 확인해 주세요.");
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response) {
                if(request.isForMainFrame()) showFailure("서버 응답 오류 (HTTP "+response.getStatusCode()+"). NAS 역방향 프록시와 컨테이너를 확인해 주세요.");
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error) {
                handler.cancel(); showFailure("HTTPS 인증서를 확인할 수 없습니다. 도메인과 일치하는 유효한 인증서를 NAS에 설정해 주세요.");
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int value) { progress.setProgress(value); }
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setTitle("가족 달력").setMessage(message)
                    .setPositiveButton("확인",(d,w)->result.confirm()).setNegativeButton("취소",(d,w)->result.cancel())
                    .setOnCancelListener(d->result.cancel()).show(); return true;
            }
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setTitle("가족 달력").setMessage(message)
                    .setPositiveButton("확인",(d,w)->result.confirm()).setOnCancelListener(d->result.confirm()).show(); return true;
            }
        });
        content.addView(browser,new FrameLayout.LayoutParams(-1,-1));
        errorPanel = panel(); errorPanel.setGravity(Gravity.CENTER);
        failureText=text("",16); errorPanel.addView(failureText);
        Button retry=button("다시 연결"); retry.setOnClickListener(v->loadServer()); errorPanel.addView(retry);
        Button change=button("서버 주소 설정"); change.setOnClickListener(v->showSettings()); errorPanel.addView(change);
        errorPanel.setVisibility(View.GONE); content.addView(errorPanel,new FrameLayout.LayoutParams(-1,-1));
        buildSettings(); setContentView(root); root.requestApplyInsets();
    }

    private void buildSettings() {
        settingsPanel=new ScrollView(this); LinearLayout form=panel();
        TextView heading=text("우리 가족 NAS 연결",25); form.addView(heading);
        TextView detail=text("캘린더가 열리는 HTTPS 주소를 입력하세요.\n가족 모두 같은 주소를 사용하면 일정이 함께 공유됩니다.",15); form.addView(detail);
        TextView label=text("서버 주소",14); form.addView(label);
        serverInput=new EditText(this); serverInput.setSingleLine(true);
        serverInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setHint("https://calendar.example.com"); serverInput.setTextSize(16); form.addView(serverInput,new LinearLayout.LayoutParams(-1,-2));
        addressError=text("",14); addressError.setTextColor(Color.rgb(166,60,53)); form.addView(addressError);
        Button connect=button("연결하기"); form.addView(connect);
        connect.setOnClickListener(v->{
            try {
                String next=ServerAddress.normalize(serverInput.getText().toString());
                boolean changed=!next.equals(server); server=next;
                preferences.edit().putString("server",server).apply();
                if(changed) {
                    WebStorage.getInstance().deleteAllData(); browser.clearHistory(); browser.clearCache(true);
                    CookieManager.getInstance().removeAllCookies(done->loadServer());
                } else loadServer();
            } catch(IllegalArgumentException e) { addressError.setText(e.getMessage()); }
        });
        TextView note=text("로그인은 연결 후 가족 계정으로 진행합니다.\n이 앱은 NAS 서버와 인터넷 연결이 필요합니다.\n\nAndroid 앱 v1.0.0 · NAS 웹앱과 버전은 별도입니다.",13); form.addView(note);
        settingsPanel.addView(form); settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void showSettings() {
        browser.stopLoading(); progress.setVisibility(View.GONE); content.setVisibility(View.GONE);
        serverInput.setText(server); addressError.setText(""); settingsPanel.setVisibility(View.VISIBLE);
    }
    private void loadServer() {
        if(server.isEmpty()) { showSettings(); return; }
        try { server=ServerAddress.normalize(server); }
        catch(IllegalArgumentException e) { showSettings();addressError.setText(e.getMessage());return; }
        settingsPanel.setVisibility(View.GONE); content.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE); browser.setVisibility(View.VISIBLE); failed=false;
        browser.loadUrl(server);
    }
    private void showFailure(String message) {
        failed=true;progress.setVisibility(View.GONE);browser.setVisibility(View.GONE);
        failureText.setText(message);errorPanel.setVisibility(View.VISIBLE);
    }
    private void handleBack() {
        if(settingsPanel.getVisibility()==View.VISIBLE && !server.isEmpty()) loadServer();
        else if(browser.getVisibility()==View.VISIBLE && browser.canGoBack()) browser.goBack();
        else finish();
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { handleBack(); }
    @Override protected void onPause() { super.onPause(); browser.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onResume() { super.onResume(); if(browser!=null) browser.onResume(); }
    @Override protected void onDestroy() {
        if(browser!=null) { content.removeView(browser);browser.destroy(); }
        super.onDestroy();
    }
    private LinearLayout panel() {
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(24),dp(24),dp(24),dp(24));p.setBackgroundColor(Color.rgb(246,245,239));return p;
    }
    private TextView text(String value,int size) {
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(BRAND);
        v.setPadding(0,dp(8),0,dp(8));return v;
    }
    private Button button(String value) { Button b=new Button(this);b.setText(value);b.setAllCaps(false);return b; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
