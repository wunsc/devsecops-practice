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
}
