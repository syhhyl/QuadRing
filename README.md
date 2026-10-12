# QuadRing

Hardware development with Chisel, Scala, and Diplomacy. Built with Mill.

Requires JDK 21, Verilator, a C++ compiler, and Make.

```bash
git submodule update --init --recursive
./test.sh
```

Compiles sources, runs tests, and emits SystemVerilog to `generated/`.
