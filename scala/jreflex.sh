#!/bin/bash

DIR="$(dirname $0)"
JAR="$DIR/jreflex.jar"

# I lost many hours of sleep because of this. Fuck you. Fucking fuck you bad.
# WHY NOT USE NON-DETERMINISTIC SHIT IN YOUR DFA LIBRARY?
# FUCK YOU
java -Ddk.brics.automaton.debug=1 -jar $JAR "$@"

