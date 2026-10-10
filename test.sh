#!/usr/bin/env bash
# Build, test, and generate Verilog into generated/.
set -euo pipefail

./mill QuadRing.compile
./mill QuadRing.test

# Generate Verilog and the Diplomacy graph (generated/AdderTop.graphml).
./mill QuadRing.runMain adder.AdderMain

# Render the graph to SVG. graphml2gv warns about yEd-only keys; ignore them.
graphml2gv generated/AdderTop.graphml 2>/dev/null | dot -Tsvg -o generated/AdderTop.svg
