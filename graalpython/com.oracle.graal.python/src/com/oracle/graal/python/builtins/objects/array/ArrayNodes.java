/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.objects.array;

import com.oracle.graal.python.builtins.objects.common.BufferStorageNodes;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

public abstract class ArrayNodes {
    @GenerateInline
    @GenerateUncached(false)
    @GenerateCached(false)
    public abstract static class GetValueNode extends Node {
        public abstract Object execute(Node inliningTarget, PArray array, int index);

        @Specialization
        static Object get(Node node, PArray array, int index,
                        @Bind("this") Node inliningTarget,
                        @Cached BufferStorageNodes.UnpackValueNode unpackValueNode) {
            return unpackValueNode.execute(inliningTarget, array.getFormat(), array.getBuffer(), index * array.getFormat().bytesize);
        }
    }

    @GenerateInline
    @GenerateUncached(false)
    @GenerateCached(false)
    public abstract static class PutValueNode extends Node {
        public abstract void execute(VirtualFrame frame, Node inliningTarget, PArray array, int index, Object value);

        @Specialization
        static void put(VirtualFrame frame, PArray array, int index, Object value,
                        @Cached(inline = false) BufferStorageNodes.PackValueNode packValueNode) {
            packValueNode.execute(frame, array.getFormat(), value, array.getBuffer(), index * array.getFormat().bytesize);
        }
    }

    @GenerateInline
    @GenerateUncached(false)
    @GenerateCached(false)
    public abstract static class CheckValueNode extends Node {
        public abstract void execute(VirtualFrame frame, Node inliningTarget, PArray array, Object value);

        @Specialization
        static void check(VirtualFrame frame, PArray array, Object value,
                        @Cached(inline = false) BufferStorageNodes.PackValueNode packValueNode) {
            packValueNode.execute(frame, array.getFormat(), value, new byte[8], 0);
        }
    }
}
