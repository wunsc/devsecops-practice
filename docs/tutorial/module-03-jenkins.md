# Module 3: Jenkins on OpenShift

| Duration | Track | Prerequisites |
|----------|-------|---------------|
| ~75 minutes | Foundation | Module 2B complete, `devsecops-tools` namespace exists, GitLab running with repos created |

---

## What You'll Learn

By the end of this module you will be able to:

1. Deploy a Jenkins controller on OpenShift with persistent storage.
2. Build a custom agent image packed with DevSecOps tools (dotnet, Podman, roxctl, dotnet-sonarscanner, dependency-check, k6, and more).
3. Configure Jenkins entirely through code using JCasC (Jenkins Configuration as Code).
4. Explain why ephemeral agent pods beat persistent agents for CI/CD on Kubernetes.
5. Design a shared library where every pipeline function lives in its own file, accepts a `Map config`, and returns a structured result.
6. Wire an inline CPS job that calls a shared library orchestrator -- no Jenkinsfile in the application repo.

---

## Prerequisites

Before starting this module, confirm:

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

```bash
# You are logged in as cluster-admin
$OC whoami
# Expected: admin (or a user with cluster-admin)

# The tools namespace exists
$OC get ns $NS_TOOLS
# Expected: Active

# GitLab is running and the 5 repos exist
curl -sk ${GITLAB_URL}/-/readiness | jq .status
# Expected: "ok"
```

You also need the `oc` CLI and access to the OpenShift web console.

---

## Part 1: Concepts -- Why Jenkins on Kubernetes Is Different

If you have used Jenkins before, you probably ran it on a VM with a handful of permanent agents. That model does not survive contact with Kubernetes. Here is why, and what replaces it.

### The Controller-Agent Split

Jenkins on OpenShift separates into two concerns:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        OpenShift Cluster                               │
│                                                                        │
│  ┌──────────────────────────────────────┐                              │
│  │   devsecops-tools namespace          │                              │
│  │                                      │                              │
│  │  ┌─────────────────────────┐         │                              │
│  │  │  Jenkins Controller     │         │                              │
│  │  │  (Deployment, 1 pod)    │         │  numExecutors: 0             │
│  │  │                         │         │  <-- runs NO builds itself   │
│  │  │  - Serves UI            │         │                              │
│  │  │  - Stores config (PVC)  │         │                              │
│  │  │  - Schedules builds     │         │                              │
│  │  │  - Receives webhooks    │         │                              │
│  │  └──────────┬──────────────┘         │                              │
│  │             │  Kubernetes Plugin      │                              │
│  │             │  creates pods on demand │                              │
│  │             v                         │                              │
│  │  ┌─────────────────────────┐         │                              │
│  │  │  Agent Pod (ephemeral)  │  Build starts --> pod created          │
│  │  │  ┌───────────────────┐  │  Build ends   --> pod destroyed        │
│  │  │  │  jnlp container   │  │                                        │
│  │  │  │  - dotnet SDK 8   │  │                                        │
│  │  │  │  - Podman          │  │                                        │
│  │  │  │  - roxctl          │  │                                        │
│  │  │  │  - dotnet-sonarscanner│  │                                        │
│  │  │  │  - dependency-check│  │                                        │
│  │  │  │  - gitleaks        │  │                                        │
│  │  │  │  - cosign          │  │                                        │
│  │  │  │  - kustomize       │  │                                        │
│  │  │  │  - oc / kubectl    │  │                                        │
│  │  │  │  - argocd          │  │                                        │
│  │  │  │  - k6              │  │                                        │
│  │  │  └───────────────────┘  │                                        │
│  │  │  ┌───────────────────┐  │  <-- T3 only: sidecar for DAST        │
│  │  │  │  zap (sidecar)    │  │                                        │
│  │  │  └───────────────────┘  │                                        │
│  │  └─────────────────────────┘         │                              │
│  └──────────────────────────────────────┘                              │
└────────────────────────────────────────────────────────────────────────┘
```

The controller's `numExecutors` is set to **zero**. It never runs builds itself. When a job triggers, the Kubernetes plugin spins up an agent pod, the build runs inside it, and the pod is deleted. Every build starts from a clean slate.

### Why Ephemeral Agents Matter

Three reasons, all practical:

1. **Clean builds.** No leftover state from a previous pipeline corrupting the next one. No "works on my agent" debugging sessions.
2. **Elastic scaling.** Ten concurrent MR pipelines? Ten pods. No pipelines queuing? Zero pods. You pay for compute only when builds are running.
3. **Reproducible tooling.** The agent image is a Dockerfile you version-control. Upgrading a tool means rebuilding the image, not SSH-ing into an agent VM.

### The Shared Library Pattern

In this project, the application repository (`app-source`) contains zero CI/CD files. No `Jenkinsfile`. No `.jenkins`. Nothing. All pipeline logic lives in a separate Git repository called `jenkins-shared-lib`.

```
┌────────────────────────────────────────────────────────────────────────┐
│                     Jenkins Shared Library                              │
│                                                                        │
│  vars/                              src/com/devsecops/                 │
│  ├── checkoutSource.groovy          ├── PipelineConfig.groovy          │
│  ├── checkoutBuildConfig.groovy     ├── SecurityGate.groovy            │
│  ├── buildDotnet.groovy             └── ImageTagger.groovy             │
│  ├── runUnitTests.groovy                                               │
│  ├── scanSonarQube.groovy           resources/com/devsecops/           │
│  ├── scanDependencyCheck.groovy     └── notification-template.html     │
│  ├── buildContainerImage.groovy                                        │
│  ├── scanACSImage.groovy                                               │
│  ├── scanOWASPZAP.groovy                                               │
│  ├── pushToRegistry.groovy                                             │
│  ├── signImage.groovy                                                  │
│  ├── updateGitOps.groovy                                               │
│  ├── deployToEnvironment.groovy                                        │
│  ├── reportToGitLab.groovy                                             │
│  ├── commentOnMR.groovy           <-- posts scan summary on GitLab MR  │
│  ├── createPromotionMR.groovy     <-- creates promotion MR in app-gitops│
│  ├── runPerformanceTest.groovy    <-- k6 load test execution (T3)      │
│  ├── notifyTeam.groovy                                                 │
│  ├── pipelineMR.groovy      <-- T1 orchestrator (accepts service param) │
│  ├── pipelineMerge.groovy   <-- T2 orchestrator (accepts service param) │
│  ├── pipelineTag.groovy     <-- T3 orchestrator (accepts service param) │
│  └── pipelinePromote.groovy <-- T4 orchestrator (shared, detects svc)  │
└────────────────────────────────────────────────────────────────────────┘

Each file in vars/ follows the same contract:

    def call(Map config = [:]) {
        // 1. Merge defaults with provided config
        // 2. Wrap secrets in withCredentials{}
        // 3. Do work in try/catch
        // 4. Return structured result: [status: 'SUCCESS', duration: ..., ...]
    }
```

The Jenkins job definition is a one-liner:

```groovy
@Library('devsecops-shared-lib@main') _
pipelineMerge()
```

That single line loads the shared library and calls the T2 orchestrator, which internally calls `checkoutSource()`, `buildDotnet()`, `scanSonarQube()`, and every other step in sequence. The application developers never touch pipeline code.

### Why This Separation?

Think about what happens when you embed a `Jenkinsfile` in the application repo:

- Every developer can modify pipeline logic alongside code changes.
- Pipeline behavior drifts across feature branches (one branch has the old `Jenkinsfile`, another has the new one).
- Security scanning steps can be deleted by anyone with push access to the app repo.

By moving all pipeline logic to a shared library in its own repo with controlled access, you get consistent, auditable, and tamper-resistant pipelines.

---

## Part 2: Deploy the Jenkins Controller

### Step 1: Understand the Deployment Manifest

The Jenkins controller is a standard Kubernetes `Deployment` with three notable design choices. Let us walk through each one before applying anything.

**Init containers fix permissions and install plugins.** Two init containers run before the controller starts. The `fix-permissions` container (running as root on a UBI-minimal image) fixes PVC ownership to UID 1000. Then `install-plugins` uses the official `jenkins-plugin-cli` to pre-install every plugin the controller needs -- `kubernetes`, `configuration-as-code`, `gitlab-plugin`, `job-dsl`, and others. Plugins are written to a shared `emptyDir` volume so the controller can load them immediately.

**JCasC replaces the setup wizard.** The environment variable `JAVA_OPTS` includes `-Djenkins.install.runSetupWizard=false`. Instead of clicking through the wizard, all configuration -- security realm, authorization, cloud definitions, job definitions -- comes from a YAML ConfigMap mounted at `/var/jenkins_home/casc_configs`. Change the ConfigMap, restart the pod, and Jenkins reconfigures itself.

**The controller runs zero executors.** The line `numExecutors: 0` in JCasC means the controller pod never runs builds. All build work is delegated to ephemeral agent pods. This keeps the controller lightweight and stable.

Here is the core of the deployment (abbreviated for clarity -- the full file includes probes, resource limits, and the PVC):

```yaml
# infra/phase7/jenkins-deployment.yaml (key sections)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: devsecops-tools
spec:
  replicas: 1
  strategy:
    type: Recreate         # <-- PVC can only attach to one pod
  template:
    spec:
      serviceAccountName: jenkins-sa
      initContainers:
        - name: fix-permissions           # <-- fixes PVC ownership for UID 1000
          image: registry.access.redhat.com/ubi9/ubi-minimal:latest
          command: ["sh", "-c", "chown -R 1000:0 /var/jenkins_home && chmod -R 775 /var/jenkins_home"]
          securityContext:
            runAsUser: 0
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
        - name: install-plugins
          image: jenkins/jenkins:lts-jdk21
          command:
            - jenkins-plugin-cli
            - --plugins
            - configuration-as-code
            - job-dsl
            - workflow-aggregator
            - pipeline-stage-view
            - pipeline-utility-steps
            - kubernetes
            - kubernetes-credentials
            - git
            - git-client
            - gitlab-plugin
            - credentials
            - credentials-binding
            - plain-credentials
            - matrix-auth
            - antisamy-markup-formatter
            - blueocean
            - warnings-ng
            - junit
            - timestamper
            - ws-cleanup
            - ansicolor
          volumeMounts:
            - name: jenkins-plugins
              mountPath: /usr/share/jenkins/ref/plugins
      containers:
        - name: jenkins
          image: jenkins/jenkins:lts-jdk21
          env:
            - name: CASC_JENKINS_CONFIG
              value: /var/jenkins_home/casc_configs  # <-- JCasC reads from here
            - name: JAVA_OPTS
              value: >-
                -Djenkins.install.runSetupWizard=false
                -Dhudson.security.csrf.DefaultCrumbIssuer.EXCLUDE_SESSION_ID=true
                -Xmx1g -Xms512m
          ports:
            - containerPort: 8080    # UI + webhook receiver
            - containerPort: 50000   # Agent JNLP connections
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
            - name: jenkins-casc
              mountPath: /var/jenkins_home/casc_configs
              readOnly: true
            - name: jenkins-plugins
              mountPath: /usr/share/jenkins/ref/plugins
      volumes:
        - name: jenkins-home
          persistentVolumeClaim:
            claimName: jenkins-home          # 20Gi gp3-csi PVC
        - name: jenkins-casc
          configMap:
            name: jenkins-casc-config        # The JCasC ConfigMap
        - name: jenkins-plugins
          emptyDir: {}                       # Plugins installed by init container
```

### Step 2: Apply the Deployment

```bash
# Apply the SA ClusterRoleBindings first -- Jenkins needs these to create agent pods
$OC apply -f infra/phase7/jenkins-sa-clusterrolebinding.yaml

# Apply the JCasC ConfigMap -- the controller reads it at startup
$OC apply -f infra/phase7/jenkins-casc-configmap.yaml

# Apply the credential secrets -- referenced by Jenkins Script Console after boot
$OC apply -f infra/phase7/jenkins-credentials-secrets.yaml

# Apply the deployment (includes PVC, Service, Route)
$OC apply -f infra/phase7/jenkins-deployment.yaml
```

The `fix-permissions` init container runs first (fixes PVC ownership), then `install-plugins` downloads all plugins (1-2 minutes). The controller itself takes another minute to boot. Wait for it:

```bash
$OC wait --for=condition=ready pod -l app=jenkins -n $NS_TOOLS --timeout=300s
```

### Verify: Jenkins Is Running

```bash
# Pod status
$OC get pods -n $NS_TOOLS -l app=jenkins
# Expected output:
#   NAME                       READY   STATUS    RESTARTS   AGE
#   jenkins-7b58fcc7c6-g9c9k   1/1     Running   5          3d

# Route exists
$OC get route jenkins -n $NS_TOOLS -o jsonpath='{.spec.host}'
# Expected: jenkins-devsecops-tools.${APPS_DOMAIN}

# HTTP check
curl -sk -o /dev/null -w "%{http_code}" https://$($OC get route jenkins -n $NS_TOOLS -o jsonpath='{.spec.host}')/login
# Expected output:
#   200
```

Open the Jenkins URL in your browser. Log in with the credentials defined in the JCasC ConfigMap (default: `admin` / `DevSec0ps-Jenkins-2024`).

You should see an empty dashboard with zero jobs. That is correct -- we have not applied the job definitions yet.

> **Checkpoint:** Jenkins is running, the UI is accessible, and you can log in. If you get a 503, check `$OC logs -f deployment/jenkins -n $NS_TOOLS` for startup errors. The most common issue is the plugin init container failing to download plugins due to network restrictions.

---

## Part 3: Build the Custom Agent Image

### Why a Custom Agent?

The default Jenkins agent image knows how to connect to a controller and run shell commands. That is all. Our pipelines need `dotnet`, `podman`, `roxctl`, `dotnet-sonarscanner`, `dependency-check`, `gitleaks`, `cosign`, `kustomize`, `oc`, `argocd`, `k6`, and `java-17-openjdk-headless` (for running agent.jar). We bake all of these into a single custom image.

### Step 3: Walk Through the Dockerfile

The agent Dockerfile (`infra/phase7/Dockerfile.agent`) starts from Red Hat UBI9 and layers tools on top. Here are the critical sections and WHY each decision was made:

**Base image -- UBI9, not Alpine:**
```dockerfile
FROM registry.access.redhat.com/ubi9/ubi:latest
```
UBI9 (Universal Base Image) is the recommended and fully supported base image for OpenShift workloads. Alpine uses musl libc, which breaks .NET SDK binaries and many security tools. Use UBI.

**Podman storage driver -- vfs, not overlay:**
```dockerfile
RUN mkdir -p /home/jenkins/.config/containers && \
    echo '[storage]' > /home/jenkins/.config/containers/storage.conf && \
    echo 'driver = "vfs"' >> /home/jenkins/.config/containers/storage.conf && \
    echo '' >> /home/jenkins/.config/containers/storage.conf && \
    echo '[storage.options.vfs]' >> /home/jenkins/.config/containers/storage.conf
```
OpenShift does not expose a FUSE device to containers. Podman's default `overlay` driver requires FUSE. The `vfs` driver copies layers instead of using overlays -- slower, but it works everywhere. You must also pass `--storage-driver=vfs` and `--isolation=chroot` when invoking Podman in pipeline steps.

**.NET SDK symlink workaround:**
```dockerfile
RUN rpm -Uvh https://packages.microsoft.com/config/rhel/9/packages-microsoft-prod.rpm && \
    dnf install -y dotnet-sdk-8.0 && \
    ln -s /usr/share/dotnet/sdk /usr/lib64/dotnet/sdk && \
    ln -s /usr/share/dotnet/sdk-manifests /usr/lib64/dotnet/sdk-manifests && \
    ln -s /usr/share/dotnet/templates /usr/lib64/dotnet/templates
```
Microsoft installs the SDK to `/usr/share/dotnet/sdk/` but the RHEL `dotnet-host` package looks in `/usr/lib64/dotnet/sdk/`. Without the symlink, `dotnet --version` works but `dotnet build` fails with "SDK not found."

**The ENTRYPOINT -- this is where most people get stuck:**
```dockerfile
RUN printf '#!/bin/bash\n\
if [ -n "$JENKINS_URL" ] && [ -n "$JENKINS_SECRET" ] && [ -n "$JENKINS_AGENT_NAME" ]; then\n\
  curl -sL "${JENKINS_URL}jnlpJars/agent.jar" -o /usr/share/jenkins/agent.jar\n\
  exec java -jar /usr/share/jenkins/agent.jar \\\n\
    -url "$JENKINS_URL" \\\n\
    -secret "$JENKINS_SECRET" \\\n\
    -name "$JENKINS_AGENT_NAME" \\\n\
    -tunnel "$JENKINS_TUNNEL" \\\n\
    -workDir /home/jenkins/agent\n\
else\n\
  exec /bin/bash "$@"\n\
fi\n' > /usr/local/bin/jenkins-agent && \
    chmod +x /usr/local/bin/jenkins-agent

ENTRYPOINT ["/usr/local/bin/jenkins-agent"]
```

This script does three things:
1. Downloads `agent.jar` from the Jenkins controller at runtime. This ensures the agent JAR version always matches the controller version.
2. Starts the JNLP remoting connection using the environment variables that the Kubernetes plugin injects (`JENKINS_URL`, `JENKINS_SECRET`, `JENKINS_AGENT_NAME`, `JENKINS_TUNNEL`).
3. Falls back to bash if no Jenkins environment is detected (useful for local testing with `podman run`).

> **WARNING: ENTRYPOINT pitfall.** If your image ENTRYPOINT is `/bin/bash` or missing, the agent pod will start but never connect to the controller. The pod log will show a bash prompt and nothing else. The Kubernetes plugin expects the container to establish a JNLP connection on its own via the ENTRYPOINT.

### Step 4: Build the Agent Image on OpenShift

We use an OpenShift `BuildConfig` with `Docker` strategy. The BuildConfig points to `Dockerfile.agent` via the `dockerfilePath` field:

```bash
# Apply the BuildConfig and ImageStream
$OC apply -f infra/phase7/agent-buildconfig.yaml

# Trigger the build -- sends the local directory as build context
$OC start-build jenkins-agent-devsecops \
  --from-dir=infra/phase7/ \
  -n $NS_TOOLS \
  --follow
```

This takes 5-10 minutes. The build downloads and installs every tool into the image. Watch the output for errors -- the most common failure is a GitHub rate limit on the `gitleaks` or `cosign` binary download.

### Verify: Agent Image Is Built

```bash
# Check the ImageStream
$OC get is jenkins-agent-devsecops -n $NS_TOOLS
# Expected: image-registry.../devsecops-tools/jenkins-agent-devsecops  latest  <sha>

# Check that the build succeeded
$OC get builds -n $NS_TOOLS -l buildconfig=jenkins-agent-devsecops
# Expected: jenkins-agent-devsecops-1  Complete
```

> **Checkpoint:** The agent image is built and stored in the internal registry. It has not been used yet -- that happens when a pipeline triggers.

---

## Part 4: Configure JCasC

JCasC (Jenkins Configuration as Code) replaces every click you would normally do in the Jenkins UI with a YAML file. When Jenkins starts, it reads the YAML and configures itself. When you change the YAML and restart, it reconfigures.

### Step 5: Understand the JCasC Structure

The JCasC ConfigMap (`infra/phase7/jenkins-casc-configmap.yaml`) has two documents: `casc.yaml` for global configuration and `jobs.yaml` for job definitions. Let us examine each section.

**Security realm and authorization:**
```yaml
jenkins:
  securityRealm:
    local:
      allowsSignup: false
      users:
        - id: "admin"
          password: "${JENKINS_ADMIN_PASSWORD:-DevSec0ps-Jenkins-2024}"
  authorizationStrategy:
    globalMatrix:
      permissions:
        - "Overall/Administer:admin"
        - "Overall/Read:anonymous"    # <-- GitLab webhooks need this
        - "Job/Read:anonymous"        # <-- webhooks hit /project/<job>
        - "Job/Build:anonymous"       # <-- webhooks trigger builds
```

Anonymous read and build permissions look alarming, but they are required. GitLab webhooks POST to `/project/sampleapi-merge` without authentication. If anonymous users cannot read jobs or trigger builds, every webhook returns 403.

**Kubernetes cloud -- the ephemeral agent definition:**
```yaml
clouds:
  - kubernetes:
      name: "openshift"
      serverUrl: "https://kubernetes.default.svc"
      namespace: "devsecops-tools"
      jenkinsUrl: "http://jenkins.devsecops-tools.svc:8080"
      jenkinsTunnel: "jenkins.devsecops-tools.svc:50000"
      templates:
        - name: "devsecops-agent"
          label: "devsecops-agent"
          serviceAccount: "jenkins-sa"
          idleMinutes: 10
          containers:
            - name: "jnlp"
              image: "image-registry.openshift-image-registry.svc:5000/devsecops-tools/jenkins-agent-devsecops:latest"
              workingDir: "/home/jenkins/agent"
              ttyEnabled: true
              alwaysPullImage: true    # <-- critical, explained below
              resourceRequestCpu: "500m"
              resourceRequestMemory: "1Gi"
              resourceLimitCpu: "2"
              resourceLimitMemory: "4Gi"
              privileged: true         # <-- required for Podman builds
          envVars:
            - envVar:
                key: "STORAGE_DRIVER"
                value: "vfs"           # <-- Podman storage driver
            - envVar:
                key: "DOTNET_CLI_TELEMETRY_OPTOUT"
                value: "1"
            - envVar:
                key: "DOTNET_NOLOGO"
                value: "true"
          volumes:
            - emptyDirVolume:
                mountPath: "/tmp"
                memory: false
            - emptyDirVolume:
                mountPath: "/var/lib/containers"   # <-- Podman storage
                memory: false
          yaml: |
            spec:
              securityContext:
                runAsUser: 0           # <-- required for Podman
```

Two settings deserve special attention:

- **`alwaysPullImage: true`** -- Without this, OpenShift worker nodes cache the `:latest` tag. If you rebuild the agent image to add a tool, existing nodes will keep using the old cached image. `alwaysPullImage` forces a fresh pull every time an agent pod starts.

- **`runAsUser: 0`** -- Podman on OpenShift needs to run as root to manage container storage with the `vfs` driver. This is set in the `yaml` override block because the Kubernetes plugin's `privileged: true` alone is not sufficient.

> **WARNING: Do not set `command: ""` or `args: ""` on the jnlp container.** Empty strings override the image ENTRYPOINT and prevent the agent from connecting. The Kubernetes plugin needs the ENTRYPOINT to run unmodified. This is one of the most common and frustrating debugging sessions you will encounter.

**GitLab connection (for MR status reporting):**
```yaml
unclassified:
  gitLabConnectionConfig:
    connections:
      - name: "gitlab"
        url: "https://gitlab-devsecops-gitlab.${APPS_DOMAIN}"
        apiTokenId: "gitlab-api-token"       # <-- credential ID for GitLab API token
        clientBuilderId: "autodetect"
        ignoreCertificateErrors: true
```

The `gitLabConnection` config is what makes `updateGitlabCommitStatus` and `gitLabConnection('gitlab')` in pipeline options work. The `apiTokenId` points to a Jenkins credential containing a GitLab personal access token.

**Shared library registration:**
```yaml
  globalLibraries:
    libraries:
      - name: "devsecops-shared-lib"
        defaultVersion: "main"
        implicit: false
        retriever:
          modernSCM:
            scm:
              git:
                remote: "https://gitlab-devsecops-gitlab.${APPS_DOMAIN}/devsecops/jenkins-shared-lib.git"
                credentialsId: "gitlab-token"
```

This tells Jenkins where to find the shared library. When a pipeline declares `@Library('devsecops-shared-lib@main') _`, Jenkins clones this repo and makes all `vars/` functions available.

The JCasC also sets the Jenkins location URL (used for webhook callback URLs) and admin email:
```yaml
      location:
        url: "https://jenkins-devsecops-tools.${APPS_DOMAIN}/"
        adminAddress: "devsecops@example.com"
```

**Job definitions -- inline CPS, no Jenkinsfile:**

```yaml
jobs:
  - script: |
      pipelineJob('sampleapi-mr') {
        description('T1: MR Validation')
        properties {
          gitLabConnection { gitLabConnection('gitlab') }
        }
        triggers {
          gitlabPush {
            buildOnMergeRequestEvents(true)
            buildOnPushEvents(false)
          }
        }
        definition {
          cps {
            script("""\
              @Library('devsecops-shared-lib@main') _
              pipelineMR(service: 'sampleapi')
            """.stripIndent())
            sandbox(true)
          }
        }
      }
```

Each job is defined using the Job DSL plugin. The pipeline script is inline -- two lines that load the shared library and call the orchestrator function with the service name. The `"""\...""".stripIndent()` syntax ensures clean indentation. No Jenkinsfile exists anywhere in the application repo. This is the mechanism that keeps `app-source` clean of CI/CD artifacts.

Note that the tag jobs (`sampleapi-tag`, `notificationapi-tag`) cannot use `gitlabPush { buildOnTagPushEvents(true) }` because the GitLab plugin has no such method. Instead, they use a `configure` block to set `triggerOnPush(true)` with a `branchFilterType('RegexBasedFilter')` and `sourceBranchRegex('refs/tags/.*')`. The pipeline itself validates that the tag matches semantic versioning.

There are seven jobs defined in total -- three per service plus one shared promote job. Each service has its own GitLab repo and webhooks. The shared library orchestrators accept a `service` parameter:

| Job Name | Trigger | Shared Lib Call | Purpose |
|----------|---------|-----------------|---------|
| `sampleapi-mr` | MR webhook on app-source (ID 1) | `pipelineMR(service: 'sampleapi')` | Validate SampleApi feature branches |
| `sampleapi-merge` | Push to `main` on app-source (ID 1) | `pipelineMerge(service: 'sampleapi')` | Build SampleApi image, deploy to DEV |
| `sampleapi-tag` | Tag push on app-source (ID 1) | `pipelineTag(service: 'sampleapi')` | SampleApi release image, DAST + perf test |
| `notificationapi-mr` | MR webhook on notificationapi-source (ID 5) | `pipelineMR(service: 'notificationapi')` | Validate NotificationApi branches |
| `notificationapi-merge` | Push to `main` on notificationapi-source (ID 5) | `pipelineMerge(service: 'notificationapi')` | Build NotificationApi image, deploy to DEV |
| `notificationapi-tag` | Tag push on notificationapi-source (ID 5) | `pipelineTag(service: 'notificationapi')` | NotificationApi release image |
| `sampleapi-promote` | Push to `main` on app-gitops (ID 4) | `pipelinePromote()` | Shared: detects which service+env changed, syncs ArgoCD |

All service jobs pass the `service:` parameter explicitly -- SampleApi jobs pass `service: 'sampleapi'` and NotificationApi jobs pass `service: 'notificationapi'`. The `PipelineConfig.configureForService()` method sets the active source repo, image name, build args, SonarQube project, and GitLab project ID based on the service name.

### Step 6: Apply JCasC and Credentials

The JCasC ConfigMap was already applied in Step 2. If you need to update it:

```bash
# Edit and reapply
$OC apply -f infra/phase7/jenkins-casc-configmap.yaml

# Restart Jenkins to pick up changes
$OC rollout restart deployment/jenkins -n $NS_TOOLS
$OC wait --for=condition=ready pod -l app=jenkins -n $NS_TOOLS --timeout=300s
```

Credentials (GitLab token, SonarQube token, ACS token) must be created as OCP Secrets and then registered in Jenkins. The safest approach is via the Jenkins Script Console after deployment, not via JCasC.

> **WARNING: Never define credentials in JCasC where the credential ID matches a filename in `casc_secrets/`.** This causes circular resolution on reload, resulting in empty credential values. Always manage credentials through the Script Console or the Jenkins UI.

### Verify: Jobs Are Created

```bash
# Check Jenkins API for jobs (use admin credentials)
JENKINS_HOST=$($OC get route jenkins -n $NS_TOOLS -o jsonpath='{.spec.host}')
curl -sk -u admin:DevSec0ps-Jenkins-2024 \
  "https://${JENKINS_HOST}/api/json?tree=jobs[name]" | jq '.jobs[].name'
# Expected (7 jobs -- 3 per service + 1 shared promote):
#   "sampleapi-mr"
#   "sampleapi-merge"
#   "sampleapi-tag"
#   "notificationapi-mr"
#   "notificationapi-merge"
#   "notificationapi-tag"
#   "sampleapi-promote"
```

If you see seven job names, JCasC and Job DSL are working correctly.

Here is what the Jenkins dashboard looks like with all seven jobs created by JCasC:

![Jenkins dashboard showing 7 pipeline jobs managed by JCasC](../screenshot/jenkins-dashboard-7-jobs.png)

---

## Part 5: The Shared Library in Detail

Now that Jenkins is deployed and configured, let us look at how the shared library is structured and why each design decision matters.

### The Function Contract

Every file in `vars/` follows the same pattern. Here is `buildDotnet.groovy` as a concrete example:

```groovy
// jenkins-shared-lib/vars/buildDotnet.groovy
// Runs dotnet restore + build + publish for the .NET application
def call(Map config = [:]) {
    def startTime = System.currentTimeMillis()
    def project = config.project ?: '.'                // <-- default from config map
    def configuration = config.configuration ?: 'Release'
    def outputDir = config.outputDir ?: 'publish'

    echo "=== Build .NET Application ==="
    echo "  Project: ${project}"
    echo "  Configuration: ${configuration}"

    try {
        sh "dotnet restore ${project} --no-cache"
        sh "dotnet build ${project} --configuration ${configuration} --no-restore"
        sh "dotnet publish ${project} --configuration ${configuration} --no-build --output ${outputDir}"

        def duration = (System.currentTimeMillis() - startTime) / 1000
        echo "Build completed in ${duration}s"

        return [status: 'SUCCESS', duration: duration, outputDir: outputDir]
    } catch (Exception e) {
        echo "ERROR: .NET build failed — ${e.message}"
        return [status: 'FAILURE', error: e.message]
    }
}
```

Key elements of the contract:

1. **`def call(Map config = [:])`** -- Every function takes a single Map parameter with defaults. Callers pass only the values they want to override.
2. **Defaults come from the config map or environment** -- `config.project ?: '.'` means "use whatever the caller passed, or fall back to the workspace root."
3. **`try/catch` around all work** -- Failures return a structured result instead of throwing an unhandled exception. The orchestrator decides whether to abort or continue.
4. **Structured return value** -- Always a Map with at minimum `status` and either `duration` (on success) or `error` (on failure). Some functions add extra fields like `report`, `findings`, or `gateStatus`.

### The PipelineConfig Class

Rather than passing a dozen parameters to every function, the orchestrators create a single `PipelineConfig` object that holds all configuration:

```groovy
// jenkins-shared-lib/src/com/devsecops/PipelineConfig.groovy
package com.devsecops

class PipelineConfig implements Serializable {
    String appName = 'sampleapi'
    String gitCredentialsId = 'gitlab-token'
    String gitlabUrl = ''          // <-- set from env.GITLAB_URL
    String appSourceRepo = ''      // <-- derived: ${gitlabUrl}/devsecops/app-source.git
    String buildConfigRepo = ''    // <-- derived
    String gitopsRepo = ''         // <-- derived
    String imageRegistry = ''      // <-- set from env.IMAGE_REGISTRY
    String imageTag = ''           // <-- set by pipeline (main-SHA or tag)
    String sonarUrl = ''           // <-- set from env.SONARQUBE_URL
    String acsUrl = ''             // <-- set from env.ACS_CENTRAL_URL
    int maxCriticalVulns = 0
    // ... more fields ...

    @NonCPS
    void initFromEnv(def env) {
        this.gitlabUrl = env.GITLAB_URL ?: 'https://gitlab-devsecops-gitlab.apps...'
        this.appSourceRepo = "${this.gitlabUrl}/devsecops/app-source.git"
        this.buildConfigRepo = "${this.gitlabUrl}/devsecops/build-config.git"
        this.gitopsRepo = "${this.gitlabUrl}/devsecops/app-gitops.git"
        // ... more derivations ...
    }
}
```

Two things to note:

- **`initFromEnv(def env)`** -- The parameter type is `def`, not `Map`. Jenkins' `env` object is an `EnvActionImpl`, not a standard Java Map. Using `Map env` as the parameter type causes a runtime `ClassCastException`.

- **`@NonCPS`** -- Methods annotated with `@NonCPS` run outside the Jenkins CPS (Continuation Passing Style) runtime. This is required for methods that use standard Groovy/Java APIs like string manipulation. Without it, you get serialization errors at runtime.

### Service Parameterization: configureForService()

With two services (SampleApi and NotificationApi), we need one `PipelineConfig` class that can switch between services at runtime. The `configureForService()` method does exactly that:

```groovy
// jenkins-shared-lib/src/com/devsecops/PipelineConfig.groovy (continued)

// Active service being built (set by configureForService)
String activeServiceName = ''
String activeImageName = ''       // Set by configureForService
String activeSourceRepo = ''      // Set by configureForService

@NonCPS
void configureForService(String serviceName) {
    this.activeServiceName = serviceName
    switch (serviceName) {
        case 'notificationapi':
            this.activeImageName = 'notificationapi'
            this.activeSourceRepo = "${this.gitlabUrl}/devsecops/notificationapi-source.git"
            this.activeBuildArgs = [PROJECT_NAME: 'NotificationApi',    // <-- build-arg for Dockerfile
                                   SOLUTION_NAME: 'NotificationApi',  // <-- solution file name
                                   APP_PORT: '8081']                  // <-- exposed port
            this.sonarProjectKey = 'notificationapi'
            this.gitlabProjectId = '5'                                // <-- GitLab project ID
            break
        default: // sampleapi
            this.activeImageName = this.imageName
            this.activeSourceRepo = this.appSourceRepo
            this.activeBuildArgs = [:]
            this.sonarProjectKey = this.appName
            break
    }
}

@NonCPS
String getGitopsServicePath() {
    return "services/${this.activeServiceName}"    // <-- e.g. "services/notificationapi"
}
```

When a NotificationApi job calls `pipelineMerge(service: 'notificationapi')`, the orchestrator runs `pipelineConfig.configureForService('notificationapi')` early in the Initialize stage. From that point on, every function in the pipeline uses the NotificationApi repo, image name, and GitOps path — without any per-function branching logic.

To add a third service, you add one `case` block here, create a JCasC job definition, a GitLab repo, and webhooks. The shared library functions do not change at all. This is the key scalability benefit of service parameterization.

### The ImageTagger Class

Image tags are not random. Each trigger type produces a predictable tag format:

```groovy
// T2 merge: "main-a1b2c3d" (branch + 7-char SHA)
static String forMerge(String gitSha) {
    return "main-${gitSha?.take(7) ?: 'unknown'}"
}

// T3 tag: "v1.2.0" (the git tag name, stripped of refs/tags/ prefix)
static String forTag(String gitBranch) {
    return gitBranch.replaceAll('.*/tags/', '').replaceAll('.*/', '')
}
```

This means you can always trace a running container back to the exact commit or release that produced it.

### How an Orchestrator Ties It All Together

Here is the T1 (Merge Request) orchestrator flow, simplified to show the structure:

```groovy
// vars/pipelineMR.groovy
def call(Map config = [:]) {
    def pipelineConfig = new com.devsecops.PipelineConfig()
    def results = [:]                     // <-- accumulates results from each stage
    def serviceName = config.service ?: 'sampleapi'  // <-- service param from JCasC job

    pipeline {
        agent { label 'devsecops-agent' } // <-- uses the pod template we defined

        environment {
            APP_NAME = "${pipelineConfig.appName}"
            GITLAB_PROJECT_ID = "${pipelineConfig.gitlabProjectId}"
        }

        options {
            timestamps()
            ansiColor('xterm')
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
            gitLabConnection('gitlab')    // <-- enables updateGitlabCommitStatus
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        pipelineConfig.initFromEnv(env)
                        pipelineConfig.configureForService(serviceName) // <-- sets repo, image, build args
                        updateGitlabCommitStatus name: 'jenkins-ci', state: 'running'
                    }
                }
            }
            stage('Checkout Source') {
                steps {
                    script {
                        def branch = env.gitlabSourceBranch ?: env.GIT_BRANCH ?: 'main'
                        results.checkout = checkoutSource(
                            branch: branch,
                            gitUrl: pipelineConfig.activeSourceRepo  // <-- service-specific repo
                        )
                    }
                }
            }
            stage('Checkout Build Config') {
                steps {
                    script {
                        results.buildConfig = checkoutBuildConfig(
                            gitUrl: pipelineConfig.buildConfigRepo  // <-- shared across services
                        )
                    }
                }
            }
            stage('Build')            { /* ... buildDotnet(project: '.') ... */ }
            stage('Unit Tests')       { /* ... runUnitTests(project: '.') ... */ }
            stage('SonarQube')        { /* ... scanSonarQube(...) ... */ }
            stage('Dependency Check') { /* ... scanDependencyCheck(...) ... */ }
        }

        post {
            success {
                script {
                    updateGitlabCommitStatus name: 'jenkins-ci', state: 'success'
                    commentOnMR(           // <-- posts scan summary table to GitLab MR
                        status: 'SUCCESS',
                        results: results,
                        pipelineConfig: pipelineConfig,
                        gitlabUrl: pipelineConfig.gitlabUrl,
                        projectId: pipelineConfig.gitlabProjectId
                    )
                    notifyTeam(message: 'MR validation passed', status: 'SUCCESS')
                }
            }
            failure {
                script {
                    updateGitlabCommitStatus name: 'jenkins-ci', state: 'failed'
                    commentOnMR(
                        status: 'FAILURE',
                        results: results,
                        pipelineConfig: pipelineConfig,
                        gitlabUrl: pipelineConfig.gitlabUrl,
                        projectId: pipelineConfig.gitlabProjectId
                    )
                    notifyTeam(message: 'MR validation FAILED', status: 'FAILURE')
                }
            }
            always { cleanWs() }
        }
    }
}
```

Notice how the orchestrator:

1. **Reads the `service` parameter** from the JCasC job config (`config.service ?: 'sampleapi'`).
2. **Calls `configureForService()`** to set the active source repo, image name, build args, and GitLab project ID for that service.
3. **Passes `activeSourceRepo`** to checkout functions -- this is service-specific (e.g., `notificationapi-source.git`), while `buildConfigRepo` is shared across all services (the single parameterized Dockerfile).
4. Stores each result in the `results` map for later reporting.
5. Reports status back to GitLab in the `post` block.
6. Passes `gitUrl` explicitly to checkout functions -- because `env.GITLAB_URL` is not always available in the Jenkins environment. This was a real production bug that caused empty checkout URLs.

The T2 orchestrator (`pipelineMerge.groovy`) extends T1 with container image build, registry push, ACS scan, GitOps update, and DEV deployment. The T3 orchestrator (`pipelineTag.groovy`) adds strict ACS scanning, OWASP ZAP DAST (via a sidecar container), performance testing (k6 load test), image signing, and automatic promotion MR creation.

---

## Part 6: Verify the Agent Pod Lifecycle

### Step 7: Trigger a Build and Watch the Agent

With Jenkins deployed and jobs configured, let us observe the ephemeral agent lifecycle. You need the shared library pushed to GitLab for this to work. If you have not done that yet, a manual trigger will still show the agent pod behavior.

```bash
# In one terminal, watch pods in the tools namespace
$OC get pods -n $NS_TOOLS -w
```

Now trigger a build. You can do this from the Jenkins UI (click "Build Now" on any job) or via the CLI:

```bash
JENKINS_HOST=$($OC get route jenkins -n $NS_TOOLS -o jsonpath='{.spec.host}')
curl -sk -X POST -u admin:DevSec0ps-Jenkins-2024 \
  "https://${JENKINS_HOST}/job/sampleapi-merge/build"
```

In your watching terminal, you will see:

```
NAME                      READY   STATUS    AGE
jenkins-7d8f9b6c4-x2k1l  1/1     Running   15m
sampleapi-merge-1-abcde   0/1     Pending   0s      <-- agent pod created
sampleapi-merge-1-abcde   0/1     Init:0/1  2s      <-- pulling image
sampleapi-merge-1-abcde   1/1     Running   45s     <-- build is running
sampleapi-merge-1-abcde   1/1     Running   8m      <-- build finishes
sampleapi-merge-1-abcde   0/1     Completed 8m10s
sampleapi-merge-1-abcde   0/1     Terminating 8m15s <-- pod destroyed
```

The agent pod lived for exactly as long as the build needed it. After the build completes, the pod is deleted. No state persists. The next build will get a fresh pod with a freshly pulled image.

### Verify: Agent Pod Resources

While a build is running, inspect the agent pod:

```bash
# Get the agent pod name
AGENT_POD=$($OC get pods -n $NS_TOOLS -l jenkins/label=devsecops-agent -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)

# Check its resource allocation
$OC describe pod $AGENT_POD -n $NS_TOOLS | grep -A5 "Limits\|Requests"

# Check that tools are available
$OC exec $AGENT_POD -n $NS_TOOLS -- dotnet --version
$OC exec $AGENT_POD -n $NS_TOOLS -- podman --version
$OC exec $AGENT_POD -n $NS_TOOLS -- roxctl version
$OC exec $AGENT_POD -n $NS_TOOLS -- gitleaks version
$OC exec $AGENT_POD -n $NS_TOOLS -- kustomize version
$OC exec $AGENT_POD -n $NS_TOOLS -- k6 version
$OC exec $AGENT_POD -n $NS_TOOLS -- argocd version --client
```

If any tool is missing, the agent image needs to be rebuilt.

---

## Recap

Here is what you built in this module and why each piece matters:

| Component | What It Does | Why It Matters |
|-----------|-------------|----------------|
| Jenkins controller (Deployment + PVC) | Serves UI, schedules builds, receives webhooks | Single control plane for all CI/CD |
| Custom agent image (Dockerfile.agent) | Contains dotnet, Podman, roxctl, dotnet-sonarscanner, dependency-check, k6, argocd, etc. | Reproducible, versioned build environment |
| JCasC ConfigMap | Configures security, cloud, shared library, jobs | Infrastructure-as-code for Jenkins itself |
| Kubernetes cloud template | Defines ephemeral agent pod spec | Clean builds, elastic scaling, no state leakage |
| Shared library (vars/ + src/) | One function per file, Map config, structured returns | Reusable, testable, auditable pipeline logic |
| Inline CPS job definitions | `@Library(...) _; pipelineMerge()` | App repo stays clean, pipeline logic centralized |

The data flow for a build looks like this:

```
GitLab webhook --> Jenkins controller --> Kubernetes plugin creates agent pod
                                      --> Agent pod pulls shared lib from GitLab
                                      --> Orchestrator runs: checkout --> build --> scan --> push --> deploy
                                      --> Agent pod reports results
                                      --> Agent pod is destroyed
```

---

## Common Mistakes

These are real issues encountered during production deployment. Each one cost hours to debug.

### 1. Agent ENTRYPOINT Set to /bin/bash

**Symptom:** Agent pod starts, stays Running, but the Jenkins build hangs at "Waiting for agent to connect."

**Cause:** The image ENTRYPOINT is `/bin/bash` instead of a JNLP agent script. The Kubernetes plugin injects `JENKINS_URL`, `JENKINS_SECRET`, etc. as environment variables, but the container just starts a bash shell and does nothing with them.

**Fix:** Use the ENTRYPOINT script shown in the Dockerfile that downloads `agent.jar` from the controller and starts the JNLP connection.

### 2. Stale Agent Image After Rebuild

**Symptom:** You rebuilt the agent image to add a tool, but pipelines still report the tool as missing.

**Cause:** OpenShift worker nodes cache images by tag. If the tag is `:latest`, nodes will not pull a new image unless forced.

**Fix:** Set `alwaysPullImage: true` in the JCasC container template. This adds `imagePullPolicy: Always` to every agent pod.

### 3. Empty command/args in JCasC Container Template

**Symptom:** Agent pod starts but immediately exits with no logs.

**Cause:** Setting `command: ""` or `args: ""` in the JCasC yaml override replaces the image ENTRYPOINT with an empty string. The container has nothing to execute.

**Fix:** Remove `command` and `args` entirely from the jnlp container definition. Let the image ENTRYPOINT handle agent startup.

### 4. Credentials Defined in JCasC With Conflicting IDs

**Symptom:** Credentials work after initial deployment but become empty after Jenkins restarts.

**Cause:** JCasC credential IDs that match filenames in `casc_secrets/` trigger a circular resolution loop. Jenkins tries to resolve the credential from the file, which references the JCasC definition, which references the file.

**Fix:** Create credentials via the Jenkins Script Console or UI after deployment, not via JCasC.

### 5. PipelineConfig.initFromEnv With Wrong Parameter Type

**Symptom:** `MissingMethodException` or `ClassCastException` when the pipeline starts.

**Cause:** The `initFromEnv` method declares `Map env` as the parameter type, but Jenkins' `env` object is an `EnvActionImpl`, not a Map.

**Fix:** Use `def env` as the parameter type. `def` accepts any type.

### 6. env.GIT_COMMIT Is Null After Checkout

**Symptom:** Image tag is `main-null` instead of `main-a1b2c3d`.

**Cause:** When `checkout` happens inside a `vars/` function, the `env.GIT_COMMIT` variable is not always propagated back to the calling scope.

**Fix:** Use a fallback: `env.GIT_COMMIT ?: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()`

---

## Challenge

You now have Jenkins running with seven pre-defined jobs. Try this:

1. **Verify k6 is pre-installed in the agent image.** The Dockerfile already includes k6 v0.54.0 (used in Module 14 for performance testing). Confirm it by running a build and checking the console output, or by inspecting the Dockerfile: `grep k6 infra/phase7/Dockerfile.agent`.

2. **Write a new shared library function.** Create `vars/runLoadTest.groovy` that follows the contract: accepts `Map config = [:]` with a `target` URL and `duration` parameter, runs `k6 run --vus 10 --duration ${duration} ${target}`, and returns `[status: 'SUCCESS', duration: elapsed]` or `[status: 'FAILURE', error: message]`. (You will see the production version of this in Module 14 as `runPerformanceTest.groovy`.)

3. **Add it to the T3 orchestrator.** In `pipelineTag.groovy`, add a new stage after OWASP ZAP that calls your `runLoadTest` function against the DEV endpoint. (Module 14 covers this integration in detail.)

---

## Self-Assessment

Answer these before moving on. If you cannot answer confidently, re-read the relevant section.

1. Why is `numExecutors` set to 0 on the Jenkins controller?
2. What happens if you set `alwaysPullImage: false` and rebuild the agent image with a new tool?
3. Why does the agent ENTRYPOINT download `agent.jar` at runtime instead of baking it into the image?
4. What is the difference between a `vars/` function and a class in `src/`?
5. Why do we pass `gitUrl` explicitly to `checkoutSource()` instead of relying on `env.GITLAB_URL`?
6. What does `@NonCPS` do, and when is it required?
7. If a `vars/` function throws an exception instead of returning `[status: 'FAILURE']`, what happens to the pipeline?
8. Why are credentials not defined in JCasC in this project?

<details>
<summary>Answers</summary>

1. To prevent builds from running on the controller pod, which keeps it stable and available for UI/webhook/scheduling duties.
2. Worker nodes use their cached copy of the `:latest` image. The new tool will not be available until the cache is evicted or the pod is scheduled on a node that has never pulled the image.
3. Because the `agent.jar` version must match the Jenkins controller version exactly. If the controller is upgraded, a baked-in JAR becomes incompatible. Downloading at runtime guarantees a match.
4. `vars/` functions are globally callable from any pipeline (like built-in steps). `src/` classes are standard Groovy classes that must be imported and instantiated. Use `vars/` for pipeline steps, `src/` for shared logic like configuration and utility methods.
5. `env.GITLAB_URL` may not be set as a Jenkins environment variable in all configurations. Relying on it causes empty checkout URLs. Passing the URL explicitly from `PipelineConfig` (which derives it during `initFromEnv()`) is deterministic and avoids this class of bugs.
6. `@NonCPS` marks a method to run outside the CPS (Continuation Passing Style) transform. It is required for methods that use standard Java/Groovy APIs that are not CPS-compatible (string manipulation, collection operations, etc.). Without it, Jenkins throws serialization errors.
7. The stage fails immediately and the pipeline jumps to the `post { failure { } }` block. Returning a structured result instead lets the orchestrator decide whether to abort or continue with a warning.
8. Credential IDs that match filenames in `casc_secrets/` cause circular resolution on JCasC reload, resulting in empty credential values. Managing credentials via Script Console or UI avoids this.

</details>

---

## Next Module

**Module 4: GitOps with ArgoCD** -- You will deploy OpenShift GitOps, create Kustomize overlays for four environments, wire ArgoCD Applications with auto-sync (DEV) and manual sync (SIT/UAT/PROD), and implement the promotion flow that moves a release image from DEV through to production via merge requests.
