# Phase 13: Security Policies & Quality Gates

## Overview

This phase defines all security gates that enforce code quality and container
security throughout the DevSecOps pipeline. Gates are configured as "fail fast"
controls — the pipeline stops immediately when a gate fails.

## Security Gate Architecture

```
Developer ──► Pre-commit (gitleaks) ──► Git Push
                                          │
                    ┌─────────────────────┘
                    ▼
              Jenkins Pipeline
                    │
         ┌──────────┼──────────────┐
         ▼          ▼              ▼
    SonarQube    OWASP          gitleaks
    (SAST)     Dep-Check         (secrets)
    Quality      (SCA)
    Gate
         │          │
         ▼          ▼
      Image Build (Podman)
         │
         ▼
    ┌────────────────────┐
    │   ACS (StackRox)   │
    ├────────────────────┤
    │ BUILD-TIME:        │
    │  Block CVSS >= 9.0 │
    ├────────────────────┤
    │ DEPLOY-TIME:       │
    │  Block root user   │
    │  Block untrusted   │
    │   registries       │
    │  Require signature │
    │   (prod only)      │
    ├────────────────────┤
    │ RUNTIME:           │
    │  Detect crypto     │
    │   miners (kill)    │
    │  Detect reverse    │
    │   shells (kill)    │
    └────────────────────┘
         │
         ▼
    OWASP ZAP (DAST)
    (T3 tag pipeline only)
```

## Files

| File | Purpose |
|------|---------|
| `sonarqube-quality-gate.json` | Quality gate definition (API-importable) |
| `acs-policies/build-block-critical-cves.json` | Block images with CVSS >= 9.0 |
| `acs-policies/deploy-block-root-user.json` | Block containers running as UID 0 |
| `acs-policies/deploy-block-untrusted-reg.json` | Allow only trusted registries |
| `acs-policies/deploy-require-signature.json` | Require Cosign signature (prod, optional) |
| `acs-policies/runtime-detect-cryptominer.json` | Kill pods running crypto miners |
| `acs-policies/runtime-detect-reverse-shell.json` | Kill pods with reverse shells |
| `gitleaks-config.toml` | Secret detection rules (pre-commit + CI) |
| `pre-commit-config.yaml` | Pre-commit hooks (gitleaks, format, lint) |
| `apply-sonarqube-gate.sh` | Script to configure SonarQube quality gate |
| `apply-acs-policies.sh` | Script to import ACS policies |

## SonarQube Quality Gate

### Conditions

| Metric | Operator | Threshold | Description |
|--------|----------|-----------|-------------|
| `new_bugs` | > | 0 | No new bugs allowed |
| `new_vulnerabilities` | > | 0 | No new vulnerabilities |
| `new_security_hotspots_reviewed` | < | 100% | All hotspots must be reviewed |
| `new_coverage` | < | 80% | Minimum 80% test coverage |
| `new_duplicated_lines_density` | > | 3% | Maximum 3% code duplication |
| `new_code_smells` | > | 10 | Maximum 10 new code smells |
| `new_reliability_rating` | > | A (1) | Must be A reliability |
| `new_security_rating` | > | A (1) | Must be A security |

### Apply

```bash
export SONARQUBE_URL="https://sonarqube-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
export SONARQUBE_TOKEN="sqa_..."
bash infra/phase13/apply-sonarqube-gate.sh
```

## ACS Security Policies

### Policy Summary

| Policy | Lifecycle | Severity | Action | Scope |
|--------|-----------|----------|--------|-------|
| Block Critical CVEs | BUILD | Critical | Fail Build | All images |
| Block Root User | DEPLOY | High | Scale to Zero | App namespaces |
| Block Untrusted Registries | DEPLOY | High | Scale to Zero | App namespaces |
| Require Image Signature | DEPLOY | Medium | Scale to Zero | Prod only (disabled) |
| Detect Crypto Mining | RUNTIME | Critical | Kill Pod | App namespaces |
| Detect Reverse Shell | RUNTIME | Critical | Kill Pod | App namespaces |

### Apply

```bash
export ACS_CENTRAL_URL="https://central-stackrox.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
export ACS_ADMIN_PASSWORD="..."  # or ACS_API_TOKEN
bash infra/phase13/apply-acs-policies.sh
```

## Pre-commit Hooks

### Setup

```bash
cd app-source/
pip install pre-commit
cp ../infra/phase13/gitleaks-config.toml .gitleaks.toml
cp ../infra/phase13/pre-commit-config.yaml .pre-commit-config.yaml
pre-commit install
pre-commit run --all-files
```

### Hooks

| Hook | What It Checks |
|------|---------------|
| gitleaks | Leaked secrets, tokens, keys |
| trailing-whitespace | Trailing whitespace |
| end-of-file-fixer | Missing newline at EOF |
| check-yaml | YAML syntax |
| check-json | JSON syntax |
| check-merge-conflict | Merge conflict markers |
| check-added-large-files | Files > 500KB |
| detect-private-key | PEM private keys |
| dotnet-format | .NET code formatting |
| yamllint | YAML lint (relaxed) |
| markdownlint | Markdown lint |

## Troubleshooting

### SonarQube quality gate not enforced

1. Verify gate exists: `${SONARQUBE_URL}/quality_gates`
2. Verify gate is set as default or associated with the project
3. Check that `scanSonarQube.groovy` polls the quality gate status
4. The scanner must run on "new code" — first scan baseline won't fail

### ACS policy not blocking

1. Verify policy is not disabled: `disabled: false` in JSON
2. Verify scope includes the target namespace
3. Check enforcement action matches lifecycle stage
4. For build-time: `roxctl image check` must be run with `--policy-categories`
5. For deploy-time: admission controller must be enabled on SecuredCluster CR

### gitleaks false positives

Add patterns to `.gitleaks.toml` allowlist:
```toml
[allowlist]
  regexes = [
    '''your-pattern-here''',
  ]
```

Or use inline comments in code:
```
# gitleaks:allow
```
