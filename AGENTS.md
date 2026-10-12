# Repository Guidelines

## Project Structure & Module Organization

QuadRing uses Chisel 7.13.0 / Scala 2.13.17. Add designs under `src/main/scala/` and matching tests under `src/test/scala/`. The `noc` package holds the generic ring network interfaces and their tests.

Local design documents (`PLAN.md` and `docs/protocol.md`) are ignored by Git. Reconcile earlier drafts with the current plan before implementation. `build.mill` defines `QuadRing` and `diplomacyLib`. Treat pinned `third_party/diplomacy` and `third_party/cde` submodules as read-only; both compile from source without published Maven artifacts.

## Build, Test, and Development Commands

Run from the repository root; `./mill` bootstraps Mill:

- `git submodule update --init --recursive`: initialize dependencies.
- `./mill QuadRing.compile`: compile designs and dependencies.
- `./mill QuadRing.test`: run all tests; CI uses `./mill _.test`.
- `./mill QuadRing.test.testOnly <package.DesignSpec>`: run one suite once tests exist.
- `./mill QuadRing.runMain <package.DesignMain>`: run a generator once implemented.
- `./test.sh`: compile, test, and generate RTL under `generated/`; the script selects the repository root automatically.
- `./test.sh --long`: run the same checks with 100,000 random cycles per seed instead of the default 5,000. Direct Mill commands use `QUADRING_LONG_TESTS=1` for long mode; CI sets this variable.

Mill is the only supported build tool; do not reintroduce sbt.

## Coding Style & Naming Conventions

Use two-space indentation, lowercase packages, PascalCase classes/objects, and camelCase members. Name generators `<Design>Main` and suites `<Design>Spec`. No formatter/linter is configured.

Use `org.chipsalliance.cde.config.Parameters` and `org.chipsalliance.diplomacy.lazymodule._` / `org.chipsalliance.diplomacy.nodes._`. Preserve compiler flags, including `-language:reflectiveCalls`, `-Ymacro-annotations`, and `-Xcheckinit`.

## Testing Guidelines

Use ScalaTest `AnyFreeSpec`, `Matchers`, and `chisel3.simulator.scalatest.ChiselSim`. Install Verilator; Chisel resolves pinned firtool automatically. Cover boundaries, routing, backpressure, and loss/duplication with deterministic seeds and bounded waits. No coverage threshold is configured.

For Diplomacy changes, verify APIs and connection semantics against the pinned local source and examples before implementation. Validate each increment with compilation, RTL elaboration, and focused tests of parameter negotiation, port direction, and actual transfers. Exercise changed node parameters and invalid configurations where relevant; compilation alone is not sufficient. Report what was verified and any remaining uncertainty.

## Commit & Pull Request Guidelines

Recent commits use imperative subjects, often `docs:`, `chore:`, or `feat(adder):`. Keep commits focused. PRs should explain behavior, link relevant issues/roadmap tasks, and report validation commands/results. Ensure CI passes; exclude generated artifacts.

Develop one small, independently reviewable increment at a time. Complete the implementation and relevant tests, then explain the entry files, behavior, and validation for user review. Wait for the user's explicit approval before committing or pushing implementation changes.

## Toolchain & Output Rules

Use JDK 21, matching CI. The current scope is functional correctness and transaction completion; defer concurrency extensions, performance optimization, and performance statistics.

Preserve `.mill-jvm-opts` and `-Dchisel.project.root=${PWD}`. Generators must pass `--target-dir generated`; simulations write under `build/chiselsim/`. Both directories and `out/` are ignored. After removing/renaming sources, run `./mill clean QuadRing` to prevent stale classes breaking test discovery.
