# AGENTS.md

## What this repo is
- Chisel 7 / Scala 2.13.18 hardware generator built with mill. Despite the `QuadRing` name, there is **no QuadRing implementation yet**: `src/main/scala/gcd/` is still the unmodified Chisel template sample (`GCD`, `DecoupledGcd`).
- `README.md` documents the current mill-only workflow.

## Build & test
- mill is the only supported build tool and `./mill` bootstraps its own version (no install needed). sbt was intentionally removed — do not re-add `build.sbt` or `project/`.
- The mill module is named `QuadRing` (see `build.mill`).
- All tests: `./mill QuadRing.test` (CI uses `./mill _.test`).
- Single test: `./mill QuadRing.test.testOnly gcd.GCDSpec`.
- Compile only: `./mill QuadRing.compile`.
- Run a generator `main`: `./mill QuadRing.runMain gcd.GCD`.

## Toolchain quirks
- `.mill-jvm-opts` sets `-Dchisel.project.root=${PWD}`, required for Chisel to resolve output directories under mill (see comment in `build.mill`). Do not remove it; run mill from the repo root.
- Simulation tests use `chisel3.simulator.scalatest.ChiselSim` (svsim) and require **Verilator** installed; CI installs it. Chisel lowers to SystemVerilog through CIRCT `firtool`, which must be on PATH.
- Simulations write to `build/chiselsim/<SpecName>/<test name>/`, ignored only via the generic `build/` gitignore rule. `test_run_dir/*` in `.gitignore` is a stale template entry.
- Generator `main`s (`ChiselStage.emitSystemVerilogFile`) write `<Module>.sv` and `filelist.f` to the repo root, which are **not** gitignored — delete them after running or they show up in `git status`.
- `scalacOptions` include Chisel-specific flags (`-language:reflectiveCalls`, `-Ymacro-annotations`, `-Xcheckinit`); do not trim them.

## CI
- `.github/workflows/test.yml` installs Verilator and runs `./mill _.test` on push to `main` and on all PRs.
