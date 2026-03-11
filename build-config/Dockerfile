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
