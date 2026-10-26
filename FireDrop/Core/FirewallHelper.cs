using System.Diagnostics;

namespace FireDrop.Core;

/// <summary>
/// Windows shows the firewall prompt once per exe path. When the app is moved to a new
/// folder (unpacked update) there is no rule for the new path, so every inbound packet —
/// the join port and the UDP discovery reply — is silently dropped and both phones and
/// other PCs stop seeing this machine. This helper detects that state and fixes it with
/// one elevated PowerShell command.
/// </summary>
public static class FirewallHelper
{
    private const string TcpRule = "FireDrop TCP";
    private const string UdpRule = "FireDrop UDP";
    public const int DiscoveryUdpPort = 47822;

    public sealed record Status(bool Allowed, string Message);

    /// <summary>The exe that is actually running (single-file publish resolves here).</summary>
    public static string ExePath
    {
        get
        {
            try { return Process.GetCurrentProcess().MainModule?.FileName ?? ""; }
            catch { return ""; }
        }
    }

    /// <summary>
    /// Reads the rules with current privileges. Rule lookup is allowed for normal users,
    /// so this usually works; a failure means "unknown" rather than "blocked".
    /// </summary>
    public static async Task<Status> CheckAsync(int tcpPort)
    {
        var script =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "$tcp = Get-NetFirewallRule -DisplayName '" + TcpRule + "' | Select-Object -First 1; " +
            "$udp = Get-NetFirewallRule -DisplayName '" + UdpRule + "' | Select-Object -First 1; " +
            "if ($tcp -and $udp) { 'allowed' } else { 'missing' }";

        var result = await RunPowerShellAsync(script, elevated: false, timeoutMs: 15_000);
        if (!result.Ok)
            return new Status(false, L10n.Get("core.firewall.check.failed"));

        return result.Output.Contains("allowed", StringComparison.CurrentCultureIgnoreCase)
            ? new Status(true, L10n.Get("core.firewall.ok"))
            : new Status(false, L10n.Get("core.firewall.missing"));
    }

    /// <summary>
    /// Recreates the two inbound rules with an elevated PowerShell. Profile is unrestricted
    /// because laptops often run their Wi-Fi in the "public" profile, which would otherwise
    /// keep the same problem.
    /// </summary>
    public static async Task<Status> EnsureAllowedAsync(int tcpPort)
    {
        var exe = ExePath;
        if (exe.Length == 0)
            return new Status(false, L10n.Get("core.firewall.nopath"));

        var psEscaped = exe.Replace("'", "''");
        var script =
            "$ErrorActionPreference='Stop'; " +
            "try { " +
            "Get-NetFirewallRule -DisplayName '" + TcpRule + "' -ErrorAction SilentlyContinue | Remove-NetFirewallRule; " +
            "Get-NetFirewallRule -DisplayName '" + UdpRule + "' -ErrorAction SilentlyContinue | Remove-NetFirewallRule; " +
            "New-NetFirewallRule -DisplayName '" + TcpRule + "' -Direction Inbound -Action Allow " +
            "-Protocol TCP -LocalPort " + tcpPort + " -Program '" + psEscaped + "' -Profile Any | Out-Null; " +
            "New-NetFirewallRule -DisplayName '" + UdpRule + "' -Direction Inbound -Action Allow " +
            "-Protocol UDP -LocalPort " + DiscoveryUdpPort + " -Program '" + psEscaped + "' -Profile Any | Out-Null; " +
            "exit 0 } catch { exit 1 }";

        var result = await RunPowerShellAsync(script, elevated: true, timeoutMs: 90_000);
        if (result.TimedOut)
            return new Status(false, L10n.Get("core.firewall.uac.timeout"));

        if (!result.Ok)
            return new Status(false, L10n.Get("core.firewall.create.failed"));

        return new Status(true, L10n.Get("core.firewall.created"));
    }

    private static async Task<(bool Ok, string Output, bool TimedOut)> RunPowerShellAsync(
        string script, bool elevated, int timeoutMs)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments =
                    "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -Command " +
                    Quote(script),
                UseShellExecute = elevated,
                CreateNoWindow = !elevated,
                RedirectStandardOutput = !elevated,
                RedirectStandardError = !elevated,
            };
            if (elevated) psi.Verb = "runas";

            using var process = Process.Start(psi);
            if (process is null) return (false, "", false);

            var readOutput = elevated
                ? Task.FromResult(string.Empty)
                : process.StandardOutput.ReadToEndAsync();

            var wait = process.WaitForExitAsync();
            var finished = await Task.WhenAny(wait, Task.Delay(timeoutMs)) == wait;
            if (!finished && !process.HasExited)
                return (false, "", true);

            return (process.ExitCode == 0, await readOutput, false);
        }
        catch (System.ComponentModel.Win32Exception) when (elevated)
        {
            // The user cancelled the UAC prompt.
            return (false, "", false);
        }
        catch (Exception)
        {
            return (false, "", false);
        }
    }

    private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";
}
