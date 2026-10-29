using System.Threading;
using System.Windows;
using System.Windows.Threading;
using FireDrop.Core;

namespace FireDrop;

public partial class App : Application
{
    private const string MutexName = @"Local\FireDrop.SingleInstance";
    private const string ActivateEventName = @"Local\FireDrop.Activate";

    private Mutex? _instanceMutex;
    private EventWaitHandle? _activateEvent;
    private RegisteredWaitHandle? _activationWait;
    private MainWindow? _mainWindow;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnExplicitShutdown;

        if (!ClaimSingleInstance())
        {
            SignalExistingInstance();
            Shutdown();
            return;
        }

        var startHidden = e.Args.Any(arg =>
            arg.Equals("--autostart", StringComparison.OrdinalIgnoreCase) ||
            arg.Equals("--minimized", StringComparison.OrdinalIgnoreCase));

        try
        {
            var settings = AppSettings.Load();
            L10n.Configure(settings.Language);
            _mainWindow = new MainWindow(settings);
            MainWindow = _mainWindow;

            var running = await _mainWindow.InitializeAsync();

            if (!startHidden || !running || !_mainWindow.HasTrayIcon)
            {
                _mainWindow.Show();
                _mainWindow.Activate();
            }

            AppLog.Info(startHidden
                ? "FireDrop запущен в трее"
                : "FireDrop запущен");
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось запустить FireDrop", ex);
            MessageBox.Show(
                $"Не удалось запустить FireDrop.{Environment.NewLine}{Environment.NewLine}{ex.Message}",
                "FireDrop",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown();
        }
    }

    private bool ClaimSingleInstance()
    {
        try
        {
            _instanceMutex = new Mutex(initiallyOwned: true, MutexName, out var createdNew);
            if (!createdNew)
            {
                _instanceMutex.Dispose();
                _instanceMutex = null;
                return false;
            }

            _activateEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ActivateEventName);
            _activationWait = ThreadPool.RegisterWaitForSingleObject(
                _activateEvent,
                OnActivateSignal,
                null,
                Timeout.Infinite,
                executeOnlyOnce: false);

            return true;
        }
        catch (Exception ex)
        {
            // A named-object failure must not prevent the app from starting.
            AppLog.Error("Не удалось проверить единственный экземпляр приложения", ex);
            return true;
        }
    }

    private static void SignalExistingInstance()
    {
        try
        {
            using var activate = new EventWaitHandle(false, EventResetMode.AutoReset, ActivateEventName);
            activate.Set();
        }
        catch
        {
            // The first instance may be closing; nothing else to do.
        }
    }

    private void OnActivateSignal(object? state, bool timedOut)
    {
        if (timedOut) return;

        try
        {
            Dispatcher.BeginInvoke(DispatcherPriority.Send, new Action(() =>
            {
                if (_mainWindow is null) return;
                _mainWindow.ShowFromTray();
            }));
        }
        catch
        {
            // The dispatcher is already shutting down.
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _activationWait?.Unregister(null);
        _activateEvent?.Dispose();

        if (_instanceMutex is not null)
        {
            try { _instanceMutex.ReleaseMutex(); } catch { }
            _instanceMutex.Dispose();
            _instanceMutex = null;
        }

        base.OnExit(e);
    }
}
