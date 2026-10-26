using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Net.NetworkInformation;

namespace FireDrop.Core;

/// <summary>
/// Sends the same UDP broadcast probe the Android app sends and collects replies from
/// FireDrop receivers (phones and PCs) on the local network.
/// </summary>
public sealed record LanDevice(string Name, string Host, int Port, string Kind, string Version)
{
    public string KindText => Kind == "phone" ? "телефон" : "ПК";
    public string Endpoint => $"{Host}:{Port}";
}

public static class PhoneDiscovery
{
    private const int DiscoveryPort = 47822;
    private const string Probe = "FIREDROP_DISCOVER_V1";

    /// <summary>Broadcasts the probe for <paramref name="timeout" /> and collects unique answers.</summary>
    public static async Task<List<LanDevice>> DiscoverAsync(int timeout = 2200)
    {
        var found = new Dictionary<string, LanDevice>(StringComparer.Ordinal);

        try
        {
            using var socket = new UdpClient();
            socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            socket.EnableBroadcast = true;

            var targets = BroadcastTargets().ToHashSet();
            targets.Add(new IPEndPoint(IPAddress.Broadcast, DiscoveryPort));
            var probe = Encoding.UTF8.GetBytes(Probe);
            foreach (var target in targets)
            {
                try { await socket.SendAsync(probe, target); }
                catch (Exception) { /* One bad interface must not stop the sweep. */ }
            }

            // ReceiveAsync ignores ReceiveTimeout; the token is the deadline.
            using var source = new CancellationTokenSource(TimeSpan.FromMilliseconds(timeout));
            while (!source.IsCancellationRequested)
            {
                UdpReceiveResult packet;
                try
                {
                    packet = await socket.ReceiveAsync(source.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                try
                {
                    var device = ParseReply(packet);
                    if (device is null) continue;
                    var key = $"{device.Host}:{device.Port}";
                    found.TryAdd(key, device);
                }
                catch (Exception)
                {
                    // Ignore malformed replies.
                }
            }
        }
        catch (Exception)
        {
            // A missing network stack still returns whatever was collected.
        }

        return found.Values
            .OrderBy(device => device.Kind == "phone" ? 0 : 1)
            .ThenBy(device => device.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static LanDevice? ParseReply(UdpReceiveResult packet)
    {
        var text = Encoding.UTF8.GetString(packet.Buffer).Trim();
        if (string.IsNullOrEmpty(text) || text[0] != '{') return null;

        var json = JsonNode.Parse(text) as JsonObject;
        if (json?["app"]?.ToString() != "FireDrop") return null;

        var host = packet.RemoteEndPoint.Address.ToString();
        if (string.IsNullOrEmpty(host)) return null;
        if (IsOwnAddress(packet.RemoteEndPoint.Address)) return null; // Self echo.

        int port = 47820;
        if (json["port"] is JsonNode portNode && portNode.GetValueKind() == JsonValueKind.Number)
            port = portNode.GetValue<int>();

        var name = json["name"]?.ToString() ?? host;
        var kind = json["kind"]?.ToString() ?? "pc";
        var version = json["version"]?.ToString() ?? "";
        return new LanDevice(name, host, port, kind, version);
    }

    /// <summary>
    /// Local broadcasts are looped back to the sender as well; without this filter the
    /// PC lists its own virtual adapters as "devices". A reply that originates from one
    /// of this machine's own IPv4 addresses is always the echo of our own probe.
    /// </summary>
    private static bool IsOwnAddress(IPAddress address)
    {
        try
        {
            var bytes = address.GetAddressBytes();
            if (bytes.Length != 4 || IPAddress.Loopback.Equals(address)) return false;

            foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (network.OperationalStatus != OperationalStatus.Up ||
                    network.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                {
                    continue;
                }

                foreach (var unicast in network.GetIPProperties().UnicastAddresses)
                {
                    if (unicast.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (unicast.Address.GetAddressBytes().AsSpan().SequenceEqual(bytes)) return true;
                }
            }
        }
        catch (Exception)
        {
            // Without a network enumeration we choose not to filter.
            return false;
        }
        return false;
    }

    private static IEnumerable<IPEndPoint> BroadcastTargets()
    {
        var result = new List<IPEndPoint>();
        try
        {
            foreach (var network in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (network.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up ||
                    network.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
                {
                    continue;
                }

                foreach (var unicast in network.GetIPProperties().UnicastAddresses)
                {
                    if (unicast.Address.AddressFamily != AddressFamily.InterNetwork ||
                        unicast.IPv4Mask is null ||
                        unicast.IPv4Mask.GetAddressBytes().All(b => b == 0))
                    {
                        continue;
                    }

                    var address = unicast.Address.GetAddressBytes();
                    var mask = unicast.IPv4Mask.GetAddressBytes();
                    if (address.Length != mask.Length) continue;

                    var broadcast = new byte[address.Length];
                    for (var i = 0; i < address.Length; i++)
                        broadcast[i] = (byte)(address[i] | ~mask[i]);

                    result.Add(new IPEndPoint(new IPAddress(broadcast), DiscoveryPort));
                }
            }
        }
        catch (Exception)
        {
            // Interface sweep is best effort.
        }
        return result;
    }
}
