# Each tier is reachable only from the one in front of it:
#
#   tailnet → proxy:443, proxy:22
#   proxy   → app:8080, app:22, db:22
#   app     → db:5432
#
# No VM gets the project's default group: it allows all traffic between its
# members, which would let proxy reach db:5432. Each group keeps Neutron's
# default allow-all egress, for apt and image pulls.

resource "openstack_networking_secgroup_v2" "proxy" {
  name        = "sg-proxy"
  description = "proxy: 443 and 22 from the tailnet"
}

resource "openstack_networking_secgroup_v2" "app" {
  name        = "sg-app"
  description = "app: 8080 and 22 from proxy"
}

resource "openstack_networking_secgroup_v2" "db" {
  name        = "sg-db"
  description = "db: 5432 from app, 22 from proxy"
}

resource "openstack_networking_secgroup_rule_v2" "proxy" {
  for_each = {
    for pair in setproduct(var.proxy_sources, [22, 443]) : "${pair[0]}:${pair[1]}" => pair
  }

  security_group_id = openstack_networking_secgroup_v2.proxy.id
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = each.value[1]
  port_range_max    = each.value[1]
  remote_ip_prefix  = each.value[0]
}

resource "openstack_networking_secgroup_rule_v2" "app" {
  for_each = toset(["22", "8080"])

  security_group_id = openstack_networking_secgroup_v2.app.id
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = tonumber(each.key)
  port_range_max    = tonumber(each.key)
  remote_group_id   = openstack_networking_secgroup_v2.proxy.id
}

resource "openstack_networking_secgroup_rule_v2" "db_ssh" {
  security_group_id = openstack_networking_secgroup_v2.db.id
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = 22
  port_range_max    = 22
  remote_group_id   = openstack_networking_secgroup_v2.proxy.id
}

resource "openstack_networking_secgroup_rule_v2" "db_postgres" {
  security_group_id = openstack_networking_secgroup_v2.db.id
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = 5432
  port_range_max    = 5432
  remote_group_id   = openstack_networking_secgroup_v2.app.id
}
