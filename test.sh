#!/usr/bin/env bash
# Compile, test, and generate RTL from the repository root.
set -euo pipefail

if (( $# > 1 )) || [[ "${1:-}" != "" && "${1:-}" != "--long" ]]; then
  echo "Usage: $0 [--long]" >&2
  exit 2
fi
if [[ "${1:-}" == "--long" ]]; then
  export QUADRING_LONG_TESTS=1
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

./mill QuadRing.compile
./mill QuadRing.test
./mill QuadRing.runMain noc.RingMain --target-dir generated/ring
./mill QuadRing.runMain chi.ChiNoCMain --target-dir generated/chi
./mill QuadRing.runMain chi.ChiSystemMain --target-dir generated/system
./mill QuadRing.runMain chi.ChiSystemMain --coherent --target-dir generated/system-coherent
