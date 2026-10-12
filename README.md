# QuadRing

Ring interconnect implemented in Chisel and Scala. Built with Mill.

Currently supports a single unidirectional ring with configurable node count,
buffer depth, and payload type. Nodes can inject messages, route them by destination,
and receive them through valid/ready interfaces. Credit and bubble flow control
handle backpressure and reserve space for forwarding.

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

Validated with routing and recovery tests plus 500,000 randomized simulation cycles.
Multiple rings, CHI, and Diplomacy integration are pending.

Requires JDK 21, Verilator, a C++ compiler, and Make.

```bash
git submodule update --init --recursive
./test.sh
```

Compiles sources, runs tests, and emits SystemVerilog to `generated/`.
Random tests run 5,000 cycles per seed; use `./test.sh --long` for 100,000. CI runs the long mode.
The RTL generator uses four nodes, three buffer entries per node, and a 32-bit payload.
