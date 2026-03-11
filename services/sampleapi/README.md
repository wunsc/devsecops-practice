# SampleApi

.NET 8.0 Web API -- the primary microservice in the DevSecOps platform.

## Architecture

SampleApi connects to PostgreSQL (EF Core), Redis (caching via IDistributedCache),
and calls NotificationApi over HTTP for inter-service communication.

## Key Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/WeatherForecast` | GET | Returns weather forecasts (queries DB, checks cache, notifies) |
| `/healthz` | GET | Liveness probe |
| `/readyz` | GET | Readiness probe (checks PostgreSQL + Redis connectivity) |
| `/metrics` | GET | Prometheus metrics (prometheus-net) |

## Configuration

All configuration is externalized via environment variables injected by Kustomize
ConfigMap overlays (`sampleapi-env`) and Secret overlays (`sampleapi-secret`) per
environment. See `app-gitops/services/sampleapi/overlays/{env}/` for per-env values.

## Build

The Dockerfile is in the `build-config` repository (not here). Build via:
```
podman build -f build-config/Dockerfile --build-arg PROJECT_NAME=SampleApi --build-arg APP_PORT=8080 .
```

## Test

```
dotnet test tests/SampleApi.Tests/SampleApi.Tests.csproj
```
