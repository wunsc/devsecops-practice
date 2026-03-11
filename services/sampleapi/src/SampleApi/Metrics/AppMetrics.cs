// services/sampleapi/src/SampleApi/Metrics/AppMetrics.cs
// Custom Prometheus business metrics for SampleApi.
//
// These metrics provide insight beyond standard HTTP request metrics:
//   - How many forecasts are being served (by city)?
//   - How fast is the NotificationApi responding?
//   - How effective is the Redis cache?
//   - How many active database connections exist?
//
// Usage in controllers/services:
//   AppMetrics.ForecastsServed.WithLabels("London", "5day").Inc();
//   using (AppMetrics.NotificationLatency.NewTimer())
//   {
//       await notificationClient.NotifyAsync(...);
//   }

using Prometheus;

namespace SampleApi.Metrics;

public static class AppMetrics
{
    // --- Counters (monotonically increasing) ---

    /// <summary>
    /// Total weather forecasts served, labeled by city and period.
    /// PromQL: rate(sampleapi_forecasts_total[5m])
    /// </summary>
    public static readonly Counter ForecastsServed = Prometheus.Metrics.CreateCounter(
        "sampleapi_forecasts_total",
        "Total weather forecasts served",
        new CounterConfiguration
        {
            LabelNames = new[] { "city", "period" }
        });

    /// <summary>
    /// Cache operations (hit vs miss).
    /// PromQL: rate(sampleapi_cache_operations_total{result="hit"}[5m])
    ///         / rate(sampleapi_cache_operations_total[5m])
    /// </summary>
    public static readonly Counter CacheOperations = Prometheus.Metrics.CreateCounter(
        "sampleapi_cache_operations_total",
        "Cache operations by result",
        new CounterConfiguration
        {
            LabelNames = new[] { "result" }  // "hit" or "miss"
        });

    /// <summary>
    /// Notification API calls by result.
    /// PromQL: rate(sampleapi_notifications_total{result="success"}[5m])
    /// </summary>
    public static readonly Counter NotificationsSent = Prometheus.Metrics.CreateCounter(
        "sampleapi_notifications_total",
        "Notification API calls",
        new CounterConfiguration
        {
            LabelNames = new[] { "result" }  // "success" or "failure"
        });

    // --- Histograms (distribution of values) ---

    /// <summary>
    /// NotificationApi call latency distribution.
    /// PromQL: histogram_quantile(0.95, rate(sampleapi_notification_duration_seconds_bucket[5m]))
    /// </summary>
    public static readonly Histogram NotificationLatency = Prometheus.Metrics.CreateHistogram(
        "sampleapi_notification_duration_seconds",
        "Duration of calls to NotificationApi",
        new HistogramConfiguration
        {
            // Buckets from 10ms to 10s (exponential: 0.01, 0.02, 0.04, ... 10.24)
            Buckets = Histogram.ExponentialBuckets(0.01, 2, 10)
        });

    /// <summary>
    /// Database query latency distribution.
    /// PromQL: histogram_quantile(0.99, rate(sampleapi_db_query_duration_seconds_bucket[5m]))
    /// </summary>
    public static readonly Histogram DbQueryLatency = Prometheus.Metrics.CreateHistogram(
        "sampleapi_db_query_duration_seconds",
        "Duration of PostgreSQL queries",
        new HistogramConfiguration
        {
            Buckets = Histogram.ExponentialBuckets(0.001, 2, 12)  // 1ms to 4s
        });

    // --- Gauges (current value, can go up or down) ---

    /// <summary>
    /// Active database connections (current count).
    /// PromQL: sampleapi_db_connections_active
    /// </summary>
    public static readonly Gauge DbConnections = Prometheus.Metrics.CreateGauge(
        "sampleapi_db_connections_active",
        "Number of active PostgreSQL connections");
}
