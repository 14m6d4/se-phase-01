variable "cloud" {
  description = "Entry in ~/.config/openstack/clouds.yaml: an application credential scoped to the cinema project."
  type        = string
  default     = "cinema"
}

variable "network_name" {
  description = "Tenant network the VMs sit on (created in #32)."
  type        = string
  default     = "cinema-net"
}

variable "subnet_name" {
  description = "Subnet of network_name that the fixed IPs come from."
  type        = string
  default     = "cinema-subnet"
}

variable "external_network_name" {
  description = "Provider network on VLAN 30 that floating IPs come from."
  type        = string
  default     = "external-net"
}

variable "floating_ip" {
  description = "Floating IP reserved for cinema by an admin (router.md, VLAN 30 address plan). Goes to proxy."
  type        = string
  default     = "10.10.30.17"
}

variable "image_name" {
  description = "Shared base image, uploaded by verify-openstack.sh."
  type        = string
  default     = "Debian 13"
}
