# Phase 27: RHTPA (Red Hat Trusted Profile Analyzer) v1.1.4

Deploys RHTPA (Trustify) for SBOM management, vulnerability analysis, and
advisory tracking. Pipeline-generated SBOMs are uploaded here for analysis.

## Why RHTPA?

Generating an SBOM is only half the story. RHTPA provides:
- **SBOM storage**: Central repository for all service SBOMs
- **Vulnerability analysis**: Cross-references SBOMs against CVE databases
- **Advisory tracking**: Imports Red Hat CSAF advisories, CWE database
- **Pipeline gate**: generateSBOM.groovy uploads SBOM → queries vulnerabilities → gates on thresholds

## Components

| Component | Namespace | Purpose |
|-----------|-----------|---------|
| RHTPA Operator | test-app | Manages Trustify lifecycle |
| Server | test-app | REST API + web UI for SBOM management |
| Importer | test-app | Imports CVE databases and Red Hat CSAF advisories |
| PostgreSQL | test-app | Backend database (PVC: 30Gi) |

## Prerequisites

- Phase 25 (Keycloak) with trustify realm
- ODF storage (for S3-compatible object storage)
- Keycloak CA cert distributed to RHTPA namespace

## Deployment Order

1. Install RHTPA Operator (Subscription in test-app namespace)
2. Copy Keycloak CA cert to test-app namespace
3. Deploy PostgreSQL with PVC
4. Create TrustedProfileAnalyzer CR
5. Configure importers (CVE, Red Hat CSAF, CWE)
6. Add walker client credentials to Jenkins

## Pipeline Integration

The `generateSBOM.groovy` shared library function:
1. Generates CycloneDX SBOM (.NET or Java)
2. Uploads SBOM to RHTPA via REST API (using walker client token)
3. Queries RHTPA for vulnerabilities associated with the SBOM
4. Gates pipeline on critical/high vulnerability thresholds

## Credentials

| Client | ID | Secret | Purpose |
|--------|-----|--------|---------|
| walker | walker | walker-secret-rhtpa-2026 | Pipeline SBOM upload |
| frontend | frontend | (public) | Web UI access |
| admin | admin | admin123 | Trustify realm admin |

## Verification

```bash
# Check pods
oc get pods -n test-app

# Check server route
oc get route server -n test-app -o jsonpath='{.spec.host}'

# Check importer status
oc logs -n test-app deployment/importer --tail=20

# Query advisory count
TRUSTIFY_URL=$(oc get route server -n test-app -o jsonpath='https://{.spec.host}')
TOKEN=$(curl -sfk -d "client_id=walker&client_secret=walker-secret-rhtpa-2026&grant_type=client_credentials" \
  "https://keycloak.apps.<cluster>/realms/trustify/protocol/openid-connect/token" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
curl -sfk -H "Authorization: Bearer $TOKEN" "${TRUSTIFY_URL}/api/v2/advisory?limit=1" | python3 -m json.tool
```
