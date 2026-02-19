# DevOps Policies

This directory contains policy templates for DevOps practices, infrastructure validation, and deployment safety.

## Policy Categories

### 1. Infrastructure as Code (IaC)
- **docker_security.rego** - Dockerfile best practices
- **kubernetes_manifests.rego** - Validate K8s manifest safety

### 2. Deployment Safety
- **deployment_checklist.rego** - Enforce pre-deployment checks
- **rollback_capability.rego** - Ensure rollback mechanisms

### 3. Resource Management
- **resource_limits.rego** - Enforce CPU/memory limits
- **cost_controls.rego** - Prevent expensive configurations

## Usage

Enable these policies in `.code-scalpel/policy.yaml`:

```yaml
policies:
  devops:
    - name: docker-security
      file: policies/devops/docker_security.rego
      severity: HIGH
      action: WARN
    
    - name: kubernetes-security
      file: policies/devops/kubernetes_manifests.rego
      severity: CRITICAL
      action: DENY
```

## Examples

See `examples/policy_examples/devops/` for usage examples.

---

*Part of Code Scalpel v3.1+ Policy Engine*
