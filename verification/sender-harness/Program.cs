// Test harness for FireDrop.Core.PhoneSender: sends a prepared source folder
// to a running FireDrop receiver and prints a per-file report.
// Usage: SenderHarness <ip:port> <pin> <sourcePath>
// Exit codes: 0 = all files delivered, 3 = health probe failed, 4 = transfer failed.
using System.Text;
using FireDrop.Core;

namespace SenderHarness;

public static class Program
{
    private static int Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        if (args.Length >= 1 && args[0] == "discover")
        {
            var ms = args.Length > 1 && int.TryParse(args[1], out var parsed) ? parsed : 2600;
            Console.OutputEncoding = Encoding.UTF8;
            var devices = PhoneDiscovery.DiscoverAsync(ms).GetAwaiter().GetResult();
            Console.WriteLine($"devices={devices.Count}");
            foreach (var d in devices)
                Console.WriteLine($"device kind={d.Kind} name={d.Name} endpoint={d.Endpoint} version={d.Version}");
            return devices.Count > 0 ? 0 : 3;
        }

        if (args.Length >= 1 && args[0] == "firewall")
        {
            int port = args.Length > 1 && int.TryParse(args[1], out var fwPort) ? fwPort : 47820;
            var status = FirewallHelper.CheckAsync(port).GetAwaiter().GetResult();
            Console.WriteLine($"allowed={status.Allowed} message={status.Message}");
            return 0;
        }

        if (args.Length >= 1 && args[0] == "addresses")
        {
            Console.OutputEncoding = Encoding.UTF8;
            var list = NetworkHelper.GetLanAddresses();
            Console.WriteLine($"count={list.Count}");
            foreach (var a in list)
                Console.WriteLine($"addr virtual={a.IsVirtual} wireless={a.IsWireless} display={a.Display}");
            return list.Count > 0 ? 0 : 3;
        }

        if (args.Length < 3)
        {
            Console.Error.WriteLine("usage: SenderHarness <ip:port> <pin> <sourcePath>");
            return 2;
        }        var address = args[0];
        var pin = args[1];
        var source = args[2];
        if (!Directory.Exists(source))
        {
            Console.Error.WriteLine("source folder not found");
            return 2;
        }

        var probe = PhoneSender.ProbeAsync(address, CancellationToken.None).GetAwaiter().GetResult();
        Console.WriteLine($"probe ok={probe.Ok} device={probe.DeviceName} problem={probe.Problem}");
        if (!probe.Ok) return 3;

        var root = Path.GetFileName(source.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        var items = new List<PhoneSender.Item>();
        var folders = new List<string>();
        var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true };

        foreach (var file in Directory.EnumerateFiles(source, "*", options))
        {
            var relative = Path.GetRelativePath(source, file);
            var name = Path.GetFileName(relative);
            var parent = Path.GetDirectoryName(relative)?.Replace('\\', '/') ?? string.Empty;
            string relativeParent = parent.Length == 0 ? root : root + "/" + parent;

            items.Add(new PhoneSender.Item(file, name, relativeParent));
            if (!folders.Contains(relativeParent)) folders.Add(relativeParent);
        }

        var sender = new PhoneSender(address, pin, items, folders);
        try
        {
            sender.RunAsync(CancellationToken.None).GetAwaiter().GetResult();

            foreach (var item in sender.Items)
            {
                Console.WriteLine(
                    $"file={item.Name} rel={item.RelativePath} state={item.State} " +
                    $"sent={item.Sent}/{item.Size} receivedAs={item.Location} err={item.Error}");
            }

            Console.WriteLine(
                $"device={sender.DeviceName} completed={sender.CompletedCount}/{sender.Items.Count} " +
                $"failed={sender.FailedCount} bytes={sender.SentBytes}/{sender.TotalBytes}");

            var fatal = sender.FatalError;
            if (fatal.Length > 0) Console.WriteLine($"fatal={fatal}");

            return sender.CompletedCount == sender.Items.Count && sender.FailedCount == 0 ? 0 : 4;
        }
        finally
        {
            sender.Dispose();
        }
    }
}
