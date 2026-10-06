# Everything here already exists (#32). Terraform reads it and never manages it.

data "openstack_networking_network_v2" "tenant" {
  name = var.network_name
}

data "openstack_networking_subnet_v2" "tenant" {
  name       = var.subnet_name
  network_id = data.openstack_networking_network_v2.tenant.id
}

data "openstack_networking_network_v2" "external" {
  name     = var.external_network_name
  external = true
}

data "openstack_networking_floatingip_v2" "proxy" {
  address = var.floating_ip
  # Despite its name, pool filters on the network's ID.
  pool = data.openstack_networking_network_v2.external.id
}

data "openstack_images_image_v2" "base" {
  name        = var.image_name
  most_recent = true
}
