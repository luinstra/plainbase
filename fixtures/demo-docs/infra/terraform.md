---
title: Terraform
owner: ops
tags: [infra, terraform]
---

# Terraform

All cloud resources are Terraform-managed.

```hcl
module "docs" {
  source = "./modules/docs"
}
```
