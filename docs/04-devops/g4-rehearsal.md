# Gate G4 rehearsal (ENV-05)

Task ENV-05 ("Done when": a sample service goes local -> uat -> prod (ephemeral) through the pipeline only; a GitOps revert
rolls back in under 5 minutes; an unsigned image is blocked; destroy; evidence captured). This is the runbook and the evidence
sheet. **It has not been executed**: it needs a real Azure subscription, and an agent session never runs `apply`, `destroy` or
`kubectl` against a cloud cluster. The SA runs it; an agent may prepare and review the evidence.

## 0. Before you start (all must be true)

| # | Condition | How to check | Task |
|---|---|---|---|
| 1 | Bootstrap applied: state storage, ACR, the six GitHub OIDC identities, role assignments done by an Owner, `tfstate` container exists | `terraform output` in `shp-infra/bootstrap`; the GitHub variables of `shp-infra/README.md` are set | IAC-01..03 |
| 2 | The GitOps GitHub App exists (write on `shp-gitops` only) and its variables/secret are set in the application repo | `docs/04-devops/github-setup.md` | GOV-05 |
| 3 | The read-only App for Argo CD exists and the three `ARGOCD_REPO_APP_*` values are set in `shp-infra` | `shp-infra/README.md` | ENV-01 |
| 4 | `uat` provisions once end to end (this is the first real run of `env-provision`) | step 2 below | ENV-01 |
| 5 | The fields marked `FILL IN` in `shp-gitops` `envs/<env>/platform.yaml`, `services.yaml` and the sample service values are filled from the Terraform outputs; `tools/validate.sh --strict` passes | run it in `shp-gitops` | ENV-01 |
| 6 | Kyverno policies are `Enforce` and have been seen blocking something once | step 6 below | GOP-07 |

Choose the **sample service** with the least dependencies; `gateway` or `scheduling` already have a pipeline (`svc-<name>.yml`), a
chart values file and an image in ACR once the pipeline has pushed on `main`.

## 1. Local

1. `make -C dev up && make -C dev deploy SVC=<svc>`; `curl http://localhost:18000/info` through `make -C dev port-forward` returns the service name.
2. Evidence: the commit SHA, the command output, the date.

## 2. uat through the pipeline only

1. Actions -> `env-provision` in `shp-infra` -> environment `uat`, sizing `default`. The `operator` job passes only for the repo owner.
2. Read the plan summary of the `plan` job before the apply job runs. Expect no destroy or replace on a first run.
3. After apply the workflow installs Argo CD and applies `shp-gitops/bootstrap/root-uat.yaml`. Wait until every Application is `Healthy`:
   `kubectl -n argocd get applications`.
4. Merge a change in the application repo that touches the sample service: `svc-<name>` builds the image, scans it (Trivy,
   High/Critical block), signs it with cosign keyless, pushes it to ACR, and `gitops-promote` commits the digest to
   `shp-gitops` `envs/uat/<svc>/values.yaml`. **Nothing is deployed by hand.**
5. Argo CD syncs; `kubectl -n shp-apps get pods` shows the new digest running.
6. Seed synthetic data and run the smoke test (the remaining half of ENV-01's "Done when": none exists yet — write it as part of this step and record it in the evidence).
7. Evidence: the workflow run URLs, the plan summary, the digest in git and in the pod (`kubectl -n shp-apps get deploy <svc> -o jsonpath='{.spec.template.spec.containers[0].image}'`), the time from merge to running.

## 3. prod (ephemeral)

1. `env-provision` -> `prod`.
2. Actions -> `promote-prod` -> the sample service. It opens a PR in `shp-gitops` that sets the digest that runs in uat. **Merging that PR is the approval.**
3. Argo CD syncs prod; same checks as 2.5.
4. Evidence: the PR, the merge time, the running digest in prod.

## 4. Rollback in under 5 minutes

1. Note the time `T0`. In `shp-gitops`: `git revert <promote commit>`, open the PR, merge it (a revert PR is a normal PR; it still needs review).
2. Watch `kubectl -n argocd get application <svc> -w` until it is `Synced` and `Healthy` on the previous digest. Note `T1`.
3. Pass if `T1 - T0` < 5 minutes. Record both times and the digests before and after.

## 5. Unsigned image is blocked

1. Take an image already in ACR and push the same content under a new tag **without** signing it (`docker pull`, `docker tag`, `docker push` with an ACR login).
2. Open a PR in `shp-gitops` that sets that digest in `envs/uat/<svc>/values.yaml`.
3. Expected: Argo CD cannot create the pods; `kubectl -n shp-apps get events` shows the Kyverno policy `shp-verify-signature` rejecting the admission, and the previous ReplicaSet keeps serving.
4. Revert the PR. Repeat once with a tag (not a digest): the policy `shp-disallow-latest` or the chart's digest requirement must stop it.
5. Evidence: the event text, the policy name, the PR.

## 6. Destroy and verify

1. Actions -> `env-destroy` -> environment, confirm `destroy-<environment>`. Both environments, one after the other.
2. Verify nothing remains: `az group list --query "[?tags.\"shp-env\"=='<env>'].name"` returns nothing, `az keyvault list-deleted` shows the soft-deleted vault (names carry a random suffix, so a rebuild does not collide).
3. Evidence: the run URL and the two command outputs.

## 7. Evidence sheet

| Item | Result | Date | Link or output | Done by |
|---|---|---|---|---|
| Local run | | | | |
| uat: provision, plan read, apply | | | | |
| uat: pipeline-only deploy, merge-to-running time | | | | |
| uat: smoke test | | | | |
| prod: promote PR merged, running digest | | | | |
| Rollback: T0, T1, elapsed | | | | |
| Unsigned image blocked: event text | | | | |
| Destroy uat and prod, nothing left | | | | |

Mark ENV-05 `done` in `tools/plan/status.py` only when every row has a result, through `harness status verify`.
