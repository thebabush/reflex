#!/bin/sh

cd subs
rm *.pdf
for f in $1/*.dot
do
  echo "==== $f ===="
  dot -Tpdf -o "$f.pdf" "$f"
  dot -Tpng -o "$f.png" "$f"
done

