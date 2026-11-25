using System;
using System.Linq;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace Famical.Windows
{
    internal sealed class CalendarView : DockPanel
    {
        private readonly NativeStore store;
        private readonly Almanac almanac = new Almanac();
        private readonly Grid days = new Grid();
        private readonly TextBlock title = new TextBlock { VerticalAlignment = VerticalAlignment.Center, FontSize = 17 };
        private readonly TextBlock status = new TextBlock { FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis };
        private DateTime month = new DateTime(DateTime.Today.Year, DateTime.Today.Month, 1);
        private string category = "all";
        private bool mine;
        private readonly DispatcherTimer resizeTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        internal CalendarView(NativeStore store)
        {
            this.store = store;
            var bar = new DockPanel();
            var actions = new StackPanel { Orientation = Orientation.Horizontal };
            actions.Children.Add(Button("+ 일정", () => Edit(null, null, DateTime.Today)));
            actions.Children.Add(Button("설정", Settings));
            SetDock(actions, Dock.Right);
            bar.Children.Add(actions);
            var navigation = new StackPanel { Orientation = Orientation.Horizontal };
            navigation.Children.Add(Button("‹", () => { month = month.AddMonths(-1); Render(); }));
            navigation.Children.Add(title);
            navigation.Children.Add(Button("›", () => { month = month.AddMonths(1); Render(); }));
            navigation.Children.Add(Button("오늘", () => { month = new DateTime(DateTime.Today.Year, DateTime.Today.Month, 1); Render(); }));
            bar.Children.Add(navigation);
            SetDock(bar, Dock.Top);
            Children.Add(bar);
            SetDock(status, Dock.Bottom);
            Children.Add(status);
            var weekdays = new System.Windows.Controls.Primitives.UniformGrid { Columns = 7 };
            foreach (string name in new[] { "일", "월", "화", "수", "목", "금", "토" })
                weekdays.Children.Add(new TextBlock { Text = name, TextAlignment = TextAlignment.Center, Margin = new Thickness(2) });
            SetDock(weekdays, Dock.Top);
            Children.Add(weekdays);
            Children.Add(days);
            store.Changed += () => Dispatcher.BeginInvoke(new Action(Render));
            resizeTimer.Tick += (_, __) => { resizeTimer.Stop(); Render(); };
            SizeChanged += (_, __) => { resizeTimer.Stop(); resizeTimer.Start(); };
            Render();
        }
        internal static Button Button(string label, Action click)
        {
            var button = new Button { Content = label, Margin = new Thickness(2), Padding = new Thickness(6, 3, 6, 3), Background = Brush("#24516a"), Foreground = Brushes.White, BorderThickness = new Thickness(0) };
            button.Click += (_, __) => click();
            return button;
        }
        internal static SolidColorBrush Brush(string value) => (SolidColorBrush)new BrushConverter().ConvertFromString(value);
        private string Shift(DateTime day, NativeUser user)
        {
            if (user == null || user.shift_on == 0 || !Almanac.Weekday(day))
                return "";
            if (user.shift_team == "none")
                return "주간";
            long week = (long)Math.Floor((day - new DateTime(2026, 11, 16)).TotalDays / 7);
            bool night = (week % 2 + 2) % 2 == 0;
            if (user.shift_team == "B")
                night = !night;
            return night ? "야간" : "주간";
        }
        private void Render()
        {
            var view = store.View();
            status.Text = store.Status + (view.pending.Count > 0 ? $" · 미전송 {view.pending.Count}건" : "");
            status.ToolTip = status.Text;
            title.Text = month.ToString("yyyy년 M월");
            days.Children.Clear();
            days.RowDefinitions.Clear();
            days.ColumnDefinitions.Clear();
            if (view.user == null)
            {
                var login = Button("가족 계정 로그인", Login);
                login.HorizontalAlignment = HorizontalAlignment.Center;
                login.VerticalAlignment = VerticalAlignment.Center;
                days.Children.Add(login);
                return;
            }
            var first = month.AddDays(-(int)month.DayOfWeek);
            int count = ((int)month.DayOfWeek + DateTime.DaysInMonth(month.Year, month.Month) + 6) / 7 * 7;
            for (int i = 0; i < 7; i++)
                days.ColumnDefinitions.Add(new ColumnDefinition());
            for (int i = 0; i < count / 7; i++)
                days.RowDefinitions.Add(new RowDefinition());
            var occurrences = new List<Tuple<string, NativeEvent, DateTime, DateTime>>();
            foreach (var item in store.Items(view))
            {
                if ((mine && item.Value.owner != view.user.id) || (category != "all" && item.Value.category != category))
                    continue;
                foreach (var span in almanac.Spans(item.Value, month.Year))
                    if (span.Item1 <= first.AddDays(count - 1) && span.Item2 >= first)
                        occurrences.Add(Tuple.Create(item.Key, item.Value, span.Item1, span.Item2));
            }
            int limit = Math.Max(0, (int)((days.ActualHeight / (count / 7) - 45) / 23) - 1);
            for (int i = 0; i < count; i++)
            {
                var date = first.AddDays(i);
                bool outside = date.Month != month.Month;
                string shift = Shift(date, view.user), holiday = almanac.Holiday(date);
                var panel = new StackPanel { Margin = new Thickness(3) };
                var border = new Border
                {
                    Child = panel,
                    BorderBrush = Brush(date == DateTime.Today ? "#e65353" : "#476079"),
                    BorderThickness = new Thickness(date == DateTime.Today ? 2 : .5),
                    ClipToBounds = true,
                    Background = Brush(!Almanac.Weekday(date) || holiday != "" ? "#392d39" : shift == "야간" ? "#293350" : shift == "주간" ? "#18404f" : "#16344a"),
                    Opacity = outside ? .55 : 1
                };
                border.MouseLeftButtonDown += (_, e) =>
                {
                    if (e.ClickCount != 2)
                        return;
                    // Buttons keep their existing date/agenda/edit actions.
                    var target = e.OriginalSource as DependencyObject;
                    while (target != null && target != border)
                    {
                        if (target is System.Windows.Controls.Primitives.ButtonBase)
                            return;
                        target = target is FrameworkContentElement content
                            ? content.Parent : VisualTreeHelper.GetParent(target);
                    }
                    e.Handled = true;
                    Edit(null, null, date);
                };
                var matches = occurrences.Where(o => o.Item3 <= date && o.Item4 >= date &&
                    !(o.Item2.category == "work" && o.Item3 != o.Item4 && !Almanac.Weekday(date))).OrderBy(o => o.Item2.clock).ToList();
                var head = new DockPanel();
                var tag = new TextBlock { Text = shift, FontSize = 10 };
                SetDock(tag, Dock.Right);
                head.Children.Add(tag);
                var number = Button(date.Day.ToString(), () => { if (matches.Count > 0) Agenda(date, matches); else Edit(null, null, date); });
                number.Padding = new Thickness(0);
                number.Background = Brushes.Transparent;
                if (!Almanac.Weekday(date) || holiday != "")
                    number.Foreground = Brush("#ffb9bf");
                head.Children.Add(number);
                var moon = new TextBlock { Text = almanac.Lunar(date), FontSize = 9, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
                head.Children.Add(moon);
                panel.Children.Add(head);
                if (holiday != "")
                    panel.Children.Add(new TextBlock { Text = holiday, Foreground = Brush("#ffb9bf"), FontSize = 9, TextTrimming = TextTrimming.CharacterEllipsis });
                foreach (var occurrence in matches.Take(limit))
                {
                    var item = occurrence.Item2;
                    string progress = item.category == "work" && occurrence.Item3 != occurrence.Item4 ? $" ({Almanac.Weekdays(occurrence.Item3, date)}/{Almanac.Weekdays(occurrence.Item3, occurrence.Item4)})" : "";
                    var button = Button($"{(item.visibility == "private" ? "🔒 " : "")}{item.clock} {item.title}{progress}", () => Edit(occurrence.Item1, item, date));
                    button.FontSize = 11;
                    button.Height = 21;
                    button.Padding = new Thickness(2, 0, 2, 0);
                    button.HorizontalContentAlignment = HorizontalAlignment.Left;
                    var row = new DockPanel();
                    if (progress != "")
                    {
                        var counter = new TextBlock { Text = progress, FontWeight = FontWeights.SemiBold };
                        SetDock(counter, Dock.Right);
                        row.Children.Add(counter);
                    }
                    row.Children.Add(new TextBlock { Text = (item.visibility == "private" ? "🔒 " : "") + item.clock + " " + item.title, TextTrimming = TextTrimming.CharacterEllipsis });
                    button.Content = row;
                    button.HorizontalContentAlignment = HorizontalAlignment.Stretch;
                    button.ToolTip = item.title + progress + " · " + item.author;
                    button.Background = EventPalette.Color(item.color);
                    panel.Children.Add(button);
                }
                if (matches.Count > limit)
                    panel.Children.Add(Button($"+{matches.Count - limit}개", () => Agenda(date, matches)));
                Grid.SetColumn(border, i % 7);
                Grid.SetRow(border, i / 7);
                days.Children.Add(border);
            }
        }
        private Window Dialog(string title, Panel content)
        {
            var dialog = new Window
            {
                Title = title,
                Owner = Window.GetWindow(this),
                Width = 430,
                SizeToContent = SizeToContent.Height,
                MaxHeight = SystemParameters.WorkArea.Height - 40,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Background = Brush("#16344a"),
                Foreground = Brushes.White,
                ShowInTaskbar = false
            };
            dialog.SourceInitialized += (_, __) => CalendarWindow.ExcludeFromSwitcher(dialog);
            dialog.Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            return dialog;
        }
        private StackPanel Form() => new StackPanel { Margin = new Thickness(16) };
        private TextBox Field(Panel panel, string label, string value, bool multiline = false)
        {
            panel.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 8, 0, 3) });
            var text = new TextBox { Foreground = Brush("#233a32"), Background = Brushes.White, Text = value ?? "", Padding = new Thickness(5), AcceptsReturn = multiline, MinHeight = multiline ? 65 : 28 };
            panel.Children.Add(text);
            return text;
        }
        private ComboBox Choice(Panel panel, string label, string[] names, int index)
        {
            panel.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 8, 0, 3) });
            var box = new ComboBox { Foreground = Brush("#233a32"), Background = Brushes.White, ItemsSource = names, SelectedIndex = index, Padding = new Thickness(4) };
            panel.Children.Add(box);
            return box;
        }
        private void Login()
        {
            var form = Form();
            var username = Field(form, "아이디", "");
            form.Children.Add(new TextBlock { Text = "비밀번호", Margin = new Thickness(0, 8, 0, 3) });
            var password = new PasswordBox { Foreground = Brush("#233a32"), Background = Brushes.White, Padding = new Thickness(5) };
            form.Children.Add(password);
            var error = new TextBlock { TextWrapping = TextWrapping.Wrap };
            form.Children.Add(error);
            var dialog = Dialog("가족 계정 로그인", form);
            Button submit = null;
            submit = Button("로그인", async () =>
            {
                submit.IsEnabled = false;
                try
                {
                    await store.Login(username.Text, password.Password);
                    dialog.Close();
                }
                catch (Exception ex) { error.Text = ex.Message; }
                finally { submit.IsEnabled = true; }
            });
            form.Children.Add(submit);
            dialog.ShowDialog();
        }
        private void Agenda(DateTime date, List<Tuple<string, NativeEvent, DateTime, DateTime>> items)
        {
            var form = Form();
            var dialog = Dialog(date.ToString("yyyy-MM-dd"), form);
            foreach (var item in items)
                form.Children.Add(Button(item.Item2.title, () => { dialog.Close(); Edit(item.Item1, item.Item2, date); }));
            form.Children.Add(Button("+ 일정 추가", () => { dialog.Close(); Edit(null, null, date); }));
            dialog.ShowDialog();
        }
        private void Edit(string key, NativeEvent source, DateTime date)
        {
            var user = store.View().user;
            if (user == null)
            {
                Login();
                return;
            }
            if (source != null && (source.sync_readonly != 0 || source.owner != user.id && user.admin == 0))
            {
                MessageBox.Show(source.title + "\n" + source.note, "일정");
                return;
            }
            var item = source == null ? new NativeEvent { day = Almanac.Stamp(date), end_day = Almanac.Stamp(date), category = "work", visibility = "private", owner = user.id, author = user.name }
                : Almanac.Json.Deserialize<NativeEvent>(Almanac.Json.Serialize(source));
            var form = Form();
            form.Children.Add(new TextBlock { Text = date.ToString("yyyy년 M월 d일") });
            var titleBox = Field(form, "일정 (새 일정은 한 줄에 하나씩)", item.title, source == null);
            if (source == null)
                titleBox.Loaded += (_, __) => titleBox.Focus();
            var detail = new StackPanel { Visibility = source == null ? Visibility.Collapsed : Visibility.Visible };
            form.Children.Add(detail);
            var start = Field(detail, "시작일 (YYYY-MM-DD 또는 숫자 8자리)", item.day);
            var end = Field(detail, "종료일", item.end_day);
            var time = Field(detail, "시간 (HH:mm, 비우면 종일)", item.clock);
            var kind = Choice(detail, "날짜 기준", new[] { "양력", "음력" }, item.calendar_type == "lunar" ? 1 : 0);
            var annual = new CheckBox { Content = "매년 반복", IsChecked = item.annual, Margin = new Thickness(0, 8, 0, 0) };
            detail.Children.Add(annual);
            var leap = new CheckBox { Content = "윤달", IsChecked = item.leap };
            detail.Children.Add(leap);
            var type = Choice(detail, "일정 구분", new[] { "가족", "회사" }, item.category == "work" ? 1 : 0);
            var visibility = Choice(detail, "공개 범위", new[] { "모두에게 보이기", "나에게만 보이기" }, item.visibility == "private" ? 1 : 0);
            var color = Choice(detail, "일정 색상", EventPalette.Labels, Math.Max(0, Array.IndexOf(EventPalette.Ids, item.color)));
            color.ItemsSource = null;
            foreach (string id in EventPalette.Ids) {
                var option = new StackPanel { Orientation = Orientation.Horizontal };
                option.Children.Add(new Border { Width = 18, Height = 18, Background = EventPalette.Color(id), Margin = new Thickness(0,0,7,0), CornerRadius = new CornerRadius(3) });
                option.Children.Add(new TextBlock { Text = EventPalette.Labels[Array.IndexOf(EventPalette.Ids,id)], Foreground = Brushes.Black });
                color.Items.Add(option);
            }
            color.SelectedIndex = Math.Max(0, Array.IndexOf(EventPalette.Ids,item.color));
            var note = Field(detail, "메모", item.note, true);
            var error = new TextBlock { TextWrapping = TextWrapping.Wrap };
            form.Children.Add(error);
            var dialog = Dialog("일정", form);
            if (source == null)
                form.Children.Add(Button("상세", () => detail.Visibility = Visibility.Visible));
            form.Children.Add(Button("저장", () =>
            {
                try
                {
                    item.day = start.Text;
                    item.end_day = end.Text;
                    item.clock = time.Text.Trim();
                    item.note = note.Text;
                    item.calendar_type = kind.SelectedIndex == 1 ? "lunar" : "solar";
                    item.annual = annual.IsChecked == true;
                    item.leap = leap.IsChecked == true;
                    item.category = type.SelectedIndex == 1 ? "work" : "family";
                    item.visibility = visibility.SelectedIndex == 1 ? "private" : "shared";
                    item.color = EventPalette.Ids[Math.Max(0, color.SelectedIndex)];
                    var entries = source == null ? titleBox.Text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries) : new[] { titleBox.Text };
                    var prepared = new List<NativeEvent>();
                    foreach (string line in entries)
                    {
                        var entry = Almanac.Json.Deserialize<NativeEvent>(Almanac.Json.Serialize(item));
                        entry.title = line.Trim();
                        if (source == null && System.Text.RegularExpressions.Regex.IsMatch(entry.title, @"^([01]\d|2[0-3]):[0-5]\d\s+"))
                        {
                            entry.clock = entry.title.Substring(0, 5);
                            entry.title = entry.title.Substring(6).Trim();
                        }
                        almanac.Validate(entry);
                        prepared.Add(entry);
                    }
                    if (prepared.Count == 0 || prepared.Count > 50)
                        throw new FormatException("일정을 1~50개 입력하세요.");
                    foreach (var entry in prepared)
                        store.Save(key, entry);
                    dialog.Close();
                    _ = store.Sync();
                }
                catch (Exception ex) { error.Text = ex.Message; }
            }));
            if (source != null)
                form.Children.Add(Button("삭제", () => { if (MessageBox.Show("이 일정 전체를 삭제할까요?", "삭제", MessageBoxButton.YesNo) == MessageBoxResult.Yes) { store.Save(key, item, true); dialog.Close(); _ = store.Sync(); } }));
            dialog.ShowDialog();
        }
        private void Settings()
        {
            var view = store.View();
            if (view.user == null)
            {
                Login();
                return;
            }
            var form = Form();
            var dialog = Dialog("설정 · " + view.user.name, form);
            var filter = Choice(form, "일정 구분", new[] { "전체", "가족", "회사" }, category == "all" ? 0 : category == "family" ? 1 : 2);
            var owner = Choice(form, "일정 보기", new[] { "전체 일정", "내 일정" }, mine ? 1 : 0);
            var shift = Choice(form, "교대조", new[] { "A조", "B조", "해당 없음 (주간)", "교대 표시 끄기" }, view.user.shift_on == 0 ? 3 : view.user.shift_team == "B" ? 1 : view.user.shift_team == "none" ? 2 : 0);
            form.Children.Add(Button("적용", () => { category = new[] { "all", "family", "work" }[filter.SelectedIndex]; mine = owner.SelectedIndex == 1; store.Preference(new[] { "A", "B", "none", "none" }[shift.SelectedIndex], shift.SelectedIndex != 3); dialog.Close(); _ = store.Sync(); }));
            form.Children.Add(Button("지금 동기화", () => { _ = store.Sync(); dialog.Close(); }));
            form.Children.Add(Button("같은 계정으로 재로그인", () => { dialog.Close(); Login(); }));
            foreach (var pending in view.pending.Where(p => p.conflict || p.error != ""))
            {
                var text = new TextBlock { Text = pending.@event.title + " · " + pending.error, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) };
                form.Children.Add(text);
                if (pending.conflict)
                {
                    form.Children.Add(new TextBlock { Text = "내 일정 · " + pending.@event.title + " · " + pending.@event.day + " ~ " + pending.@event.end_day + " · " + pending.@event.note, TextWrapping = TextWrapping.Wrap });
                    form.Children.Add(new TextBlock { Text = pending.current == null ? "서버에서 삭제됨" : "서버 일정 · " + pending.current.title + " · " + pending.current.day + " ~ " + pending.current.end_day + " · " + pending.current.note, TextWrapping = TextWrapping.Wrap });
                }
                if (pending.conflict)
                    form.Children.Add(Button("내 변경 적용", () => { store.Resolve(pending.key, true); dialog.Close(); _ = store.Sync(); }));
                form.Children.Add(Button("서버 일정 사용 (내 변경 취소)", () => { store.Resolve(pending.key, false); dialog.Close(); _ = store.Sync(); }));
            }
            form.Children.Add(Button("로그아웃", async () => { try { await store.Logout(); dialog.Close(); } catch (Exception ex) { MessageBox.Show(ex.Message); } }));
            dialog.ShowDialog();
        }
    }
}
