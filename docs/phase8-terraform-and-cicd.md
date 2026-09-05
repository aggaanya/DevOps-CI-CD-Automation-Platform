# Phase 8 — Terraform IaC & CI/CD Report

- **Date:** 2026-09-05
- **Phase:** 8 (Terraform Infrastructure-as-Code + GitHub Actions CI/CD)
- **Verdict:** **IMPLEMENTED & VALIDATED (static)** — Azure deployment **BLOCKED** by a disabled subscription (`ReadOnlyDisabledSubscription` on `115d2aec-6fa6-49dc-9b8d-24f799fc5482`). Nothing was deployed to Azure and no success claims are made for live deployment.

## 1. Executive summary

The Phase 7 ARM-based Azure deployment (see `infrastructure/azure/*.json` and
`scripts/azure/*.ps1`) is now expressed as a single Terraform configuration
under `infrastructure/terraform/`, and a production-grade GitHub Actions
pipeline (build → test → validate → plan → deploy) wraps the platform.

What was delivered:

1. **Terraform configuration** mirroring the Phase 7 architecture 1:1
   (resource group, Log Analytics, VNet with two delegated subnets, internal
   Container Apps Environment, storage account + Azure Files, Key Vault with
   generated secrets, User-Assigned Managed Identity with least-privilege
   grants, private-access PostgreSQL Flexible Server, ACR, and all four
   container apps: rabbitmq, backend, worker, frontend).
2. **Immutable, reproducible deployment:** commit-SHA image tags throughout;
   no `latest`-only deploys.
3. **GitHub Actions workflows:** `ci.yml` (tests + `terraform fmt/validate`
   on PRs, plus an OIDC-backed `plan` job) and `deploy.yml` (OIDC login,
   state bootstrap, Terraform apply → ACR image push → revision roll).
4. **Honest validation:** `terraform fmt -check`, `terraform validate`,
   backend `mvn -B test`, worker `mvn -B test`, and frontend `npm run build`
   all pass locally. A full `terraform plan` was produced successfully against
   the configuration. **Applying** the plan remains blocked because the Azure
   subscription is disabled and the Terraform remote-state storage cannot be
   created.

## 2. Repo layout (new/changed)

```text
.github/
  workflows/
    ci.yml            # PR: java tests, frontend build, tfm fmt/validate; plan (OIDC)
    deploy.yml        # main: bootstrap state, tfm apply infra, build/push images, tfm apply deploy
infrastructure/
  terraform/
    main.tf           # required providers/version; azurerm backend (config via -backend-config)
    providers.tf      # azurerm + random providers
    variables.tf      # environment/location/project/image_tag/postgres_*/tags
    locals.tf         # naming (stable random_id suffix), image refs, pg url
    resources.tf      # all Azure resources + container apps
    outputs.tf        # fqdns, ACR, KV, identity, storage for CI/CD + humans
    terraform.tfvars.example
    environments/dev/
      dev.tfbackend   # remote-state backend config (names/keys only)
      terraform.tfvars
.gitignore            # *.tsbuildinfo added (frontend incremental build artifact)
```

The existing skeleton (`main.tf`, `outputs.tf`, `resources.tf`,
`variables.tf`) was **extended**, not duplicated: placeholder comments and
empty outputs were replaced with real resources.

## 3. Terraform ↔ Phase 7 ARM relationship

| Phase 7 ARM (`infrastructure/azure/`) | Terraform (Phase 8) |
|---|---|
| `Microsoft.App/managedEnvironments` (cae.json) | `azurerm_container_app_environment` (internal LB, LA sink, `infrastructure_subnet_id`) |
| `Microsoft.App/managedEnvironments/storages` (storages.json) | `azurerm_container_app_environment_storage` × 2 (rabbitmq-data, worker-workspaces) |
| `Microsoft.App/containerApps` (apps.json) | `azurerm_container_app` × 4 with identical env/ingress/probe/volume blocks |
| `az keyvault secret set` (deploy.ps1) | `random_password` + `azurerm_key_vault_secret` (generated, never committed) |
| `az role assignment AcrPull` | `azurerm_role_assignment` (AcrPull on ACR for the UAI) |
| `az postgres flexible-server create --vnet` | `azurerm_postgresql_flexible_server` (`delegated_subnet_id` + private DNS zone) |
| persistent 6-hex suffix in `config.ps1` | `random_id` (6-hex) keyed on project+environment |

Deliberate deviation: the ARM template wired `backend → frontend FQDN`
(CORS) and `frontend → backend FQDN` (proxy target) with crossed
`reference()` calls. Terraform requires a DAG, so the backend's
`APP_CORS_ALLOWED_ORIGINS` is derived from the environment's
`default_domain` (`https://frontend.<env>.azurecontainerapps.io`) instead of a
cross-app FQDN reference. This is functionally equivalent: the frontend nginx
is the same-origin gateway (browsers never call the backend directly), so CORS
is defensive only. Verified via `default_domain` attribute on
`azurerm_container_app_environment` (accepted by `terraform validate`).

## 4. Deploy architecture and image strategy

**Never `latest`.** Container apps reference images as
`<acr>.<region>.azurecontainerapps.io/cicd-<component>:<git-sha>`. The deploy
workflow runs in three phases:

1. `terraform apply -var image_tag=bootstrap` — creates/updates the Azure
   infrastructure (RG, VNet, CAE, ACR, PostgreSQL, Key Vault, storage, UAI).
2. `docker build`/`docker push` of `cicd-backend`, `cicd-worker`,
   `cicd-frontend` tagged with `${{ github.sha }}` into the freshly created
   ACR (authenticated via `az acr login` under OIDC; ACR admin account stays
   disabled).
3. `terraform apply -var image_tag=${{ github.sha }}` — rolls the container
   apps onto the immutable revision.

Rollback: `terraform apply -var image_tag=<previous-sha>` re-pins every app
to the prior immutable tag (kept in ACR by design).

Container apps authenticate to ACR and read Key Vault secrets through the
User-Assigned Managed Identity (`AcrPull` role + `Get/List` secret-
permissions), so no registry/Key Vault credentials exist in the repository,
the workflows, or the container configurations.

## 5. GitHub Actions: actions/secrets/variables

### Workflows

- **`ci.yml`** — runs on every PR and push to `main`:
  - `java-tests` (matrix `backend`/`worker`): `mvn -B test` (Temurin 21).
  - `frontend-build`: `npm ci && npm run build` (Node 20).
  - `terraform-validate`: `terraform fmt -check -recursive`,
    `terraform init -backend=false`, `terraform validate` (no Azure needed).
  - `terraform-plan` (PRs from this repository only): Azure OIDC login,
    bootstrap the state container, `terraform plan` (committed SHA tag), plan
    uploaded as an artifact.
- **`deploy.yml`** — on push to `main`:
  - OIDC login, idempotent state-storage bootstrap, phase-1 apply, build+push
    (SHA tag), phase-2 apply, summary of endpoints.

### Repository secrets

| Secret | Purpose |
|---|---|
| `AZURE_CLIENT_ID` | Application (service principal) client ID with a federated credential for this repo |
| `AZURE_TENANT_ID` | Azure AD tenant |
| `AZURE_SUBSCRIPTION_ID` | Subscription into which Terraform deploys |

### Repository variables (optional; sensible defaults embedded in the workflows)

| Variable | Default |
|---|---|
| `TF_STATE_RG` | `rg-tfstate-cicd` |
| `TF_STATE_STORAGE` | `cicdtfstate` |
| `TF_STATE_LOCATION` | `eastus` |

## 6. OIDC setup (one-time, manual)

1. Create an App Registration; note the client ID.
2. Create a federated credential for GitHub:
   - Issuer: `https://token.actions.githubusercontent.com`
   - Subject: `repo:<owner>/<repo>:ref:refs/heads/main` (deploy) and
     `repo:<owner>/<repo>:pull_request` (plan, optional).
   - Audience: `api://AzureADTokenExchange`.
3. Assign the application `Contributor` (or a scoped custom role) on the
   target subscription/resource group.
4. Create the three repo secrets from step 5.
5. Grant the deploying principal `Get/List` on the Key Vault (the Terraform
   config already grants the UAI; the deployer policy is documented in
   `resources.tf` and must be added for the CI principal unless RBAC or a
   policy file is used).

## 7. Terraform remote state

State lives in Azure Blob Storage via the `azurerm` backend
(`environments/dev/dev.tfbackend`: RG `rg-tfstate-cicd`, storage
`cicdtfstate`, container `tfstate`, key `dev.tfstate`). The workflows
bootstrap those three resources idempotently with `az` before `terraform
init -backend-config`. The storage account name is global — if
`cicdtfstate` is taken, set `TF_STATE_STORAGE` to a unique name and update
`dev.tfbackend` accordingly.

Local safe operations never touch the backend:
`terraform init -backend=false && terraform validate`.

## 8. What is verified vs. blocked

**Verified (all green):**
- `terraform fmt -check -recursive` — clean.
- `terraform init -backend=false` + `terraform validate` — valid.
- `terraform plan` (local copy, no backend) — 27 resources to create; plan
  completes with exit 0. This exercises provider schema, graph ordering,
  dependency resolution and variable wiring, not Azure side-effects.
- `mvn -B test` backend — 357 tests, 0 failures.
- `mvn -B test` worker — 82 tests, 0 failures.
- `npm run build` (frontend) — `tsc -b && vite build`, clean build.

**Blocked / not performed (honest):**
- Any live Azure apply. Subscription `115d2aec-...` reports
  `ReadOnlyDisabledSubscription`; state storage cannot be created, so even a
  remote-backend apply cannot run.
- Consequently ACR image push, revision rollouts, DNS FQDNs, and end-to-end
  health checks against the deployed environment are **not** validated.
- The `terraform-plan`/`deploy` GitHub jobs will stop at Azure login until
  secrets are configured and the subscription is re-enabled.

## 9. Prerequisites to deploy (future, once the subscription is active)

1. Re-enable the subscription and confirm the provider registrations
   (`Microsoft.App`, `Microsoft.DBforPostgreSQL`, `Microsoft.ContainerRegistry`,
   `Microsoft.OperationalInsights`, `Microsoft.Storage`, `Microsoft.KeyVault`,
   `Microsoft.Network`, `Microsoft.ManagedIdentity` — same list as
   `scripts/azure/deploy.ps1`).
2. Create the App Registration + federated credential and repo secrets
   (Sections 5–6).
3. Ensure `frontend/package-lock.json` is committed (required by `npm ci`).
4. `git push origin main` — `ci.yml` validates, `deploy.yml` deploys.
5. Verify: `terraform output frontend_fqdn` → dashboard; actuator health
   shows `controlPlane/database/rabbitmq UP`.