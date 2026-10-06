variable "name" {
  description = "VM name, also its hostname."
  type        = string
}

variable "flavor_id" {
  description = "Flavor. The cinema flavors have disk 0, so the VM boots from a volume."
  type        = string
}

variable "image_id" {
  description = "Image the root volume is created from."
  type        = string
}

variable "root_size_gb" {
  description = "Size of the root volume."
  type        = number
}

variable "network_id" {
  description = "Network the VM's port is on."
  type        = string
}

variable "subnet_id" {
  description = "Subnet that fixed_ip belongs to."
  type        = string
}

variable "fixed_ip" {
  description = "Fixed address on subnet_id. The other VMs reach this one by it (/etc/hosts)."
  type        = string
}

variable "security_group_ids" {
  description = "Security groups on the port. Must not be empty, or Neutron adds the project's default group."
  type        = list(string)

  validation {
    condition     = length(var.security_group_ids) > 0
    error_message = "Give at least one security group."
  }
}

variable "extra_volume_ids" {
  description = "Existing volumes attached at boot, in order (vdb, vdc, …). They outlive the VM."
  type        = list(string)
  default     = []
}

variable "key_pair" {
  description = "Nova keypair for the default user."
  type        = string
}

variable "user_data" {
  description = "cloud-init user data. Read on first boot only; changing it later does not touch a running VM."
  type        = string
}
