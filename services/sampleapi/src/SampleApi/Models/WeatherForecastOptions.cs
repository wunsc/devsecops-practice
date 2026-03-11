// app-source/src/SampleApi/Models/WeatherForecastOptions.cs
// IOptions<T> class for externalized configuration (Rule 3)
// Values come from appsettings.json, overridden by environment variables
// from Kustomize ConfigMap overlays per environment

namespace SampleApi.Models;

/// <summary>
/// Configuration options for the WeatherForecast feature.
/// Bound to the "WeatherForecast" section in appsettings.json.
/// Overridable via environment variables (e.g., WeatherForecast__TemperatureUnit).
/// </summary>
public class WeatherForecastOptions
{
    /// <summary>Temperature unit: Celsius or Fahrenheit</summary>
    public string TemperatureUnit { get; set; } = "Celsius";

    /// <summary>Number of forecast days to return</summary>
    public int ForecastDays { get; set; } = 5;

    /// <summary>Minimum temperature for random generation</summary>
    public int MinTemperature { get; set; } = -20;

    /// <summary>Maximum temperature for random generation</summary>
    public int MaxTemperature { get; set; } = 55;

    /// <summary>Location name for the forecast</summary>
    public string Location { get; set; } = "Default";
}
