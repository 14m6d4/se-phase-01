# OpenStack: RAID1 host, Kolla-Ansible, the cinema project

The DL380 G9 runs an all-in-one OpenStack on two mirrored disks. The switch and router carry its three VLANs (see `switch.md` and `router.md`).

| | |
|---|---|
| Host | DL380 G9, Ubuntu 24.04, 56 CPUs, 64 GB RAM, two 1 TB SATA HDDs |
| OpenStack | 2026.1, deployed by Kolla-Ansible 22 (all-in-one) |
| Dashboard and APIs | `https://openstack.strycks.dpdns.org` → VIP `10.10.20.12` |
| Host management | `ssh hl-server` (`10.10.10.11`, VLAN 10) |

## Disks

The B140i software RAID is off: the embedded SATA controller runs in AHCI mode (`ahci` driver), and Linux mdadm mirrors the disks. Both disks are partitioned alike:

| Partition | Size | Use |
|---|---|---|
| `sda1`, `sdb1` | 1G | EFI system partition (ESP), one per disk |
| `sda2` + `sdb2` | 280G | `md0` RAID1 → ext4 `/` (host OS, containers, `/swap.img`) |
| `sda3` + `sdb3` | 650G | `md1` RAID1 → LVM VG `cinder-volumes` → thin pool `cinder-volumes-pool` (Cinder volumes) |

Both arrays are listed in `/etc/mdadm/mdadm.conf`. Ubuntu's `mdadm` package runs a monthly consistency check, so `mdadm --detail` shows `clean, checking` for a few hours each month. `active` just means the array is being written to.

## Boot

Either disk boots the host on its own:

- **Both ESPs hold the same files.** GRUB's `grub-efi/install_devices` lists both ESPs, so every GRUB or shim update is written to both.
- **Both have a firmware boot entry:** two `Ubuntu` entries, one per ESP, each pointing at `\EFI\ubuntu\shimx64.efi`, both at the front of `BootOrder`.
- **`/boot/efi` mounts one ESP with `nofail,x-systemd.device-timeout=10s`.** If that disk is gone, boot carries on instead of stopping in the emergency shell.

When a disk is replaced:
1. Copy the partition table from the good disk (`sgdisk -R`, then `sgdisk -G`).
2. Re-add its partitions to `md0` and `md1` (`mdadm --add`).
3. Run `dpkg-reconfigure grub-efi-amd64` and tick both ESPs, which reinstalls GRUB and recreates the boot entry.

See the [Ubuntu RAID notes](https://help.ubuntu.com/community/Installation/SoftwareRAID) and `man mdadm`.

## Kolla-Ansible

| | |
|---|---|
| Install | `~/kolla-venv` (Kolla-Ansible and the OpenStack client), inventory `~/all-in-one` |
| Config | [`kolla/globals.yml`](kolla/globals.yml) → `/etc/kolla/globals.yml` |
| Secrets | `/etc/kolla/passwords.yml`, `/etc/kolla/certificates/`, `admin-openrc*.sh`, `clouds.yaml`: on the host only |
| Override | `/etc/kolla/config/cinder/cinder-volume.conf` (below) |

Interfaces:

| NIC | VLAN | Role |
|---|---|---|
| `eno1` | 10 MGMT | Host OS `10.10.10.11`, default route |
| `eno2` | 20 SERVICES | `network_interface`: host `10.10.20.11`, keepalived VIP `10.10.20.12`. Every API, the database and RabbitMQ listen here |
| `eno3` | 30 WORKLOAD | `neutron_external_interface`: port of the OVS bridge `br-ex`, no host address |

Services: Keystone, Glance, Nova, Neutron (Open vSwitch agent, L3, DHCP and metadata agents), Cinder (LVM backend over iSCSI, no backup service), Placement, Heat and Horizon, behind HAProxy and keepalived on the VIP, with MariaDB through ProxySQL and RabbitMQ.

**TLS:**
- Internal TLS is on, and Kolla generated its own CA and certificate. Every endpoint is `https://openstack.strycks.dpdns.org:<port>`.
- Clients trust the CA from `/etc/kolla/certificates/ca/root.crt` on the host: `OS_CACERT`, or `cacert_file` in Terraform.
- The CA certificate is public. The keys next to it are not.
- `admin-openrc.sh` and `clouds.yaml` are written by `kolla-ansible post-deploy`. Re-run it after every TLS or endpoint change, or they keep the old URLs.

**Cinder override:** it turns on the image-volume cache, so booting from volume doesn't copy the image every time. The cache volumes are owned by Cinder's internal tenant, which is the `service` project and the `cinder` user:

```ini
[DEFAULT]
cinder_internal_tenant_project_id = <id of the service project>
cinder_internal_tenant_user_id = <id of the cinder user>
[lvm-1]
image_volume_cache_enabled = true
image_volume_cache_max_size_gb = 100
image_volume_cache_max_count = 10
```

## Networks

| Network | Type | Subnet | Notes |
|---|---|---|---|
| `external-net` | flat on `br-ex` (`eno3`), shared, external | `10.10.30.0/24`, gateway `.1`, no DHCP | Allocation pool `.100–.199`. Routers' gateways and floating IPs live here |
| Tenant networks | VXLAN | per project | Each project has a router with SNAT to `external-net` |

The VLAN 30 address plan, including the cinema range `10.10.30.16/28` outside the pool, is in `router.md`. Projects other than `cinema` are out of scope for this repo.

## Images and flavors

Shared, public:

| Image | |
|---|---|
| `Ubuntu 24.04 LTS` | Ubuntu cloud image (qcow2), default user `ubuntu` |

| Flavor | vCPU | RAM | Disk |
|---|---|---|---|
| `c1.small` | 1 | 2 GB | 0 |
| `c2.medium` | 2 | 4 GB | 0 |
| `c2.large` | 2 | 8 GB | 0 |

`Disk 0` means a VM boots from a Cinder volume whose size is set per VM, on the `md1` thin pool. Nova refuses non-admins an image boot on a `disk 0` flavor, so VM disks never land on the host's root filesystem.

## The cinema project

| | |
|---|---|
| Project | `cinema` (tag `infra-32`) |
| Access | Keystone group `cinema-team` with role `member`. The admin user also has `member`, to work inside the project |
| Network | `cinema-net` / `cinema-subnet` `192.168.100.0/24` (DNS `1.1.1.1`, `8.8.8.8`) → `cinema-router` → `external-net` |
| Floating IPs | `10.10.30.17` (proxy, #33). Created by an admin with an explicit address |
| Quotas | 6 instances, 12 vCPU, 24 GB RAM, 10 volumes / 200 GB, 1 router, floating IPs = the number reserved |

Team members never get `admin` on any project: in Keystone, `admin` on one project is admin over the whole cloud. The floating-IP quota equals the addresses an admin has reserved, so members can't take addresses from the shared pool. That keeps `cinema` inside `10.10.30.16/28`, the only VLAN 30 range the tailnet policy grants them.

**Add a team member:** create the user in Horizon (**Identity → Users**, default domain, no primary project), hand over the password out-of-band, then:

```sh
openstack group add user cinema-team <user>
```

They also need the tailnet login in `group:team` (`router.md`).

**Reserve another floating IP** (the next free address in `10.10.30.18–.30`):

```sh
openstack quota set --floating-ips <reserved + 1> cinema
openstack floating ip create --project cinema --floating-ip-address 10.10.30.18 external-net
```

## Rebuild from scratch

Commands and choices only. The linked docs carry the details.

1. **Firmware** (RBSU): embedded SATA in AHCI mode, UEFI boot.
2. **Ubuntu 24.04 Server**, custom storage layout, on both disks:
   - a 1G ESP on each disk (the second one as "Add as another boot device")
   - `md0` RAID1 over the two 280G partitions → ext4 `/`
   - `md1` RAID1 over the rest, left unformatted

   After the first boot:

   ```sh
   sudo pvcreate /dev/md1 && sudo vgcreate cinder-volumes /dev/md1
   sudo lvcreate -T -l 95%FREE cinder-volumes/cinder-volumes-pool
   sudo dpkg-reconfigure grub-efi-amd64   # tick both ESPs
   # /etc/fstab: add nofail,x-systemd.device-timeout=10s to /boot/efi
   ```

3. **Network:** netplan with `eno1` `10.10.10.11/24` (gateway `10.10.10.1`), `eno2` `10.10.20.11/24`, `eno3` up without an address.
4. **Kolla-Ansible**, following the [quickstart](https://docs.openstack.org/kolla-ansible/latest/user/quickstart.html) for the 2026.1 release:

   ```sh
   python3 -m venv ~/kolla-venv && . ~/kolla-venv/bin/activate
   pip install 'kolla-ansible==22.*' python-openstackclient
   kolla-ansible install-deps
   sudo mkdir -p /etc/kolla && sudo chown $USER: /etc/kolla
   cp ~/kolla-venv/share/kolla-ansible/etc_examples/kolla/passwords.yml /etc/kolla/
   cp ~/kolla-venv/share/kolla-ansible/ansible/inventory/all-in-one ~/
   cp docs/infra/kolla/globals.yml /etc/kolla/globals.yml   # from this repo
   kolla-genpwd
   kolla-ansible certificates -i ~/all-in-one               # see the TLS guide
   kolla-ansible bootstrap-servers -i ~/all-in-one
   kolla-ansible prechecks -i ~/all-in-one
   kolla-ansible deploy -i ~/all-in-one
   kolla-ansible post-deploy -i ~/all-in-one
   ```

   Then write the Cinder override above and run `kolla-ansible reconfigure -i ~/all-in-one -t cinder`. The [TLS guide](https://docs.openstack.org/kolla-ansible/latest/admin/tls.html) covers the CA.
5. **External network, image, flavors** (as admin):

   ```sh
   openstack network create --external --share --provider-network-type flat \
     --provider-physical-network physnet1 external-net
   openstack subnet create --network external-net --subnet-range 10.10.30.0/24 \
     --gateway 10.10.30.1 --no-dhcp --allocation-pool start=10.10.30.100,end=10.10.30.199 external-subnet
   openstack image create --public --disk-format qcow2 --container-format bare \
     --file noble-server-cloudimg-amd64.img 'Ubuntu 24.04 LTS'
   openstack flavor create --public --vcpus 1 --ram 2048 --disk 0 c1.small
   openstack flavor create --public --vcpus 2 --ram 4096 --disk 0 c2.medium
   openstack flavor create --public --vcpus 2 --ram 8192 --disk 0 c2.large
   ```

   Check the image against Ubuntu's `SHA256SUMS` before uploading.
6. **The cinema project:** run `verify-openstack.sh`. It creates everything in [The cinema project](#the-cinema-project).

## Verify

Run [`verify-openstack.sh`](verify-openstack.sh) from the admin workstation after any change to the host, the Kolla config or the `cinema` project. It works over `ssh hl-server`, asks before each change, and writes a Markdown report to paste on the issue. It checks:

- RAID, boot setup and `fstab`
- `globals.yml` against the repo
- OpenStack services and agents
- the `cinema` project
- a throwaway VM on `10.10.30.17`

`verify-openstack.sh inventory` only reads, and prints everything this doc was written from.
