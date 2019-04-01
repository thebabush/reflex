#!/usr/bin/env python3

import argparse
import os

import reflex.simplify


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('graph', metavar='graph.gpickle', type=str, nargs=1)
    parser.add_argument('out_path', metavar="out-path", type=str, nargs=1)
    args = parser.parse_args()

    graph = args.graph[0]
    graph = os.path.abspath(graph)

    out_path = args.out_path[0]
    out_path = os.path.abspath(out_path)
    os.makedirs(out_path, exist_ok=True)

    reflex.simplify.simplify(graph, out_path)


if __name__ == '__main__':
    main()

