// jenkins-shared-lib/src/com/devsecops/PipelineConfig.groovy
// Centralized configuration class for all pipeline parameters
// All values are parameterized — no hardcoded URLs, namespaces, or thresholds
//
// KNOWN FIX: Utility methods need @NonCPS annotation for CPS compatibility
package com.devsecops

import com.cloudbees.groovy.cps.NonCPS

class PipelineConfig implements Serializable {
    private static final long serialVersionUID = 1L

    // --- Application ---
    String appName = 'sampleapi'
    String appNamespace = 'sampleapi'
    String dotnetSolution = 'SampleApi.sln'
    String dotnetProject = '.'
    String dotnetTestProject = 'tests/SampleApi.Tests/SampleApi.Tests.csproj'

    // --- Git ---
    String gitCredentialsId = 'gitlab-token'
    String gitApiTokenId = 'gitlab-api-token'
    String gitlabUrl = ''      // Set from env.GITLAB_URL
    String appSourceRepo = ''  // Derived from gitlabUrl
    String buildConfigRepo = '' // Derived from gitlabUrl
    String gitopsRepo = ''     // Derived from gitlabUrl
    String gitMainBranch = 'main'

    // --- Registry ---
    String imageRegistry = ''  // Set from env.IMAGE_REGISTRY
    String imageNamespace = '' // Derived: {appName}-dev
    String imageName = ''      // Same as appName
    String imageTag = ''       // Set by pipeline (main-SHA or tag)

    // --- Multi-service support ---
    // Active service being built (set by configureForService)
    String activeServiceName = 'sampleapi'
    String activeImageName = ''       // Set by configureForService
    String activeSourceRepo = ''      // Set by configureForService
    Map activeBuildArgs = [:]         // Dockerfile build args for active service
    String activeLanguage = 'dotnet'  // 'dotnet' or 'java'
    String activeDockerfile = 'Dockerfile' // 'Dockerfile' or 'Dockerfile.java'
    String notificationApiImageName = 'notificationapi'

    // --- SonarQube ---
    String sonarUrl = ''       // Set from env.SONARQUBE_URL
    String sonarTokenCredId = 'sonarqube-token'
    String sonarProjectKey = '' // Same as appName

    // --- ACS (StackRox) ---
    String acsUrl = ''         // Set from env.ACS_CENTRAL_URL
    String acsTokenCredId = 'acs-token'

    // --- ArgoCD ---
    String argocdServer = ''   // Set from env.ARGOCD_SERVER
    String argocdTokenCredId = 'argocd-token'

    // --- Cosign ---
    String cosignKeyCredId = 'cosign-signing-key'

    // --- Thresholds ---
    int sonarQualityGateTimeoutMinutes = 5
    double coverageThreshold = 80.0
    int maxCriticalVulns = 0
    int maxHighVulns = 5

    // --- GitLab ---
    String gitlabProjectId = '' // Set from env or webhook payload

    /**
     * Initialize config from Jenkins environment variables
     * Call this from orchestrators to populate env-dependent values
     */
    @NonCPS
    void initFromEnv(def env) {
        this.gitlabUrl = env.GITLAB_URL ?: 'https://gitlab-devsecops-gitlab.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com'
        this.imageRegistry = env.IMAGE_REGISTRY ?: 'image-registry.openshift-image-registry.svc:5000'
        this.sonarUrl = env.SONARQUBE_URL ?: 'http://sonarqube.devsecops-tools.svc:9000'
        this.acsUrl = env.ACS_CENTRAL_URL ?: 'https://central.stackrox.svc:443'
        this.argocdServer = env.ARGOCD_SERVER ?: 'openshift-gitops-server-openshift-gitops.apps.muhrahma-cluster.vmware.tamlab.rdu2.redhat.com'

        // Derived values
        this.appSourceRepo = "${this.gitlabUrl}/devsecops/app-source.git"
        this.buildConfigRepo = "${this.gitlabUrl}/devsecops/build-config.git"
        this.gitopsRepo = "${this.gitlabUrl}/devsecops/app-gitops.git"
        this.imageNamespace = "${this.appName}-dev"
        this.imageName = this.appName
        this.sonarProjectKey = this.appName
        this.gitlabProjectId = env.GITLAB_PROJECT_ID ?: '4'
    }

    /**
     * Get the full image reference (registry/namespace/name:tag)
     */
    @NonCPS
    String getFullImageRef() {
        return "${this.imageRegistry}/${this.imageNamespace}/${this.imageName}:${this.imageTag}"
    }

    /**
     * Get the image reference without tag (for kustomize set image)
     */
    @NonCPS
    String getImageBase() {
        return "${this.imageRegistry}/${this.imageNamespace}/${this.imageName}"
    }

    /**
     * Get the full NotificationApi image reference (same tag as sampleapi)
     */
    @NonCPS
    String getNotificationApiImageRef() {
        return "${this.imageRegistry}/${this.imageNamespace}/${this.notificationApiImageName}:${this.imageTag}"
    }

    /**
     * Configure pipeline for a specific service.
     * Sets activeSourceRepo, activeImageName, activeBuildArgs based on service name.
     * Call after initFromEnv() — needs gitlabUrl to derive repo URLs.
     *
     * Supported services: 'sampleapi', 'notificationapi', 'order-service', 'inventory-service', 'payment-service'
     */
    @NonCPS
    void configureForService(String serviceName) {
        this.activeServiceName = serviceName
        switch (serviceName) {
            case 'notificationapi':
                this.activeImageName = this.notificationApiImageName
                this.activeSourceRepo = "${this.gitlabUrl}/devsecops/notificationapi-source.git"
                this.activeBuildArgs = [PROJECT_NAME: 'NotificationApi', SOLUTION_NAME: 'NotificationApi', APP_PORT: '8081']
                this.sonarProjectKey = 'notificationapi'
                this.gitlabProjectId = '8'
                break
            case 'order-service':
                this.activeImageName = 'order-service'
                this.activeSourceRepo = "${this.gitlabUrl}/devsecops/order-service.git"
                this.activeBuildArgs = [SERVICE_NAME: 'order-service', APP_PORT: '8080']
                this.activeLanguage = 'java'
                this.activeDockerfile = 'Dockerfile.java'
                this.imageNamespace = 'javaapp-dev'
                this.appNamespace = 'javaapp'
                this.sonarProjectKey = 'order-service'
                this.gitlabProjectId = '10'
                break
            case 'inventory-service':
                this.activeImageName = 'inventory-service'
                this.activeSourceRepo = "${this.gitlabUrl}/devsecops/inventory-service.git"
                this.activeBuildArgs = [SERVICE_NAME: 'inventory-service', APP_PORT: '8081']
                this.activeLanguage = 'java'
                this.activeDockerfile = 'Dockerfile.java'
                this.imageNamespace = 'javaapp-dev'
                this.appNamespace = 'javaapp'
                this.sonarProjectKey = 'inventory-service'
                this.gitlabProjectId = '11'
                break
            case 'payment-service':
                this.activeImageName = 'payment-service'
                this.activeSourceRepo = "${this.gitlabUrl}/devsecops/payment-service.git"
                this.activeBuildArgs = [SERVICE_NAME: 'payment-service', APP_PORT: '8082']
                this.activeLanguage = 'java'
                this.activeDockerfile = 'Dockerfile.java'
                this.imageNamespace = 'javaapp-dev'
                this.appNamespace = 'javaapp'
                this.sonarProjectKey = 'payment-service'
                this.gitlabProjectId = '12'
                break
            default: // sampleapi
                this.activeImageName = this.imageName
                this.activeSourceRepo = this.appSourceRepo
                this.activeBuildArgs = [:]
                this.sonarProjectKey = this.appName
                break
        }
    }

    /**
     * Get image ref for the active service (set by configureForService)
     */
    @NonCPS
    String getActiveImageRef() {
        return "${this.imageRegistry}/${this.imageNamespace}/${this.activeImageName}:${this.imageTag}"
    }

    /**
     * Get the gitops service path for the active service.
     * Used by updateGitOps and createPromotionMR to locate the correct
     * per-service overlay directory: services/{service}/overlays/{env}/
     */
    @NonCPS
    String getGitopsServicePath() {
        return "services/${this.activeServiceName}"
    }
}
