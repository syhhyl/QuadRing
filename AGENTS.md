# AGENTS.md

## What this repo is
- Chisel 7.13.0 / Scala 2.13.17 hardware generator built with mill. Despite the `QuadRing` name, there is **no QuadRing/NoC implementation yet**: designs live under `src/main/scala/` (today only the `adder` example).
- The standalone `chipsalliance/diplomacy` framework, plus its pinned `chipsalliance/cde` dependency, are vendored as git submodules under `third_party/` and compiled by the `diplomacyLib` mill module. There is **no published Maven artifact** for either, so they must be built from source. After cloning run `git submodule update --init --recursive`; treat `third_party/` as read-only pinned externals.
- The main `QuadRing` module depends on `diplomacyLib`, so designs import:
  `org.chipsalliance.cde.config.Parameters`, `org.chipsalliance.diplomacy.lazymodule._`, `org.chipsalliance.diplomacy.nodes._` (note: **not** `freechips.rocketchip.diplomacy`, which is the older rocket-chip namespace).
- `PLAN.md` (gitignored, local-only, written in Chinese) is the intended 2x2 ring NoC / CHI roadmap; it is not tracked, so don't rely on it existing or add it to commits.
- `README.md` documents the current mill-only workflow.

## Build & test
- mill is the only supported build tool and `./mill` bootstraps its own version (no install needed). sbt was intentionally removed — do not re-add `build.sbt` or `project/`.
- The mill module is named `QuadRing` (see `build.mill`).
- All tests: `./mill QuadRing.test` (CI uses `./mill _.test`).
- Single test: `./mill QuadRing.test.testOnly adder.AdderSpec`.
- Compile only: `./mill QuadRing.compile` (this also compiles `diplomacyLib` from the submodules on first run).
- Run a generator `main`: `./mill QuadRing.runMain adder.AdderMain`.
- Full workflow: `./test.sh` runs compile → test → `runMain adder.AdderMain` → render the Diplomacy graph to SVG. Besides Verilator it needs **Graphviz** (`graphml2gv` and `dot`), which the mill commands alone do not.

## Toolchain quirks
- `.mill-jvm-opts` sets `-Dchisel.project.root=${PWD}`, required for Chisel to resolve output directories under mill (see comment in `build.mill`). Do not remove it; run mill from the repo root.
- Simulation tests use `chisel3.simulator.scalatest.ChiselSim` (svsim) and require **Verilator** installed; CI installs it. `firtool` does **not** need to be on `PATH`: chisel resolves its pinned binary from the `org.chipsalliance:llvm-firtool` Maven artifact (cached under `~/.cache/llvm-firtool`), which is why CI never installs it.
- Simulations write to `build/chiselsim/<SpecName>/<test name>/`, ignored only via the generic `build/` gitignore rule. `test_run_dir/*` in `.gitignore` is a stale template entry.
- Generator `main`s (`ChiselStage.emitSystemVerilogFile`) write into the gitignored `generated/` directory: `adder.AdderMain` emits `generated/AdderTop.sv`, its submodule `generated/Adder.sv`, `generated/filelist.f`, and `generated/AdderTop.graphml`. Keep passing `--target-dir generated`; without it the `.sv`/`filelist.f` land in the repo root, which are **not** gitignored.
- Deleting a design is not enough: orphaned `.class` files remain in the gitignored `out/` build dir, and `./mill QuadRing.test` can then fail hard during test discovery with `NoClassDefFoundError: <deleted class>` (mill loads every class on the classpath). Run `./mill clean QuadRing` after removing or renaming sources.
- `scalacOptions` include Chisel-specific flags (`-language:reflectiveCalls`, `-Ymacro-annotations`, `-Xcheckinit`); do not trim them.

## CI
- `.github/workflows/test.yml` installs Verilator and runs `./mill _.test` on pushes to `main` and tags, and on all PRs.
