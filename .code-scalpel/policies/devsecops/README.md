# DevSecOps Policies

This directory contains policy templates for security-first DevOps practices, automated security checks, and compliance enforcement.

## Policy Categories

### 1. Secret Management
- **secret_detection.rego** - Detect hardcoded secrets
- **secret_rotation.rego** - Enforce rotation policies

### 2. Dependency Security
- **sbom_validation.rego** - Software Bill of Materials checks
- **vulnerability_scanning.rego** - Known CVE detection
- **license_compliance.rego** - License policy enforcement

### 3. Container Security
- **image_scanning.rego** - Container image security
- **registry_compliance.rego** - Approved registries only

## Usage

Enable these policies in `.code-scalpel/policy.yaml`:

```yaml
policies:
  devsecops:
    - name: secret-detection
      file: policies/devsecops/secret_detection.rego
      severity: CRITICAL
      action: DENY
    
    - name: sbom-validation
      file: policies/devsecops/sbom_validation.rego
      severity: HIGH
      action: WARN
```

## Examples

See `examples/policy_examples/devsecops/` for usage examples.

---

*Part of Code Scalpel v3.1+ Policy Engine*
