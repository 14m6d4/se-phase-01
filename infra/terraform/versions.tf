terraform {
  required_version = ">= 1.9"

  required_providers {
    openstack = {
      source  = "terraform-provider-openstack/openstack"
      version = "~> 3.4"
    }
  }
}

# Credentials come from the user's clouds.yaml (see clouds.yaml.example).
provider "openstack" {
  cloud = var.cloud
}
