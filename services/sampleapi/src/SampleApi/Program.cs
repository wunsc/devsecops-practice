// app-source/src/SampleApi/Program.cs
// ASP.NET Core 8.0 Web API entry point
// Rule 3: External config via IOptions<T> pattern
// Configuration precedence: appsettings.json < appsettings.{ENV}.json < env vars < ConfigMaps
// In OpenShift, Kustomize ConfigMap overlays inject environment variables that override appsettings.
//
// Phase 17: Added PostgreSQL (EF Core), Redis (distributed cache), and NotificationApi (HttpClient).
// All three dependencies are optional — the app starts and serves requests without them.
// Phase 20: Added Prometheus metrics via prometheus-net (UseHttpMetrics + MapMetrics).
// Phase 22: Added OpenTelemetry distributed tracing (ASP.NET Core, HttpClient, EF Core, Redis).

using Microsoft.EntityFrameworkCore;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using Prometheus;
using SampleApi.Data;
using SampleApi.Models;
using SampleApi.Services;

var builder = WebApplication.CreateBuilder(args);

// --- Configuration binding (Rule 3 — IOptions pattern) ---
builder.Services.Configure<WeatherForecastOptions>(
    builder.Configuration.GetSection("WeatherForecast"));

// --- External service configuration (Phase 17) ---
ConfigurePostgreSql(builder);
ConfigureRedis(builder);
ConfigureNotificationApi(builder);
ConfigureHealthChecks(builder);

// --- OpenTelemetry distributed tracing (Phase 22) ---
// Traces are exported via OTLP gRPC to the OTel Collector, which forwards to Tempo.
// OTEL_EXPORTER_OTLP_ENDPOINT and OTEL_SERVICE_NAME are set per-env via ConfigMap.
var otelEndpoint = builder.Configuration.GetValue<string>("OTEL_EXPORTER_OTLP_ENDPOINT") ?? "";
if (!string.IsNullOrEmpty(otelEndpoint))
{
    builder.Services.AddOpenTelemetry()
        .ConfigureResource(resource => resource
            .AddService(
                serviceName: builder.Configuration.GetValue<string>("OTEL_SERVICE_NAME") ?? "sampleapi",
                serviceVersion: typeof(Program).Assembly.GetName().Version?.ToString() ?? "1.0.0")
            .AddAttributes(new Dictionary<string, object>
            {
                ["deployment.environment"] = builder.Configuration.GetValue<string>("ASPNETCORE_ENVIRONMENT") ?? "Unknown"
            }))
        .WithTracing(tracing =>
        {
            tracing
                .AddAspNetCoreInstrumentation(opts =>
                {
                    // Filter out health check and metrics endpoints to reduce noise
                    opts.Filter = ctx =>
                        !ctx.Request.Path.StartsWithSegments("/healthz") &&
                        !ctx.Request.Path.StartsWithSegments("/readyz") &&
                        !ctx.Request.Path.StartsWithSegments("/metrics");
                })
                .AddHttpClientInstrumentation()                // Outgoing HTTP (→ NotificationApi)
                .AddEntityFrameworkCoreInstrumentation()        // PostgreSQL query spans
                .AddRedisInstrumentation()                     // Redis command spans
                .AddOtlpExporter(opts =>
                {
                    opts.Endpoint = new Uri(otelEndpoint);
                });
        });
}

// --- Controllers ---
builder.Services.AddControllers();

// --- Swagger/OpenAPI (controlled by feature flag) ---
var swaggerEnabled = builder.Configuration.GetValue<bool>("FEATURE_SWAGGER_ENABLED",
    builder.Environment.IsDevelopment());

if (swaggerEnabled)
{
    builder.Services.AddEndpointsApiExplorer();
    builder.Services.AddSwaggerGen(c =>
    {
        c.SwaggerDoc("v1", new() { Title = "SampleAPI", Version = "v1" });
    });
}

// --- CORS (configured per environment via env vars) ---
var corsOrigins = builder.Configuration.GetValue<string>("CORS_ALLOWED_ORIGINS") ?? "*";
builder.Services.AddCors(options =>
{
    options.AddDefaultPolicy(policy =>
    {
        if (corsOrigins == "*")
        {
            policy.AllowAnyOrigin().AllowAnyMethod().AllowAnyHeader();
        }
        else
        {
            policy.WithOrigins(corsOrigins.Split(','))
                  .AllowAnyMethod()
                  .AllowAnyHeader();
        }
    });
});

var app = builder.Build();

if (swaggerEnabled)
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors();

// --- Prometheus metrics middleware (Phase 20) ---
// UseHttpMetrics() adds HTTP request duration/count metrics automatically.
// MapMetrics() exposes the /metrics endpoint for Prometheus scraping.
app.UseHttpMetrics();
app.UseAuthorization();
app.MapHealthChecks("/healthz");
app.MapHealthChecks("/readyz");
app.MapMetrics();                              // GET /metrics → Prometheus exposition format
app.MapControllers();

await app.RunAsync();

// --- Service configuration methods (extracted to reduce cognitive complexity) ---

static string BuildConnectionString(string baseUrl, string password)
{
    return string.IsNullOrEmpty(password) ? baseUrl : $"{baseUrl};Password={password}";
}

static string BuildRedisConfig(string url, string password)
{
    return string.IsNullOrEmpty(password) ? url : $"{url},password={password}";
}

static void ConfigurePostgreSql(WebApplicationBuilder builder)
{
    var databaseUrl = builder.Configuration.GetValue<string>("DATABASE_URL") ?? "";
    var databasePassword = builder.Configuration.GetValue<string>("DATABASE_PASSWORD") ?? "";

    if (!string.IsNullOrEmpty(databaseUrl))
    {
        var connectionString = BuildConnectionString(databaseUrl, databasePassword);
        builder.Services.AddDbContext<AppDbContext>(options =>
            options.UseNpgsql(connectionString));
    }
    else
    {
        builder.Services.AddDbContext<AppDbContext>(options =>
            options.UseInMemoryDatabase("SampleApi_Fallback"));
    }
}

static void ConfigureRedis(WebApplicationBuilder builder)
{
    var redisUrl = builder.Configuration.GetValue<string>("REDIS_URL") ?? "";
    var redisPassword = builder.Configuration.GetValue<string>("REDIS_PASSWORD") ?? "";

    if (!string.IsNullOrEmpty(redisUrl))
    {
        var redisConfig = BuildRedisConfig(redisUrl, redisPassword);
        builder.Services.AddStackExchangeRedisCache(options =>
        {
            options.Configuration = redisConfig;
            options.InstanceName = "sampleapi:";
        });
    }
    else
    {
        builder.Services.AddDistributedMemoryCache();
    }

    builder.Services.AddScoped<CacheService>();
}

static void ConfigureNotificationApi(WebApplicationBuilder builder)
{
    var notificationApiUrl = builder.Configuration.GetValue<string>(
        "NOTIFICATION_API_URL") ?? "http://localhost:8081";

    builder.Services.AddHttpClient<NotificationClient>(client =>
    {
        client.BaseAddress = new Uri(notificationApiUrl);
        client.Timeout = TimeSpan.FromSeconds(5);
    });
}

static void ConfigureHealthChecks(WebApplicationBuilder builder)
{
    var databaseUrl = builder.Configuration.GetValue<string>("DATABASE_URL") ?? "";
    var databasePassword = builder.Configuration.GetValue<string>("DATABASE_PASSWORD") ?? "";
    var redisUrl = builder.Configuration.GetValue<string>("REDIS_URL") ?? "";
    var redisPassword = builder.Configuration.GetValue<string>("REDIS_PASSWORD") ?? "";

    var healthChecksBuilder = builder.Services.AddHealthChecks();

    if (!string.IsNullOrEmpty(databaseUrl))
    {
        healthChecksBuilder.AddNpgSql(
            BuildConnectionString(databaseUrl, databasePassword),
            name: "postgresql",
            tags: ["db", "ready"]);
    }

    if (!string.IsNullOrEmpty(redisUrl))
    {
        healthChecksBuilder.AddRedis(
            BuildRedisConfig(redisUrl, redisPassword),
            name: "redis",
            tags: ["cache", "ready"]);
    }
}
