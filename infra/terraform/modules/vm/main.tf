# One VM: a port with a fixed IP, a root volume created from the image, and an
# instance booting from that volume.
#
# The port is its own resource, so the address, the security groups and any
# floating IP stay when the VM is rebuilt.

resource "openstack_networking_port_v2" "this" {
  name               = var.name
  network_id         = var.network_id
  security_group_ids = var.security_group_ids

  fixed_ip {
    subnet_id  = var.subnet_id
    ip_address = var.fixed_ip
  }
}

# The root volume is created first, and the instance boots only once it is
# available. Letting Nova create it at boot fails on this host's slow disks:
# Nova waits about 3 minutes for the volume, then puts the instance in ERROR.
resource "openstack_blockstorage_volume_v3" "root" {
  name     = "${var.name}-root"
  size     = var.root_size_gb
  image_id = var.image_id

  timeouts {
    create = "60m"
    delete = "30m"
  }

  # A newer image or a bigger size never rebuilds the VM by itself.
  lifecycle {
    ignore_changes = [image_id, size]
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

  block_device {
    uuid                  = openstack_blockstorage_volume_v3.root.id
    source_type           = "volume"
    destination_type      = "volume"
    boot_index            = 0
    delete_on_termination = false
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

  # Rebuilding a VM is expensive on this hardware, so a cloud-init change
  # never replaces one by itself. Rebuild on purpose by replacing the root
  # volume; the instance follows:
  #   terraform apply -replace=module.<name>.openstack_blockstorage_volume_v3.root
  lifecycle {
    ignore_changes       = [user_data, block_device]
    replace_triggered_by = [openstack_blockstorage_volume_v3.root.id]
  }
}
