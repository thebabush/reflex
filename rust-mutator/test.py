#!/usr/bin/env python3

import ctypes


so = ctypes.CDLL('./target/debug/libmutator.so')

data = bytes([1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])
data = bytes([0, 0, 0, 0, 0, 0, 0, 0])
mutated_out = ctypes.create_string_buffer(0x1000)
spliced_out = ctypes.create_string_buffer(0x1000)

so.afl_custom_mutator.argtypes = [
    ctypes.c_char_p,
    ctypes.c_size_t,
    ctypes.c_char_p,
    ctypes.c_size_t,
    ctypes.c_int,
]

r = so.afl_custom_mutator(
    data,
    len(data),
    mutated_out,
    len(mutated_out),
    666,
)

print(r)

mem = ctypes.POINTER(ctypes.c_ubyte)()
p_mem = ctypes.byref(mem)

so.afl_custom_splicer(
    data,
    len(data),
    data,
    len(data),
    p_mem,
)

