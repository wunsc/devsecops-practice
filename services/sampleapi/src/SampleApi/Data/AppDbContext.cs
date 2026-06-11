// src/SampleApi/Data/AppDbContext.cs
// EF Core DbContext for PostgreSQL persistence.
// Connection string is built from environment variables injected by Kustomize ConfigMap/Secret overlays.
// The app works without PostgreSQL — all DB operations are wrapped in try/catch at the caller level.

using Microsoft.EntityFrameworkCore;
using SampleApi.Models;

namespace SampleApi.Data;

/// <summary>
/// Application database context for PostgreSQL via Npgsql.
/// Uses snake_case table/column naming to follow PostgreSQL conventions.
/// </summary>
public class AppDbContext : DbContext
{
    public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
    {
    }

    /// <summary>Weather forecast records persisted to PostgreSQL</summary>
    public DbSet<WeatherRecord> WeatherRecords { get; set; } = null!;

    /// <summary>Alert subscriptions for weather threshold notifications</summary>
    public DbSet<AlertSubscription> AlertSubscriptions { get; set; } = null!;

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        // Configure the WeatherRecord entity with snake_case table name
        modelBuilder.Entity<WeatherRecord>(entity =>
        {
            entity.ToTable("weather_records");

            // Index on city + date for efficient lookups
            entity.HasIndex(e => new { e.City, e.Date })
                  .HasDatabaseName("ix_weather_records_city_date");

            // Default value for created_at — PostgreSQL now() function
            entity.Property(e => e.CreatedAt)
                  .HasDefaultValueSql("now()");
        });

        modelBuilder.Entity<AlertSubscription>(entity =>
        {
            entity.ToTable("alert_subscriptions");
            entity.HasIndex(e => e.City)
                  .HasDatabaseName("ix_alert_subscriptions_city");
            entity.Property(e => e.CreatedAt)
                  .HasDefaultValueSql("now()");
        });
    }
}
