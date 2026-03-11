// src/SampleApi/Controllers/HealthController.cs
// Health and readiness endpoints for Kubernetes probes.
// Phase 17: Enhanced /readyz to check PostgreSQL and Redis connectivity.
// The app returns "degraded" (not "unhealthy") when dependencies are down,
// because it can still serve requests without them.

using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Distributed;
using SampleApi.Data;

namespace SampleApi.Controllers;

/// <summary>
/// Health check endpoints for Kubernetes liveness and readiness probes.
/// /healthz — liveness: is the process alive?
/// /readyz  — readiness: is the app ready to accept traffic?
/// </summary>
[ApiController]
[Route("/")]
public class HealthController : ControllerBase
{
    private const string StatusHealthy = "healthy";
    private const string StatusUnhealthy = "unhealthy";
    private const string StatusDegraded = "degraded";

    private readonly ILogger<HealthController> _logger;
    private readonly AppDbContext _dbContext;
    private readonly IDistributedCache _cache;

    public HealthController(
        ILogger<HealthController> logger,
        AppDbContext dbContext,
        IDistributedCache cache)
    {
        _logger = logger;
        _dbContext = dbContext;
        _cache = cache;
    }

    /// <summary>
    /// Liveness probe — returns 200 if the process is running.
    /// Kubernetes restarts the pod if this fails.
    /// This should ONLY check if the process is alive, not dependencies.
    /// </summary>
    [HttpGet("healthz")]
    [ProducesResponseType(typeof(object), 200)]
    public IActionResult Healthz()
    {
        return Ok(new { status = StatusHealthy, timestamp = DateTime.UtcNow });
    }

    /// <summary>
    /// Readiness probe — returns 200 if the app is ready for traffic.
    /// Checks PostgreSQL and Redis connectivity.
    /// Returns 200 even if dependencies are down (degraded mode) because
    /// the API still functions without them — forecasts are generated in-memory.
    /// Kubernetes only removes the pod from Service endpoints if this returns non-200.
    /// </summary>
    [HttpGet("readyz")]
    [ProducesResponseType(typeof(object), 200)]
    public async Task<IActionResult> Readyz()
    {
        var checks = new Dictionary<string, object>();
        var overallStatus = "ready";

        // Check PostgreSQL connectivity
        try
        {
            var canConnect = await _dbContext.Database.CanConnectAsync();
            checks["postgresql"] = new { status = canConnect ? StatusHealthy : StatusUnhealthy };
            if (!canConnect)
            {
                overallStatus = StatusDegraded;
                _logger.LogWarning("Readiness check: PostgreSQL is unreachable");
            }
        }
        catch (Exception ex)
        {
            checks["postgresql"] = new { status = StatusUnhealthy, error = ex.Message };
            overallStatus = StatusDegraded;
            _logger.LogWarning(ex, "Readiness check: PostgreSQL connectivity check failed");
        }

        // Check Redis connectivity
        try
        {
            var testKey = "__health_check__";
            await _cache.SetStringAsync(testKey, "ok",
                new DistributedCacheEntryOptions
                {
                    AbsoluteExpirationRelativeToNow = TimeSpan.FromSeconds(10)
                });
            var result = await _cache.GetStringAsync(testKey);
            var redisHealthy = result == "ok";
            checks["redis"] = new { status = redisHealthy ? StatusHealthy : StatusUnhealthy };
            if (!redisHealthy)
            {
                overallStatus = StatusDegraded;
                _logger.LogWarning("Readiness check: Redis ping failed");
            }
        }
        catch (Exception ex)
        {
            checks["redis"] = new { status = StatusUnhealthy, error = ex.Message };
            overallStatus = StatusDegraded;
            _logger.LogWarning(ex, "Readiness check: Redis connectivity check failed");
        }

        return Ok(new
        {
            status = overallStatus,
            timestamp = DateTime.UtcNow,
            checks
        });
    }
}
