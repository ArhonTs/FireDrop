using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using FireDrop.Core;
using FireDrop.ViewModels;
using Forms = System.Windows.Forms;
using Drawing = System.Drawing;

namespace FireDrop;

public partial class MainWindow : Window
{
    private static readonly Color SuccessColor = Color.FromRgb(52, 211, 153);
    private static readonly Color AccentColor = Color.FromRgb(34, 211, 238);
    private static readonly Color DangerColor = Color.FromRgb(251, 113, 133);
    private static readonly Color WarningColor = Color.FromRgb(251, 191, 36);

    private readonly AppSettings _settings;
    private readonly UploadManager _uploads;
    private readonly TransferServer _server;
    private readonly SemaphoreSlim _serverGate = new(1, 1);
    private readonly ObservableCollection<FileItemViewModel> _files = new();
    private readonly Dictionary<string, FileItemViewModel> _fileIndex = new(StringComparer.Ordinal);

    private readonly DispatcherTimer _statsTimer;
    private readonly DispatcherTimer _activityTimer;
    private readonly DispatcherTimer _networkTimer;
    private readonly DispatcherTimer _toastTimer;

    private List<LanAddress> _addresses = new();
    private string? _selectedIp;
    private bool _manualAddressSelection;
    private IInputElement? _settingsReturnFocus;
    private string? _serverError;
    private Forms.NotifyIcon? _trayIcon;
    private Forms.ContextMenuStrip? _trayMenu;
    private Drawing.Icon? _trayDrawingIcon;
    private bool _initialized;
    private bool _shuttingDown;
    private bool _allowClose;
    private bool _forceExit;
    private bool _settingsVisible;
    private bool _savingSettings;
    private string _languageChoice = "auto";
    private bool _trayHintShown;

    private bool _sendVisible;
    private SendPhase _sendPhase = SendPhase.Idle;
    private IInputElement? _sendReturnFocus;
    private PhoneSender? _sender;
    private CancellationTokenSource? _sendCts;
    private readonly ObservableCollection<SendItemViewModel> _sendQueue = new();
    private readonly ObservableCollection<SendDeviceViewModel> _foundDevices = new();
    private readonly DispatcherTimer _sendTimer;
    private long _lastSentBytes;
    private long _lastSampleMs;

    public MainWindow(AppSettings settings)
    {
        InitializeComponent();

        _settings = settings;
        _uploads = new UploadManager(settings.SaveFolder);
        _server = new TransferServer(_uploads, settings.DeviceName);

        FilesList.ItemsSource = _files;
        PinItems.ItemsSource = _server.Pin.Select(ch => ch.ToString()).ToArray();
        AutomationProperties.SetName(
            PinItems,
            L10n.S("ui.pin.automation.format",
                string.Join(" ", _server.Pin.Select(ch => ch.ToString()))));
        VersionText.Text = L10n.S("ui.version.format", TransferServer.Version);
        FolderPathText.Text = _uploads.SaveFolder;
        FolderPathText.ToolTip = _uploads.SaveFolder;

        _statsTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(400),
        };
        _statsTimer.Tick += (_, _) => RefreshStats();

        _activityTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(6),
        };
        _activityTimer.Tick += (_, _) =>
        {
            _activityTimer.Stop();
            ResetConnectionStatus();
        };

        _networkTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(1200),
        };
        _networkTimer.Tick += (_, _) =>
        {
            _networkTimer.Stop();
            RefreshAddresses();
        };

        _toastTimer = new DispatcherTimer();
        _toastTimer.Tick += (_, _) => HideToast();

        SendFilesList.ItemsSource = _sendQueue;
        SendDevicesList.ItemsSource = _foundDevices;

        _sendTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(300),
        };
        _sendTimer.Tick += (_, _) => RefreshSendProgress();

        _server.ClientActivity += OnClientActivity;
        _uploads.FileStarted += info => RunOnUi(() => HandleFileStarted(info));
        _uploads.FileCompleted += info => RunOnUi(() => HandleFileCompleted(info));
        _uploads.FileFailed += (id, reason) => RunOnUi(() => HandleFileFailed(id, reason));

        NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;

        UpdateFileChrome();
        UpdateFooter();
        RefreshAddresses();
    }

    public bool HasTrayIcon => _trayIcon is not null;

    public async Task<bool> InitializeAsync()
    {
        if (_initialized)
            return _server.IsRunning;

        _initialized = true;
        CreateTrayIcon();
        SetStatus(L10n.Get("ui.status.starting"), WarningColor);

        var running = await StartServerInternalAsync(_settings.Port);
        _statsTimer.Start();
        RefreshStats();
        UpdateConnectionUi();
        UpdateFooter();
        return running;
    }

    public void ShowFromTray()
    {
        if (_shuttingDown) return;

        ShowInTaskbar = true;
        if (!IsVisible)
            Show();

        if (WindowState == WindowState.Minimized)
            WindowState = WindowState.Normal;

        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    private async Task<bool> StartServerInternalAsync(int port)
    {
        await _serverGate.WaitAsync();
        try
        {
            if (_server.IsRunning)
                await _server.StopAsync();

            _serverError = null;

            try
            {
                await _server.StartAsync(port);
            }
            catch (Exception ex)
            {
                _serverError = DescribeServerError(ex, port);
                AppLog.Error($"Не удалось запустить сервер на порту {port}", ex);
                SetStatus(L10n.Get("ui.state.server"), DangerColor);
                UpdateConnectionUi();
                UpdateFooter();
                return false;
            }

            AppLog.Info($"Сервер FireDrop слушает порт {port}");
            RefreshAddresses();
            ResetConnectionStatus();
            UpdateFooter();
            return true;
        }
        finally
        {
            _serverGate.Release();
        }
    }

    private static string DescribeServerError(Exception ex, int port)
    {
        var socket = FindSocketException(ex);
        if (socket?.SocketErrorCode == SocketError.AddressAlreadyInUse ||
            ex.Message.Contains("address already in use", StringComparison.OrdinalIgnoreCase))
        {
            return L10n.S("ui.error.port.busy", port);
        }

        if (socket?.SocketErrorCode == SocketError.AccessDenied)
        {
            return L10n.S("ui.error.port.denied", port);
        }

        return L10n.S("ui.error.port.generic", port);
    }

    private static SocketException? FindSocketException(Exception? ex)
    {
        for (var depth = 0; ex is not null && depth < 8; depth++, ex = ex.InnerException)
        {
            if (ex is SocketException socket)
                return socket;
        }

        return null;
    }

    private LanAddress? CurrentAddress =>
        _selectedIp is null ? null : _addresses.FirstOrDefault(a => a.Ip == _selectedIp);

    private void RefreshAddresses()
    {
        var previousIp = _selectedIp;
        _addresses = NetworkHelper.GetLanAddresses().ToList();

        var keepManual = _manualAddressSelection && previousIp is not null &&
                         _addresses.Any(a => a.Ip == previousIp);
        if (!keepManual)
        {
            _manualAddressSelection = false;
            _selectedIp = _addresses.FirstOrDefault()?.Ip;
        }

        var selected = CurrentAddress;
        AddressText.Text = selected?.Display ?? L10n.Get("ui.network.notfound");
        AddressText.ToolTip = selected is null
            ? L10n.Get("ui.network.nowifi.tooltip")
            : $"{selected.Description} · {selected.Ip}";
        AddressButton.ToolTip = _addresses.Count > 1
            ? L10n.Get("ui.address.tooltip")
            : L10n.Get("ui.address.refresh.tooltip");
        AutomationProperties.SetName(
            AddressButton,
            selected is null
                ? L10n.Get("ui.address.automation.none")
                : L10n.S("ui.address.automation.format", selected.Display));

        UpdateConnectionUi();

        if (_server.IsRunning && !_activityTimer.IsEnabled)
            ResetConnectionStatus();
    }

    private void UpdateConnectionUi()
    {
        var address = CurrentAddress;
        var running = _server.IsRunning;

        QrLiveText.Text = running ? L10n.Get("ui.status.online") : L10n.Get("ui.status.offline");
        QrLiveText.Foreground = Frozen(running ? SuccessColor : DangerColor);
        QrLiveDot.Fill = Frozen(running ? SuccessColor : DangerColor);

        if (!running)
        {
            QrCodeImage.Text = string.Empty;
            UrlText.Text = "http://—";
            CopyLinkButton.IsEnabled = false;
            CopyPinButton.IsEnabled = false;
            AddressButton.IsEnabled = false;
            ShowQrState(
                L10n.Get("ui.state.server"),
                _serverError ?? L10n.Get("ui.qr.server.retry.hint"),
                L10n.Get("ui.action.retry"),
                serverMode: true);
            return;
        }

        CopyPinButton.IsEnabled = true;
        AddressButton.IsEnabled = true;

        if (address is null)
        {
            QrCodeImage.Text = string.Empty;
            UrlText.Text = "http://—";
            CopyLinkButton.IsEnabled = false;
            ShowQrState(
                L10n.Get("ui.qr.nowifi.title"),
                L10n.Get("ui.qr.nowifi.hint"),
                L10n.Get("ui.action.refresh"),
                serverMode: false);
            return;
        }

        QrCodeImage.Text = _server.BuildUrl(address.Ip, includeToken: true);
        UrlText.Text = _server.BuildUrl(address.Ip, includeToken: false);
        UrlText.ToolTip = L10n.Get("ui.url.tooltip");
        CopyLinkButton.IsEnabled = true;
        QrStatePanel.Visibility = Visibility.Collapsed;
    }

    private void ShowQrState(string title, string message, string action, bool serverMode)
    {
        QrStateTitle.Text = title;
        QrStateText.Text = message;
        QrStateAction.Content = action;
        QrStateAction.Tag = serverMode ? "server" : "network";
        QrStatePanel.Visibility = Visibility.Visible;
        QrStatePanel.ToolTip = message;
    }

    private void ResetConnectionStatus()
    {
        if (!_server.IsRunning)
            SetStatus(L10n.Get("ui.state.server"), DangerColor);
        else if (CurrentAddress is null)
            SetStatus(L10n.Get("ui.status.nowifi"), WarningColor);
        else
            SetStatus(L10n.Get("ui.status.ready"), SuccessColor);
    }

    private void SetStatus(string text, Color color)
    {
        StatusText.Text = text;
        StatusText.Foreground = Frozen(color);
        StatusDot.Fill = Frozen(color);
        StatusPill.Background = Frozen(Color.FromArgb(24, color.R, color.G, color.B));
        StatusPill.BorderBrush = Frozen(Color.FromArgb(62, color.R, color.G, color.B));
    }

    private void UpdateFooter()
    {
        var running = _server.IsRunning;
        FooterStateDot.Fill = Frozen(running ? SuccessColor : DangerColor);
        FooterStateText.Text = running
            ? L10n.S("ui.footer.running.format", _server.Port)
            : L10n.Get("ui.state.server");
    }

    private void OnClientActivity()
    {
        RunOnUi(() =>
        {
            if (_shuttingDown || !_server.IsRunning) return;
            SetStatus(L10n.Get("ui.status.phoneconnected"), AccentColor);
            _activityTimer.Stop();
            _activityTimer.Start();
        });
    }

    private void RefreshStats()
    {
        if (_shuttingDown) return;

        var snapshot = _uploads.GetSnapshot();

        foreach (var active in snapshot.Active)
        {
            if (!_fileIndex.TryGetValue(active.Id, out var item))
            {
                item = new FileItemViewModel(active);
                _fileIndex[active.Id] = item;
                _files.Insert(0, item);
            }

            item.ApplyProgress(active);
        }

        SpeedValueText.Text = FileItemViewModel.FormatSpeed(snapshot.Speed);
        SpeedHintText.Text = snapshot.Active.Count == 0
            ? L10n.Get("ui.status.waiting")
            : L10n.S("ui.stats.inprogress",
                snapshot.Active.Count,
                Plural(snapshot.Active.Count, L10n.Get("ui.plural.file.one"), L10n.Get("ui.plural.file.few"), L10n.Get("ui.plural.file.many")));
        FilesValueText.Text = snapshot.CompletedCount.ToString("N0", CultureInfo.CurrentCulture);
        VolumeValueText.Text = FileItemViewModel.FormatBytes(snapshot.CompletedBytes);
        UpdateFileChrome();
    }

    private void HandleFileStarted(TransferInfo info)
    {
        if (_shuttingDown) return;
        EnsureFileItem(info);
        UpdateFileChrome();
    }

    private void HandleFileCompleted(TransferInfo info)
    {
        if (_shuttingDown) return;

        var item = EnsureFileItem(info);
        item.Complete(info);
        UpdateFileChrome();

        ShowToast(
            $"{Shorten(info.FileName, 46)} · {FileItemViewModel.FormatBytes(info.Size)} · {FileItemViewModel.FormatSpeed(info.AvgBytesPerSecond)}",
            ToastKind.Success,
            3400);

        if (!IsVisible || WindowState == WindowState.Minimized)
            ShowTrayFileNotification(info);
    }

    private void HandleFileFailed(string id, string reason)
    {
        if (_shuttingDown) return;
        if (!_fileIndex.TryGetValue(id, out var item)) return;

        item.Fail(reason);
        UpdateFileChrome();

        var cancelled = reason.Contains("отмен", StringComparison.OrdinalIgnoreCase) ||
                        reason.Contains("cancell", StringComparison.OrdinalIgnoreCase) ||
                        reason.Contains("истекло", StringComparison.OrdinalIgnoreCase) ||
                        reason.Contains("timed out", StringComparison.OrdinalIgnoreCase);
        ShowToast(
            cancelled ? L10n.Get("ui.toast.receiving.cancelled") : L10n.S("ui.toast.receive.failed", reason),
            cancelled ? ToastKind.Info : ToastKind.Error,
            4200);
    }

    private FileItemViewModel EnsureFileItem(TransferInfo info)
    {
        if (_fileIndex.TryGetValue(info.Id, out var item))
            return item;

        item = new FileItemViewModel(info);
        _fileIndex[info.Id] = item;
        _files.Insert(0, item);
        return item;
    }

    private void UpdateFileChrome()
    {
        FilesCountBadge.Text = _files.Count.ToString(CultureInfo.CurrentCulture);
        EmptyState.Visibility = _files.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        ClearButton.IsEnabled = _files.Any(item => item.IsCompleted || item.IsFailed);
    }

    private void ClearFiles_Click(object sender, RoutedEventArgs e)
    {
        var removable = _files.Where(item => !item.IsActive).ToList();
        if (removable.Count == 0) return;

        foreach (var item in removable)
        {
            _files.Remove(item);
            _fileIndex.Remove(item.Id);
        }

        FilesList.SelectedItem = null;
        UpdateFileChrome();
        ShowToast(L10n.Get("ui.toast.history.cleared"), ToastKind.Info, 2000);
    }

    private void FilesList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (FilesList.SelectedItem is not FileItemViewModel item) return;
        if (!item.IsCompleted) return;
        OpenFile(item);
    }

    private void OpenFile(FileItemViewModel item)
    {
        try
        {
            if (!File.Exists(item.FullPath))
            {
                ShowToast(L10n.Get("ui.toast.file.missing"), ToastKind.Error);
                return;
            }

            Process.Start(new ProcessStartInfo(item.FullPath) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            AppLog.Error($"Не удалось открыть файл {item.FullPath}", ex);
            ShowToast(L10n.Get("ui.toast.file.open.failed"), ToastKind.Error);
        }
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        OpenFolder(_uploads.SaveFolder);
    }

    private void ChangeFolder_Click(object sender, RoutedEventArgs e)
    {
        OpenSettings(focusFolder: true);
    }

    private void OpenFolder(string folder)
    {
        try
        {
            Directory.CreateDirectory(folder);
            Process.Start(new ProcessStartInfo("explorer.exe", $"\"{folder}\"")
            {
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            AppLog.Error($"Не удалось открыть папку {folder}", ex);
            ShowToast(L10n.Get("ui.toast.folder.open.failed"), ToastKind.Error);
        }
    }

    private void CopyLink_Click(object sender, RoutedEventArgs e)
    {
        CopyCurrentLink();
    }

    private void CopyCurrentLink()
    {
        var address = CurrentAddress;
        if (!_server.IsRunning || address is null)
        {
            ShowToast(L10n.Get("ui.toast.need.wifi"), ToastKind.Error);
            return;
        }

        if (CopyText(_server.BuildUrl(address.Ip, includeToken: true)))
            ShowToast(L10n.Get("ui.toast.link.copied"), ToastKind.Success, 2400);
    }

    private void CopyPin_Click(object sender, RoutedEventArgs e)
    {
        if (!_server.IsRunning)
        {
            ShowToast(L10n.Get("ui.toast.server.notstarted"), ToastKind.Error);
            return;
        }

        if (CopyText(_server.Pin))
            ShowToast(L10n.Get("ui.toast.pin.copied"), ToastKind.Success, 2400);
    }

    private bool CopyText(string text)
    {
        try
        {
            Clipboard.SetText(text);
            return true;
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось скопировать текст в буфер обмена", ex);
            ShowToast(L10n.Get("ui.toast.copy.failed"), ToastKind.Error);
            return false;
        }
    }

    private void AddressButton_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu
        {
            PlacementTarget = AddressButton,
            Placement = PlacementMode.Top,
        };

        if (_addresses.Count == 0)
        {
            menu.Items.Add(new MenuItem { Header = L10n.Get("ui.network.notfound"), IsEnabled = false });
        }
        else
        {
            foreach (var address in _addresses)
            {
                var prefix = address.Ip == _selectedIp ? "✓  " : "     ";
                var item = new MenuItem
                {
                    Header = prefix + address.Display,
                    Tag = address.Ip,
                    ToolTip = address.Description,
                };
                item.Click += AddressItem_Click;
                menu.Items.Add(item);
            }
        }

        var refresh = new MenuItem { Header = L10n.Get("ui.address.menu.refresh") };
        refresh.Click += (_, _) => RefreshAddresses();
        menu.Items.Add(refresh);

        if (_addresses.Count > 0 && _manualAddressSelection)
        {
            var automatic = new MenuItem { Header = L10n.Get("ui.address.menu.auto") };
            automatic.Click += (_, _) =>
            {
                _manualAddressSelection = false;
                RefreshAddresses();
            };
            menu.Items.Add(automatic);
        }

        menu.IsOpen = true;
    }

    private void AddressItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not MenuItem { Tag: string ip }) return;
        _manualAddressSelection = true;
        _selectedIp = ip;
        RefreshAddresses();
    }

    private void QrStateAction_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string mode }) return;

        if (mode == "network")
        {
            RefreshAddresses();
            if (CurrentAddress is not null)
                ShowToast(L10n.Get("ui.toast.network.found"), ToastKind.Success, 1800);
            return;
        }

        _ = RetryServerAsync();
    }

    private async Task RetryServerAsync()
    {
        if (_initialized && !_shuttingDown)
        {
            SetStatus(L10n.Get("ui.status.starting"), WarningColor);
            QrStateAction.IsEnabled = false;
            var started = await StartServerInternalAsync(_settings.Port);
            QrStateAction.IsEnabled = true;

            if (started)
                ShowToast(L10n.Get("ui.toast.server.started"), ToastKind.Success);
            else
                ShowToast(_serverError ?? L10n.Get("ui.toast.server.startfailed"), ToastKind.Error, 5200);
        }
    }

    private void SettingsButton_Click(object sender, RoutedEventArgs e)
    {
        OpenSettings(focusFolder: false);
    }

    private void LanguageButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.Tag is not string code) return;
        _languageChoice = code;
        UpdateLanguageButtons();
    }

    private void UpdateLanguageButtons()
    {
        SetLanguageButton(LanguageAutoButton, _languageChoice == "auto");
        SetLanguageButton(LanguageRuButton, _languageChoice == "ru");
        SetLanguageButton(LanguageEnButton, _languageChoice == "en");
    }

    private void SetLanguageButton(Button button, bool active)
    {
        button.Style = (Style)FindResource(active ? "PrimaryButtonStyle" : "SecondaryButtonStyle");
    }

    private void PromptLanguageRestart()
    {
        var result = MessageBox.Show(
            L10n.Get("ui.language.restart.question"),
            "FireDrop",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);

        if (result == MessageBoxResult.Yes)
            RestartForLanguage();
    }

    private void RestartForLanguage()
    {
        var executable = Environment.ProcessPath;
        if (!string.IsNullOrWhiteSpace(executable))
        {
            try
            {
                // The new instance waits for this one to release the single-instance mutex.
                Process.Start(new ProcessStartInfo(
                    "cmd.exe",
                    $"/c ping 127.0.0.1 -n 2 >nul & start \"\" \"{executable}\"")
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                });
            }
            catch (Exception ex)
            {
                AppLog.Error("Не удалось перезапустить FireDrop", ex);
            }
        }

        _forceExit = true;
        Close();
    }

    private void OpenSettings(bool focusFolder)
    {        if (_settingsVisible) return;

        _settingsVisible = true;
        _settingsReturnFocus = Keyboard.FocusedElement;
        DeviceNameBox.Text = _settings.DeviceName;
        SaveFolderBox.Text = _uploads.SaveFolder;
        PortBox.Text = _settings.Port.ToString(CultureInfo.InvariantCulture);
        CurrentPortText.Text = _server.IsRunning
            ? L10n.S("ui.port.current.format", _server.Port)
            : L10n.Get("ui.port.stopped");
        AutostartCheck.IsChecked = _settings.StartWithWindows;
        TrayCheck.IsChecked = _settings.MinimizeToTrayOnClose;
        _languageChoice = _settings.Language;
        UpdateLanguageButtons();
        HideSettingsError();

        TitleBar.IsEnabled = false;
        MainContent.IsEnabled = false;
        FooterBar.IsEnabled = false;
        SaveSettingsButton.IsDefault = true;

        SettingsOverlay.Visibility = Visibility.Visible;
        SettingsOverlay.Opacity = 0;
        SettingsCardScale.ScaleX = 0.985;
        SettingsCardScale.ScaleY = 0.985;

        _ = CheckFirewallAsync();

        SettingsOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(140)));
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var scaleAnimation = new DoubleAnimation(0.985, 1, TimeSpan.FromMilliseconds(180))
        {
            EasingFunction = ease,
        };
        SettingsCardScale.BeginAnimation(ScaleTransform.ScaleXProperty, scaleAnimation);
        SettingsCardScale.BeginAnimation(ScaleTransform.ScaleYProperty, scaleAnimation);

        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            if (focusFolder) SaveFolderBox.Focus();
            else
            {
                DeviceNameBox.Focus();
                DeviceNameBox.SelectAll();
            }
        }));
    }

    private void HideSettings()
    {
        if (!_settingsVisible) return;

        _settingsVisible = false;
        SaveSettingsButton.IsDefault = false;
        SettingsOverlay.Visibility = Visibility.Collapsed;
        SettingsOverlay.BeginAnimation(OpacityProperty, null);
        SettingsCardScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        SettingsCardScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        HideSettingsError();

        TitleBar.IsEnabled = true;
        MainContent.IsEnabled = true;
        FooterBar.IsEnabled = true;

        var returnFocus = _settingsReturnFocus;
        _settingsReturnFocus = null;
        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            if (returnFocus is UIElement element && element.IsVisible && element.IsEnabled)
                element.Focus();
            else
                SettingsButton.Focus();
        }));
    }

    private void CloseSettings_Click(object sender, RoutedEventArgs e)
    {
        HideSettings();
    }

    private void SettingsOverlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (!SettingsCard.IsMouseOver)
            HideSettings();
    }

    private void BrowseFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            using var dialog = new Forms.FolderBrowserDialog
            {
                Description = L10n.Get("ui.dialog.savefolder"),
                UseDescriptionForTitle = true,
                ShowNewFolderButton = true,
                SelectedPath = Directory.Exists(SaveFolderBox.Text) ? SaveFolderBox.Text : _uploads.SaveFolder,
            };

            var owner = new WindowHandleOwner(new WindowInteropHelper(this).Handle);
            if (dialog.ShowDialog(owner) == Forms.DialogResult.OK)
                SaveFolderBox.Text = dialog.SelectedPath;
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось открыть выбор папки", ex);
            ShowSettingsError(L10n.Get("ui.error.folder.dialog"));
        }
    }

    private async void SaveSettings_Click(object sender, RoutedEventArgs e)
    {
        if (_savingSettings) return;

        HideSettingsError();

        var deviceName = DeviceNameBox.Text.Trim();
        if (deviceName.Length is < 1 or > 40)
        {
            ShowSettingsError(L10n.Get("ui.error.name.length"));
            DeviceNameBox.Focus();
            return;
        }

        if (!int.TryParse(PortBox.Text.Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out var port) ||
            port is < 1024 or > 65535)
        {
            ShowSettingsError(L10n.Get("ui.error.port.range"));
            PortBox.Focus();
            return;
        }

        var folder = SaveFolderBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(folder))
        {
            ShowSettingsError(L10n.Get("ui.error.folder.required"));
            SaveFolderBox.Focus();
            return;
        }

        try
        {
            folder = Path.GetFullPath(folder);
            Directory.CreateDirectory(folder);
        }
        catch (Exception ex)
        {
            AppLog.Error($"Недоступна папка сохранения {folder}", ex);
            ShowSettingsError(L10n.Get("ui.error.folder.invalid"));
            SaveFolderBox.Focus();
            return;
        }

        var portChanged = port != _settings.Port;
        var wasRunning = _server.IsRunning;

        _savingSettings = true;
        SaveSettingsButton.IsEnabled = false;
        SaveSettingsButton.Content = L10n.Get("ui.action.saving");

        try
        {
            if (portChanged || !wasRunning)
            {
                SetStatus(L10n.Get("ui.status.restarting"), WarningColor);
                var started = await StartServerInternalAsync(port);
                if (_shuttingDown) return;

                if (!started)
                {
                    var failure = _serverError ?? L10n.Get("ui.error.port.restartfailed");

                    if (wasRunning)
                    {
                        var restored = await StartServerInternalAsync(_settings.Port);
                        if (!restored)
                            SetStatus(L10n.Get("ui.state.server"), DangerColor);
                    }

                    ShowSettingsError(failure);
                    return;
                }
            }

            _settings.DeviceName = deviceName;
            _server.DeviceName = deviceName;
            _settings.Port = port;
            _settings.SaveFolder = folder;
            _settings.StartWithWindows = AutostartCheck.IsChecked == true;
            _settings.MinimizeToTrayOnClose = TrayCheck.IsChecked == true;
            var languageChanged = !string.Equals(_settings.Language, _languageChoice, StringComparison.Ordinal);
            _settings.Language = _languageChoice;
            _settings.Save();

            _uploads.SetSaveFolder(folder);
            FolderPathText.Text = _uploads.SaveFolder;
            FolderPathText.ToolTip = _uploads.SaveFolder;

            HideSettings();
            RefreshAddresses();
            UpdateFooter();

            if (_server.IsRunning)
                ResetConnectionStatus();

            ShowToast(L10n.Get("ui.toast.saved"), ToastKind.Success);

            if (languageChanged)
                PromptLanguageRestart();
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось сохранить настройки", ex);
            ShowSettingsError(L10n.S("ui.error.save.format", ex.Message));
        }
        finally
        {
            _savingSettings = false;
            SaveSettingsButton.IsEnabled = true;
            SaveSettingsButton.Content = L10n.Get("ui.action.save");
        }
    }

    private void ShowSettingsError(string message)
    {
        SettingsError.Text = message;
        SettingsError.Visibility = Visibility.Visible;
        SettingsError.BringIntoView();
    }

    private void HideSettingsError()
    {
        SettingsError.Text = string.Empty;
        SettingsError.Visibility = Visibility.Collapsed;
    }

    private void PortBox_PreviewTextInput(object sender, TextCompositionEventArgs e)
    {
        e.Handled = e.Text.Any(ch => !char.IsDigit(ch));
    }

    private void PortBox_OnPaste(object sender, DataObjectPastingEventArgs e)
    {
        if (e.DataObject.GetDataPresent(DataFormats.Text))
        {
            var text = e.DataObject.GetData(DataFormats.Text) as string ?? string.Empty;
            if (text.All(char.IsDigit))
                return;
        }

        e.CancelCommand();
    }

    // ===================== О приложении и брандмауэр =====================

    private bool _aboutVisible;
    private bool _firewallChecking;
    private bool _firewallFixing;
    private IInputElement? _aboutReturnFocus;

    private void AboutButton_Click(object sender, RoutedEventArgs e)
    {
        if (_aboutVisible) return;
        if (_settingsVisible) HideSettings();

        _aboutVisible = true;
        _aboutReturnFocus = Keyboard.FocusedElement;

        TitleBar.IsEnabled = false;
        MainContent.IsEnabled = false;
        FooterBar.IsEnabled = false;

        AboutVersionText.Text = L10n.S("ui.version.format",
            Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.2.1");

        AboutOverlay.Visibility = Visibility.Visible;
        AboutOverlay.Opacity = 0;
        AboutCardScale.ScaleX = 0.985;
        AboutCardScale.ScaleY = 0.985;

        AboutOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(140)));
        var scaleAnimation = new DoubleAnimation(0.985, 1, TimeSpan.FromMilliseconds(180))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        AboutCardScale.BeginAnimation(ScaleTransform.ScaleXProperty, scaleAnimation);
        AboutCardScale.BeginAnimation(ScaleTransform.ScaleYProperty, scaleAnimation);

        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            AboutCard.Focus();
        }));
    }

    private void HideAbout()
    {
        if (!_aboutVisible) return;

        _aboutVisible = false;
        AboutOverlay.Visibility = Visibility.Collapsed;
        AboutOverlay.BeginAnimation(OpacityProperty, null);
        AboutCardScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        AboutCardScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);

        TitleBar.IsEnabled = true;
        MainContent.IsEnabled = true;
        FooterBar.IsEnabled = true;

        var returnFocus = _aboutReturnFocus;
        _aboutReturnFocus = null;
        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            if (returnFocus is UIElement element && element.IsVisible && element.IsEnabled)
                element.Focus();
            else
                AboutButton.Focus();
        }));
    }

    private void CloseAbout_Click(object sender, RoutedEventArgs e) => HideAbout();

    private void AboutOverlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (!AboutCard.IsMouseOver)
            HideAbout();
    }

    private void OpenAuthorTelegram_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo("https://t.me/LoioshS")
            {
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось открыть Telegram автора", ex);
            ShowToast(L10n.Get("ui.toast.author.link"), ToastKind.Info);
        }
    }

    private async Task CheckFirewallAsync()
    {
        if (_firewallChecking || _firewallFixing) return;
        _firewallChecking = true;

        FirewallButton.IsEnabled = false;
        FirewallStatusText.Text = L10n.Get("ui.firewall.checking");

        try
        {
            var status = await Task.Run(() => FirewallHelper.CheckAsync(_server.Port).GetAwaiter().GetResult());
            ApplyFirewallStatus(status);
        }
        finally
        {
            _firewallChecking = false;
            FirewallButton.IsEnabled = true;
        }
    }

    private void ApplyFirewallStatus(FirewallHelper.Status status)
    {
        FirewallStatusText.Text = status.Message;
        FirewallStatusText.SetResourceReference(TextBlock.ForegroundProperty,
            status.Allowed ? "SuccessBrush" : "WarningBrush");
        FirewallButton.Visibility = status.Allowed
            ? Visibility.Collapsed
            : Visibility.Visible;
        FirewallButton.Content = status.Allowed
            ? L10n.Get("ui.firewall.allowed")
            : L10n.Get("ui.firewall.button");
    }

    private async void FirewallButton_Click(object sender, RoutedEventArgs e)
    {
        if (_firewallFixing) return;

        var source = (Button)sender;
        _firewallFixing = true;
        source.IsEnabled = false;

        FirewallStatusText.Text = L10n.Get("ui.firewall.confirming");
        FirewallStatusText.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");

        try
        {
            var status = await FirewallHelper.EnsureAllowedAsync(_server.Port);
            ApplyFirewallStatus(status);
            ShowToast(status.Allowed ? L10n.Get("ui.toast.firewall.ok") : L10n.Get("ui.toast.firewall.fail"),
                status.Allowed ? ToastKind.Success : ToastKind.Error);

            if (status.Allowed && _sendVisible && _foundDevices.Count == 0)
            {
                FindDevicesButton.IsEnabled = true;
                FindDevicesAsync_Click(FindDevicesButton, new RoutedEventArgs());
            }
        }
        finally
        {
            _firewallFixing = false;
            source.IsEnabled = true;
            FirewallButton.IsEnabled = true;
        }
    }

    // ===================== Отправка на телефон =====================

    private void SendToPhoneButton_Click(object sender, RoutedEventArgs e)
    {
        if (_sendVisible) return;
        if (_sendPhase == SendPhase.Done) ResetSendPanel();
        OpenSend();
    }

    private void OpenSend()
    {
        if (_sendVisible) return;

        _sendVisible = true;
        _sendReturnFocus = Keyboard.FocusedElement;
        SendFirewallCard.Visibility = Visibility.Collapsed;

        TitleBar.IsEnabled = false;
        MainContent.IsEnabled = false;
        FooterBar.IsEnabled = false;
        SendActionButton.IsDefault = _sendPhase == SendPhase.Idle;

        SendOverlay.Visibility = Visibility.Visible;
        SendOverlay.Opacity = 0;
        SendCardScale.ScaleX = 0.985;
        SendCardScale.ScaleY = 0.985;

        SendOverlay.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(140)));
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var scaleAnimation = new DoubleAnimation(0.985, 1, TimeSpan.FromMilliseconds(180))
        {
            EasingFunction = ease,
        };
        SendCardScale.BeginAnimation(ScaleTransform.ScaleXProperty, scaleAnimation);
        SendCardScale.BeginAnimation(ScaleTransform.ScaleYProperty, scaleAnimation);

        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            if (SendAddressBox.Text.Length == 0) SendAddressBox.Focus();
            else SendPinBox.Focus();
        }));
    }

    private void HideSend()
    {
        if (!_sendVisible) return;

        _sendVisible = false;
        SendActionButton.IsDefault = false;
        SendOverlay.Visibility = Visibility.Collapsed;
        SendOverlay.BeginAnimation(OpacityProperty, null);
        SendCardScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        SendCardScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);

        TitleBar.IsEnabled = true;
        MainContent.IsEnabled = true;
        FooterBar.IsEnabled = true;

        var returnFocus = _sendReturnFocus;
        _sendReturnFocus = null;
        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            if (returnFocus is UIElement element && element.IsVisible && element.IsEnabled)
                element.Focus();
            else
                SendToPhoneButton.Focus();
        }));
    }

    private void CloseSend_Click(object sender, RoutedEventArgs e) => HideSend();

    private void SendOverlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (!SendCard.IsMouseOver)
            HideSend();
    }

    private async void FindDevicesAsync_Click(object sender, RoutedEventArgs e)
    {
        var button = (Button)sender;
        if (_sendPhase != SendPhase.Idle || !button.IsEnabled) return;

        button.IsEnabled = false;
        SendFoundLabel.Text = L10n.Get("ui.search.searching");
        SendFoundLabel.Visibility = Visibility.Visible;
        SendDevicesList.Visibility = Visibility.Collapsed;

        try
        {
            var devices = await Task.Run(() => PhoneDiscovery.DiscoverAsync(2600));

            _foundDevices.Clear();
            var current = NormalizeEndpoint(SendAddressBox.Text);
            foreach (var device in devices)
            {
                var selected = current.Length > 0 &&
                               current == $"{device.Host}:{device.Port}";
                _foundDevices.Add(new SendDeviceViewModel(device, selected));
            }

            if (_foundDevices.Count == 0)
            {
                SendFoundLabel.Text = L10n.Get("ui.search.notfound");
                SendDevicesList.Visibility = Visibility.Collapsed;
                SendFirewallCard.Visibility = Visibility.Visible;
            }
            else
            {
                SendFoundLabel.Text = L10n.S("ui.search.found.format", _foundDevices.Count);
                SendDevicesList.Visibility = Visibility.Visible;
                SendFirewallCard.Visibility = Visibility.Collapsed;
            }
        }
        catch (Exception ex)
        {
            ShowSendError(L10n.S("ui.error.discover.format", Shorten(ex.Message, 100)));
        }
        finally
        {
            button.IsEnabled = true;
        }
    }

    private void SendDevice_Click(object sender, RoutedEventArgs e)
    {
        if (_sendPhase != SendPhase.Idle) return;
        if (sender is not FrameworkElement { DataContext: SendDeviceViewModel selected }) return;

        SendAddressBox.Text = selected.Device.Endpoint;
        SendAddressBox.CaretIndex = SendAddressBox.Text.Length;

        var endpoint = selected.Device.Endpoint;
        var refreshed = _foundDevices
            .Select(vm => new SendDeviceViewModel(vm.Device,
                vm.Device.Endpoint == endpoint))
            .ToList();
        _foundDevices.Clear();
        foreach (var vm in refreshed) _foundDevices.Add(vm);

        HideSendError();
        SendPinBox.Focus();
    }

    private void SendAddressBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        // A pasted QR payload ("firedrop://send?host=…&port=…&pin=…") fills both fields.
        var text = SendAddressBox.Text;
        if (text.StartsWith("firedrop:", StringComparison.OrdinalIgnoreCase))
        {
            var link = PhoneSender.ParseLink(text);
            if (link.IsLink)
            {
                var pin = SendPinBox.Text.Trim();
                if (link.Pin.Length > 0 && pin.Length == 0) SendPinBox.Text = link.Pin;
                var address = $"{link.Host}:{link.Port}";
                if (SendAddressBox.Text != address)
                {
                    SendAddressBox.Text = address;
                    SendAddressBox.CaretIndex = address.Length;
                    return;
                }
            }
        }

        HideSendError();
    }

    private void SendPinBox_PreviewTextInput(object sender, TextCompositionEventArgs e)
    {
        e.Handled = e.Text.Any(ch => !char.IsDigit(ch));
    }

    private void SendPinBox_OnPaste(object sender, DataObjectPastingEventArgs e)
    {
        if (e.DataObject.GetDataPresent(DataFormats.Text))
        {
            var text = e.DataObject.GetData(DataFormats.Text) as string ?? string.Empty;
            if (text.All(char.IsDigit)) return;
        }

        e.CancelCommand();
    }

    private void PickSendFiles_Click(object sender, RoutedEventArgs e)
    {
        if (_sendPhase != SendPhase.Idle) return;

        try
        {
            using var dialog = new Forms.OpenFileDialog
            {
                Title = L10n.Get("ui.dialog.sendfiles"),
                Multiselect = true,
                CheckFileExists = true,
            };
            if (dialog.ShowDialog(new WindowHandleOwner(new WindowInteropHelper(this).Handle)) != Forms.DialogResult.OK) return;

            foreach (var file in dialog.FileNames)
            {
                if (_sendQueue.Any(vm => string.Equals(vm.FullPath, file, StringComparison.OrdinalIgnoreCase)))
                {
                    continue;
                }

                EnqueueFile(file);
            }
            RefreshSendQueue();
        }
        catch (Exception ex)
        {
            ShowSendError(L10n.S("ui.error.pickfiles.format", Shorten(ex.Message, 100)));
        }
    }

    private void PickSendFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_sendPhase != SendPhase.Idle) return;

        try
        {
            using var dialog = new Forms.FolderBrowserDialog
            {
                Description = L10n.Get("ui.dialog.sendfolder"),
                UseDescriptionForTitle = true,
                ShowNewFolderButton = false,
                SelectedPath = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            };
            if (dialog.ShowDialog(new WindowHandleOwner(new WindowInteropHelper(this).Handle)) != Forms.DialogResult.OK) return;

            EnqueueFolder(dialog.SelectedPath);
            RefreshSendQueue();
        }
        catch (Exception ex)
        {
            ShowSendError(L10n.S("ui.error.pickfolder.format", Shorten(ex.Message, 100)));
        }
    }

    private void SendClear_Click(object sender, RoutedEventArgs e)
    {
        if (_sendPhase != SendPhase.Idle) return;
        _sendQueue.Clear();
        RefreshSendQueue();
    }

    private void EnqueueFile(string path)
    {
        if (!File.Exists(path)) return;

        var name = Path.GetFileName(path);
        if (string.IsNullOrWhiteSpace(name)) name = L10n.Get("ui.send.fallback.file");

        foreach (var existing in _sendQueue)
        {
            if (string.Equals(existing.FullPath, path, StringComparison.OrdinalIgnoreCase)) return;
        }

        _sendQueue.Add(new SendItemViewModel(path, name, string.Empty));
    }

    private void EnqueueFolder(string path)
    {
        if (!Directory.Exists(path)) return;

        var root = Path.GetFileName(path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        if (string.IsNullOrWhiteSpace(root)) root = L10n.Get("ui.send.fallback.folder");

        var options = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            RecurseSubdirectories = true,
            AttributesToSkip = FileAttributes.Hidden | FileAttributes.System,
        };

        foreach (var file in Directory.EnumerateFiles(path, "*", options))
        {
            string relative;
            try { relative = Path.GetRelativePath(path, file); }
            catch { continue; }

            var name = Path.GetFileName(relative);
            var parent = Path.GetDirectoryName(relative)?.Replace('\\', '/') ?? string.Empty;
            var combined = parent.Length > 0 ? parent + "/" + name : name;

            if (string.IsNullOrEmpty(name)) continue;
            // The receiver composes "relativePath/name"; mirror its sanity caps.
            if (combined.Length > 118 || combined.Split('/').Length > 12) continue;

            if (_sendQueue.Any(vm => string.Equals(vm.FullPath, file, StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            _sendQueue.Add(new SendItemViewModel(
                file,
                name,
                parent.Length == 0 ? root : root + "/" + parent));
        }
    }

    private void RefreshSendQueue()
    {
        long total = _sendQueue.Sum(vm => vm.Bytes);
        if (_sendQueue.Count == 0)
        {
            SendFilesCard.Visibility = Visibility.Collapsed;
            SendQueueText.Text = L10n.Get("ui.sendqueue.empty");
        }
        else
        {
            SendFilesCard.Visibility = Visibility.Visible;
            SendQueueText.Text = L10n.S("ui.sendqueue.format",
                _sendQueue.Count,
                Plural(_sendQueue.Count, L10n.Get("ui.plural.file.one"), L10n.Get("ui.plural.file.few"), L10n.Get("ui.plural.file.many")),
                FileItemViewModel.FormatBytes(total));
        }

        SendClearButton.IsEnabled = _sendQueue.Count > 0 && _sendPhase == SendPhase.Idle;
    }

    private async void SendAction_Click(object sender, RoutedEventArgs e)
    {
        if (_sender is { Finished: false }) return;
        if (_sendPhase == SendPhase.Done)
        {
            ResetSendPanel();
            HideSend();
            return;
        }

        HideSendError();
        var address = SendAddressBox.Text.Trim();
        var link = PhoneSender.ParseLink(address);
        if (link.IsLink)
        {
            address = $"{link.Host}:{link.Port}";
            if (SendPinBox.Text.Trim().Length == 0 && link.Pin.Length > 0) SendPinBox.Text = link.Pin;
        }

        var pin = SendPinBox.Text.Trim();
        if (address.Length == 0 || PhoneSender.ParseAddress(address).Host.Length == 0)
        {
            ShowSendError(L10n.Get("ui.error.recipient.required"));
            return;
        }

        if (pin.Length != 6 || pin.Any(ch => !char.IsDigit(ch)))
        {
            ShowSendError(L10n.Get("ui.error.pin.required"));
            return;
        }

        if (_sendQueue.Count == 0)
        {
            ShowSendError(L10n.Get("ui.error.nothing.selected"));
            return;
        }

        var queued = _sendQueue
            .Select(vm => (vm, new PhoneSender.Item(vm.FullPath, vm.Name, vm.RelativePath)))
            .ToList();
        var items = queued.Select(entry => entry.Item2).ToList();
        var folders = items
            .Select(item => item.RelativePath)
            .Where(path => path.Length > 0)
            .Distinct(StringComparer.Ordinal)
            .ToList();

        _sendPhase = SendPhase.Sending;
        SetSendInputs(false);
        SendActionButton.IsEnabled = false;
        SendActionButton.IsDefault = false;
        SendActionButton.Content = L10n.Get("ui.send.progressing");
        SendCancelButton.Visibility = Visibility.Visible;
        SendCancelButton.IsEnabled = true;
        SendProgressPanel.Visibility = Visibility.Visible;
        SendProgressTitle.Text = L10n.Get("ui.send.connecting");
        SendProgressHint.Text = string.Empty;
        SendProgressBar.Value = 0;

        _sendCts = new CancellationTokenSource();
        var token = _sendCts.Token;

        try
        {
            var phoneSender = await Task.Run(() => new PhoneSender(address, pin, items, folders));
            if (token.IsCancellationRequested)
            {
                phoneSender.Dispose();
                return;
            }

            _sender = phoneSender;
            foreach (var (vm, item) in queued) vm.Bind(item);

            _ = Task.Run(() => phoneSender.RunAsync(token));
            _lastSentBytes = 0;
            _lastSampleMs = 0;
            _sendTimer.Start();
        }
        catch (Exception ex)
        {
            ResetSendPanel();
            ShowSendError(L10n.S("ui.error.startsend.format", Shorten(ex.Message, 100)));
        }
    }

    private void RefreshSendProgress()
    {
        var sender = _sender;
        if (sender is null) return;

        foreach (var vm in _sendQueue) vm.Refresh();

        if (sender.Finished)
        {
            if (_sendPhase != SendPhase.Done) FinishSend(sender);
            return;
        }

        var total = sender.TotalBytes;
        var sent = sender.SentBytes;
        if (total > 0) SendProgressBar.Value = Math.Min(100, sent * 100.0 / total);

        var running = sender.Items.FirstOrDefault(item => item.State == PhoneSender.FileRunning);
        var index = sender.CompletedCount + sender.FailedCount + (running is null ? 0 : 1);
        SendProgressTitle.Text = running is not null
            ? L10n.S("ui.send.progress.item.format", index, sender.Items.Count, running.Name)
            : L10n.S("ui.send.progress.format", index, sender.Items.Count);

        var now = Environment.TickCount64;
        if (_lastSampleMs > 0)
        {
            var seconds = (now - _lastSampleMs) / 1000.0;
            if (seconds > 0.05)
            {
                var speed = Math.Max(0, sent - _lastSentBytes) / seconds;
                SendProgressHint.Text = L10n.S("ui.send.hint.format",
                    FileItemViewModel.FormatSpeed(speed),
                    FileItemViewModel.FormatBytes(Math.Max(0, sent)),
                    FileItemViewModel.FormatBytes(total));
                _lastSentBytes = sent;
                _lastSampleMs = now;
            }
        }
        else
        {
            _lastSentBytes = sent;
            _lastSampleMs = now;
        }
    }

    private void FinishSend(PhoneSender sender)
    {
        _sendTimer.Stop();
        _sendPhase = SendPhase.Done;

        SendCancelButton.Visibility = Visibility.Collapsed;
        SendCancelButton.IsEnabled = true;
        SendActionButton.IsEnabled = true;
        SendActionButton.IsDefault = true;
        SendActionButton.Content = L10n.Get("ui.action.done");
        SetSendInputs(true);
        RefreshSendQueue();
        SendProgressPanel.Visibility = Visibility.Visible;

        foreach (var vm in _sendQueue) vm.Refresh();

        if (sender.Cancelled)
        {
            SendProgressBar.Value = 0;
            SendProgressTitle.Text = L10n.Get("ui.toast.receiving.cancelled");
            SendProgressHint.Text = sender.FailedCount > 0
                ? L10n.S("ui.send.failed.part.format",
                    sender.FailedCount,
                    Plural(sender.FailedCount, L10n.Get("ui.plural.file.one"), L10n.Get("ui.plural.file.few"), L10n.Get("ui.plural.file.many")))
                : string.Empty;
        }
        else if (sender.FailedCount > 0)
        {
            SendProgressTitle.Text = L10n.S("ui.send.failed.batch.format",
                sender.FailedCount,
                Plural(sender.FailedCount, L10n.Get("ui.plural.file.one"), L10n.Get("ui.plural.file.few"), L10n.Get("ui.plural.file.many")));
            SendProgressHint.Text = L10n.S("ui.send.completed.line.format",
                sender.CompletedCount,
                Plural(sender.CompletedCount, L10n.Get("ui.plural.file.one"), L10n.Get("ui.plural.file.few"), L10n.Get("ui.plural.file.many")));
            ShowSendError(Shorten(string.Join("; ", sender.FailedItems
                .Take(3)
                .Select(item => $"{item.Name} — {item.Error}")), 220));
            ShowToast(L10n.Get("ui.toast.send.errors"), ToastKind.Error);
        }
        else
        {
            SendProgressBar.Value = 100;
            SendProgressTitle.Text = L10n.S("ui.send.completed.format",
                sender.CompletedCount,
                Plural(sender.CompletedCount, L10n.Get("ui.plural.file.one"), L10n.Get("ui.plural.file.few"), L10n.Get("ui.plural.file.many")));
            var seconds = Math.Max(1, (sender.FinishedAt - sender.StartedAt) / 1000.0);
            SendProgressHint.Text = sender.SentBytes > 0
                ? $"{FileItemViewModel.FormatBytes(sender.SentBytes)} · {FileItemViewModel.FormatSpeed(sender.SentBytes / seconds)}"
                : string.Empty;
            ShowToast(L10n.S("ui.toast.send.target.format", sender.Target), ToastKind.Success);
        }
    }

    private void SendCancel_Click(object sender, RoutedEventArgs e)
    {
        if (_sender is not { Finished: false }) return;

        _sender.Cancel();
        try { _sendCts?.Cancel(); } catch { }

        SendCancelButton.IsEnabled = false;
        SendProgressTitle.Text = L10n.Get("ui.send.stopping");
    }

    private void ResetSendPanel()
    {
        _sendTimer.Stop();
        try { _sender?.Dispose(); } catch { }
        _sender = null;
        try { _sendCts?.Dispose(); } catch { }
        _sendCts = null;
        _sendPhase = SendPhase.Idle;

        foreach (var vm in _sendQueue) vm.Reset();

        SendProgressPanel.Visibility = Visibility.Collapsed;
        SendProgressTitle.Text = L10n.Get("ui.send.progressing");
        SendProgressHint.Text = string.Empty;
        SendProgressBar.Value = 0;
        SendCancelButton.Visibility = Visibility.Collapsed;
        SendCancelButton.IsEnabled = true;
        SetSendInputs(true);
        SendActionButton.IsEnabled = true;
        SendActionButton.Content = L10n.Get("ui.action.send");
        SendActionButton.IsDefault = true;
        HideSendError();
    }

    private void SetSendInputs(bool enabled)
    {
        SendAddressBox.IsEnabled = enabled;
        SendPinBox.IsEnabled = enabled;
        FindDevicesButton.IsEnabled = enabled;
        PickSendFilesButton.IsEnabled = enabled;
        PickSendFolderButton.IsEnabled = enabled;
        SendClearButton.IsEnabled = enabled && _sendQueue.Count > 0;
    }

    private void ShowSendError(string message)
    {
        SendError.Text = message;
        SendError.Visibility = Visibility.Visible;
    }

    private void HideSendError()
    {
        if (SendError.Visibility == Visibility.Visible)
        {
            SendError.Text = string.Empty;
            SendError.Visibility = Visibility.Collapsed;
        }
    }

    private static string NormalizeEndpoint(string text)
    {
        var (host, port) = PhoneSender.ParseAddress(text);
        return host.Length == 0 ? string.Empty : $"{host}:{port}";
    }

    private enum SendPhase
    {
        Idle,
        Sending,
        Done,
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is DependencyObject source && IsInteractive(source))
            return;

        if (e.ClickCount == 2)
        {
            ToggleMaximize();
            return;
        }

        if (e.ButtonState == MouseButtonState.Pressed)
        {
            try { DragMove(); } catch { }
        }
    }

    private static bool IsInteractive(DependencyObject source)
    {
        var current = source;
        for (var depth = 0; current is not null && depth < 40; depth++)
        {
            if (current is ButtonBase || current is TextBoxBase || current is ComboBox || current is ScrollBar)
                return true;

            current = current is Visual or System.Windows.Media.Media3D.Visual3D
                ? VisualTreeHelper.GetParent(current)
                : LogicalTreeHelper.GetParent(current);
        }

        return false;
    }

    private void Minimize_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }

    private void Maximize_Click(object sender, RoutedEventArgs e)
    {
        ToggleMaximize();
    }

    private void ToggleMaximize()
    {
        WindowState = WindowState == WindowState.Maximized
            ? WindowState.Normal
            : WindowState.Maximized;
    }

    private void CloseWindow_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        if (MaximizeIcon is null) return;

        var maximized = WindowState == WindowState.Maximized;
        MaximizeIcon.Data = Geometry.Parse(maximized
            ? "M8 8h9v9H8z M5 5h9v9H5z"
            : "M4 4h16v16H4z");
        MaximizeButton.ToolTip = maximized ? L10n.Get("ui.restore.tooltip") : L10n.Get("ui.maximize.tooltip");
        AutomationProperties.SetName(MaximizeButton, maximized ? L10n.Get("ui.restore.automation") : L10n.Get("ui.maximize.automation"));
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Escape) return;

        if (_settingsVisible)
        {
            HideSettings();
            e.Handled = true;
            return;
        }

        if (_aboutVisible)
        {
            HideAbout();
            e.Handled = true;
            return;
        }

        if (_sendVisible)
        {
            HideSend();
            e.Handled = true;
        }
    }

    private async void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_allowClose) return;

        if (!_shuttingDown && !_forceExit && _settings.MinimizeToTrayOnClose)
        {
            e.Cancel = true;
            Hide();
            ShowTrayHint();
            return;
        }

        e.Cancel = true;
        if (_shuttingDown) return;

        await ShutdownAsync();
        _allowClose = true;
        Application.Current?.Shutdown();
    }

    private async Task ShutdownAsync()
    {
        if (_shuttingDown) return;
        _shuttingDown = true;

        _statsTimer.Stop();
        _activityTimer.Stop();
        _networkTimer.Stop();
        _toastTimer.Stop();
        _sendTimer.Stop();

        try { _sender?.Cancel(); } catch { }
        try { _sendCts?.Cancel(); } catch { }

        NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;

        try
        {
            await _serverGate.WaitAsync();
            try
            {
                await _server.StopAsync();
            }
            finally
            {
                _serverGate.Release();
            }

            await _uploads.DisposeAsync();
        }
        catch (Exception ex)
        {
            AppLog.Error("Ошибка при завершении FireDrop", ex);
        }
        finally
        {
            DisposeTrayIcon();
        }
    }

    private async void ExitFromTray()
    {
        _forceExit = true;

        if (IsVisible)
        {
            Close();
            return;
        }

        await ShutdownAsync();
        _allowClose = true;
        Application.Current?.Shutdown();
    }

    private void CreateTrayIcon()
    {
        try
        {
            var executable = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(executable))
            {
                try { _trayDrawingIcon = Drawing.Icon.ExtractAssociatedIcon(executable); }
                catch { }
            }

            var textColor = Drawing.Color.FromArgb(241, 245, 255);
            _trayMenu = new Forms.ContextMenuStrip
            {
                ShowImageMargin = false,
                BackColor = Drawing.Color.FromArgb(13, 23, 40),
                ForeColor = textColor,
                Font = new Drawing.Font("Segoe UI", 9f, Drawing.FontStyle.Regular),
                Renderer = new Forms.ToolStripProfessionalRenderer(new TrayColorTable()),
            };

            var openItem = new Forms.ToolStripMenuItem(L10n.Get("ui.tray.open")) { ForeColor = textColor };
            openItem.Click += (_, _) => Dispatcher.BeginInvoke(new Action(ShowFromTray));

            var copyItem = new Forms.ToolStripMenuItem(L10n.Get("ui.tray.copy")) { ForeColor = textColor };
            copyItem.Click += (_, _) => Dispatcher.BeginInvoke(new Action(CopyCurrentLink));

            var folderItem = new Forms.ToolStripMenuItem(L10n.Get("ui.tray.folder")) { ForeColor = textColor };
            folderItem.Click += (_, _) => Dispatcher.BeginInvoke(new Action(() => OpenFolder(_uploads.SaveFolder)));

            var exitItem = new Forms.ToolStripMenuItem(L10n.Get("ui.tray.exit")) { ForeColor = textColor };
            exitItem.Click += (_, _) => Dispatcher.BeginInvoke(new Action(ExitFromTray));

            _trayMenu.Items.Add(openItem);
            _trayMenu.Items.Add(copyItem);
            _trayMenu.Items.Add(folderItem);
            _trayMenu.Items.Add(new Forms.ToolStripSeparator());
            _trayMenu.Items.Add(exitItem);

            _trayIcon = new Forms.NotifyIcon
            {
                Icon = _trayDrawingIcon ?? Drawing.SystemIcons.Application,
                Text = L10n.Get("ui.window.title"),
                Visible = true,
                ContextMenuStrip = _trayMenu,
            };
            _trayIcon.DoubleClick += (_, _) => Dispatcher.BeginInvoke(new Action(ShowFromTray));
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось создать значок в трее", ex);
            DisposeTrayIcon();
        }
    }

    private void ShowTrayHint()
    {
        if (_trayHintShown || _trayIcon is null) return;
        _trayHintShown = true;

        _trayIcon.BalloonTipTitle = L10n.Get("ui.tray.hint.title");
        _trayIcon.BalloonTipText = L10n.Get("ui.tray.hint.text");
        _trayIcon.BalloonTipIcon = Forms.ToolTipIcon.Info;
        _trayIcon.ShowBalloonTip(3000);
    }

    private void ShowTrayFileNotification(TransferInfo info)
    {
        if (_trayIcon is null) return;

        _trayIcon.BalloonTipTitle = L10n.Get("ui.tray.file.title");
        _trayIcon.BalloonTipText =
            $"{Shorten(info.FileName, 52)} · {FileItemViewModel.FormatBytes(info.Size)}";
        _trayIcon.BalloonTipIcon = Forms.ToolTipIcon.Info;
        _trayIcon.ShowBalloonTip(2600);
    }

    private void DisposeTrayIcon()
    {
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _trayIcon = null;
        }

        _trayMenu?.Dispose();
        _trayMenu = null;

        _trayDrawingIcon?.Dispose();
        _trayDrawingIcon = null;
    }

    private void ShowToast(string message, ToastKind kind, int durationMilliseconds = 2800)
    {
        if (_shuttingDown || !_initialized) return;

        var (background, border, iconBackground, iconColor) = kind switch
        {
            ToastKind.Success => (
                Color.FromRgb(16, 42, 34),
                Color.FromRgb(42, 107, 83),
                Color.FromRgb(27, 90, 69),
                SuccessColor),
            ToastKind.Error => (
                Color.FromRgb(42, 18, 26),
                Color.FromRgb(110, 43, 60),
                Color.FromRgb(74, 27, 42),
                DangerColor),
            _ => (
                Color.FromRgb(14, 34, 48),
                Color.FromRgb(36, 81, 104),
                Color.FromRgb(23, 61, 82),
                AccentColor),
        };

        ToastText.Text = message;
        Toast.Background = Frozen(background);
        Toast.BorderBrush = Frozen(border);
        ToastIconHost.Background = Frozen(iconBackground);
        ToastIcon.Stroke = Frozen(iconColor);
        ToastIcon.Data = Geometry.Parse(kind switch
        {
            ToastKind.Success => "M4 12.5l5 5L20 6.5",
            ToastKind.Error => "M12 4 2.8 20h18.4L12 4Zm0 6v5m0 3v.4",
            _ => "M12 8v5m0 3.5v.5M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z",
        });

        Toast.BeginAnimation(OpacityProperty, null);
        ToastTransform.BeginAnimation(TranslateTransform.YProperty, null);
        Toast.Visibility = Visibility.Visible;
        Toast.Opacity = 0;
        ToastTransform.Y = 12;

        Toast.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(150)));
        ToastTransform.BeginAnimation(
            TranslateTransform.YProperty,
            new DoubleAnimation(12, 0, TimeSpan.FromMilliseconds(180))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
            });

        _toastTimer.Stop();
        _toastTimer.Interval = TimeSpan.FromMilliseconds(durationMilliseconds);
        _toastTimer.Start();
    }

    private void HideToast()
    {
        _toastTimer.Stop();
        if (Toast.Visibility != Visibility.Visible) return;

        var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(180));
        fade.Completed += (_, _) =>
        {
            Toast.Visibility = Visibility.Collapsed;
            Toast.BeginAnimation(OpacityProperty, null);
            ToastTransform.BeginAnimation(TranslateTransform.YProperty, null);
            Toast.Opacity = 1;
            ToastTransform.Y = 0;
        };
        Toast.BeginAnimation(OpacityProperty, fade);
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        RunOnUi(() =>
        {
            if (_shuttingDown) return;
            _networkTimer.Stop();
            _networkTimer.Start();
        });
    }

    private void RunOnUi(Action action)
    {
        if (_shuttingDown || Dispatcher.HasShutdownStarted) return;

        if (Dispatcher.CheckAccess())
            action();
        else
            Dispatcher.BeginInvoke(DispatcherPriority.Background, action);
    }

    private static SolidColorBrush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private static string Shorten(string text, int limit)
    {
        if (string.IsNullOrEmpty(text) || text.Length <= limit) return text;
        return text[..(limit - 1)] + "…";
    }

    private static string Plural(int count, string one, string few, string many)
    {
        var mod100 = count % 100;
        if (mod100 is >= 11 and <= 14) return many;
        return (count % 10) switch
        {
            1 => one,
            2 or 3 or 4 => few,
            _ => many,
        };
    }

    private enum ToastKind
    {
        Success,
        Error,
        Info,
    }

    private sealed class WindowHandleOwner : Forms.IWin32Window
    {
        public WindowHandleOwner(IntPtr handle) => Handle = handle;
        public IntPtr Handle { get; }
    }

    private sealed class TrayColorTable : Forms.ProfessionalColorTable
    {
        private static readonly Drawing.Color Background = Drawing.Color.FromArgb(13, 23, 40);
        private static readonly Drawing.Color Border = Drawing.Color.FromArgb(36, 54, 83);
        private static readonly Drawing.Color Hover = Drawing.Color.FromArgb(26, 41, 66);

        public override Drawing.Color ToolStripDropDownBackground => Background;
        public override Drawing.Color ImageMarginGradientBegin => Background;
        public override Drawing.Color ImageMarginGradientMiddle => Background;
        public override Drawing.Color ImageMarginGradientEnd => Background;
        public override Drawing.Color MenuBorder => Border;
        public override Drawing.Color MenuItemBorder => Hover;
        public override Drawing.Color MenuItemSelected => Hover;
        public override Drawing.Color MenuItemSelectedGradientBegin => Hover;
        public override Drawing.Color MenuItemSelectedGradientEnd => Hover;
        public override Drawing.Color SeparatorDark => Border;
        public override Drawing.Color SeparatorLight => Border;
    }
}
