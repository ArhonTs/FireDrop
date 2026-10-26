using System.Text;

namespace FireDrop.Core;

/// <summary>Small, dependency-free file logger. Never throws into the app.</summary>
public static class AppLog
{
    private static readonly object Gate = new();
    private static readonly string LogDirectory = Path.Combine(AppPaths.DataDirectory, "logs");
    private static readonly string LogPath = Path.Combine(LogDirectory, "firedrop.log");

    public static void Info(string message) => Write("INFO", message);
    public static void Error(string message, Exception? exception = null)
        => Write("ERROR", exception is null ? message : $"{message}{Environment.NewLine}{exception}");

    [System.Diagnostics.Conditional("DEBUG")]
    public static void Debug(string message) => Write("DEBUG", message);

    private static void Write(string level, string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(LogDirectory);
                if (File.Exists(LogPath) && new FileInfo(LogPath).Length > 2 * 1024 * 1024)
                {
                    var old = LogPath + ".old";
                    try { File.Delete(old); } catch { }
                    File.Move(LogPath, old);
                }

                File.AppendAllText(
                    LogPath,
                    $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {message}{Environment.NewLine}",
                    Encoding.UTF8);
            }
        }
        catch
        {
            // Logging must never break a transfer.
        }
    }
}

public static class AppPaths
{
    public static string DataDirectory
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FireDrop");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public static string PartialDirectory
    {
        get
        {
            var dir = Path.Combine(DataDirectory, "partial");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }
}
