# Phase 25: Keycloak (RHBK) for Supply Chain OIDC

Deploys Red Hat Build of Keycloak (RHBK) v26.4 as the OIDC identity provider
for RHTAS (Fulcio keyless signing) and RHTPA (Trustify SBOM analysis).

## Components

| Component | Namespace | Purpose |
|-----------|-----------|---------|
| Keycloak Operator | rhbok-operator | Manages Keycloak lifecycle |
| Keycloak Instance | rhbok-operator | OIDC provider (trustify realm) |
| PostgreSQL 15 | rhbok-operator | Keycloak backend database |
| TLS Certificate | rhbok-operator | Self-signed cert for HTTPS |
| Trustify Realm | rhbok-operator | Realm with frontend + walker clients |

## Deployment Order

1. Create namespace and install Keycloak Operator
2. Deploy PostgreSQL database with PVC
3. Generate TLS certificate and create secret
4. Deploy Keycloak CR
5. Import trustify realm via KeycloakRealmImport
6. Add walker client for pipeline SBOM upload (RHTPA)
7. Verify OIDC endpoint accessibility

## Access

- URL: https://keycloak.apps.<cluster-domain>
- Admin credentials: from `keycloak-initial-admin` secret
- Trustify realm admin: admin / admin123
- Walker client: walker / walker-secret-rhtpa-2026

## Verification

```bash
# Check Keycloak pod
oc get pods -n rhbok-operator

# Check route
oc get route keycloak -n rhbok-operator

# Test OIDC discovery
curl -sk https://keycloak.apps.<cluster>/realms/trustify/.well-known/openid-configuration | python3 -m json.tool
```
