# Disaster Recovery Plan — DevSecOps Platform

## Recovery Priority Order

| Priority | Component | RTO | RPO | Recovery Method |
|----------|-----------|-----|-----|-----------------|
| P1 | OpenShift Cluster | 4h | 0 | AWS re-provision + restore etcd |
| P2 | GitLab CE | 1h | 24h | Restore from backup tarball |
| P3 | Application (PROD) | 15m | 0 | ArgoCD auto-sync from GitOps repo |
| P4 | PostgreSQL Data | 30m | 24h | Restore from pg_dump backup |
| P5 | SonarQube | 2h | N/A | Redeploy + reimport quality gate |
| P6 | Jenkins | 1h | 0 | Redeploy from JCasC ConfigMap |
| P7 | ACS/StackRox | 2h | N/A | Reinstall operator + init bundle |
| P8 | Observability Stack | 2h | 7d | Redeploy operators + CRs |

## Recovery Procedures

### 1. Application Recovery (ArgoCD Auto-Heal)

The application is self-healing via ArgoCD GitOps:

```bash
# If pods are deleted/crashed, ArgoCD auto-syncs from app-gitops repo
# DEV auto-syncs immediately, SIT/UAT/PROD require manual sync

# Force sync all apps
for APP in sampleapi-dev sampleapi-sit sampleapi-uat sampleapi-prod \
           notificationapi-dev notificationapi-sit notificationapi-uat notificationapi-prod \
           infra-dev infra-sit infra-uat infra-prod; do
  argocd app sync $APP --force 2>/dev/null || echo "Skipping $APP"
done

# Verify
argocd app list
```

### 2. PostgreSQL Recovery

```bash
# List available backups
oc exec -n sampleapi-prod deploy/postgresql-0 -- \
  ls -lh /backups/sampleapi_*.sql.gz

# Restore from latest backup
LATEST=$(oc exec -n sampleapi-prod deploy/postgresql-0 -- \
  ls -1t /backups/sampleapi_*.sql.gz | head -1)

# Scale down app to prevent writes during restore
oc scale deployment sampleapi -n sampleapi-prod --replicas=0

# Drop and recreate database
oc exec -it postgresql-0 -n sampleapi-prod -- psql -U sampleapi -c "DROP DATABASE IF EXISTS sampleapi;"
oc exec -it postgresql-0 -n sampleapi-prod -- psql -U sampleapi -c "CREATE DATABASE sampleapi;"

# Restore
oc exec -it postgresql-0 -n sampleapi-prod -- bash -c \
  "gunzip -c ${LATEST} | psql -U sampleapi -d sampleapi"

# Scale app back up
oc scale deployment sampleapi -n sampleapi-prod --replicas=3

# Verify
curl -sk https://sampleapi-sampleapi-prod.${APPS_DOMAIN}/readyz
```

### 3. GitLab Recovery

```bash
# If GitLab pod is deleted, Kubernetes recreates it from the Deployment
# Data persists on PVCs (data, config, logs)

# If data is corrupted, restore from backup:
GITLAB_POD=$(oc get pods -n devsecops-gitlab -l app=gitlab-ce -o jsonpath='{.items[0].metadata.name}')

# List backups
oc exec -n devsecops-gitlab ${GITLAB_POD} -- ls /var/opt/gitlab/backups/

# Restore (requires stopping services first)
oc exec -n devsecops-gitlab ${GITLAB_POD} -- gitlab-ctl stop puma
oc exec -n devsecops-gitlab ${GITLAB_POD} -- gitlab-ctl stop sidekiq
oc exec -n devsecops-gitlab ${GITLAB_POD} -- \
  gitlab-backup restore BACKUP=<timestamp>_gitlab_backup
oc exec -n devsecops-gitlab ${GITLAB_POD} -- gitlab-ctl start
```

### 4. Jenkins Recovery

Jenkins is stateless (configuration in JCasC ConfigMap, pipelines in shared library):

```bash
# Recreate Jenkins
oc delete deploy jenkins -n devsecops-tools
oc apply -f infra/phase7/jenkins-deployment.yaml
oc apply -f infra/phase7/jenkins-casc-configmap.yaml

# Wait for ready
oc wait --for=condition=ready pod -l app=jenkins -n devsecops-tools --timeout=300s

# Verify — jobs should auto-configure from JCasC
curl -sk https://jenkins-devsecops-tools.${APPS_DOMAIN}/login
```

### 5. Full Cluster Recovery

If the entire cluster is lost:

1. Provision new OCP cluster (same version, same domain if possible)
2. Follow EXECUTION-GUIDE.md Steps 1-12 in sequence
3. Push all 5 repos to new GitLab instance
4. ArgoCD auto-deploys all environments
5. Restore PostgreSQL data from most recent backup
6. Verify all integrations (webhooks, tokens)

**Estimated time**: 4-6 hours for full platform recovery.

## Backup Verification Schedule

| Test | Frequency | Procedure |
|------|-----------|-----------|
| PostgreSQL restore | Monthly | Restore backup to dev, verify data |
| GitLab restore | Monthly | Restore backup to temp instance |
| ArgoCD sync | Weekly | Delete DEV pods, verify auto-heal |
| Full DR drill | Quarterly | Rebuild from scratch in test cluster |

## Data That Cannot Be Recovered

| Data | Reason | Mitigation |
|------|--------|------------|
| In-flight pipeline runs | Jenkins is ephemeral | Re-trigger after recovery |
| Loki logs beyond retention | 30-day retention policy | Export critical logs to long-term storage |
| Tempo traces beyond retention | 48-hour retention | Increase retention for PROD if needed |
| ACS runtime alerts | Stored in Central DB | Back up Central DB (not currently automated) |
