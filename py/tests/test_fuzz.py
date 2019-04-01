from reflex import fuzzer as F


def test_partial_regexp_eval():
    re_set = F.RESet([ord(c) for c in 'abc'])
    lit_a = F.Literal([ord('a')])
    lit_b = F.Literal([ord('b')])
    lit_c = F.Literal([ord('c')])
    
    r = F.partial_regexp_eval(re_set, lit_a)
    # print(r[1].pp())
    assert r == (True, None)


