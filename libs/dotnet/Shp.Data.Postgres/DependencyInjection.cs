using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Shp.Data.Postgres;

public static class DependencyInjection
{
    /// <summary>Chạy <see cref="OutboxRelay"/> nền. Service phải đăng ký <c>NpgsqlDataSource</c> và <see cref="IEventPublisher"/>.</summary>
    public static IServiceCollection AddShpOutboxRelay(this IServiceCollection services, Action<OutboxRelayOptions>? configure = null)
    {
        ArgumentNullException.ThrowIfNull(services);
        services.TryAddSingleton(TimeProvider.System);
        var options = services.AddOptions<OutboxRelayOptions>();
        if (configure is not null)
        {
            options.Configure(configure);
        }

        services.AddSingleton<OutboxRelay>();
        services.AddHostedService(sp => sp.GetRequiredService<OutboxRelay>());
        return services;
    }
}
