# AGENTS.md

## What this repo is
- Chisel 7.13.0 / Scala 2.13.17 hardware generator built with mill. Despite the `QuadRing` name, there is **no QuadRing/NoC implementation yet**: write designs under `src/main/scala/`.
- The standalone `chipsalliance/diplomacy` framework, plus its pinned `chipsalliance/cde` dependency, are vendored as git submodules under `third_party/` and compiled by the `diplomacyLib` mill module. There is **no published Maven artifact** for either. After cloning run `git submodule update --init --recursive`.
- The main `QuadRing` module depends on `diplomacyLib`, so designs import:
  `org.chipsalliance.cde.config.Parameters`, `org.chipsalliance.diplomacy.lazymodule._`, `org.chipsalliance.diplomacy.nodes._` (note: **not** `freechips.rocketchip.diplomacy`, which is the older rocket-chip namespace).
- `README.md` documents the current mill-only workflow.

## Build & test
- mill is the only supported build tool and `./mill` bootstraps its own version (no install needed). sbt was intentionally removed — do not re-add `build.sbt` or `project/`.
- The mill module is named `QuadRing` (see `build.mill`).
- All tests: `./mill QuadRing.test` (CI uses `./mill _.test`).
- Single test: `./mill QuadRing.test.testOnly adder.AdderSpec`.
- Compile only: `./mill QuadRing.compile` (this also compiles `diplomacyLib` from the submodules on first run).
- Run a generator `main`: `./mill QuadRing.runMain adder.AdderMain`.

## Toolchain quirks
- `.mill-jvm-opts` sets `-Dchisel.project.root=${PWD}`, required for Chisel to resolve output directories under mill (see comment in `build.mill`). Do not remove it; run mill from the repo root.
- Simulation tests use `chisel3.simulator.scalatest.ChiselSim` (svsim) and require **Verilator** installed; CI installs it. Chisel lowers to SystemVerilog through CIRCT `firtool`, which must be on PATH.
- Simulations write to `build/chiselsim/<SpecName>/<test name>/`, ignored only via the generic `build/` gitignore rule. `test_run_dir/*` in `.gitignore` is a stale template entry.
- Generator `main`s (`ChiselStage.emitSystemVerilogFile`) write `<Module>.sv` and `filelist.f` to the repo root, which are **not** gitignored — delete them after running or they show up in `git status`.
- `scalacOptions` include Chisel-specific flags (`-language:reflectiveCalls`, `-Ymacro-annotations`, `-Xcheckinit`); do not trim them.

## CI
- `.github/workflows/test.yml` installs Verilator and runs `./mill _.test` on push to `main` and on all PRs.
