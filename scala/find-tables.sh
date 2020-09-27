#!/bin/bash

DIR="$(dirname $0)"
JAR="$DIR/jreflex.jar"

java -cp $JAR io.github.thebabush.reflex.graph.main "$@"

