#!/usr/bin/env python3

import itertools
import json
import os
import pickle
import struct
import types

from collections import defaultdict
from collections import namedtuple

import cxxfilt
import lief
import networkx as nx


def analyze_exits(G):
    # map an accepting state into its exit values
    exits = {}
    for u, u_data in G.nodes(data=True):
        if not u_data['accepts']:
            continue

        for _, v, data in G.edges(u, data=True):
            # ee = set(chr(i) for i in range(0, 256)).difference(data['alphabet'])
            ee = set(chr(i) for i in range(1, 256)).difference(data['alphabet'])
            # assert ee
            exits[u] = ''.join(sorted(ee))
    return exits


SIZE_TO_FORMAT = {
    1: 'B',
    2: 'H',
    4: 'I',
}


SizedOffset = namedtuple(
    'SizedOffset',
    [
        'offset',
        'size',
    ]
)


Config = namedtuple(
    'Config',
    [
        'target',
        'out_path',
        'max_state',
        'strip_nulls',
        'states_to_strip',
        'endianness',
        'yy_accept',
        'yy_base',
        'yy_chk',
        'yy_def',
        'yy_ec',
        'yy_meta',
        'yy_nxt',
    ]
)


class Target(object):

    def __init__(self, config):
        self._bin = open(config.target, 'rb')
        self._cfg = config
        self._fmt = '<' if config.endianness == 'little' else '>'

    def _read(self, w, i):
        size   = w.size
        offset = w.offset + i * size
        fmt    = self._fmt + SIZE_TO_FORMAT[w.size]
        self._bin.seek(offset)
        data = self._bin.read(size)
        return struct.unpack(fmt, data)[0]

    def yy_accept(self, i):
        return self._read(self._cfg.yy_accept, i)

    def yy_base(self, i):
        return self._read(self._cfg.yy_base, i)

    def yy_chk(self, i):
        return self._read(self._cfg.yy_chk, i)

    def yy_def(self, i):
        return self._read(self._cfg.yy_def, i)

    def yy_ec(self, i):
        return self._read(self._cfg.yy_ec, i)

    def yy_meta(self, i):
        return self._read(self._cfg.yy_meta, i)

    def yy_nxt(self, i):
        return self._read(self._cfg.yy_nxt, i)


def reflex(config):
    flex = Target(config)
    max_state = config.max_state
    strip_states = config.states_to_strip
    strip_nulls = config.strip_nulls
    out_path = config.out_path


    class2chars = defaultdict(set)
    # for i in range(0, 256):
    for i in range(1, 256):
        class2chars[flex.yy_ec(i)].add(chr(i))


    def class2string(c):
        return ''.join(map(chr,sorted(class2chars[c])))


    G = nx.DiGraph()
    for state in range(max_state):
        G.add_node(state, label='|' + str(state) + '|', accepts=flex.yy_accept(state))


    def follow(H, state, clazz):
        s = state
        c = clazz
        while flex.yy_chk(flex.yy_base(state) + clazz) != state:
            # print(state)
            state = flex.yy_def(state)
            if state >= max_state:
                clazz = flex.yy_meta(clazz)
        next_state = flex.yy_nxt(flex.yy_base(state) + clazz)

        if strip_states and next_state in strip_states:
            return

        # if (state, next_state) in H.edges:
        if (s, next_state) in H.edges:
            # H.edges[(state, next_state)]['alphabet'] = H.edges[(state, next_state)]['alphabet'].union(class2chars[c])
            H.edges[(s, next_state)]['alphabet'] = H.edges[(s, next_state)]['alphabet'].union(class2chars[c])
        else:
            # H.add_edge(state, next_state, alphabet=class2chars[c], accepts=flex.yy_accept[next_state])

            if strip_nulls and c == 0:
                return
            H.add_edge(s, next_state, alphabet=class2chars[c])


    for curr_state in range(1, max_state):
        print(curr_state)
        if flex.yy_accept(curr_state):
            G.nodes[curr_state]['shape'] = 'doublecircle'

        for clazz in class2chars:
            follow(G, curr_state, clazz)

        # if (curr_state, flex.yy_def[curr_state]) not in G.edges:
            # G.add_edge(curr_state, flex.yy_def[curr_state], alphabet=set(), accepts=flex.yy_accept[curr_state])


    for u, v, data in G.edges(data=True):
        label = repr(''.join(sorted(data['alphabet']))) if data['alphabet'] else '•'
        label = label.replace('\\', '\\\\')

        if len(data['alphabet']) >= 255:
            label = 'all'
        elif len(data['alphabet']) > 50:
            label = 'long'

        data['label'] = label

    # Remove nodes without label (AKA added by transitions and not by us)
    # TODO(babush): properly tag them instead of relying on the label
    to_remove = set()
    for n, data in G.nodes(data=True):
        if 'label' not in data:
            to_remove.add(n)
            continue

        label = data['label']

        if data['accepts']:
            label += '/{}'.format(data['accepts'])

        data['label'] = label

    for n in to_remove:
        G.remove_node(n)


    import networkx.drawing.nx_agraph
    networkx.drawing.nx_agraph.write_dot(G, os.path.join(out_path, 'out.dot'))
    networkx.readwrite.gpickle.write_gpickle(G, os.path.join(out_path, 'G.gpickle'))

    sub1 = nx.descendants(G, 1)
    sub1.add(1)
    sub1 = G.subgraph(sub1)
    networkx.drawing.nx_agraph.write_dot(sub1, os.path.join(out_path, '1.dot'))

    # Export DFA map
    dfa_transitions = {}
    for u, v, e_data in G.edges(data=True):
        v_data = G.nodes[v]
        for ch in e_data['alphabet']:
            dfa_transitions[(u, ch)] = (v, v_data['accepts'])
    pickle.dump(dfa_transitions, open(os.path.join(out_path, 'dfa_transitions.pickle'), 'wb'))

    # Dump exits
    pickle.dump(analyze_exits(G), open(os.path.join(out_path, 'exits.pickle'), 'wb'))

