using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32;

namespace FireDrop.Core;

public sealed class AppSettings
{
    private static readonly Guid DownloadsFolderId = new("374DE290-123F-4565-9164-39C4925E467B");

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public string SaveFolder { get; set; } = DefaultSaveFolder();

    public int Port { get; set; } = 47820;
    public string DeviceName { get; set; } = Environment.MachineName;
    public bool StartWithWindows { get; set; }
    public bool MinimizeToTrayOnClose { get; set; }

    private static string SettingsPath => Path.Combine(AppPaths.DataDirectory, "settings.json");

    public static AppSettings Load()
    {
        AppSettings settings;
        try
        {
            settings = File.Exists(SettingsPath)
                ? JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsPath), JsonOptions) ?? new AppSettings()
                : new AppSettings();
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось прочитать настройки, используются значения по умолчанию", ex);
            settings = new AppSettings();
        }

        if (string.IsNullOrWhiteSpace(settings.SaveFolder))
            settings.SaveFolder = DefaultSaveFolder();

        if (settings.Port is < 1024 or > 65535)
            settings.Port = 47820;

        if (string.IsNullOrWhiteSpace(settings.DeviceName))
            settings.DeviceName = Environment.MachineName;

        try { Directory.CreateDirectory(settings.SaveFolder); }
        catch { settings.SaveFolder = AppPaths.DataDirectory; }

        return settings;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.DataDirectory);
            File.WriteAllText(SettingsPath, JsonSerializer.Serialize(this, JsonOptions));
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось сохранить настройки", ex);
        }

        ApplyStartupSetting();
    }

    private static string DefaultSaveFolder()
    {
        var downloads = string.Empty;

        try
        {
            var folderId = DownloadsFolderId;
            if (SHGetKnownFolderPath(ref folderId, 0, IntPtr.Zero, out var pointer) == 0 &&
                pointer != IntPtr.Zero)
            {
                try { downloads = Marshal.PtrToStringUni(pointer) ?? string.Empty; }
                finally { Marshal.FreeCoTaskMem(pointer); }
            }
        }
        catch
        {
            // Fall back to the conventional profile path below.
        }

        if (string.IsNullOrWhiteSpace(downloads))
        {
            var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            downloads = string.IsNullOrWhiteSpace(profile)
                ? AppPaths.DataDirectory
                : Path.Combine(profile, "Downloads");
        }

        return Path.Combine(downloads, "FireDrop");
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHGetKnownFolderPath(ref Guid rfid, uint flags, IntPtr token, out IntPtr path);

    private void ApplyStartupSetting()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            if (key is null) return;

            const string valueName = "FireDrop";
            if (StartWithWindows)
            {
                var exe = Environment.ProcessPath;
                if (!string.IsNullOrWhiteSpace(exe))
                    key.SetValue(valueName, $"\"{exe}\" --autostart");
            }
            else if (key.GetValue(valueName) is not null)
            {
                key.DeleteValue(valueName, throwOnMissingValue: false);
            }
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось изменить автозапуск", ex);
        }
    }
}
