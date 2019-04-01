#!/usr/bin/env python3

import os


test = input('> ')
test = eval(test)
print(test)
open('/tmp/test.slaspec', 'wb').write(test)
os.system('./stroz/sleigh /tmp/test')


