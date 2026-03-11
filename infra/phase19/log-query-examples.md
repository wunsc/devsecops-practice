# LogQL Query Examples for DevSecOps

Useful queries for the OCP Console (Observe → Logs) or Grafana Explore.

## Basic Log Queries

### All logs from a namespace
```logql
{kubernetes_namespace_name="sampleapi-dev"}
```

### Filter by container name
```logql
{kubernetes_namespace_name="sampleapi-dev", kubernetes_container_name="sampleapi"}
```

### Search for errors (plain text match)
```logql
{kubernetes_namespace_name="sampleapi-dev"} |= "error"
```

### Exclude health check noise
```logql
{kubernetes_namespace_name="sampleapi-dev", kubernetes_container_name="sampleapi"} != "/healthz" != "/readyz"
```

## Structured JSON Queries

.NET apps with `AddJsonConsole()` emit structured logs. Parse them with `| json`.

### Error-level logs only
```logql
{kubernetes_namespace_name="sampleapi-dev", kubernetes_container_name="sampleapi"}
  | json
  | level="Error" or level="Critical"
```

### Logs from a specific controller
```logql
{kubernetes_namespace_name="sampleapi-dev"}
  | json
  | Category=~".*WeatherForecast.*"
```

### Exception stack traces
```logql
{kubernetes_namespace_name="sampleapi-dev"}
  | json
  | Exception!=""
```

### Slow requests (latency > 1000ms)
```logql
{kubernetes_namespace_name="sampleapi-dev"}
  | json
  | latency_ms > 1000
```

## Jenkins Pipeline Logs

### All Jenkins build logs
```logql
{kubernetes_namespace_name="devsecops-tools", kubernetes_container_name=~"jnlp|jenkins"}
  |= "Build #"
```

### Failed pipeline stages
```logql
{kubernetes_namespace_name="devsecops-tools", kubernetes_container_name="jnlp"}
  |= "ERROR" or |= "FAILURE"
```

### SonarQube scan results
```logql
{kubernetes_namespace_name="devsecops-tools"}
  |= "Quality Gate"
```

### ACS image scan results
```logql
{kubernetes_namespace_name="devsecops-tools"}
  |= "roxctl" or |= "CRITICAL" or |= "IMPORTANT"
```

## Cross-Service Debugging

### All errors across all environments
```logql
{kubernetes_namespace_name=~"sampleapi-.*"}
  |= "error"
```

### NotificationApi errors only
```logql
{kubernetes_namespace_name=~"sampleapi-.*", kubernetes_container_name="notificationapi"}
  | json
  | level="Error"
```

### Database connection issues
```logql
{kubernetes_namespace_name=~"sampleapi-.*"}
  |= "Npgsql" or |= "connection" or |= "timeout"
```

### Redis connection issues
```logql
{kubernetes_namespace_name=~"sampleapi-.*"}
  |= "StackExchange.Redis" or |= "redis" |= "connection"
```

## Rate and Aggregation Queries

### Error rate per namespace (errors/minute over 5m window)
```logql
sum by (kubernetes_namespace_name) (
  rate({kubernetes_namespace_name=~"sampleapi-.*"} |= "error" [5m])
)
```

### Log volume by namespace (bytes/second)
```logql
sum by (kubernetes_namespace_name) (
  bytes_rate({kubernetes_namespace_name=~"sampleapi-.*"} [5m])
)
```

### Error rate by service
```logql
sum by (kubernetes_container_name) (
  rate({kubernetes_namespace_name="sampleapi-prod"} | json | level="Error" [5m])
)
```

### Top 10 error messages
```logql
topk(10,
  sum by (message) (
    count_over_time({kubernetes_namespace_name="sampleapi-prod"} | json | level="Error" [1h])
  )
)
```

## GitLab and ArgoCD Logs

### GitLab application logs
```logql
{kubernetes_namespace_name="devsecops-gitlab", kubernetes_container_name="gitlab-ce"}
```

### ArgoCD sync events
```logql
{kubernetes_namespace_name="openshift-gitops"}
  |= "Sync" or |= "sync"
```

## Audit Trail Queries

### API server audit: who deleted a resource
```logql
{log_type="audit"}
  | json
  | verb="delete"
  | objectRef_namespace="sampleapi-prod"
```

### API server audit: secret access
```logql
{log_type="audit"}
  | json
  | objectRef_resource="secrets"
  | objectRef_namespace=~"sampleapi-.*"
```

## Tips

1. **Start broad, then narrow**: Begin with `{namespace="..."}`, add filters incrementally
2. **Use `| json` early**: Enables field-level filtering on structured logs
3. **Time range matters**: Shorter ranges = faster queries. Start with 15m, expand if needed
4. **Label selectors are fastest**: `{namespace="...", container="..."}` is faster than `|= "text"`
5. **Rate queries need `[duration]`**: Always specify a range like `[5m]` or `[1h]`
6. **OCP Console vs Grafana**: Console shows raw logs; Grafana Explore supports visualization
