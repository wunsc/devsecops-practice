// src/SampleApi/Services/NotificationClient.cs
// HTTP client wrapper for calling the NotificationApi microservice.
// All calls are resilient — failures are logged as warnings but never propagate
// to the caller. This ensures the main API works even if NotificationApi is down.

using System.Text;
using System.Text.Json;

namespace SampleApi.Services;

/// <summary>
/// Typed HttpClient for the NotificationApi microservice.
/// Sends notifications (email, slack, webhook) via POST /api/Notify.
/// URL is configured via NOTIFICATION_API_URL environment variable.
/// </summary>
public class NotificationClient
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly HttpClient _httpClient;
    private readonly ILogger<NotificationClient> _logger;

    public NotificationClient(HttpClient httpClient, ILogger<NotificationClient> logger)
    {
        _httpClient = httpClient;
        _logger = logger;
    }

    /// <summary>
    /// Send a notification to the NotificationApi.
    /// Returns the response from NotificationApi, or null if the call fails.
    /// This method never throws — all exceptions are caught and logged.
    /// </summary>
    /// <param name="type">Notification type: "email", "slack", "webhook"</param>
    /// <param name="message">The notification message body</param>
    /// <param name="recipient">Target recipient (email address, channel, URL)</param>
    public virtual async Task<NotificationResponse?> SendNotificationAsync(
        string type, string message, string recipient)
    {
        try
        {
            var payload = new
            {
                Type = type,
                Message = message,
                Recipient = recipient,
                Timestamp = DateTime.UtcNow
            };

            var json = JsonSerializer.Serialize(payload);
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            _logger.LogDebug(
                "Sending notification: type={Type}, recipient={Recipient}",
                type, recipient);

            var response = await _httpClient.PostAsync("/api/Notify", content);

            if (response.IsSuccessStatusCode)
            {
                var responseBody = await response.Content.ReadAsStringAsync();
                var result = JsonSerializer.Deserialize<NotificationResponse>(
                    responseBody, JsonOptions);

                _logger.LogInformation(
                    "Notification sent successfully: type={Type}, recipient={Recipient}",
                    type, recipient);

                return result;
            }

            // Non-success HTTP status — log but don't throw
            _logger.LogWarning(
                "NotificationApi returned {StatusCode} for type={Type}, recipient={Recipient}",
                response.StatusCode, type, recipient);
            return null;
        }
        catch (HttpRequestException ex)
        {
            // Network error — NotificationApi is unreachable
            _logger.LogWarning(ex,
                "NotificationApi unreachable. Notification not sent: type={Type}, recipient={Recipient}",
                type, recipient);
            return null;
        }
        catch (TaskCanceledException ex)
        {
            // Timeout — NotificationApi took too long to respond
            _logger.LogWarning(ex,
                "NotificationApi timed out. Notification not sent: type={Type}, recipient={Recipient}",
                type, recipient);
            return null;
        }
        catch (Exception ex)
        {
            // Unexpected error — catch-all to prevent caller failure
            _logger.LogWarning(ex,
                "Unexpected error calling NotificationApi: type={Type}, recipient={Recipient}",
                type, recipient);
            return null;
        }
    }
}

/// <summary>
/// Response model from the NotificationApi POST /api/Notify endpoint.
/// </summary>
public class NotificationResponse
{
    /// <summary>Whether the notification was accepted for delivery</summary>
    public bool Success { get; set; }

    /// <summary>Unique ID for tracking the notification</summary>
    public string? NotificationId { get; set; }

    /// <summary>Human-readable status message</summary>
    public string? Message { get; set; }
}
