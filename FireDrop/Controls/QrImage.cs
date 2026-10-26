using System.Windows;
using System.Windows.Automation.Peers;
using System.Windows.Media;
using Net.Codecrete.QrCodeGenerator;

namespace FireDrop.Controls;

/// <summary>Draws a crisp QR code with a quiet zone, at device-independent pixels.</summary>
public sealed class QrImage : FrameworkElement
{
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text),
        typeof(string),
        typeof(QrImage),
        new FrameworkPropertyMetadata(string.Empty, FrameworkPropertyMetadataOptions.AffectsRender, OnTextChanged));

    private static readonly Brush ModuleBrush = CreateBrush(Color.FromRgb(8, 14, 27));
    private QrCode? _code;

    public QrImage()
    {
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
        SnapsToDevicePixels = true;
    }

    public string Text
    {
        get => (string)GetValue(TextProperty);
        set => SetValue(TextProperty, value);
    }

    private static Brush CreateBrush(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private static void OnTextChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        ((QrImage)d).Rebuild();
    }

    protected override AutomationPeer OnCreateAutomationPeer()
    {
        return new QrImageAutomationPeer(this);
    }

    private void Rebuild()
    {
        _code = null;
        var text = Text;
        if (!string.IsNullOrWhiteSpace(text))
        {
            try
            {
                _code = QrCode.EncodeText(text, QrCode.Ecc.Medium);
            }
            catch (Exception ex)
            {
                Core.AppLog.Error("Не удалось построить QR-код", ex);
            }
        }

        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext drawingContext)
    {
        base.OnRender(drawingContext);
        if (_code is null) return;

        var side = Math.Min(ActualWidth, ActualHeight);
        if (side <= 0) return;

        const int quietZone = 4;
        var modules = _code.Size;
        var totalModules = modules + quietZone * 2;
        var scale = Math.Floor(side / totalModules);
        if (scale < 1) return;

        var drawn = scale * totalModules;
        var left = Math.Floor((ActualWidth - drawn) / 2);
        var top = Math.Floor((ActualHeight - drawn) / 2);

        drawingContext.DrawRectangle(
            Brushes.White,
            null,
            new Rect(left, top, drawn, drawn));

        for (var y = 0; y < modules; y++)
        {
            for (var x = 0; x < modules; x++)
            {
                if (!_code.GetModule(x, y)) continue;
                drawingContext.DrawRectangle(
                    ModuleBrush,
                    null,
                    new Rect(
                        left + (x + quietZone) * scale,
                        top + (y + quietZone) * scale,
                        scale,
                        scale));
            }
        }
    }
}

internal sealed class QrImageAutomationPeer(QrImage owner) : FrameworkElementAutomationPeer(owner)
{
    protected override AutomationControlType GetAutomationControlTypeCore()
    {
        return AutomationControlType.Image;
    }

    protected override string GetClassNameCore()
    {
        return nameof(QrImage);
    }
}
