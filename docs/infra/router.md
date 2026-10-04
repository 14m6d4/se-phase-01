# Router: VLANs, zones, firewall and Tailscale

The OpenWrt Router routes the homelab VLANs, runs their firewall and DHCP, and is the tailnet's subnet router. The Ruijie Switch only carries the VLANs (see `switch.md`).

| | |
|---|---|
| Device | OpenWrt Router (LuCI web UI, SSH, `uci`) |
| Management | `https://10.10.10.1` and `ssh root@10.10.10.1` |

## Ports

| Port | Connected to | VLANs |
|---|---|---|
| `wan` | ISP router | — (DHCP client; address reserved on the ISP router) |
| `lan1` | Ruijie Switch port 16 | 1 untagged, 10/20/30 tagged |
| `lan2` | — | none (unused) |
| `lan3` | Rescue port, for a laptop | 1 untagged |

All three LAN ports sit on the `br-lan` bridge; VLAN filtering on the bridge decides what each port carries.

## Interfaces

| Interface | Device | VLAN | Address | Zone | DHCP |
|---|---|---|---|---|---|
| `lan` | `br-lan.1` | 1 RESCUE | `192.168.99.1/24` | `lan` | `.100–.249` |
| `MGMT_TEST` | `br-lan.10` | 10 MGMT | `10.10.10.1/24` | `mgmt_test` | off |
| `SERVICES` | `br-lan.20` | 20 SERVICES | `10.10.20.1/24` | `services` | `.100–.199` |
| `WORKLOAD` | `br-lan.30` | 30 WORKLOAD | `10.10.30.1/24` | `workload` | `.50–.89` |
| `wan` | `wan` | — | DHCP from the ISP router | `wan` | — |
| `tailscale` | `tailscale0` | — | tailnet address | `tailscale` | — |

`MGMT_TEST` is the MGMT VLAN; the name is historical. VLAN 10 hosts use static addresses (see `switch.md`), so DHCP is off there.

### VLAN 30 address plan

VLAN 30 is the Neutron external network, shared by every OpenStack project. Its addresses are split so that each user of the subnet has a range of its own:

| Range | Use |
|---|---|
| `.1` | OpenWrt Router |
| `.16–.31` (`10.10.30.16/28`) | Cinema project floating IPs, outside every pool. An admin creates them with an explicit address. The proxy is `.17` |
| `.50–.89` | OpenWrt Router DHCP |
| `.100–.199` | Neutron floating-IP allocation pool (other projects) |

The tailnet policy grants team members the cinema range only, so other projects' floating IPs stay out of their reach (see [Tailscale](#tailscale)).

## Zones

| Zone | Networks | Input | Forward | Forwards to | Masquerade |
|---|---|---|---|---|---|
| `lan` | `lan` | ACCEPT | ACCEPT | — (switch UI only, by rule) | no |
| `wan` | `wan` | REJECT | DROP | — | yes |
| `mgmt_test` | `MGMT_TEST` | ACCEPT | REJECT | `wan` | no |
| `services` | `SERVICES` | REJECT | REJECT | `wan` | no |
| `workload` | `WORKLOAD` | REJECT | REJECT | `wan` | no |
| `tailscale` | `tailscale` | REJECT | REJECT | — (VLANs by rule) | no |

Defaults: input REJECT, output ACCEPT, forward REJECT, SYN-flood protection on.

There is no forwarding between the internal VLANs. A host that needs two VLANs gets a NIC on each (the DL380 G9 has `eno1`–`eno3`). The internal VLANs reach the internet through `wan` but not the home LAN `192.168.1.0/24` behind the ISP router.

## Firewall rules

Rules other than the OpenWrt defaults, by zone:

| Name | From | To | Match | Action |
|---|---|---|---|---|
| `Allow-Rescue-Switch` | `lan` | `mgmt_test` | `10.10.10.2` tcp 80 | ACCEPT |
| `Allow-DHCP-Services` | `services` | router | udp 67 | ACCEPT |
| `Allow-DNS-Services` | `services` | router | `10.10.20.1` port 53 | ACCEPT |
| `Block-Services-HomeLAN` | `services` | `wan` | `192.168.1.0/24` | REJECT |
| `Allow-DHCP-Workload` | `workload` | router | udp 67 | ACCEPT |
| `Allow-DNS-Workload` | `workload` | router | `10.10.30.1` port 53 | ACCEPT |
| `Block-Workload-HomeLAN` | `workload` | `wan` | `192.168.1.0/24` | REJECT |
| `Block-MGMT-HomeLAN` | `mgmt_test` | `wan` | `192.168.1.0/24` | REJECT |
| `Allow-Admin-OpenWrt` | `wan` | router | from `192.168.1.6` | ACCEPT |
| `Allow-Admin-Homelab` | `wan` | any | from `192.168.1.6` to `10.10.0.0/16` | ACCEPT |
| `Allow-Tailscale-Router` | `tailscale` | router | tcp 22, 80, 443 | ACCEPT |
| `Allow-Tailscale-MGMT` | `tailscale` | `mgmt_test` | `10.10.10.0/24` | ACCEPT |
| `Allow-Tailscale-Services` | `tailscale` | `services` | `10.10.20.0/24` | ACCEPT |
| `Allow-Tailscale-Workload` | `tailscale` | `workload` | `10.10.30.0/24` | ACCEPT |
| `Block-All-IPv6-Forward` | any | any | IPv6 | REJECT |
| `Block-All-IPv6-Input` | any | router | IPv6 | REJECT |

- **VLAN 20 and 30 → router:** DHCP and DNS only. Ping to their `.1` gets `Destination Port Unreachable`.
- **Home LAN admin:** `192.168.1.6` is the admin workstation, reserved on the ISP router. It reaches `10.10.0.0/16` through a static route via the OpenWrt Router's WAN address, also reserved.
- **Tailnet:** the router firewall lets the whole tailnet into the three VLANs and onto the router's management ports. *Who* gets *what* is decided by the tailnet policy alone, which `tailscaled` enforces on this router before a packet reaches a VLAN.
- **IPv6** is off: blocked at the router, no WAN6.
- **OpenWrt defaults kept:** `Allow-DHCP-Renew`, `Allow-IGMP`, `Allow-IPSec-ESP`, `Allow-ISAKMP`. **Defaults disabled:** `Allow-Ping`, `Allow-DHCPv6`, `Allow-MLD`, `Allow-ICMPv6-Input`, `Allow-ICMPv6-Forward`.

## Tailscale

The router runs `tailscaled` as a subnet router:

| | |
|---|---|
| Advertised routes | `10.10.10.0/24`, `10.10.20.0/24`, `10.10.30.0/24` (approved in the admin console) |
| SNAT | on: VLAN hosts see tailnet traffic coming from `.1` |
| Exit node | no |

The tailnet policy lives in [`tailscale-acl.hujson`](tailscale-acl.hujson):

| Who | Reaches |
|---|---|
| `group:admin` (the tailnet owner) | everything: every tailnet device and all three VLANs |
| `group:team` (team members) | `10.10.20.12` (Horizon on 443, OpenStack APIs), `10.10.30.16/28` (cinema floating IPs), any port |
| every member | their own devices |

The repo copy holds placeholder logins (`admin@example.com`, `teammate@example.com`). To apply it, copy the file into **Admin console → Access controls**, replace the placeholders with real logins, and save. Saving runs the `tests` block, and the console refuses a policy whose tests fail. Add a new team member by adding their login to `group:team`.

Isolation between OpenStack projects has two layers. Keystone keeps team members inside the cinema project in Horizon and the APIs (see `openstack.md`, #32). The tailnet policy keeps their traffic to the cinema range.

## Reaching the router

- **Normal:** `https://10.10.10.1` or `ssh root@10.10.10.1`, from VLAN 10 or as `group:admin` on the tailnet.
- **Home LAN:** from the admin workstation `192.168.1.6`.
- **Rescue:** plug a laptop into `lan3`. It gets a `192.168.99.0/24` lease, and reaches the router at `192.168.99.1` and the switch UI at `http://10.10.10.2`.

## Rebuild from factory defaults

Run from a laptop on `lan3`. The stock LAN address is `192.168.1.1`, the same subnet as the home LAN, so keep `wan` unplugged until step 1 is committed. Then reconnect to `192.168.99.1`.

1. **Bridge VLANs and interfaces:**

   ```sh
   uci batch <<'EOF'
   set network.lan.device='br-lan.1'
   delete network.lan.ipaddr
   delete network.lan.netmask
   add_list network.lan.ipaddr='192.168.99.1/24'
   add network bridge-vlan
   set network.@bridge-vlan[-1].device='br-lan'
   set network.@bridge-vlan[-1].vlan='1'
   add_list network.@bridge-vlan[-1].ports='lan1:u*'
   add_list network.@bridge-vlan[-1].ports='lan3:u*'
   add network bridge-vlan
   set network.@bridge-vlan[-1].device='br-lan'
   set network.@bridge-vlan[-1].vlan='10'
   add_list network.@bridge-vlan[-1].ports='lan1:t'
   add network bridge-vlan
   set network.@bridge-vlan[-1].device='br-lan'
   set network.@bridge-vlan[-1].vlan='20'
   add_list network.@bridge-vlan[-1].ports='lan1:t'
   add network bridge-vlan
   set network.@bridge-vlan[-1].device='br-lan'
   set network.@bridge-vlan[-1].vlan='30'
   add_list network.@bridge-vlan[-1].ports='lan1:t'
   set network.MGMT_TEST=interface
   set network.MGMT_TEST.proto='static'
   set network.MGMT_TEST.device='br-lan.10'
   add_list network.MGMT_TEST.ipaddr='10.10.10.1/24'
   set network.SERVICES=interface
   set network.SERVICES.proto='static'
   set network.SERVICES.device='br-lan.20'
   add_list network.SERVICES.ipaddr='10.10.20.1/24'
   set network.WORKLOAD=interface
   set network.WORKLOAD.proto='static'
   set network.WORKLOAD.device='br-lan.30'
   add_list network.WORKLOAD.ipaddr='10.10.30.1/24'
   set network.wan6.disabled='1'
   commit network
   EOF
   ```

2. **DHCP pools:**

   ```sh
   uci batch <<'EOF'
   set dhcp.MGMT_TEST=dhcp
   set dhcp.MGMT_TEST.interface='MGMT_TEST'
   set dhcp.MGMT_TEST.ignore='1'
   set dhcp.SERVICES=dhcp
   set dhcp.SERVICES.interface='SERVICES'
   set dhcp.SERVICES.start='100'
   set dhcp.SERVICES.limit='100'
   set dhcp.SERVICES.leasetime='12h'
   set dhcp.WORKLOAD=dhcp
   set dhcp.WORKLOAD.interface='WORKLOAD'
   set dhcp.WORKLOAD.start='50'
   set dhcp.WORKLOAD.limit='40'
   set dhcp.WORKLOAD.leasetime='12h'
   commit dhcp
   EOF
   ```

3. **Zones and forwardings.** The stock `lan → wan` forwarding is removed: the rescue zone reaches the switch UI only.

   ```sh
   uci batch <<'EOF'
   delete firewall.@forwarding[0]
   add firewall zone
   set firewall.@zone[-1].name='mgmt_test'
   set firewall.@zone[-1].input='ACCEPT'
   set firewall.@zone[-1].output='ACCEPT'
   set firewall.@zone[-1].forward='REJECT'
   add_list firewall.@zone[-1].network='MGMT_TEST'
   add firewall zone
   set firewall.@zone[-1].name='services'
   set firewall.@zone[-1].input='REJECT'
   set firewall.@zone[-1].output='ACCEPT'
   set firewall.@zone[-1].forward='REJECT'
   add_list firewall.@zone[-1].network='SERVICES'
   add firewall zone
   set firewall.@zone[-1].name='workload'
   set firewall.@zone[-1].input='REJECT'
   set firewall.@zone[-1].output='ACCEPT'
   set firewall.@zone[-1].forward='REJECT'
   add_list firewall.@zone[-1].network='WORKLOAD'
   add firewall zone
   set firewall.@zone[-1].name='tailscale'
   set firewall.@zone[-1].input='REJECT'
   set firewall.@zone[-1].output='ACCEPT'
   set firewall.@zone[-1].forward='REJECT'
   add_list firewall.@zone[-1].network='tailscale'
   add firewall forwarding
   set firewall.@forwarding[-1].src='mgmt_test'
   set firewall.@forwarding[-1].dest='wan'
   add firewall forwarding
   set firewall.@forwarding[-1].src='services'
   set firewall.@forwarding[-1].dest='wan'
   add firewall forwarding
   set firewall.@forwarding[-1].src='workload'
   set firewall.@forwarding[-1].dest='wan'
   commit firewall
   EOF
   ```

4. **Rules.** Add each rule in the [Firewall rules](#firewall-rules) table under **Network → Firewall → Traffic Rules**, or with `uci`. For example:

   ```sh
   uci batch <<'EOF'
   add firewall rule
   set firewall.@rule[-1].name='Allow-Tailscale-Router'
   set firewall.@rule[-1].src='tailscale'
   set firewall.@rule[-1].proto='tcp'
   set firewall.@rule[-1].dest_port='22 80 443'
   set firewall.@rule[-1].target='ACCEPT'
   commit firewall
   EOF
   ```

   Then disable the five defaults listed above (`set firewall.@rule[N].enabled='0'`) and run `service firewall reload`.

5. **Tailscale:** install the `tailscale` package, then:

   ```sh
   uci batch <<'EOF'
   set network.tailscale=interface
   set network.tailscale.proto='none'
   set network.tailscale.device='tailscale0'
   commit network
   EOF
   tailscale up --advertise-routes=10.10.10.0/24,10.10.20.0/24,10.10.30.0/24
   ```

   Approve the three routes on the router's machine page in the admin console, and apply [`tailscale-acl.hujson`](tailscale-acl.hujson).

6. Set a root password (kept outside this repo), then plug `wan` back in.

## Verify

Run [`verify-router.sh`](verify-router.sh) from the admin workstation after any change to the router or the tailnet policy. It checks the running config against this doc over SSH and writes a Markdown report to paste on the issue. `verify-router.sh member`, run on a `group:team` member's device, probes what the tailnet policy lets that member reach.
