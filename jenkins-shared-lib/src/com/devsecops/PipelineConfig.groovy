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
        this.gitlabUrl = env.GITLAB_URL ?: 'https://gitlab-devsecops-gitlab.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com'
        this.imageRegistry = env.IMAGE_REGISTRY ?: 'image-registry.openshift-image-registry.svc:5000'
        this.sonarUrl = env.SONARQUBE_URL ?: 'https://sonarqube-devsecops-tools.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com'
        this.acsUrl = env.ACS_CENTRAL_URL ?: 'https://central-stackrox.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com'
        this.argocdServer = env.ARGOCD_SERVER ?: 'openshift-gitops-server-openshift-gitops.apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com'

        // Derived values
        this.appSourceRepo = "${this.gitlabUrl}/devsecops/app-source.git"
        this.buildConfigRepo = "${this.gitlabUrl}/devsecops/build-config.git"
        this.gitopsRepo = "${this.gitlabUrl}/devsecops/app-gitops.git"
        this.imageNamespace = "${this.appName}-dev"
        this.imageName = this.appName
        this.sonarProjectKey = this.appName
        this.gitlabProjectId = env.GITLAB_PROJECT_ID ?: '1'
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
     * Supported services: 'sampleapi', 'notificationapi'
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
                this.gitlabProjectId = '5'
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
