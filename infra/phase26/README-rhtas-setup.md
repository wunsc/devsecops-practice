# Phase 26: RHTAS (Red Hat Trusted Artifact Signer) v1.4.0

Deploys RHTAS for keyless container image signing and verification using
Sigstore components (Fulcio, Rekor, CTLog, TUF, Trillian, TSA).

## Why RHTAS?

Traditional image signing uses long-lived keys that can be leaked or stolen.
RHTAS provides keyless signing — identity-based certificates issued by Fulcio
(tied to your Keycloak OIDC identity) and recorded in Rekor (immutable
transparency log). No private keys to manage.

## Components

| Component | Purpose |
|-----------|---------|
| Fulcio | Short-lived certificate authority (issues certs via OIDC identity) |
| Rekor | Transparency log (immutable record of all signing events) |
| CTLog | Certificate Transparency log |
| TUF | The Update Framework (root of trust distribution) |
| Trillian | Merkle tree backend for Rekor and CTLog |
| TSA | Timestamp Authority (RFC 3161 timestamps) |

## Prerequisites

- Phase 25 (Keycloak) completed with trustify realm configured
- Keycloak OIDC endpoint accessible from the cluster

## Deployment Order

1. Install RHTAS Operator (Subscription)
2. Create Securesign CR with Fulcio OIDC pointing to Keycloak trustify realm
3. Wait for all components to become ready
4. Verify routes (fulcio-server, rekor-server, rekor-search-ui)
5. Test signing with cosign

## Pipeline Integration

The `signImage.groovy` shared library function uses RHTAS for:
1. **Keyless signing**: `cosign sign` with Fulcio identity token
2. **SBOM attestation**: `cosign attest` with CycloneDX SBOM
3. **Key-based fallback**: If keyless fails, falls back to cosign key pair

## Verification

```bash
# Check all RHTAS pods
oc get pods -n trusted-artifact-signer

# Check routes
oc get routes -n trusted-artifact-signer

# Test Rekor health
curl -sk https://rekor-server-trusted-artifact-signer.apps.<cluster>/api/v1/log

# Test Fulcio health
curl -sk https://fulcio-server-trusted-artifact-signer.apps.<cluster>/healthz
```
