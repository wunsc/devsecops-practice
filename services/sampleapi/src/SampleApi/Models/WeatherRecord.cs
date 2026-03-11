// src/SampleApi/Models/WeatherRecord.cs
// EF Core entity for persisting weather forecast data to PostgreSQL.
// Table uses snake_case naming to follow PostgreSQL conventions.

using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace SampleApi.Models;

/// <summary>
/// Entity class representing a weather record stored in PostgreSQL.
/// Maps to the "weather_records" table with snake_case column names.
/// </summary>
[Table("weather_records")]
public class WeatherRecord
{
    /// <summary>Auto-incremented primary key</summary>
    [Key]
    [DatabaseGenerated(DatabaseGeneratedOption.Identity)]
    [Column("id")]
    public int Id { get; set; }

    /// <summary>City or location name for the forecast</summary>
    [Required]
    [MaxLength(200)]
    [Column("city")]
    public string City { get; set; } = string.Empty;

    /// <summary>Date of the forecast</summary>
    [Column("date")]
    public DateOnly Date { get; set; }

    /// <summary>Temperature in Celsius</summary>
    [Column("temperature_c")]
    public int TemperatureC { get; set; }

    /// <summary>Human-readable weather summary (e.g., "Warm", "Chilly")</summary>
    [MaxLength(100)]
    [Column("summary")]
    public string? Summary { get; set; }

    /// <summary>Timestamp when this record was created (UTC)</summary>
    [Column("created_at")]
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}
