locals {
  # Written into /etc/hosts on every VM, so they reach each other by name.
  hosts = { for name, vm in var.vms : name => vm.fixed_ip }

  user_data = {
    for name in keys(var.vms) : name => templatefile("${path.module}/cloud-init/cloud-config.yaml.tftpl", {
      hostname = name
      hosts    = local.hosts
      # Cinder volumes show up as /dev/disk/by-id/virtio-<first 20 chars of the ID>.
      pgdata_serial = name == "db" ? substr(openstack_blockstorage_volume_v3.pgdata.id, 0, 20) : ""
    })
  }
}

data "openstack_compute_flavor_v2" "vm" {
  for_each = var.vms
  name     = each.value.flavor
}

resource "openstack_compute_keypair_v2" "admin" {
  name       = "cinema-terraform"
  public_key = file(pathexpand(var.ssh_public_key_path))
}

# PostgreSQL's data. It outlives db: a rebuilt db mounts it again.
resource "openstack_blockstorage_volume_v3" "pgdata" {
  name        = "pgdata"
  description = "PostgreSQL data for db, mounted at /srv/pgdata"
  size        = var.pgdata_size_gb

  lifecycle {
    prevent_destroy = true
  }
}

module "proxy" {
  source = "./modules/vm"

  name               = "proxy"
  flavor_id          = data.openstack_compute_flavor_v2.vm["proxy"].id
  image_id           = data.openstack_images_image_v2.base.id
  root_size_gb       = var.vms.proxy.root_size_gb
  network_id         = data.openstack_networking_network_v2.tenant.id
  subnet_id          = data.openstack_networking_subnet_v2.tenant.id
  fixed_ip           = var.vms.proxy.fixed_ip
  security_group_ids = [openstack_networking_secgroup_v2.proxy.id]
  key_pair           = openstack_compute_keypair_v2.admin.name
  user_data          = local.user_data.proxy
}

module "app" {
  source = "./modules/vm"

  name               = "app"
  flavor_id          = data.openstack_compute_flavor_v2.vm["app"].id
  image_id           = data.openstack_images_image_v2.base.id
  root_size_gb       = var.vms.app.root_size_gb
  network_id         = data.openstack_networking_network_v2.tenant.id
  subnet_id          = data.openstack_networking_subnet_v2.tenant.id
  fixed_ip           = var.vms.app.fixed_ip
  security_group_ids = [openstack_networking_secgroup_v2.app.id]
  key_pair           = openstack_compute_keypair_v2.admin.name
  user_data          = local.user_data.app
}

module "db" {
  source = "./modules/vm"

  name               = "db"
  flavor_id          = data.openstack_compute_flavor_v2.vm["db"].id
  image_id           = data.openstack_images_image_v2.base.id
  root_size_gb       = var.vms.db.root_size_gb
  network_id         = data.openstack_networking_network_v2.tenant.id
  subnet_id          = data.openstack_networking_subnet_v2.tenant.id
  fixed_ip           = var.vms.db.fixed_ip
  security_group_ids = [openstack_networking_secgroup_v2.db.id]
  extra_volume_ids   = [openstack_blockstorage_volume_v3.pgdata.id]
  key_pair           = openstack_compute_keypair_v2.admin.name
  user_data          = local.user_data.db
}

resource "openstack_networking_floatingip_associate_v2" "proxy" {
  floating_ip = data.openstack_networking_floatingip_v2.proxy.address
  port_id     = module.proxy.port_id
}
