#!/usr/bin/env bash
# Compile and test from the repository root.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

./mill QuadRing.compile
./mill QuadRing.test
