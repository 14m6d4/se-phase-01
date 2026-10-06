# One VM: a port with a fixed IP, and an instance booting from a root volume
# created from the image.
#
# The port is its own resource, so the address, the security groups and any
# floating IP stay when the instance is replaced.

resource "openstack_networking_port_v2" "this" {
  name               = var.name
  network_id         = var.network_id
  security_group_ids = var.security_group_ids

  fixed_ip {
    subnet_id  = var.subnet_id
    ip_address = var.fixed_ip
  }
}

resource "openstack_compute_instance_v2" "this" {
  name      = var.name
  flavor_id = var.flavor_id
  key_pair  = var.key_pair
  user_data = var.user_data

  network {
    port = openstack_networking_port_v2.this.id
  }

  # Root volume, deleted with the instance.
  block_device {
    uuid                  = var.image_id
    source_type           = "image"
    destination_type      = "volume"
    volume_size           = var.root_size_gb
    boot_index            = 0
    delete_on_termination = true
  }

  dynamic "block_device" {
    for_each = var.extra_volume_ids
    content {
      uuid                  = block_device.value
      source_type           = "volume"
      destination_type      = "volume"
      boot_index            = -1
      delete_on_termination = false
    }
  }

  # Rebuilding a VM is expensive on this hardware, so a new image or a
  # cloud-init change never replaces one by itself. Rebuild on purpose with
  # terraform apply -replace=module.<name>.openstack_compute_instance_v2.this
  lifecycle {
    ignore_changes = [user_data, block_device]
  }
}
