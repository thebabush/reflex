#!/bin/bash

rm -rf ../flex/autoit/stage0/ ../flex/autoit/out/

echo "=== STAGE 0 ==="

./py/reflex.py \
  --accept 0x0001FEE0 2 \
  --base   0x00020DF0 2 \
  --chk    0x00021150 2 \
  --def    0x00021C80 2 \
  --ec     0x00021FE0 4 \
  --meta   0x000201F0 4 \
  --nxt    0x000202C0 2 \
  --max-state 0x185 \
  ../flex/autoit/Au3Check.exe \
  ../flex/autoit/stage0/

./py/simplify.py \
  ../flex/autoit/stage0/G.gpickle \
  ../flex/autoit/out/

echo "=== STAGE 1 ==="

echo "SCALA"
parallel -j 12 echo print {} ';' ./scala/jreflex.sh print "{}.regexp" "{}" ::: ../flex/autoit/out/*.dfa
#for f in ../flex/autoit/out/*.dfa;
#do
  #echo "SCALA $f"
  #./scala/jreflex.sh print "$f.regexp" "$f"
  #cat "$f.regexp"
#done

echo "PROTO"
./scala/jreflex.sh proto ../flex/autoit/out/out.proto ../flex/autoit/out/*.dfa

