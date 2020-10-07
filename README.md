# Reflex

Flex 'em lexers.

Also known as *extract parsers' tokens from a binary*.

Also known as *a tool that helped us gain a 5th place at DEF CON*.

## Tutorial

> *Tutorials are for n00bs.* - someone, somewhere, probably.

First of all, find `yylex()` in your binary.
Then you look around a bit and find the various `yy_XXX` tables used by flex to
encode the DFA and the size of their elements.
You can easily do it by comparing your target program with a flex-based program
that you compile from source.
We had plans to automate this process with some heuristics but never got around
it.

```sh
# download the example binary
mkdir -p ./example
wget 'https://github.com/thebabush/reflex/files/4605997/liq.zip' -O ./example/liq.zip
unzip ./example/liq.zip -d ./example/
# extract & uncompress the DFAs
./py/reflex.py --accept 0x12BC7E0 2 \
               --base   0x12BCA40 2 \
               --chk    0x12BCF20 2 \
               --def    0x12BCB80 2 \
               --ec     0x12BC900 1 \
               --meta   0x12BCA00 1 \
               --nxt    0x12BCCC0 2 \
               --max-state 142 \
               ./example/liq \
               ./workdir/
# make PDF of default starting state
dot -Tpdf -o ./workdir/out.png ./workdir/1.dot
./py/simplify.py ./workdir/G.gpickle ./workdir/simple/
# make PDFs/PNGs
./subs2pdf.sh ./workdir/simple
# build the jar
pushd ./scala && sbt assembly && popd
# print the regexes
for f in ./workdir/simple/*.dfa
do
  echo "== $f =="
  ./scala/jreflex.sh print /dev/stdout "$f"
  echo
done
```

Example graph:

![whole graph](https://user-images.githubusercontent.com/1985669/81504105-b7faf780-92e7-11ea-916a-f7f6189df119.png)

Example regexps:

```
== ./workdir/simple/27_6.dfa ==
if
== ./workdir/simple/28_1.dfa ==
else
== ./workdir/simple/28_2.dfa ==
else
== ./workdir/simple/28_5.dfa ==
else
== ./workdir/simple/28_6.dfa ==
else
== ./workdir/simple/29_1.dfa ==
return(( ?|\t))*\n
== ./workdir/simple/29_2.dfa ==
return(( ?|\t))*\n
== ./workdir/simple/29_5.dfa ==
```

If you are crazy enough, you can create a crappy AFL/Rust mutator using `jreflex`.
Just know that in order to build you'll have to wait **A LOT**.

```sh
./scala/jreflex.sh rust ./rust-mutator/src/gen.rs ./workdir/simple/*.dfa
```

## Ghidra Script

I made a quickly hacked ghidra script to export `yylex` data flow into graphviz
and graphml files.
The latter can be used together with `find-tables.sh` to (hopefully) recover
the tables from the binary automatically.
It doesn't always work (ghidra's IR is funky), but even when it fails it can
recover at least some of the tables.

Instructions:

1. `cp ./ghidra/Reflex.java ~/ghidra_script/`
2. Open ghidra
3. Navigate to `yylex()` in your binary
4. Run the script
   (it will create `/tmp/out.xml`, `/tmp/out.dot` and `/tmp/simple.dot`)
5. Run `scala/find-tables.sh` and it should output the arguments to forward to
   `reflex.py`

## A bit of history

This was all part of a PhD at Politecnico di Milano, that I dropped :D

Reflex started the day in which I had a Twitter argument with the _unofficial_
account of a very well known open-source reverse engineering tool (wink wink).
Actually, they just replied to me in an **very** hostile way for no reason.
I was angry, the source code of the tool was not available back then, so I
decided to do the only reasonable thing: take the only non-java binary and
find bugs to prove my point.
Turns out it (sleigh) was using a flex-based parser to read architecture
definitions or something like that.
So I built a python script to extract and uncompress the DFA embedded in the
program as a graph.
Then a bunch of stuff happened, but in the current form you have a scala tool
that can output the regexps in readable form (among other half-assed things).

What's kinda interesting is that I did most of this in May 2019.
In August I was at **DEF CON CTF** with mhackeroni and there was a challenge
based on flex and bison.
We succesfully used my tool and it worked perfectly.
How likely is that you write a tool because of a mean comment and then use it
at DEF CON?
I found it pretty funny.

Anyway, the idea was to sell Reflex as a research paper, but as I said I
dropped out. Arguably there's no real science in all of this. We wanted to try
fuzzing programs with the tokens extracted using it, but the project died thanks
to my life choices.

By the way, there's an integer underflow in `sleigh` because it doesn't account
for null characters inside strings (aka strings with length 0).
This leads to a `new std::string()` of `-1` characters.
Hurray for real science.

## Fun facts

Programs that I know of using flex/bison:

- AutoIt's Au3check and malware using AutoIt
- Autodesk Maya: libOGSDeviceOGL4.dylib
- Unity: libunity.so (probably for shaders)
- VxWorks: has a C interpreter written in flex/bison (WTF... if you know why there's such thing, tell me in an issue)
- Zyxel: the shell of some routers (e.g.: `WAC6103D-I_5.50(AAXH.3)C0`)
- A lot of open source projects (not interesting)
