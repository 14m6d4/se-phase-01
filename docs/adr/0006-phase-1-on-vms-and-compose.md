# Phase 1 on VMs and Docker Compose, as a stepping stone to Kubernetes

Phase 1 hosts the backend on our own DL380 G9 (#35), in the OpenStack `cinema` project. It runs on three VMs built by Terraform, `proxy`, `app` and `db`, each running its containers with Docker Compose and deployed by hand from a runbook (#33, #34). Kubernetes is where this is headed, but not yet: it comes after a simple CI/CD pipeline exists. Until then, a cluster would add a control plane, an ingress controller, a storage driver for Cinder and a new way of debugging, all for a single app and a single database, and before anything deploys automatically. VMs and Compose are the smallest setup that gets the service onto real hardware behind TLS, and they are easy to inspect over SSH while the team learns the platform.

The phases are meant to be built in this order: VMs and a manual runbook first, then a CI/CD pipeline that replaces the runbook, then Kubernetes. Each step replaces one thing at a time.

## What carries over

- **The container image and its contract.** The app ships as `ghcr.io/14m6d4/se-phase-01:<sha>`, configured only through environment variables. A Deployment runs the same image with the same variables.
- **The network layout.** Each tier is reachable only from the one in front of it: tailnet → proxy:443, proxy → app:8080, app → db:5432. The security groups become NetworkPolicies with the same rules.
- **The data volume.** PostgreSQL's data lives on its own Cinder volume, not on a VM disk. It maps to a PersistentVolume, and the data can move with it.
- **Terraform** for everything OpenStack: networks, security groups, volumes, and later the cluster's nodes.
- **TLS through DNS-01**, with a wildcard certificate for `*.strycks.dpdns.org`, which cert-manager can issue the same way.

## What gets thrown away

- The per-role `compose.yml` files and the `.env` files on each VM.
- Installing Docker with cloud-init, and caring for each VM by hand: SSH keys added by hand, manual reboots, rebuilds with `terraform apply -replace`.
- `/etc/hosts` for names between VMs; Kubernetes Services replace it.
- The manual deploy and rollback runbook; CI/CD replaces it first.
- SSH through `ProxyJump` as the way in; `kubectl` replaces it.

## Consequences

- There's no redundancy. One `app` and one `db` mean a reboot or a rebuild is downtime, so automatic reboots are off and rebuilds only happen when someone runs them on purpose.
- Terraform never replaces a VM by itself: rebuilds are expensive on this hardware, so running VMs can lag behind the cloud-init template (`docs/infra/terraform.md`).
- Anything added in phase 1 should go into the image, the environment, the network rules or the volume. Things in those four carry over. Things on a VM's disk don't.
