#!/usr/bin/env bash
# Compile, test, and generate RTL from the repository root.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

./mill QuadRing.compile
./mill QuadRing.test
./mill QuadRing.runMain noc.RingMain --target-dir generated
