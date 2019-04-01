#!/usr/bin/env python3

import argparse
import os

import reflex.reflex
from reflex.reflex import SizedOffset


def s2i(s):
    if s.startswith('0x'):
        return int(s, 16)
    else:
        return int(s)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('target', type=str, nargs=1)
    parser.add_argument('out_path', metavar="out-path/", type=str, nargs=1)
    parser.add_argument('--accept', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--base', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--chk', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--def', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--ec', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--meta', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--nxt', metavar=('offset', 'size'), type=s2i, nargs=2, required=1)
    parser.add_argument('--max-state', type=s2i, nargs=1, required=1)
    parser.add_argument('--endianness', type=str, nargs=1, default='little')
    parser.add_argument('--strip-nulls', type=bool, nargs=1, default=True)
    args = parser.parse_args()

    assert args.endianness in {'little', 'big'}

    target = args.target[0]
    target = os.path.abspath(target)

    out_path = args.out_path[0]
    out_path = os.path.abspath(out_path)
    os.makedirs(out_path, exist_ok=True)

    config = reflex.reflex.Config(
        target,
        out_path,
        args.max_state[0],
        args.strip_nulls,
        set(),
        args.endianness,
        SizedOffset(args.accept[0], args.accept[1]),
        SizedOffset(args.base[0]  , args.base[1]),
        SizedOffset(args.chk[0]   , args.chk[1]),
        SizedOffset(getattr(args, 'def')[0], getattr(args, 'def')[1]),
        SizedOffset(args.ec[0]    , args.ec[1]),
        SizedOffset(args.meta[0]  , args.meta[1]),
        SizedOffset(args.nxt[0]   , args.nxt[1]),
    )

    reflex.reflex.reflex(config)
    # reflex.reflex.reflex(SYMBOLS, SLEIGH, '../data/', MAX_STATE)


if __name__ == '__main__':
    main()


