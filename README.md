# QuadRing

Chisel 7 hardware generator (Scala 2.13.18), built with [mill](https://mill-build.org).

The `QuadRing` design is not implemented yet: `src/main/scala/gcd/` currently holds the
unmodified Chisel template example (`GCD`, `DecoupledGcd`).

## Requirements

- JDK 11 or newer (JDK 21 tested)
- [Verilator](https://verilator.org/guide/latest/install.html), for the `ChiselSim` simulation tests
- CIRCT `firtool` on `PATH` (Chisel lowers to SystemVerilog through it)

The checked-in `./mill` script bootstraps mill, so no separate installation is needed.

## Build and test

```sh
./mill QuadRing.compile                     # compile
./mill QuadRing.test                        # run all tests
./mill QuadRing.test.testOnly gcd.GCDSpec   # run a single test class
```

## Generate Verilog

```sh
./mill QuadRing.runMain gcd.GCD
```

This writes `GCD.sv` and `filelist.f` into the repository root; neither is gitignored,
so remove them when you are done.
