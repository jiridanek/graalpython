/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
// skip GIL
package com.oracle.graal.python.builtins.objects.cext.capi;

import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.PythonObjectReference;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.profiles.ConditionProfile;

public abstract class PythonNativeWrapper implements TruffleObject {

    /**
     * Reference count of an object that is only referenced by the Java heap - this is larger than 1
     * since native code sometimes special cases for low refcounts.
     */
    public static final long MANAGED_REFCNT = 10;

    public static final long IMMORTAL_REFCNT = 256 * 256;

    private static final long UNINITIALIZED = -1;

    private Object delegate;
    private long nativePointer = UNINITIALIZED;

    /**
     * Equivalent to {@code ob_refcnt}.
     */
    private long refCount = MANAGED_REFCNT;

    public PythonObjectReference ref;

    public PythonNativeWrapper() {
    }

    public PythonNativeWrapper(Object delegate) {
        this.delegate = delegate;
    }

    public final long getRefCount() {
        return refCount;
    }

    public final void setRefCount(long refCount) {
        this.refCount = refCount;
    }

    public final Object getDelegate() {
        return delegate;
    }

    protected final void setDelegate(Object delegate) {
        assert this.delegate == null || this.delegate == delegate;
        this.delegate = delegate;
    }

    public final long getNativePointer() {
        return nativePointer;
    }

    public final void setNativePointer(long nativePointer) {
        // we should set the pointer just once
        assert this.nativePointer == UNINITIALIZED || this.nativePointer == nativePointer || nativePointer == UNINITIALIZED;
        this.nativePointer = nativePointer;
    }

    public final boolean isNative(
                    @Cached ConditionProfile hasNativePointerProfile) {
        return hasNativePointerProfile.profile(nativePointer != UNINITIALIZED);
    }

    public final boolean isNative() {
        return nativePointer != UNINITIALIZED;
    }

    protected static long coerceToLong(Object allocated, InteropLibrary lib) {
        if (allocated instanceof Long) {
            return (long) allocated;
        } else {
            if (!lib.isPointer(allocated)) {
                lib.toNative(allocated);
            }
            try {
                return lib.asPointer(allocated);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }
    }
}
