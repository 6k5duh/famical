package org.famical.calendar;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.webkit.CookieManager;
import android.widget.*;
import java.time.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  private SharedPreferences preferences;
  private long smsPermissionUser;
  private String smsPermissionServer;
  private String server;
  private NativeStore store;
  private NativeCalendar calendar;
  private NativeMonthView grid;
  private TextView title, status;
  private LinearLayout root;
  private YearMonth month = YearMonth.now();
  private String category = "all";
  private boolean mine;
  private boolean collapsed;
  private ValueAnimator collapseAnimator;
  private LocalDate selectedDate = LocalDate.now();
  private LinearLayout agendaPanel;
  private TextView agendaTitle;
  private ListView agendaList;
  private List<NativeMonthView.Entry> selectedEntries = new ArrayList<>();

  private void moveMonth(int delta) {
    YearMonth next = month.plusMonths(delta);
    if (next.getYear() < 1900 || next.getYear() > 2099) return;
    grid.animateMonth(delta);
    month = next;
    selectedDate = month.atDay(Math.min(selectedDate.getDayOfMonth(), month.lengthOfMonth()));
    render();
  }

  private void setCollapsed(boolean value) {
    if (collapsed == value || store.view().optJSONObject("user") == null) return;
    grid.finishMonthAnimation();
    int start = agendaPanel.getVisibility() == View.GONE ? 0 : agendaPanel.getHeight();
    if (collapseAnimator != null) {
      start = agendaPanel.getLayoutParams().height;
      ValueAnimator old = collapseAnimator;
      collapseAnimator = null;
      old.cancel();
    }
    collapsed = value;
    if (!ValueAnimator.areAnimatorsEnabled() || root.getHeight() == 0) {
      settleAgenda();
      render();
      return;
    }
    ValueAnimator animator = ValueAnimator.ofInt(Math.max(0, start), collapsed ? dp(212) : 0);
    collapseAnimator = animator;
    render();
    agendaPanel.getLayoutParams().height = Math.max(0, start);
    agendaPanel.requestLayout();
    animator.setDuration(260);
    animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
    animator.addUpdateListener(
        frame -> {
          agendaPanel.getLayoutParams().height = (int) frame.getAnimatedValue();
          agendaPanel.requestLayout();
        });
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (collapseAnimator == animation) {
              collapseAnimator = null;
              settleAgenda();
            }
          }
        });
    animator.start();
  }

  private void settleAgenda() {
    agendaPanel.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
    agendaPanel.setVisibility(
        collapsed && store.view().optJSONObject("user") != null ? View.VISIBLE : View.GONE);
    agendaPanel.requestLayout();
  }

  private void selectDate(LocalDate date, List<NativeMonthView.Entry> entries) {
    selectedDate = date;
    if (collapsed) renderAgenda();
    else agenda(date, entries);
    grid.selection(selectedDate, collapsed);
  }

  private void renderAgenda() {
    agendaPanel.setVisibility(
        (collapsed || collapseAnimator != null) && store.view().optJSONObject("user") != null
            ? View.VISIBLE
            : View.GONE);
    if (!collapsed) return;
    selectedEntries = grid.entries(selectedDate);
    agendaTitle.setText(
        selectedDate.getMonthValue()
            + "월 "
            + selectedDate.getDayOfMonth()
            + "일 · "
            + selectedEntries.size()
            + "개");
    agendaList.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return Math.max(1, selectedEntries.size());
          }

          public Object getItem(int position) {
            return selectedEntries.isEmpty() ? null : selectedEntries.get(position);
          }

          public long getItemId(int position) {
            return position;
          }

          public boolean isEnabled(int position) {
            return !selectedEntries.isEmpty();
          }

          public View getView(int position, View reusable, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            row.setLayoutParams(new AbsListView.LayoutParams(-1, dp(56)));
            if (selectedEntries.isEmpty()) {
              row.addView(text("등록된 일정이 없습니다.", 14));
              return row;
            }
            NativeMonthView.Entry entry = selectedEntries.get(position);
            View mark = new View(MainActivity.this);
            mark.setBackgroundColor(WidgetEvents.color(entry.event.optString("color")));
            LinearLayout.LayoutParams marker = new LinearLayout.LayoutParams(dp(4), dp(32));
            marker.rightMargin = dp(10);
            row.addView(mark, marker);
            TextView time =
                text(
                    entry.event.optString("clock").isEmpty()
                        ? "종일"
                        : entry.event.optString("clock"),
                    13);
            row.addView(time, new LinearLayout.LayoutParams(dp(48), -2));
            TextView label = text(entry.event.optString("title"), 15);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            String progress = entry.progress(selectedDate);
            if (!progress.isEmpty()) row.addView(text(progress, 13));
            return row;
          }
        });
    agendaList.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (position < selectedEntries.size()) {
            NativeMonthView.Entry entry = selectedEntries.get(position);
            edit(entry.key, entry.event, selectedDate);
          }
        });
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("calendarMonth", month.toString());
    state.putString("selectedDate", selectedDate.toString());
    state.putBoolean("collapsed", collapsed);
    state.putString("category", category);
    state.putBoolean("mine", mine);
    super.onSaveInstanceState(state);
  }

  private final Handler timer = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (store != null) store.sync();
          timer.postDelayed(this, 60000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) {
      month = YearMonth.parse(state.getString("calendarMonth", month.toString()));
      selectedDate = LocalDate.parse(state.getString("selectedDate", selectedDate.toString()));
      collapsed = state.getBoolean("collapsed");
      category = state.getString("category", "all");
      mine = state.getBoolean("mine");
    }
    preferences = getSharedPreferences("famical", MODE_PRIVATE);
    server = preferences.getString("server", "");
    if (server.isEmpty()) {
      server = ServerAddress.DEFAULT_SERVER;
      preferences.edit().putString("server", server).apply();
    }
    calendar = NativeCalendar.get(this);
    store = NativeStore.get(this);
    store.changed = this::render;
    buildScreen();
    if (state == null) selectWidgetDate(getIntent());
    render();
    CalendarSync.schedule(this);
    NativeSyncJob.schedule(this, false);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (selectWidgetDate(intent)) render();
  }

  private boolean selectWidgetDate(Intent intent) {
    android.net.Uri uri = intent == null ? null : intent.getData();
    if (uri == null
        || !Intent.ACTION_VIEW.equals(intent.getAction())
        || !"famical".equals(uri.getScheme())
        || !"calendar".equals(uri.getHost())
        || uri.getPathSegments().size() != 1) return false;
    LocalDate date;
    try {
      date = LocalDate.parse(uri.getLastPathSegment());
    } catch (java.time.format.DateTimeParseException e) {
      return false;
    }
    if (date.getYear() < 1900 || date.getYear() > 2099) return false;
    if (collapseAnimator != null) collapseAnimator.end();
    grid.finishMonthAnimation();
    selectedDate = date;
    month = YearMonth.from(date);
    collapsed = true;
    settleAgenda();
    return true;
  }

  private void buildScreen() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xfff6f5ef);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
          } else
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.addView(
        action(
            "‹",
            () -> {
              moveMonth(-1);
            }));
    title = text("", 17);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(
        action(
            "›",
            () -> {
              moveMonth(1);
            }));
    bar.addView(action("+", () -> edit(null, null, LocalDate.now())));
    bar.addView(action("⚙", this::settings));
    root.addView(bar);
    grid = new NativeMonthView(this, calendar);
    grid.gestures(this::moveMonth, this::setCollapsed);
    root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    agendaPanel = new LinearLayout(this);
    agendaPanel.setOrientation(LinearLayout.VERTICAL);
    agendaPanel.setBackgroundColor(android.graphics.Color.WHITE);
    LinearLayout agendaHeader = new LinearLayout(this);
    agendaHeader.setGravity(Gravity.CENTER_VERTICAL);
    agendaTitle = text("", 15);
    agendaTitle.setPadding(dp(12), 0, 0, 0);
    agendaHeader.addView(agendaTitle, new LinearLayout.LayoutParams(0, -2, 1));
    agendaHeader.addView(action("+", () -> edit(null, null, selectedDate)));
    agendaHeader.addView(action("펼치기", () -> setCollapsed(false)));
    agendaPanel.addView(agendaHeader, new LinearLayout.LayoutParams(-1, dp(44)));
    agendaList = new ListView(this);
    agendaList.setDividerHeight(0);
    agendaPanel.addView(agendaList, new LinearLayout.LayoutParams(-1, dp(168)));
    root.addView(agendaPanel, new LinearLayout.LayoutParams(-1, -2));
    agendaPanel.setVisibility(View.GONE);
    title.setContentDescription("월 제목. 누르면 일정 목록을 펼치거나 접습니다.");
    title.setOnClickListener(v -> setCollapsed(!collapsed));
    status = text("", 11);
    status.setPadding(dp(8), dp(4), dp(8), dp(4));
    status.setOnClickListener(v -> store.sync());
    root.addView(status);
    setContentView(root);
    root.requestApplyInsets();
  }

  private void render() {
    if (isDestroyed()) return;
    JSONObject state = store.view(), user = state.optJSONObject("user");
    title.setText(month.getYear() + "년 " + month.getMonthValue() + "월");
    int count = NativeStore.array(state, "pending").length();
    status.setText(store.status + (count > 0 ? " · 미전송 " + count + "건" : ""));
    if (user == null) {
      grid.setVisibility(View.INVISIBLE);
      agendaPanel.setVisibility(View.GONE);
      status.setText("가족 계정으로 처음 로그인하면 오프라인에서도 사용할 수 있습니다. 여기를 누르세요.");
      status.setOnClickListener(v -> login());
      return;
    }
    grid.setVisibility(View.VISIBLE);
    status.setOnClickListener(v -> store.sync());
    grid.display(month, user, store.items(), category, mine, this::selectDate);
    grid.selection(selectedDate, collapsed);
    renderAgenda();
    cacheWidget();
  }

  private LinearLayout form() {
    LinearLayout p = new LinearLayout(this);
    p.setOrientation(LinearLayout.VERTICAL);
    p.setPadding(dp(18), dp(6), dp(18), dp(12));
    return p;
  }

  private TextView text(String value, int size) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(0xff233a32);
    return v;
  }

  private Button action(String value, Runnable run) {
    Button button = new Button(this);
    button.setText(value);
    button.setAllCaps(false);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(9), 0, dp(9), 0);
    button.setOnClickListener(v -> run.run());
    return button;
  }

  private EditText field(LinearLayout parent, String label, String value, boolean multiline) {
    TextView heading = text(label, 13);
    heading.setPadding(0, dp(10), 0, 0);
    parent.addView(heading);
    EditText input = new EditText(this);
    input.setText(value);
    input.setTextSize(16);
    input.setSingleLine(!multiline);
    if (multiline) input.setMinLines(3);
    parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
    return input;
  }

  private EditText dateField(LinearLayout parent, String label, String value) {
    EditText input = field(parent, label, value, false);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_DATETIME
            | android.text.InputType.TYPE_DATETIME_VARIATION_DATE);
    input.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          public void afterTextChanged(android.text.Editable s) {
            String value = s.toString(), normalized = NativeCalendar.normalize(value);
            if (!value.equals(normalized)) {
              input.setText(normalized);
              input.setSelection(normalized.length());
            }
          }
        });
    return input;
  }

  private Spinner choice(LinearLayout parent, String label, String[] options, int selected) {
    parent.addView(text(label, 13));
    Spinner spinner = new Spinner(this);
    spinner.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
    spinner.setSelection(selected);
    parent.addView(spinner);
    return spinner;
  }

  private AlertDialog dialog(String name, LinearLayout panel) {
    ScrollView scroll = new ScrollView(this);
    scroll.addView(panel);
    return new AlertDialog.Builder(this)
        .setTitle(name)
        .setView(scroll)
        .setNegativeButton("닫기", null)
        .create();
  }

  private void notice(String message) {
    if (!isFinishing())
      new AlertDialog.Builder(this).setMessage(message).setPositiveButton("확인", null).show();
  }

  private void login() {
    LinearLayout panel = form();
    EditText username = field(panel, "아이디", "", false), password = field(panel, "비밀번호", "", false);
    password.setInputType(129);
    TextView error = text("", 13);
    panel.addView(error);
    AlertDialog dialog = dialog("가족 계정 로그인", panel);
    Button button = action("로그인", () -> {});
    panel.addView(button);
    button.setOnClickListener(
        v -> {
          button.setEnabled(false);
          store.login(
              username.getText().toString(),
              password.getText().toString(),
              message -> {
                button.setEnabled(true);
                if (message.isEmpty()) dialog.dismiss();
                else error.setText(message);
              });
        });
    dialog.show();
  }

  private void agenda(LocalDate date, List<NativeMonthView.Entry> entries) {
    if (entries.isEmpty()) {
      edit(null, null, date);
      return;
    }
    String[] names = new String[entries.size() + 1];
    for (int i = 0; i < entries.size(); i++)
      names[i] =
          entries.get(i).event.optString("clock")
              + " "
              + entries.get(i).event.optString("title")
              + entries.get(i).progress(date);
    names[entries.size()] = "+ 일정 추가";
    new AlertDialog.Builder(this)
        .setTitle(date.toString())
        .setItems(
            names,
            (d, n) -> {
              if (n == entries.size()) edit(null, null, date);
              else edit(entries.get(n).key, entries.get(n).event, date);
            })
        .setNegativeButton("닫기", null)
        .show();
  }

  private void edit(String key, JSONObject source, LocalDate date) {
    JSONObject user = store.view().optJSONObject("user");
    if (user == null) {
      login();
      return;
    }
    try {
      if (source != null
          && (source.optInt("sync_readonly") != 0
              || (source.optLong("owner") != user.optLong("id") && user.optInt("admin") == 0))) {
        notice(source.optString("title") + "\n" + source.optString("note"));
        return;
      }
      JSONObject item =
          source == null
              ? new JSONObject()
                  .put("day", date.toString())
                  .put("end_day", date.toString())
                  .put("owner", user.getLong("id"))
                  .put("author", user.optString("name"))
                  .put("category", category.equals("work") ? "work" : "family")
                  .put("visibility", "shared")
              : NativeStore.copy(source);
      LinearLayout panel = form();
      EditText name = field(panel, "제목", item.optString("title"), false);
      EditText start = dateField(panel, "시작일 (YYYY-MM-DD · 숫자 8자리)", item.getString("day"));
      EditText end = dateField(panel, "종료일", item.getString("end_day"));
      EditText time = field(panel, "시간 (HH:mm · 비우면 종일)", item.optString("clock"), false);
      Spinner kind =
          choice(
              panel,
              "날짜 기준",
              new String[] {"양력", "음력"},
              item.optString("calendar_type").equals("lunar") ? 1 : 0);
      CheckBox annual = new CheckBox(this);
      annual.setText("매년 반복");
      annual.setChecked(item.optBoolean("annual"));
      panel.addView(annual);
      CheckBox leap = new CheckBox(this);
      leap.setText("윤달");
      leap.setChecked(item.optBoolean("leap"));
      panel.addView(leap);
      Spinner type =
          choice(
              panel,
              "일정 구분",
              new String[] {"가족", "회사"},
              item.optString("category").equals("work") ? 1 : 0);
      Spinner visibility =
          choice(
              panel,
              "공개 범위",
              new String[] {"모두에게 보이기", "나에게만 보이기"},
              item.optString("visibility").equals("private") ? 1 : 0);
      String[] colors = WidgetEvents.COLOR_IDS;
      Spinner color =
          choice(
              panel,
              "색상",
              WidgetEvents.COLOR_LABELS,
              Math.max(0, Arrays.asList(colors).indexOf(item.optString("color"))));
      color.setAdapter(
          new ArrayAdapter<String>(
              this, android.R.layout.simple_spinner_dropdown_item, WidgetEvents.COLOR_LABELS) {
            private View swatch(View view, int position) {
              TextView label = (TextView) view;
              android.graphics.drawable.GradientDrawable square =
                  new android.graphics.drawable.GradientDrawable();
              square.setColor(WidgetEvents.color(colors[position]));
              square.setCornerRadius(dp(3));
              square.setBounds(0, 0, dp(18), dp(18));
              label.setCompoundDrawables(square, null, null, null);
              label.setCompoundDrawablePadding(dp(8));
              return label;
            }

            @Override
            public View getView(int position, View reusable, ViewGroup parent) {
              return swatch(super.getView(position, reusable, parent), position);
            }

            @Override
            public View getDropDownView(int position, View reusable, ViewGroup parent) {
              return swatch(super.getDropDownView(position, reusable, parent), position);
            }
          });
      color.setSelection(Math.max(0, Arrays.asList(colors).indexOf(item.optString("color"))));
      EditText note = field(panel, "메모", item.optString("note"), true);
      TextView error = text("", 13);
      panel.addView(error);
      AlertDialog dialog = dialog("일정", panel);
      panel.addView(
          action(
              "저장",
              () -> {
                try {
                  item.put("title", name.getText().toString())
                      .put("day", start.getText().toString())
                      .put("end_day", end.getText().toString())
                      .put("clock", time.getText().toString().trim())
                      .put("calendar_type", kind.getSelectedItemPosition() == 1 ? "lunar" : "solar")
                      .put("annual", annual.isChecked())
                      .put("leap", leap.isChecked())
                      .put("category", type.getSelectedItemPosition() == 1 ? "work" : "family")
                      .put(
                          "visibility",
                          visibility.getSelectedItemPosition() == 1 ? "private" : "shared")
                      .put("color", colors[color.getSelectedItemPosition()])
                      .put("note", note.getText().toString());
                  calendar.validate(item);
                  store.save(key, item, false);
                  dialog.dismiss();
                  store.sync();
                } catch (Exception e) {
                  error.setText(e.getMessage());
                }
              }));
      if (source != null)
        panel.addView(
            action(
                "삭제",
                () ->
                    new AlertDialog.Builder(this)
                        .setMessage("이 일정 전체를 삭제할까요?")
                        .setPositiveButton(
                            "삭제",
                            (d, n) -> {
                              try {
                                store.save(key, item, true);
                                dialog.dismiss();
                                store.sync();
                              } catch (Exception e) {
                                error.setText(e.getMessage());
                              }
                            })
                        .setNegativeButton("취소", null)
                        .show()));
      dialog.show();
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  private void settings() {
    JSONObject state = store.view(), user = state.optJSONObject("user");
    LinearLayout panel = form();
    AlertDialog dialog = dialog("설정 · Android v2.2.1", panel);
    if (user != null) {
      panel.addView(text(user.optString("name") + " 님", 18));
      boolean smsPermission =
          checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
      boolean smsOn = state.optBoolean("smsEnabled") && smsPermission;
      panel.addView(text("휴가 문자 자동 등록 · " + (smsOn ? "켜짐" : "꺼짐"), 14));
      panel.addView(
          text("052-219-5000의 [휴가승인 요청] 문자를 회사·나에게만 공개 일정으로 등록합니다. 승인 완료를 뜻하지 않습니다.", 12));
      if (!state.optString("smsLastImport").isEmpty())
        panel.addView(text("최근 문자 등록 " + state.optString("smsLastImport"), 12));
      panel.addView(
          action(
              smsOn ? "문자 자동 등록 끄기" : "문자 자동 등록 켜기",
              () -> {
                dialog.dismiss();
                if (smsOn) {
                  setLeaveSmsEnabled(false);
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("휴가 문자 자동 등록")
                    .setMessage(
                        "지금부터 수신하는 지정 번호의 휴가 요청 SMS를 읽어 일정에 등록합니다. 문자 원문은 서버에 보내지 않으며 이름·휴가 날짜로 만든"
                            + " 일정만 동기화합니다. 기존 문자는 가져오지 않습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton(
                        "켜기",
                        (d, which) -> {
                          if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
                              == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            setLeaveSmsEnabled(true);
                          else {
                            smsPermissionUser = user.optLong("id");
                            smsPermissionServer = server;
                            requestPermissions(
                                new String[] {android.Manifest.permission.RECEIVE_SMS}, 52);
                          }
                        })
                    .show();
              }));
      Spinner filter =
          choice(
              panel,
              "일정 구분",
              new String[] {"전체", "가족", "회사"},
              category.equals("all") ? 0 : category.equals("family") ? 1 : 2);
      Spinner owner = choice(panel, "일정 보기", new String[] {"전체 일정", "내 일정"}, mine ? 1 : 0);
      int team =
          user.optInt("shift_on") == 0
              ? 3
              : user.optString("shift_team").equals("B")
                  ? 1
                  : user.optString("shift_team").equals("none") ? 2 : 0;
      Spinner shift =
          choice(panel, "교대조", new String[] {"A조", "B조", "해당 없음 (주간)", "교대 표시 끄기"}, team);
      panel.addView(
          action(
              "적용",
              () -> {
                try {
                  category =
                      new String[] {"all", "family", "work"}[filter.getSelectedItemPosition()];
                  mine = owner.getSelectedItemPosition() == 1;
                  store.preference(
                      new String[] {"A", "B", "none", "none"}[shift.getSelectedItemPosition()],
                      shift.getSelectedItemPosition() != 3);
                  dialog.dismiss();
                  store.sync();
                } catch (Exception e) {
                  notice(e.getMessage());
                }
              }));
      panel.addView(
          action(
              "오늘",
              () -> {
                month = YearMonth.now();
                selectedDate = LocalDate.now();
                dialog.dismiss();
                render();
              }));
      panel.addView(
          action(
              "지금 동기화",
              () -> {
                store.sync();
                dialog.dismiss();
              }));
      panel.addView(
          action(
              "같은 계정으로 재로그인",
              () -> {
                dialog.dismiss();
                login();
              }));
      JSONArray pending = NativeStore.array(state, "pending");
      for (int i = 0; i < pending.length(); i++) {
        JSONObject p = pending.optJSONObject(i);
        if (!p.optBoolean("conflict") && p.optString("error").isEmpty()) continue;
        panel.addView(
            text(p.optJSONObject("event").optString("title") + " · " + p.optString("error"), 13));
        if (p.optBoolean("conflict")) {
          JSONObject local = p.optJSONObject("event"), remote = p.optJSONObject("current");
          panel.addView(
              text(
                  "내 일정 · "
                      + local.optString("title")
                      + " · "
                      + local.optString("day")
                      + " ~ "
                      + local.optString("end_day")
                      + " · "
                      + local.optString("note"),
                  13));
          panel.addView(
              text(
                  remote == null
                      ? "서버에서 삭제됨"
                      : "서버 일정 · "
                          + remote.optString("title")
                          + " · "
                          + remote.optString("day")
                          + " ~ "
                          + remote.optString("end_day")
                          + " · "
                          + remote.optString("note"),
                  13));
        }
        if (p.optBoolean("conflict"))
          panel.addView(
              action(
                  "내 변경 적용",
                  () -> {
                    try {
                      store.resolve(p.optString("key"), true);
                      dialog.dismiss();
                      store.sync();
                    } catch (Exception e) {
                      notice(e.getMessage());
                    }
                  }));
        panel.addView(
            action(
                "서버 일정 사용 (내 변경 취소)",
                () -> {
                  try {
                    store.resolve(p.optString("key"), false);
                    dialog.dismiss();
                    store.sync();
                  } catch (Exception e) {
                    notice(e.getMessage());
                  }
                }));
      }
      panel.addView(
          action(
              "삼성·휴대전화 캘린더 연동",
              () -> {
                dialog.dismiss();
                showCalendarSync();
              }));
      panel.addView(
          action(
              "로그아웃",
              () ->
                  store.logout(
                      message -> {
                        if (!message.isEmpty()) notice(message);
                        else dialog.dismiss();
                      })));
    } else
      panel.addView(
          action(
              "로그인",
              () -> {
                dialog.dismiss();
                login();
              }));
    CheckBox privateEvents = new CheckBox(this);
    privateEvents.setText("위젯에 나의 개인 일정 표시");
    privateEvents.setChecked(preferences.getBoolean("widget_private", false));
    privateEvents.setOnCheckedChangeListener(
        (b, on) -> {
          preferences.edit().putBoolean("widget_private", on).apply();
          cacheWidget();
        });
    panel.addView(privateEvents);
    EditText address = field(panel, "서버 주소", server, false);
    panel.addView(
        action(
            "서버 변경",
            () -> {
              try {
                String next = ServerAddress.normalize(address.getText().toString());
                if (next.equals(server)) {
                  dialog.dismiss();
                  return;
                }
                store.logout(
                    message -> {
                      if (!message.isEmpty()) {
                        notice(message);
                        return;
                      }
                      preferences.edit().putString("server", next).apply();
                      dialog.dismiss();
                      recreate();
                    });
              } catch (Exception e) {
                notice(e.getMessage());
              }
            }));
    dialog.show();
  }

  private void cacheWidget() {
    store.updateWidget();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onResume() {
    super.onResume();
    timer.post(refresh);
    if (store != null && preferences.getBoolean("sync_enabled", false)) {
      String cookie = CookieManager.getInstance().getCookie(server);
      if (cookie != null) CalendarSync.submit(this, cookie, "", null);
    }
  }

  @Override
  protected void onPause() {
    if (collapseAnimator != null) collapseAnimator.end();
    if (grid != null) grid.finishMonthAnimation();
    timer.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (collapseAnimator != null) collapseAnimator.end();
    if (grid != null) grid.finishMonthAnimation();
    if (store != null) store.changed = () -> {};
    super.onDestroy();
  }

  private void showCalendarSync() {
    if (server.isEmpty()) {
      notice("먼저 서버에 연결하고 가족 계정으로 로그인해 주세요.");
      return;
    }
    String cookie = CookieManager.getInstance().getCookie(server);
    if (cookie == null) {
      notice("먼저 가족 계정으로 로그인해 주세요.");
      return;
    }
    if (preferences.getBoolean("sync_enabled", false)
        || !preferences.getString("sync_connection", "").isEmpty()) {
      new AlertDialog.Builder(this)
          .setTitle("캘린더 연동")
          .setView(
              text(
                  preferences.getString("sync_label", "")
                      + "\n"
                      + preferences.getString("sync_status", "")
                      + "\n\n일반 일정은 양방향으로 반영합니다. 충돌은 기본적으로 보류합니다. 반복·음력 보기용 일정은 원본에서 수정하세요.",
                  14))
          .setItems(
              new String[] {
                "지금 동기화",
                "동시에 수정된 일반 일정: 휴대전화 우선",
                "동시에 수정된 일반 일정: 가족 달력 우선",
                "연동 해제 (기존 일정 보관)",
                "연동 연결·재개"
              },
              (d, which) -> {
                if (which == 3) {
                  CalendarSync.disconnect(this, cookie, this::syncNotice);
                  return;
                }
                if (which == 4) {
                  selectCalendar();
                  return;
                }
                if (!preferences.getBoolean("sync_enabled", false)) {
                  syncNotice("연동 연결·재개를 선택해 주세요.");
                  return;
                }
                CalendarSync.submit(
                    this,
                    cookie,
                    which == 1 ? "native" : which == 2 ? "server" : "",
                    this::syncNotice);
              })
          .setNegativeButton("닫기", null)
          .show();
    } else selectCalendar();
  }

  private void selectCalendar() {
    if (!CalendarSync.permitted(this)) {
      requestPermissions(
          new String[] {
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR
          },
          41);
      return;
    }
    try {
      java.util.List<DeviceCalendars.Choice> calendars =
          DeviceCalendars.choices(getContentResolver());
      if (calendars.isEmpty()) {
        syncNotice("읽기·쓰기가 가능한 캘린더가 없습니다. 삼성 캘린더의 계정 동기화를 확인해 주세요.");
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("연동할 캘린더 선택")
          .setItems(
              calendars.stream().map(Object::toString).toArray(String[]::new),
              (dialog, which) -> {
                DeviceCalendars.Choice choice = calendars.get(which);
                final String[] visibility = {"private"};
                LinearLayout details = form();
                details.addView(
                    text(
                        "선택한 캘린더와 본인의 가족 달력 일정 사이에 추가·수정·삭제를 반영합니다. 다른 가족의 일정은 복사하지 않습니다.\n\n"
                            + "지난달부터 앞으로 약 12개월을 연결합니다. 반복·음력 원본 규칙은 원래 앱에서 수정하세요. 1시간 갱신은 절전 정책에"
                            + " 따라 지연될 수 있습니다.",
                        14));
                android.widget.CheckBox exportPrivate = new android.widget.CheckBox(this);
                exportPrivate.setText("새 개인 일정도 휴대전화로 내보내기");
                exportPrivate.setChecked(false);
                details.addView(exportPrivate);
                details.addView(
                    text("내보낸 일정은 선택한 캘린더의 공유 권한을 따릅니다. 이미 연결한 일정은 공개 범위를 바꿔도 연결을 유지합니다.", 12));
                new AlertDialog.Builder(this)
                    .setTitle("양방향 연동 설정")
                    .setView(details)
                    .setSingleChoiceItems(
                        new String[] {"가져오는 일정: 나에게만 보이기", "가져오는 일정: 모두에게 보이기"},
                        0,
                        (d, index) -> visibility[0] = index == 0 ? "private" : "shared")
                    .setPositiveButton(
                        "양방향 연동 시작",
                        (d, w) ->
                            CalendarSync.connect(
                                this,
                                choice,
                                visibility[0],
                                exportPrivate.isChecked(),
                                CookieManager.getInstance().getCookie(server),
                                this::syncNotice))
                    .setNegativeButton("취소", null)
                    .show();
              })
          .setNegativeButton("취소", null)
          .show();
    } catch (Exception e) {
      syncNotice("캘린더 목록을 읽지 못했습니다. 권한을 확인해 주세요.");
    }
  }

  private void syncNotice(String message) {
    notice(message);
    store.sync();
  }

  private void setLeaveSmsEnabled(boolean enabled) {
    try {
      store.enableLeaveSms(enabled);
      notice(enabled ? "휴가 문자 자동 등록을 켰습니다. 이후 수신하는 SMS부터 적용됩니다." : "휴가 문자 자동 등록을 껐습니다.");
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 52) {
      JSONObject user = store.view().optJSONObject("user");
      if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
              == android.content.pm.PackageManager.PERMISSION_GRANTED
          && user != null
          && user.optLong("id") == smsPermissionUser
          && server.equals(smsPermissionServer)) setLeaveSmsEnabled(true);
      else
        notice(
            "SMS 권한이 허용되지 않아 자동 등록을 켜지 않았습니다. 휴대전화 설정의 앱 권한을 확인하세요. 기기·설치 방식에 따라 SMS 권한이 제한될 수"
                + " 있습니다.");
      smsPermissionUser = 0;
      smsPermissionServer = null;
    }
    if (request == 41) {
      if (CalendarSync.permitted(this)) selectCalendar();
      else syncNotice("캘린더 권한을 허용해야 연동할 수 있습니다. 가족 달력은 계속 사용할 수 있습니다.");
    }
  }
}
