# Registry Configuration — Setup Guide

## Registry Choice: OCP Internal Registry

**Decision:** Use the OpenShift internal container registry instead of an external registry (Quay.io).

**Rationale:**
- No external registry credentials to manage
- Images stay within the cluster network (faster pulls, no egress costs)
- Integrated with OpenShift RBAC (ServiceAccount tokens for auth)
- Cross-namespace image pulling via `system:image-puller` RoleBindings
- Suitable for development/sandbox environments

**Trade-offs vs Quay.io:**
- No built-in vulnerability scanning (handled by ACS in Phase 5)
- No image replication across clusters
- No robot accounts or team-based access control
- For production, consider migrating to Quay.io for enterprise features

## Image Naming Convention

```
Internal Registry:
  image-registry.openshift-image-registry.svc:5000/{namespace}/{image}:{tag}

This project (2 services):
  image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:{tag}
  image-registry.openshift-image-registry.svc:5000/sampleapi-dev/notificationapi:{tag}

Tag patterns:
  T2 (merge to main):  main-<short-sha>     e.g., main-a1b2c3d
  T3 (git tag):         <tag-name>           e.g., v1.2.0
  T3 (additional):      latest-release       (always points to latest tagged release)
```

## How It Works

### Image Push (Jenkins Pipeline)
1. Jenkins agent builds the image with Podman
2. `podman push` to `image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:<tag>`
3. Authentication: Jenkins SA token (granted `system:image-builder` on `sampleapi-dev`)

### Image Pull (Application Pods)
- **DEV namespace:** Pods pull from their own namespace — no extra config needed
- **SIT/UAT/PROD namespaces:** Pods pull from `sampleapi-dev` namespace via cross-namespace `system:image-puller` RoleBindings (created in Phase 1)

### Cross-Namespace Pull Flow
```
sampleapi-dev (images stored here)
    ↑ system:image-puller RoleBinding
    ├── sampleapi-sit SA
    ├── sampleapi-uat SA
    └── sampleapi-prod SA
```

## Execution Order

```bash
# 1. Apply pull secret strategy (ConfigMap documenting the approach)
oc apply -f infra/phase4/registry-pull-secrets.yaml

# 2. Apply Jenkins SA image-builder/puller RoleBindings
oc apply -f infra/phase4/registry-sa-rolebinding.yaml
```

## Verification

```bash
# Verify Jenkins SA can push images to sampleapi-dev
oc auth can-i create imagestreams --as=system:serviceaccount:devsecops-tools:jenkins-sa -n sampleapi-dev
# Expected: yes

# Verify Jenkins SA can push image layers
oc auth can-i update imagestreams/layers --as=system:serviceaccount:devsecops-tools:jenkins-sa -n sampleapi-dev
# Expected: yes

# Verify cross-namespace pull (SIT SA can pull from DEV)
oc auth can-i get imagestreams/layers --as=system:serviceaccount:sampleapi-sit:sampleapi-sa -n sampleapi-dev
# Expected: yes

# Verify internal registry is accessible within the cluster
oc get svc -n openshift-image-registry
# Expected: image-registry ClusterIP on port 5000

# Check registry pod is healthy
oc get pods -n openshift-image-registry -l docker-registry=default
```

## Migrating to External Registry (Future)

If migrating to Quay.io or another external registry:

1. Create dockerconfigjson secrets (template in `registry-pull-secrets.yaml`)
2. Link secrets to SAs: `oc secrets link <sa> <secret> --for=pull -n <namespace>`
3. Update `pushToRegistry.groovy` in the shared library with new registry URL
4. Update Kustomize overlays with new image references
5. Update ACS trusted registries policy (Phase 5)
