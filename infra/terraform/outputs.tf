output "fixed_ips" {
  description = "Each VM's address on cinema-subnet."
  value       = local.hosts
}

output "proxy_floating_ip" {
  description = "proxy's address on VLAN 30, reachable from the tailnet."
  value       = data.openstack_networking_floatingip_v2.proxy.address
}

output "ssh_config" {
  description = "Append to ~/.ssh/config: terraform output -raw ssh_config >> ~/.ssh/config"
  value       = <<-EOT
    Host cinema-proxy
      HostName ${data.openstack_networking_floatingip_v2.proxy.address}
      User debian

    Host cinema-app
      HostName ${var.vms.app.fixed_ip}
      User debian
      ProxyJump cinema-proxy

    Host cinema-db
      HostName ${var.vms.db.fixed_ip}
      User debian
      ProxyJump cinema-proxy
  EOT
}
