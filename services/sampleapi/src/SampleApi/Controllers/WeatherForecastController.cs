// app-source/src/SampleApi/Controllers/WeatherForecastController.cs
// Sample REST API controller that demonstrates IOptions<T> usage (Rule 3)
// Configuration values come from appsettings.json or environment variables
// set by Kustomize ConfigMap overlays per environment
//
// Phase 17: Added PostgreSQL persistence, Redis caching, and NotificationApi integration.
// All three dependencies are optional — the controller gracefully handles their absence.
// Failure priority: Always return forecasts > try to cache > try to persist > try to notify.

using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;
using SampleApi.Data;
using SampleApi.Models;
using SampleApi.Services;

namespace SampleApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class WeatherForecastController : ControllerBase
{
    private static readonly string[] Summaries =
    [
        "Freezing", "Bracing", "Chilly", "Cool", "Mild",
        "Warm", "Balmy", "Hot", "Sweltering", "Scorching"
    ];

    private readonly ILogger<WeatherForecastController> _logger;
    private readonly WeatherForecastOptions _options;
    private readonly AppDbContext _dbContext;
    private readonly CacheService _cacheService;
    private readonly NotificationClient _notificationClient;

    public WeatherForecastController(
        ILogger<WeatherForecastController> logger,
        IOptions<WeatherForecastOptions> options,
        AppDbContext dbContext,
        CacheService cacheService,
        NotificationClient notificationClient)
    {
        _logger = logger;
        _options = options.Value;
        _dbContext = dbContext;
        _cacheService = cacheService;
        _notificationClient = notificationClient;
    }

    /// <summary>
    /// GET /api/WeatherForecast
    /// Returns weather forecast data using externalized configuration.
    /// Flow: Check Redis cache -> Generate if miss -> Save to PostgreSQL -> Cache in Redis -> Notify
    /// All dependency failures are handled gracefully — forecasts are always returned.
    /// </summary>
    [HttpGet]
    public async Task<IEnumerable<WeatherForecast>> Get()
    {
        var location = _options.Location;
        var cacheKey = $"forecasts:{location}";

        _logger.LogInformation(
            "Generating {Days}-day forecast for {Location} in {Unit}",
            _options.ForecastDays, location, _options.TemperatureUnit);

        // 1. Try cache first (Redis) — returns null if cache miss or Redis is down
        var cached = await _cacheService.GetAsync<WeatherForecast[]>(cacheKey);
        if (cached is not null)
        {
            _logger.LogInformation("Returning cached forecast for {Location}", location);
            return cached;
        }

        // 2. Cache miss — generate fresh forecasts (same logic as before)
        var forecasts = Enumerable.Range(1, _options.ForecastDays).Select(index =>
        {
            var tempC = Random.Shared.Next(_options.MinTemperature, _options.MaxTemperature);
            return new WeatherForecast
            {
                Date = DateOnly.FromDateTime(DateTime.Now.AddDays(index)),
                TemperatureC = tempC,
                Summary = Summaries[Random.Shared.Next(Summaries.Length)],
                Location = location,
                TemperatureUnit = _options.TemperatureUnit
            };
        }).ToArray();

        // 3. Persist to PostgreSQL (fire-and-forget pattern — don't block the response)
        _ = Task.Run(async () =>
        {
            try
            {
                var records = forecasts.Select(f => new WeatherRecord
                {
                    City = f.Location,
                    Date = f.Date,
                    TemperatureC = f.TemperatureC,
                    Summary = f.Summary,
                    CreatedAt = DateTime.UtcNow
                });

                _dbContext.WeatherRecords.AddRange(records);
                await _dbContext.SaveChangesAsync();
                _logger.LogDebug("Saved {Count} forecast records to PostgreSQL for {Location}",
                    forecasts.Length, location);
            }
            catch (Exception ex)
            {
                // PostgreSQL down — log warning, don't fail the request.
                // Data is ephemeral anyway; the next request will generate new forecasts.
                _logger.LogWarning(ex,
                    "Failed to save forecast records to PostgreSQL for {Location}. " +
                    "Data not persisted but response is still returned.", location);
            }
        });

        // 4. Store in Redis cache with 5-minute TTL
        // CacheService handles failures internally — this call never throws.
        await _cacheService.SetAsync(cacheKey, forecasts);

        // 5. Fire-and-forget: notify about new forecast generation via NotificationApi
        // This runs asynchronously and never blocks the response.
        _ = Task.Run(async () =>
        {
            try
            {
                await _notificationClient.SendNotificationAsync(
                    type: "forecast_generated",
                    message: $"New {_options.ForecastDays}-day forecast generated for {location}",
                    recipient: "weather-alerts");
            }
            catch (Exception ex)
            {
                // NotificationClient already handles its own errors, but catch any unexpected ones
                _logger.LogWarning(ex,
                    "Unexpected error sending forecast notification for {Location}", location);
            }
        });

        return forecasts;
    }

    /// <summary>
    /// GET /api/WeatherForecast/config
    /// Returns the current configuration (useful for debugging per-env config)
    /// </summary>
    [HttpGet("config")]
    public ActionResult<WeatherForecastOptions> GetConfig()
    {
        return Ok(_options);
    }

    /// <summary>
    /// GET /api/WeatherForecast/version
    /// Returns build info for verifying which version is deployed per environment
    /// </summary>
    [HttpGet("version")]
    [ProducesResponseType(typeof(object), 200)]
    public ActionResult GetVersion()
    {
        var assembly = System.Reflection.Assembly.GetExecutingAssembly();
        return Ok(new
        {
            version = assembly.GetName().Version?.ToString() ?? "1.0.0",
            environment = _options.Location,
            forecastDays = _options.ForecastDays,
            timestamp = DateTime.UtcNow
        });
    }
}

/// <summary>
/// Weather forecast data model
/// </summary>
public class WeatherForecast
{
    public DateOnly Date { get; set; }
    public int TemperatureC { get; set; }
    public int TemperatureF => 32 + (int)(TemperatureC / 0.5556);
    public string? Summary { get; set; }
    public string Location { get; set; } = "Default";
    public string TemperatureUnit { get; set; } = "Celsius";
}
