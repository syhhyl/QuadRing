# Repository Guidelines

## Project Structure & Module Organization

QuadRing uses Chisel 7.13.0 / Scala 2.13.17. Add designs under `src/main/scala/` and matching tests under `src/test/scala/`. No designs or tests currently remain after removing the examples.

Local design documents (`PLAN.md` and `docs/protocol.md`) are ignored by Git. Reconcile earlier drafts with the current plan before implementation. `build.mill` defines `QuadRing` and `diplomacyLib`. Treat pinned `third_party/diplomacy` and `third_party/cde` submodules as read-only; both compile from source without published Maven artifacts.

## Build, Test, and Development Commands

Run from the repository root; `./mill` bootstraps Mill:

- `git submodule update --init --recursive`: initialize dependencies.
- `./mill QuadRing.compile`: compile designs and dependencies.
- `./mill QuadRing.test`: run all tests; CI uses `./mill _.test`.
- `./mill QuadRing.test.testOnly <package.DesignSpec>`: run one suite once tests exist.
- `./mill QuadRing.runMain <package.DesignMain>`: run a generator once implemented.
- `./test.sh`: compile and test; the script selects the repository root automatically. Optional graph rendering requires Graphviz (`graphml2gv`, `dot`).

Mill is the only supported build tool; do not reintroduce sbt.

## Coding Style & Naming Conventions

Use two-space indentation, lowercase packages, PascalCase classes/objects, and camelCase members. Name generators `<Design>Main` and suites `<Design>Spec`. No formatter/linter is configured.

Use `org.chipsalliance.cde.config.Parameters` and `org.chipsalliance.diplomacy.lazymodule._` / `org.chipsalliance.diplomacy.nodes._`. Preserve compiler flags, including `-language:reflectiveCalls`, `-Ymacro-annotations`, and `-Xcheckinit`.

## Testing Guidelines

Use ScalaTest `AnyFreeSpec`, `Matchers`, and `chisel3.simulator.scalatest.ChiselSim`. Install Verilator; Chisel resolves pinned firtool automatically. Cover boundaries, routing, backpressure, and loss/duplication with deterministic seeds and bounded waits. No coverage threshold is configured.

## Commit & Pull Request Guidelines

Recent commits use imperative subjects, often `docs:`, `chore:`, or `feat(adder):`. Keep commits focused. PRs should explain behavior, link relevant issues/roadmap tasks, and report validation commands/results. Ensure CI passes; exclude generated artifacts.

## Toolchain & Output Rules

Use JDK 21, matching CI. The current scope is functional correctness and transaction completion; defer concurrency extensions, performance optimization, and performance statistics.

Preserve `.mill-jvm-opts` and `-Dchisel.project.root=${PWD}`. Generators must pass `--target-dir generated`; simulations write under `build/chiselsim/`. Both directories and `out/` are ignored. After removing/renaming sources, run `./mill clean QuadRing` to prevent stale classes breaking test discovery.
