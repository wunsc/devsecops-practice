namespace NotificationApi.Models;

public class NotificationStats
{
    public string City { get; set; } = string.Empty;
    public int TotalAlertsSent { get; set; }
    public int TotalSubscriptions { get; set; }
    public int AlertsTriggeredToday { get; set; }
    public DateTime RetrievedAt { get; set; }
}
