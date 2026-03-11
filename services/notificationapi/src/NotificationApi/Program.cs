// app-source/src/NotificationApi/Program.cs
// ASP.NET Core 8.0 Web API entry point for the Notification microservice
// Rule 3: External config via environment variables from Kustomize ConfigMap overlays
// Configuration precedence: appsettings.json < appsettings.{ENV}.json < env vars < ConfigMaps
// This service listens on port 8081 (SampleApi uses 8080) to allow co-location in dev.
// Phase 20: Added Prometheus metrics via prometheus-net (UseHttpMetrics + MapMetrics).
// Phase 22: Added OpenTelemetry distributed tracing (ASP.NET Core, Redis).

using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using Prometheus;
using StackExchange.Redis;

var builder = WebApplication.CreateBuilder(args);

// --- Redis connection (resilient — handles connection failures gracefully) ---
// REDIS_URL and REDIS_PASSWORD env vars are set per environment via Kustomize ConfigMap/Secret overlays
var redisUrl = builder.Configuration.GetValue<string>("REDIS_URL", "localhost:6379");
var redisPassword = builder.Configuration.GetValue<string>("REDIS_PASSWORD", "");

var redisConfig = ConfigurationOptions.Parse(redisUrl);
if (!string.IsNullOrEmpty(redisPassword))
{
    redisConfig.Password = redisPassword;
}
// AbortOnConnectFail=false allows the app to start even if Redis is down.
// StackExchange.Redis will keep retrying in the background.
// This is critical for OpenShift — pods should not crashloop just because Redis
// restarts; the readiness probe will mark the pod as not-ready instead.
redisConfig.AbortOnConnectFail = false;
// ConnectTimeout controls how long the initial connection attempt waits (ms)
redisConfig.ConnectTimeout = 5000;
// SyncTimeout controls how long synchronous operations wait (ms)
redisConfig.SyncTimeout = 3000;

// Register IConnectionMultiplexer as singleton — StackExchange.Redis is designed
// to be shared across the entire application lifetime (thread-safe, multiplexed)
builder.Services.AddSingleton<IConnectionMultiplexer>(sp =>
{
    var logger = sp.GetRequiredService<ILogger<Program>>();
    logger.LogInformation("Connecting to Redis at {RedisUrl}", redisUrl);
    return ConnectionMultiplexer.Connect(redisConfig);
});

// --- OpenTelemetry distributed tracing (Phase 22) ---
// Traces are exported via OTLP gRPC to the OTel Collector, which forwards to Tempo.
// OTEL_EXPORTER_OTLP_ENDPOINT and OTEL_SERVICE_NAME are set per-env via ConfigMap.
var otelEndpoint = builder.Configuration.GetValue<string>("OTEL_EXPORTER_OTLP_ENDPOINT") ?? "";
if (!string.IsNullOrEmpty(otelEndpoint))
{
    builder.Services.AddOpenTelemetry()
        .ConfigureResource(resource => resource
            .AddService(
                serviceName: builder.Configuration.GetValue<string>("OTEL_SERVICE_NAME") ?? "notificationapi",
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
                    opts.Filter = ctx =>
                        !ctx.Request.Path.StartsWithSegments("/healthz") &&
                        !ctx.Request.Path.StartsWithSegments("/readyz") &&
                        !ctx.Request.Path.StartsWithSegments("/metrics");
                })
                .AddRedisInstrumentation()                     // Redis command spans
                .AddOtlpExporter(opts =>
                {
                    opts.Endpoint = new Uri(otelEndpoint);
                });
        });
}

// --- Health checks ---
// Built-in health check middleware for /healthz and /readyz
// The HealthController provides custom readiness logic (Redis ping)
builder.Services.AddHealthChecks();

// --- Controllers ---
builder.Services.AddControllers();

// --- Swagger/OpenAPI (controlled by feature flag) ---
// FEATURE_SWAGGER_ENABLED env var controls whether Swagger UI is available
// DEV: true, SIT: true, UAT: false, PROD: false
var swaggerEnabled = builder.Configuration.GetValue<bool>("FEATURE_SWAGGER_ENABLED",
    builder.Environment.IsDevelopment());

if (swaggerEnabled)
{
    builder.Services.AddEndpointsApiExplorer();
    builder.Services.AddSwaggerGen(c =>
    {
        c.SwaggerDoc("v1", new() { Title = "NotificationAPI", Version = "v1" });
    });
}

// --- CORS (configured per environment via env vars) ---
// CORS_ALLOWED_ORIGINS env var is set per environment in the Kustomize ConfigMap overlay
var corsOrigins = builder.Configuration.GetValue<string>("CORS_ALLOWED_ORIGINS", "*");
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

// --- Middleware pipeline ---

// Swagger UI (feature-flagged)
if (swaggerEnabled)
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors();

// --- Prometheus metrics middleware (Phase 20) ---
app.UseHttpMetrics();
app.UseAuthorization();

// --- Health check endpoints ---
// /healthz — liveness probe (is the process alive?)
app.MapHealthChecks("/healthz");

// /readyz — readiness probe (is the app ready to serve traffic?)
// Note: the HealthController.Readyz() method provides a more detailed check
// that includes Redis connectivity; the built-in one here is a fallback
app.MapHealthChecks("/readyz");

// --- Prometheus metrics endpoint (Phase 20) ---
app.MapMetrics();                              // GET /metrics → Prometheus exposition format

// --- API controllers ---
app.MapControllers();

app.Run();
