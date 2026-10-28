using Shp.ServiceDefaults;

var builder = WebApplication.CreateBuilder(args);
builder.AddServiceDefaults("gateway");

// Bảng định tuyến nằm ở appsettings.json (ReverseProxy), theo docs/03-architecture/detailed-design/07-api-conventions.md §1.
// Địa chỉ cluster ghi đè được bằng biến môi trường, ví dụ ReverseProxy__Clusters__clinical__Destinations__d1__Address.
// /internal/* cố ý không có route nào.
builder.Services.AddReverseProxy().LoadFromConfig(builder.Configuration.GetSection("ReverseProxy"));

// TODO(SA, GW-xx): xác thực JWT (iss/aud/exp), rate limit, CORS cho web.

var app = builder.Build();
app.MapServiceDefaults();
app.MapReverseProxy();

await app.RunAsync().ConfigureAwait(false);

/// <summary>Điểm vào cho WebApplicationFactory trong test.</summary>
public partial class Program;
