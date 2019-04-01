#!/usr/bin/env python3

import functools
import json
import os

import networkx as nx
import networkx.drawing.nx_agraph


def write_dfa(fp, g, start):
    if len(g.edges) == 0:
        print("NO EDGES")
        return

    with open(fp, 'w') as f:
        alphabet = set()
        for _, _, aa in g.edges(data=True):
            alphabet.update(aa['alphabet'])

        f.write('{}\n'.format(start))
        f.write('{}\n'.format(len(g.nodes)))
        for n, data in g.nodes(data=True):
            f.write('{} {}\n'.format(n, 1 if data['accepts'] > 1 else 0))

        edges = []
        for u, v, data in g.edges(data=True):
            edges.append('{} {} {}'.format(
                u,
                v,
                ' '.join(
                    str(ord(c)) for c in data['alphabet']
                )
            ))

        # print(alphabet)
        f.write('{}\n'.format(len(edges)))
        f.write('\n'.join(edges) + '\n')

    with open(fp + '.nfa', 'w') as f:
        f.write('{}\n'.format(start))
        f.write('{}\n'.format(len(g.nodes)))
        for n, data in g.nodes(data=True):
            f.write('{} {}\n'.format(n, 1 if data['accepts'] > 1 else 0))
        f.write('{}\n'.format(len(g.edges)))
        for u, v, data in g.edges(data=True):
            f.write('{} {} {}\n'.format(
                u,
                v,
                ' '.join(
                    str(ord(c)) for c in data['alphabet']
                )
            ))


def simplify(g_pickle_path, out_path):
    G = nx.readwrite.gpickle.read_gpickle(g_pickle_path)
    R = G.reverse(copy=True)

    max_accepts = functools.reduce(lambda x, y: max(x, y[1]['accepts']), G.nodes(data=True), 0) + 1
    print('max_accepts:', max_accepts)

    for out in range(2, max_accepts):
        out_nodes = set()
        r = R.copy()
        for n, data in r.nodes(data=True):
            if data['accepts'] == out:
                out_nodes.add(n)

        subnodes = set(out_nodes)
        # print(subnodes)
        for n in out_nodes:
            r2 = nx.descendants(r, n)
            subnodes.update(r2)

        r2 = G.subgraph(subnodes).copy()

        # print(r2.copy())
        for n, data in r2.nodes(data=True):
            accepts = data['accepts']
            if accepts > 0:
                if accepts < out:
                    data['accepts'] = out
                    data['label'] += '/{}'.format(out)
                elif accepts > out:
                    data['accepts'] = 0
                    data['label'] += '/None'
                    del data['shape']

        # Write the simplified graph as dot-file
        networkx.drawing.nx_agraph.write_dot(r2, os.path.join(out_path, '{}.dot'.format(out)))

        # Dump the DFA for the Haskell part
        sources = list(n for n, d in r2.in_degree() if d == 0)
        # assert sources
        for start in sources:
            s = nx.descendants(r2, start)
            s.add(start)
            s = r2.subgraph(s).copy()
            write_dfa(os.path.join(out_path, '{}_{}.dfa'.format(out, start)), s, start)

