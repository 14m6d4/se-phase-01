# Terraform: the proxy, app and db VMs

Terraform builds the three VMs the backend runs on (#34), inside the `cinema` project from `openstack.md`. Only `proxy` faces the tailnet. Why VMs and Compose for now: [ADR-0006](../adr/0006-phase-1-on-vms-and-compose.md).

| | |
|---|---|
| Code | [`infra/terraform/`](../../infra/terraform/), with the [`vm` module](../../infra/terraform/modules/vm/) |
| State | Local `terraform.tfstate`, gitignored, on the workstation that runs `apply` |
| Credentials | An application credential for `cinema`, in `~/.config/openstack/clouds.yaml` |
| Base image | `Debian 13` genericcloud, default user `debian` |

## What it builds

| VM | Flavor | Root volume | Address | Security group |
|---|---|---|---|---|
| `proxy` | `c1.small` | 10 GB | `192.168.100.10`, floating IP `10.10.30.17` | `sg-proxy`: tcp 443, 22 from the tailnet |
| `app` | `c2.medium` | 20 GB | `192.168.100.20` | `sg-app`: tcp 8080, 22 from `sg-proxy` |
| `db` | `c2.medium` | 20 GB | `192.168.100.30` | `sg-db`: tcp 5432 from `sg-app`, 22 from `sg-proxy` |

Plus a 20 GB volume `pgdata` attached to `db` and the keypair `cinema-terraform`. Sizes and addresses are the `vms` variable.

Terraform only reads what #32 created: `cinema-net`, `cinema-subnet`, `external-net`, the floating IP, the image and the flavors.

**Security groups:**
- "From the tailnet" means `10.10.30.1/32`. The router masquerades tailnet traffic, so VMs see it coming from the router (`router.md` → Tailscale). The admin workstation `192.168.1.6/32` is allowed too, because the router already lets it into `10.10.0.0/16`.
- No VM has the project's `default` group. Its "all traffic between members" rule would let `proxy` reach `db:5432`.
- Outbound traffic is open on every group, for apt and image pulls.
- A blocked connection times out instead of being refused: security groups drop packets.

**cloud-init** ([`cloud-config.yaml.tftpl`](../../infra/terraform/cloud-init/cloud-config.yaml.tftpl), no secrets) on every VM:
- Docker CE and the Compose plugin, from Docker's apt repo
- Docker logs `json-file`, 10 MB × 3 per container; journald capped at 100 MB
- SSH with keys only, no root login
- `unattended-upgrades` for Debian security updates and Docker only, with no automatic reboot (reboot by hand, see the runbook in #34)
- `/etc/hosts` lists `proxy`, `app` and `db`, so the VMs reach each other by name
- on `db`: `pgdata` is formatted (only if it has no filesystem yet) and mounted at `/srv/pgdata`

## Before the first apply

1. **Image:** an admin runs `verify-openstack.sh`, which uploads `Debian 13`.
2. **Credential:** in Horizon, with `cinema` selected, go to **Identity → Application Credentials → Create**, name it `terraform`, and download the `clouds.yaml`. Put it in `~/.config/openstack/clouds.yaml` (mode 600) with the entry renamed to `cinema`. [`clouds.yaml.example`](../../infra/terraform/clouds.yaml.example) shows the shape.
3. **Key:** the public key for `debian`, by default `~/.ssh/id_ed25519.pub` (variable `ssh_public_key_path`).
4. **Tailnet:** the workstation reaches `openstack.strycks.dpdns.org` and `10.10.30.17`.

## Apply

```sh
cd infra/terraform
terraform init
terraform plan
terraform apply
terraform output -raw ssh_config >> ~/.ssh/config
```

The `ssh_config` output adds `cinema-proxy`, plus `cinema-app` and `cinema-db` through `ProxyJump cinema-proxy`:

```sh
ssh cinema-db
```

`app` and `db` have no floating IP, so the jump through `proxy` is the only way in.

**Team members' SSH keys** are added by hand, after the VM has been tested: append each key to `/home/debian/.ssh/authorized_keys`. A rebuild loses them, so add them again afterwards.

## Changing things later

A VM is never replaced on its own. The `vm` module ignores later changes to the user data, the root volume's image and its size. Changing the cloud-init template, the image (a newer `Debian 13`, or a new Debian release), or a root volume's size does nothing to a running VM. A running VM can therefore lag behind the template. Fix small things over SSH, and put the same change in the template so the next rebuild has it.

**Rebuild one VM on purpose** by replacing its root volume. The instance is replaced with it:

```sh
terraform apply -replace=module.app.openstack_blockstorage_volume_v3.root
```

Replacing only the instance would boot the new one from the old disk.

The root volume is created and waited for before the instance boots: up to 60 minutes. On this host's disks, letting Nova create it at boot fails after about 3 minutes, leaving the instance in `ERROR`. Cinder's image-volume cache (`openstack.md`) makes later volumes from the same image quick clones.

- **Kept:** the port, so the fixed IP, the security groups and the floating IP, and on `db` the `pgdata` volume.
- **Lost:** the root volume and anything done by hand. That includes team members' keys and the SSH host key, so remove the old one with `ssh-keygen -R`.
- Stop the containers on `db` before rebuilding it, so PostgreSQL shuts down cleanly.

Flavor changes resize the instance in place, with a reboot. Security-group changes apply to the port directly. Neither one rebuilds the VM.

**`pgdata` can't be destroyed by Terraform** (`prevent_destroy`), so `terraform destroy` stops with an error. To really delete it, remove the `lifecycle` block first, deliberately.

## Verify

Run [`verify-terraform.sh`](verify-terraform.sh) from the workstation after `apply`. It changes nothing, and writes a Markdown report to paste on the issue. It checks:

- `terraform fmt`, `validate`, and that `plan` shows no changes
- SSH to `proxy` directly, and to `app` and `db` only through it, with cloud-init finished
- Docker, Compose v2, the log limits, journald, sshd and `unattended-upgrades` on each VM
- `/srv/pgdata` mounted on `db`
- `db:5432` connects from `app` and times out from `proxy`. Until PostgreSQL runs there (#34), the script listens on the port for 30 s.
