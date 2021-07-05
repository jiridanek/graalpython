/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.builtins.modules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.annotations.ArgumentClinic;
import com.oracle.graal.python.annotations.ArgumentClinic.ClinicConversion;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.Python3Core;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.modules.MarshalModuleBuiltinsClinicProviders.DumpNodeClinicProviderGen;
import com.oracle.graal.python.builtins.modules.MarshalModuleBuiltinsClinicProviders.DumpsNodeClinicProviderGen;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.code.CodeNodes.CreateCodeNode;
import com.oracle.graal.python.builtins.objects.code.PCode;
import com.oracle.graal.python.builtins.objects.common.EconomicMapStorage;
import com.oracle.graal.python.builtins.objects.common.HashingCollectionNodes;
import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorage.DictEntry;
import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceNodesFactory.GetObjectArrayNodeGen;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodesFactory.GetInternalObjectArrayNodeGen;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.floats.PFloat;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.builtins.objects.set.PBaseSet;
import com.oracle.graal.python.builtins.objects.str.PString;
import com.oracle.graal.python.builtins.objects.str.StringNodes;
import com.oracle.graal.python.builtins.objects.str.StringNodesFactory.IsInternedStringNodeGen;
import com.oracle.graal.python.builtins.objects.type.TypeNodes;
import com.oracle.graal.python.lib.PyComplexCheckExactNodeGen;
import com.oracle.graal.python.lib.PyDictCheckExactNodeGen;
import com.oracle.graal.python.lib.PyFloatCheckExactNodeGen;
import com.oracle.graal.python.lib.PyFrozenSetCheckExactNodeGen;
import com.oracle.graal.python.lib.PyListCheckExactNodeGen;
import com.oracle.graal.python.lib.PyLongCheckExactNodeGen;
import com.oracle.graal.python.lib.PySetCheckExactNodeGen;
import com.oracle.graal.python.lib.PyTupleCheckExactNodeGen;
import com.oracle.graal.python.lib.PyUnicodeCheckExactNodeGen;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallBinaryNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallUnaryNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.clinic.ArgumentClinicProvider;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;

@CoreFunctions(defineModule = "marshal")
public final class MarshalModuleBuiltins extends PythonBuiltins {
    private static final String CURRENT_VERSION_STR = "4";
    private static final int CURRENT_VERSION = Integer.parseInt(CURRENT_VERSION_STR);

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return MarshalModuleBuiltinsFactory.getFactories();
    }

    @Override
    public void initialize(Python3Core core) {
        super.initialize(core);
        builtinConstants.put("version", CURRENT_VERSION);
    }

    @Builtin(name = "dump", minNumOfPositionalArgs = 2, parameterNames = {"value", "file", "version"})
    @ArgumentClinic(name = "version", defaultValue = CURRENT_VERSION_STR, conversion = ClinicConversion.Int)
    @GenerateNodeFactory
    abstract static class DumpNode extends PythonTernaryClinicBuiltinNode {
        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return DumpNodeClinicProviderGen.INSTANCE;
        }

        protected static LookupAndCallBinaryNode createCallWriteNode() {
            return LookupAndCallBinaryNode.create("write");
        }

        @Specialization
        Object doit(VirtualFrame frame, Object value, Object file, int version,
                        @Cached("createCallWriteNode()") LookupAndCallBinaryNode callNode) {
            try {
                return callNode.executeObject(frame, file, factory().createBytes(Marshal.dump(value, version, getCore())));
            } catch (Marshal.MarshalError me) {
                if (me.argument != null) {
                    throw raise(me.type, me.message, me.argument);
                } else {
                    throw raise(me.type, me.message);
                }
            }
        }
    }

    @Builtin(name = "dumps", minNumOfPositionalArgs = 1, parameterNames = {"value", "version"})
    @ArgumentClinic(name = "version", defaultValue = CURRENT_VERSION_STR, conversion = ClinicConversion.Int)
    @GenerateNodeFactory
    abstract static class DumpsNode extends PythonBinaryClinicBuiltinNode {
        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return DumpsNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        Object doit(Object value, int version) {
            try {
                return factory().createBytes(Marshal.dump(value, version, getCore()));
            } catch (Marshal.MarshalError me) {
                if (me.argument != null) {
                    throw raise(me.type, me.message, me.argument);
                } else {
                    throw raise(me.type, me.message);
                }
            }
        }
    }

    @Builtin(name = "load", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class LoadNode extends PythonBuiltinNode {
        protected static LookupAndCallUnaryNode createCallReadNode() {
            return LookupAndCallUnaryNode.create("read");
        }

        @Specialization
        Object doit(VirtualFrame frame, Object file,
                        @Cached("createCallReadNode()") LookupAndCallUnaryNode callNode,
                        @CachedLibrary(limit = "3") PythonObjectLibrary bufferLib,
                        @Cached PRaiseNode raise) {
            Object buffer = callNode.executeObject(frame, file);
            if (!bufferLib.isBuffer(buffer)) {
                throw raise(PythonBuiltinClassType.TypeError, "file.read() in marshal.load did not return bytes");
            }
            return LoadsNode.doit(buffer, bufferLib, raise);
        }
    }

    @Builtin(name = "loads", minNumOfPositionalArgs = 1, numOfPositionalOnlyArgs = 1, parameterNames = {"bytes"})
    @ArgumentClinic(name = "bytes", conversion = ClinicConversion.ReadableBuffer)
    @GenerateNodeFactory
    abstract static class LoadsNode extends PythonUnaryClinicBuiltinNode {
        @TruffleBoundary
        @Specialization(limit = "3")
        static Object doit(Object buffer,
                        @CachedLibrary("buffer") PythonBufferAccessLibrary bufferLib,
                        @Cached PRaiseNode raise) {
            try {
                return Marshal.load(bufferLib.getInternalOrCopiedByteArray(buffer), bufferLib.getBufferLength(buffer));
            } catch (NumberFormatException e) {
                throw raise.raise(PythonBuiltinClassType.ValueError, ErrorMessages.BAD_MARSHAL_DATA_S, e.getMessage());
            } catch (UnsupportedMessageException | IOException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } catch (Marshal.MarshalError me) {
                if (me.argument != null) {
                    throw raise.raise(me.type, me.message, me.argument);
                } else {
                    throw raise.raise(me.type, me.message);
                }
            }
        }

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return MarshalModuleBuiltinsClinicProviders.LoadsNodeClinicProviderGen.INSTANCE;
        }
    }

    private static final class Marshal {
        private static final char TYPE_NULL = '0';
        private static final char TYPE_NONE = 'N';
        private static final char TYPE_NOVALUE = 'n';
        private static final char TYPE_FALSE = 'F';
        private static final char TYPE_TRUE = 'T';
        private static final char TYPE_STOPITER = 'S';
        private static final char TYPE_ELLIPSIS = '.';
        private static final char TYPE_INT = 'i';
        private static final char TYPE_INT64 = 'I'; // just for backward compatibility with CPython
        private static final char TYPE_FLOAT = 'f';
        private final static char TYPE_BINARY_FLOAT = 'g';
        private static final char TYPE_COMPLEX = 'x';
        private final static char TYPE_BINARY_COMPLEX = 'y';
        private static final char TYPE_LONG = 'l';
        private static final char TYPE_STRING = 's';
        private static final char TYPE_INTERNED = 't';
        private final static char TYPE_REF = 'r';
        private static final char TYPE_TUPLE = '(';
        private static final char TYPE_LIST = '[';
        private static final char TYPE_DICT = '{';
        private static final char TYPE_CODE = 'c';
        private static final char TYPE_GRAALPYTHON_CODE = 'C';
        private final static char TYPE_UNICODE = 'u';
        private static final char TYPE_UNKNOWN = '?';
        private static final char TYPE_SET = '<';
        private static final char TYPE_FROZENSET = '>';
        private static final char FLAG_REF = 0x80;
        private static final char TYPE_ASCII = 'a';
        private static final char TYPE_ASCII_INTERNED = 'A';
        private static final char TYPE_SMALL_TUPLE = ')';
        private static final char TYPE_SHORT_ASCII = 'z';
        private static final char TYPE_SHORT_ASCII_INTERNED = 'Z';
        private static final int MAX_MARSHAL_STACK_DEPTH = 2000;

        // CPython enforces 15bits per digit when reading/writing large integers for portability
        private static final int MARSHAL_SHIFT = 15;
        private static final BigInteger MARSHAL_BASE = BigInteger.valueOf(1 << MARSHAL_SHIFT);

        static final class MarshalError extends RuntimeException {
            static final long serialVersionUID = 5323687983726237118L;

            final PythonBuiltinClassType type;
            final String message;
            final Object argument;

            MarshalError(PythonBuiltinClassType type, String message) {
                this(type, message, null);
            }

            MarshalError(PythonBuiltinClassType type, String message, Object argument) {
                super(null, null);
                this.type = type;
                this.message = message;
                this.argument = argument;
            }

            @SuppressWarnings("sync-override")
            @Override
            public final Throwable fillInStackTrace() {
                return this;
            }
        }

        @TruffleBoundary
        private static byte[] dump(Object value, int version, Python3Core core) throws MarshalError {
            Marshal outMarshal = new Marshal(version, core.getTrue(), core.getFalse());
            outMarshal.writeObject(value);
            return outMarshal.out.toByteArray();
        }

        @TruffleBoundary
        private static Object load(byte[] ary, int length) throws NumberFormatException, IOException, MarshalError {
            Marshal inMarshal = new Marshal(ary, length);
            return inMarshal.readObject();
        }

        private static final PythonObjectFactory factory = PythonObjectFactory.getUncached();
        final HashMap<Object, Integer> refMap;
        final ArrayList<Object> refList;
        final ByteArrayOutputStream out;
        final ByteArrayInputStream in;
        final int version;
        final PInt pyTrue;
        final PInt pyFalse;
        int depth;

        Marshal(int version, PInt pyTrue, PInt pyFalse) {
            this.version = version;
            this.pyTrue = pyTrue;
            this.pyFalse = pyFalse;
            this.out = new ByteArrayOutputStream();
            this.refMap = new HashMap<>();
            this.depth = 0;
            this.in = null;
            this.refList = null;
        }

        Marshal(byte[] in, int length) {
            this.in = new ByteArrayInputStream(in, 0, length);
            this.refList = new ArrayList<>();
            this.depth = 0;
            this.version = -1;
            this.pyTrue = null;
            this.pyFalse = null;
            this.out = null;
            this.refMap = null;
        }

        private void writeByte(int v) {
            out.write(v);
        }

        private int readByte() {
            return in.read();
        }

        private void writeSize(int sz) {
            writeInt(sz);
        }

        private int readByteSize() {
            return checkSize(readByte());
        }

        private int readSize() {
            return checkSize(readInt());
        }

        private int checkSize(int sz) {
            if (sz < 0 || sz > in.available()) {
                throw new MarshalError(PythonBuiltinClassType.EOFError, ErrorMessages.BAD_MARSHAL_DATA_S, "size out of range");
            }
            return sz;
        }

        private void writeBytes(byte[] bytes) throws IOException {
            writeSize(bytes.length);
            out.write(bytes);
        }

        private byte[] readBytes() throws IOException {
            int sz = readSize();
            return in.readNBytes(sz);
        }

        private void writeInt(int v) {
            for (int i = 0; i < Integer.SIZE; i += Byte.SIZE) {
                out.write((v >> i) & 0xff);
            }
        }

        private int readInt() {
            int result = 0;
            for (int i = 0; i < Integer.SIZE; i += Byte.SIZE) {
                result |= (in.read() << i);
            }
            return result;
        }

        private void writeLong(long v) {
            for (int i = 0; i < Long.SIZE; i += Byte.SIZE) {
                out.write((int)((v >> i) & 0xff));
            }
        }

        private long readLong() {
            long result = 0;
            for (int i = 0; i < Long.SIZE; i += Byte.SIZE) {
                result |= (in.read() << i);
            }
            return result;
        }

        private void writeBigInteger(BigInteger v) {
            // for compatibility with cpython, we store the number in base 2**15
            BigInteger[] divRem;
            ArrayList<Integer> digits = new ArrayList<>();
            BigInteger quotient = v;
            do {
                divRem = quotient.divideAndRemainder(MARSHAL_BASE);
                quotient = divRem[0];
                digits.add(divRem[1].intValue());
            } while (quotient.signum() != 0);
            int sz = digits.size();
            if (v.signum() < 0) {
                writeSize(-sz);
            } else {
                writeSize(sz);
            }
            for (int digit : digits) {
                for (int i = 0; i < Short.SIZE; i += Byte.SIZE) {
                    out.write((digit >> i) & 0xff);
                }
            }
        }

        private BigInteger readBigInteger() {
            boolean negative;
            int sz = readSize();
            if (sz < 0) {
                negative = true;
                sz = -sz;
            } else {
                negative = false;
            }

            int digit = 0;
            for (int i = 0; i < Short.SIZE; i += Byte.SIZE) {
                digit |= (in.read() << i);
            }
            BigInteger result = BigInteger.valueOf(digit);

            for (int i = 1; i < sz; i++) {
                digit = 0;
                for (int j = 0; j < Short.SIZE; j += Byte.SIZE) {
                    digit |= (in.read() << j);
                }
                result = result.add(BigInteger.valueOf(digit).multiply(MARSHAL_BASE.pow(i)));
            }
            if (negative) {
                return result.negate();
            } else {
                return result;
            }
        }

        private void writeDouble(double v) {
            writeLong(Double.doubleToLongBits(v));
        }

        private double readDouble() {
            return Double.longBitsToDouble(readLong());
        }

        private void writeDoubleString(double v) throws IOException {
            writeShortString(Double.toString(v));
        }

        private double readDoubleString() throws NumberFormatException, IOException {
            return Double.parseDouble(readShortString());
        }

        private void writeReferenceOrComplexObject(Object v) {
            Integer reference = null;
            if (version < 3 || (reference = refMap.get(v)) == null) {
                int flag = 0;
                if (version >= 3) {
                    flag = FLAG_REF;
                    refMap.put(v, refMap.size());
                }
                writeComplexObject(v, flag);
            } else if (reference != null) {
                writeByte(TYPE_REF);
                writeInt(reference);
            }
        }

        private Object readReference() {
            int n = readSize();
            if (n >= refList.size()) {
                throw new MarshalError(PythonBuiltinClassType.ValueError, ErrorMessages.BAD_MARSHAL_DATA);
            }
            Object o = refList.get(n);
            assert o != null;
            return o;
        }

        private void writeObject(Object v) {
            depth++;

            if (depth >= MAX_MARSHAL_STACK_DEPTH) {
                throw new MarshalError(PythonBuiltinClassType.ValueError, ErrorMessages.MAX_MARSHAL_STACK_DEPTH);
            }

            // see CPython's w_object
            if (v == null) {
                writeByte(TYPE_NULL);
            } else if (v == PNone.NONE) {
                writeByte(TYPE_NONE);
            } else if (v == PNone.NO_VALUE) {
                writeByte(TYPE_NOVALUE);
            } else if (TypeNodes.IsSameTypeNode.getUncached().execute(v, PythonBuiltinClassType.StopIteration)) {
                writeByte(TYPE_STOPITER);
            } else if (TypeNodes.IsSameTypeNode.getUncached().execute(v, PythonBuiltinClassType.PEllipsis)) {
                writeByte(TYPE_ELLIPSIS);
            } else if (v == Boolean.TRUE || v == pyTrue) {
                writeByte(TYPE_TRUE);
            } else if (v == Boolean.FALSE || v == pyFalse) {
                writeByte(TYPE_FALSE);
            } else if (v instanceof Integer) {
                writeByte(TYPE_INT);
                writeInt((Integer) v);
            } else if (v instanceof Long) {
                writeByte(TYPE_INT64);
                writeLong((Long) v);
            } else if (v instanceof Float) {
                writeByte(TYPE_FLOAT);
                writeDouble((Float) v);
            } else if (v instanceof Double) {
                writeByte(TYPE_FLOAT);
                writeDouble((Double) v);
            } else {
                writeReferenceOrComplexObject(v);
            }

            depth--;
        }

        private void writeComplexObject(Object v, int flag) {
            try {
                if (PyLongCheckExactNodeGen.getUncached().execute(v)) {
                    BigInteger bigInt = ((PInt) v).getValue();
                    if (bigInt.signum() == 0) {
                        // we don't handle ZERO in read/writeBigInteger
                        writeByte(TYPE_INT | flag);
                        writeInt(0);
                    } else {
                        // other cases are fine to not narrow here
                        writeByte(TYPE_LONG | flag);
                        writeBigInteger(((PInt) v).getValue());
                    }
                } else if (PyFloatCheckExactNodeGen.getUncached().execute(v)) {
                    if (version > 1) {
                        writeByte(TYPE_BINARY_FLOAT | flag);
                        writeDouble(((PFloat) v).getValue());
                    } else {
                        writeByte(TYPE_FLOAT | flag);
                        writeDoubleString(((PFloat) v).getValue());
                    }
                } else if (PyComplexCheckExactNodeGen.getUncached().execute(v)) {
                    if (version > 1) {
                        writeByte(TYPE_BINARY_COMPLEX | flag);
                        writeDouble(((PComplex) v).getReal());
                        writeDouble(((PComplex) v).getImag());
                    } else {
                        writeByte(TYPE_COMPLEX | flag);
                        writeDoubleString(((PComplex) v).getReal());
                        writeDoubleString(((PComplex) v).getImag());
                    }
                } else if (v instanceof String) {
                    writeByte(TYPE_UNICODE | flag);
                    writeString((String) v);
                } else if (PyUnicodeCheckExactNodeGen.getUncached().execute(v)) {
                    if (version >= 3 && IsInternedStringNodeGen.getUncached().execute((PString) v)) {
                        writeByte(TYPE_INTERNED | flag);
                    } else {
                        writeByte(TYPE_UNICODE | flag);
                    }
                    writeString(((PString) v).getValue());
                } else if (PyTupleCheckExactNodeGen.getUncached().execute(v)) {
                    Object[] items = GetObjectArrayNodeGen.getUncached().execute(v);
                    if (version >= 4 && items.length < 256) {
                        writeByte(TYPE_SMALL_TUPLE | flag);
                        writeByte(items.length);
                    } else {
                        writeByte(TYPE_TUPLE | flag);
                        writeSize(items.length);
                    }
                    for (Object item : items) {
                        writeObject(item);
                    }
                } else if (PyListCheckExactNodeGen.getUncached().execute(v)) {
                    writeByte(TYPE_LIST | flag);
                    Object[] items = GetInternalObjectArrayNodeGen.getUncached().execute(SequenceNodes.GetSequenceStorageNode.getUncached().execute(v));
                    writeSize(items.length);
                    for (Object item : items) {
                        writeObject(item);
                    }
                } else if (PyDictCheckExactNodeGen.getUncached().execute(v)) {
                    HashingStorage dictStorage = HashingCollectionNodes.GetHashingStorageNode.getUncached().execute(v);
                    // NULL terminated as in CPython
                    writeByte(TYPE_DICT | flag);
                    HashingStorageLibrary lib = HashingStorageLibrary.getFactory().getUncached(dictStorage);
                    for (DictEntry entry : lib.entries(dictStorage)) {
                        writeObject(entry.key);
                        writeObject(entry.value);
                    }
                    writeByte(TYPE_NULL);
                } else if (PySetCheckExactNodeGen.getUncached().execute(v) || PyFrozenSetCheckExactNodeGen.getUncached().execute(v)) {
                    if (PyFrozenSetCheckExactNodeGen.getUncached().execute(v)) {
                        writeByte(TYPE_FROZENSET | flag);
                    } else {
                        writeByte(TYPE_SET | flag);
                    }
                    HashingStorage dictStorage = HashingCollectionNodes.GetHashingStorageNode.getUncached().execute(v);
                    HashingStorageLibrary lib = HashingStorageLibrary.getFactory().getUncached(dictStorage);
                    int len = lib.length(dictStorage);
                    writeSize(len);
                    for (DictEntry entry : lib.entries(dictStorage)) {
                        writeObject(entry.key);
                    }
                } else if (v instanceof PCode) {
                    // we always store code objects in our format, CPython will not read our
                    // marshalled data when that contains code objects
                    PCode c = (PCode) v;
                    writeByte(TYPE_GRAALPYTHON_CODE | flag);
                    writeString(c.getFilename());
                    writeInt(c.getFlags());
                    writeBytes(c.getCodestring());
                    writeInt(c.getFirstLineNo());
                    byte[] lnotab = c.getLnotab();
                    if (lnotab == null) {
                        lnotab = PythonUtils.EMPTY_BYTE_ARRAY;
                    }
                    writeBytes(lnotab);
                } else if (PythonObjectLibrary.getUncached().isBuffer(v)) {
                    writeByte(TYPE_STRING | flag);
                    try {
                        writeBytes(PythonObjectLibrary.getUncached().getBufferBytes(v));
                    } catch (UnsupportedMessageException e) {
                        throw CompilerDirectives.shouldNotReachHere();
                    }
                } else {
                    writeByte(TYPE_UNKNOWN);
                    // don't store a reference to an unknown thing
                    return;
                }
            } catch (IOException e) {
                throw new MarshalError(PythonBuiltinClassType.ValueError, ErrorMessages.WAS_NOT_POSSIBLE_TO_MARSHAL_P, v);
            }
        }

        @FunctionalInterface
        static interface AddRefAndReturn {
            Object run(Object o);
        }

        private Object readObject() throws NumberFormatException, IOException {
            depth++;

            if (depth >= MAX_MARSHAL_STACK_DEPTH) {
                throw new MarshalError(PythonBuiltinClassType.ValueError, ErrorMessages.MAX_MARSHAL_STACK_DEPTH);
            }

            int code = readByte();
            int flag = code & FLAG_REF;
            int type = code & ~FLAG_REF;

            if (type == TYPE_REF) {
                depth--;
                return readReference();
            } else {
                Object retval = readObject(type, (o) -> { if (flag != 0) refList.add(o); return o; });
                depth--;
                return retval;
            }
        }

        private Object readObject(int type, AddRefAndReturn addRef) throws NumberFormatException, IOException {
            switch (type) {
                case TYPE_NULL:
                    return null;
                case TYPE_NONE:
                    return PNone.NONE;
                case TYPE_NOVALUE:
                    return PNone.NO_VALUE;
                case TYPE_STOPITER:
                    return PythonBuiltinClassType.StopIteration;
                case TYPE_ELLIPSIS:
                    return PythonBuiltinClassType.PEllipsis;
                case TYPE_FALSE:
                    return false;
                case TYPE_TRUE:
                    return true;
                case TYPE_INT:
                    return addRef.run(readInt());
                case TYPE_INT64:
                    return addRef.run(readLong());
                case TYPE_LONG:
                    return addRef.run(factory.createInt(readBigInteger()));
                case TYPE_FLOAT:
                    return addRef.run(readDoubleString());
                case TYPE_BINARY_FLOAT:
                    return addRef.run(readDouble());
                case TYPE_COMPLEX:
                    return addRef.run(factory.createComplex(readDoubleString(), readDoubleString()));
                case TYPE_BINARY_COMPLEX:
                    return addRef.run(factory.createComplex(readDouble(), readDouble()));
                case TYPE_STRING:
                    return addRef.run(factory.createBytes(readBytes()));
                case TYPE_ASCII_INTERNED:
                    return addRef.run(readAscii(readSize(), true));
                case TYPE_ASCII:
                    return addRef.run(readAscii(readSize(), false));
                case TYPE_SHORT_ASCII_INTERNED:
                    return addRef.run(readAscii(readByteSize(), true));
                case TYPE_SHORT_ASCII:
                    return addRef.run(readAscii(readByteSize(), false));
                case TYPE_INTERNED:
                    return addRef.run(StringNodes.InternStringNode.getUncached().execute(readString()));
                case TYPE_UNICODE:
                    return addRef.run(readString());
                case TYPE_SMALL_TUPLE:
                    int smallTupleSize = readByteSize();
                    Object[] smallTupleItems = new Object[smallTupleSize];
                    Object smallTuple = addRef.run(factory.createTuple(smallTupleItems));
                    readArray(smallTupleItems);
                    return smallTuple;
                case TYPE_TUPLE:
                    int tupleSize = readSize();
                    Object[] tupleItems = new Object[tupleSize];
                    Object tuple = addRef.run(factory.createTuple(tupleItems));
                    readArray(tupleItems);
                    return tuple;
                case TYPE_LIST:
                    int listSize = readSize();
                    Object[] listItems = new Object[listSize];
                    Object list = addRef.run(factory.createList(listItems));
                    readArray(listItems);
                    return list;
                case TYPE_DICT:
                    int dictSz = readSize();
                    HashingStorage store = PDict.createNewStorage(false, dictSz);
                    PDict dict = factory.createDict(store);
                    addRef.run(dict);
                    HashingStorageLibrary dictLib = HashingStorageLibrary.getFactory().getUncached(store);
                    for (int i = 0; i < dictSz; i++) {
                        Object key = readObject();
                        if (key == null) {
                            break;
                        }
                        Object value = readObject();
                        if (value != null) {
                            store = dictLib.setItem(store, key, value);
                        }
                    }
                    dict.setDictStorage(store);
                    return dict;
                case TYPE_SET:
                case TYPE_FROZENSET:
                    int setSz = readSize();
                    HashingStorage setStore = EconomicMapStorage.create(setSz);
                    PBaseSet set;
                    if (type == TYPE_FROZENSET) {
                        set = factory.createFrozenSet(setStore);
                    } else {
                        set = factory.createSet(setStore);
                    }
                    addRef.run(set);
                    HashingStorageLibrary setLib = HashingStorageLibrary.getFactory().getUncached(setStore);
                    for (int i = 0; i < setSz; i++) {
                        Object key = readObject();
                        setStore = setLib.setItem(setStore, key, PNone.NO_VALUE);
                    }
                    set.setDictStorage(setStore);
                    return set;
                case TYPE_GRAALPYTHON_CODE:
                    return readCode();
                case TYPE_CODE:
                    return readCPythonCode(addRef);
                default:
                    throw new MarshalError(PythonBuiltinClassType.ValueError, ErrorMessages.BAD_MARSHAL_DATA);
            }
        }

        private void writeString(String v) throws IOException {
            byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
            writeSize(bytes.length);
            out.write(bytes);
        }

        private String readString() throws IOException {
            byte[] bytes = readBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private void writeShortString(String v) throws IOException {
            byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
            assert bytes.length < 256;
            writeByte(bytes.length);
            out.write(bytes);
        }

        private String readShortString() throws IOException {
            int sz = readByteSize();
            byte[] bytes = in.readNBytes(sz);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private Object readAscii(long sz, boolean intern) throws IOException {
            byte[] bytes = in.readNBytes((int) sz);
            String value = new String(bytes, StandardCharsets.US_ASCII);
            if (intern) {
                return StringNodes.InternStringNode.getUncached().execute(value);
            } else {
                return value;
            }
        }

        private void readArray(Object[] items) throws NumberFormatException, IOException {
            for (int i = 0; i < items.length; i++) {
                Object item = readObject();
                if (item == null) {
                    throw new MarshalError(PythonBuiltinClassType.ValueError, ErrorMessages.BAD_MARSHAL_DATA);
                }
                items[i] = item;
            }
        }

        private PCode readCode() throws IOException {
            String fileName = readString().intern();
            int flags = readInt();

            int codeLen = readSize();
            byte[] codeString = new byte[codeLen + Long.BYTES];
            in.read(codeString, 0, codeLen);
            // get a new ID every time we deserialize the same filename in the same context
            ByteBuffer.wrap(codeString).putLong(codeLen, PythonLanguage.getContext().getDeserializationId(fileName));
            int firstLineNo = readInt();
            byte[] lnoTab = readBytes();
            return CreateCodeNode.createCode(PythonLanguage.getCurrent(), PythonLanguage.getContext(), flags, codeString, fileName, firstLineNo, lnoTab);
        }

        @SuppressWarnings("unused")
        private Object readCPythonCode(AddRefAndReturn addRef) throws IOException {
            Object[] items = new Object[16];
            Object result = addRef.run(factory.createTuple(items));

            int argcount = (int) (items[0] = readInt());
            int posonlyargcount = (int) (items[1] = readInt());
            int kwonlyargcount = (int) (items[2] = readInt());
            int nlocals = (int) (items[3] = readInt());
            int stacksize = (int) (items[4] = readInt());
            int flags = (int) (items[5] = readInt());
            Object code = items[6] = readObject();
            Object consts = items[7] = readObject();
            Object names = items[8] = readObject();
            Object varnames = items[9] = readObject();
            Object freevars = items[10] = readObject();
            Object cellvars = items[11] = readObject();
            Object filename = items[12] = readObject();
            Object name = items[13] = readObject();
            int firstlineno = (int) (items[14] = readInt());
            Object lnotab = items[15] = readObject();
            return result;
        }
    }
}
