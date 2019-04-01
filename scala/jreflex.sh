#!/bin/bash

DIR="$(dirname $0)"
JAR="$DIR/jreflex.jar"

java -jar $JAR "$@"

