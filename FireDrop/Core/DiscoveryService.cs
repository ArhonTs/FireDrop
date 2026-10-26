using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace FireDrop.Core;

/// <summary>
/// Answers UDP broadcast probes from the Android app so phones can find this PC
/// without typing an IP address. Only the public name and port are exposed;
/// the PIN is still required to connect.
/// </summary>
public sealed class DiscoveryService : IDisposable
{
    public const int DiscoveryPort = 47822;
    public const string Probe = "FIREDROP_DISCOVER_V1";

    private readonly Func<string> _deviceName;
    private readonly Func<int> _port;
    private CancellationTokenSource? _cts;
    private UdpClient? _listener;
    private int _disposed;

    public DiscoveryService(Func<string> deviceName, Func<int> port)
    {
        _deviceName = deviceName;
        _port = port;
    }

    public void Start()
    {
        if (_listener is not null) return;

        try
        {
            var listener = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            listener.EnableBroadcast = true;
            _cts = new CancellationTokenSource();
            _listener = listener;
            _ = Task.Run(() => ReceiveLoopAsync(listener, _cts.Token));
            AppLog.Info($"UDP-обнаружение запущено на порту {DiscoveryPort}");
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось запустить UDP-обнаружение", ex);
        }
    }

    private async Task ReceiveLoopAsync(UdpClient listener, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                var result = await listener.ReceiveAsync(token);
                var text = Encoding.UTF8.GetString(result.Buffer).Trim();
                if (!text.StartsWith(Probe, StringComparison.Ordinal)) continue;

                var payload = JsonSerializer.Serialize(new
                {
                    app = "FireDrop",
                    kind = "pc",
                    name = _deviceName(),
                    port = _port(),
                    version = TransferServer.Version,
                });

                var bytes = Encoding.UTF8.GetBytes(payload);
                await listener.SendAsync(bytes, result.RemoteEndPoint, token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                // A single bad datagram must not stop discovery.
                await Task.Delay(200, CancellationToken.None);
            }
            catch (Exception ex)
            {
                AppLog.Error("Ошибка UDP-обнаружения", ex);
                await Task.Delay(500, CancellationToken.None);
            }
        }
    }

    public void Dispose() => Stop();

    /// <summary>Cancels the listener but keeps the instance reusable for a later Start().</summary>
    public void Stop()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;

        try { _cts?.Cancel(); } catch { }
        try { _listener?.Dispose(); } catch { }
        _cts?.Dispose();
        _cts = null;
        _listener = null;
        Interlocked.Exchange(ref _disposed, 0);
    }
}
