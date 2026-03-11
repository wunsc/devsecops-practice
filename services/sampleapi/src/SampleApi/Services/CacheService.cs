// src/SampleApi/Services/CacheService.cs
// Wrapper around IDistributedCache (Redis) with graceful failure handling.
// If Redis is unavailable, methods return null/no-op instead of throwing.
// This ensures the API remains functional even when Redis is down.

using System.Text.Json;
using Microsoft.Extensions.Caching.Distributed;

namespace SampleApi.Services;

/// <summary>
/// Redis cache wrapper that handles connection failures gracefully.
/// Uses System.Text.Json for serialization to avoid Newtonsoft dependency.
/// Default TTL is 5 minutes — configurable per call.
/// </summary>
public class CacheService
{
    private readonly IDistributedCache _cache;
    private readonly ILogger<CacheService> _logger;

    /// <summary>Default cache entry lifetime: 5 minutes</summary>
    private static readonly TimeSpan DefaultTtl = TimeSpan.FromMinutes(5);

    public CacheService(IDistributedCache cache, ILogger<CacheService> logger)
    {
        _cache = cache;
        _logger = logger;
    }

    /// <summary>
    /// Retrieve a cached value by key, deserialized to type T.
    /// Returns null if the key does not exist or Redis is unreachable.
    /// </summary>
    public virtual async Task<T?> GetAsync<T>(string key) where T : class
    {
        try
        {
            var cached = await _cache.GetStringAsync(key);
            if (cached is null)
            {
                _logger.LogDebug("Cache MISS for key: {Key}", key);
                return null;
            }

            _logger.LogDebug("Cache HIT for key: {Key}", key);
            return JsonSerializer.Deserialize<T>(cached);
        }
        catch (Exception ex)
        {
            // Redis down — log warning but don't fail the request.
            // The caller will fall back to generating fresh data.
            _logger.LogWarning(ex, "Redis GET failed for key: {Key}. Treating as cache miss.", key);
            return null;
        }
    }

    /// <summary>
    /// Store a value in cache with the specified TTL (default: 5 minutes).
    /// Silently fails if Redis is unreachable — the app continues without caching.
    /// </summary>
    public virtual async Task SetAsync<T>(string key, T value, TimeSpan? expiration = null)
    {
        try
        {
            var json = JsonSerializer.Serialize(value);
            var options = new DistributedCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = expiration ?? DefaultTtl
            };

            await _cache.SetStringAsync(key, json, options);
            _logger.LogDebug("Cache SET for key: {Key}, TTL: {Ttl}", key, expiration ?? DefaultTtl);
        }
        catch (Exception ex)
        {
            // Redis down — log warning but don't fail the request.
            _logger.LogWarning(ex, "Redis SET failed for key: {Key}. Data not cached.", key);
        }
    }

    /// <summary>
    /// Remove a cached value by key.
    /// Silently fails if Redis is unreachable.
    /// </summary>
    public virtual async Task RemoveAsync(string key)
    {
        try
        {
            await _cache.RemoveAsync(key);
            _logger.LogDebug("Cache REMOVE for key: {Key}", key);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Redis REMOVE failed for key: {Key}.", key);
        }
    }
}
