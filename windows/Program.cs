using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
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
        private static void Main() => new Application().Run(new CalendarWindow());
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
        private bool starting, closed;

        public CalendarWindow()
        {
            Title = "Famical · 가족 달력";
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
            AddButton(controls, "−", "최소화", () => WindowState = WindowState.Minimized);
            AddButton(controls, "□", "최대화 또는 복원", ToggleMaximize);
            AddButton(controls, "×", "닫기", Close);
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
            Loaded += (_, __) => Navigate();
            Closed += (_, __) => { closed = true; SaveSettings(); browser.Dispose(); };
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
                Directory.CreateDirectory(dataDir);
                var bounds = RestoreBounds;
                new XElement("window", new XAttribute("server", server.AbsoluteUri),
                    new XAttribute("width", bounds.Width), new XAttribute("height", bounds.Height),
                    new XAttribute("opacity", Opacity)).Save(Path.Combine(dataDir, "window.xml"));
            } catch (IOException) {} catch (UnauthorizedAccessException) {}
        }
    }
}
