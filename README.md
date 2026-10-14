# QuadRing

Ring interconnect implemented in Chisel and Scala. Built with Mill.

Supports four independent unidirectional rings with configurable node count,
buffer depth, and payload types. Nodes can inject messages, route them by destination,
and receive them through valid/ready interfaces. Credit and bubble flow control
handle backpressure and reserve space for forwarding.

CHI subset payloads (REQ, RSP, SNP, DAT) connect through Diplomacy. Endpoint node IDs
map to ring positions; DAT carries a complete 512-bit line. RN0 and RN1 accept local
line-read requests. HN forwards reads and whole-line writes to SN, preserves upstream
request IDs, and waits for SN write completion. SN supports initialized whole-line
reads and writes, with separate write authorization and completion. Each endpoint
handles one active transaction; local RN writes, retry, caches, and snoops are pending.

Each channel uses this topology in the four-node configuration:

```text
    +----------------+                +----------------+
    |   Endpoint 0   |                |   Endpoint 1   |
    +----------------+                +----------------+
      inject | ^ eject                  inject | ^ eject
             v |                               v |
    +----------------+                +----------------+
    |   RingStop 0   |--------------->|   RingStop 1   |
    +--------^-------+                +--------|-------+
             |                                 |
             |             RING                |
             |                                 v
    +--------|-------+                +----------------+
    |   RingStop 3   |<---------------|   RingStop 2   |
    +----------------+                +----------------+
             ^ |                               ^ |
      inject | v eject                  inject | v eject
    +----------------+                +----------------+
    |   Endpoint 3   |                |   Endpoint 2   |
    +----------------+                +----------------+
```

The ring core is validated with routing and recovery tests plus 500,000 randomized simulation cycles.
CHI transport tests cover node ID mapping, all source/destination pairs, and backpressure.

Requires JDK 21, Verilator, a C++ compiler, and Make.

```bash
git submodule update --init --recursive
./test.sh
```

Compiles sources, runs tests, and emits SystemVerilog to `generated/`.
Random tests run 5,000 cycles per seed; use `./test.sh --long` for 100,000. CI runs the long mode.
Emits a 32-bit single ring to `generated/ring/` and the four-channel CHI transport
to `generated/chi/`. The assembled endpoint system is emitted to `generated/system/`.
Each output has its own `filelist.f`, with four nodes and three buffer entries per node.
