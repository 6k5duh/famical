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
        CalendarSync.schedule(this);
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
        ws.setUserAgentString(ws.getUserAgentString()+" FamicalAndroid/1.2.0");
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
                boolean changed=!next.equals(server);
                if(changed){preferences.edit().putBoolean("sync_enabled",false).apply();CalendarSync.schedule(this);}
                server=next;
                preferences.edit().putString("server",server).apply();
                if(changed) {
                    WebStorage.getInstance().deleteAllData(); browser.clearHistory(); browser.clearCache(true);
                    CookieManager.getInstance().removeAllCookies(done->{ loadServer();CalendarWidget.refreshAll(this); });
                } else loadServer();
            } catch(IllegalArgumentException e) { addressError.setText(e.getMessage()); }
        });
        TextView note=text("로그인은 연결 후 가족 계정으로 진행합니다.\n이 앱은 NAS 서버와 인터넷 연결이 필요합니다.\n\nAndroid 앱 v1.2.0 · NAS 웹앱과 버전은 별도입니다.",13); form.addView(note);
        android.widget.CheckBox privateEvents=new android.widget.CheckBox(this);
        privateEvents.setText("위젯에 나의 개인 일정 표시");
        privateEvents.setChecked(preferences.getBoolean("widget_private",false));
        privateEvents.setOnCheckedChangeListener((v,checked)->{preferences.edit().putBoolean("widget_private",checked).remove("widget_lines").remove("widget_day").remove("widget_month_events").remove("widget_month").apply();CalendarWidget.refreshAll(this);});
        form.addView(privateEvents);
        Button calendarSync=button("삼성·휴대전화 캘린더 연동");form.addView(calendarSync);calendarSync.setOnClickListener(v->showCalendarSync());
        TextView widgetNote=text("홈 화면을 길게 누른 뒤 위젯 → 우리 가족 달력을 추가하세요. 위젯은 월 달력·교대조·오늘 일정 최대 3개를 표시합니다. 갱신은 기기 정책에 따라 지연될 수 있습니다.",13);form.addView(widgetNote);
        settingsPanel.addView(form); settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void showCalendarSync() {
        if(server.isEmpty()){addressError.setText("먼저 서버에 연결하고 가족 계정으로 로그인해 주세요.");return;}
        String cookie=CookieManager.getInstance().getCookie(server);
        if(cookie==null){addressError.setText("먼저 가족 계정으로 로그인해 주세요.");return;}
        if(preferences.getBoolean("sync_enabled",false)||!preferences.getString("sync_connection","").isEmpty()) {
            new AlertDialog.Builder(this).setTitle("캘린더 연동")
                .setView(text(preferences.getString("sync_label","")+"\n"+preferences.getString("sync_status","")+"\n\n일반 일정은 양방향으로 반영합니다. 충돌은 기본적으로 보류합니다. 반복·음력 보기용 일정은 원본에서 수정하세요.",14))
                .setItems(new String[]{"지금 동기화","동시에 수정된 일반 일정: 휴대전화 우선","동시에 수정된 일반 일정: 가족 달력 우선","연동 해제 (기존 일정 보관)","연동 연결·재개"},(d,which)->{
                    if(which==3){CalendarSync.disconnect(this,cookie,this::syncNotice);return;}
                    if(which==4){selectCalendar();return;}
                    if(!preferences.getBoolean("sync_enabled",false)){syncNotice("연동 연결·재개를 선택해 주세요.");return;}
                    CalendarSync.submit(this,cookie,which==1?"native":which==2?"server":"",this::syncNotice);
                }).setNegativeButton("닫기",null).show();
        } else selectCalendar();
    }
    private void selectCalendar() {
        if(!CalendarSync.permitted(this)) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR,android.Manifest.permission.WRITE_CALENDAR},41);return;
        }
        try {
            java.util.List<DeviceCalendars.Choice> calendars=DeviceCalendars.choices(getContentResolver());
            if(calendars.isEmpty()){syncNotice("읽기·쓰기가 가능한 캘린더가 없습니다. 삼성 캘린더의 계정 동기화를 확인해 주세요.");return;}
            new AlertDialog.Builder(this).setTitle("연동할 캘린더 선택")
                .setItems(calendars.stream().map(Object::toString).toArray(String[]::new),(dialog,which)->{
                    DeviceCalendars.Choice choice=calendars.get(which);final String[] visibility={"private"};
                    LinearLayout details=panel();
                    details.addView(text("선택한 캘린더와 본인의 가족 달력 일정 사이에 추가·수정·삭제를 반영합니다. 다른 가족의 일정은 복사하지 않습니다.\n\n지난달부터 앞으로 약 12개월을 연결합니다. 반복·음력 원본 규칙은 원래 앱에서 수정하세요. 1시간 갱신은 절전 정책에 따라 지연될 수 있습니다.",14));
                    android.widget.CheckBox exportPrivate=new android.widget.CheckBox(this);
                    exportPrivate.setText("새 개인 일정도 휴대전화로 내보내기");exportPrivate.setChecked(false);details.addView(exportPrivate);
                    details.addView(text("내보낸 일정은 선택한 캘린더의 공유 권한을 따릅니다. 이미 연결한 일정은 공개 범위를 바꿔도 연결을 유지합니다.",12));
                    new AlertDialog.Builder(this).setTitle("양방향 연동 설정")
                        .setView(details)
                        .setSingleChoiceItems(new String[]{"가져오는 일정: 나에게만 보이기","가져오는 일정: 모두에게 보이기"},0,(d,index)->visibility[0]=index==0?"private":"shared")
                        .setPositiveButton("양방향 연동 시작",(d,w)->CalendarSync.connect(this,choice,visibility[0],exportPrivate.isChecked(),CookieManager.getInstance().getCookie(server),this::syncNotice))
                        .setNegativeButton("취소",null).show();
                }).setNegativeButton("취소",null).show();
        }catch(Exception e){syncNotice("캘린더 목록을 읽지 못했습니다. 권한을 확인해 주세요.");}
    }
    private void syncNotice(String message) {
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle("캘린더 연동").setMessage(message).setPositiveButton("확인",(d,w)->{if(content.getVisibility()==View.VISIBLE)loadServer();}).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==41){if(CalendarSync.permitted(this))selectCalendar();else syncNotice("캘린더 권한을 허용해야 연동할 수 있습니다. 가족 달력은 계속 사용할 수 있습니다.");}
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
    @Override protected void onPause() {
        browser.evaluateJavascript("localStorage.getItem('familyCalendar.shift')", raw -> {
            try {
                Object value=new org.json.JSONTokener(raw).nextValue();
                if(value instanceof String) {
                    org.json.JSONObject settings=new org.json.JSONObject((String)value);
                    String team=settings.optString("team","A");
                    preferences.edit().putString("widget_team","none".equals(team)?"none":"B".equals(team)?"B":"A").putBoolean("widget_shift",settings.optBoolean("on",true)).apply();
                }
            } catch(org.json.JSONException ignored) {}
            CookieManager.getInstance().flush();CalendarWidget.refreshAll(this);
        });
        super.onPause(); browser.onPause(); CookieManager.getInstance().flush();CalendarWidget.refreshAll(this);
    }
    @Override protected void onResume() {
        super.onResume(); if(browser!=null) browser.onResume();
        if(preferences!=null&&preferences.getBoolean("sync_enabled",false)) {
            CalendarSync.schedule(this);
            String cookie=CookieManager.getInstance().getCookie(server);
            if(cookie!=null)CalendarSync.submit(this,cookie,"",null);
        }
    }
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
