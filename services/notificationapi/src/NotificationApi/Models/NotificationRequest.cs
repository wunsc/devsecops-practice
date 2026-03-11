// app-source/src/NotificationApi/Models/NotificationRequest.cs
// Inbound notification payload sent by callers (e.g., SampleApi)
// Validated by the controller before publishing to Redis

namespace NotificationApi.Models;

/// <summary>
/// Represents an incoming notification request.
/// Callers POST this payload to /api/Notify.
/// </summary>
public class NotificationRequest
{
    /// <summary>Notification type, e.g. "weather_alert", "system", "email"</summary>
    public string Type { get; set; } = string.Empty;

    /// <summary>Notification message body</summary>
    public string Message { get; set; } = string.Empty;

    /// <summary>Recipient identifier (email, user ID, channel name, etc.)</summary>
    public string Recipient { get; set; } = string.Empty;
}
