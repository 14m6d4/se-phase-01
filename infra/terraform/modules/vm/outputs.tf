output "id" {
  description = "Instance ID."
  value       = openstack_compute_instance_v2.this.id
}

output "port_id" {
  description = "Port ID, for a floating IP."
  value       = openstack_networking_port_v2.this.id
}

output "fixed_ip" {
  description = "Fixed address on the tenant subnet."
  value       = var.fixed_ip
}
