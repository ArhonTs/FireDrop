using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace FireDrop.Core;

public sealed record LanAddress(string Ip, string InterfaceName, string Description, bool IsWireless, bool IsVirtual = false)
{
    public string Display
    {
        get
        {
            var kind = IsWireless ? "Wi-Fi" : InterfaceName;
            return IsVirtual
                ? L10n.S("core.net.virtual", kind, Ip)
                : L10n.S("core.net.display", kind, Ip);
        }
    }
}

public static class NetworkHelper
{
    private static readonly string[] VirtualKeywords =
    {
        "vmware", "vmnet", "virtualbox", "hyper-v", "vethernet", "wsl", "loopback", "tap-", "tun",
        "vpn", "tailscale", "zerotier", "radmin", "docker", "bluetooth", "wi-fi direct",
        "virtual", "pseudo", "npcap", "hamachi", "wireguard", "openvpn",
    };

    public static IReadOnlyList<LanAddress> GetLanAddresses()
    {
        var physical = new List<(LanAddress Address, int Score)>();
        var virtualAdapters = new List<(LanAddress Address, int Score)>();

        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up ||
                    nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                    continue;

                IPInterfaceProperties props;
                try { props = nic.GetIPProperties(); }
                catch { continue; }

                var addresses = props.UnicastAddresses
                    .Select(a => a.Address)
                    .Where(a => a.AddressFamily == AddressFamily.InterNetwork)
                    .Where(a => !IPAddress.IsLoopback(a))
                    .Where(a => !a.ToString().StartsWith("169.254.", StringComparison.Ordinal))
                    .ToList();

                if (addresses.Count == 0) continue;

                var identity = $"{nic.Name} {nic.Description}".ToLowerInvariant();
                var isVirtual = VirtualKeywords.Any(identity.Contains);
                var isWireless = nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ||
                                 identity.Contains("wi-fi") || identity.Contains("wireless") || identity.Contains("wlan");
                var hasGateway = props.GatewayAddresses.Any(g =>
                    g.Address is not null &&
                    g.Address.AddressFamily == AddressFamily.InterNetwork &&
                    !g.Address.Equals(IPAddress.Any));

                foreach (var ip in addresses)
                {
                    var text = ip.ToString();
                    var isPrivate = IsPrivate(text);
                    var score = 0;
                    if (!isVirtual) score += 100;
                    if (isWireless) score += 60;
                    if (hasGateway) score += 45;
                    if (isPrivate) score += 20;
                    if (nic.Speed >= 100_000_000) score += 5;

                    var address = new LanAddress(text, nic.Name, nic.Description, isWireless, isVirtual);
                    if (isVirtual)
                        virtualAdapters.Add((address, score));
                    else
                        physical.Add((address, score));
                }
            }
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось получить список сетевых адресов", ex);
        }

        // Show every usable address, like the first version did: real adapters first
        // (they always outrank the rest by score), virtual ones (VMware, Hyper-V, WSL…)
        // after — they are still a working network in their own subnet, so both network
        // variants stay available for choosing.
        var candidates = physical.Concat(virtualAdapters).ToList();
        var result = candidates
            .OrderByDescending(x => x.Score)
            .ThenBy(x => x.Address.Ip, StringComparer.Ordinal)
            .Select(x => x.Address)
            .DistinctBy(a => a.Ip)
            .ToList();

        return result;
    }

    public static bool IsPrivate(string ip)
    {
        if (!IPAddress.TryParse(ip, out var address)) return false;
        var bytes = address.GetAddressBytes();
        return bytes[0] switch
        {
            10 => true,
            172 => bytes[1] is >= 16 and <= 31,
            192 => bytes[1] == 168,
            _ => false,
        };
    }

    public static string HostName => Environment.MachineName;
}
