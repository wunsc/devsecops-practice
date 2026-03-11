#!/usr/bin/env bash
# =============================================================================
# DevSecOps Tutorial — Environment Variables
# =============================================================================
#
# HOW TO USE:
#   1. Edit the values below to match YOUR cluster
#   2. Source this file before running any tutorial commands:
#      source ./env.sh
#   3. Verify: $OC whoami && echo $APPS_DOMAIN
#
# Every tutorial module uses these variables. This ensures commands are
# repeatable on any OpenShift cluster without editing individual modules.
# =============================================================================

# ── CLUSTER & DOMAIN ──
# Change these to match your cluster. Find your apps domain with:
#   oc get ingresses.config cluster -o jsonpath='{.spec.domain}'
export OC=~/Downloads/oc                    # Path to your oc binary
export CLUSTER_API="https://api.cluster-pmqwq.pmqwq.sandbox270.opentlc.com:6443"
export APPS_DOMAIN="apps.cluster-pmqwq.pmqwq.sandbox270.opentlc.com"
export STORAGE_CLASS="gp3-csi"              # Default StorageClass for PVCs

# ── APPLICATION ──
export APP_NAME="sampleapi"
export APP_GROUP="devsecops"                # GitLab group name

# ── NAMESPACES ──
export NS_TOOLS="devsecops-tools"
export NS_GITLAB="devsecops-gitlab"
export NS_ACS_OP="rhacs-operator"
export NS_ACS="stackrox"
export NS_GITOPS="openshift-gitops"
export NS_DEV="${APP_NAME}-dev"
export NS_SIT="${APP_NAME}-sit"
export NS_UAT="${APP_NAME}-uat"
export NS_PROD="${APP_NAME}-prod"

# ── SERVICE URLS ──
# Route format: {route-name}-{namespace}.{apps-domain}
export GITLAB_URL="https://gitlab-${NS_GITLAB}.${APPS_DOMAIN}"
export JENKINS_URL="https://jenkins-${NS_TOOLS}.${APPS_DOMAIN}"
export SONARQUBE_URL="https://sonarqube-${NS_TOOLS}.${APPS_DOMAIN}"
export ACS_URL="https://central-${NS_ACS}.${APPS_DOMAIN}"
export ARGOCD_URL="https://openshift-gitops-server-${NS_GITOPS}.${APPS_DOMAIN}"
export GRAFANA_URL="https://grafana-route-${NS_TOOLS}.${APPS_DOMAIN}"

# ── APPLICATION URLS ──
# OpenShift route host = {route-name}-{namespace}.{apps-domain}
# Route name is "sampleapi" in each namespace, so host = sampleapi-sampleapi-dev.apps...
export APP_DEV_URL="https://${APP_NAME}-${NS_DEV}.${APPS_DOMAIN}"
export APP_SIT_URL="https://${APP_NAME}-${NS_SIT}.${APPS_DOMAIN}"
export APP_UAT_URL="https://${APP_NAME}-${NS_UAT}.${APPS_DOMAIN}"
export APP_PROD_URL="https://${APP_NAME}-${NS_PROD}.${APPS_DOMAIN}"

# ── INTERNAL REGISTRY ──
export REGISTRY_INT="image-registry.openshift-image-registry.svc:5000"
export REGISTRY_IMAGE="${REGISTRY_INT}/${NS_DEV}/${APP_NAME}"

# ── GITLAB PROJECT IDs ──
# Assigned when you create repos in GitLab. Update after Step 2.
export GITLAB_PROJECT_APP_SOURCE=1
export GITLAB_PROJECT_BUILD_CONFIG=2
export GITLAB_PROJECT_SHARED_LIB=3
export GITLAB_PROJECT_APP_GITOPS=4
export GITLAB_PROJECT_NOTIFICATION_SOURCE=5

# ── CLI TOOLS ──
# Add ~/bin to PATH for helm, kustomize, yq, roxctl, argocd, cosign
export PATH="$HOME/bin:$PATH"

# ── VERIFY ──
echo "Environment loaded:"
echo "  Cluster:     $APPS_DOMAIN"
echo "  OC:          $OC"
echo "  GitLab:      $GITLAB_URL"
echo "  Jenkins:     $JENKINS_URL"
echo "  SonarQube:   $SONARQUBE_URL"
echo "  ACS:         $ACS_URL"
echo "  ArgoCD:      $ARGOCD_URL"
echo "  Grafana:     $GRAFANA_URL"
echo "  App DEV:     $APP_DEV_URL"
echo "  App PROD:    $APP_PROD_URL"
