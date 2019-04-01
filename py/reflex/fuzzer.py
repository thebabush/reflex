#!/usr/bin/env python3

import collections
import glob
import itertools
import json
import os
import pickle
import random
import shlex
import subprocess

import numpy as np

from . import re


MIN_TOKENS = 5
MAX_TOKENS = 10
MAX_TEST_LEN = 10


RS = collections.namedtuple('RS', ('rule', 'state'))


def parse_file_name(fn):
    _, fn = os.path.split(fn)
    fn = fn.split('.')[0]
    rule, state = fn.split('_')
    return RS(int(rule), int(state))


def solve_regex(re):
    if re.is_re_set():
        return random.choice(re.contents),
    elif re.is_literal():
        return re.contents
    elif re.is_then():
        return itertools.chain(solve_regex(re.contents[0]), solve_regex(re.contents[1]))
    elif re.is_or():
        return solve_regex(random.choice(re.contents))
    elif re.is_optional():
        if random.random() >= 0.5:
            return solve_regex(random.choice(re.contents))
        else:
            return []
    elif re.is_star():
        ret = []
        while random.random() >= 0.3:
            ret = itertools.chain(ret, solve_regex(re.contents[0]))
        return ret
    elif re.is_plus():
        ret = solve_regex(re.contents[0])
        while random.random() >= 0.3:
            ret = itertools.chain(ret, solve_regex(re.contents[0]))
        return ret
    else:
        print(re.__class__.__name__)
        return []


class Testcase(object):
    def __init__(self, tokens, coverage=None):
        self.tokens = list(tokens)
        self.coverage = coverage


def mk_generate(rm):
    states = list(rm.keys())
    def gen():
        tokens = []
        for i in range(random.randint(MIN_TOKENS, MAX_TOKENS)):
            state = random.choice(states)
            re = random.choice(rm[state])
            token = solve_regex(re)
            tokens.append(list(token))
        return Testcase(tokens)
    return gen


def mk_generate_one(rm):
    states = list(rm.keys())
    def gen():
        state = random.choice(states)
        re = random.choice(rm[state])
        token = solve_regex(re)
        return token
    return gen


def trans1_intersperse_maybe_rand(rm, t):
    it = iter(t)
    yield next(it)
    for x in it:
        if random.random() > 0.5:
            yield [random.randint(0, 255)]
        yield x


def trans1_intersperse_maybe_whitespace(rm, t):
    k = random.choice(b' \t\n')
    it = iter(t)
    yield next(it)
    for x in it:
        if random.random() < 0.2:
            yield [k]
        yield x


# def trans1_intersperse_maybe_const(rm, t):
    # k = random.randint(0, 255)
    # it = iter(t)
    # yield next(it)
    # for x in it:
        # if random.random() > 0.95:
            # yield [k]
        # yield x


def trans1_replicate_maybe(rm, tt):
    for t in tt:
        if random.random() > 0.95:
            for i in range(random.randint(1, 10)):
                yield t
        yield t


# def trans1_filter_rand(rm, tt):
    # for t in tt:
        # if random.random() < 0.9:
            # yield t


# def trans1_strip_pre(rm, tt):
    # if len(tt) < 3:
        # return tt
    # start = random.randint(0, min(len(tt), 3) - 1)
    # return tt[start:]


# def trans1_strip_post(rm, tt):
    # if len(tt) < 3:
        # return tt
    # end = random.randint(0, min(len(tt), 3) - 1)
    # return tt[:-end]


def trans1_concat_token(gen, tt):
    for t in tt:
        yield t
    yield gen()


def trans1_swap_token_maybe(gen, tt):
    for t in tt:
        if random.random() < 0.1:
            t = gen()
        yield t


def transform(tfunk, testcase):
    return Testcase(tfunk(testcase.tokens))


def trans2_sex(aa, bb, prob=0.5):
    for a, b in zip(aa, bb):
        if random.random() < prob:
            yield a
        else:
            yield b


def trans2_simple_sex(aa, bb):
    for t in trans2_sex(aa, bb, prob=0.95):
        yield t


def trans2_concat(aa, bb):
    if random.random() > 0.5:
        return aa + bb
    else:
        return bb + aa


def trans2_splice(aa, bb):
    aa = list(aa)
    bb = list(bb)
    aa = aa[random.randint(0, len(aa)-1):]
    bb = bb[random.randint(0, len(bb)-1):]
    return aa + bb


def transform2(tfunk, t1, t2):
    return Testcase(tfunk(t1.tokens, t2.tokens))


def get_transforms():
    transforms1 = []
    transforms2 = []
    g = globals()
    for t in list(g):
        if t.startswith('trans1'):
            transforms1.append(g[t])
        elif t.startswith('trans2'):
            transforms2.append(g[t])
    return transforms1, transforms2


def afl_showmap(argv, file_path, coverage_path, test):
    CMD = 'afl-showmap -Q -b -o {coverage} -- {argv}'
    cmd = CMD.format(argv=argv, coverage=coverage_path)
    # print(cmd)
    with open(file_path, 'wb') as f:
        f.write(test)

    # print(test)

    ret = subprocess.run(
            shlex.split(cmd),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
    )

    if ret.returncode < 0:
        return ret.returncode, None

    cov = None
    with open(coverage_path, 'rb') as f:
        cov = np.fromfile(coverage_path, dtype=np.uint8)
    return ret.returncode, cov


class Fuzzer(object):
    def __init__(self, out_dir, regmap, argv, file_path, name='bbz'):
        self.sync_dir = out_dir
        self.my_dir = os.path.join(out_dir, name)
        self.my_queue = os.path.join(self.my_dir, 'queue')
        self.my_coverage = os.path.join(self.my_dir, 'coverage.bin')

        self.regmap = regmap
        self.argv = argv
        self.fp = file_path
        self.tests = []
        self.global_coverage = np.zeros(65536, dtype=np.uint8)

        self.init_path()
        self.transforms = get_transforms()

    def init_path(self):
        os.makedirs(self.sync_dir, exist_ok=True)
        os.makedirs(self.my_dir, exist_ok=True)
        os.makedirs(self.my_queue, exist_ok=True)

    def fuzz(self):
        generate = mk_generate(self.regmap)
        self.generate_one = mk_generate_one(self.regmap)
        while True:
            print('Creating a new test and fuzzing it...')
            test = generate()
            self.fuzz_one(test, times=max(1, 100 - len(self.tests) // 2))

            print('Iterating on old tests...')
            for i in range(100):
                test = random.choice(self.tests)
                print('Old {}...'.format(i))
                self.fuzz_one(test, times=10)

    def fuzz_one(self, test, times):
        for i in range(times):
            if i % 10 == 0:
                print(i)

            # TODO: do we really need a copy?
            new_test = test.tokens[:]
            for j in range(1 << random.randint(1, 4)):
                transform = random.choice(self.transforms[0])
                new_test = list(transform(self.generate_one, new_test))

            if len(new_test) > MAX_TEST_LEN:
                new_test = new_test[:MAX_TEST_LEN]

            # Skip empty testcases
            if not new_test:
                continue

            new_test = Testcase(new_test)
            buff = b''.join(bytes(t) for t in new_test.tokens)

            # Get the coverage
            exitcode, cov = afl_showmap(self.argv, self.fp, self.my_coverage, buff)
            if exitcode < 0:
                print('CRASH!!!')
                exit()
            cov = (cov > 0).astype(np.uint8)
            new_test.coverage = cov

            new_global_coverage = cov | self.global_coverage
            if np.any(new_global_coverage != self.global_coverage):
                print('Increased coverage :D')
                print(buff)
                self.global_coverage = new_global_coverage
                self.tests.append(new_test)

        SPLICE_ROUNDS = len(self.tests)
        for i in range(SPLICE_ROUNDS):
            other = random.choice(self.tests)
            new_test = random.choice(self.transforms[1])(test.tokens[:], other.tokens[:])

            new_test = Testcase(new_test)
            buff = b''.join(bytes(t) for t in new_test.tokens)

            # Get the coverage
            exitcode, cov = afl_showmap(self.argv, self.fp, self.my_coverage, buff)
            if exitcode < 0:
                print('CRASH!!!')
                exit()
            cov = (cov > 0).astype(np.uint8)
            new_test.coverage = cov

            new_global_coverage = cov | self.global_coverage
            if np.any(new_global_coverage != self.global_coverage):
                print('Splice increased coverage :D')
                print(buff)
                self.global_coverage = new_global_coverage
                self.tests.append(new_test)
            

def main(out_dir, file_path, argv, regexps_path):
    s = random.randint(0, 100000)
    random.seed(s)
    # random.seed(54136)
    print('SEED:', s)

    regmap = collections.defaultdict(list)
    for fn in sorted(glob.glob(os.path.join(regexps_path, '*.regexp')), key=lambda x: parse_file_name(x)):
        with open(fn, 'r') as f:
            rs = parse_file_name(fn)
            regexp = re.load_regexp(f.read())
            regmap[rs.state].append(regexp)

    fuzzer = Fuzzer(out_dir, regmap, argv, file_path)
    fuzzer.fuzz()

