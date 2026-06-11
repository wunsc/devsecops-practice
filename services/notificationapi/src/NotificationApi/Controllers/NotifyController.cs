// app-source/src/NotificationApi/Controllers/NotifyController.cs
// REST API controller for publishing notifications via Redis pub/sub
// SampleApi (or other callers) POST notification payloads here;
// the controller publishes them to a Redis channel for downstream consumers.

using Microsoft.AspNetCore.Mvc;
using NotificationApi.Models;
using StackExchange.Redis;

namespace NotificationApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class NotifyController : ControllerBase
{
    // Redis pub/sub channel name for all notifications
    private const string NotificationChannel = "notifications";

    private readonly ILogger<NotifyController> _logger;
    private readonly IConnectionMultiplexer _redis;

    public NotifyController(
        ILogger<NotifyController> logger,
        IConnectionMultiplexer redis)
    {
        _logger = logger;
        _redis = redis;
    }

    /// <summary>
    /// POST /api/Notify
    /// Accepts a notification payload and publishes it to the Redis "notifications" channel.
    /// Returns 202 Accepted with a tracking ID (fire-and-forget pattern).
    /// </summary>
    [HttpPost]
    public async Task<ActionResult<NotificationResponse>> Post([FromBody] NotificationRequest request)
    {
        // Validate required fields
        if (string.IsNullOrWhiteSpace(request.Type) || string.IsNullOrWhiteSpace(request.Message))
        {
            return BadRequest(new { error = "Type and Message are required fields." });
        }

        var notificationId = Guid.NewGuid();

        _logger.LogInformation(
            "Publishing notification {Id} of type {Type} to channel {Channel} for recipient {Recipient}",
            notificationId, request.Type, NotificationChannel, request.Recipient);

        try
        {
            // Publish to Redis pub/sub channel
            // Serialize as a simple JSON string for downstream consumers
            var payload = System.Text.Json.JsonSerializer.Serialize(new
            {
                id = notificationId,
                type = request.Type,
                message = request.Message,
                recipient = request.Recipient,
                timestamp = DateTime.UtcNow
            });

            var subscriber = _redis.GetSubscriber();
            var receiversCount = await subscriber.PublishAsync(
                RedisChannel.Literal(NotificationChannel), payload);

            _logger.LogInformation(
                "Notification {Id} published to {Receivers} subscriber(s)",
                notificationId, receiversCount);
        }
        catch (RedisConnectionException ex)
        {
            // Redis is down — log the error but still return 202
            // In a production system, you might queue to a local fallback or return 503
            _logger.LogWarning(ex,
                "Redis unavailable when publishing notification {Id}. " +
                "Notification accepted but delivery is degraded.", notificationId);
        }

        // Return 202 Accepted — the notification is queued, not delivered synchronously
        var response = new NotificationResponse
        {
            Id = notificationId,
            Status = "queued",
            Channel = NotificationChannel,
            Timestamp = DateTime.UtcNow
        };

        return Accepted(response);
    }

    /// <summary>
    /// GET /api/Notify/health
    /// Returns Redis connection status for operational visibility.
    /// Not a replacement for /healthz or /readyz — this is an API-level diagnostic.
    /// </summary>
    [HttpGet("health")]
    public IActionResult GetHealth()
    {
        var isConnected = _redis.IsConnected;

        _logger.LogDebug("Redis health check: connected={Connected}", isConnected);

        return Ok(new
        {
            redis = isConnected ? "connected" : "disconnected",
            endpoint = _redis.Configuration,
            timestamp = DateTime.UtcNow
        });
    }

    [HttpPost("subscribe")]
    public async Task<IActionResult> Subscribe([FromBody] SubscribeRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.City) || string.IsNullOrWhiteSpace(request.Email))
            return BadRequest(new { error = "City and Email are required." });

        var subscriptionId = Guid.NewGuid().ToString();
        _logger.LogInformation("Registering subscription {Id} for {City}/{Email}", subscriptionId, request.City, request.Email);

        try
        {
            var db = _redis.GetDatabase();
            var payload = System.Text.Json.JsonSerializer.Serialize(new
            {
                email = request.Email,
                thresholdTemp = request.ThresholdTemp,
                createdAt = DateTime.UtcNow
            });

            await db.HashSetAsync($"subscriptions:{request.City}", subscriptionId, payload);
            await db.HashIncrementAsync($"stats:{request.City}", "subscriptions", 1);

            _logger.LogInformation("Subscription {Id} stored in Redis for city {City}", subscriptionId, request.City);
        }
        catch (RedisConnectionException ex)
        {
            _logger.LogWarning(ex, "Redis unavailable for subscription {Id}", subscriptionId);
        }

        return Ok(new
        {
            subscriptionId,
            city = request.City,
            email = request.Email,
            status = "registered"
        });
    }

    [HttpPost("check")]
    public async Task<ActionResult<CheckAlertResponse>> CheckAlert([FromBody] CheckAlertRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.SubscriptionId) || string.IsNullOrWhiteSpace(request.City))
            return BadRequest(new { error = "SubscriptionId and City are required." });

        _logger.LogInformation("Checking alert for subscription {Id} in {City}, currentTemp={Temp}",
            request.SubscriptionId, request.City, request.CurrentTemp);

        int thresholdTemp = 30;
        try
        {
            var db = _redis.GetDatabase();
            var raw = await db.HashGetAsync($"subscriptions:{request.City}", request.SubscriptionId);
            if (raw.HasValue)
            {
                var sub = System.Text.Json.JsonDocument.Parse(raw.ToString());
                thresholdTemp = sub.RootElement.GetProperty("thresholdTemp").GetInt32();
            }
        }
        catch (RedisConnectionException ex)
        {
            _logger.LogWarning(ex, "Redis unavailable during alert check");
        }

        await Task.Delay(Random.Shared.Next(10, 40));

        bool triggered = request.CurrentTemp >= thresholdTemp;
        string? alertMessage = null;

        if (triggered)
        {
            alertMessage = $"Temperature {request.CurrentTemp}°C exceeds threshold {thresholdTemp}°C in {request.City}";
            _logger.LogWarning("Alert triggered: {Message}", alertMessage);

            try
            {
                var db = _redis.GetDatabase();
                var subscriber = _redis.GetSubscriber();

                await subscriber.PublishAsync(RedisChannel.Literal(NotificationChannel),
                    System.Text.Json.JsonSerializer.Serialize(new
                    {
                        type = "weather_alert",
                        message = alertMessage,
                        city = request.City,
                        timestamp = DateTime.UtcNow
                    }));

                await db.HashIncrementAsync($"stats:{request.City}", "alerts_sent", 1);
                await db.HashIncrementAsync($"stats:{request.City}", "alerts_today", 1);
            }
            catch (RedisConnectionException ex)
            {
                _logger.LogWarning(ex, "Redis unavailable during alert publish");
            }
        }

        return Ok(new CheckAlertResponse
        {
            SubscriptionId = request.SubscriptionId,
            City = request.City,
            ThresholdTemp = thresholdTemp,
            CurrentTemp = request.CurrentTemp,
            AlertTriggered = triggered,
            AlertMessage = alertMessage,
            EvaluatedAt = DateTime.UtcNow
        });
    }

    [HttpGet("stats/{city}")]
    public async Task<ActionResult<NotificationStats>> GetStats(string city)
    {
        _logger.LogInformation("Fetching notification stats for {City}", city);

        int alertsSent = 0, subscriptions = 0, alertsToday = 0;

        try
        {
            var db = _redis.GetDatabase();
            var sent = await db.HashGetAsync($"stats:{city}", "alerts_sent");
            var subs = await db.HashGetAsync($"stats:{city}", "subscriptions");
            var today = await db.HashGetAsync($"stats:{city}", "alerts_today");

            alertsSent = (int)(sent.IsNull ? 0 : (long)sent);
            subscriptions = (int)(subs.IsNull ? 0 : (long)subs);
            alertsToday = (int)(today.IsNull ? 0 : (long)today);
        }
        catch (RedisConnectionException ex)
        {
            _logger.LogWarning(ex, "Redis unavailable for stats lookup");
        }

        return Ok(new NotificationStats
        {
            City = city,
            TotalAlertsSent = alertsSent,
            TotalSubscriptions = subscriptions,
            AlertsTriggeredToday = alertsToday,
            RetrievedAt = DateTime.UtcNow
        });
    }
}
