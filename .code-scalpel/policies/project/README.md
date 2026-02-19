# Project Structure Policies

This directory contains policies that enforce consistent code organization and documentation standards for the Code Scalpel project.

## Overview

The project structure policy ensures:
- **Consistent file placement** - Similar code in similar directories
- **Complete documentation** - README.md in every meaningful directory
- **Clean architecture** - Core analysis isolated from integrations
- **Naming conventions** - PEP 8 compliance and project standards
- **Module boundaries** - Preventing circular dependencies

## Policy: structure.rego

Enforces Code Scalpel's project structure conventions.

### Configuration

Configuration file: [.code-scalpel/project-structure.yaml](../../project-structure.yaml)

## Usage

Enable in `.code-scalpel/policy.yaml`:

```yaml
policies:
  project:
    - name: structure
      file: policies/project/structure.rego
      severity: HIGH
      action: DENY
```

---

*Part of Code Scalpel v3.1+ Policy Engine*
