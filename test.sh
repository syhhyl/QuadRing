# build and test
./mill QuadRing.compile
./mill QuadRing.test


# generate verilog
./mill QuadRing.runMain adder.Adder
./mill QuadRing.runMain sel.Sel
./mill QuadRing.runMain noc.DiplomacyAdder
