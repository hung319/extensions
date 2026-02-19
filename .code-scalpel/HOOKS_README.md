# Code Scalpel Claude Code Hooks

This directory contains configuration for Claude Code governance hooks that enforce
Code Scalpel policies on all file operations.

## Quick Start

```bash
# Install Claude Code hooks
code-scalpel install-hooks

# Install git hooks
code-scalpel install-git-hooks
```

## How It Works

### Layer 1: Claude Code Hooks

Claude Code hooks intercept tool usage before and after execution:

- **PreToolUse**: Validates file operations against governance policies
- **PostToolUse**: Logs all operations to the audit trail

Configuration in `.claude/settings.json`:
```json
{
  "hooks": {
    "PreToolUse": [{
      "name": "code-scalpel-governance",
      "match": {"tools": ["Edit", "Write", "Bash", "MultiEdit"]},
      "command": "code-scalpel hook pre-tool-use",
      "onFailure": "block"
    }]
  }
}
```

### Layer 2: Git Hooks

Git hooks provide commit-time enforcement:

- **pre-commit**: Verifies audit coverage for all staged changes
- **commit-msg**: Logs commits to audit trail

### Enforcement Modes

| Mode | Behavior |
|------|----------|
| `audit-only` | Log all operations without blocking |
| `warn` | Warn on violations, allow operations |
| `block` | Block operations that violate policy |

## Commands

```bash
# Claude Code hooks
code-scalpel install-hooks      # Install hooks to .claude/settings.json
code-scalpel install-hooks --user  # Install to user-level settings
code-scalpel uninstall-hooks    # Remove hooks

# Git hooks
code-scalpel install-git-hooks  # Install git pre-commit/commit-msg hooks
code-scalpel verify-audit-coverage <file>  # Check audit coverage

# Manual hook invocation (used by hooks internally)
code-scalpel hook pre-tool-use  # Run governance validation
code-scalpel hook post-tool-use # Run audit logging
```

## Documentation

See `docs/architecture/IDE_ENFORCEMENT_GOVERNANCE.md` for full documentation.
