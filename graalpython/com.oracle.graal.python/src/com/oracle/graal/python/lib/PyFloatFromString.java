/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.lib;

import static com.oracle.graal.python.builtins.PythonBuiltinClassType.TypeError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.ValueError;

import com.oracle.graal.python.builtins.objects.floats.FloatUtils;
import com.oracle.graal.python.builtins.objects.object.ObjectNodes;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PNodeWithContext;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToJavaStringNode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;

/**
 * Equivalent of CPython's {@code PyFloat_FromString}. Converts a string to a python float (Java
 * {@code double}). Raises {@code ValueError} when the conversion fails.
 */
@GenerateUncached
public abstract class PyFloatFromString extends PNodeWithContext {
    public abstract double execute(Frame frame, Object obj);

    public abstract double execute(Frame frame, String obj);

    @Specialization
    static double doString(VirtualFrame frame, String object,
                    @Shared("repr") @Cached ObjectNodes.ReprAsJavaStringNode reprNode,
                    @Shared("raise") @Cached PRaiseNode raiseNode) {
        return convertStringToDouble(frame, object, object, reprNode, raiseNode);
    }

    @Specialization
    static double doGeneric(VirtualFrame frame, Object object,
                    @Cached CastToJavaStringNode cast,
                    @CachedLibrary(limit = "3") PythonObjectLibrary lib,
                    @Shared("repr") @Cached ObjectNodes.ReprAsJavaStringNode reprNode,
                    @Shared("raise") @Cached PRaiseNode raiseNode) {
        String string = null;
        try {
            string = cast.execute(object);
        } catch (CannotCastException e) {
            if (lib.isBuffer(object)) {
                try {
                    byte[] bytes = lib.getBufferBytes(object);
                    string = PythonUtils.newString(bytes);
                } catch (UnsupportedMessageException e1) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
        }
        if (string != null) {
            return convertStringToDouble(frame, string, object, reprNode, raiseNode);
        }
        throw raiseNode.raise(TypeError, ErrorMessages.ARG_MUST_BE_STRING_OR_NUMBER, "float()", object);
    }

    private static double convertStringToDouble(VirtualFrame frame, String src, Object origObj, ObjectNodes.ReprAsJavaStringNode reprNode, PRaiseNode raiseNode) {
        String str = FloatUtils.removeUnicodeAndUnderscores(src);
        // Adapted from CPython's float_from_string_inner
        if (str != null) {
            int len = str.length();
            int offset = FloatUtils.skipAsciiWhitespace(str, 0, len);
            FloatUtils.StringToDoubleResult res = FloatUtils.stringToDouble(str, offset, len);
            if (res != null) {
                int end = FloatUtils.skipAsciiWhitespace(str, res.position, len);
                if (end == len) {
                    return res.value;
                }
            }
        }
        String repr;
        try {
            repr = reprNode.execute(frame, origObj);
        } catch (PException e) {
            // Failed to format the message. Mirrors CPython behavior when the repr fails
            throw raiseNode.raise(ValueError);
        }
        throw raiseNode.raise(ValueError, ErrorMessages.COULD_NOT_CONVERT_STRING_TO_FLOAT, repr);
    }

}
