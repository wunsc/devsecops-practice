// src/NotificationApi/Controllers/HealthController.cs
// Health and readiness endpoints for Kubernetes probes
// Readiness checks Redis connectivity — if Redis is down,
// the pod is removed from Service endpoints (no traffic routed to it).

using Microsoft.AspNetCore.Mvc;
using StackExchange.Redis;

namespace NotificationApi.Controllers;

/// <summary>
/// Health check endpoints for Kubernetes liveness and readiness probes.
/// /healthz — liveness: is the process alive?
/// /readyz  — readiness: is the app ready to accept traffic?
/// </summary>
[ApiController]
public class HealthController : ControllerBase
{
    private readonly ILogger<HealthController> _logger;
    private readonly IConnectionMultiplexer _redis;

    public HealthController(
        ILogger<HealthController> logger,
        IConnectionMultiplexer redis)
    {
        _logger = logger;
        _redis = redis;
    }

    /// <summary>
    /// Liveness probe — returns 200 if the process is running.
    /// Kubernetes restarts the pod if this fails.
    /// </summary>
    [HttpGet("/healthz")]
    public IActionResult Healthz()
    {
        return Ok(new { status = "healthy", timestamp = DateTime.UtcNow });
    }

    /// <summary>
    /// Readiness probe — returns 200 if the app is ready for traffic.
    /// Checks Redis connectivity because this service depends on Redis
    /// for its core pub/sub functionality.
    /// Kubernetes removes the pod from the Service endpoints if this fails.
    /// </summary>
    [HttpGet("/readyz")]
    public IActionResult Readyz()
    {
        try
        {
            if (_redis.IsConnected)
            {
                // Ping Redis to verify the connection is truly alive
                var db = _redis.GetDatabase();
                var pong = db.Ping();
                _logger.LogDebug("Redis readiness ping: {Latency}ms", pong.TotalMilliseconds);

                return Ok(new
                {
                    status = "ready",
                    redis = "connected",
                    latencyMs = pong.TotalMilliseconds,
                    timestamp = DateTime.UtcNow
                });
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Redis readiness check failed");
        }

        // Redis is not connected — report not ready
        // Kubernetes will stop routing traffic until Redis is back
        return StatusCode(503, new
        {
            status = "not_ready",
            redis = "disconnected",
            timestamp = DateTime.UtcNow
        });
    }
}
