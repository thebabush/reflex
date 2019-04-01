#!/bin/bash

rm -rf ../flex/sleigh/stage0/ ../flex/sleigh/out/

echo "=== STAGE 0 ==="

./py/reflex.py \
  --accept          0x74760 2 \
  --base            0x75100 2 \
  --chk             0x75560 2 \
  --def             0x74b80 2 \
  --ec              0x74360 4 \
  --meta            0x74fe0 4 \
  --nxt             0x75d20 2 \
  --max-state 522 \
  ../flex/sleigh/sleigh \
  ../flex/sleigh/stage0/

./py/simplify.py \
  ../flex/sleigh/stage0/G.gpickle \
  ../flex/sleigh/out/

echo "=== STAGE 1 ==="

echo "SCALA"
parallel -j 12 echo print {} ';' ./scala/jreflex.sh print "{}.regexp" "{}" ::: ../flex/autoit/out/*.dfa

echo "PROTO"
./scala/jreflex.sh proto ../flex/sleigh/out/out.proto ../flex/sleigh/out/*.dfa

