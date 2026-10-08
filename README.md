# QuadRing

Chisel 7 hardware generator (Scala 2.13.17), built with [mill](https://mill-build.org).

The `QuadRing` NoC design is not implemented yet: write designs under `src/main/scala/`.
The [Diplomacy](https://github.com/chipsalliance/diplomacy) framework (and its `cde`
dependency) are vendored as git submodules under `third_party/` and exposed through the
`diplomacyLib` mill module, so the main `QuadRing` module can `import
org.chipsalliance.diplomacy...` directly.

After cloning, initialize the submodules:

```
git submodule update --init --recursive
```

## Requirements

- JDK 11 or newer (JDK 21 tested)
- [Verilator](https://verilator.org/guide/latest/install.html), for the `ChiselSim` simulation tests
- CIRCT `firtool` on `PATH` (Chisel lowers to SystemVerilog through it)

The checked-in `./mill` script bootstraps mill, so no separate installation is needed.

## Build, test and generate verilog
`./test.sh`

This writes all `.sv` and filelist.f into the repository root, neither is gitignored, so remove them when you are done.