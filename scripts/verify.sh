#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MODE="auto"
for arg in "$@"; do
    case "$arg" in
        --container) MODE="container" ;;
        --native)    MODE="native" ;;
        -h|--help)
            echo "Usage: ./scripts/verify.sh [--container | --native]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

detect_jdk() {
    local ver="$1"
    local var_name="JAVA_${ver}_HOME"
    if [[ -n "${!var_name:-}" && -x "${!var_name}/bin/java" ]]; then
        echo "${!var_name}"
        return 0
    fi
    if [[ -x "/opt/java/temurin-${ver}/bin/java" ]]; then
        echo "/opt/java/temurin-${ver}"
        return 0
    fi
    if [[ -x "/usr/libexec/java_home" ]]; then
        local mac_home
        if mac_home="$(/usr/libexec/java_home -v "$ver" 2>/dev/null)" && [[ -x "${mac_home}/bin/java" ]]; then
            echo "$mac_home"
            return 0
        fi
    fi
    return 1
}

run_native() {
    export JAVA_17_HOME="$(detect_jdk 17)"
    export JAVA_21_HOME="$(detect_jdk 21)"
    export JAVA_25_HOME="$(detect_jdk 25)"
    export JAVA_HOME="$JAVA_21_HOME"

    echo "=== [1/2] Build matrix + Unit/Property tests + ArchitectureContractTest ==="
    python3 scripts/build-matrix.py

    echo ""
    echo "=== [2/2] Packaged-JAR Real-Runtime Black-Box E2E (1.21.1 NeoForge) ==="
    python3 scripts/verify-e2e.py

    echo ""
    echo "ALL THREE VERIFICATION GATES PASSED."
}

run_container() {
    local runtime=""
    if command -v docker >/dev/null 2>&1; then
        runtime="docker"
    elif command -v podman >/dev/null 2>&1; then
        runtime="podman"
    else
        echo "Error: neither docker nor podman is available, and host is missing JDK 17/21/25." >&2
        exit 1
    fi

    local image="waystones-item-cost-extended-verify:latest"
    echo "=== Building verification container (${image}) via ${runtime} ==="
    "$runtime" build -f Dockerfile.verify -t "$image" "$REPO_ROOT"

    echo "=== Running full verification suite inside container ==="
    "$runtime" run --rm --init \
        -v "${REPO_ROOT}:/workspace" \
        -v "rinchan-gradle-cache:/root/.gradle" \
        -w /workspace \
        "$image" \
        bash scripts/verify.sh --native
}

if [[ "$MODE" == "container" ]]; then
    run_container
elif [[ "$MODE" == "native" ]]; then
    run_native
else
    if detect_jdk 17 >/dev/null && detect_jdk 21 >/dev/null && detect_jdk 25 >/dev/null; then
        run_native
    else
        echo "Host missing one or more required JDKs (17/21/25); falling back to container verification..."
        run_container
    fi
fi
