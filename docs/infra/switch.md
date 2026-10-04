# Switch: VLANs, trunk and access ports

The Ruijie Switch joins the DL380 G9 to the OpenWrt Router. It carries VLANs only; routing, DHCP and the firewall live on the OpenWrt Router (see `router.md`, #31).

| | |
|---|---|
| Device | Ruijie Switch (web UI only, no CLI or config export) |
| Management | `http://10.10.10.2` on VLAN 10 |

## Topology

```
ISP router ── OpenWrt Router WAN
              OpenWrt Router LAN1 ═══ trunk 1,10,20,30 ═══ port 16
                                                          │
                         ┌────────────────────────────────┤ Ruijie Switch
                         │ port 2  (VLAN 10) ── iLO
                         │ port 4  (VLAN 10) ── DL380 G9 eno1  (host OS)
                         │ port 6  (VLAN 20) ── DL380 G9 eno2  (OpenStack APIs)
                         │ port 8  (VLAN 30) ── DL380 G9 eno3  (Neutron external)
                         └────────────────────────────────
```

Only the even ports are cabled: the cables come in from below the switch.

## VLANs

| VLAN | Name | Subnet | Gateway | Purpose |
|---|---|---|---|---|
| 1 | RESCUE | — | — | Default VLAN. Native (untagged) on the trunk; home of the unused ports |
| 10 | MGMT | `10.10.10.0/24` | `10.10.10.1` | iLO, OpenStack host OS, router, switch |
| 20 | SERVICES | `10.10.20.0/24` | `10.10.20.1` | OpenStack APIs |
| 30 | WORKLOAD | `10.10.30.0/24` | `10.10.30.1` | Neutron external network: VM floating IPs |

The OpenWrt Router is `.1` on every VLAN and the only DHCP server. Infrastructure hosts use static addresses:

| Address | Host |
|---|---|
| `10.10.10.1` | OpenWrt Router |
| `10.10.10.2` | Ruijie Switch |
| `10.10.10.10` | iLO |
| `10.10.10.11` | OpenStack host OS (`eno1`) |

## Ports

Mode, permitted VLANs and native VLAN as set under **VLAN Settings → VLAN Settings**; status under **Homepage → Port Info**.

| Port | Status | Mode | Permit | Native | Connected to |
|---|---|---|---|---|---|
| 1 | Disabled | Access | 1 | 1 | — |
| 2 | Enabled | Access | 10 | 10 | iLO |
| 3 | Disabled | Access | 1 | 1 | — |
| 4 | Enabled | Access | 10 | 10 | DL380 G9 `eno1` |
| 5 | Disabled | Access | 1 | 1 | — |
| 6 | Enabled | Access | 20 | 20 | DL380 G9 `eno2` |
| 7 | Disabled | Access | 1 | 1 | — |
| 8 | Enabled | Access | 30 | 30 | DL380 G9 `eno3` |
| 9 | Disabled | Access | 1 | 1 | — |
| 10 | Disabled | Access | 1 | 1 | Reserved for DL380 G9 `eno4` |
| 11 | Disabled | Access | 1 | 1 | — |
| 12 | Disabled | Access | 20 | 20 | — (left over from a removed host) |
| 13 | Disabled | Access | 1 | 1 | — |
| 14 | Disabled | Access | 10 | 10 | — (left over from a removed host) |
| 15 | Disabled | Trunk | 1,10,20,30 | 1 | — (spare uplink) |
| 16 | Enabled | Trunk | 1,10,20,30 | 1 | OpenWrt Router LAN1 (uplink) |

Every unused port is disabled, so plugging a cable into one gives no access to any VLAN.

## Reaching the switch

- **Normal:** `http://10.10.10.2` from VLAN 10, or from the tailnet through the router.
- **Rescue:** the switch allows one management IP on one VLAN, so the switch has no address on VLAN 1. A laptop on the OpenWrt Router rescue port reaches `10.10.10.2:80` through a rescue-zone forwarding rule (see `router.md`, #31).
- **Last resort:** **System Settings → Restore Default**, then rebuild from the steps below.

## Rebuild from factory defaults

1. **VLAN Settings → VLAN Members:** switch VLAN Settings on; add VLAN IDs `10`, `20`, `30`.
2. **VLAN Settings → VLAN Settings:** set each port as in the [Ports](#ports) table. For a trunk, pick *Trunk*, permit `1,10,20,30`, native `VLAN 1`.
3. **Homepage → Port Info:** set Status to *Disabled* for every unused port.
4. **System Settings → Account Settings:** set a new admin password (kept outside this repo).
5. **System Settings → IP Settings:** VLAN `10`, Auto Obtain IP disabled, IP `10.10.10.2`, mask `255.255.255.0`, gateway and DNS `10.10.10.1`. The browser loses the switch here; reconnect from VLAN 10.

## Known issues

- Ports 2, 4, 6 and 8 negotiate **100M/Full**; the uplink on port 16 runs at 1000M. Suspected cable crosstalk; to be fixed by re-cabling.
