namespace NotificationApi.Models;

public class SubscribeRequest
{
    public string City { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public int ThresholdTemp { get; set; }
}
