#!/usr/bin/env bash
# Build, test, and generate Verilog into generated/.
set -euo pipefail

./mill QuadRing.compile
./mill QuadRing.test

# Generate Verilog.
./mill QuadRing.runMain adder.AdderMain
