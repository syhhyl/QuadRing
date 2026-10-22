# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and validation

Use Mill from the repository root, with JDK 21, Verilator, a C++ compiler, and Make. `./mill` bootstraps the pinned Mill version.

```bash
# Initialize dependencies
git submodule update --init --recursive

# Compile / run all tests
./mill QuadRing.compile
./mill QuadRing.test

# Full regression: compile, test, generate four RTL targets, Verilator lint
./test.sh
./test.sh --long

# Long randomized testing without generation/lint
QUADRING_LONG_TESTS=1 ./mill QuadRing.test.testOnly chi.ChiCoherentRandomSpec

# Generate individual RTL targets
./mill QuadRing.runMain noc.RingMain --target-dir generated/ring
./mill QuadRing.runMain chi.ChiNoCMain --target-dir generated/chi
./mill QuadRing.runMain chi.ChiSystemMain --target-dir generated/system
./mill QuadRing.runMain chi.ChiSystemMain --coherent --target-dir generated/system-coherent

# Lint one generated target (filelist paths are relative to its directory)
(cd generated/system-coherent && verilator --lint-only --top-module ChiSystem -f filelist.f)

# After removing or renaming Scala sources, clear stale classes
./mill clean QuadRing
```

`test.sh` selects the repository root and uses `./mill --no-daemon` for each phase. CI runs `./test.sh --long`. Randomized tests normally use 5,000 cycles per seed; long mode uses 100,000. There is no Scala formatter/linter configured; the lint phase checks generated RTL.

Preserve `.mill-jvm-opts`, especially `-Dchisel.project.root=${PWD}`, and the compiler options in `build.mill`. Generated RTL belongs under `generated/`; simulations write under `build/chiselsim/`. These and `out/` are ignored. Mill is the only supported build tool, despite its use of `SbtModule` internally.

## Architecture

Start with `docs/design.md` for the implemented architecture, transaction sequences, completion boundaries, and progress assumptions. `README.md` provides the overview. `PLAN.md` and `docs/protocol.md` are ignored local documents and may retain historical draft tasks; reconcile those with current source/design documentation before implementing changes.

### Transport versus protocol

Diplomacy SourceNode/SinkNode names describe parameter flow, not unidirectional hardware traffic: each endpoint edge carries TX and RX for all four channels. Connections use `network.node := endpoint.node`; connection order determines physical position, independently of CHI IDs. Sparse/permuted IDs are supported, duplicates fail elaboration, and transmitting to an unknown ID asserts. Check pinned local Diplomacy APIs/examples before changing negotiation or port wiring.

Each stop has a ring-input FIFO and downstream credit accounting. Forwarding needs at least one credit; injection needs at least two to preserve a bubble. Forwarded traffic has strict priority over injection. A blocked local-destination FIFO head blocks traffic behind it. RingStop uses ordinary `Decoupled`, not `Irrevocable`: a stalled local injection can be superseded by an arriving forward. Eventual progress assumes finite offered traffic and eventual consumers, not fairness under unlimited injection.

### Endpoint behavior and completion

`ChiSystem` defaults to **non-coherent** operation; `ChiSystemParams(coherent = true)` enables RN caches and the HN directory. Local commands contain `{write, addr, data, txnID}`; results contain `{txnID, data}`. All accesses are aligned, whole 64-byte lines, with all 512 data bits in one DAT flit.

- **Non-coherent read:** RN → HN → SN using `ReadNoSnp`; `CompData` returns through HN. No `CompAck` is required.
- **Non-coherent write:** HN authorizes RN data with `CompDBIDResp`, then obtains SN authorization, forwards data, and waits for SN `Comp` after the memory update. RN local completion occurs after its write DAT is injected, so it does **not** mean SN memory has already changed. DBID authorization is not write completion.
- **Coherent access:** RN uses `ReadShared` or `ReadUnique` on misses/upgrades. HN consults its directory, snoops peers when necessary, and obtains data from memory or a dirty holder. RN installs the line and injects `CompAck`; HN retains the transaction until that acknowledgement arrives. Local result backpressure does not retain the HN context after acknowledgement.
- **Cache/replacement:** The direct-mapped cache is integrated into `RnNode`; `RnCache.scala` defines I/SC/UC/UD state encodings, not a separate cache module. Reads hit locally in any valid state; UC/UD writes are local and become UD. Clean victims use `Evict`; dirty victims remain buffered through `WriteBackFull`. A snoop can take a dirty victim before writeback authorization, after which the pending writeback sends invalid-state data and HN must not update memory with it. Preserve this cancellation path.
- **Retry:** Link credits are separate from protocol retry. A busy receiver records a waiter and sends `RetryAck`; later it reserves admission and sends `PCrdGrant`. RN waits for both, in either arrival order, and reissues the identical request with `allowRetry=false`. A waiter is not an automatically executed request. HN preserves upstream `(srcID, txnID)` identity, so two RNs may reuse a transaction ID.

Snoops must continue while an RN command or result is waiting. Endpoint output selections lock while stalled; do not confuse this with the generic RingStop arbiter's weaker contract.

## Tests and scope boundaries

Tests use ScalaTest `AnyFreeSpec` / `Matchers` with ChiselSim. `src/test/scala/chi/ChiNodeTestSupport.scala` provides direct diplomatic peers for independently stalling channels and real-NoC fixtures for transport-integrated tests. Isolated endpoint tests do not substitute for real-ring transfer tests; the serial product HN does not naturally exercise every SN contention/retry path.

Random suites use deterministic seeds, bounded waits/backpressure, reference models, and final drain checks. Coherent reference ordering is derived from local command/result history, not DUT state. `CacheOwnershipMonitor.scala` and `RetryObservation.scala` use test-only BoringUtils probes into internal registers for ownership, retry accounting, and credit/reservation balance. Renaming probed registers can break monitor elaboration; these probes are not product RTL.

The generic ring is configurable, but the assembled endpoint system is fixed to two RNs, one HN, and one SN, with one local command per RN, one active HN context, and one SN operation. The bounded directory has two holder slots per memory line and no replacement; it must cover SN memory. This is an internal CHI subset, not complete CHI wire/link compliance: no partial writes, multibeat lines, forwarded snoops, full CHI link layer, or error recovery. Reset discards dirty cache data and restores initial SN memory. Bounded test waits are not latency guarantees.

Follow `AGENTS.md` for repository conventions and review requirements. In particular, keep pinned submodules read-only, preserve functional-correctness/transaction-completion scope rather than expanding concurrency or performance work, and validate Diplomacy changes with compilation, elaboration, and actual parameter/transfer tests. Do not commit or push implementation changes without explicit user review approval.
