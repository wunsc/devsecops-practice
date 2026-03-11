// app-source/src/NotificationApi/Models/NotificationResponse.cs
// Outbound response returned after a notification is queued
// Contains tracking information for the caller

namespace NotificationApi.Models;

/// <summary>
/// Response returned after a notification is successfully queued.
/// The Id can be used for tracking/correlation in logs.
/// </summary>
public class NotificationResponse
{
    /// <summary>Unique identifier for this notification (for tracking/correlation)</summary>
    public Guid Id { get; set; }

    /// <summary>Current status of the notification, e.g. "queued"</summary>
    public string Status { get; set; } = string.Empty;

    /// <summary>Redis pub/sub channel the notification was published to</summary>
    public string Channel { get; set; } = string.Empty;

    /// <summary>Timestamp when the notification was accepted</summary>
    public DateTime Timestamp { get; set; }
}
