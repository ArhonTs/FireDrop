namespace FireDrop.Core;

public sealed record TransferInfo(
    string Id,
    string FileName,
    string FullPath,
    long Size,
    DateTime StartedAt,
    DateTime? CompletedAt,
    double AvgBytesPerSecond,
    string? Error);

public sealed record ActiveTransferInfo(
    string Id,
    string FileName,
    long Size,
    long ReceivedBytes,
    double Speed,
    DateTime StartedAt);

public sealed record UploadManagerSnapshot(
    IReadOnlyList<ActiveTransferInfo> Active,
    int CompletedCount,
    long CompletedBytes)
{
    public long ActiveBytes => Active.Sum(a => a.ReceivedBytes);
    public long ActiveTotal => Active.Sum(a => a.Size);
    public double Speed => Active.Sum(a => a.Speed);
}

public sealed class UploadException : Exception
{
    public int StatusCode { get; }

    public UploadException(int statusCode, string message) : base(message)
    {
        StatusCode = statusCode;
    }
}
