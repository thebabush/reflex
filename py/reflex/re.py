import json


class Regexp(object):
    def __init__(self, contents=None):
        self.contents = contents

    @classmethod
    def from_json(clazz, j):
        if not isinstance(j, dict):
            return j
        tag = j['tag']
        if tag == 'Then':
            r = Then()
        elif tag == 'Literal':
            r = Literal()
        elif tag == 'RESet':
            r = RESet()
        elif tag == 'Star':
            r = Star()
        elif tag == 'OneOrMore':
            r = Plus()
        elif tag == 'Or':
            r = Or()
        elif tag == 'Optional':
            r = Optional()
        else:
            raise Exception(tag)
        r.from_contents(j['contents'])
        return r

    def from_contents(self, c):
        if not isinstance(c, list):
            c = [c]
        self.contents = [Regexp.from_json(ci) for ci in c]

    def pp(self, indent=0):
        ret = []
        if self.is_re_set():
            ret.append('  ' * (indent) + repr(''.join(chr(x) for x in self.contents)))
        else:
            ret.append((' ' * indent) + self.__class__.__name__)
            for c in self.contents:
                if isinstance(c, int):
                    ret.append(('  ' * (indent + 1)) + repr(chr(c)))
                elif c.is_re_set():
                    ret.append('  ' * (indent + 1) + repr(''.join(chr(x) for x in c.contents)))
                else:
                    ret.append(('  ' * (indent + 1)) + c.pp(indent + 1))
        return '\n'.join(ret)

    def __str__(self):
        return self.pp()

    def is_empty(self):
        return False

    def is_literal(self):
        return False

    def is_optional(self):
        return False

    def is_or(self):
        return False

    def is_then(self):
        return False

    def is_re_set(self):
        return False

    def is_star(self):
        return False
    
    def is_plus(self):
        return False

    def is_leaf(self):
        return self.is_re_set() or self.is_literal()


class Empty(Regexp):
    def __init__(self):
        pass

    def is_empty(self):
        return True


class Optional(Regexp):
    def is_optional(self):
        return True


class Or(Regexp):
    def is_or(self):
        return True


class Plus(Regexp):
    def is_plus(self):
        return True


class Then(Regexp):
    def is_then(self):
        return True


class Literal(Regexp):
    def is_literal(self):
        return True


class RESet(Regexp):
    def is_re_set(self):
        return True


class Star(Regexp):
    def is_star(self):
        return True


def load_regexp(s):
    j = json.loads(s)
    o = Regexp.from_json(j)
    return o

