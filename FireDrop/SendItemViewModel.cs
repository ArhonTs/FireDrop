using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Media;
using FireDrop.Core;

namespace FireDrop.ViewModels;

/// <summary>One row in the send queue of the "Отправить на телефон" panel.</summary>
public sealed class SendItemViewModel : INotifyPropertyChanged
{
    private static readonly Brush MutedBrush = Frozen(Color.FromRgb(148, 163, 184));
    private static readonly Brush LiveBrush = Frozen(Color.FromRgb(34, 211, 238));
    private static readonly Brush DoneBrush = Frozen(Color.FromRgb(52, 211, 153));
    private static readonly Brush FailBrush = Frozen(Color.FromRgb(251, 113, 133));

    private PhoneSender.Item? _live;
    private string _stateText = "в очереди";
    private Brush _stateBrush = MutedBrush;
    private int _lastState = -1;
    private long _lastSent = -1;

    public string FullPath { get; }
    public string Name { get; }
    public string RelativePath { get; }
    public string Title { get; }
    public long Bytes { get; }
    public string SizeText { get; }

    public SendItemViewModel(string fullPath, string name, string relativePath)
    {
        FullPath = fullPath;
        Name = name;
        RelativePath = relativePath;
        Title = relativePath.Length == 0 ? name : relativePath + "/" + name;
        Bytes = QuietSize(FullPath);
        SizeText = FileItemViewModel.FormatBytes(Bytes);
        StateText = "в очереди";
    }

    /// <summary>Returns the row to its pre-sender state (used after a finished batch).</summary>
    public void Reset()
    {
        _live = null;
        _lastState = -1;
        _lastSent = -1;
        StateText = "в очереди";
        StateBrush = MutedBrush;
    }

    public string StateText
    {
        get => _stateText;
        private set => SetField(ref _stateText, value);
    }

    public Brush StateBrush
    {
        get => _stateBrush;
        private set => SetField(ref _stateBrush, value);
    }

    /// <summary>Binds a live transfer session row to this view model.</summary>
    public void Bind(PhoneSender.Item item)
    {
        if (item is null) return;
        _live = item;
        Refresh();
    }

    /// <summary>Recalculates the visible status; called from the send timer.</summary>
    public void Refresh()
    {
        var item = _live;
        if (item is null)
        {
            StateText = "в очереди";
            StateBrush = MutedBrush;
            return;
        }

        var state = item.State;
        if (state != _lastState)
        {
            _lastState = state;
            switch (state)
            {
                case PhoneSender.FileRunning:
                    StateText = PercentText(item);
                    StateBrush = LiveBrush;
                    break;
                case PhoneSender.FileDone:
                    StateText = "готово";
                    StateBrush = DoneBrush;
                    break;
                case PhoneSender.FileError:
                    StateText = item.Error.Length > 0 ? item.Error : "ошибка";
                    StateBrush = FailBrush;
                    break;
                case PhoneSender.FileCancelled:
                    StateText = "отменено";
                    StateBrush = FailBrush;
                    break;
                default:
                    StateText = "в очереди";
                    StateBrush = MutedBrush;
                    break;
            }
        }
        else if (state == PhoneSender.FileRunning && item.Sent != _lastSent)
        {
            _lastSent = item.Sent;
            StateText = PercentText(item);
        }
    }

    private static string PercentText(PhoneSender.Item item)
    {
        if (item.Size <= 0) return "0%";
        var percent = (int)(Math.Min(99, item.Sent * 100 / item.Size));
        return percent + "%";
    }

    private static long QuietSize(string path)
    {
        try { return new FileInfo(path).Length; }
        catch { return 0; }
    }

    private static Brush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void SetField<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

/// <summary>A found FireDrop receiver in the LAN.</summary>
public sealed class SendDeviceViewModel
{
    public SendDeviceViewModel(LanDevice device, bool selected)
    {
        Device = device;
        Selected = selected;
        Name = device.Name;
        EndpointLabel = $"{device.Endpoint} · {device.KindText}";
        SelectName = $"Устройство {device.Name}, {device.Endpoint}";
    }

    public LanDevice Device { get; }
    public bool Selected { get; }
    public string Name { get; }
    public string EndpointLabel { get; }
    public string SelectName { get; }
    public string Mark => Selected ? "✓" : "•";
}
