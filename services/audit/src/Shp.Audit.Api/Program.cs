using Shp.ServiceDefaults;

var builder = WebApplication.CreateBuilder(args);
builder.AddServiceDefaults("audit");

// TODO(dev): đăng ký service nghiệp vụ, DbContext/Mongo client, MassTransit consumer ở đây.

var app = builder.Build();
app.MapServiceDefaults();

// TODO(dev): map endpoint theo docs/03-architecture/detailed-design (mỗi nhóm endpoint một file Endpoints/*.cs).

await app.RunAsync().ConfigureAwait(false);

/// <summary>Điểm vào cho WebApplicationFactory trong test.</summary>
public partial class Program;
