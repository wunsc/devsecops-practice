namespace NotificationApi.Models;

public class CheckAlertRequest
{
    public string SubscriptionId { get; set; } = string.Empty;
    public string City { get; set; } = string.Empty;
    public int CurrentTemp { get; set; }
}
