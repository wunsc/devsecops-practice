// app-source/tests/NotificationApi.Tests/HealthControllerTests.cs
// Unit tests for the NotificationApi HealthController
// Tests verify liveness and readiness probes, including Redis connectivity checks

using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;
using Moq;
using NotificationApi.Controllers;
using StackExchange.Redis;
using Xunit;

namespace NotificationApi.Tests;

public class HealthControllerTests
{
    private readonly Mock<ILogger<HealthController>> _loggerMock;
    private readonly Mock<IConnectionMultiplexer> _redisMock;
    private readonly Mock<IDatabase> _databaseMock;

    public HealthControllerTests()
    {
        _loggerMock = new Mock<ILogger<HealthController>>();
        _redisMock = new Mock<IConnectionMultiplexer>();
        _databaseMock = new Mock<IDatabase>();

        _redisMock.Setup(r => r.GetDatabase(It.IsAny<int>(), It.IsAny<object>()))
            .Returns(_databaseMock.Object);
    }

    [Fact]
    public void Healthz_ReturnsOkWithHealthyStatus()
    {
        // Arrange
        _redisMock.Setup(r => r.IsConnected).Returns(true);
        var controller = new HealthController(_loggerMock.Object, _redisMock.Object);

        // Act
        var result = controller.Healthz();

        // Assert — liveness probe should always return 200 if the process is running
        Assert.IsType<OkObjectResult>(result);
    }

    [Fact]
    public void Readyz_RedisConnected_ReturnsOk()
    {
        // Arrange
        _redisMock.Setup(r => r.IsConnected).Returns(true);
        _databaseMock.Setup(d => d.Ping(It.IsAny<CommandFlags>()))
            .Returns(TimeSpan.FromMilliseconds(1));
        var controller = new HealthController(_loggerMock.Object, _redisMock.Object);

        // Act
        var result = controller.Readyz();

        // Assert — readiness should return 200 when Redis is connected
        Assert.IsType<OkObjectResult>(result);
    }

    [Fact]
    public void Readyz_RedisDisconnected_Returns503()
    {
        // Arrange
        _redisMock.Setup(r => r.IsConnected).Returns(false);
        var controller = new HealthController(_loggerMock.Object, _redisMock.Object);

        // Act
        var result = controller.Readyz();

        // Assert — readiness should return 503 when Redis is down
        // This tells Kubernetes to stop routing traffic to this pod
        var statusResult = Assert.IsType<ObjectResult>(result);
        Assert.Equal(503, statusResult.StatusCode);
    }

    [Fact]
    public void Readyz_RedisPingThrows_Returns503()
    {
        // Arrange — Redis reports connected but ping throws (transient network issue)
        _redisMock.Setup(r => r.IsConnected).Returns(true);
        _databaseMock.Setup(d => d.Ping(It.IsAny<CommandFlags>()))
            .Throws(new RedisConnectionException(ConnectionFailureType.UnableToConnect, "timeout"));
        var controller = new HealthController(_loggerMock.Object, _redisMock.Object);

        // Act
        var result = controller.Readyz();

        // Assert — should gracefully handle the exception and return 503
        var statusResult = Assert.IsType<ObjectResult>(result);
        Assert.Equal(503, statusResult.StatusCode);
    }
}
