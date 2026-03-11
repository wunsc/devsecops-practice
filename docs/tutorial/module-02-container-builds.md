# Module 2: Container Builds on OpenShift

**Duration:** ~60 minutes | **Track:** Foundation | **Prerequisite:** Module 1 (Infrastructure Foundation)

---

## What You'll Learn

By the end of this module, you will be able to:

1. Explain why Docker-in-Docker is banned on OpenShift and what replaces it.
2. Write a multi-stage Dockerfile that produces a small, secure .NET 8 image.
3. Build container images with Podman using the correct flags for OpenShift.
4. Push images to the OpenShift internal registry and deploy them as pods.
5. Use `.dockerignore` to keep build context lean and builds fast.

---

## Why This Matters

Every DevSecOps pipeline eventually needs to turn source code into a container image. On a standard laptop you might just run `docker build` and move on. On OpenShift, that command will fail. Understanding *why* it fails -- and what to do instead -- is the difference between a pipeline that works in a demo and one that works in production.

Three constraints shape everything in this module:

1. **No Docker socket.** OpenShift does not expose the Docker daemon. There is no `/var/run/docker.sock` to mount.
2. **No root.** Containers run as arbitrary, non-root UIDs by default. The build tool must handle that.
3. **No FUSE device.** The overlay filesystem driver that Podman normally uses requires a FUSE device, which is not available inside an OpenShift pod.

These are not limitations -- they are security features. They prevent container escape attacks, privilege escalation, and host filesystem manipulation. Your job is to work *with* them, not around them.

---

## Prerequisites

Before starting this module, confirm:

- [ ] Module 1 completed -- namespaces `devsecops-tools` and `sampleapi-dev` exist.
- [ ] You have `oc` CLI access and are logged into the cluster.
- [ ] Podman is installed on your local machine (for local experimentation).
- [ ] The `.NET 8 SDK` is available locally (optional, for the "quick win" step).

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

Verify your environment:

```bash
# Confirm cluster access
$OC whoami
# Expected: your username

# Confirm namespaces exist
$OC get ns $NS_TOOLS $NS_DEV
# Expected: both namespaces listed as Active

# Confirm Podman is installed locally
podman --version
# Expected: podman version 4.x or 5.x
```

---

## Concepts: The Container Build Landscape on OpenShift

### Why Not Docker?

Docker is a monolithic daemon that runs as root. When you type `docker build`, your CLI sends the build context to a long-running root daemon via a Unix socket. That daemon does the actual work: pulling base images, executing `RUN` commands, layering filesystems.

This architecture has a fundamental problem: **anyone who can talk to the Docker socket has root access to the host.** Mounting that socket inside a container (Docker-in-Docker or DinD) gives the container full control over the host kernel. On a shared cluster, that is a security disaster.

OpenShift enforces Security Context Constraints (SCCs) that prevent:
- Running as root (UID 0)
- Accessing the Docker socket
- Using privileged containers
- Mounting host paths

> **Callout: The Rule**
>
> PLAN.md, Rule 36: *"Use BuildConfig or Podman for image builds -- never Docker-in-Docker."*
>
> This is not a suggestion. ACS (StackRox) policies will block privileged containers at admission time.

### Why Podman?

Podman is a daemonless, rootless container engine. There is no background process. Each `podman build` command is a standalone process that runs in the user's namespace. No socket. No daemon. No root required.

The CLI is deliberately Docker-compatible: `podman build`, `podman push`, `podman run` all accept the same flags as their Docker equivalents. If you know Docker, you already know 90% of Podman.

But on OpenShift, Podman needs two special flags:

| Flag | Why |
|------|-----|
| `--storage-driver=vfs` | The default `overlay` driver requires a FUSE device, which is not available inside an OpenShift pod. VFS uses plain file copies instead. Slower, but it works everywhere. |
| `--isolation=chroot` | The default isolation mode requires additional Linux capabilities that OpenShift's restricted SCC does not grant. The `chroot` mode works within the restricted SCC without needing those capabilities. |

You will see these two flags on every Podman command in this project. If you forget them, you will get errors like `Error: kernel does not support overlay fs` or `error creating runtime: ... permission denied`.

### Why Multi-Stage Builds?

A naive Dockerfile installs the SDK, copies source code, builds, and ships the result. The final image contains the compiler, all NuGet package caches, and every intermediate artifact. For .NET 8, that means an image over 1 GB.

A multi-stage build uses two (or more) `FROM` statements:

```
Stage 1 (build):   SDK image (~800 MB) --> compile, test, publish
                         |
                    COPY --from=build
                         |
Stage 2 (runtime): ASP.NET runtime image (~220 MB) --> run the app
```

The final image contains only the runtime and your published DLLs. The SDK, source code, and build artifacts are discarded.

This matters for three reasons:

1. **Security.** Fewer packages = fewer CVEs. The SDK contains compilers, debuggers, and utilities that attackers love. The runtime image does not.
2. **Speed.** Smaller images push and pull faster. In a pipeline that builds, pushes, and deploys on every merge, this adds up.
3. **Cost.** Smaller images consume less registry storage and less network bandwidth.

---

## Step 1: Understand Multi-Stage Builds (Quick Win)

Before touching OpenShift, let us build a simple multi-stage image locally to see the concept in action.

Create a temporary directory and write a minimal two-stage Dockerfile:

```bash
# Create a scratch area (this is for learning only, not part of the project)
mkdir -p /tmp/multistage-demo && cd /tmp/multistage-demo
```

Create a minimal .NET app:

```bash
dotnet new webapi -n DemoApi --no-https
```

Now write the Dockerfile:

```dockerfile
# /tmp/multistage-demo/Dockerfile
# Stage 1: Build — uses the full SDK (large image, has compilers)
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY DemoApi/ ./DemoApi/
# Restore packages, build, and publish a release binary
RUN dotnet publish DemoApi/DemoApi.csproj \
    --configuration Release \
    --output /app/publish

# Stage 2: Runtime — uses the slim ASP.NET base (small image, no compilers)
FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime
WORKDIR /app
# Copy ONLY the published output from the build stage
COPY --from=build /app/publish .
# Non-root user — required by OpenShift and ACS policies
USER 1001
EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:8080
ENTRYPOINT ["dotnet", "DemoApi.dll"]
```

Build and compare image sizes:

```bash
# Build the multi-stage image
podman build -t demo-multi .

# For comparison, build a single-stage image (SDK included)
cat > Dockerfile.single << 'SINGLE'
FROM mcr.microsoft.com/dotnet/sdk:8.0
WORKDIR /src
COPY DemoApi/ ./DemoApi/
RUN dotnet publish DemoApi/DemoApi.csproj -c Release -o /app/publish
WORKDIR /app/publish
EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:8080
ENTRYPOINT ["dotnet", "DemoApi.dll"]
SINGLE

podman build -f Dockerfile.single -t demo-single .

# Compare sizes
podman images | grep demo
# Expected output (approximate):
#   demo-single   latest   <id>   ~900 MB
#   demo-multi    latest   <id>   ~220 MB
```

### Verify

```bash
# Run the multi-stage image to confirm it works
podman run --rm -d -p 8080:8080 --name demo-test demo-multi
curl http://localhost:8080/weatherforecast
# Expected: JSON array of weather data
podman stop demo-test
```

> **Callout: What Just Happened**
>
> The multi-stage image is roughly 75% smaller. It contains no compiler, no NuGet cache, no source code.
> An attacker who compromises this container finds only a .NET runtime and your published DLLs -- far less
> attack surface than a full SDK.

Clean up when done:

```bash
podman rmi demo-multi demo-single 2>/dev/null
rm -rf /tmp/multistage-demo
```

---

## Step 2: The Production Dockerfile (Annotated Walkthrough)

Now let us examine the actual Dockerfile used by the project. This file lives in the `build-config` repository -- not in the application source. That separation is deliberate: the Dockerfile is a *build concern*, not an *application concern*. Developers change application code; the platform team controls how it gets containerized.

> **Callout: Separation of Concerns**
>
> The application repo (`app-source`) contains zero CI/CD artifacts -- no Dockerfile, no Jenkinsfile, no
> sonar config. The build configuration repo (`build-config`) contains the Dockerfile, SonarQube config,
> and scan suppression files. Jenkins clones both repos and uses them together:
>
> ```
> podman build -f build-config/Dockerfile .
>              ^^^^^^^^^^^^^^^^^^^^^^^^^^  ^
>              Dockerfile from build-config  Build context is app-source root
> ```

Here is the production Dockerfile with detailed annotations:

```dockerfile
# build-config/Dockerfile
# Single parameterized Dockerfile for all .NET 8.0 services
#
# This Dockerfile lives in the build-config repo (Rule 2), NOT in app-source.
# Jenkins pipeline runs:
#   podman build --build-arg PROJECT_NAME=SampleApi -f build-config/Dockerfile .
#   podman build --build-arg PROJECT_NAME=NotificationApi --build-arg SOLUTION_NAME=NotificationApi --build-arg APP_PORT=8081 -f build-config/Dockerfile .
#
# The build context (.) is the service source workspace root.
# Each service repo has its own .sln file and src/{PROJECT_NAME}/ directory.
#
# SECURITY:
#   - Runs as non-root user (UID 1001) — enforced by ACS policy
#   - Multi-stage build — SDK tools not in final image
#
# ADDING A NEW SERVICE:
#   Set PROJECT_NAME and SOLUTION_NAME (defaults to PROJECT_NAME if not set).
#   The service repo must have: {SOLUTION_NAME}.sln, src/{PROJECT_NAME}/

# ── Build arguments (override at build time) ──
ARG PROJECT_NAME=SampleApi
ARG SOLUTION_NAME=SampleApi
ARG APP_PORT=8080

# ============================================================
# Stage 1: Build
# ============================================================
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build

ARG PROJECT_NAME
ARG SOLUTION_NAME

WORKDIR /src

# Copy entire source tree (filtered by .dockerignore)
COPY . .

# Restore NuGet packages
RUN dotnet restore ${SOLUTION_NAME}.sln

# Build the solution
RUN dotnet build ${SOLUTION_NAME}.sln --configuration Release --no-restore

# Publish only the target project (self-contained=false → requires ASP.NET runtime)
RUN dotnet publish src/${PROJECT_NAME}/${PROJECT_NAME}.csproj \
    --configuration Release \
    --no-build \
    --output /app/publish

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS runtime

ARG PROJECT_NAME
ARG APP_PORT

# OCI image labels (used by ACS for image metadata)
LABEL maintainer="DevSecOps Team" \
      org.opencontainers.image.title="${PROJECT_NAME}" \
      org.opencontainers.image.description=".NET 8.0 ${PROJECT_NAME} — DevSecOps microservice" \
      org.opencontainers.image.vendor="DevSecOps" \
      io.k8s.description=".NET 8.0 ${PROJECT_NAME} for DevSecOps pipeline" \
      io.openshift.expose-services="${APP_PORT}:http" \
      io.openshift.tags="dotnet,webapi,devsecops"

WORKDIR /app

# Copy published application from build stage
COPY --from=build /app/publish .

# Configure ASP.NET Core to listen on the specified port (unprivileged)
# OpenShift requires non-privileged ports (>1024)
ENV ASPNETCORE_URLS=http://+:${APP_PORT} \
    ASPNETCORE_ENVIRONMENT=Production \
    DOTNET_RUNNING_IN_CONTAINER=true

# Expose the application port
EXPOSE ${APP_PORT}

# Run as non-root user (UID 1001)
# ACS policy "Block Root User Containers" enforces this
USER 1001

# Health check — used by Kubernetes liveness/readiness probes
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:${APP_PORT}/healthz || exit 1

# Store the DLL name for ENTRYPOINT (ARG is not available at runtime)
ENV APP_DLL="${PROJECT_NAME}.dll"

# Start the application
ENTRYPOINT ["sh", "-c", "dotnet $APP_DLL"]
```

> **Key: One Dockerfile, Multiple Services**
>
> This single parameterized Dockerfile builds both SampleApi and NotificationApi.
> The pipeline sets `--build-arg PROJECT_NAME=SampleApi --build-arg APP_PORT=8080` for SampleApi
> and `--build-arg PROJECT_NAME=NotificationApi --build-arg APP_PORT=8081` for NotificationApi.
> This eliminates Dockerfile duplication across services -- the platform team maintains one file.

### Key Design Decisions

**Why `USER 1001` and not `USER 1000`?**

On OpenShift, each namespace assigns containers an arbitrary UID from a range (e.g., 1000620000-1000629999). The `USER` instruction in the Dockerfile sets the *default* UID, but OpenShift overrides it. What matters is that the Dockerfile does NOT specify `USER root` (UID 0). ACS enforces this: any image with `USER root` or no `USER` instruction at all will be blocked at admission time.

We use 1001 as a convention to signal "this image is designed for non-root execution." The actual runtime UID will be different.

**Why port 8080?**

Ports below 1024 require root privileges to bind. OpenShift's restricted SCC does not grant `CAP_NET_BIND_SERVICE`. Port 8080 is the standard choice for non-privileged HTTP services.

**Why `HEALTHCHECK` in the Dockerfile?**

Kubernetes has its own liveness and readiness probes defined in the Deployment manifest. The Dockerfile `HEALTHCHECK` serves as a fallback for local development (`podman run`) and as documentation. ACS can also flag images without health checks.

**Why OCI labels?**

Labels like `io.openshift.expose-services` and `io.k8s.description` tell OpenShift and ACS what this image does. ACS uses them for image classification and policy matching. They cost nothing at runtime.

---

## Step 3: Build with Podman on OpenShift

Now we move from local builds to building inside an OpenShift pod, which is how the Jenkins pipeline does it. The Jenkins agent pod has Podman installed with specific configuration.

### 3a: How the Jenkins Agent is Configured for Podman

The Jenkins agent Dockerfile (shown below in relevant excerpt) sets up Podman for rootless operation on OpenShift:

```dockerfile
# From infra/phase7/Dockerfile.agent (relevant sections)

# --- Podman configuration for OpenShift ---
# OpenShift runs containers with arbitrary UIDs; Podman needs vfs storage driver
# and subuid/subgid mappings for rootless operation
RUN echo "jenkins:1000:65536" >> /etc/subuid && \
    echo "jenkins:1000:65536" >> /etc/subgid

# Podman storage config — vfs is required on OpenShift (no fuse device)
RUN mkdir -p /home/jenkins/.config/containers && \
    echo '[storage]' > /home/jenkins/.config/containers/storage.conf && \
    echo 'driver = "vfs"' >> /home/jenkins/.config/containers/storage.conf && \
    echo '' >> /home/jenkins/.config/containers/storage.conf && \
    echo '[storage.options.vfs]' >> /home/jenkins/.config/containers/storage.conf

# Podman registries config — allow short-name pulls
RUN mkdir -p /etc/containers && \
    echo 'unqualified-search-registries = ["registry.access.redhat.com", "docker.io"]' \
    > /etc/containers/registries.conf
```

Three things happen here:

1. **subuid/subgid mappings** -- Podman needs to know what UID range it can use for user namespaces. Without this, rootless Podman cannot create containers.
2. **VFS storage driver** -- Baked into the config file so every Podman command uses it by default. The `--storage-driver=vfs` flag on the command line is a belt-and-suspenders approach.
3. **Registry config** -- Allows pulling images by short name (e.g., `ubi9/ubi` instead of `registry.access.redhat.com/ubi9/ubi`).

### 3b: How the Pipeline Calls Podman

Here is the shared library function that executes the build. Study the flags:

```groovy
// jenkins-shared-lib/vars/buildContainerImage.groovy (complete)
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def dockerfile = config.dockerfile ?: 'build-config/Dockerfile'  // <-- from build-config repo
    def imageRef = config.imageRef ?: ''
    def context = config.context ?: '.'                              // <-- app-source workspace root
    def noCache = config.noCache ?: false
    def buildArgs = config.buildArgs ?: [:]                          // <-- per-service build args

    echo "=== Build Container Image ==="
    echo "  Dockerfile: ${dockerfile}"
    echo "  Image: ${imageRef}"
    echo "  Context: ${context}"

    try {
        if (!imageRef) {
            error "imageRef is required — provide the full image reference (registry/ns/app:tag)"
        }

        // Verify Dockerfile exists
        if (!fileExists(dockerfile)) {
            error "Dockerfile not found: ${dockerfile}. Did checkoutBuildConfig() run?"
        }

        // KNOWN FIX: --storage-driver=vfs and --isolation=chroot for OpenShift
        def noCacheFlag = noCache ? '--no-cache' : ''
        // Build --build-arg flags from the buildArgs map
        def buildArgFlags = buildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')
        sh """
            podman build \\
                --storage-driver=vfs \\
                --isolation=chroot \\
                -f ${dockerfile} \\
                -t ${imageRef} \\
                ${noCacheFlag} \\
                ${buildArgFlags} \\
                ${context}
        """

        // Verify image was built
        sh "podman --storage-driver=vfs images ${imageRef}"

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Container image built in ${duration}s"

        return [status: 'SUCCESS', duration: duration, imageRef: imageRef]
    } catch (Exception e) {
        echo "ERROR: Container image build failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
```

Notice the pattern:

- `--storage-driver=vfs` appears on *every* Podman command, including `podman images`. This is required because Podman needs to know which storage backend to query.
- `--isolation=chroot` is only needed on `podman build`, not on `push` or `images`.
- The `buildArgs` map is converted to `--build-arg KEY=VALUE` flags. The pipeline orchestrator passes service-specific values like `[PROJECT_NAME: 'NotificationApi', APP_PORT: '8081']` to build different services from the same Dockerfile.
- The function returns a structured map (`status`, `duration`, `imageRef`), not a boolean. This is a project convention: every shared library function returns enough context for the orchestrator to make decisions.

### 3c: Try It Yourself (Simulated)

If you have Podman locally, you can simulate the OpenShift constraints:

```bash
cd app-source

# Build using the exact same command the pipeline uses
# -f points to the Dockerfile in build-config (relative to current directory)
# Context (.) is the app-source root
# --build-arg sets the service name and port (parameterized Dockerfile)
podman build \
    --storage-driver=vfs \
    --isolation=chroot \
    -f ../build-config/Dockerfile \
    --build-arg PROJECT_NAME=SampleApi \
    --build-arg SOLUTION_NAME=SampleApi \
    --build-arg APP_PORT=8080 \
    -t sampleapi:local-test \
    .
```

### Verify

```bash
# Confirm the image was built
podman --storage-driver=vfs images sampleapi:local-test
# Expected: one image listed, ~220 MB

# Run it to confirm it starts (--storage-driver=vfs matches the build storage)
podman --storage-driver=vfs run --rm -d -p 8080:8080 --name sampleapi-test sampleapi:local-test

# Test the health endpoint
curl http://localhost:8080/healthz
# Expected: {"status":"Healthy"} or similar

# Test the API
curl http://localhost:8080/api/WeatherForecast
# Expected: JSON array

# Clean up
podman --storage-driver=vfs stop sampleapi-test
```

---

## Step 4: Push to the OCP Internal Registry

Building the image is half the job. The other half is getting it into a registry where OpenShift can pull it to run as pods. The project uses OpenShift's built-in internal registry.

### 4a: How the Internal Registry Works

OpenShift ships with a container registry accessible at:

```
image-registry.openshift-image-registry.svc:5000
```

This address is only reachable from inside the cluster. Images are organized by namespace:

```
image-registry.openshift-image-registry.svc:5000/<namespace>/<imagestream>:<tag>
                                                  ^^^^^^^^^^^  ^^^^^^^^^^^  ^^^
                                                  sampleapi-dev  sampleapi   main-abc1234
```

An `ImageStream` is an OpenShift resource that tracks image tags. When you push an image to the internal registry, OpenShift automatically creates or updates the corresponding ImageStream tag.

### 4b: Authentication

Inside a pod (like the Jenkins agent), a ServiceAccount token is mounted at `/var/run/secrets/kubernetes.io/serviceaccount/token`. Podman uses this token to authenticate with the internal registry.

Here is the shared library function that handles the push:

```groovy
// jenkins-shared-lib/vars/pushToRegistry.groovy (authentication section)

// Internal registry — authenticate with SA token
sh """
    SA_TOKEN=\$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
    podman --storage-driver=vfs login \\
        -u serviceaccount \\
        -p "\${SA_TOKEN}" \\
        --tls-verify=false \\
        ${imageRef.split('/')[0]}
"""
sh "podman --storage-driver=vfs push --tls-verify=false ${imageRef}"
```

Key details:

- **Username is `serviceaccount`** -- this is a fixed string, not the SA name.
- **Password is the mounted token** -- read from the filesystem at runtime.
- **`--tls-verify=false`** -- the internal registry uses a self-signed certificate. In production you would add the CA to the trust store instead, but for internal cluster communication this is acceptable.

### 4c: Registry Permissions

In Module 1 (Phase 1), we set up RBAC so that:

- The `jenkins-sa` ServiceAccount in `devsecops-tools` has `system:image-pusher` role in `sampleapi-dev` (can push images).
- The ServiceAccounts in `sampleapi-sit`, `sampleapi-uat`, and `sampleapi-prod` have `system:image-puller` role in `sampleapi-dev` (can pull images).

This means all images are stored in `sampleapi-dev`, and other environments pull from there. One source of truth.

```bash
# Verify push permission (run this on the cluster)
$OC auth can-i update imagestreams/layers \
    -n $NS_DEV \
    --as=system:serviceaccount:${NS_TOOLS}:jenkins-sa
# Expected: yes

# Verify pull permission from SIT
$OC auth can-i get imagestreams/layers \
    -n $NS_DEV \
    --as=system:serviceaccount:${NS_SIT}:default
# Expected: yes
```

### 4d: Push from Your Local Machine (Optional Exercise)

If you want to push an image to the internal registry from outside the cluster, you need to expose the registry route:

```bash
# Check if registry route exists
$OC get route -n openshift-image-registry
# If no route, you need cluster-admin to create one (skip this if not admin)

# If route exists, get the hostname
REGISTRY_ROUTE=$($OC get route default-route -n openshift-image-registry -o jsonpath='{.spec.host}')

# Login using your oc token
podman login $REGISTRY_ROUTE -u $($OC whoami) -p $($OC whoami -t) --tls-verify=false

# Tag and push
podman tag sampleapi:local-test $REGISTRY_ROUTE/${NS_DEV}/sampleapi:manual-test
podman push --tls-verify=false $REGISTRY_ROUTE/${NS_DEV}/sampleapi:manual-test
```

### Verify

```bash
# Check the ImageStream
$OC get is sampleapi -n $NS_DEV
# Expected: ImageStream listed

# Check image tags
$OC get istag -n $NS_DEV | grep sampleapi
# Expected: sampleapi:manual-test (or whatever tag the pipeline pushed)
```

---

## Step 5: Deploy the Container as a Pod

With the image in the registry, let us deploy it and verify it runs on OpenShift.

### 5a: Create a Minimal Deployment

```yaml
# /tmp/sampleapi-test-deployment.yaml
# Minimal deployment to verify the container image works on OpenShift
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sampleapi-test
  namespace: sampleapi-dev
  labels:
    app: sampleapi-test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: sampleapi-test
  template:
    metadata:
      labels:
        app: sampleapi-test
    spec:
      containers:
        - name: sampleapi
          # Internal registry address — only works from inside the cluster
          image: image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:manual-test
          ports:
            - containerPort: 8080
              protocol: TCP
          # Liveness probe — restarts the pod if the app is unresponsive
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 15
          # Readiness probe — removes the pod from service endpoints if unhealthy
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 256Mi
          # Security context — enforce non-root
          securityContext:
            allowPrivilegeEscalation: false
            runAsNonRoot: true
```

Apply it:

```bash
$OC apply -f /tmp/sampleapi-test-deployment.yaml
```

### 5b: Expose it via a Service and Route

```bash
# Create a service
$OC expose deployment sampleapi-test \
    --port=8080 \
    --target-port=8080 \
    -n $NS_DEV

# Create a route (OpenShift-specific — like an Ingress)
$OC expose svc sampleapi-test -n $NS_DEV
```

### Verify

```bash
# Check pod status
$OC get pods -n $NS_DEV -l app=sampleapi-test
# Expected: 1/1 Running

# Check events if pod is not running
$OC get events -n $NS_DEV --sort-by='.lastTimestamp' | tail -10

# Get the route URL
ROUTE_URL=$($OC get route sampleapi-test -n $NS_DEV -o jsonpath='{.spec.host}')
echo "Route: http://$ROUTE_URL"

# Test the endpoints
curl -s http://$ROUTE_URL/healthz
# Expected: healthy response

curl -s http://$ROUTE_URL/api/WeatherForecast | head -c 200
# Expected: JSON data
```

### 5c: Inspect What OpenShift Did to Your Container

This is instructive. Let us see how OpenShift modified the container at runtime:

```bash
# Get the pod name
POD=$($OC get pods -n $NS_DEV -l app=sampleapi-test -o jsonpath='{.items[0].metadata.name}')

# Check what UID the container is running as
$OC exec $POD -n $NS_DEV -- id
# Expected: uid=100062xxxx(1000620000) gid=0(root) groups=0(root),100062xxxx
# Notice: NOT 1001 (from Dockerfile), but an arbitrary UID from the namespace range
# The group is always 0 (root group) — this is by design in OpenShift

# Check the process
$OC exec $POD -n $NS_DEV -- ps aux
# Expected: dotnet SampleApi.dll running as the arbitrary UID

# Check filesystem permissions
$OC exec $POD -n $NS_DEV -- ls -la /app/
# Expected: files owned by root:root, but readable by the arbitrary UID
```

> **Callout: Arbitrary UIDs**
>
> OpenShift assigns a UID from the namespace's UID range, NOT the UID specified in the Dockerfile.
> Your Dockerfile says `USER 1001`, but the pod runs as UID 1000620000 (or similar). This is why
> your application must not assume a specific UID. Files must be readable by group 0 (root group),
> which OpenShift always assigns.

### 5d: Clean Up the Test Deployment

```bash
$OC delete deployment sampleapi-test -n $NS_DEV
$OC delete svc sampleapi-test -n $NS_DEV
$OC delete route sampleapi-test -n $NS_DEV
```

---

## Step 6: The .dockerignore File

The `.dockerignore` file controls what gets sent to Podman as the build context. Without it, Podman sends *everything* in the current directory -- including `.git/` (which can be hundreds of megabytes), `bin/` and `obj/` directories, IDE files, test results, and documentation.

The `.dockerignore` must live in the **build context root**, which in this project is the `app-source` directory. Here is the project's actual file:

```
# app-source/.dockerignore
# Exclude files not needed in the Docker build context
# This file MUST be in the build context root (app-source/)
# The Dockerfile is in build-config/ (Rule 2), but context is app-source/

# Build artifacts
**/bin/
**/obj/
**/publish/
**/out/

# IDE files
**/.vs/
**/.vscode/
**/.idea/
**/*.user
**/*.suo

# Git
.git/
.gitignore

# Test results and coverage
**/test-results/
**/coverage-results/
**/TestResults/

# Documentation
*.md
LICENSE

# Security configs (not needed in image)
.gitleaks.toml
.pre-commit-config.yaml

# OS files
**/.DS_Store
**/Thumbs.db
```

Why each exclusion matters:

| Excluded | Why | Impact if Missing |
|----------|-----|-------------------|
| `**/bin/`, `**/obj/` | Local build artifacts conflict with container build | Build errors, stale binaries |
| `.git/` | Git history can be 100+ MB | Build context transfer takes minutes |
| `**/TestResults/` | Coverage reports from local runs | Wastes build context space |
| `*.md`, `LICENSE` | Documentation has no runtime purpose | Minor, but keep it clean |
| `.gitleaks.toml` | Security scan config is not needed inside the image | Minor, but information leakage |

### Verify

You can see the effect of `.dockerignore` by observing build context transfer size. Podman prints the context size at the start of a build:

```bash
cd app-source

# Build WITHOUT .dockerignore (temporarily rename it)
mv .dockerignore .dockerignore.bak
podman build --no-cache -f ../build-config/Dockerfile -t test-no-ignore . 2>&1 | head -5
# Look for the "STEP 1" line — the context transfer will be larger

# Restore .dockerignore and build again
mv .dockerignore.bak .dockerignore
podman build --no-cache -f ../build-config/Dockerfile -t test-with-ignore . 2>&1 | head -5
# The context transfer will be smaller because .git/, bin/, obj/ are excluded

# Clean up test images
podman rmi test-no-ignore test-with-ignore 2>/dev/null
```

---

## What Just Happened?

Let us trace the complete flow from source code to running pod:

```
1. Developer pushes code to app-source repo (GitLab)
                    |
2. Jenkins pipeline starts (triggered by webhook)
                    |
3. Jenkins agent pod spins up (custom image with Podman, .NET SDK, tools)
                    |
4. checkoutSource() clones app-source      --> workspace root: ./
   checkoutBuildConfig() clones build-config --> workspace: ./build-config/
                    |
5. buildDotnet() runs dotnet build + test
                    |
6. buildContainerImage() runs:
   podman build --storage-driver=vfs --isolation=chroot \
       -f build-config/Dockerfile \     <-- Dockerfile from build-config repo
       --build-arg PROJECT_NAME=SampleApi \  <-- parameterized per service
       --build-arg APP_PORT=8080 \
       -t image-registry...svc:5000/sampleapi-dev/sampleapi:main-abc1234 \
       .                                <-- build context is app-source root
                    |                       .dockerignore filters the context
7. pushToRegistry() authenticates with SA token, pushes to internal registry
                    |
8. updateGitOps() updates the image tag in app-gitops overlay
                    |
9. ArgoCD detects the change, pulls the image, deploys the pod
                    |
10. Pod runs as arbitrary UID, non-root, on port 8080, with health checks
```

Every piece has a reason. The Dockerfile is separate from the app (separation of concerns). Podman uses VFS and chroot (OpenShift constraints). The image runs as non-root (security enforcement). The `.dockerignore` keeps the build fast (operational efficiency).

---

## Common Mistakes

### Mistake 1: Trying Docker-in-Docker

**Symptom:** Pipeline fails with `Cannot connect to the Docker daemon` or pod fails to start due to SCC violations.

**Why:** There is no Docker daemon on OpenShift. Mounting `/var/run/docker.sock` is blocked by the restricted SCC.

**Fix:** Use Podman with `--storage-driver=vfs --isolation=chroot`. Replace every `docker` command with `podman` -- the CLI is compatible.

---

### Mistake 2: Forgetting `--storage-driver=vfs`

**Symptom:** `Error: kernel does not support overlay fs: unknown` or `error mounting ... permission denied`.

**Why:** The default overlay driver requires either a FUSE device or specific kernel capabilities. Neither is available in OpenShift pods under the restricted SCC.

**Fix:** Add `--storage-driver=vfs` to *every* Podman command (build, push, images, tag, login). It is not enough to set it in the config file alone -- use both for reliability.

---

### Mistake 3: Running as Root in the Container

**Symptom:** Pod starts but ACS blocks it with policy violation `Container runs as root`. Or the pod crashes because files are owned by root and the arbitrary UID cannot read them.

**Why:** OpenShift assigns an arbitrary UID from the namespace range. If your Dockerfile expects root (UID 0), the application may fail when it cannot write to directories owned by root.

**Fix:** Always include `USER 1001` (or any non-zero UID) in your Dockerfile. Ensure your application directory is readable by group 0:

```dockerfile
# Make app directory accessible to arbitrary UIDs
RUN chown -R 1001:0 /app && chmod -R g=u /app
USER 1001
```

---

### Mistake 4: Missing `.dockerignore`

**Symptom:** Builds are slow. The "Sending build context" step takes 30+ seconds. The image contains unexpected files.

**Why:** Without `.dockerignore`, Podman sends everything in the build context directory to the build process. The `.git/` directory alone can add hundreds of megabytes.

**Fix:** Create `.dockerignore` in the build context root (where you run `podman build`). Exclude `**/bin/`, `**/obj/`, `.git/`, IDE files, and documentation.

---

### Mistake 5: Using Port 80 or 443

**Symptom:** Application crashes at startup with `System.Net.Sockets.SocketException: Permission denied`.

**Why:** Ports below 1024 require `CAP_NET_BIND_SERVICE`, which the restricted SCC does not grant.

**Fix:** Use port 8080 (or any port above 1024). Set `ASPNETCORE_URLS=http://+:8080` in the Dockerfile or as a Kubernetes environment variable.

---

### Mistake 6: Putting the Dockerfile in the App Repo

**Symptom:** Works fine technically, but violates the project's separation-of-concerns architecture.

**Why:** Developers should not need to understand or modify build infrastructure. The platform team owns the Dockerfile, the scan configs, and the pipeline logic. Mixing them in one repo means a developer editing a controller could accidentally break the container build.

**Fix:** Keep the Dockerfile in the `build-config` repo. Use `podman build -f build-config/Dockerfile .` to point to it from a different directory than the build context.

---

## Challenge: Optimize Image Size

Your task: reduce the production image size further. Here are three approaches to investigate:

1. **Use a `-alpine` or `-chiseled` base image.** Microsoft publishes `mcr.microsoft.com/dotnet/aspnet:8.0-alpine` (based on Alpine Linux, ~100 MB) and `mcr.microsoft.com/dotnet/aspnet:8.0-jammy-chiseled` (distroless, ~110 MB). What trade-offs does each introduce? (Hint: Alpine uses musl libc, not glibc. Chiseled images have no shell.)

2. **Enable PublishTrimmed.** Add `<PublishTrimmed>true</PublishTrimmed>` to the `.csproj` or the `dotnet publish` command. This removes unused framework assemblies. What risks does trimming introduce for reflection-heavy code?

3. **Enable ReadyToRun compilation.** Add `<PublishReadyToRun>true</PublishReadyToRun>` to pre-compile IL to native code. This increases image size slightly but reduces cold-start time. When is this trade-off worth it?

Try each approach, compare sizes with `podman images`, and document which you would recommend for production.

---

## Self-Assessment

Answer these questions without looking back at the module. If you cannot answer confidently, re-read the relevant section.

1. Why does OpenShift ban Docker-in-Docker? Name two specific security risks.

2. What two Podman flags are required on OpenShift, and what does each one solve?

3. This project's Dockerfile uses `COPY . .` to copy all source files at once. An alternative pattern copies `.csproj` files first, runs `dotnet restore`, then copies the rest. What would be the benefit of that two-step approach, and why might this project have chosen the simpler single-copy approach instead?

4. Where does the `.dockerignore` file need to live -- next to the Dockerfile, or in the build context root? Why does this matter when the Dockerfile is in a different repository?

5. How does Podman authenticate to the OpenShift internal registry from inside a Jenkins agent pod?

6. Why does the Dockerfile specify `USER 1001` if OpenShift is going to override it with an arbitrary UID anyway?

7. What is the full image reference format for the OCP internal registry? Write it out for an image called `sampleapi` with tag `v1.2.0` in namespace `sampleapi-dev`.

### Answers

1. DinD requires (a) access to the Docker socket, which grants root-level host access, and (b) a privileged container, which disables all kernel security features (SELinux, seccomp, capabilities). Both are attack vectors for container escape.

2. `--storage-driver=vfs` (the overlay driver requires a FUSE device, unavailable in OCP pods) and `--isolation=chroot` (the default isolation mode requires Linux capabilities not granted by OpenShift's restricted SCC).

3. The two-step approach enables layer caching: NuGet restore runs after the `.csproj` copy, and since dependencies change rarely but source code changes on every commit, the restore layer stays cached across builds (saving 30-60 seconds). This project uses the simpler `COPY . .` approach because CI pipeline builds typically run with `--no-cache` or on ephemeral agents where layer caches do not persist between builds, making the optimization less impactful.

4. In the build context root (where `.` points to in `podman build -f ... .`). In this project, that is the `app-source` directory. Even though the Dockerfile is in `build-config`, Podman reads `.dockerignore` from the context root, not from the Dockerfile's directory.

5. It reads the ServiceAccount token from `/var/run/secrets/kubernetes.io/serviceaccount/token` and passes it as the password to `podman login` with username `serviceaccount`.

6. To signal intent and pass ACS policy checks. ACS checks the Dockerfile's `USER` instruction at build time. If there is no `USER` instruction or it says `USER root`, ACS blocks the image. The actual runtime UID is different, but the Dockerfile must declare non-root intent.

7. `image-registry.openshift-image-registry.svc:5000/sampleapi-dev/sampleapi:v1.2.0`

---

## Next Module Preview

**Module 2B: GitLab & Registry Setup** -- You can build images, but where does the source code live? And where do the images go? Module 2B covers deploying GitLab CE on OpenShift with external PostgreSQL and Redis, creating the five repositories that follow the separation-of-concerns pattern (one per service + shared build/GitOps/pipeline repos), generating an API token for Jenkins, and configuring the OCP internal registry so all environments can pull your images.

After that, **Module 3: Jenkins on OpenShift** will wire up pipelines to build and validate code automatically on git events (merge requests, pushes to main, and tags).

---

*Module 2 complete. Estimated time: 60 minutes.*
