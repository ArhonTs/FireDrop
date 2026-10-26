using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace FireDrop.Core;

public sealed class TransferServer
{
    public const string CookieName = "firedrop_session";
    private const int MaxJoinFailures = 8;
    private static readonly TimeSpan JoinWindow = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan JoinLockout = TimeSpan.FromMinutes(2);

    private readonly UploadManager _uploads;
    private readonly WebAssetStore _assets = new();
    private readonly JoinLimiter _joinLimiter = new();
    private readonly DiscoveryService _discovery;
    private WebApplication? _app;
    private int _port;
    private long _lastActivityTicks;
    private string _deviceName;

    public TransferServer(UploadManager uploads, string deviceName)
    {
        _uploads = uploads;
        _deviceName = NormalizeDeviceName(deviceName);
        _discovery = new DiscoveryService(() => _deviceName, () => _port);
        Token = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        Pin = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
    }

    public string Token { get; }
    public string Pin { get; }
    public int Port => _port;
    public bool IsRunning => _app is not null;

    public string DeviceName
    {
        get => _deviceName;
        set => _deviceName = NormalizeDeviceName(value);
    }

    private static string NormalizeDeviceName(string? value)
    {
        var name = string.IsNullOrWhiteSpace(value) ? Environment.MachineName : value.Trim();
        return name.Length > 40 ? name[..40] : name;
    }

    public static string Version =>
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.2.3";

    public event Action? ClientActivity;

    public string BuildUrl(string ip, bool includeToken)
    {
        var url = $"http://{ip}:{_port}";
        return includeToken ? $"{url}/?t={Token}" : url;
    }

    public async Task StartAsync(int port)
    {
        if (_app is not null)
            throw new InvalidOperationException(L10n.Get("core.server.alreadyrunning"));

        _port = port;
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ContentRootPath = AppContext.BaseDirectory,
            EnvironmentName = Environments.Production,
        });

        builder.Logging.ClearProviders();
        builder.Logging.SetMinimumLevel(LogLevel.None);

        builder.WebHost.ConfigureKestrel(options =>
        {
            options.ListenAnyIP(port);
            options.AddServerHeader = false;
            options.AllowSynchronousIO = false;
            options.Limits.MaxRequestBodySize = null;
            options.Limits.KeepAliveTimeout = TimeSpan.FromMinutes(5);
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(30);
            options.Limits.MinRequestBodyDataRate = null;
        });

        builder.Services.ConfigureHttpJsonOptions(options =>
        {
            options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
        });

        var app = builder.Build();
        ConfigurePipeline(app);

        try
        {
            await app.StartAsync();
        }
        catch
        {
            await app.DisposeAsync();
            throw;
        }

        _app = app;
        _discovery.Start();
        AppLog.Info($"Сервер FireDrop запущен на порту {_port}, устройство: {_deviceName}");

#if DEBUG
        try
        {
            File.WriteAllText(
                Path.Combine(AppPaths.DataDirectory, "debug-session.txt"),
                $"token={Token}{Environment.NewLine}pin={Pin}{Environment.NewLine}port={_port}{Environment.NewLine}");
        }
        catch { }
#endif
    }

    public async Task StopAsync()
    {
        var app = _app;
        _app = null;
        _discovery.Stop();
        if (app is null) return;

        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await app.StopAsync(cts.Token);
        }
        catch (Exception ex)
        {
            AppLog.Error("Ошибка остановки сервера", ex);
        }
        finally
        {
            await app.DisposeAsync();
            AppLog.Info("Сервер FireDrop остановлен");
        }
    }

    private void ConfigurePipeline(WebApplication app)
    {
        app.Use(async (context, next) =>
        {
            try
            {
                await next();
            }
            catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
            {
                // The phone went away mid-request. Nothing to send.
            }
            catch (BadHttpRequestException ex)
            {
                await WriteErrorAsync(context, ex.StatusCode, ex.Message);
            }
            catch (UploadException ex)
            {
                await WriteErrorAsync(context, ex.StatusCode, ex.Message);
            }
            catch (Exception ex)
            {
                AppLog.Error($"Необработанная ошибка HTTP {context.Request.Method} {context.Request.Path}", ex);
                await WriteErrorAsync(context, 500, L10n.Get("core.server.internal"));
            }
        });

        app.Use(async (context, next) =>
        {
            var headers = context.Response.Headers;
            headers["X-Content-Type-Options"] = "nosniff";
            headers["Referrer-Policy"] = "no-referrer";
            headers["X-Frame-Options"] = "DENY";
            headers["Cross-Origin-Resource-Policy"] = "same-origin";

            if (context.Request.Path.StartsWithSegments("/api"))
                headers["Cache-Control"] = "no-store";

            await next();
        });

        app.Use(async (context, next) =>
        {
            var path = context.Request.Path.Value ?? string.Empty;
            if (path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase) &&
                !path.Equals("/api/join", StringComparison.OrdinalIgnoreCase) &&
                !path.Equals("/api/health", StringComparison.OrdinalIgnoreCase) &&
                !IsAuthorized(context))
            {
                await WriteErrorAsync(context, StatusCodes.Status401Unauthorized, L10n.Get("core.server.unauthorized"));
                return;
            }

            if (path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase) && IsAuthorized(context))
            {
                var now = Stopwatch.GetTimestamp();
                var previous = Interlocked.Read(ref _lastActivityTicks);
                if (Stopwatch.GetElapsedTime(previous, now) > TimeSpan.FromSeconds(2) &&
                    Interlocked.CompareExchange(ref _lastActivityTicks, now, previous) == previous)
                {
                    ClientActivity?.Invoke();
                }
            }

            await next();
        });

        app.MapGet("/", (HttpContext context) =>
        {
            if (context.Request.Query.TryGetValue("t", out var queryToken) &&
                SecureEquals(queryToken.ToString(), Token))
            {
                SetAuthCookie(context);
                return (IResult)Results.Redirect("/");
            }

            if (IsAuthorized(context))
                return ServeAsset("index.html", context);

            return ServeAsset("join.html", context);
        });

        app.MapGet("/join", (HttpContext context) =>
            IsAuthorized(context)
                ? (IResult)Results.Redirect("/")
                : ServeAsset("join.html", context));

        app.MapGet("/api/health", () => Results.Json(new
        {
            ok = true,
            app = "FireDrop",
            version = Version,
        }));

        app.MapGet("/api/info", () => Results.Json(new
        {
            deviceName = _deviceName,
            hostName = Environment.MachineName,
            chunkSize = UploadManager.ChunkSize,
            parallelChunks = UploadManager.ParallelChunks,
            folders = true,
            version = Version,
        }));

        app.MapPost("/api/join", (HttpContext context, JoinRequest request) =>
        {
            var ip = ClientIp(context);
            if (_joinLimiter.IsBlocked(ip))
            {
                return Results.Json(
                    new { error = L10n.Get("core.server.join.ratelimited") },
                    statusCode: StatusCodes.Status429TooManyRequests);
            }

            var pin = (request.Pin ?? string.Empty).Replace(" ", string.Empty).Replace("-", string.Empty);
            if (!SecureEquals(pin, Pin))
            {
                _joinLimiter.RegisterFailure(ip);
                return Results.Json(
                    new { error = L10n.Get("core.server.join.badpin") },
                    statusCode: StatusCodes.Status401Unauthorized);
            }

            _joinLimiter.Reset(ip);
            SetAuthCookie(context);
            ClientActivity?.Invoke();
            return Results.Json(new { ok = true });
        });

        app.MapPost("/api/uploads", (HttpContext context, UploadInitRequest request) =>
        {
            if (request.Size < 0)
                throw new UploadException(400, L10n.Get("core.upload.badsize"));

            var info = _uploads.Create(
                request.Name,
                request.Size,
                request.LastModified,
                ClientIp(context),
                request.RelativePath);

            return Results.Json(new UploadInitResponse(
                info.Id,
                UploadManager.ChunkSize,
                UploadManager.ParallelChunks));
        });

        app.MapPost("/api/folders", (CreateFolderRequest request) =>
        {
            _uploads.CreateFolder(request.Path);
            return Results.Json(new { ok = true });
        });

        app.MapPut("/api/uploads/{id}/chunk", async (HttpContext context, string id) =>
        {
            if (!long.TryParse(context.Request.Query["offset"].ToString(), out var offset) || offset < 0)
                throw new UploadException(400, L10n.Get("core.upload.nooffset"));

            var contentLength = context.Request.ContentLength;
            if (contentLength is null)
                throw new UploadException(411, L10n.Get("core.upload.nolength"));

            await _uploads.WriteChunkAsync(
                id,
                offset,
                contentLength.Value,
                context.Request.Body,
                context.RequestAborted);

            return Results.Json(new { ok = true });
        });

        app.MapPost("/api/uploads/{id}/complete", async (string id) =>
        {
            var info = await _uploads.CompleteAsync(id);
            return Results.Json(new
            {
                ok = true,
                file = new
                {
                    name = info.FileName,
                    size = info.Size,
                    savedAt = info.CompletedAt,
                    speed = info.AvgBytesPerSecond,
                },
            });
        });

        app.MapDelete("/api/uploads/{id}", async (string id) =>
        {
            await _uploads.AbortAsync(id, L10n.Get("core.upload.cancelledbyphone"));
            return Results.NoContent();
        });

        foreach (var (route, fileName) in StaticRoutes)
            app.MapGet(route, (HttpContext context) => ServeAsset(fileName, context));
    }

    private static readonly Dictionary<string, string> StaticRoutes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["/app.js"] = "app.js",
        ["/join.js"] = "join.js",
        ["/style.css"] = "style.css",
        ["/manifest.webmanifest"] = "manifest.webmanifest",
        ["/icon-192.png"] = "icon-192.png",
        ["/icon-512.png"] = "icon-512.png",
        ["/apple-touch-icon.png"] = "apple-touch-icon.png",
        ["/favicon-32.png"] = "favicon-32.png",
        ["/favicon.ico"] = "favicon-32.png",
    };

    private IResult ServeAsset(string fileName, HttpContext context)
    {
        if (!_assets.TryGet(fileName, out var bytes, out var contentType))
            return Results.NotFound();

        var isHtml = fileName.EndsWith(".html", StringComparison.OrdinalIgnoreCase);
        if (isHtml)
        {
            context.Response.Headers["Content-Security-Policy"] =
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; " +
                "form-action 'self'; frame-ancestors 'none'";
            context.Response.Headers["Cache-Control"] = "no-store";
        }
        else if (fileName.EndsWith(".png", StringComparison.OrdinalIgnoreCase) ||
                 fileName.EndsWith(".ico", StringComparison.OrdinalIgnoreCase))
        {
            context.Response.Headers["Cache-Control"] = "public, max-age=86400";
        }
        else
        {
            context.Response.Headers["Cache-Control"] = "no-cache";
        }

        return Results.Bytes(bytes, contentType);
    }

    private bool IsAuthorized(HttpContext context)
    {
        if (context.Request.Cookies.TryGetValue(CookieName, out var cookie) && SecureEquals(cookie, Token))
            return true;

        if (context.Request.Query.TryGetValue("t", out var query) && SecureEquals(query.ToString(), Token))
            return true;

        return false;
    }

    private void SetAuthCookie(HttpContext context)
    {
        context.Response.Cookies.Append(
            CookieName,
            Token,
            new CookieOptions
            {
                HttpOnly = true,
                SameSite = SameSiteMode.Lax,
                Path = "/",
                IsEssential = true,
                MaxAge = TimeSpan.FromDays(30),
            });
    }

    private static string ClientIp(HttpContext context)
    {
        var ip = context.Connection.RemoteIpAddress;
        if (ip is null) return "unknown";
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        return ip.ToString();
    }

    private static bool SecureEquals(string? a, string? b)
    {
        if (a is null || b is null) return false;
        var left = Encoding.UTF8.GetBytes(a);
        var right = Encoding.UTF8.GetBytes(b);
        return left.Length == right.Length && CryptographicOperations.FixedTimeEquals(left, right);
    }

    private static async Task WriteErrorAsync(HttpContext context, int statusCode, string message)
    {
        if (context.Response.HasStarted)
            return;

        context.Response.StatusCode = statusCode;
        context.Response.ContentType = "application/json; charset=utf-8";
        await context.Response.WriteAsJsonAsync(new { error = message });
    }

    private sealed record JoinRequest(string? Pin);
    private sealed record UploadInitRequest(string? Name, long Size, long? LastModified, string? RelativePath);
    private sealed record UploadInitResponse(string Id, int ChunkSize, int ParallelChunks);
    private sealed record CreateFolderRequest(string? Path);

    private sealed class JoinLimiter
    {
        private readonly object _gate = new();
        private readonly Dictionary<string, Queue<DateTime>> _failures = new(StringComparer.Ordinal);
        private readonly Dictionary<string, DateTime> _blockedUntil = new(StringComparer.Ordinal);

        public bool IsBlocked(string ip)
        {
            lock (_gate)
            {
                if (_blockedUntil.TryGetValue(ip, out var until))
                {
                    if (until > DateTime.UtcNow) return true;
                    _blockedUntil.Remove(ip);
                    _failures.Remove(ip);
                }

                return false;
            }
        }

        public void RegisterFailure(string ip)
        {
            lock (_gate)
            {
                if (!_failures.TryGetValue(ip, out var queue))
                {
                    queue = new Queue<DateTime>();
                    _failures[ip] = queue;
                }

                var now = DateTime.UtcNow;
                queue.Enqueue(now);
                while (queue.Count > 0 && now - queue.Peek() > JoinWindow)
                    queue.Dequeue();

                if (queue.Count >= MaxJoinFailures)
                {
                    _blockedUntil[ip] = now + JoinLockout;
                    queue.Clear();
                }

                if (_failures.Count > 200)
                {
                    foreach (var key in _failures.Keys.ToArray())
                    {
                        if (_failures[key].Count == 0 || now - _failures[key].Peek() > JoinWindow)
                            _failures.Remove(key);
                    }
                }
            }
        }

        public void Reset(string ip)
        {
            lock (_gate)
            {
                _failures.Remove(ip);
                _blockedUntil.Remove(ip);
            }
        }
    }
}

internal sealed class WebAssetStore
{
    private static readonly Dictionary<string, string> MimeTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        [".html"] = "text/html; charset=utf-8",
        [".css"] = "text/css; charset=utf-8",
        [".js"] = "application/javascript; charset=utf-8",
        [".webmanifest"] = "application/manifest+json; charset=utf-8",
        [".png"] = "image/png",
        [".ico"] = "image/x-icon",
        [".svg"] = "image/svg+xml",
    };

    private readonly Dictionary<string, (byte[] Bytes, string ContentType)> _files =
        new(StringComparer.OrdinalIgnoreCase);

    public WebAssetStore()
    {
        var assembly = typeof(WebAssetStore).Assembly;
        foreach (var name in assembly.GetManifestResourceNames())
        {
            var marker = ".Web.";
            var index = name.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
            if (index < 0) continue;

            var fileName = name[(index + marker.Length)..];
            var extension = Path.GetExtension(fileName);
            if (!MimeTypes.TryGetValue(extension, out var contentType))
                contentType = "application/octet-stream";

            using var stream = assembly.GetManifestResourceStream(name);
            if (stream is null) continue;
            using var memory = new MemoryStream();
            stream.CopyTo(memory);
            _files[fileName] = (memory.ToArray(), contentType);
        }
    }

    public bool TryGet(string fileName, out byte[] bytes, out string contentType)
    {
        if (_files.TryGetValue(fileName, out var entry))
        {
            bytes = entry.Bytes;
            contentType = entry.ContentType;
            return true;
        }

        bytes = Array.Empty<byte>();
        contentType = "application/octet-stream";
        return false;
    }
}

