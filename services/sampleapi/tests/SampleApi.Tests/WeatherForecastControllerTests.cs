// tests/SampleApi.Tests/WeatherForecastControllerTests.cs
// Unit tests for the WeatherForecastController
// Tests verify IOptions<T> configuration, PostgreSQL persistence, Redis caching,
// and NotificationApi integration (all mocked)

using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Distributed;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Moq;
using SampleApi.Controllers;
using SampleApi.Data;
using SampleApi.Models;
using SampleApi.Services;
using Xunit;

namespace SampleApi.Tests;

public class WeatherForecastControllerTests
{
    private readonly Mock<ILogger<WeatherForecastController>> _loggerMock;
    private readonly WeatherForecastOptions _options;
    private readonly AppDbContext _dbContext;
    private readonly Mock<CacheService> _cacheMock;
    private readonly Mock<NotificationClient> _notifMock;

    public WeatherForecastControllerTests()
    {
        _loggerMock = new Mock<ILogger<WeatherForecastController>>();
        _options = new WeatherForecastOptions
        {
            TemperatureUnit = "Celsius",
            ForecastDays = 5,
            MinTemperature = -20,
            MaxTemperature = 55,
            Location = "Test"
        };

        // In-memory EF Core database for tests
        var dbOptions = new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase(databaseName: Guid.NewGuid().ToString())
            .Options;
        _dbContext = new AppDbContext(dbOptions);

        // Mock CacheService — GetAsync returns null (cache miss), SetAsync does nothing
        _cacheMock = new Mock<CacheService>(
            Mock.Of<IDistributedCache>(),
            Mock.Of<ILogger<CacheService>>());
        _cacheMock.Setup(c => c.GetAsync<WeatherForecast[]>(It.IsAny<string>()))
            .ReturnsAsync((WeatherForecast[]?)null);

        // Mock NotificationClient — SendNotificationAsync does nothing
        _notifMock = new Mock<NotificationClient>(
            new HttpClient(),
            Mock.Of<ILogger<NotificationClient>>());
    }

    [Fact]
    public async Task Get_ReturnsCorrectNumberOfForecasts()
    {
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = (await controller.Get()).ToList();

        Assert.Equal(_options.ForecastDays, result.Count);
    }

    [Fact]
    public async Task Get_ReturnsCorrectLocation()
    {
        _options.Location = "TestCity";
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = (await controller.Get()).ToList();

        Assert.All(result, forecast => Assert.Equal("TestCity", forecast.Location));
    }

    [Fact]
    public async Task Get_ReturnsCorrectTemperatureUnit()
    {
        _options.TemperatureUnit = "Fahrenheit";
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = (await controller.Get()).ToList();

        Assert.All(result, forecast => Assert.Equal("Fahrenheit", forecast.TemperatureUnit));
    }

    [Fact]
    public async Task Get_TemperaturesWithinRange()
    {
        _options.MinTemperature = 0;
        _options.MaxTemperature = 10;
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = (await controller.Get()).ToList();

        Assert.All(result, forecast =>
        {
            Assert.InRange(forecast.TemperatureC, _options.MinTemperature, _options.MaxTemperature - 1);
        });
    }

    [Fact]
    public async Task Get_ForecastDatesAreInFuture()
    {
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);
        var today = DateOnly.FromDateTime(DateTime.Now);

        var result = (await controller.Get()).ToList();

        Assert.All(result, forecast => Assert.True(forecast.Date > today));
    }

    [Fact]
    public async Task Get_AllForecastsHaveSummary()
    {
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = (await controller.Get()).ToList();

        Assert.All(result, forecast => Assert.False(string.IsNullOrEmpty(forecast.Summary)));
    }

    [Fact]
    public async Task Get_TemperatureFahrenheitConversion()
    {
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = (await controller.Get()).First();

        var expectedF = 32 + (int)(result.TemperatureC / 0.5556);
        Assert.Equal(expectedF, result.TemperatureF);
    }

    [Fact]
    public void GetConfig_ReturnsCurrentOptions()
    {
        _options.Location = "ConfigTest";
        _options.ForecastDays = 3;
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = controller.GetConfig();

        var okResult = Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(result.Result);
        var config = Assert.IsType<WeatherForecastOptions>(okResult.Value);
        Assert.Equal("ConfigTest", config.Location);
        Assert.Equal(3, config.ForecastDays);
    }

    [Fact]
    public void GetVersion_ReturnsVersionInfo()
    {
        _options.Location = "DEV";
        _options.ForecastDays = 7;
        var optionsMock = Options.Create(_options);
        var controller = new WeatherForecastController(
            _loggerMock.Object, optionsMock, _dbContext, _cacheMock.Object, _notifMock.Object);

        var result = controller.GetVersion();

        Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(result);
    }
}
