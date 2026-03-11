# NotificationApi

.NET 8.0 internal microservice -- receives notification requests from SampleApi
and publishes events via Redis pub/sub.

## Key Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/Notify` | POST | Accepts notification payload, publishes to Redis |
| `/healthz` | GET | Liveness probe |
| `/readyz` | GET | Readiness probe (checks Redis connectivity) |
| `/metrics` | GET | Prometheus metrics (prometheus-net) |

## Configuration

All configuration is externalized via environment variables injected by Kustomize
ConfigMap overlays (`notificationapi-env`) and Secret overlays (`notificationapi-secret`)
per environment. See `app-gitops/services/notificationapi/overlays/{env}/` for per-env values.

## Build

The Dockerfile is in the `build-config` repository (not here). Build via:
```
podman build -f build-config/Dockerfile --build-arg PROJECT_NAME=NotificationApi --build-arg APP_PORT=8081 .
```

## Architecture

NotificationApi is an internal service (no OpenShift Route). SampleApi calls it
over HTTP on port 8081 within the namespace. It connects to Redis for pub/sub.

```
SampleApi --HTTP--> NotificationApi --pub/sub--> Redis
```
