# build and test
./mill QuadRing.compile
./mill QuadRing.test


# generate verilog
./mill QuadRing.runMain adder.Adder
./mill QuadRing.runMain gcd.GCD
./mill QuadRing.runMain sel.Sel

