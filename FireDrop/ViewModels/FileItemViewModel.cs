using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Windows.Media;
using FireDrop.Core;

namespace FireDrop.ViewModels;

public sealed class FileItemViewModel : INotifyPropertyChanged
{
    private static readonly Brush ImageBg = Frozen(Color.FromArgb(30, 52, 211, 153));
    private static readonly Brush ImageFg = Frozen(Color.FromRgb(110, 231, 183));
    private static readonly Brush VideoBg = Frozen(Color.FromArgb(30, 167, 139, 250));
    private static readonly Brush VideoFg = Frozen(Color.FromRgb(196, 181, 253));
    private static readonly Brush AudioBg = Frozen(Color.FromArgb(30, 244, 114, 182));
    private static readonly Brush AudioFg = Frozen(Color.FromRgb(249, 168, 212));
    private static readonly Brush ArchiveBg = Frozen(Color.FromArgb(30, 251, 191, 36));
    private static readonly Brush ArchiveFg = Frozen(Color.FromRgb(252, 211, 77));
    private static readonly Brush DocBg = Frozen(Color.FromArgb(30, 96, 165, 250));
    private static readonly Brush DocFg = Frozen(Color.FromRgb(147, 197, 253));
    private static readonly Brush OtherBg = Frozen(Color.FromArgb(30, 148, 163, 184));
    private static readonly Brush OtherFg = Frozen(Color.FromRgb(203, 213, 225));
    private static readonly Brush AccentBrush = Frozen(Color.FromRgb(34, 211, 238));
    private static readonly Brush SuccessBrush = Frozen(Color.FromRgb(52, 211, 153));
    private static readonly Brush DangerBrush = Frozen(Color.FromRgb(251, 113, 133));

    private static readonly string[] ImageExtensions =
        { "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "avif", "raw", "dng" };
    private static readonly string[] VideoExtensions =
        { "mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "mts", "m2ts" };
    private static readonly string[] AudioExtensions =
        { "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma" };
    private static readonly string[] ArchiveExtensions =
        { "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg" };
    private static readonly string[] DocExtensions =
        { "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "epub", "md" };

    private readonly long _size;
    private string _fileName;
    private string _fullPath;
    private string _extensionText;
    private Brush _iconBackground;
    private Brush _iconForeground;
    private Brush _progressBrush = AccentBrush;
    private string _statusText = "Передача";
    private string _speedText = string.Empty;
    private string _timeText = "сейчас";
    private double _progress;
    private bool _isActive = true;
    private bool _isCompleted;
    private bool _isFailed;

    public FileItemViewModel(TransferInfo info)
    {
        Id = info.Id;
        _fileName = info.FileName;
        _fullPath = info.FullPath;
        _size = info.Size;
        StartedAt = info.StartedAt;
        _extensionText = "FILE";
        _iconBackground = OtherBg;
        _iconForeground = OtherFg;
        ApplyVisual();
    }

    public FileItemViewModel(ActiveTransferInfo info)
        : this(new TransferInfo(
            info.Id,
            info.FileName,
            string.Empty,
            info.Size,
            info.StartedAt,
            null,
            0,
            null))
    {
        ApplyProgress(info);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public string Id { get; }
    public DateTime StartedAt { get; }
    public DateTime? CompletedAt { get; private set; }
    public long Size => _size;
    public bool IsActive => _isActive;
    public bool IsCompleted => _isCompleted;
    public bool IsFailed => _isFailed;

    public string FileName
    {
        get => _fileName;
        private set => SetField(ref _fileName, value);
    }

    public string FullPath
    {
        get => _fullPath;
        private set => SetField(ref _fullPath, value);
    }

    public string SizeText => FormatBytes(_size);
    public string ExtensionText => _extensionText;
    public Brush IconBackground => _iconBackground;
    public Brush IconForeground => _iconForeground;
    public Brush ProgressBrush => _progressBrush;
    public string StatusText => _statusText;
    public string SpeedText => _speedText;
    public string TimeText => _timeText;
    public double Progress => _progress;
    public string ProgressText => $"{_progress:0}%";
    public string Tooltip => string.IsNullOrWhiteSpace(_fullPath) ? FileName : _fullPath;
    public string AutomationName => $"{FileName}, {SizeText}, {StatusText}";

    public void ApplyProgress(ActiveTransferInfo info)
    {
        if (_isCompleted || _isFailed) return;
        _isActive = true;
        _progress = info.Size > 0
            ? Math.Clamp(info.ReceivedBytes * 100.0 / info.Size, 0, 100)
            : 0;
        _statusText = "Передача";
        _speedText = FormatSpeed(info.Speed);
        _progressBrush = AccentBrush;
        _timeText = "сейчас";
        Notify(nameof(Progress));
        Notify(nameof(ProgressText));
        Notify(nameof(StatusText));
        Notify(nameof(SpeedText));
        Notify(nameof(ProgressBrush));
        Notify(nameof(TimeText));
        Notify(nameof(IsActive));
    }

    public void Complete(TransferInfo info)
    {
        FileName = info.FileName;
        FullPath = info.FullPath;
        var completedAt = info.CompletedAt ?? DateTime.Now;
        CompletedAt = completedAt.Kind == DateTimeKind.Utc ? completedAt.ToLocalTime() : completedAt;
        _isActive = false;
        _isCompleted = true;
        _isFailed = false;
        _progress = 100;
        _statusText = "Готово";
        _speedText = FormatSpeed(info.AvgBytesPerSecond);
        _progressBrush = SuccessBrush;
        _timeText = CompletedAt.Value.ToString("HH:mm", CultureInfo.CurrentCulture);
        ApplyVisual();
        Notify(nameof(IsActive));
        Notify(nameof(IsCompleted));
        Notify(nameof(IsFailed));
        Notify(nameof(Progress));
        Notify(nameof(ProgressText));
        Notify(nameof(StatusText));
        Notify(nameof(SpeedText));
        Notify(nameof(ProgressBrush));
        Notify(nameof(TimeText));
        Notify(nameof(Tooltip));
    }

    public void Fail(string reason)
    {
        _isActive = false;
        _isCompleted = false;
        _isFailed = true;
        _statusText = "Не удалось";
        _speedText = string.IsNullOrWhiteSpace(reason) ? "Ошибка" : reason;
        _progressBrush = DangerBrush;
        Notify(nameof(IsActive));
        Notify(nameof(IsCompleted));
        Notify(nameof(IsFailed));
        Notify(nameof(StatusText));
        Notify(nameof(SpeedText));
        Notify(nameof(ProgressBrush));
        Notify(nameof(AutomationName));
    }

    private void ApplyVisual()
    {
        var extension = Path.GetExtension(_fileName).TrimStart('.').ToLowerInvariant();
        _extensionText = string.IsNullOrEmpty(extension)
            ? "FILE"
            : extension[..Math.Min(4, extension.Length)].ToUpperInvariant();

        if (ImageExtensions.Contains(extension))
        {
            _iconBackground = ImageBg;
            _iconForeground = ImageFg;
        }
        else if (VideoExtensions.Contains(extension))
        {
            _iconBackground = VideoBg;
            _iconForeground = VideoFg;
        }
        else if (AudioExtensions.Contains(extension))
        {
            _iconBackground = AudioBg;
            _iconForeground = AudioFg;
        }
        else if (ArchiveExtensions.Contains(extension))
        {
            _iconBackground = ArchiveBg;
            _iconForeground = ArchiveFg;
        }
        else if (DocExtensions.Contains(extension))
        {
            _iconBackground = DocBg;
            _iconForeground = DocFg;
        }
        else
        {
            _iconBackground = OtherBg;
            _iconForeground = OtherFg;
        }

        Notify(nameof(ExtensionText));
        Notify(nameof(IconBackground));
        Notify(nameof(IconForeground));
        Notify(nameof(FileName));
        Notify(nameof(SizeText));
        Notify(nameof(AutomationName));
    }

    public static string FormatBytes(long bytes)
    {
        string[] units = { "Б", "КБ", "МБ", "ГБ", "ТБ" };
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        var text = unit == 0 ? $"{value:0}" : $"{value:0.#}";
        return $"{text} {units[unit]}";
    }

    public static string FormatSpeed(double bytesPerSecond)
    {
        if (bytesPerSecond <= 0) return "0 МБ/с";
        var mb = bytesPerSecond / (1024 * 1024);
        if (mb >= 1024) return $"{mb / 1024:0.0} ГБ/с";
        if (mb >= 1) return $"{mb:0.0} МБ/с";
        return $"{bytesPerSecond / 1024:0} КБ/с";
    }

    private static Brush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private void SetField<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        Notify(propertyName);
    }

    private void Notify([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}
