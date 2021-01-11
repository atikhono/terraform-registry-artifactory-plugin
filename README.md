# terraform-registry-artifactory-plugin

Terraform registry example implementation for Artifactory PRO.

Supports private module and provider registry as well as the caching proxy for the public Terraform registry (both module and provider registry), with the followin limitations:

* The plugin does not generate /versions metadata for local module registry

It is expected that a build server would generate this metadata and publish it along with the module, i.e module versions metadata is updated when a new version is uploaded

* Public Terraform provider registry caching (/v1/providers/) is supported only for official providers (vendored by HashiCorp)

Presumably this is extensible by adding more remote repositories for provider vendors.

## Artifactory configuration

* terraform-registry-proxy

Generic remote repository for https://registry.terraform.io

* terraform-registry-local

Generic local repository (private Terraform registry)

* terraform-registry

Virtual repository for terraform-registry-proxy and terraform-registry-local

HTTP server in front of Artifactory must serve the private Terraform registry host (i.e terraform-registry.example.com). This host is to be used as module/provider source in Terraform code.

## Installation

Copy `terraform-registry-plugin.groovy` to Artifactory plugins directory.
