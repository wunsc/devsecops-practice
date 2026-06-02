# Module 16: Java Microservices on OpenShift

| | |
|---|---|
| **Track** | Supply Chain & Multi-Language |
| **Duration** | ~90 minutes |
| **Difficulty** | Intermediate |
| **Prerequisites** | Modules 1-10 complete (all pipeline triggers working, GitOps understood) |

---

## What You'll Learn

By the end of this module you will be able to:

1. Explain how `PipelineConfig.configureForService()` routes Java and .NET services through the same shared library orchestrators
2. Create namespaces, RBAC, and shared infrastructure (PostgreSQL + Redis) for a Java microservice ecosystem
3. Build a multi-stage Dockerfile for Java 21 Spring Boot applications using UBI9-minimal
4. Create 3 GitLab repositories, 3 SonarQube projects, 9 Jenkins jobs, and 9 GitLab webhooks for order-service, inventory-service, and payment-service
5. Structure per-service GitOps manifests with Kustomize base + overlays for 4 environments
6. Create 16 ArgoCD Application CRDs (12 for services + 4 for shared infrastructure)
7. Run a full T2 (merge) pipeline for a Java service and verify the deployment in javaapp-dev
8. Verify inter-service communication between order-service, inventory-service, and payment-service

---

## Prerequisites

> **Environment variables:** Before running any commands, source the environment file:
> ```bash
> source ./env.sh
> ```
> This sets `$OC`, `$APPS_DOMAIN`, `$NS_TOOLS`, `$NS_JAVA_DEV`, and all other cluster-specific variables used throughout this module. See `env.sh` for the full variable list.

Confirm the following before starting:

```bash
# You are logged in with cluster-admin
$OC whoami
# --> admin

# .NET pipeline infrastructure is fully working (Module 8-10)
$OC get pods -n $NS_DEV -l app=sampleapi
# --> 1 pod Running

# ArgoCD is operational with existing apps
$OC get applications -n $NS_GITOPS -o custom-columns='NAME:.metadata.name' | head -5
# --> infra-dev, sampleapi-dev, notificationapi-dev, etc.

# Jenkins is running and accessible
curl -sk -o /dev/null -w "%{http_code}" ${JENKINS_URL}/login
# --> 200

# GitLab is running
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/groups/devsecops/projects" | jq -r '.[].path' | sort
# --> app-gitops, app-source, build-config, jenkins-shared-lib, notificationapi-source

# SonarQube is healthy
SONAR_PASS=$($OC get secret sonarqube-token -n $NS_TOOLS -o jsonpath='{.data.password}' | base64 -d)
curl -sk -u "admin:${SONAR_PASS}" \
  https://sonarqube-${NS_TOOLS}.${APPS_DOMAIN}/api/system/health \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['health'])"
# --> GREEN
```

If any of these fail, go back to the relevant module and resolve the issue before continuing.

---

## 1. Concepts: Multi-Language Pipelines (15 min)

### Why Add Java to a .NET Platform?

In real enterprises, teams do not agree on a single language. The .NET team ships their API. The Java team ships their microservices. The platform team builds one CI/CD pipeline that handles both. If your DevSecOps platform only supports .NET, it is a single-language demo, not a platform.

Adding Java proves three things:
1. **The shared library is truly shared** -- the same `pipelineMR()`, `pipelineMerge()`, and `pipelineTag()` orchestrators handle both languages without duplication
2. **The GitOps structure scales** -- adding 3 services means adding 3 directories, not rewriting the deployment model
3. **Security scanning is language-aware** -- SonarQube uses different analyzers, SBOMs use different generators, but the pipeline flow is identical

### Architecture After This Module

```
sampleapi-{env} namespace                    javaapp-{env} namespace
┌──────────────┐ ┌───────────────────┐       ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  SampleApi   │→│  NotificationApi  │       │ OrderService │→│ InventorySvc │→│ PaymentSvc   │
│  (.NET 8)    │ │  (.NET 8)         │       │ (Java 21)    │ │ (Java 21)    │ │ (Java 21)    │
│  Port 8080   │ │  Port 8081        │       │ Port 8080    │ │ Port 8081    │ │ Port 8082    │
└──────┬───────┘ └────────┬──────────┘       └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       └─────────┬────────┘                         └────────┬───────┴────────┬───────┘
          ┌──────▼──────┐ ┌───────┐               ┌─────────▼──┐ ┌──────────▼──┐
          │ PostgreSQL  │ │ Redis │               │ PostgreSQL  │ │    Redis     │
          │ (StatefulSet)│ │(SS)  │               │ (StatefulSet)│ │ (StatefulSet)│
          │ Port 5432   │ │ 6379 │               │  Port 5432   │ │  Port 6379   │
          └─────────────┘ └──────┘               └─────────────┘ └─────────────┘
```

Two completely isolated namespace sets. The `.NET` services in `sampleapi-{env}` have their own PostgreSQL and Redis. The Java services in `javaapp-{env}` get their own. No cross-namespace database sharing.

> **Why this matters:** Namespace isolation means a rogue Java service cannot accidentally connect to the .NET database. Each ecosystem has its own secrets, NetworkPolicies, and resource quotas. A failure in `javaapp-dev` does not affect `sampleapi-dev`.

### How Language Routing Works: PipelineConfig.configureForService()

The shared library orchestrators (`pipelineMR`, `pipelineMerge`, `pipelineTag`) are language-agnostic. They call `pipelineConfig.configureForService(serviceName)`, which sets:

| Field | .NET (sampleapi) | Java (order-service) |
|-------|-------------------|---------------------|
| `activeLanguage` | `dotnet` | `java` |
| `activeDockerfile` | `Dockerfile` | `Dockerfile.java` |
| `activeSourceRepo` | `.../app-source.git` | `.../order-service.git` |
| `imageNamespace` | `sampleapi-dev` | `javaapp-dev` |
| `appNamespace` | `sampleapi` | `javaapp` |
| `activeBuildArgs` | `[:]` (Dockerfile defaults) | `[SERVICE_NAME: 'order-service', APP_PORT: '8080']` |
| `sonarProjectKey` | `sampleapi` | `order-service` |
| `gitlabProjectId` | `1` | `10` |

The orchestrator then uses `activeLanguage` to choose which build/test/scan functions to call:

```groovy
// Inside pipelineMerge.groovy — the language routing
stage('Build') {
    steps {
        script {
            if (pipelineConfig.activeLanguage == 'java') {
                results.build = buildJava(project: '.')    // ← mvn package -DskipTests
            } else {
                results.build = buildDotnet(project: '.')  // ← dotnet build
            }
        }
    }
}
```

This pattern repeats for unit tests (`runJavaTests` vs `runUnitTests`), SAST (`scanSonarQubeJava` vs `scanSonarQube`), and SBOM generation (`cyclonedx-maven-plugin` vs `dotnet-CycloneDX`).

### Pipeline Stage Comparison

| Stage | .NET Pipeline | Java Pipeline |
|-------|---------------|---------------|
| Build | `buildDotnet.groovy` -- `dotnet restore` / `dotnet build` / `dotnet publish` | `buildJava.groovy` -- `mvn package -DskipTests -B` |
| Unit Tests | `runUnitTests.groovy` -- `dotnet test`, xunit format | `runJavaTests.groovy` -- `mvn test -B`, surefire/JUnit format |
| SAST | `scanSonarQube.groovy` -- dotnet-sonarscanner begin/end | `scanSonarQubeJava.groovy` -- `mvn sonar:sonar` (Maven plugin) |
| SBOM | dotnet-CycloneDX | cyclonedx-maven-plugin |
| Dependency Check | OWASP Dependency-Check (same CLI) | OWASP Dependency-Check (same CLI) |
| Container Build | `Dockerfile` (multi-stage .NET 8 SDK -> ASP.NET runtime) | `Dockerfile.java` (multi-stage Maven+JDK 21 -> UBI9-minimal+JRE) |
| Image Scan | ACS `roxctl` (identical) | ACS `roxctl` (identical) |
| Image Signing | Cosign (identical) | Cosign (identical) |
| GitOps Update | `updateGitOps.groovy` (identical) | `updateGitOps.groovy` (identical) |
| Health Endpoint | `/healthz` -> `{"healthy":true}` | `/actuator/health` -> `{"status":"UP"}` |

Notice that 5 out of 10 stages are identical regardless of language. The platform does not care what language produced the container image -- it cares about the image.

### The Three Java Services

| Service | Port | Purpose | External Route? |
|---------|------|---------|-----------------|
| **order-service** | 8080 | Public API -- receives orders, coordinates inventory check and payment | Yes (the frontend gateway) |
| **inventory-service** | 8081 | Internal -- checks stock levels, reserves inventory | No (internal ClusterIP only) |
| **payment-service** | 8082 | Internal -- processes payments, records transactions | No (internal ClusterIP only) |

The call flow:

```
External Request
       │
       ▼
┌──────────────┐    HTTP (8081)    ┌──────────────┐
│ order-service │───────────────→ │ inventory-svc │
│   (port 8080) │                  │  (port 8081)  │
│               │    HTTP (8082)   └──────────────┘
│               │───────────────→ ┌──────────────┐
│               │                  │ payment-svc  │
└──────┬────────┘                  │  (port 8082)  │
       │                           └──────┬────────┘
       │ JDBC (5432)                       │ JDBC (5432)
       ▼                                   ▼
┌──────────────┐               ┌──────────────┐
│ PostgreSQL   │               │ PostgreSQL   │  (same instance, different DBs)
└──────────────┘               └──────────────┘
```

> **Why this matters:** This is the minimal realistic microservice pattern. One service is the public gateway. The others are internal. This lets you demonstrate:
> - **NetworkPolicies** that allow ingress traffic only to order-service
> - **No external Route** for inventory-service and payment-service
> - **Inter-service HTTP calls** that must work inside the cluster
> - **Independent deployment** -- updating payment-service does not require redeploying order-service

---

## 2. Examine the Java Services (10 min)

### Spring Boot Application Structure

Each Java service follows the same Spring Boot 3.x structure:

```
order-service/
├── pom.xml                          # Maven build file
├── mvnw                             # Maven wrapper (no global Maven needed)
├── mvnw.cmd                         # Maven wrapper for Windows
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties # Wrapper version config
└── src/
    ├── main/
    │   ├── java/com/devsecops/orderservice/
    │   │   ├── OrderServiceApplication.java    # @SpringBootApplication entry point
    │   │   ├── controller/
    │   │   │   ├── OrderController.java        # REST API endpoints
    │   │   │   └── HealthController.java       # /actuator/health customizations
    │   │   ├── service/
    │   │   │   └── OrderService.java           # Business logic
    │   │   ├── model/
    │   │   │   └── Order.java                  # JPA entity
    │   │   └── config/
    │   │       └── AppConfig.java              # Environment-driven config
    │   └── resources/
    │       └── application.yml                 # Spring Boot configuration
    └── test/
        └── java/com/devsecops/orderservice/
            └── OrderServiceTest.java           # JUnit 5 tests
```

### Key pom.xml Sections

The `pom.xml` is the equivalent of .NET's `.csproj`. Here are the sections that matter for the pipeline:

```xml
<!-- order-service/pom.xml -->
<project>
    <groupId>com.devsecops</groupId>
    <artifactId>order-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>                     <!-- ← Produces a fat JAR, not a WAR -->

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>                   <!-- ← Spring Boot 3.x requires JDK 17+ -->
    </parent>

    <properties>
        <java.version>21</java.version>            <!-- ← JDK 21 LTS (matches Dockerfile) -->
        <sonar.java.source>21</sonar.java.source>  <!-- ← Tells SonarQube which Java version -->
    </properties>

    <dependencies>
        <!-- Web + REST -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Actuator for /actuator/health endpoint -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>  <!-- ← THIS IS KEY -->
        </dependency>

        <!-- JPA + PostgreSQL -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- JUnit 5 for tests -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>                    <!-- ← Only in test classpath -->
        </dependency>

        <!-- CycloneDX Maven plugin for SBOM generation -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>  <!-- ← Creates executable JAR -->
            </plugin>
            <plugin>
                <groupId>org.cyclonedx</groupId>
                <artifactId>cyclonedx-maven-plugin</artifactId>    <!-- ← SBOM generation -->
                <version>2.8.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

### Spring Boot Health Endpoints vs .NET Health Endpoints

This is a critical difference for your Kubernetes probes:

| Aspect | .NET (SampleApi) | Java (Spring Boot) |
|--------|-------------------|--------------------|
| Health URL | `/healthz` | `/actuator/health` |
| Response (healthy) | `{"healthy":true}` | `{"status":"UP"}` |
| Response (unhealthy) | `{"healthy":false}` | `{"status":"DOWN"}` |
| Readiness URL | `/readyz` | `/actuator/health/readiness` |
| Liveness URL | `/healthz` | `/actuator/health/liveness` |
| Configuration | Custom middleware | `spring-boot-starter-actuator` dependency |
| Enable in config | Always on | `management.endpoints.web.exposure.include=health` |

Your Kubernetes Deployment probes will look different:

```yaml
# .NET probes (existing SampleApi)
livenessProbe:
  httpGet:
    path: /healthz                    # ← .NET custom endpoint
    port: 8080
readinessProbe:
  httpGet:
    path: /readyz                     # ← .NET custom endpoint
    port: 8080

# Java probes (new order-service)
livenessProbe:
  httpGet:
    path: /actuator/health/liveness   # ← Spring Boot Actuator
    port: 8080
readinessProbe:
  httpGet:
    path: /actuator/health/readiness  # ← Spring Boot Actuator
    port: 8080
```

> **Why this matters:** If you copy the .NET probe paths into a Java Deployment, the pod will enter `CrashLoopBackOff` because `/healthz` returns 404 on a Spring Boot app. The health path is the #1 mistake when adding a new language.

### application.yml Configuration

Spring Boot uses `application.yml` (or `application.properties`) for configuration. Environment variables override these defaults:

```yaml
# src/main/resources/application.yml
server:
  port: ${APP_PORT:8080}                          # ← Overridden by env var or default

spring:
  application:
    name: ${SERVICE_NAME:order-service}
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/orderdb}
    username: ${DATABASE_USER:postgres}
    password: ${DATABASE_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: update                             # ← Auto-create tables (DEV only)

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus            # ← Expose actuator endpoints
  endpoint:
    health:
      probes:
        enabled: true                              # ← Enable /liveness and /readiness
      show-details: always
```

The key pattern: **the app reads environment variables that you inject via Kubernetes ConfigMaps and Secrets.** The same mechanism as .NET's `IConfiguration` -- different syntax, same result.

---

## 3. Create Java Namespaces and Infrastructure (10 min)

### Step 1: Create the Four Java Namespaces

Just like the .NET services have `sampleapi-{dev,sit,uat,prod}`, the Java services need `javaapp-{dev,sit,uat,prod}`:

```bash
# Create all 4 namespaces
for ENV in dev sit uat prod; do
  $OC create namespace ${JAVA_APP_NAME}-${ENV} \
    --dry-run=client -o yaml | $OC apply -f -
  
  # Label for organization
  $OC label namespace ${JAVA_APP_NAME}-${ENV} \
    app.kubernetes.io/part-of=devsecops \
    environment=${ENV} \
    language=java \
    --overwrite
done
```

Verify:

```bash
$OC get namespaces -l language=java
```

Expected:

```
NAME           STATUS   AGE
javaapp-dev    Active   10s
javaapp-prod   Active   10s
javaapp-sit    Active   10s
javaapp-uat    Active   10s
```

### Step 2: Configure RBAC for Jenkins

Jenkins needs to push images to the `javaapp-dev` namespace and trigger deployments across all Java namespaces. The Jenkins ServiceAccount in `$NS_TOOLS` needs the same permissions it has for the .NET namespaces:

```bash
# Grant Jenkins image-pusher role in javaapp-dev (where images are built and stored)
$OC policy add-role-to-user system:image-builder \
  system:serviceaccount:${NS_TOOLS}:jenkins \
  -n ${NS_JAVA_DEV}

# Grant Jenkins edit access to all Java namespaces (for deployments, configmaps, secrets)
for ENV in dev sit uat prod; do
  $OC policy add-role-to-user edit \
    system:serviceaccount:${NS_TOOLS}:jenkins \
    -n ${JAVA_APP_NAME}-${ENV}
done

# Grant ArgoCD access to manage resources in Java namespaces
for ENV in dev sit uat prod; do
  $OC policy add-role-to-user admin \
    system:serviceaccount:${NS_GITOPS}:openshift-gitops-argocd-application-controller \
    -n ${JAVA_APP_NAME}-${ENV}
done
```

Verify:

```bash
# Confirm Jenkins can push images to javaapp-dev
$OC auth can-i create imagestreams \
  --as=system:serviceaccount:${NS_TOOLS}:jenkins \
  -n ${NS_JAVA_DEV}
# --> yes

# Confirm ArgoCD can manage deployments in javaapp-dev
$OC auth can-i create deployments \
  --as=system:serviceaccount:${NS_GITOPS}:openshift-gitops-argocd-application-controller \
  -n ${NS_JAVA_DEV}
# --> yes
```

### Step 3: Create Resource Quotas and Limit Ranges

```yaml
# infra/javaapp/base/resource-quota.yaml
# Shared resource quota template -- overlays will adjust limits per environment
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: compute-quota
spec:
  hard:
    requests.cpu: "4"                  # ← Total CPU requests across all pods
    requests.memory: 8Gi               # ← Total memory requests
    limits.cpu: "8"                     # ← Total CPU limits
    limits.memory: 16Gi                # ← Total memory limits
    pods: "20"                         # ← Max pods (3 services + infra + replicasets)
---
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
spec:
  limits:
    - type: Container
      default:
        cpu: 500m                      # ← Default limit if not specified
        memory: 512Mi
      defaultRequest:
        cpu: 100m                      # ← Default request if not specified
        memory: 128Mi
      max:
        cpu: "2"                       # ← No single container can exceed 2 CPU
        memory: 2Gi
```

Apply to all namespaces:

```bash
for ENV in dev sit uat prod; do
  $OC apply -f infra/javaapp/base/resource-quota.yaml -n ${JAVA_APP_NAME}-${ENV}
done
```

Verify:

```bash
$OC get quota,limitrange -n $NS_JAVA_DEV
```

Expected:

```
NAME                     AGE   REQUEST                                          LIMIT
resourcequota/compute-quota   10s   requests.cpu: 0/4, requests.memory: 0/8Gi   limits.cpu: 0/8, limits.memory: 0/16Gi

NAME                      CREATED AT
limitrange/default-limits   2026-05-29T...
```

### Step 4: Deploy Shared Infrastructure (PostgreSQL + Redis)

The Java services need their own PostgreSQL and Redis instances, separate from the .NET stack. We will define these as part of the GitOps infrastructure layer.

Create the PostgreSQL StatefulSet:

```yaml
# app-gitops/infra-javaapp/base/postgresql/statefulset.yaml
# PostgreSQL for Java microservices -- separate instance from .NET stack
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgresql
  labels:
    app: postgresql
    part-of: javaapp-infra
spec:
  serviceName: postgresql
  replicas: 1
  selector:
    matchLabels:
      app: postgresql
  template:
    metadata:
      labels:
        app: postgresql
    spec:
      containers:
        - name: postgresql
          image: registry.redhat.io/rhel9/postgresql-16:latest   # ← RHEL-supported image
          ports:
            - containerPort: 5432
              name: postgresql
          env:
            - name: POSTGRESQL_USER
              valueFrom:
                secretKeyRef:
                  name: infra-javaapp-secret      # ← Credentials from per-env secret
                  key: POSTGRESQL_USER
            - name: POSTGRESQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: infra-javaapp-secret
                  key: POSTGRESQL_PASSWORD
            - name: POSTGRESQL_DATABASE
              value: orderdb                       # ← Default database name
            - name: POSTGRESQL_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: infra-javaapp-secret
                  key: POSTGRESQL_ADMIN_PASSWORD
          resources:
            requests:
              cpu: 200m
              memory: 256Mi
            limits:
              cpu: "1"
              memory: 1Gi
          volumeMounts:
            - name: postgresql-data
              mountPath: /var/lib/pgsql/data       # ← RHEL PG image uses this path
          readinessProbe:
            exec:
              command:
                - /bin/sh
                - -c
                - pg_isready -U $POSTGRESQL_USER -d $POSTGRESQL_DATABASE
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            exec:
              command:
                - /bin/sh
                - -c
                - pg_isready -U $POSTGRESQL_USER -d $POSTGRESQL_DATABASE
            initialDelaySeconds: 30
            periodSeconds: 30
  volumeClaimTemplates:
    - metadata:
        name: postgresql-data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: gp3-csi                  # ← Must match your cluster's StorageClass
        resources:
          requests:
            storage: 5Gi                           # ← Enough for dev/test data
```

Create the PostgreSQL Service:

```yaml
# app-gitops/infra-javaapp/base/postgresql/service.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: postgresql
  labels:
    app: postgresql
spec:
  ports:
    - port: 5432
      targetPort: 5432
      name: postgresql
  selector:
    app: postgresql
  clusterIP: None                                  # ← Headless service for StatefulSet
```

Create the Redis StatefulSet:

```yaml
# app-gitops/infra-javaapp/base/redis/statefulset.yaml
# Redis cache for Java microservices
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
  labels:
    app: redis
    part-of: javaapp-infra
spec:
  serviceName: redis
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
        - name: redis
          image: registry.redhat.io/rhel9/redis-7:latest
          ports:
            - containerPort: 6379
              name: redis
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: infra-javaapp-secret
                  key: REDIS_PASSWORD
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 512Mi
          readinessProbe:
            exec:
              command:
                - /bin/sh
                - -c                               # ← RHEL Redis image needs explicit auth
                - redis-cli -a "$REDIS_PASSWORD" ping
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            exec:
              command:
                - /bin/sh
                - -c
                - redis-cli -a "$REDIS_PASSWORD" ping
            initialDelaySeconds: 15
            periodSeconds: 30
```

Create the Redis Service:

```yaml
# app-gitops/infra-javaapp/base/redis/service.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: redis
  labels:
    app: redis
spec:
  ports:
    - port: 6379
      targetPort: 6379
      name: redis
  selector:
    app: redis
  clusterIP: None
```

Create the base kustomization:

```yaml
# app-gitops/infra-javaapp/base/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - postgresql/statefulset.yaml
  - postgresql/service.yaml
  - redis/statefulset.yaml
  - redis/service.yaml
```

Create the DEV overlay with credentials:

```yaml
# app-gitops/infra-javaapp/overlays/dev/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: javaapp-dev                             # ← All resources go to this namespace

resources:
  - ../../base
  - secret-env.yaml
```

```yaml
# app-gitops/infra-javaapp/overlays/dev/secret-env.yaml
# Infrastructure credentials for Java services in DEV
# NOTE: In production, use SealedSecrets or ExternalSecrets. Plain secrets are for tutorials.
---
apiVersion: v1
kind: Secret
metadata:
  name: infra-javaapp-secret
type: Opaque
stringData:
  POSTGRESQL_USER: javaapp                         # ← Shared DB user for all Java services
  POSTGRESQL_PASSWORD: javaapp-secret-2026          # ← Change in production!
  POSTGRESQL_ADMIN_PASSWORD: admin-secret-2026
  REDIS_PASSWORD: redis-java-secret-2026
```

> **Why this matters:** Each environment gets its own credentials secret. DEV credentials are simple for debugging. SIT/UAT/PROD secrets should use `SealedSecrets` or an external secrets manager. The pattern is the same -- only the secret values change per overlay.

Create identical overlays for SIT, UAT, and PROD (same structure, different namespace):

```bash
# Create SIT/UAT/PROD overlays by copying DEV and updating namespace
for ENV in sit uat prod; do
  mkdir -p app-gitops/infra-javaapp/overlays/${ENV}
  
  # Kustomization with correct namespace
  cat > app-gitops/infra-javaapp/overlays/${ENV}/kustomization.yaml << KUSTEOF
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: javaapp-${ENV}

resources:
  - ../../base
  - secret-env.yaml
KUSTEOF

  # Secret with same structure (change passwords per env in real deployments)
  cat > app-gitops/infra-javaapp/overlays/${ENV}/secret-env.yaml << SECEOF
---
apiVersion: v1
kind: Secret
metadata:
  name: infra-javaapp-secret
type: Opaque
stringData:
  POSTGRESQL_USER: javaapp
  POSTGRESQL_PASSWORD: javaapp-${ENV}-2026
  POSTGRESQL_ADMIN_PASSWORD: admin-${ENV}-2026
  REDIS_PASSWORD: redis-java-${ENV}-2026
SECEOF
done
```

### Step 5: Create NetworkPolicies

```yaml
# app-gitops/infra-javaapp/base/networkpolicies.yaml
# Zero-trust networking for Java microservices namespace
---
# 1. Deny all ingress by default
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
spec:
  podSelector: {}                                  # ← Applies to ALL pods in namespace
  policyTypes:
    - Ingress
---
# 2. Allow OpenShift router to reach order-service (the only external service)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-router-to-order-service
spec:
  podSelector:
    matchLabels:
      app: order-service                           # ← Only order-service gets external traffic
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: ingress
      ports:
        - port: 8080
          protocol: TCP
---
# 3. Allow order-service to call inventory-service and payment-service
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-order-to-internal-services
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/part-of: javaapp-internal  # ← Matches inventory + payment
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: order-service                   # ← Only order-service can call them
      ports:
        - port: 8081
          protocol: TCP
        - port: 8082
          protocol: TCP
---
# 4. Allow all Java services to reach PostgreSQL
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-app-to-postgresql
spec:
  podSelector:
    matchLabels:
      app: postgresql
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: javaapp   # ← All Java services can reach the DB
      ports:
        - port: 5432
          protocol: TCP
---
# 5. Allow all Java services to reach Redis
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-app-to-redis
spec:
  podSelector:
    matchLabels:
      app: redis
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: javaapp
      ports:
        - port: 6379
          protocol: TCP
---
# 6. Allow same-namespace communication (pods in same namespace can talk to each other)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - podSelector: {}                          # ← Any pod in the same namespace
---
# 7. Allow monitoring (Prometheus scraping)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-monitoring
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              network.openshift.io/policy-group: monitoring
      ports:
        - port: 8080
          protocol: TCP
        - port: 8081
          protocol: TCP
        - port: 8082
          protocol: TCP
---
# 8. Deny all egress from PostgreSQL (DB should never initiate outbound connections)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-postgresql-egress
spec:
  podSelector:
    matchLabels:
      app: postgresql
  policyTypes:
    - Egress
  egress: []                                       # ← Empty = deny all egress
---
# 9. Deny all egress from Redis
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-redis-egress
spec:
  podSelector:
    matchLabels:
      app: redis
  policyTypes:
    - Egress
  egress: []
---
# 10. Allow ArgoCD to manage resources in this namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-argocd
spec:
  podSelector: {}
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: openshift-gitops
```

Add the NetworkPolicies to the base kustomization:

```yaml
# app-gitops/infra-javaapp/base/kustomization.yaml (updated)
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - postgresql/statefulset.yaml
  - postgresql/service.yaml
  - redis/statefulset.yaml
  - redis/service.yaml
  - networkpolicies.yaml                           # ← Added
```

Verify the kustomize build works:

```bash
kustomize build app-gitops/infra-javaapp/overlays/dev/ | head -20
```

Expected: YAML output with `namespace: javaapp-dev` injected into all resources.

---

## 4. Create Dockerfile.java (10 min)

### Tell: Multi-Stage Builds for Java

The strategy is the same as the .NET Dockerfile: use a fat build image (Maven + JDK) to compile, then copy only the JAR into a minimal runtime image. This reduces the final image from ~800MB to ~200MB.

```
Stage 1: Build                        Stage 2: Runtime
┌──────────────────────┐              ┌──────────────────────┐
│ Maven 3.9.8 + JDK 21│              │ UBI9-minimal + JRE 21│
│ (850MB)              │              │ (200MB)              │
│                      │   COPY jar   │                      │
│ mvn package          │────────────→│ java -jar app.jar    │
│ → target/app.jar     │              │                      │
└──────────────────────┘              └──────────────────────┘
         ❌ discarded                          ✅ shipped
```

### Dockerfile Comparison: .NET vs Java

| Aspect | Dockerfile (.NET) | Dockerfile.java (Java) |
|--------|-------------------|------------------------|
| Build image | `mcr.microsoft.com/dotnet/sdk:8.0` | `registry.access.redhat.com/ubi9/openjdk-21:latest` |
| Runtime image | `mcr.microsoft.com/dotnet/aspnet:8.0` | `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` |
| Build command | `dotnet publish -c Release` | `mvn package -DskipTests` |
| Output artifact | DLLs + `appsettings.json` | Single fat JAR |
| Runtime command | `dotnet SampleApi.dll` | `java -jar app.jar` |
| Default port | 8080 | 8080 |
| Non-root user | `USER 1001` | `USER 185` (JBoss user in UBI images) |

### Create the Dockerfile

```dockerfile
# build-config/Dockerfile.java
# Multi-stage Dockerfile for Spring Boot Java 21 applications
# Used by all Java services: order-service, inventory-service, payment-service
#
# Build args:
#   SERVICE_NAME — name of the service (used for JAR filename lookup)
#   APP_PORT     — port the application listens on (default 8080)
#
# Usage:
#   podman build -f Dockerfile.java \
#     --build-arg SERVICE_NAME=order-service \
#     --build-arg APP_PORT=8080 \
#     -t order-service:latest .

# ══════════════════════════════════════════════════════════════
# Stage 1: BUILD — compile and package with Maven
# ══════════════════════════════════════════════════════════════
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build

ARG SERVICE_NAME=order-service

# Copy Maven wrapper and POM first for dependency caching
# If only code changes (not pom.xml), Docker cache reuses the dependency layer
COPY --chown=185:0 pom.xml mvnw ./
COPY --chown=185:0 .mvn .mvn

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B \
    2>/dev/null || true                            # ← Tolerate missing wrapper; fallback below

# Copy source code
COPY --chown=185:0 src src

# Build the application (skip tests — they ran in an earlier pipeline stage)
RUN ./mvnw package -DskipTests -B \
    -Dmaven.repo.local=/home/jboss/.m2 \
    && mv target/*.jar target/app.jar              # ← Normalize JAR name for Stage 2

# ══════════════════════════════════════════════════════════════
# Stage 2: RUNTIME — minimal image with just the JRE
# ══════════════════════════════════════════════════════════════
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest

ARG APP_PORT=8080
ARG SERVICE_NAME=order-service

# Labels for image metadata (OCI standard)
LABEL name="${SERVICE_NAME}" \
      version="1.0.0" \
      summary="DevSecOps ${SERVICE_NAME} - Spring Boot microservice" \
      io.k8s.description="Spring Boot microservice for DevSecOps tutorial" \
      io.openshift.expose-services="${APP_PORT}:http"

# Copy the fat JAR from build stage
COPY --from=build --chown=185:0 /home/jboss/target/app.jar /deployments/app.jar

# Environment variables (can be overridden by ConfigMap/Secret)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/urandom"
ENV APP_PORT=${APP_PORT}

# The UBI OpenJDK images run as user 185 (jboss) by default — no USER directive needed
# This makes the image compatible with OpenShift's restricted SCC

EXPOSE ${APP_PORT}

# Spring Boot executable JAR — no application server needed
ENTRYPOINT ["java", \
            "-jar", "/deployments/app.jar", \
            "--server.port=${APP_PORT}"]            # ← Port from build arg / env var
```

> **Why this matters:** The `--chown=185:0` flags ensure all files are owned by the JBoss user (UID 185). OpenShift's default security context constraint (`restricted-v2`) runs containers as a random UID in the `root` group (GID 0). The `:0` group ownership ensures the random UID can read the files. Without this, you get `Permission denied` on the JAR file.

### Push to build-config Repository

The Dockerfile lives in the `build-config` repo alongside the existing .NET `Dockerfile`:

```bash
cd build-config

# Verify the .NET Dockerfile already exists
ls Dockerfile
# --> Dockerfile

# Create the Java Dockerfile (content shown above)
# (Copy the Dockerfile.java content to build-config/Dockerfile.java)

# Commit and push
git add Dockerfile.java
git commit -m "Add multi-stage Dockerfile for Java Spring Boot services"
git push origin main
```

Verify both Dockerfiles exist:

```bash
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/devsecops%2Fbuild-config/repository/tree" \
  | jq -r '.[].name' | grep -i docker
```

Expected:

```
Dockerfile
Dockerfile.java
```

---

## 5. Create GitLab Repos and Jenkins Jobs (15 min)

This section creates 3 GitLab repositories, 3 SonarQube projects, 9 Jenkins jobs, and 9 GitLab webhooks. It is the most configuration-heavy section in the module, but every step follows the exact same pattern established in Modules 8 and 9B.

### Step 1: Create 3 GitLab Repositories

```bash
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)

# Get the devsecops group ID
GROUP_ID=$(curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/groups" | jq -r '.[] | select(.path=="devsecops") | .id')

echo "Group ID: $GROUP_ID"

# ── Create order-service repository ──
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects" \
  -d "name=order-service" \
  -d "namespace_id=${GROUP_ID}" \
  -d "initialize_with_readme=true" \
  -d "description=Java Spring Boot Order Service — DevSecOps tutorial" \
  | jq '{id: .id, path: .path_with_namespace}'

# ── Create inventory-service repository ──
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects" \
  -d "name=inventory-service" \
  -d "namespace_id=${GROUP_ID}" \
  -d "initialize_with_readme=true" \
  -d "description=Java Spring Boot Inventory Service — DevSecOps tutorial" \
  | jq '{id: .id, path: .path_with_namespace}'

# ── Create payment-service repository ──
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects" \
  -d "name=payment-service" \
  -d "namespace_id=${GROUP_ID}" \
  -d "initialize_with_readme=true" \
  -d "description=Java Spring Boot Payment Service — DevSecOps tutorial" \
  | jq '{id: .id, path: .path_with_namespace}'
```

Expected output (project IDs may vary):

```json
{"id": 10, "path": "devsecops/order-service"}
{"id": 11, "path": "devsecops/inventory-service"}
{"id": 12, "path": "devsecops/payment-service"}
```

Verify:

```bash
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/groups/devsecops/projects" | jq -r '.[].path' | sort
```

Expected:

```
app-gitops
app-source
build-config
inventory-service
jenkins-shared-lib
notificationapi-source
order-service
payment-service
```

> **Why this matters:** If the project IDs do not match `env.sh` (order-service=10, inventory-service=11, payment-service=12), update `env.sh` with the actual IDs. The `PipelineConfig.configureForService()` method uses these IDs to report build status back to the correct GitLab project.

### Step 2: Create SonarQube Projects

Each Java service needs its own SonarQube project for SAST analysis:

```bash
SONAR_PASS=$($OC get secret sonarqube-token -n $NS_TOOLS -o jsonpath='{.data.password}' | base64 -d)
SONAR_API="https://sonarqube-${NS_TOOLS}.${APPS_DOMAIN}/api"

# Create order-service project
curl -sk -u "admin:${SONAR_PASS}" -X POST \
  "${SONAR_API}/projects/create" \
  -d "name=order-service" \
  -d "project=order-service" \
  -d "visibility=public"

# Create inventory-service project
curl -sk -u "admin:${SONAR_PASS}" -X POST \
  "${SONAR_API}/projects/create" \
  -d "name=inventory-service" \
  -d "project=inventory-service" \
  -d "visibility=public"

# Create payment-service project
curl -sk -u "admin:${SONAR_PASS}" -X POST \
  "${SONAR_API}/projects/create" \
  -d "name=payment-service" \
  -d "project=payment-service" \
  -d "visibility=public"

echo "--- SonarQube projects created ---"
```

Set the quality gate for all three projects:

```bash
# Get the DevSecOps quality gate ID (created in Module 5)
QG_ID=$(curl -sk -u "admin:${SONAR_PASS}" \
  "${SONAR_API}/qualitygates/list" \
  | jq -r '.qualitygates[] | select(.name=="DevSecOps") | .id')
echo "Quality Gate ID: $QG_ID"

# Apply the quality gate to each Java project
for SVC in order-service inventory-service payment-service; do
  curl -sk -u "admin:${SONAR_PASS}" -X POST \
    "${SONAR_API}/qualitygates/select" \
    -d "gateName=DevSecOps" \
    -d "projectKey=${SVC}"
  echo "  Quality gate set for: ${SVC}"
done
```

Verify:

```bash
curl -sk -u "admin:${SONAR_PASS}" \
  "${SONAR_API}/projects/search" \
  | jq -r '.components[] | select(.key | test("service")) | .key'
```

Expected:

```
order-service
inventory-service
payment-service
```

### Step 3: Add Jenkins Job Definitions (JCasC)

The 9 new Jenkins jobs follow the exact same pattern as the .NET jobs. Each service gets 3 jobs (MR, merge, tag). The only difference is the `service` parameter passed to the shared library orchestrator.

Add these job definitions to your Jenkins CasC ConfigMap:

```yaml
# infra/jenkins/java-jobs-casc.yaml
# 9 Jenkins job definitions for Java microservices
# Pattern: 3 services x 3 triggers = 9 jobs
# All jobs call the SAME shared library orchestrators with service parameter
---
# ══════════════════════════════════════════════════════════════
# ORDER-SERVICE (project ID 10, port 8080)
# ══════════════════════════════════════════════════════════════

# ── T1: Merge Request validation ──
- script: |
    pipelineJob('order-service-mr') {
      description('T1: Order Service MR Validation — Java Spring Boot')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')               // ← Required for MR status reporting
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(true)           // ← Fire on MR open/update
          buildOnPushEvents(false)
          buildOnNoteEvents(false)
          rebuildOpenMergeRequest('source')
          targetBranchRegex('main')
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineMR(service: 'order-service')    // ← Routes to Java build/test/scan
          """.stripIndent())
          sandbox(true)
        }
      }
    }

# ── T2: Merge to main — build image, deploy DEV ──
- script: |
    pipelineJob('order-service-merge') {
      description('T2: Order Service Merge — Build + Deploy to javaapp-dev')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(true)                   // ← Fire on push to main
          buildOnNoteEvents(false)
          includeBranches('main')
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineMerge(service: 'order-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

# ── T3: Tag push — release-ready image ──
- script: |
    pipelineJob('order-service-tag') {
      description('T3: Order Service Tag — Full validation + release image')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(false)
          buildOnTagPushEvents(true)                // ← Fire on tag push
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineTag(service: 'order-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

# ══════════════════════════════════════════════════════════════
# INVENTORY-SERVICE (project ID 11, port 8081)
# ══════════════════════════════════════════════════════════════

- script: |
    pipelineJob('inventory-service-mr') {
      description('T1: Inventory Service MR Validation — Java Spring Boot')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(true)
          buildOnPushEvents(false)
          buildOnNoteEvents(false)
          rebuildOpenMergeRequest('source')
          targetBranchRegex('main')
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineMR(service: 'inventory-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

- script: |
    pipelineJob('inventory-service-merge') {
      description('T2: Inventory Service Merge — Build + Deploy to javaapp-dev')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(true)
          buildOnNoteEvents(false)
          includeBranches('main')
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineMerge(service: 'inventory-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

- script: |
    pipelineJob('inventory-service-tag') {
      description('T3: Inventory Service Tag — Full validation + release image')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(false)
          buildOnTagPushEvents(true)
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineTag(service: 'inventory-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

# ══════════════════════════════════════════════════════════════
# PAYMENT-SERVICE (project ID 12, port 8082)
# ══════════════════════════════════════════════════════════════

- script: |
    pipelineJob('payment-service-mr') {
      description('T1: Payment Service MR Validation — Java Spring Boot')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(true)
          buildOnPushEvents(false)
          buildOnNoteEvents(false)
          rebuildOpenMergeRequest('source')
          targetBranchRegex('main')
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineMR(service: 'payment-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

- script: |
    pipelineJob('payment-service-merge') {
      description('T2: Payment Service Merge — Build + Deploy to javaapp-dev')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(true)
          buildOnNoteEvents(false)
          includeBranches('main')
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineMerge(service: 'payment-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }

- script: |
    pipelineJob('payment-service-tag') {
      description('T3: Payment Service Tag — Full validation + release image')
      properties {
        gitLabConnectionProperty {
          gitLabConnection('gitlab')
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(false)
          buildOnTagPushEvents(true)
        }
      }
      definition {
        cps {
          script("""
            @Library('devsecops-shared-lib@main') _
            pipelineTag(service: 'payment-service')
          """.stripIndent())
          sandbox(true)
        }
      }
    }
```

Apply the updated JCasC ConfigMap:

```bash
# Update the Jenkins CasC ConfigMap with the new job definitions
$OC apply -f infra/jenkins/java-jobs-casc.yaml -n $NS_TOOLS

# Reload Jenkins configuration (no restart needed with CasC)
JENKINS_CRUMB=$(curl -sk -u admin:$JENKINS_PASS \
  "${JENKINS_URL}/crumbIssuer/api/json" | jq -r '.crumb')

curl -sk -X POST -u admin:$JENKINS_PASS \
  -H "Jenkins-Crumb: $JENKINS_CRUMB" \
  "${JENKINS_URL}/configuration-as-code/reload"

echo "Jenkins configuration reloaded"
```

Verify all 16 Jenkins jobs exist (7 existing + 9 new):

```bash
curl -sk -u admin:$JENKINS_PASS \
  "${JENKINS_URL}/api/json?tree=jobs[name]" \
  | jq -r '.jobs[].name' | sort
```

Expected:

```
inventory-service-merge
inventory-service-mr
inventory-service-tag
notificationapi-merge
notificationapi-mr
notificationapi-tag
order-service-merge
order-service-mr
order-service-tag
payment-service-merge
payment-service-mr
payment-service-tag
sampleapi-merge
sampleapi-mr
sampleapi-promote
sampleapi-tag
```

16 jobs total: 3 per .NET service (6) + 3 per Java service (9) + 1 shared promote = 16.

### Step 4: Create GitLab Webhooks

Each Java service repo needs 3 webhooks (one per trigger), just like the .NET services. That is 9 new webhooks:

```bash
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)

# ══════════════════════════════════════════════════════════════
# ORDER-SERVICE webhooks (project ID $GITLAB_PROJECT_ORDER_SERVICE)
# ══════════════════════════════════════════════════════════════

# T1: MR events → order-service-mr
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/order-service-mr" \
  -d "merge_requests_events=true" \
  -d "push_events=false" \
  -d "tag_push_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: order-service T1 (MR) webhook"

# T2: Push to main → order-service-merge
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/order-service-merge" \
  -d "push_events=true" \
  -d "push_events_branch_filter=main" \
  -d "merge_requests_events=false" \
  -d "tag_push_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: order-service T2 (merge) webhook"

# T3: Tag push → order-service-tag
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/order-service-tag" \
  -d "tag_push_events=true" \
  -d "push_events=false" \
  -d "merge_requests_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: order-service T3 (tag) webhook"

# ══════════════════════════════════════════════════════════════
# INVENTORY-SERVICE webhooks (project ID $GITLAB_PROJECT_INVENTORY_SERVICE)
# ══════════════════════════════════════════════════════════════

# T1: MR events → inventory-service-mr
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_INVENTORY_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/inventory-service-mr" \
  -d "merge_requests_events=true" \
  -d "push_events=false" \
  -d "tag_push_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: inventory-service T1 (MR) webhook"

# T2: Push to main → inventory-service-merge
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_INVENTORY_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/inventory-service-merge" \
  -d "push_events=true" \
  -d "push_events_branch_filter=main" \
  -d "merge_requests_events=false" \
  -d "tag_push_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: inventory-service T2 (merge) webhook"

# T3: Tag push → inventory-service-tag
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_INVENTORY_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/inventory-service-tag" \
  -d "tag_push_events=true" \
  -d "push_events=false" \
  -d "merge_requests_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: inventory-service T3 (tag) webhook"

# ══════════════════════════════════════════════════════════════
# PAYMENT-SERVICE webhooks (project ID $GITLAB_PROJECT_PAYMENT_SERVICE)
# ══════════════════════════════════════════════════════════════

# T1: MR events → payment-service-mr
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_PAYMENT_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/payment-service-mr" \
  -d "merge_requests_events=true" \
  -d "push_events=false" \
  -d "tag_push_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: payment-service T1 (MR) webhook"

# T2: Push to main → payment-service-merge
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_PAYMENT_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/payment-service-merge" \
  -d "push_events=true" \
  -d "push_events_branch_filter=main" \
  -d "merge_requests_events=false" \
  -d "tag_push_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: payment-service T2 (merge) webhook"

# T3: Tag push → payment-service-tag
curl -sk -X POST -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_PAYMENT_SERVICE}/hooks" \
  -d "url=${JENKINS_URL}/project/payment-service-tag" \
  -d "tag_push_events=true" \
  -d "push_events=false" \
  -d "merge_requests_events=false" \
  -d "enable_ssl_verification=false"
echo "  Created: payment-service T3 (tag) webhook"
```

Verify all webhooks:

```bash
echo "=== order-service webhooks (project ${GITLAB_PROJECT_ORDER_SERVICE}) ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_ORDER_SERVICE}/hooks" \
  | jq -r '.[].url'

echo ""
echo "=== inventory-service webhooks (project ${GITLAB_PROJECT_INVENTORY_SERVICE}) ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_INVENTORY_SERVICE}/hooks" \
  | jq -r '.[].url'

echo ""
echo "=== payment-service webhooks (project ${GITLAB_PROJECT_PAYMENT_SERVICE}) ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/projects/${GITLAB_PROJECT_PAYMENT_SERVICE}/hooks" \
  | jq -r '.[].url'
```

Expected (3 webhooks per repo):

```
=== order-service webhooks (project 10) ===
https://jenkins-devsecops-tools.apps.../project/order-service-mr
https://jenkins-devsecops-tools.apps.../project/order-service-merge
https://jenkins-devsecops-tools.apps.../project/order-service-tag

=== inventory-service webhooks (project 11) ===
https://jenkins-devsecops-tools.apps.../project/inventory-service-mr
https://jenkins-devsecops-tools.apps.../project/inventory-service-merge
https://jenkins-devsecops-tools.apps.../project/inventory-service-tag

=== payment-service webhooks (project 12) ===
https://jenkins-devsecops-tools.apps.../project/payment-service-mr
https://jenkins-devsecops-tools.apps.../project/payment-service-merge
https://jenkins-devsecops-tools.apps.../project/payment-service-tag
```

### Complete Webhook Map

Here is the full webhook map with all services (existing + new):

```
GitLab repo: app-source (project ID 1) — SampleApi (.NET)
  Webhook 1: MR events       → https://jenkins.../project/sampleapi-mr
  Webhook 2: Push to main    → https://jenkins.../project/sampleapi-merge
  Webhook 3: Tag push        → https://jenkins.../project/sampleapi-tag

GitLab repo: notificationapi-source (project ID 5) — NotificationApi (.NET)
  Webhook 4: MR events       → https://jenkins.../project/notificationapi-mr
  Webhook 5: Push to main    → https://jenkins.../project/notificationapi-merge
  Webhook 6: Tag push        → https://jenkins.../project/notificationapi-tag

GitLab repo: app-gitops (project ID 4) — Shared GitOps manifests
  Webhook 7: Push to main    → https://jenkins.../project/sampleapi-promote

GitLab repo: order-service (project ID 10) — Order Service (Java)
  Webhook 8:  MR events      → https://jenkins.../project/order-service-mr
  Webhook 9:  Push to main   → https://jenkins.../project/order-service-merge
  Webhook 10: Tag push       → https://jenkins.../project/order-service-tag

GitLab repo: inventory-service (project ID 11) — Inventory Service (Java)
  Webhook 11: MR events      → https://jenkins.../project/inventory-service-mr
  Webhook 12: Push to main   → https://jenkins.../project/inventory-service-merge
  Webhook 13: Tag push       → https://jenkins.../project/inventory-service-tag

GitLab repo: payment-service (project ID 12) — Payment Service (Java)
  Webhook 14: MR events      → https://jenkins.../project/payment-service-mr
  Webhook 15: Push to main   → https://jenkins.../project/payment-service-merge
  Webhook 16: Tag push       → https://jenkins.../project/payment-service-tag
```

16 webhooks total. Each webhook is a one-to-one mapping: one Git event type on one repo triggers one Jenkins job. No ambiguity, no guessing.

---

## 6. Create GitOps Structure for Java Services (10 min)

### GitOps Directory Layout

The Java services follow the exact same GitOps structure as the .NET services. Each service gets a `base/` + `overlays/{env}/` directory under `app-gitops/services/`:

```
app-gitops/
├── services/
│   ├── sampleapi/                   # ← Existing (.NET)
│   ├── notificationapi/             # ← Existing (.NET)
│   ├── order-service/               # ← NEW (Java)
│   │   ├── base/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── route.yaml           # ← Only order-service has an external Route
│   │   └── overlays/
│   │       ├── dev/
│   │       │   ├── kustomization.yaml
│   │       │   ├── configmap-env.yaml
│   │       │   ├── secret-env.yaml
│   │       │   └── patch-deployment.yaml
│   │       ├── sit/
│   │       ├── uat/
│   │       └── production/
│   ├── inventory-service/           # ← NEW (Java, NO Route)
│   │   ├── base/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   └── service.yaml         # ← ClusterIP only, no Route
│   │   └── overlays/
│   │       ├── dev/
│   │       ├── sit/
│   │       ├── uat/
│   │       └── production/
│   └── payment-service/             # ← NEW (Java, NO Route)
│       ├── base/
│       │   ├── kustomization.yaml
│       │   ├── deployment.yaml
│       │   └── service.yaml         # ← ClusterIP only, no Route
│       └── overlays/
│           ├── dev/
│           ├── sit/
│           ├── uat/
│           └── production/
├── infra/                           # ← Existing (.NET infra)
├── infra-javaapp/                   # ← NEW (Java infra)
│   ├── base/
│   └── overlays/
├── argocd/                          # ← Add 16 new Application CRDs here
└── README.md
```

### Step 1: Create order-service Base Manifests

```yaml
# app-gitops/services/order-service/base/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - deployment.yaml
  - service.yaml
  - route.yaml                                     # ← Only order-service gets a Route

commonLabels:
  app: order-service
  app.kubernetes.io/part-of: javaapp
  app.kubernetes.io/component: api-gateway
```

```yaml
# app-gitops/services/order-service/base/deployment.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  labels:
    app: order-service
spec:
  replicas: 1                                      # ← Overridden per environment
  selector:
    matchLabels:
      app: order-service
  template:
    metadata:
      labels:
        app: order-service
        app.kubernetes.io/part-of: javaapp         # ← Used by NetworkPolicy selectors
    spec:
      containers:
        - name: order-service
          image: image-registry.openshift-image-registry.svc:5000/javaapp-dev/order-service:latest
          ports:
            - containerPort: 8080
              name: http
              protocol: TCP
          envFrom:
            - configMapRef:
                name: order-service-env            # ← Non-secret config (DB URL, feature flags)
            - secretRef:
                name: order-service-secret          # ← Secrets (DB password)
          resources:
            requests:
              cpu: 100m
              memory: 256Mi                        # ← Java apps need more baseline memory
            limits:
              cpu: 500m
              memory: 512Mi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness      # ← Spring Boot Actuator, NOT /healthz
              port: 8080
            initialDelaySeconds: 30                # ← Java apps take longer to start
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness     # ← Spring Boot Actuator
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 5
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: /actuator/health               # ← Gives JVM time for first startup
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 30                   # ← 30 x 5s = 150s max startup time
```

> **Why this matters:** Java applications take significantly longer to start than .NET applications. The `startupProbe` gives the JVM up to 150 seconds to initialize. Without it, the liveness probe would kill the pod before it finishes starting, causing an infinite `CrashLoopBackOff`.

```yaml
# app-gitops/services/order-service/base/service.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: order-service
  labels:
    app: order-service
spec:
  ports:
    - port: 8080
      targetPort: 8080
      name: http
      protocol: TCP
  selector:
    app: order-service
  type: ClusterIP
```

```yaml
# app-gitops/services/order-service/base/route.yaml
# External Route — only order-service is exposed outside the cluster
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: order-service
  labels:
    app: order-service
spec:
  port:
    targetPort: http
  tls:
    termination: edge                              # ← TLS at the router, HTTP to the pod
    insecureEdgeTerminationPolicy: Redirect
  to:
    kind: Service
    name: order-service
    weight: 100
```

### Step 2: Create order-service DEV Overlay

```yaml
# app-gitops/services/order-service/overlays/dev/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: javaapp-dev                             # ← All resources go here

resources:
  - ../../base
  - configmap-env.yaml
  - secret-env.yaml

patches:
  - path: patch-deployment.yaml

images:
  - name: image-registry.openshift-image-registry.svc:5000/javaapp-dev/order-service
    newTag: latest                                 # ← Updated by updateGitOps.groovy
```

```yaml
# app-gitops/services/order-service/overlays/dev/configmap-env.yaml
# Non-secret configuration for order-service in DEV
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-env
data:
  SPRING_PROFILES_ACTIVE: "dev"                    # ← Activates DEV Spring profile
  SERVICE_NAME: "order-service"
  APP_PORT: "8080"
  # Database connection (password is in the Secret, not here)
  DATABASE_URL: "jdbc:postgresql://postgresql.javaapp-dev.svc:5432/orderdb"
  DATABASE_USER: "javaapp"
  # Inter-service URLs (internal DNS)
  INVENTORY_SERVICE_URL: "http://inventory-service.javaapp-dev.svc:8081"
  PAYMENT_SERVICE_URL: "http://payment-service.javaapp-dev.svc:8082"
  # Logging
  LOGGING_LEVEL_ROOT: "INFO"
  LOGGING_LEVEL_COM_DEVSECOPS: "DEBUG"             # ← Verbose logging for our code in DEV
  # Features
  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,info,prometheus,env"
```

```yaml
# app-gitops/services/order-service/overlays/dev/secret-env.yaml
# Secrets for order-service in DEV
# NOTE: In production, use SealedSecrets or ExternalSecrets
---
apiVersion: v1
kind: Secret
metadata:
  name: order-service-secret
type: Opaque
stringData:
  DATABASE_PASSWORD: "javaapp-secret-2026"         # ← Must match infra-javaapp-secret
  REDIS_PASSWORD: "redis-java-secret-2026"
```

```yaml
# app-gitops/services/order-service/overlays/dev/patch-deployment.yaml
# DEV-specific deployment overrides
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  replicas: 1                                      # ← Single replica in DEV
  template:
    spec:
      containers:
        - name: order-service
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          env:
            - name: JAVA_OPTS                      # ← Reduced heap for DEV
              value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0"
```

### Step 3: Create inventory-service Base Manifests

Inventory-service is an internal service -- no Route:

```yaml
# app-gitops/services/inventory-service/base/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - deployment.yaml
  - service.yaml
  # NO route.yaml — inventory-service is internal only

commonLabels:
  app: inventory-service
  app.kubernetes.io/part-of: javaapp
  app.kubernetes.io/component: internal-service
```

```yaml
# app-gitops/services/inventory-service/base/deployment.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-service
  labels:
    app: inventory-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: inventory-service
  template:
    metadata:
      labels:
        app: inventory-service
        app.kubernetes.io/part-of: javaapp
        app.kubernetes.io/part-of: javaapp-internal   # ← Used by NetworkPolicy
    spec:
      containers:
        - name: inventory-service
          image: image-registry.openshift-image-registry.svc:5000/javaapp-dev/inventory-service:latest
          ports:
            - containerPort: 8081                  # ← Different port than order-service
              name: http
              protocol: TCP
          envFrom:
            - configMapRef:
                name: inventory-service-env
            - secretRef:
                name: inventory-service-secret
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081                           # ← Must match containerPort
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 20
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 30
```

```yaml
# app-gitops/services/inventory-service/base/service.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: inventory-service
  labels:
    app: inventory-service
spec:
  ports:
    - port: 8081
      targetPort: 8081
      name: http
      protocol: TCP
  selector:
    app: inventory-service
  type: ClusterIP                                  # ← Internal only, no NodePort/LoadBalancer
```

Create the DEV overlay:

```yaml
# app-gitops/services/inventory-service/overlays/dev/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: javaapp-dev

resources:
  - ../../base
  - configmap-env.yaml
  - secret-env.yaml

patches:
  - path: patch-deployment.yaml

images:
  - name: image-registry.openshift-image-registry.svc:5000/javaapp-dev/inventory-service
    newTag: latest
```

```yaml
# app-gitops/services/inventory-service/overlays/dev/configmap-env.yaml
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: inventory-service-env
data:
  SPRING_PROFILES_ACTIVE: "dev"
  SERVICE_NAME: "inventory-service"
  APP_PORT: "8081"
  DATABASE_URL: "jdbc:postgresql://postgresql.javaapp-dev.svc:5432/inventorydb"
  DATABASE_USER: "javaapp"
  LOGGING_LEVEL_ROOT: "INFO"
  LOGGING_LEVEL_COM_DEVSECOPS: "DEBUG"
  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,info,prometheus"
```

```yaml
# app-gitops/services/inventory-service/overlays/dev/secret-env.yaml
---
apiVersion: v1
kind: Secret
metadata:
  name: inventory-service-secret
type: Opaque
stringData:
  DATABASE_PASSWORD: "javaapp-secret-2026"
  REDIS_PASSWORD: "redis-java-secret-2026"
```

```yaml
# app-gitops/services/inventory-service/overlays/dev/patch-deployment.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-service
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: inventory-service
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

### Step 4: Create payment-service Base Manifests

Payment-service is also internal -- same pattern as inventory-service, different port:

```yaml
# app-gitops/services/payment-service/base/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - deployment.yaml
  - service.yaml
  # NO route.yaml — payment-service is internal only

commonLabels:
  app: payment-service
  app.kubernetes.io/part-of: javaapp
  app.kubernetes.io/component: internal-service
```

```yaml
# app-gitops/services/payment-service/base/deployment.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment-service
  labels:
    app: payment-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: payment-service
  template:
    metadata:
      labels:
        app: payment-service
        app.kubernetes.io/part-of: javaapp
        app.kubernetes.io/part-of: javaapp-internal
    spec:
      containers:
        - name: payment-service
          image: image-registry.openshift-image-registry.svc:5000/javaapp-dev/payment-service:latest
          ports:
            - containerPort: 8082                  # ← Port 8082 for payment-service
              name: http
              protocol: TCP
          envFrom:
            - configMapRef:
                name: payment-service-env
            - secretRef:
                name: payment-service-secret
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8082
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8082
            initialDelaySeconds: 20
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8082
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 30
```

```yaml
# app-gitops/services/payment-service/base/service.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: payment-service
  labels:
    app: payment-service
spec:
  ports:
    - port: 8082
      targetPort: 8082
      name: http
      protocol: TCP
  selector:
    app: payment-service
  type: ClusterIP
```

Create the DEV overlay:

```yaml
# app-gitops/services/payment-service/overlays/dev/kustomization.yaml
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: javaapp-dev

resources:
  - ../../base
  - configmap-env.yaml
  - secret-env.yaml

patches:
  - path: patch-deployment.yaml

images:
  - name: image-registry.openshift-image-registry.svc:5000/javaapp-dev/payment-service
    newTag: latest
```

```yaml
# app-gitops/services/payment-service/overlays/dev/configmap-env.yaml
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: payment-service-env
data:
  SPRING_PROFILES_ACTIVE: "dev"
  SERVICE_NAME: "payment-service"
  APP_PORT: "8082"
  DATABASE_URL: "jdbc:postgresql://postgresql.javaapp-dev.svc:5432/paymentdb"
  DATABASE_USER: "javaapp"
  LOGGING_LEVEL_ROOT: "INFO"
  LOGGING_LEVEL_COM_DEVSECOPS: "DEBUG"
  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,info,prometheus"
```

```yaml
# app-gitops/services/payment-service/overlays/dev/secret-env.yaml
---
apiVersion: v1
kind: Secret
metadata:
  name: payment-service-secret
type: Opaque
stringData:
  DATABASE_PASSWORD: "javaapp-secret-2026"
  REDIS_PASSWORD: "redis-java-secret-2026"
```

```yaml
# app-gitops/services/payment-service/overlays/dev/patch-deployment.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment-service
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: payment-service
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

### Step 5: Create SIT/UAT/PROD Overlays for All Services

Each higher environment increases replicas and resources. Create them with a script:

```bash
# Create SIT/UAT/PROD overlays for all 3 Java services
for SVC in order-service inventory-service payment-service; do
  # Determine the port for this service
  case $SVC in
    order-service)     PORT=8080 ;;
    inventory-service) PORT=8081 ;;
    payment-service)   PORT=8082 ;;
  esac

  for ENV in sit uat prod; do
    # Determine replicas per environment
    case $ENV in
      sit)  REPLICAS=2; CPU_REQ="200m"; MEM_REQ="384Mi"; CPU_LIM="1"; MEM_LIM="1Gi" ;;
      uat)  REPLICAS=2; CPU_REQ="200m"; MEM_REQ="384Mi"; CPU_LIM="1"; MEM_LIM="1Gi" ;;
      prod) REPLICAS=3; CPU_REQ="500m"; MEM_REQ="512Mi"; CPU_LIM="2"; MEM_LIM="2Gi" ;;
    esac

    DIR="app-gitops/services/${SVC}/overlays/${ENV}"
    mkdir -p "${DIR}"

    # kustomization.yaml
    cat > "${DIR}/kustomization.yaml" << KEOF
---
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: javaapp-${ENV}

resources:
  - ../../base
  - configmap-env.yaml
  - secret-env.yaml

patches:
  - path: patch-deployment.yaml

images:
  - name: image-registry.openshift-image-registry.svc:5000/javaapp-dev/${SVC}
    newTag: latest
KEOF

    # configmap-env.yaml
    cat > "${DIR}/configmap-env.yaml" << CEOF
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: ${SVC}-env
data:
  SPRING_PROFILES_ACTIVE: "${ENV}"
  SERVICE_NAME: "${SVC}"
  APP_PORT: "${PORT}"
  DATABASE_URL: "jdbc:postgresql://postgresql.javaapp-${ENV}.svc:5432/${SVC//-/}db"
  DATABASE_USER: "javaapp"
  INVENTORY_SERVICE_URL: "http://inventory-service.javaapp-${ENV}.svc:8081"
  PAYMENT_SERVICE_URL: "http://payment-service.javaapp-${ENV}.svc:8082"
  LOGGING_LEVEL_ROOT: "WARN"
  LOGGING_LEVEL_COM_DEVSECOPS: "INFO"
  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,info,prometheus"
CEOF

    # secret-env.yaml
    cat > "${DIR}/secret-env.yaml" << SEOF
---
apiVersion: v1
kind: Secret
metadata:
  name: ${SVC}-secret
type: Opaque
stringData:
  DATABASE_PASSWORD: "javaapp-${ENV}-2026"
  REDIS_PASSWORD: "redis-java-${ENV}-2026"
SEOF

    # patch-deployment.yaml
    cat > "${DIR}/patch-deployment.yaml" << PEOF
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${SVC}
spec:
  replicas: ${REPLICAS}
  template:
    spec:
      containers:
        - name: ${SVC}
          resources:
            requests:
              cpu: ${CPU_REQ}
              memory: ${MEM_REQ}
            limits:
              cpu: "${CPU_LIM}"
              memory: ${MEM_LIM}
PEOF

    echo "  Created overlay: ${SVC}/${ENV}"
  done
done
```

Verify the directory structure:

```bash
find app-gitops/services/order-service -type f | sort
find app-gitops/services/inventory-service -type f | sort
find app-gitops/services/payment-service -type f | sort
```

Expected (for each service):

```
app-gitops/services/order-service/base/deployment.yaml
app-gitops/services/order-service/base/kustomization.yaml
app-gitops/services/order-service/base/route.yaml
app-gitops/services/order-service/base/service.yaml
app-gitops/services/order-service/overlays/dev/configmap-env.yaml
app-gitops/services/order-service/overlays/dev/kustomization.yaml
app-gitops/services/order-service/overlays/dev/patch-deployment.yaml
app-gitops/services/order-service/overlays/dev/secret-env.yaml
app-gitops/services/order-service/overlays/production/configmap-env.yaml
...
app-gitops/services/order-service/overlays/sit/...
app-gitops/services/order-service/overlays/uat/...
```

### Step 6: Create ArgoCD Application CRDs

We need 16 new ArgoCD Application CRDs:
- 4 per Java service (dev, sit, uat, prod) = 12
- 4 for Java infrastructure (dev, sit, uat, prod) = 4

```yaml
# app-gitops/argocd/order-service-dev.yaml
# ArgoCD Application for order-service in DEV — auto-sync
---
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: order-service-dev
  namespace: openshift-gitops
  labels:
    app.kubernetes.io/part-of: javaapp
    environment: dev
  finalizers:
    - resources-finalizer.argocd.argoproj.io        # ← Cleanup resources when app is deleted
spec:
  project: devsecops                                # ← Must match your AppProject
  source:
    repoURL: https://gitlab-devsecops-gitlab.${APPS_DOMAIN}/devsecops/app-gitops.git
    targetRevision: main
    path: services/order-service/overlays/dev        # ← Points to the DEV overlay
  destination:
    server: https://kubernetes.default.svc
    namespace: javaapp-dev                           # ← Target namespace
  syncPolicy:
    automated:                                       # ← DEV auto-syncs from Git
      prune: true                                    # ← Delete resources removed from Git
      selfHeal: true                                 # ← Revert manual changes
    syncOptions:
      - CreateNamespace=false                        # ← Namespace already exists
      - PruneLast=true
```

Create the remaining ArgoCD apps using a script:

```bash
# Generate ArgoCD Application CRDs for all Java services + infra
GITOPS_REPO="https://gitlab-${NS_GITLAB}.${APPS_DOMAIN}/devsecops/app-gitops.git"

# ── Service Applications (3 services x 4 envs = 12 apps) ──
for SVC in order-service inventory-service payment-service; do
  for ENV in dev sit uat prod; do
    # DEV gets auto-sync, others get manual sync
    if [ "$ENV" = "dev" ]; then
      SYNC_POLICY='syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=false
      - PruneLast=true'
    else
      SYNC_POLICY='syncPolicy:
    syncOptions:
      - CreateNamespace=false
      - PruneLast=true'
    fi

    cat > "app-gitops/argocd/${SVC}-${ENV}.yaml" << AEOF
# ArgoCD Application for ${SVC} in ${ENV}
---
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: ${SVC}-${ENV}
  namespace: openshift-gitops
  labels:
    app.kubernetes.io/part-of: javaapp
    environment: ${ENV}
    service: ${SVC}
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: devsecops
  source:
    repoURL: ${GITOPS_REPO}
    targetRevision: main
    path: services/${SVC}/overlays/${ENV}
  destination:
    server: https://kubernetes.default.svc
    namespace: javaapp-${ENV}
  ${SYNC_POLICY}
AEOF

    echo "  Created: ${SVC}-${ENV}.yaml"
  done
done

# ── Infrastructure Applications (4 envs) ──
for ENV in dev sit uat prod; do
  if [ "$ENV" = "dev" ]; then
    SYNC_POLICY='syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=false
      - PruneLast=true'
  else
    SYNC_POLICY='syncPolicy:
    syncOptions:
      - CreateNamespace=false
      - PruneLast=true'
  fi

  cat > "app-gitops/argocd/infra-javaapp-${ENV}.yaml" << IEOF
# ArgoCD Application for Java infrastructure in ${ENV}
---
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: infra-javaapp-${ENV}
  namespace: openshift-gitops
  labels:
    app.kubernetes.io/part-of: javaapp-infra
    environment: ${ENV}
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: devsecops
  source:
    repoURL: ${GITOPS_REPO}
    targetRevision: main
    path: infra-javaapp/overlays/${ENV}
  destination:
    server: https://kubernetes.default.svc
    namespace: javaapp-${ENV}
  ${SYNC_POLICY}
IEOF

  echo "  Created: infra-javaapp-${ENV}.yaml"
done
```

Verify all ArgoCD app CRDs exist:

```bash
ls app-gitops/argocd/*java* app-gitops/argocd/order* \
   app-gitops/argocd/inventory* app-gitops/argocd/payment* 2>/dev/null | sort
```

Expected:

```
app-gitops/argocd/infra-javaapp-dev.yaml
app-gitops/argocd/infra-javaapp-prod.yaml
app-gitops/argocd/infra-javaapp-sit.yaml
app-gitops/argocd/infra-javaapp-uat.yaml
app-gitops/argocd/inventory-service-dev.yaml
app-gitops/argocd/inventory-service-prod.yaml
app-gitops/argocd/inventory-service-sit.yaml
app-gitops/argocd/inventory-service-uat.yaml
app-gitops/argocd/order-service-dev.yaml
app-gitops/argocd/order-service-prod.yaml
app-gitops/argocd/order-service-sit.yaml
app-gitops/argocd/order-service-uat.yaml
app-gitops/argocd/payment-service-dev.yaml
app-gitops/argocd/payment-service-prod.yaml
app-gitops/argocd/payment-service-sit.yaml
app-gitops/argocd/payment-service-uat.yaml
```

16 new ArgoCD Application CRDs.

### Step 7: Commit and Push GitOps Changes

```bash
cd app-gitops

git add services/order-service/ services/inventory-service/ services/payment-service/
git add infra-javaapp/
git add argocd/

git commit -m "Add Java microservices: order-service, inventory-service, payment-service

- 3 services with base + 4 environment overlays each
- Shared infrastructure (PostgreSQL + Redis) for javaapp namespaces
- 16 ArgoCD Application CRDs (12 service + 4 infra)
- NetworkPolicies with zero-trust baseline"

git push origin main
```

### Step 8: Apply ArgoCD Applications

```bash
# Apply the AppProject update (add javaapp namespaces to allowed destinations)
$OC apply -f app-gitops/argocd/project.yaml

# Apply all 16 new ArgoCD Applications
$OC apply -f app-gitops/argocd/

# Wait for initial sync
echo "Waiting 30 seconds for initial sync..."
sleep 30
```

Verify all ArgoCD Applications:

```bash
$OC get applications -n $NS_GITOPS \
  -l app.kubernetes.io/part-of=javaapp \
  -o custom-columns='NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status'
```

Expected:

```
NAME                       SYNC     HEALTH
order-service-dev          Synced   Healthy
order-service-sit          Synced   Healthy
order-service-uat          Synced   Healthy
order-service-prod         Synced   Healthy
inventory-service-dev      Synced   Healthy
inventory-service-sit      Synced   Healthy
inventory-service-uat      Synced   Healthy
inventory-service-prod     Synced   Healthy
payment-service-dev        Synced   Healthy
payment-service-sit        Synced   Healthy
payment-service-uat        Synced   Healthy
payment-service-prod       Synced   Healthy
```

```bash
$OC get applications -n $NS_GITOPS \
  -l app.kubernetes.io/part-of=javaapp-infra \
  -o custom-columns='NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status'
```

Expected:

```
NAME                       SYNC     HEALTH
infra-javaapp-dev          Synced   Healthy
infra-javaapp-sit          Synced   Healthy
infra-javaapp-uat          Synced   Healthy
infra-javaapp-prod         Synced   Healthy
```

### Total ArgoCD Application Count

```bash
$OC get applications -n $NS_GITOPS --no-headers | wc -l
```

Expected: **28** (12 existing .NET + 16 new Java).

```
.NET ecosystem:
  sampleapi-{dev,sit,uat,prod}         = 4
  notificationapi-{dev,sit,uat,prod}   = 4
  infra-{dev,sit,uat,prod}             = 4
                                        ──
                                        12

Java ecosystem:
  order-service-{dev,sit,uat,prod}     = 4
  inventory-service-{dev,sit,uat,prod} = 4
  payment-service-{dev,sit,uat,prod}   = 4
  infra-javaapp-{dev,sit,uat,prod}     = 4
                                        ──
                                        16

Total: 28
```

---

## 7. Run Your First Java Pipeline (15 min)

This is the payoff -- pushing Java code and watching the exact same pipeline infrastructure that builds .NET services now build, scan, and deploy a Java service.

### Step 1: Push Order Service Source Code

Clone the order-service repository and add the Spring Boot application code:

```bash
GITLAB_TOKEN=$($OC get secret gitlab-token -n $NS_TOOLS -o jsonpath='{.data.token}' | base64 -d)

# Clone the order-service repo
git clone https://root:${GITLAB_TOKEN}@$(echo $GITLAB_URL | sed 's|https://||')/devsecops/order-service.git
cd order-service
```

Create the Maven wrapper (so the build does not require Maven pre-installed):

```bash
# Create the Maven wrapper files
mkdir -p .mvn/wrapper

cat > .mvn/wrapper/maven-wrapper.properties << 'MEOF'
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.8/apache-maven-3.9.8-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
MEOF
```

Create the `pom.xml`:

```bash
cat > pom.xml << 'POMEOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.devsecops</groupId>
    <artifactId>order-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <name>order-service</name>
    <description>DevSecOps Order Service - Spring Boot microservice</description>

    <properties>
        <java.version>21</java.version>
        <sonar.java.source>21</sonar.java.source>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.cyclonedx</groupId>
                <artifactId>cyclonedx-maven-plugin</artifactId>
                <version>2.8.2</version>
            </plugin>
        </plugins>
    </build>
</project>
POMEOF
```

Create the main application class:

```bash
mkdir -p src/main/java/com/devsecops/orderservice

cat > src/main/java/com/devsecops/orderservice/OrderServiceApplication.java << 'JEOF'
package com.devsecops.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
JEOF
```

Create the REST controller:

```bash
mkdir -p src/main/java/com/devsecops/orderservice/controller

cat > src/main/java/com/devsecops/orderservice/controller/OrderController.java << 'JEOF'
package com.devsecops.orderservice.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {

    @Value("${SERVICE_NAME:order-service}")
    private String serviceName;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @GetMapping("/orders")
    public Map<String, Object> getOrders() {
        return Map.of(
            "service", serviceName,
            "profile", activeProfile,
            "timestamp", Instant.now().toString(),
            "orders", java.util.List.of(
                Map.of("id", 1, "item", "Widget", "quantity", 5, "status", "PENDING"),
                Map.of("id", 2, "item", "Gadget", "quantity", 3, "status", "CONFIRMED")
            )
        );
    }

    @GetMapping("/info")
    public Map<String, String> getInfo() {
        return Map.of(
            "service", serviceName,
            "language", "Java 21",
            "framework", "Spring Boot 3.3",
            "profile", activeProfile
        );
    }
}
JEOF
```

Create the application configuration:

```bash
mkdir -p src/main/resources

cat > src/main/resources/application.yml << 'YEOF'
server:
  port: ${APP_PORT:8080}

spring:
  application:
    name: ${SERVICE_NAME:order-service}
  datasource:
    url: ${DATABASE_URL:jdbc:h2:mem:testdb}
    username: ${DATABASE_USER:sa}
    password: ${DATABASE_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
  h2:
    console:
      enabled: false

management:
  endpoints:
    web:
      exposure:
        include: ${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health,info,prometheus}
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always

logging:
  level:
    root: ${LOGGING_LEVEL_ROOT:INFO}
    com.devsecops: ${LOGGING_LEVEL_COM_DEVSECOPS:DEBUG}
YEOF
```

Create a basic unit test:

```bash
mkdir -p src/test/java/com/devsecops/orderservice

cat > src/test/java/com/devsecops/orderservice/OrderServiceTest.java << 'TEOF'
package com.devsecops.orderservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void ordersEndpointReturnsData() throws Exception {
        mockMvc.perform(get("/api/orders"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    void infoEndpointReturnsServiceInfo() throws Exception {
        mockMvc.perform(get("/api/info"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.language").value("Java 21"));
    }
}
TEOF
```

Create the test application properties (uses H2 in-memory DB for tests):

```bash
cat > src/test/resources/application.yml << 'TYEOF'
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: create-drop
TYEOF
```

### Step 2: Commit and Push to Trigger T2 Pipeline

```bash
cd order-service

git add .
git commit -m "Initial order-service Spring Boot application

- Spring Boot 3.3 with JDK 21
- REST API with /api/orders and /api/info endpoints
- Spring Actuator for /actuator/health probes
- JPA + PostgreSQL (H2 for tests)
- JUnit 5 tests
- CycloneDX Maven plugin for SBOM"

git push origin main
```

This push to `main` triggers the T2 (merge) pipeline via the webhook on the order-service repo.

### Step 3: Watch the Pipeline Execute

Open Jenkins and watch the `order-service-merge` job:

```bash
echo "Jenkins URL: ${JENKINS_URL}/job/order-service-merge/"
```

The pipeline stages will execute in this order:

```
┌─────────────┐   ┌───────────┐   ┌─────────┐   ┌────────────┐   ┌──────────────┐
│ Initialize  │→ │ Checkout  │→ │  Build  │→ │ Unit Tests │→ │  SonarQube   │
│ (configure  │   │  Source   │   │  Java   │   │   Java     │   │  Java SAST   │
│  for java)  │   │  (main)  │   │ (mvn)   │   │ (mvn test) │   │ (mvn sonar)  │
└─────────────┘   └───────────┘   └─────────┘   └────────────┘   └──────────────┘
                                                                          │
   ┌────────────┐   ┌──────────┐   ┌──────────┐   ┌───────────┐         │
   │ Deploy to  │← │ Update   │← │ ACS Scan │← │  Push to  │← ┌──────▼───────┐
   │   DEV      │   │  GitOps  │   │ (roxctl) │   │ Registry  │   │ Build Image  │
   │ (ArgoCD)   │   │(image tag)│   │          │   │           │   │ (Dockerfile  │
   └────────────┘   └──────────┘   └──────────┘   └───────────┘   │  .java)      │
                                                                   └──────────────┘
```

Key things to watch in the console output:

```
=== T2: Merge to Main Pipeline ===
  Service: order-service                           ← Routing to Java
  Source repo: https://gitlab.../devsecops/order-service.git

=== Build Java Application ===                     ← buildJava.groovy, not buildDotnet
  JAVA_HOME: /usr/lib/jvm/java-21-openjdk
  mvn package -DskipTests -B

=== Run Java Unit Tests ===                        ← runJavaTests.groovy
  mvn test -B
  Tests run: 3, Failures: 0, Errors: 0

=== SonarQube Analysis (Java) ===                  ← scanSonarQubeJava.groovy
  mvn sonar:sonar -Dsonar.projectKey=order-service

=== Build Container Image ===
  Dockerfile: build-config/Dockerfile.java         ← Java Dockerfile, not .NET

=== Push to Registry ===
  Image: image-registry.../javaapp-dev/order-service:main-abc1234

=== Update GitOps ===
  Path: services/order-service/overlays/dev/       ← Per-service overlay
  Image tag: main-abc1234
```

> **Why this matters:** Look at how the `service: 'order-service'` parameter cascades through the entire pipeline. One parameter change in the Jenkins job definition routes the pipeline through Java-specific build, test, and scan functions while reusing identical image push, ACS scan, GitOps update, and deployment logic.

### Step 4: Monitor ArgoCD Sync

After `updateGitOps` pushes the new image tag to the GitOps repo, the `order-service-dev` ArgoCD app (which has `automated` sync policy) will detect the change and deploy:

```bash
# Watch the sync status
$OC get application order-service-dev -n $NS_GITOPS \
  -o jsonpath='{.status.sync.status}{"\t"}{.status.health.status}{"\n"}'
```

Expected:

```
Synced	Healthy
```

If the status shows `OutOfSync` or `Progressing`, wait 30 seconds and check again. ArgoCD polls every 3 minutes by default, but the webhook should trigger an immediate sync.

---

## 8. Verify (5 min)

### Step 1: Check All Pods in javaapp-dev

```bash
$OC get pods -n $NS_JAVA_DEV
```

Expected (after all 3 services are deployed):

```
NAME                                  READY   STATUS    RESTARTS   AGE
order-service-<hash>                  1/1     Running   0          5m
inventory-service-<hash>              1/1     Running   0          ...
payment-service-<hash>                1/1     Running   0          ...
postgresql-0                          1/1     Running   0          10m
redis-0                               1/1     Running   0          10m
```

If only order-service is running (because you only pushed that service so far), that is correct. Inventory-service and payment-service will appear after you push their code and run their T2 pipelines using the same process from Section 7.

### Step 2: Check Order Service Health Endpoint

```bash
# External access via Route
curl -sk ${ORDER_SVC_DEV_URL}/actuator/health | jq .
```

Expected:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "livenessState": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    },
    "readinessState": {
      "status": "UP"
    }
  }
}
```

### Step 3: Check Order Service API

```bash
curl -sk ${ORDER_SVC_DEV_URL}/api/orders | jq .
```

Expected:

```json
{
  "service": "order-service",
  "profile": "dev",
  "timestamp": "2026-05-29T...",
  "orders": [
    {"id": 1, "item": "Widget", "quantity": 5, "status": "PENDING"},
    {"id": 2, "item": "Gadget", "quantity": 3, "status": "CONFIRMED"}
  ]
}
```

### Step 4: Check Inter-Service Communication (Internal)

Inventory-service and payment-service have no external Route. Verify from inside the cluster:

```bash
# Check inventory-service from order-service pod
$OC exec -n $NS_JAVA_DEV deploy/order-service -- \
  curl -s http://inventory-service.${NS_JAVA_DEV}.svc:8081/actuator/health
```

Expected:

```json
{"status":"UP",...}
```

```bash
# Check payment-service from order-service pod
$OC exec -n $NS_JAVA_DEV deploy/order-service -- \
  curl -s http://payment-service.${NS_JAVA_DEV}.svc:8082/actuator/health
```

Expected:

```json
{"status":"UP",...}
```

> **Why this matters:** This proves that inter-service communication works through Kubernetes DNS. Order-service resolves `inventory-service.javaapp-dev.svc` to the ClusterIP of the inventory-service Service. No external routes, no load balancers -- just cluster-internal DNS.

### Step 5: Verify NetworkPolicies

```bash
$OC get netpol -n $NS_JAVA_DEV
```

Expected:

```
NAME                                POD-SELECTOR                              AGE
allow-app-to-postgresql             app=postgresql                            10m
allow-app-to-redis                  app=redis                                 10m
allow-from-argocd                   <none>                                    10m
allow-monitoring                    <none>                                    10m
allow-order-to-internal-services    app.kubernetes.io/part-of=javaapp-internal 10m
allow-router-to-order-service       app=order-service                         10m
allow-same-namespace                <none>                                    10m
default-deny-ingress                <none>                                    10m
deny-postgresql-egress              app=postgresql                            10m
deny-redis-egress                   app=redis                                 10m
```

### Step 6: Verify the Full Platform State

Count everything:

```bash
echo "=== Namespaces ==="
$OC get ns -l app.kubernetes.io/part-of=devsecops --no-headers | wc -l
echo "  (Expected: 8 — 4 .NET + 4 Java)"

echo ""
echo "=== ArgoCD Applications ==="
$OC get applications -n $NS_GITOPS --no-headers | wc -l
echo "  (Expected: 28 — 12 .NET + 16 Java)"

echo ""
echo "=== Jenkins Jobs ==="
curl -sk -u admin:$JENKINS_PASS \
  "${JENKINS_URL}/api/json?tree=jobs[name]" \
  | jq -r '.jobs | length'
echo "  (Expected: 16 — 6 .NET + 9 Java + 1 promote)"

echo ""
echo "=== GitLab Repos ==="
curl -sk -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "${GITLAB_URL}/api/v4/groups/devsecops/projects" | jq -r '. | length'
echo "  (Expected: 8)"

echo ""
echo "=== SonarQube Projects ==="
curl -sk -u "admin:${SONAR_PASS}" \
  "${SONAR_API}/projects/search" | jq -r '.paging.total'
echo "  (Expected: 5 — sampleapi + notificationapi + 3 Java services)"
```

---

## What Just Happened?

Let's connect the pieces:

1. **Language-agnostic pipeline architecture.** The same `pipelineMR()`, `pipelineMerge()`, and `pipelineTag()` orchestrators handle both .NET and Java services. The `PipelineConfig.configureForService()` method sets the language, Dockerfile, source repo, and all build parameters. Downstream functions branch on `activeLanguage` to call the right build/test/scan tools. No pipeline code was duplicated.

2. **Namespace isolation.** Java services live in `javaapp-{env}` namespaces, completely separate from `sampleapi-{env}`. Each ecosystem has its own PostgreSQL, Redis, secrets, NetworkPolicies, and resource quotas. A failure in one ecosystem does not affect the other.

3. **GitOps consistency.** The same base + overlay Kustomize pattern applies to Java services. Each service has its own directory under `app-gitops/services/`, its own ArgoCD Application per environment, and its own image tag in its own `kustomization.yaml`. Updating order-service does not create Git conflicts with inventory-service or payment-service.

4. **28 ArgoCD Applications** manage the entire platform: 12 for .NET (4 sampleapi + 4 notificationapi + 4 infra) and 16 for Java (4 per service + 4 infra-javaapp).

5. **16 Jenkins jobs** cover all 5 services: 3 jobs per service (MR, merge, tag) + 1 shared promote job. Each job is a one-liner that calls the shared library with a `service` parameter.

6. **16 GitLab webhooks** connect 6 source repos to Jenkins: 3 webhooks per source repo (one per trigger type) + 1 on the GitOps repo for promotions.

---

## Common Mistakes

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Copied `.NET` health probe paths into Java Deployment | Pod `CrashLoopBackOff`, liveness probe fails on `/healthz` (returns 404) | Java uses `/actuator/health/liveness`, not `/healthz` |
| Forgot `startupProbe` on Java Deployment | Pod killed during JVM startup by `livenessProbe` | Add `startupProbe` with `failureThreshold: 30` to give JVM 150s to start |
| Wrong image namespace (`sampleapi-dev` instead of `javaapp-dev`) | Image push succeeds but ACS scan or ArgoCD can't find image | Verify `PipelineConfig.configureForService()` sets `imageNamespace = 'javaapp-dev'` |
| Maven `pom.xml` missing `spring-boot-maven-plugin` | JAR file is not executable, container fails to start | Add `spring-boot-maven-plugin` to `<build><plugins>` in pom.xml |
| SonarQube project not created before first pipeline run | SAST stage fails with "project not found" | Create SonarQube projects before pushing code (Step 2 in Section 5) |
| GitLab project ID mismatch in `env.sh` vs actual | Webhooks trigger wrong pipeline or status not reported to correct MR | After creating repos, verify IDs: `curl .../api/v4/projects | jq '.[] | {id, path}'` |
| `Dockerfile.java` not in `build-config` repo | Image build stage fails: "Dockerfile.java: No such file" | Push `Dockerfile.java` to the `build-config` repo (Section 4) |
| Forgot to give ArgoCD admin access to `javaapp-{env}` namespaces | ArgoCD shows "Forbidden" on sync | Run `$OC policy add-role-to-user admin system:serviceaccount:openshift-gitops:openshift-gitops-argocd-application-controller -n javaapp-dev` |
| Redis readiness probe uses `/usr/libexec/check-container` | Pod `CrashLoopBackOff` | RHEL Redis image has no `check-container`. Use `redis-cli -a "$REDIS_PASSWORD" ping` |
| Java service cannot connect to PostgreSQL | Application starts but DB health shows DOWN | Check that `DATABASE_URL` uses `javaapp-{env}` namespace in DNS: `postgresql.javaapp-dev.svc:5432` |

---

## Self-Assessment

- [ ] I can explain how `PipelineConfig.configureForService()` routes Java and .NET services through the same pipeline orchestrators
- [ ] I can describe the difference between `/actuator/health` (Java) and `/healthz` (.NET) and why this matters for Kubernetes probes
- [ ] I know why Java Deployments need a `startupProbe` with a high `failureThreshold`
- [ ] I can list all 28 ArgoCD Applications and explain the naming convention
- [ ] I can explain why order-service has a Route but inventory-service and payment-service do not
- [ ] I know where Java service images are stored (`javaapp-dev` namespace in internal registry)
- [ ] I can verify inter-service communication from inside the cluster using `$OC exec`
- [ ] I understand why the `javaapp-{env}` namespaces are completely separate from `sampleapi-{env}`
- [ ] I can create additional Java service pipelines by adding a new `case` in `PipelineConfig` and 3 Jenkins job definitions

---

## Next Module Preview

**Module 17: Supply Chain Security with RHTAS and RHTPA** -- Now that you have 5 services across 2 languages, you will integrate Red Hat Trusted Artifact Signer (Sigstore/Cosign on OpenShift) for keyless image signing and Red Hat Trusted Profile Analyzer (Trustify) for SBOM-based vulnerability management. You will sign every image with a cluster-issued certificate, verify signatures at admission time via ACS policy, and upload SBOMs to Trustify for continuous vulnerability tracking across your entire software supply chain.

---

*Module 16 complete. Estimated time: 90 minutes.*
