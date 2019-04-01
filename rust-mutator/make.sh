#!/bin/bash

export RUST_BACKTRACE=1

cargo build && \
  RUST_BACKTRACE=1 ./test.py

