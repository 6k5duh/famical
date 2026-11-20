using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using Forms = System.Windows.Forms;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shell;
using System.Xml;
using System.Xml.Linq;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace Famical.Windows
{
    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            bool startHidden = Array.Exists(args, arg => string.Equals(arg, "--tray", StringComparison.OrdinalIgnoreCase));
            using (var openRequest = new EventWaitHandle(false, EventResetMode.AutoReset,
                @"Local\Famical.Windows.OpenCalendar", out bool firstInstance)) {
                if (!firstInstance) {
                    if (!startHidden) openRequest.Set();
                    return;
                }

                var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
                var calendar = new CalendarWindow();
                app.MainWindow = calendar;
                app.SessionEnding += (_, __) => calendar.AllowExit();
                var listener = ThreadPool.RegisterWaitForSingleObject(openRequest,
                    (_, __) => app.Dispatcher.BeginInvoke(new Action(calendar.OpenCalendar)), null, Timeout.Infinite, false);
                try {
                    if (!startHidden) calendar.Show();
                    app.Run();
                } finally {
                    listener.Unregister(null);
                }
            }
        }
    }

    internal sealed class CalendarWindow : Window
    {
        private const string DefaultServer = "https://famical.ymsh.synology.me/";
        private readonly string dataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Famical");
        private readonly WebView2CompositionControl browser = new WebView2CompositionControl();
        private readonly TextBlock status = new TextBlock {
            Foreground = Brushes.White, Margin = new Thickness(14), TextWrapping = TextWrapping.Wrap
        };
        private readonly Uri server;
        private readonly Forms.NotifyIcon tray;
        private readonly Forms.ContextMenuStrip trayMenu;
        private readonly System.Drawing.Icon trayIcon;
        private bool starting, closed, exiting;
        private WindowState lastVisibleState = WindowState.Normal;

        public CalendarWindow()
        {
            Title = "Famical · 가족 달력";
            ShowInTaskbar = false;
            Background = new SolidColorBrush(Color.FromRgb(15, 35, 51));
            Foreground = Brushes.White;
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            MinWidth = 370;
            MinHeight = 320;
            ResizeMode = ResizeMode.CanResizeWithGrip;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Width = Math.Min(1080, SystemParameters.WorkArea.Width);
            Height = Math.Min(780, SystemParameters.WorkArea.Height);
            server = RestoreSettings();

            WindowChrome.SetWindowChrome(this, new WindowChrome {
                CaptionHeight = 0, ResizeBorderThickness = new Thickness(6),
                GlassFrameThickness = new Thickness(0), CornerRadius = new CornerRadius(0)
            });

            var panel = new DockPanel { Margin = new Thickness(6) };
            var bar = new DockPanel { Margin = new Thickness(3, 1, 3, 5) };
            DockPanel.SetDock(bar, Dock.Top);
            panel.Children.Add(bar);
            var controls = new StackPanel { Orientation = Orientation.Horizontal };
            DockPanel.SetDock(controls, Dock.Right);
            bar.Children.Add(controls);
            controls.Children.Add(new TextBlock { Text = "투명도", VerticalAlignment = VerticalAlignment.Center });
            var transparency = new Slider {
                Width = 70, Minimum = 0, Maximum = 60, Value = (1 - Opacity) * 100,
                VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(6,0,7,0),
                ToolTip = "창 투명도 (0~60%)", SmallChange = 5
            };
            System.Windows.Automation.AutomationProperties.SetName(transparency, "창 투명도");
            transparency.ValueChanged += (_, e) => Opacity = 1 - e.NewValue / 100;
            controls.Children.Add(transparency);
            AddButton(controls, "↻", "다시 연결", Navigate);
            AddButton(controls, "−", "트레이로 숨기기", Hide);
            AddButton(controls, "□", "최대화 또는 복원", ToggleMaximize);
            AddButton(controls, "×", "트레이로 숨기기", Hide);
            var title = new Border { Background = Brushes.Transparent, Child = new TextBlock {
                Text = "FAMICAL", FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center
            }};
            title.MouseLeftButtonDown += (_, e) => {
                if (e.ClickCount == 2) ToggleMaximize();
                else if (e.LeftButton == MouseButtonState.Pressed) DragMove();
            };
            bar.Children.Add(title);
            var area = new Grid();
            area.Children.Add(browser);
            area.Children.Add(status);
            panel.Children.Add(area);
            Content = panel;
            trayMenu = new Forms.ContextMenuStrip();
            trayMenu.Items.Add("달력 열기", null, (_, __) => OpenCalendar());
            trayMenu.Items.Add("종료", null, (_, __) => { AllowExit(); Application.Current.Shutdown(); });
            trayIcon = System.Drawing.Icon.ExtractAssociatedIcon(typeof(Program).Assembly.Location);
            tray = new Forms.NotifyIcon {
                Icon = trayIcon, Text = "Famical · 가족 달력", ContextMenuStrip = trayMenu, Visible = true
            };
            tray.MouseClick += (_, e) => { if (e.Button == Forms.MouseButtons.Left) OpenCalendar(); };
            Loaded += OnFirstLoad;
            StateChanged += (_, __) => {
                if (WindowState == WindowState.Minimized) Hide();
                else lastVisibleState = WindowState;
            };
            Closed += (_, __) => {
                closed = true;
                tray.Visible = false;
                tray.Dispose();
                trayMenu.Dispose();
                trayIcon.Dispose();
                SaveSettings();
                browser.Dispose();
            };
        }

        private const int ExtendedStyleIndex = -20;
        private const int ToolWindowStyle = 0x00000080;
        private const int AppWindowStyle = 0x00040000;

        [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
        private static extern int GetWindowLong(IntPtr window, int index);

        [DllImport("user32.dll", EntryPoint = "SetWindowLongW")]
        private static extern int SetWindowLong(IntPtr window, int index, int value);

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            // ShowInTaskbar alone still allows Alt+Tab to list a visible borderless window.
            var handle = new WindowInteropHelper(this).Handle;
            int style = GetWindowLong(handle, ExtendedStyleIndex);
            SetWindowLong(handle, ExtendedStyleIndex, (style | ToolWindowStyle) & ~AppWindowStyle);
        }

        private void OnFirstLoad(object sender, RoutedEventArgs e)
        {
            Loaded -= OnFirstLoad;
            Navigate();
        }

        internal void AllowExit() => exiting = true;

        internal void OpenCalendar()
        {
            if (closed || exiting) return;
            if (WindowState == WindowState.Minimized) WindowState = lastVisibleState;
            Show();
            Activate();
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            base.OnClosing(e);
            if (exiting) return;
            e.Cancel = true;
            Dispatcher.BeginInvoke(new Action(() => { if (!closed && !exiting) Hide(); }));
        }

        private static void AddButton(Panel panel, string text, string name, Action action)
        {
            var button = new Button {
                Content = text, ToolTip = name, Width = 31, Height = 26,
                Foreground = Brushes.White, Background = new SolidColorBrush(Color.FromRgb(31,65,86)),
                BorderThickness = new Thickness(0), Margin = new Thickness(1)
            };
            System.Windows.Automation.AutomationProperties.SetName(button, name);
            button.Click += (_, __) => action();
            panel.Children.Add(button);
        }

        private void ToggleMaximize() => WindowState =
            WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

        private async void Navigate()
        {
            if (starting || closed) return;
            starting = true;
            status.Visibility = Visibility.Visible;
            status.Text = "가족 달력에 연결하는 중…";
            try {
                if (browser.CoreWebView2 == null) {
                    var environment = await CoreWebView2Environment.CreateAsync(null, Path.Combine(dataDir, "Browser"));
                    if (closed) return;
                    await browser.EnsureCoreWebView2Async(environment);
                    if (closed) return;
                    var core = browser.CoreWebView2;
                    core.Settings.AreHostObjectsAllowed = false;
                    core.Settings.IsWebMessageEnabled = false;
                    core.NewWindowRequested += (_, e) => e.Handled = true;
                    core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
                    core.NavigationStarting += (_, e) => {
                        if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var next) ||
                            next.GetLeftPart(UriPartial.Authority) != server.GetLeftPart(UriPartial.Authority))
                            e.Cancel = true;
                    };
                    core.NavigationCompleted += (_, e) => {
                        status.Visibility = e.IsSuccess ? Visibility.Collapsed : Visibility.Visible;
                        if (!e.IsSuccess) status.Text = "서버에 연결할 수 없습니다. 인터넷과 NAS를 확인한 뒤 ↻를 누르세요.";
                    };
                }
                browser.CoreWebView2.Navigate(new Uri(server, "/?view=desktop").AbsoluteUri);
            } catch (WebView2RuntimeNotFoundException) {
                status.Text = "Microsoft Edge WebView2 Runtime을 설치한 뒤 앱을 다시 열어 주세요.";
            } catch (Exception ex) {
                if (!closed) status.Text = "달력을 열 수 없습니다. " + ex.Message;
            } finally { starting = false; }
        }

        private Uri RestoreSettings()
        {
            try {
                var settings = XElement.Load(Path.Combine(dataDir, "window.xml"));
                Width = Clamp((double?)settings.Attribute("width") ?? Width, MinWidth, SystemParameters.WorkArea.Width);
                Height = Clamp((double?)settings.Attribute("height") ?? Height, MinHeight, SystemParameters.WorkArea.Height);
                Opacity = Clamp((double?)settings.Attribute("opacity") ?? 1, 0.4, 1);
                if (Uri.TryCreate((string)settings.Attribute("server"), UriKind.Absolute, out var uri) &&
                    uri.Scheme == Uri.UriSchemeHttps && uri.UserInfo == "" && uri.PathAndQuery == "/" && uri.Fragment == "")
                    return uri;
            } catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException ||
                                          ex is XmlException || ex is FormatException || ex is OverflowException) {}
            return new Uri(DefaultServer);
        }

        private static double Clamp(double number, double min, double max) =>
            double.IsNaN(number) ? min : Math.Max(min, Math.Min(max, number));

        private void SaveSettings()
        {
            try {
                var bounds = RestoreBounds;
                if (bounds.IsEmpty) return;
                Directory.CreateDirectory(dataDir);
                new XElement("window", new XAttribute("server", server.AbsoluteUri),
                    new XAttribute("width", bounds.Width), new XAttribute("height", bounds.Height),
                    new XAttribute("opacity", Opacity)).Save(Path.Combine(dataDir, "window.xml"));
            } catch (IOException) {} catch (UnauthorizedAccessException) {}
        }
    }
}
