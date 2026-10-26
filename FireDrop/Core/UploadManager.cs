using System.Buffers;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;

namespace FireDrop.Core;

/// <summary>
/// Receives files in parallel chunks and writes them straight to disk.
/// No base64, no multipart parsing and no hashing on the hot path.
/// </summary>
public sealed class UploadManager : IAsyncDisposable
{
    public const int ChunkSize = 8 * 1024 * 1024;
    public const int ParallelChunks = 6;
    private const int CopyBufferSize = 256 * 1024;
    private const long MinimumFreeSpace = 64L * 1024 * 1024;
    private static readonly TimeSpan StaleAfter = TimeSpan.FromHours(2);

    private readonly object _gate = new();
    private readonly Dictionary<string, UploadSession> _sessions = new(StringComparer.Ordinal);
    private readonly HashSet<string> _reservedPaths = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<TransferInfo> _completed = new();
    private readonly Dictionary<string, TransferInfo> _recentlyCompleted = new(StringComparer.Ordinal);
    private readonly string _partialDirectory;
    private readonly Timer _cleanupTimer;
    private string _saveFolder;
    private long _completedBytes;
    private int _disposed;

    public UploadManager(string saveFolder)
    {
        _saveFolder = Path.GetFullPath(saveFolder);
        Directory.CreateDirectory(_saveFolder);
        _partialDirectory = AppPaths.PartialDirectory;
        CleanPartialDirectory();
        _cleanupTimer = new Timer(_ => CleanupStale(), null, TimeSpan.FromMinutes(10), TimeSpan.FromMinutes(10));
    }

    public event Action<TransferInfo>? FileStarted;
    public event Action<TransferInfo>? FileCompleted;
    public event Action<string, string>? FileFailed;

    public string SaveFolder
    {
        get { lock (_gate) return _saveFolder; }
    }

    public void SetSaveFolder(string folder)
    {
        var full = Path.GetFullPath(folder);
        Directory.CreateDirectory(full);
        lock (_gate) _saveFolder = full;
    }

    public UploadManagerSnapshot GetSnapshot()
    {
        UploadSession[] sessions;
        int completedCount;
        long completedBytes;
        lock (_gate)
        {
            sessions = _sessions.Values.Where(s => !s.Aborted && !s.Completed).ToArray();
            completedCount = _completed.Count;
            completedBytes = _completedBytes;
        }

        var active = sessions
            .Select(s => s.ToActiveInfo())
            .OrderBy(a => a.StartedAt)
            .ToArray();
        return new UploadManagerSnapshot(active, completedCount, completedBytes);
    }

    public TransferInfo Create(
        string? originalName,
        long size,
        long? lastModified,
        string? clientAddress,
        string? relativePath = null)
    {
        if (size < 0)
            throw new UploadException(400, "Некорректный размер файла.");

        var safeName = SanitizeFileName(originalName);
        var directories = SanitizeRelativePath(relativePath);
        var displayName = BuildDisplayName(directories, safeName);
        var id = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var tempPath = Path.Combine(_partialDirectory, id + ".part");
        string finalPath;

        lock (_gate)
        {
            finalPath = ReservePathLocked(directories, safeName);
        }

        try
        {
            EnsureFreeSpace(size);
            var stream = new FileStream(
                tempPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.Read,
                4096,
                FileOptions.Asynchronous);
            stream.SetLength(size);

            var session = new UploadSession(
                id,
                originalName ?? displayName,
                displayName,
                directories,
                finalPath,
                tempPath,
                size,
                lastModified,
                stream,
                clientAddress);

            lock (_gate)
            {
                _sessions[id] = session;
            }

            var info = session.ToTransferInfo();
            AppLog.Info($"Начата передача: {displayName} ({FormatBytes(size)}), клиент {clientAddress ?? "неизвестен"}");
            FileStarted?.Invoke(info);
            return info;
        }
        catch (UploadException)
        {
            lock (_gate) _reservedPaths.Remove(finalPath);
            throw;
        }
        catch (Exception ex)
        {
            lock (_gate) _reservedPaths.Remove(finalPath);
            TryDelete(tempPath);
            AppLog.Error($"Не удалось создать приёмник файла {displayName}", ex);
            throw new UploadException(500, "Не удалось создать файл на диске. Проверьте папку сохранения.");
        }
    }

    public async Task<long> WriteChunkAsync(
        string id,
        long offset,
        long declaredLength,
        Stream body,
        CancellationToken cancellationToken)
    {
        var session = GetSession(id);
        if (session.Aborted) throw new UploadException(410, "Передача отменена.");
        if (session.Completed) throw new UploadException(409, "Файл уже принят.");

        var expectedLength = GetExpectedChunkLength(session.Size, offset);
        if (expectedLength < 0 || declaredLength != expectedLength)
            throw new UploadException(400, "Некорректный размер части файла.");

        if (session.Chunks.ContainsKey(offset))
        {
            await DrainAsync(body, declaredLength, cancellationToken);
            return declaredLength;
        }

        var buffer = ArrayPool<byte>.Shared.Rent(CopyBufferSize);
        long written = 0;
        try
        {
            while (written < expectedLength)
            {
                var toRead = (int)Math.Min(buffer.Length, expectedLength - written);
                var read = await body.ReadAsync(buffer.AsMemory(0, toRead), cancellationToken);
                if (read <= 0) break;

                await RandomAccess.WriteAsync(
                    session.Stream.SafeFileHandle,
                    buffer.AsMemory(0, read),
                    offset + written,
                    cancellationToken);

                written += read;
                session.LastActivityUtc = DateTime.UtcNow;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        if (written != expectedLength)
            throw new UploadException(400, "Часть файла пришла не полностью.");

        if (session.Chunks.TryAdd(offset, expectedLength))
        {
            var total = Interlocked.Add(ref session.ReceivedBytes, expectedLength);
            session.Speed.SetTotal(total);
        }

        session.LastActivityUtc = DateTime.UtcNow;
        return expectedLength;
    }

    public async Task<TransferInfo> CompleteAsync(string id)
    {
        lock (_gate)
        {
            if (_recentlyCompleted.TryGetValue(id, out var alreadyDone))
                return alreadyDone;
        }

        var session = GetSession(id);
        await session.CompletionGate.WaitAsync();
        try
        {
            if (session.Completed && session.Result is not null)
                return session.Result;
            if (session.Aborted) throw new UploadException(410, "Передача отменена.");

            var expectedChunks = session.Size == 0 ? 0 : (session.Size + ChunkSize - 1) / ChunkSize;
            var received = Interlocked.Read(ref session.ReceivedBytes);
            var chunksValid = session.Chunks.Count == expectedChunks &&
                              session.Chunks.Values.Sum() == session.Size &&
                              received == session.Size;

            if (!chunksValid)
            {
                var missing = Math.Max(0, expectedChunks - session.Chunks.Count);
                throw new UploadException(409,
                    $"Передача ещё не завершена (не хватает частей: {missing}).");
            }

            try
            {
                await session.Stream.FlushAsync();
                session.Stream.Dispose();
            }
            catch (Exception ex)
            {
                AppLog.Error("Ошибка сброса файла на диск", ex);
                await FailAsync(session, "Не удалось сохранить файл на диск.");
                throw new UploadException(500, "Не удалось сохранить файл на диск.");
            }

            string finalPath;
            lock (_gate)
            {
                finalPath = session.FinalPath;
                if (File.Exists(finalPath) || Directory.Exists(finalPath))
                {
                    _reservedPaths.Remove(finalPath);
                    finalPath = ReservePathLocked(session.Directories, session.LeafName);
                    session.FinalPath = finalPath;
                }
            }

            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(finalPath)!);
                File.Move(session.TempPath, finalPath, overwrite: false);
            }
            catch (IOException)
            {
                // Extremely rare: a file with the same name appeared while we were receiving.
                lock (_gate)
                {
                    _reservedPaths.Remove(finalPath);
                    finalPath = ReservePathLocked(session.Directories, session.LeafName);
                    session.FinalPath = finalPath;
                }
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(finalPath)!);
                    File.Move(session.TempPath, finalPath, overwrite: false);
                }
                catch (Exception ex)
                {
                    AppLog.Error("Не удалось переименовать принятый файл", ex);
                    await FailAsync(session, "Не удалось сохранить файл в папке назначения.");
                    throw new UploadException(500, "Не удалось сохранить файл в папке назначения.");
                }
            }

            session.Completed = true;
            ApplyLastModified(finalPath, session.LastModified);

            var duration = DateTime.UtcNow - session.StartedAt;
            var avgSpeed = session.Size / Math.Max(duration.TotalSeconds, 0.001);
            var result = new TransferInfo(
                session.Id,
                session.SafeName,
                finalPath,
                session.Size,
                session.StartedAt,
                DateTime.UtcNow,
                avgSpeed,
                null);

            lock (_gate)
            {
                _sessions.Remove(id);
                _reservedPaths.Remove(finalPath);
                _completed.Insert(0, result);
                _completedBytes += session.Size;
                _recentlyCompleted[id] = result;
                if (_completed.Count > 300)
                    _completed.RemoveRange(300, _completed.Count - 300);
                if (_recentlyCompleted.Count > 200)
                {
                    foreach (var staleId in _recentlyCompleted.Keys.Take(_recentlyCompleted.Count - 200).ToArray())
                        _recentlyCompleted.Remove(staleId);
                }
            }

            session.Result = result;
            AppLog.Info(
                $"Файл принят: {result.FileName} ({FormatBytes(result.Size)}), " +
                $"{FormatBytes((long)avgSpeed)}/с, за {duration.TotalSeconds:0.0} с");
            FileCompleted?.Invoke(result);
            return result;
        }
        finally
        {
            session.CompletionGate.Release();
        }
    }

    public async Task AbortAsync(string id, string reason = "Передача отменена")
    {
        UploadSession? session;
        lock (_gate)
        {
            _sessions.TryGetValue(id, out session);
        }

        if (session is null) return;

        await session.CompletionGate.WaitAsync();
        try
        {
            if (session.Completed || session.Aborted) return;
            await FailAsync(session, reason);
        }
        finally
        {
            session.CompletionGate.Release();
        }
    }

    private async Task FailAsync(UploadSession session, string reason)
    {
        session.Aborted = true;
        lock (_gate)
        {
            _sessions.Remove(session.Id);
            _reservedPaths.Remove(session.FinalPath);
        }

        try { session.Stream.Dispose(); } catch { }
        TryDelete(session.TempPath);
        RemoveEmptyDirectories(session.Directories);
        AppLog.Info($"Передача прервана: {session.SafeName} — {reason}");
        FileFailed?.Invoke(session.Id, reason);
        await Task.CompletedTask;
    }

    private UploadSession GetSession(string id)
    {
        UploadSession? session;
        lock (_gate)
        {
            _sessions.TryGetValue(id, out session);
        }

        if (session is null)
            throw new UploadException(404, "Передача не найдена.");
        return session;
    }

    private void CleanupStale()
    {
        try
        {
            List<UploadSession> stale;
            lock (_gate)
            {
                stale = _sessions.Values
                    .Where(s => DateTime.UtcNow - s.LastActivityUtc > StaleAfter)
                    .ToList();
            }

            foreach (var session in stale)
                _ = AbortAsync(session.Id, "Истекло время ожидания");
        }
        catch (Exception ex)
        {
            AppLog.Error("Ошибка очистки незавершённых передач", ex);
        }
    }

    private static long GetExpectedChunkLength(long fileSize, long offset)
    {
        if (offset < 0 || offset % ChunkSize != 0 || offset >= fileSize)
            return -1;
        return Math.Min(ChunkSize, fileSize - offset);
    }

    private static async Task DrainAsync(Stream body, long length, CancellationToken ct)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        try
        {
            long left = length;
            while (left > 0)
            {
                var read = await body.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, left)), ct);
                if (read <= 0) break;
                left -= read;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private void EnsureFreeSpace(long size)
    {
        try
        {
            string saveFolder;
            lock (_gate) saveFolder = _saveFolder;
            var root = Path.GetPathRoot(saveFolder);
            if (string.IsNullOrEmpty(root)) return;
            var drive = new DriveInfo(root);
            if (drive.IsReady && drive.AvailableFreeSpace < size + MinimumFreeSpace)
                throw new UploadException(507, $"На диске недостаточно места. Свободно {FormatBytes(drive.AvailableFreeSpace)}.");
        }
        catch (UploadException)
        {
            throw;
        }
        catch
        {
            // UNC paths and virtual drives may not expose DriveInfo.
        }
    }

    public void CreateFolder(string? relativePath)
    {
        var directories = SanitizeRelativePath(relativePath);
        if (directories.Length == 0)
            throw new UploadException(400, "Не указан путь папки.");

        string folder;
        lock (_gate) folder = _saveFolder;

        var target = Path.Combine(new[] { folder }.Concat(directories).ToArray());
        try
        {
            Directory.CreateDirectory(target);
            AppLog.Info($"Создана папка: {BuildDisplayName(directories, string.Empty)}");
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось создать папку назначения", ex);
            throw new UploadException(500, "Не удалось создать папку в папке сохранения.");
        }
    }

    private string ReservePathLocked(string[] directories, string safeName)
    {
        var folder = _saveFolder;
        if (directories.Length > 0)
            folder = Path.Combine(new[] { folder }.Concat(directories).ToArray());

        var candidate = Path.Combine(folder, safeName);
        if (!File.Exists(candidate) && !Directory.Exists(candidate) && _reservedPaths.Add(candidate))
            return candidate;

        var stem = Path.GetFileNameWithoutExtension(safeName);
        var extension = Path.GetExtension(safeName);
        for (var i = 1; i < 100_000; i++)
        {
            candidate = Path.Combine(folder, $"{stem} ({i}){extension}");
            if (!File.Exists(candidate) && !Directory.Exists(candidate) && _reservedPaths.Add(candidate))
                return candidate;
        }

        candidate = Path.Combine(folder, $"{stem}-{DateTime.Now:HHmmssfff}{extension}");
        _reservedPaths.Add(candidate);
        return candidate;
    }

    private void RemoveEmptyDirectories(string[] directories)
    {
        if (directories.Length == 0) return;

        string folder;
        lock (_gate) folder = _saveFolder;

        for (var depth = directories.Length; depth >= 1; depth--)
        {
            try
            {
                var path = Path.Combine(new[] { folder }.Concat(directories.Take(depth)).ToArray());
                if (!Directory.Exists(path)) break;
                if (Directory.EnumerateFileSystemEntries(path).Any()) break;
                Directory.Delete(path);
            }
            catch
            {
                break;
            }
        }
    }

    /// <summary>
    /// Splits an optional relative folder path from the client into safe segments.
    /// Traversal markers, separators and device names are stripped, depth and length are capped.
    /// </summary>
    private static string[] SanitizeRelativePath(string? relativePath)
    {
        if (string.IsNullOrWhiteSpace(relativePath)) return Array.Empty<string>();

        var parts = relativePath.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
        var segments = new List<string>();
        var totalLength = 0;

        foreach (var part in parts)
        {
            if (part is "." or "..") continue;

            var segment = SanitizeFileName(part);
            if (string.IsNullOrWhiteSpace(segment)) continue;

            if (segments.Count >= 12 || totalLength + segment.Length > 120) break;
            segments.Add(segment);
            totalLength += segment.Length + 1;
        }

        return segments.ToArray();
    }

    private static string BuildDisplayName(string[] directories, string fileName)
    {
        var parts = directories.Concat(new[] { fileName }).Where(p => p.Length > 0).ToArray();
        return parts.Length == 0 ? fileName : string.Join("/", parts);
    }

    private static string SanitizeFileName(string? name)
    {
        var value = name ?? string.Empty;
        value = value.Replace('/', '\\');
        var index = value.LastIndexOf('\\');
        if (index >= 0) value = value[(index + 1)..];

        var invalid = Path.GetInvalidFileNameChars();
        var chars = value
            .Where(c => !invalid.Contains(c) && !char.IsControl(c))
            .ToArray();
        value = new string(chars).Trim().TrimEnd('.', ' ');
        if (string.IsNullOrWhiteSpace(value) || value is "." or "..")
            value = "file";

        var stem = Path.GetFileNameWithoutExtension(value);
        var extension = Path.GetExtension(value);
        var reserved = new[]
        {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        };
        if (reserved.Contains(stem, StringComparer.OrdinalIgnoreCase))
            value = "_" + value;

        if (value.Length > 180)
        {
            extension = extension.Length <= 20 ? extension : extension[..20];
            var maxStem = Math.Max(1, 180 - extension.Length);
            stem = Path.GetFileNameWithoutExtension(value);
            value = (stem.Length > maxStem ? stem[..maxStem] : stem) + extension;
        }

        return value;
    }

    private static void ApplyLastModified(string path, long? unixMilliseconds)
    {
        if (unixMilliseconds is null) return;
        try
        {
            var date = DateTimeOffset.FromUnixTimeMilliseconds(unixMilliseconds.Value).UtcDateTime;
            if (date.Year is >= 1980 and <= 2200)
                File.SetLastWriteTimeUtc(path, date);
        }
        catch
        {
            // A wrong timestamp must not fail the transfer.
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    public static void CleanPartialDirectory()
    {
        try
        {
            var dir = AppPaths.PartialDirectory;
            foreach (var file in Directory.EnumerateFiles(dir, "*.part"))
                TryDelete(file);
        }
        catch (Exception ex)
        {
            AppLog.Error("Не удалось очистить временные файлы", ex);
        }
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

        return unit == 0
            ? $"{value:0} {units[unit]}"
            : $"{value:0.#} {units[unit]}";
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        _cleanupTimer.Dispose();

        UploadSession[] sessions;
        lock (_gate)
        {
            sessions = _sessions.Values.ToArray();
            _sessions.Clear();
        }

        foreach (var session in sessions)
        {
            try
            {
                session.Aborted = true;
                session.Stream.Dispose();
                TryDelete(session.TempPath);
            }
            catch { }
        }

        await Task.CompletedTask;
    }

    private sealed class UploadSession
    {
        public UploadSession(
            string id,
            string originalName,
            string safeName,
            string[] directories,
            string finalPath,
            string tempPath,
            long size,
            long? lastModified,
            FileStream stream,
            string? clientAddress)
        {
            Id = id;
            OriginalName = originalName;
            SafeName = safeName;
            Directories = directories;
            LeafName = Path.GetFileName(finalPath);
            FinalPath = finalPath;
            TempPath = tempPath;
            Size = size;
            LastModified = lastModified;
            Stream = stream;
            ClientAddress = clientAddress;
            StartedAt = DateTime.UtcNow;
            LastActivityUtc = StartedAt;
        }

        public string Id { get; }
        public string OriginalName { get; }
        public string SafeName { get; }
        public string[] Directories { get; }
        public string LeafName { get; }
        public string FinalPath { get; set; }
        public string TempPath { get; }
        public long Size { get; }
        public long? LastModified { get; }
        public FileStream Stream { get; }
        public string? ClientAddress { get; }
        public DateTime StartedAt { get; }
        public DateTime LastActivityUtc { get; set; }
        public ConcurrentDictionary<long, long> Chunks { get; } = new();
        public SpeedMeter Speed { get; } = new();
        public SemaphoreSlim CompletionGate { get; } = new(1, 1);
        public long ReceivedBytes;
        public volatile bool Aborted;
        public volatile bool Completed;
        public TransferInfo? Result { get; set; }

        public TransferInfo ToTransferInfo() => new(
            Id,
            SafeName,
            FinalPath,
            Size,
            StartedAt,
            null,
            0,
            null);

        public ActiveTransferInfo ToActiveInfo() => new(
            Id,
            SafeName,
            Size,
            Interlocked.Read(ref ReceivedBytes),
            Speed.GetSpeed(),
            StartedAt);
    }

    private sealed class SpeedMeter
    {
        private readonly object _gate = new();
        private readonly Queue<(long Ticks, long Total)> _samples = new();
        private long _lastUpdateTicks;

        public void SetTotal(long total)
        {
            lock (_gate)
            {
                var now = Stopwatch.GetTimestamp();
                _samples.Enqueue((now, total));
                _lastUpdateTicks = now;
                while (_samples.Count > 2 &&
                       Stopwatch.GetElapsedTime(_samples.Peek().Ticks, now) > TimeSpan.FromSeconds(3))
                    _samples.Dequeue();
            }
        }

        public double GetSpeed()
        {
            lock (_gate)
            {
                if (_samples.Count < 2) return 0;
                var now = Stopwatch.GetTimestamp();
                if (Stopwatch.GetElapsedTime(_lastUpdateTicks, now) > TimeSpan.FromSeconds(1.5))
                    return 0;

                var first = _samples.Peek();
                var last = _samples.Last();
                var seconds = Stopwatch.GetElapsedTime(first.Ticks, last.Ticks).TotalSeconds;
                return seconds < 0.05 ? 0 : (last.Total - first.Total) / seconds;
            }
        }
    }
}
