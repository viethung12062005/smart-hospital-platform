using System.Collections.Concurrent;

namespace Shp.Events.Audit;

/// <summary>Nơi gửi bản ghi audit. Production: ghi vào outbox cùng transaction (SHR-07) để không mất audit khi bus lỗi; test: <see cref="InMemoryAuditSink"/>.</summary>
public interface IAuditSink
{
    Task EmitAsync(AuditRecord record, CancellationToken cancellationToken);
}

/// <summary>Sink trong bộ nhớ cho test và chạy local nhanh.</summary>
public sealed class InMemoryAuditSink : IAuditSink
{
    private readonly ConcurrentQueue<AuditRecord> _records = new();

    public IReadOnlyCollection<AuditRecord> Records => [.. _records];

    public Task EmitAsync(AuditRecord record, CancellationToken cancellationToken)
    {
        _records.Enqueue(record);
        return Task.CompletedTask;
    }
}
