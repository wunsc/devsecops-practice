// app-source/tests/NotificationApi.Tests/NotifyControllerTests.cs
// Unit tests for the NotifyController
// Tests verify that the controller correctly publishes to Redis pub/sub
// and handles error conditions gracefully

using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;
using Moq;
using NotificationApi.Controllers;
using NotificationApi.Models;
using StackExchange.Redis;
using Xunit;

namespace NotificationApi.Tests;

public class NotifyControllerTests
{
    private readonly Mock<ILogger<NotifyController>> _loggerMock;
    private readonly Mock<IConnectionMultiplexer> _redisMock;
    private readonly Mock<ISubscriber> _subscriberMock;

    public NotifyControllerTests()
    {
        _loggerMock = new Mock<ILogger<NotifyController>>();
        _redisMock = new Mock<IConnectionMultiplexer>();
        _subscriberMock = new Mock<ISubscriber>();

        // Default setup: Redis is connected and subscriber is available
        _redisMock.Setup(r => r.IsConnected).Returns(true);
        _redisMock.Setup(r => r.GetSubscriber(It.IsAny<object>()))
            .Returns(_subscriberMock.Object);
    }

    [Fact]
    public async Task Post_ValidRequest_Returns202Accepted()
    {
        // Arrange
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);
        var request = new NotificationRequest
        {
            Type = "weather_alert",
            Message = "Severe thunderstorm warning",
            Recipient = "user@example.com"
        };

        // Act
        var result = await controller.Post(request);

        // Assert — 202 Accepted is the expected status for fire-and-forget
        var acceptedResult = Assert.IsType<AcceptedResult>(result.Result);
        var response = Assert.IsType<NotificationResponse>(acceptedResult.Value);
        Assert.Equal("queued", response.Status);
        Assert.Equal("notifications", response.Channel);
        Assert.NotEqual(Guid.Empty, response.Id);
    }

    [Fact]
    public async Task Post_ValidRequest_PublishesToRedisChannel()
    {
        // Arrange
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);
        var request = new NotificationRequest
        {
            Type = "weather_alert",
            Message = "Heavy rain expected",
            Recipient = "operations-team"
        };

        // Act
        await controller.Post(request);

        // Assert — verify that PublishAsync was called on the "notifications" channel
        _subscriberMock.Verify(
            s => s.PublishAsync(
                It.Is<RedisChannel>(ch => ch.ToString() == "notifications"),
                It.Is<RedisValue>(v => v.ToString().Contains("Heavy rain expected")),
                It.IsAny<CommandFlags>()),
            Times.Once);
    }

    [Fact]
    public async Task Post_EmptyType_ReturnsBadRequest()
    {
        // Arrange
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);
        var request = new NotificationRequest
        {
            Type = "",
            Message = "Some message",
            Recipient = "user@example.com"
        };

        // Act
        var result = await controller.Post(request);

        // Assert — missing Type should be rejected
        Assert.IsType<BadRequestObjectResult>(result.Result);
    }

    [Fact]
    public async Task Post_EmptyMessage_ReturnsBadRequest()
    {
        // Arrange
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);
        var request = new NotificationRequest
        {
            Type = "weather_alert",
            Message = "",
            Recipient = "user@example.com"
        };

        // Act
        var result = await controller.Post(request);

        // Assert — missing Message should be rejected
        Assert.IsType<BadRequestObjectResult>(result.Result);
    }

    [Fact]
    public async Task Post_ResponseContainsTimestamp()
    {
        // Arrange
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);
        var beforePost = DateTime.UtcNow;
        var request = new NotificationRequest
        {
            Type = "system",
            Message = "Scheduled maintenance at midnight",
            Recipient = "all-users"
        };

        // Act
        var result = await controller.Post(request);

        // Assert — timestamp should be between before and after the call
        var acceptedResult = Assert.IsType<AcceptedResult>(result.Result);
        var response = Assert.IsType<NotificationResponse>(acceptedResult.Value);
        Assert.True(response.Timestamp >= beforePost);
        Assert.True(response.Timestamp <= DateTime.UtcNow);
    }

    [Fact]
    public void GetHealth_RedisConnected_ReturnsConnectedStatus()
    {
        // Arrange
        _redisMock.Setup(r => r.IsConnected).Returns(true);
        _redisMock.Setup(r => r.Configuration).Returns("localhost:6379");
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);

        // Act
        var result = controller.GetHealth();

        // Assert
        var okResult = Assert.IsType<OkObjectResult>(result);
        Assert.NotNull(okResult.Value);
    }

    [Fact]
    public void GetHealth_RedisDisconnected_ReturnsDisconnectedStatus()
    {
        // Arrange
        _redisMock.Setup(r => r.IsConnected).Returns(false);
        _redisMock.Setup(r => r.Configuration).Returns("localhost:6379");
        var controller = new NotifyController(_loggerMock.Object, _redisMock.Object);

        // Act
        var result = controller.GetHealth();

        // Assert — should still return 200 OK (this is a diagnostic endpoint, not a probe)
        var okResult = Assert.IsType<OkObjectResult>(result);
        Assert.NotNull(okResult.Value);
    }
}
