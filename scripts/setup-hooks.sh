#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
git -C "$REPO_ROOT" config core.hooksPath .githooks
chmod +x "$REPO_ROOT"/.githooks/* "$REPO_ROOT"/scripts/*.sh "$REPO_ROOT"/scripts/*.py
echo "Git hooks enabled: core.hooksPath=$(git -C "$REPO_ROOT" config --get core.hooksPath)"
