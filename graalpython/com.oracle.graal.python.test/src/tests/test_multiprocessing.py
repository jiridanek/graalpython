# Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
import multiprocessing
import sys
import time
from multiprocessing.connection import wait


def test_wait_timeout():
    timeout = 3
    a, b = multiprocessing.Pipe()
    x, y = multiprocessing.connection.Pipe(False)  # Truffle multiprocessing pipe
    for fds in [[a, b], [x, y], [a, b, x, y]]:
        start = time.monotonic()
        res = wait(fds, timeout)
        delta = time.monotonic() - start
        assert not res
        assert delta < timeout * 2
        assert delta > timeout / 2


def test_wait():
    a, b = multiprocessing.Pipe()
    x, y = multiprocessing.connection.Pipe(False)  # Truffle multiprocessing pipe
    a.send(42)
    res = wait([b, y], 3)
    assert res == [b], "res1"
    assert b.recv() == 42, "res2"
    y.send(33)
    res = wait([b, x], 3)
    assert res == [x], "res3"
    assert x.recv() == 33, "res4"
    a.send(1)
    y.send(2)
    res = wait([b, x], 3)
    assert set(res) == set([b, x])
    assert b.recv() == 1
    assert x.recv() == 2


def test_array_read():
    # TODO multiprocessing.Array doesn't work on emulated backend
    if sys.implementation.name == 'graalpy' and __graalpython__.posix_module_backend() == 'java':
        return
    # This used to be buggy due to wrong usage of memoryview offsets when two objects were allocated in the same block
    # Don't remove the unused value on the next line
    # noinspection PyUnusedLocal
    num = multiprocessing.Value('d', 0.0)
    arr = multiprocessing.Array('i', range(10))
    assert arr[1] == 1
