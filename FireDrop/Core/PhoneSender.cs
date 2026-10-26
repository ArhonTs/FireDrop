using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace FireDrop.Core;

/// <summary>
/// Sends files from the PC to a FireDrop receiver (a phone running FireDrop or another
/// PC) over the same chunked HTTP protocol the Android app uses: PIN join → folders →
/// upload sessions → parallel chunk PUTs → complete. One HttpClient keeps several
/// pooled connections busy and chunk bodies stream straight from the source file.
/// </summary>
public sealed class PhoneSender : IDisposable
{
    public const int FileQueued = 0;
    public const int FileRunning = 1;
    public const int FileDone = 2;
    public const int FileError = 3;
    public const int FileCancelled = 4;

    private const int ConnectMs = 8_000;
    private const int CopyBufferSize = 128 * 1024;
    private const int MinChunk = 256 * 1024;
    private const int MaxChunk = 64 * 1024 * 1024;

    /// <summary>One file in the send queue.</summary>
    public sealed class Item
    {
        public Item(string fullPath, string name, string relativePath)
        {
            FullPath = fullPath;
            Name = name;
            RelativePath = relativePath;
        }

        public string FullPath { get; }
        public string Name { get; }
        /// <summary>Parent folders relative to the send root; the receiver appends Name.</summary>
        public string RelativePath { get; }

        public long Size { get; set; }
        public long LastModified { get; set; }

        public long Sent;
        public volatile int State = FileQueued;
        public volatile string Error = "";
        public string Location = "";
        public string UploadId = "";
    }

    public sealed class ProtocolError : Exception
    {
        public readonly int Status;

        public ProtocolError(int status, string message) : base(message) => Status = status;
    }

    /// <summary>Result of an unauthenticated /api/health probe.</summary>
    public sealed record HealthReply(bool Ok, string Problem, string DeviceName);

    private readonly string _host;
    private readonly int _port;
    private readonly string _pin;
    private readonly List<Item> _items;
    private readonly List<string> _folders;

    private HttpClient? _http;
    private volatile string _cookie = "";

    public volatile bool Cancelled;
    public volatile bool Finished;
    public volatile string FatalError = "";
    public volatile string DeviceName = "";
    public long StartedAt;
    public long FinishedAt;
    public volatile int ChunkSize = 8 * 1024 * 1024;
    public volatile int ParallelStreams = 6;

    public PhoneSender(string address, string pin, IReadOnlyList<Item> items, IReadOnlyList<string> folders)
    {
        (_host, _port) = ParseAddress(address);
        _pin = pin;
        _items = items
            .OrderBy(item => item.RelativePath, StringComparer.Ordinal)
            .ThenBy(item => item.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
        foreach (var item in _items)
        {
            try
            {
                var info = new FileInfo(item.FullPath);
                if (info.Exists)
                {
                    item.Size = info.Length;
                    item.LastModified =
                        new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeMilliseconds();
                }
                else
                {
                    item.State = FileError;
                    item.Error = L10n.Get("core.send.filenotfound");
                }
            }
            catch (Exception)
            {
                item.State = FileError;
                item.Error = L10n.Get("core.send.filereadfailed");
            }
        }

        _folders = folders
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.Ordinal)
            .ToList();
    }

    public string Target => DeviceName.Length > 0 ? DeviceName : $"{_host}:{_port}";
    public IReadOnlyList<Item> Items => _items;
    public IReadOnlyList<string> Folders => _folders;
    public long TotalBytes => _items.Sum(item => Math.Max(0, item.Size));
    public long SentBytes => _items.Sum(item => Math.Max(0, item.Sent));
    public int CompletedCount => _items.Count(item => item.State == FileDone);
    public int FailedCount => _items.Count(item => item.State == FileError);
    public List<Item> FailedItems => _items.Where(item => item.State == FileError).ToList();

    // -------------------------------------------------------------- address

    /// <summary>Parses "192.168.1.10", "192.168.1.10:47820" or "http://host:port/anything".</summary>
    public static (string Host, int Port) ParseAddress(string text)
    {
        var value = (text ?? string.Empty).Trim();
        if (value.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) value = value[7..];
        else if (value.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) value = value[8..];

        var slash = value.IndexOf('/');
        if (slash >= 0) value = value[..slash];

        var colon = value.LastIndexOf(':');
        if (colon > 0 && colon < value.Length - 1)
        {
            var portPart = value[(colon + 1)..];
            if (portPart.Length is >= 2 and <= 5 && portPart.All(char.IsDigit) &&
                int.TryParse(portPart, out var parsed) && parsed is >= 1024 and <= 65535)
            {
                return (value[..colon].Trim(), parsed);
            }
        }

        return (value.Trim(), 47820);
    }

    /// <summary>
    /// Parses either a plain address ("192.168.1.5:47820") or the QR payload of the
    /// phone app ("firedrop://send?host=192.168.1.5&port=47830&pin=123456").
    /// Returns the PIN as well when the payload carries one.
    /// </summary>
    public static (string Host, int Port, string Pin, bool IsLink) ParseLink(string text)
    {
        var value = (text ?? string.Empty).Trim();
        if (!value.StartsWith("firedrop://", StringComparison.OrdinalIgnoreCase)) return ("", 0, "", false);

        var query = value.IndexOf('?', StringComparison.Ordinal);
        if (query < 0) return ("", 0, "", false);

        string host = "";
        var port = 47820;
        var pin = "";

        foreach (var pair in value[(query + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = pair.IndexOf('=', StringComparison.Ordinal);
            if (eq <= 0) continue;

            var key = Uri.UnescapeDataString(pair[..eq].Trim());
            var raw = Uri.UnescapeDataString(pair[(eq + 1)..].Trim());
            if (key.Equals("host", StringComparison.OrdinalIgnoreCase) && raw.Length > 0) host = raw;
            else if (key.Equals("port", StringComparison.OrdinalIgnoreCase) &&
                     int.TryParse(raw, out var parsed) && parsed is >= 1024 and <= 65535) port = parsed;
            else if (key.Equals("pin", StringComparison.OrdinalIgnoreCase) ||
                     key.Equals("code", StringComparison.OrdinalIgnoreCase)) pin = raw;
        }

        return host.Length > 0 ? (host, port, pin, true) : ("", 0, "", false);
    }

    private Uri BaseAddress()
    {
        var hostPart = _host.Contains(':') && !_host.StartsWith('[') ? $"[{_host}]" : _host;
        return new Uri($"http://{hostPart}:{_port}/");
    }

    private static Uri BaseUri(string host, int port)
    {
        var hostPart = host.Contains(':') && !host.StartsWith('[') ? $"[{host}]" : host;
        return new Uri($"http://{hostPart}:{port}/");
    }

    /// <summary>Checks /api/health without credentials to see if a FireDrop receiver answers.</summary>
    public static async Task<HealthReply> ProbeAsync(string address, CancellationToken cancellation)
    {
        var (host, port) = ParseAddress(address);
        using var client = CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get,
            new Uri(BaseUri(host, port), "/api/health"));

        HttpResponseMessage response;
        try
        {
            using var timeout = TimeoutToken(cancellation, 4_000, 6_000);
            response = await client.SendAsync(request, HttpCompletionOption.ResponseContentRead, timeout.Token);
        }
        catch (OperationCanceledException)
        {
            return new HealthReply(false, L10n.Get("core.send.health.dead"), "");
        }
        catch (Exception)
        {
            return new HealthReply(false, L10n.Get("core.send.health.dead"), "");
        }

        var body = await SafeBodyAsync(response, cancellation);
        var json = ParseObject(body);
        var app = json["app"]?.ToString();
        var ok = (int)response.StatusCode is >= 200 and < 300 && app == "FireDrop";
        if (!ok) return new HealthReply(false, L10n.Get("core.send.health.notfiredrop"), "");
        return new HealthReply(true, "", json["deviceName"]?.ToString() ?? "");
    }

    // ------------------------------------------------------------------ run

    public void Cancel()
    {
        Cancelled = true;
        foreach (var item in _items)
        {
            if (item.State == FileQueued) item.State = FileCancelled;
        }
    }

    public async Task RunAsync(CancellationToken cancellation)
    {
        StartedAt = Environment.TickCount64;
        var ok = true;
        try
        {
            _http = CreateClient();
            await JoinAsync(cancellation);
            await ReadInfoAsync(cancellation);
            await CreateFoldersAsync(cancellation);

            // Big files go one by one with parallel chunk streams; small files use a
            // limited pool so a folder of thousands of photos does not crawl on latency.
            var large = _items.Where(item => item.Size > ChunkSize).ToList();
            var small = _items.Where(item => item.Size <= ChunkSize).ToList();

            foreach (var item in large)
            {
                cancellation.ThrowIfCancellationRequested();
                await SendFileAsync(item, ParallelStreams, cancellation);
            }

            if (small.Count > 0)
            {
                var gate = new SemaphoreSlim(Math.Min(4, small.Count));
                var tasks = small.Select(async item =>
                {
                    var acquired = false;
                    try
                    {
                        await gate.WaitAsync(cancellation);
                        acquired = true;
                        await SendFileAsync(item, 1, cancellation);
                    }
                    catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
                    {
                        // The item stays queued; the RunAsync finally marks it cancelled.
                    }
                    finally
                    {
                        if (acquired) gate.Release();
                    }
                }).ToArray();
                await Task.WhenAll(tasks);
            }

            if (!cancellation.IsCancellationRequested)
                ok = FailedCount == 0;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            Cancelled = true;
        }
        catch (Exception ex)
        {
            ok = false;
            FatalError = Describe(ex);
        }
        finally
        {
            foreach (var item in _items)
            {
                if (item.State is FileQueued or FileRunning)
                {
                    item.State = Cancelled ? FileCancelled : FileError;
                    if (item.State == FileError && item.Error.Length == 0)
                        item.Error = FatalError.Length > 0 ? FatalError : L10n.Get("core.send.interrupted");
                }
            }

            if (Cancelled)
            {
                FatalError = L10n.Get("core.upload.cancelled");
            }
            else if (FatalError.Length == 0 && FailedCount > 0)
            {
                FatalError = L10n.S("core.send.failedcount", FailedCount);
            }

            FinishedAt = Environment.TickCount64;
            Finished = true;
            _http?.Dispose();
            _http = null;
        }
    }

    // ----------------------------------------------------------------- join

    private async Task JoinAsync(CancellationToken cancellation)
    {
        var reply = await RequestAsync(HttpMethod.Post, "/api/join",
            new JsonObject { ["pin"] = _pin }, ConnectMs, 15_000, cancellation);

        var token = reply["token"]?.ToString() ?? "";
        var device = reply["deviceName"]?.ToString();
        if (!string.IsNullOrWhiteSpace(device)) DeviceName = device;

        if (_cookie.Length == 0 && token.Length > 0)
            _cookie = $"{TransferServer.CookieName}={token}";

        if (_cookie.Length == 0)
            throw new ProtocolError(0, L10n.Get("core.send.join.notconfirmed"));
    }

    private async Task ReadInfoAsync(CancellationToken cancellation)
    {
        try
        {
            var reply = await RequestAsync(HttpMethod.Get, "/api/info", null, ConnectMs, 15_000, cancellation);
            var name = reply["deviceName"]?.ToString();
            if (!string.IsNullOrWhiteSpace(name)) DeviceName = name;

            if (reply["chunkSize"] is JsonValue size && size.TryGetValue<int>(out var parsed) &&
                parsed is >= MinChunk and <= MaxChunk) ChunkSize = parsed;

            if (reply["parallelChunks"] is JsonValue streams &&
                streams.TryGetValue<int>(out var streamsParsed) && streamsParsed is >= 1 and <= 16)
            {
                ParallelStreams = streamsParsed;
            }
        }
        catch (Exception)
        {
            // Info is only a hint; the protocol defaults keep the transfer possible.
        }
    }

    private async Task CreateFoldersAsync(CancellationToken cancellation)
    {
        foreach (var folder in _folders
                     .OrderBy(path => path.Length)
                     .ThenBy(path => path, StringComparer.Ordinal))
        {
            cancellation.ThrowIfCancellationRequested();
            try
            {
                await RequestAsync(HttpMethod.Post, "/api/folders",
                    new JsonObject { ["path"] = folder }, ConnectMs, 15_000, cancellation);
            }
            catch (ProtocolError error) when (error.Status is 404 or 405)
            {
                // Older receiver without folder pre-creation: file uploads create folders anyway.
                return;
            }
            catch
            {
                // One failed folder must not stop the transfer.
            }
        }
    }

    // ---------------------------------------------------------------- files

    private async Task SendFileAsync(Item item, int streams, CancellationToken cancellation)
    {
        if (item.State != FileQueued) return;
        item.State = FileRunning;

        try
        {
            var init = new JsonObject
            {
                ["name"] = item.Name,
                ["size"] = Math.Max(0, item.Size),
                ["lastModified"] = item.LastModified,
                ["relativePath"] = item.RelativePath,
            };

            var created = await RequestAsync(HttpMethod.Post, "/api/uploads", init, 15_000, 30_000, cancellation);
            item.UploadId = created["id"]?.ToString() ?? "";

            if (item.UploadId.Length == 0)
                throw new ProtocolError(0, L10n.Get("core.send.start.denied"));

            if (created["chunkSize"] is JsonValue chunkValue &&
                chunkValue.TryGetValue<int>(out var chunk) && chunk is >= MinChunk and <= MaxChunk)
            {
                ChunkSize = chunk;
            }

            if (item.Size > 0) await UploadChunksAsync(item, Math.Max(1, streams), cancellation);

            item.Location = await CompleteAsync(item, cancellation);
            item.Sent = item.Size;
            item.State = FileDone;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            item.State = FileCancelled;
        }
        catch (Exception ex)
        {
            item.Error = Describe(ex);
            item.State = FileError;
            await DeleteUploadAsync(item);
        }
    }

    private async Task<string> CompleteAsync(Item item, CancellationToken cancellation)
    {
        Exception? last = null;
        for (var attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                var reply = await RequestAsync(HttpMethod.Post,
                    $"/api/uploads/{item.UploadId}/complete", null, 15_000, 600_000, cancellation);
                return reply["file"]?["name"]?.ToString()
                       ?? reply["file"]?["path"]?.ToString()
                       ?? item.Name;
            }
            catch (ProtocolError error) when (error.Status == 409)
            {
                // 409 conflict: the receiver has already saved this upload.
                return item.Name;
            }
            catch (ProtocolError error) when (error.Status is >= 400 and < 500)
            {
                throw;
            }
            catch (Exception ex) when (ex is not OperationCanceledException || cancellation.IsCancellationRequested)
            {
                last = ex;
                if (attempt < 2)
                    await Task.Delay(TimeSpan.FromMilliseconds(600), CancellationToken.None);
            }
        }

        throw last ?? new ProtocolError(0, L10n.Get("core.send.save.notconfirmed"));
    }

    private async Task UploadChunksAsync(Item item, int streams, CancellationToken cancellation)
    {
        var size = item.Size;
        var chunk = ChunkSize;
        var chunks = (size + chunk - 1) / chunk;
        var workers = Math.Max(1, Math.Min(streams, (int)chunks));
        var next = 0;
        var gate = new object();
        Exception? failure = null;

        var tasks = Enumerable.Range(0, workers).Select(_ => Task.Run(async () =>
        {
            while (true)
            {
                if (cancellation.IsCancellationRequested) return;
                bool failed;
                lock (gate) failed = failure != null;
                if (failed) return;

                int index;
                lock (gate)
                {
                    if (next >= chunks) return;
                    index = next++;
                }

                var offset = (long)index * chunk;
                var length = (int)Math.Min(chunk, size - offset);
                try
                {
                    await PutChunkAsync(item, offset, length, cancellation);
                }
                catch (Exception ex)
                {
                    lock (gate) failure ??= ex;
                    return;
                }
            }
        })).ToArray();

        await Task.WhenAll(tasks);
        if (failure != null) throw failure;
        cancellation.ThrowIfCancellationRequested();
    }

    private async Task PutChunkAsync(Item item, long offset, int length, CancellationToken outer)
    {
        Exception? last = null;
        for (var attempt = 1; attempt <= 3; attempt++)
        {
            if (outer.IsCancellationRequested)
                throw new OperationCanceledException(outer);

            try
            {
                await PutChunkOnceAsync(item, offset, length, outer);
                return;
            }
            catch (ProtocolError error) when (error.Status is 400 or 404 or 410 or 507)
            {
                throw;
            }
            catch (Exception ex) when (ex is OperationCanceledException && outer.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                last = ex;
                if (attempt < 3)
                    await Task.Delay(TimeSpan.FromMilliseconds(350 * attempt), CancellationToken.None);
            }
        }

        // A swallowed failure here would silently leave a hole in the file.
        throw last ?? new ProtocolError(0, L10n.Get("core.send.chunkfailed"));
    }

    private async Task PutChunkOnceAsync(Item item, long offset, int length, CancellationToken outer)
    {
        var http = _http ?? throw new InvalidOperationException(L10n.Get("core.send.connectionclosed"));
        using var timeout = TimeoutToken(outer, 20_000, 150_000);
        using var request = new HttpRequestMessage(HttpMethod.Put,
            new Uri(BaseAddress(), $"/api/uploads/{item.UploadId}/chunk?offset={offset}"));
        if (_cookie.Length > 0) request.Headers.TryAddWithoutValidation("Cookie", _cookie);

        // The chunk travels as one byte[] of the exact advertized length, so the
        // request framing can never disagree with the declared Content-Length.
        var bytes = new byte[length];
        var total = 0;
        try
        {
            using (var stream = new FileStream(item.FullPath, FileMode.Open, FileAccess.Read,
                FileShare.Read, CopyBufferSize, FileOptions.Asynchronous))
            {
                stream.Seek(offset, SeekOrigin.Begin);
                while (total < length)
                {
                    var read = await stream.ReadAsync(bytes.AsMemory(total, length - total), outer);
                    if (read <= 0)
                        throw new ProtocolError(0, L10n.S("core.send.filechanged", item.Name));
                    total += read;
                }
            }
        }
        catch (Exception error) when (error is not OperationCanceledException &&
                                      error is not ProtocolError &&
                                      outer.IsCancellationRequested)
        {
            throw new OperationCanceledException(outer);
        }

        using var content = new ByteArrayContent(bytes);
        content.Headers.TryAddWithoutValidation("Content-Type", "application/octet-stream");
        request.Content = content;

        using var response = await http.SendAsync(request,
            HttpCompletionOption.ResponseHeadersRead, timeout.Token);

        // Drain the (tiny) reply so the pooled connection can be reused.
        var text = await SafeBodyAsync(response, timeout.Token);
        var json = ParseObject(text);
        if (!response.IsSuccessStatusCode)
        {
            var message = json["error"]?.ToString();
            throw new ProtocolError((int)response.StatusCode,
                string.IsNullOrWhiteSpace(message)
                    ? L10n.S("core.send.httperror", (int)response.StatusCode)
                    : message);
        }
    }

    private async Task DeleteUploadAsync(Item item)
    {
        if (item.UploadId.Length == 0) return;
        try
        {
            var http = _http;
            if (http is null) return;
            using var request = new HttpRequestMessage(HttpMethod.Delete,
                new Uri(BaseAddress(), $"/api/uploads/{item.UploadId}"));
            if (_cookie.Length > 0) request.Headers.TryAddWithoutValidation("Cookie", _cookie);
            using var timeout = TimeoutToken(CancellationToken.None, 6_000, 8_000);
            await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, timeout.Token);
        }
        catch
        {
            // Best-effort cleanup only.
        }
    }

    // ------------------------------------------------------------- plumbing

    private static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            UseCookies = false,
            UseProxy = false,
            AutomaticDecompression = DecompressionMethods.None,
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
            ConnectTimeout = TimeSpan.FromSeconds(ConnectMs / 1000),
        };
        var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.TryAddWithoutValidation(
            "User-Agent", $"FireDrop-PC/{TransferServer.Version}");
        client.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "application/json");
        return client;
    }

    private static CancellationTokenSource TimeoutToken(CancellationToken outer, int connectMs, int readMs)
    {
        var linked = CancellationTokenSource.CreateLinkedTokenSource(outer);
        linked.CancelAfter(TimeSpan.FromMilliseconds(connectMs + readMs));
        return linked;
    }

    private static async Task<string> SafeBodyAsync(HttpResponseMessage response, CancellationToken cancellation)
    {
        try
        {
            return Encoding.UTF8.GetString(
                await response.Content.ReadAsByteArrayAsync(cancellation));
        }
        catch
        {
            return "";
        }
    }

    private static JsonObject ParseObject(string text)
    {
        if (!string.IsNullOrWhiteSpace(text))
        {
            try
            {
                if (JsonNode.Parse(text) is JsonObject json) return json;
            }
            catch
            {
                // Fall through to the empty object below.
            }
        }
        return new JsonObject();
    }

    private async Task<JsonObject> RequestAsync(HttpMethod method, string path, JsonObject? body,
        int connectMs, int readMs, CancellationToken cancellation)
    {
        var http = _http ?? throw new InvalidOperationException(L10n.Get("core.send.connectionclosed"));
        using var request = new HttpRequestMessage(method, new Uri(BaseAddress(), path.TrimStart('/')));
        if (_cookie.Length > 0) request.Headers.TryAddWithoutValidation("Cookie", _cookie);
        if (body is not null)
        {
            request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        }

        using var timeout = TimeoutToken(cancellation, connectMs, readMs);
        using var response = await http.SendAsync(request,
            HttpCompletionOption.ResponseContentRead, timeout.Token);

        var text = await SafeBodyAsync(response, timeout.Token);
        var json = ParseObject(text);
        var status = (int)response.StatusCode;

        if (_cookie.Length == 0)
        {
            var setCookie = response.Headers.TryGetValues("Set-Cookie", out var values)
                ? values.FirstOrDefault()
                : null;
            var pair = setCookie?.Split(';')[0].Trim();
            if (!string.IsNullOrEmpty(pair) && pair.Contains('=', StringComparison.Ordinal))
            {
                _cookie = pair;
            }
            else
            {
                var token = json["token"]?.ToString();
                if (!string.IsNullOrEmpty(token))
                    _cookie = $"{TransferServer.CookieName}={token}";
            }

            var device = json["deviceName"]?.ToString();
            if (!string.IsNullOrWhiteSpace(device) && DeviceName.Length == 0) DeviceName = device;
        }

        if (status >= 400)
        {
            var error = json["error"]?.ToString();
            throw new ProtocolError(status,
                string.IsNullOrWhiteSpace(error) ? L10n.S("core.send.servererror", status) : error);
        }

        return json;
    }

    private static string Describe(Exception error)
    {
        if (error is ProtocolError protocol && protocol.Message.Length > 0) return protocol.Message;

        if (error is TaskCanceledException or OperationCanceledException)
            return L10n.Get("core.send.timeout");

        var text = string.IsNullOrEmpty(error.Message) ? error.GetType().Name : error.Message;
        if (error.InnerException is not null && !string.IsNullOrEmpty(error.InnerException.Message))
        {
            text = error.InnerException.Message;
        }

        if (text.Contains("no connection", StringComparison.OrdinalIgnoreCase) ||
            text.Contains("refused", StringComparison.OrdinalIgnoreCase))
        {
            return L10n.Get("core.send.unreachable");
        }
        if (text.Contains("timed out", StringComparison.OrdinalIgnoreCase) ||
            text.Contains("timeout", StringComparison.OrdinalIgnoreCase))
        {
            return L10n.Get("core.send.timeout");
        }
        if (text.Contains("host unreachable", StringComparison.OrdinalIgnoreCase) ||
            text.Contains("not reachable", StringComparison.OrdinalIgnoreCase))
        {
            return L10n.Get("core.send.nonetwork");
        }
        if (text.Contains("name or service", StringComparison.OrdinalIgnoreCase) ||
            text.Contains("getaddrinfo", StringComparison.OrdinalIgnoreCase))
        {
            return L10n.Get("core.send.address.notfound");
        }
        return text.Length <= 120 ? text : text[..120] + "…";
    }

    public void Dispose()
    {
        _http?.Dispose();
        _http = null;
    }
}
