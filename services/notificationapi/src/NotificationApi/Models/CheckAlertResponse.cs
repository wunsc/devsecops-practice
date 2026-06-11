namespace NotificationApi.Models;

public class CheckAlertResponse
{
    public string SubscriptionId { get; set; } = string.Empty;
    public string City { get; set; } = string.Empty;
    public int ThresholdTemp { get; set; }
    public int CurrentTemp { get; set; }
    public bool AlertTriggered { get; set; }
    public string? AlertMessage { get; set; }
    public DateTime EvaluatedAt { get; set; }
}
