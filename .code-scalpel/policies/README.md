# Policy Templates

This directory contains production-ready policy templates for enforcing governance across architecture, DevOps, and DevSecOps.

## Directory Structure

```
policies/
├── architecture/          # Architecture management
│   ├── README.md
│   ├── layered_architecture.rego
│   └── module_boundaries.rego
│
├── devops/               # DevOps best practices
│   ├── README.md
│   ├── docker_security.rego
│   └── kubernetes_manifests.rego
│
├── devsecops/            # DevSecOps automation
│   ├── README.md
│   ├── secret_detection.rego
│   └── sbom_validation.rego
│
└── project/              # Project structure
    ├── README.md
    └── structure.rego
```

## Quick Start

### 1. Enable Policies

Edit `.code-scalpel/policy.yaml`:

```yaml
policies:
  architecture:
    - name: layered-architecture
      file: policies/architecture/layered_architecture.rego
      severity: HIGH
      action: DENY
  
  devops:
    - name: docker-security
      file: policies/devops/docker_security.rego
      severity: HIGH
      action: DENY
  
  devsecops:
    - name: secret-detection
      file: policies/devsecops/secret_detection.rego
      severity: CRITICAL
      action: DENY
```

### 2. Test Policies

```bash
code-scalpel policy validate
code-scalpel policy test --category architecture
```

### 3. Customize

Copy template .rego files and modify rules to match your project requirements.

---

*Part of Code Scalpel v3.1+ Policy Engine*
