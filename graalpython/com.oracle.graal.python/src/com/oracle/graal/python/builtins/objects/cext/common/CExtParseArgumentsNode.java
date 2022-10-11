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
package com.oracle.graal.python.builtins.objects.cext.common;

import static com.oracle.graal.python.builtins.PythonBuiltinClassType.OverflowError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.SystemError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.TypeError;
import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_CONVERTBUFFER;
import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_GET_BUFFER_R;
import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_GET_BUFFER_RW;
import static com.oracle.graal.python.nodes.StringLiterals.T_EMPTY_STRING;
import static com.oracle.graal.python.nodes.truffle.TruffleStringMigrationHelpers.isJavaString;
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;
import static com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.bytes.PByteArray;
import com.oracle.graal.python.builtins.objects.bytes.PBytes;
import com.oracle.graal.python.builtins.objects.cext.PythonNativeVoidPtr;
import com.oracle.graal.python.builtins.objects.cext.capi.CApiContext.LLVMType;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.AsCharPointerNode;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.AsNativeComplexNode;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.GetLLVMType;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.PRaiseNativeNode;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.TransformExceptionToNativeNode;
import com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.AsNativeDoubleNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.AsNativePrimitiveNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.PCallCExtFunction;
import com.oracle.graal.python.builtins.objects.cext.common.CExtParseArgumentsNodeFactory.ConvertArgNodeGen;
import com.oracle.graal.python.builtins.objects.common.HashingCollectionNodes;
import com.oracle.graal.python.builtins.objects.common.HashingStorageNodes.HashingStorageGetItem;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes.GetSequenceStorageNode;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.str.PString;
import com.oracle.graal.python.builtins.objects.str.StringNodes.StringLenNode;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.lib.PyObjectIsTrueNode;
import com.oracle.graal.python.lib.PyObjectSizeNode;
import com.oracle.graal.python.lib.PySequenceCheckNode;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.builtins.TupleNodes;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.object.GetClassNode;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonContext.GetThreadStateNode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.runtime.sequence.storage.SequenceStorage;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism.Megamorphic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;

public abstract class CExtParseArgumentsNode {
    static final char FORMAT_LOWER_S = 's';
    static final char FORMAT_UPPER_S = 'S';
    static final char FORMAT_LOWER_Z = 'z';
    static final char FORMAT_UPPER_Z = 'Z';
    static final char FORMAT_LOWER_Y = 'y';
    static final char FORMAT_UPPER_Y = 'Y';
    static final char FORMAT_LOWER_U = 'u';
    static final char FORMAT_UPPER_U = 'U';
    static final char FORMAT_LOWER_E = 'e';
    static final char FORMAT_LOWER_B = 'b';
    static final char FORMAT_UPPER_B = 'B';
    static final char FORMAT_LOWER_H = 'h';
    static final char FORMAT_UPPER_H = 'H';
    static final char FORMAT_LOWER_I = 'i';
    static final char FORMAT_UPPER_I = 'I';
    static final char FORMAT_LOWER_L = 'l';
    static final char FORMAT_UPPER_L = 'L';
    static final char FORMAT_LOWER_K = 'k';
    static final char FORMAT_UPPER_K = 'K';
    static final char FORMAT_LOWER_N = 'n';
    static final char FORMAT_LOWER_C = 'c';
    static final char FORMAT_UPPER_C = 'C';
    static final char FORMAT_LOWER_F = 'f';
    static final char FORMAT_LOWER_D = 'd';
    static final char FORMAT_UPPER_D = 'D';
    static final char FORMAT_UPPER_O = 'O';
    static final char FORMAT_LOWER_W = 'w';
    static final char FORMAT_LOWER_P = 'p';
    static final char FORMAT_PAR_OPEN = '(';
    static final char FORMAT_PAR_CLOSE = ')';

    @GenerateUncached
    @ImportStatic({PGuards.class, PythonUtils.class})
    public abstract static class ParseTupleAndKeywordsNode extends Node {

        public abstract int execute(TruffleString funName, Object argv, Object kwds, Object format, Object kwdnames, Object varargs, CExtContext nativeContext);

        @Specialization(guards = {"isDictOrNull(kwds)", "eqNode.execute(cachedFormat, format, TS_ENCODING)", "lengthNode.execute(cachedFormat, TS_ENCODING) <= 8"}, limit = "5")
        int doSpecial(TruffleString funName, PTuple argv, Object kwds, @SuppressWarnings("unused") TruffleString format, Object kwdnames, Object varargs, CExtContext nativeConext,
                        @Cached(value = "format", allowUncached = true) @SuppressWarnings("unused") TruffleString cachedFormat,
                        @Cached TruffleString.CodePointLengthNode lengthNode,
                        @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached("createConvertArgNodes(cachedFormat, lengthNode)") ConvertArgNode[] convertArgNodes,
                        @Cached HashingCollectionNodes.LenNode kwdsLenNode,
                        @Cached SequenceStorageNodes.LenNode argvLenNode,
                        @Cached PRaiseNativeNode raiseNode,
                        @Cached TruffleString.EqualNode eqNode) {
            try {
                PDict kwdsDict = null;
                if (kwds != null && kwdsLenNode.execute((PDict) kwds) != 0) {
                    kwdsDict = (PDict) kwds;
                }
                doParsingExploded(funName, argv, kwdsDict, format, kwdnames, varargs, nativeConext, convertArgNodes, argvLenNode, lengthNode, codepointAtIndexNode, raiseNode);
                return 1;
            } catch (InteropException | ParseArgumentsException e) {
                return 0;
            }
        }

        @Specialization(guards = "isDictOrNull(kwds)", replaces = "doSpecial")
        @Megamorphic
        int doGeneric(TruffleString funName, PTuple argv, Object kwds, TruffleString format, Object kwdnames, Object varargs, CExtContext nativeContext,
                        @Cached ConvertArgNode convertArgNode,
                        @Cached HashingCollectionNodes.LenNode kwdsLenNode,
                        @Cached TruffleString.CodePointLengthNode lengthNode,
                        @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached SequenceStorageNodes.LenNode argvLenNode,
                        @Cached PRaiseNativeNode raiseNode) {
            try {
                PDict kwdsDict = null;
                if (kwds != null && kwdsLenNode.execute((PDict) kwds) != 0) {
                    kwdsDict = (PDict) kwds;
                }
                ParserState state = new ParserState(funName, new PositionalArgStack(argv, null), nativeContext);
                int length = lengthNode.execute(format, TS_ENCODING);
                for (int i = 0; i < length; i++) {
                    state = convertArg(state, kwdsDict, format, i, length, kwdnames, varargs, convertArgNode, codepointAtIndexNode, raiseNode);
                }
                checkExcessArgs(argv, argvLenNode, state, raiseNode);
                return 1;
            } catch (InteropException | ParseArgumentsException e) {
                return 0;
            }
        }

        private static void checkExcessArgs(PTuple argv, SequenceStorageNodes.LenNode argvLenNode, ParserState state, PRaiseNativeNode raiseNode) {
            int argvLen = argvLenNode.execute(argv.getSequenceStorage());
            if (argvLen > state.v.argnum) {
                raiseNode.raiseIntWithoutFrame(0, TypeError, ErrorMessages.EXPECTED_AT_MOST_D_ARGS_GOT_D, state.v.argnum, argvLen);
                throw ParseArgumentsException.raise();
            }
        }

        @Fallback
        @SuppressWarnings("unused")
        int error(TruffleString funName, Object argv, Object kwds, Object format, Object kwdnames, Object varargs, CExtContext nativeContext,
                        @Cached PRaiseNativeNode raiseNode) {
            return raiseNode.raiseIntWithoutFrame(0, SystemError, ErrorMessages.BAD_ARG_TO_INTERNAL_FUNC);
        }

        @ExplodeLoop(kind = LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
        private static void doParsingExploded(TruffleString funName, PTuple argv, Object kwds, TruffleString format, Object kwdnames, Object varargs, CExtContext nativeContext,
                        ConvertArgNode[] convertArgNodes, SequenceStorageNodes.LenNode argvLenNode, TruffleString.CodePointLengthNode lengthNode,
                        TruffleString.CodePointAtIndexNode codepointAtIndexNode, PRaiseNativeNode raiseNode)
                        throws InteropException, ParseArgumentsException {
            int length = lengthNode.execute(format, TS_ENCODING);
            CompilerAsserts.partialEvaluationConstant(length);
            ParserState state = new ParserState(funName, new PositionalArgStack(argv, null), nativeContext);
            for (int i = 0; i < length; i++) {
                state = convertArg(state, kwds, format, i, length, kwdnames, varargs, convertArgNodes[i], codepointAtIndexNode, raiseNode);
            }
            checkExcessArgs(argv, argvLenNode, state, raiseNode);
        }

        private static ParserState convertArg(ParserState state, Object kwds, TruffleString format, int formatIdx, int formatLength, Object kwdnames, Object varargs, ConvertArgNode convertArgNode,
                        TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {
            if (state.skip) {
                return state.skipped();
            }
            int c = codepointAtIndexNode.execute(format, formatIdx, TS_ENCODING);
            switch (c) {
                case FORMAT_LOWER_S:
                case FORMAT_LOWER_Z:
                case FORMAT_LOWER_Y:
                case FORMAT_UPPER_S:
                case FORMAT_UPPER_Y:
                case FORMAT_LOWER_U:
                case FORMAT_UPPER_Z:
                case FORMAT_UPPER_U:
                case FORMAT_LOWER_E:
                case FORMAT_LOWER_B:
                case FORMAT_UPPER_B:
                case FORMAT_LOWER_H:
                case FORMAT_UPPER_H:
                case FORMAT_LOWER_I:
                case FORMAT_UPPER_I:
                case FORMAT_LOWER_L:
                case FORMAT_LOWER_K:
                case FORMAT_UPPER_L:
                case FORMAT_UPPER_K:
                case FORMAT_LOWER_N:
                case FORMAT_LOWER_C:
                case FORMAT_UPPER_C:
                case FORMAT_LOWER_F:
                case FORMAT_LOWER_D:
                case FORMAT_UPPER_D:
                case FORMAT_UPPER_O:
                case FORMAT_LOWER_W:
                case FORMAT_LOWER_P:
                case FORMAT_PAR_OPEN:
                case FORMAT_PAR_CLOSE:
                    return convertArgNode.execute(state, kwds, (char) c, format, formatIdx, formatLength, kwdnames, varargs);
                case '|':
                    if (state.restOptional) {
                        raiseNode.raiseIntWithoutFrame(0, SystemError, ErrorMessages.INVALID_FORMAT_STRING_PIPE_SPECIFIED_TWICE, c);
                        throw ParseArgumentsException.raise();
                    }
                    return state.restOptional();
                case '$':
                    if (state.restKeywordsOnly) {
                        raiseNode.raiseIntWithoutFrame(0, SystemError, ErrorMessages.INVALID_FORMAT_STRING_PIPE_SPECIFIED_TWICE, c);
                        throw ParseArgumentsException.raise();
                    }
                    return state.restKeywordsOnly();
                case '!':
                case '&':
                case '*':
                case '#':
                    // always skip '!', '&', '*', and '#' because these will be handled in the
                    // look-ahead of the major specifiers like 'O' or 's'
                    return state;
                case ':':
                    // We extract and remove the function name already in the calling builtin. So
                    // this char may not occur here.
                    assert false : "got ':' but this should be trimmed from the format string";
                    return state;
                case ';':
                    // We extract and remove the function name already in the calling builtin. So
                    // this char may not occur here.
                    assert false : "got ';' but this should be trimmed from the format string";
                    return state;
                default:
                    raiseNode.raiseIntWithoutFrame(0, TypeError, ErrorMessages.UNRECOGNIZED_FORMAT_CHAR, c);
                    throw ParseArgumentsException.raise();
            }
        }

        static ConvertArgNode[] createConvertArgNodes(TruffleString format, TruffleString.CodePointLengthNode lengthNode) {
            ConvertArgNode[] convertArgNodes = new ConvertArgNode[lengthNode.execute(format, TS_ENCODING)];
            for (int i = 0; i < convertArgNodes.length; i++) {
                convertArgNodes[i] = ConvertArgNodeGen.create();
            }
            return convertArgNodes;
        }

        static boolean isDictOrNull(Object object) {
            return object == null || object instanceof PDict;
        }
    }

    /**
     * The parser state that captures the current output variable index, if arguments are optional,
     * if arguments will be taken from the keywords dictionary only, and the current arguments
     * tuple.<br/>
     * The state is implemented in an immutable way since every specifier should get his unique
     * state.
     */
    @ValueType
    static final class ParserState {
        private final TruffleString funName;
        private final int outIndex;
        private final boolean skip; // skip the next format char
        private final boolean restOptional;
        private final boolean restKeywordsOnly;
        private final PositionalArgStack v;
        private final CExtContext nativeContext;

        ParserState(TruffleString funName, PositionalArgStack v, CExtContext nativeContext) {
            this(funName, 0, false, false, false, v, nativeContext);
        }

        private ParserState(TruffleString funName, int outIndex, boolean skip, boolean restOptional, boolean restKeywordsOnly, PositionalArgStack v, CExtContext nativeContext) {
            this.funName = funName;
            this.outIndex = outIndex;
            this.skip = skip;
            this.restOptional = restOptional;
            this.restKeywordsOnly = restKeywordsOnly;
            this.v = v;
            this.nativeContext = nativeContext;
        }

        ParserState incrementOutIndex() {
            return new ParserState(funName, outIndex + 1, false, restOptional, restKeywordsOnly, v, nativeContext);
        }

        ParserState restOptional() {
            return new ParserState(funName, outIndex, false, true, restKeywordsOnly, v, nativeContext);
        }

        ParserState restKeywordsOnly() {
            return new ParserState(funName, outIndex, false, restOptional, true, v, nativeContext);
        }

        ParserState open(PositionalArgStack nestedArgs) {
            return new ParserState(funName, outIndex, false, restOptional, false, nestedArgs, nativeContext);
        }

        ParserState skip() {
            return new ParserState(funName, outIndex, true, restOptional, restKeywordsOnly, v, nativeContext);
        }

        ParserState skipped() {
            return new ParserState(funName, outIndex, false, restOptional, restKeywordsOnly, v, nativeContext);
        }

        ParserState close() {
            return new ParserState(funName, outIndex, false, restOptional, false, v.prev, nativeContext);
        }

    }

    @ValueType
    static final class PositionalArgStack {
        private final PTuple argv;
        private int argnum;
        private final PositionalArgStack prev;

        PositionalArgStack(PTuple argv, PositionalArgStack prev) {
            this.argv = argv;
            this.prev = prev;
        }
    }

    /**
     * This node does the conversion of a single specifier and is comparable to CPython's
     * {@code convertsimple} function. Each specifier is implemented in a separate specialization
     * since the different specifiers need a very different set of helper nodes.
     */
    @GenerateUncached
    @ImportStatic(CExtParseArgumentsNode.class)
    abstract static class ConvertArgNode extends Node {
        public abstract ParserState execute(ParserState state, Object kwds, char c, TruffleString format, int formatIdx, int formatLength, Object kwdnames, Object varargs)
                        throws InteropException, ParseArgumentsException;

        static boolean isCStringSpecifier(char c) {
            return c == FORMAT_LOWER_S || c == FORMAT_LOWER_Z;
        }

        @Specialization(guards = "c == FORMAT_LOWER_Y")
        static ParserState doBufferR(ParserState stateIn, Object kwds, @SuppressWarnings("unused") char c, TruffleString format, int formatIdx, int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Shared("atIndex") @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached GetVaArgsNode getVaArgNode,
                        @Cached PCallCExtFunction callGetBufferRwNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Cached(value = "createTN(stateIn)", uncached = "getUncachedTN(stateIn)") CExtToNativeNode argToSulongNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {
            ParserState state = stateIn;
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                if (isLookahead(format, formatIdx, formatLength, '*', codepointAtIndexNode)) {
                    /* formatIdx++; */
                    // 'y*'; output to 'Py_buffer*'
                    Object pybufferPtr = getVaArgNode.getPyObjectPtr(varargs, state.outIndex);
                    getbuffer(state.nativeContext, callGetBufferRwNode, raiseNode, arg, argToSulongNode, pybufferPtr, true);
                } else {
                    Object voidPtr = getVaArgNode.getVoidPtr(varargs, state.outIndex);
                    Object count = convertbuffer(state.nativeContext, callGetBufferRwNode, raiseNode, arg, argToSulongNode, voidPtr);
                    if (isLookahead(format, formatIdx, formatLength, '#', codepointAtIndexNode)) {
                        /* formatIdx++; */
                        // 'y#'
                        state = state.incrementOutIndex();
                        writeOutVarNode.writeInt64(varargs, state.outIndex, count);
                    }
                }
            } else if (isLookahead(format, formatIdx, formatLength, '#', codepointAtIndexNode)) {
                state = state.incrementOutIndex();
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "isCStringSpecifier(c)")
        static ParserState doCString(ParserState stateIn, Object kwds, @SuppressWarnings("unused") char c, TruffleString format, int formatIdx, int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Shared("atIndex") @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached GetVaArgsNode getVaArgNode,
                        @Cached AsCharPointerNode asCharPointerNode,
                        @Cached StringLenNode stringLenNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Cached(value = "createTN(stateIn)", uncached = "getUncachedTN(stateIn)") CExtToNativeNode toNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {
            ParserState state = stateIn;
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            boolean z = c == FORMAT_LOWER_Z;
            if (isLookahead(format, formatIdx, formatLength, '*', codepointAtIndexNode)) {
                /* formatIdx++; */
                // 's*' or 'z*'
                if (!skipOptionalArg(arg, state.restOptional)) {
                    getVaArgNode.getPyObjectPtr(varargs, state.outIndex);
                    // TODO(fa) create Py_buffer
                }
            } else if (isLookahead(format, formatIdx, formatLength, '#', codepointAtIndexNode)) {
                /* formatIdx++; */
                // 's#' or 'z#'
                if (!skipOptionalArg(arg, state.restOptional)) {
                    if (z && PGuards.isPNone(arg)) {
                        writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(PythonContext.get(toNativeNode).getNativeNull()));
                        state = state.incrementOutIndex();
                        writeOutVarNode.writeInt32(varargs, state.outIndex, 0);
                    } else if (PGuards.isString(arg)) {
                        // TODO(fa) we could use CStringWrapper to do the copying lazily
                        writeOutVarNode.writePyObject(varargs, state.outIndex, asCharPointerNode.execute(arg));
                        state = state.incrementOutIndex();
                        writeOutVarNode.writeInt64(varargs, state.outIndex, (long) stringLenNode.execute(arg));
                    } else {
                        throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_S_GOT_P, z ? "str or None" : "str", arg);
                    }
                }
            } else {
                // 's' or 'z'
                if (!skipOptionalArg(arg, state.restOptional)) {
                    if (z && PGuards.isPNone(arg)) {
                        writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(PythonContext.get(toNativeNode).getNativeNull()));
                    } else if (PGuards.isString(arg)) {
                        // TODO(fa) we could use CStringWrapper to do the copying lazily
                        writeOutVarNode.writePyObject(varargs, state.outIndex, asCharPointerNode.execute(arg));
                    } else {
                        throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_S_GOT_P, z ? "str or None" : "str", arg);
                    }
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_S")
        static ParserState doBytes(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached GetClassNode getClassNode,
                        @Cached IsBuiltinClassProfile isBytesProfile,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Cached(value = "createTN(state)", uncached = "getUncachedTN(state)") CExtToNativeNode toNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                if (isBytesProfile.profileClass(getClassNode.execute(arg), PythonBuiltinClassType.PBytes)) {
                    writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(arg));
                } else {
                    throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_S_NOT_P, "bytes", arg);
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_Y")
        static ParserState doByteArray(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached GetClassNode getClassNode,
                        @Cached IsBuiltinClassProfile isBytesProfile,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Cached(value = "createTN(state)", uncached = "getUncachedTN(state)") CExtToNativeNode toNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                if (isBytesProfile.profileClass(getClassNode.execute(arg), PythonBuiltinClassType.PByteArray)) {
                    writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(arg));
                } else {
                    throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_S_NOT_P, "bytearray", arg);
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_U")
        static ParserState doUnicode(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached GetClassNode getClassNode,
                        @Cached IsBuiltinClassProfile isBytesProfile,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Cached(value = "createTN(state)", uncached = "getUncachedTN(state)") CExtToNativeNode toNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                if (isBytesProfile.profileClass(getClassNode.execute(arg), PythonBuiltinClassType.PString)) {
                    writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(arg));
                } else {
                    throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_S_NOT_P, "str", arg);
                }
            }
            return state.incrementOutIndex();
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "c == FORMAT_LOWER_E")
        static ParserState doEncodedString(ParserState stateIn, Object kwds, @SuppressWarnings("unused") char c, TruffleString format, @SuppressWarnings("unused") int formatIdx, int formatLength,
                        Object kwdnames, @SuppressWarnings("unused") Object varargs,
                        @Cached AsCharPointerNode asCharPointerNode,
                        @Cached GetVaArgsNode getVaArgNode,
                        @Shared("atIndex") @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached(value = "createTJ(stateIn)", uncached = "getUncachedTJ(stateIn)") CExtToJavaNode argToJavaNode,
                        @Cached PyObjectSizeNode sizeNode,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {
            ParserState state = stateIn;
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                Object encoding = getVaArgNode.getCharPtr(varargs, state.outIndex);
                state = state.incrementOutIndex();
                final boolean recodeStrings;
                if (isLookahead(format, formatIdx, formatLength, 's', codepointAtIndexNode)) {
                    recodeStrings = true;
                } else if (isLookahead(format, formatIdx, formatLength, 't', codepointAtIndexNode)) {
                    recodeStrings = false;
                } else {
                    throw raise(raiseNode, TypeError, ErrorMessages.ESTAR_FORMAT_SPECIFIERS_NOT_ALLOWED, arg);
                }
                // XXX: TODO: actual support for the en-/re-coding of objects, proper error handling
                // TODO(tfel) we could use CStringWrapper to do the copying lazily
                writeOutVarNode.writePyObject(varargs, state.outIndex, asCharPointerNode.execute(arg));
                if (isLookahead(format, formatIdx + 1, formatLength, '#', codepointAtIndexNode)) {
                    final int size = sizeNode.execute(null, argToJavaNode.execute(state.nativeContext, arg));
                    state = state.incrementOutIndex();
                    writeOutVarNode.writeInt64(varargs, state.outIndex, size);
                }
            }
            return state.incrementOutIndex().skip(); // e is always followed by 's' or 't', which me
                                                     // must skip
        }

        @Specialization(guards = "c == FORMAT_LOWER_B")
        static ParserState doUnsignedByte(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            // C type: unsigned char
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    long ival = asNativePrimitiveNode.toInt64(arg, true);
                    if (ival < 0) {
                        throw raise(raiseNode, OverflowError, ErrorMessages.UNSIGNED_BYTE_INT_LESS_THAN_MIN);
                    } else if (ival > Byte.MAX_VALUE * 2 + 1) {
                        // TODO(fa) MAX_VALUE should be retrieved from Sulong
                        throw raise(raiseNode, OverflowError, ErrorMessages.UNSIGNED_BYTE_INT_GREATER_THAN_MAX);
                    }
                    writeOutVarNode.writeUInt8(varargs, state.outIndex, ival);
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_B")
        static ParserState doUnsignedByteBitfield(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) throws InteropException, ParseArgumentsException {

            // C type: unsigned char
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    writeOutVarNode.writeUInt8(varargs, state.outIndex, asNativePrimitiveNode.toInt64(arg, false));
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_LOWER_H")
        static ParserState doShortInt(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            // C type: signed short int
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    long ival = asNativePrimitiveNode.toInt64(arg, true);
                    // TODO(fa) MIN_VALUE and MAX_VALUE should be retrieved from Sulong
                    if (ival < Short.MIN_VALUE) {
                        throw raise(raiseNode, OverflowError, ErrorMessages.SIGNED_SHORT_INT_LESS_THAN_MIN);
                    } else if (ival > Short.MAX_VALUE) {
                        throw raise(raiseNode, OverflowError, ErrorMessages.SIGNED_SHORT_INT_GREATER_THAN_MAX);
                    }
                    writeOutVarNode.writeInt16(varargs, state.outIndex, ival);
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_H")
        static ParserState doUnsignedShortInt(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) throws InteropException, ParseArgumentsException {

            // C type: short int sized bitfield
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    writeOutVarNode.writeInt16(varargs, state.outIndex, asNativePrimitiveNode.toInt64(arg, false));
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_LOWER_I")
        static ParserState doSignedInt(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            // C type: signed int
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    long ival = asNativePrimitiveNode.toInt64(arg, true);
                    // TODO(fa) MIN_VALUE and MAX_VALUE should be retrieved from Sulong
                    if (ival < Integer.MIN_VALUE) {
                        throw raise(raiseNode, OverflowError, ErrorMessages.SIGNED_INT_LESS_THAN_MIN);
                    } else if (ival > Integer.MAX_VALUE) {
                        throw raise(raiseNode, OverflowError, ErrorMessages.SIGNED_INT_GREATER_THAN_MAX);
                    }
                    writeOutVarNode.writeInt32(varargs, state.outIndex, ival);
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_I")
        static ParserState doUnsignedInt(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) throws InteropException, ParseArgumentsException {

            // C type: int sized bitfield
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    writeOutVarNode.writeUInt32(varargs, state.outIndex, asNativePrimitiveNode.toInt64(arg, false));
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        static boolean isLongSpecifier(char c) {
            return c == FORMAT_LOWER_L || c == FORMAT_UPPER_L;
        }

        @Specialization(guards = "isLongSpecifier(c)")
        static ParserState doLong(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) throws InteropException, ParseArgumentsException {

            // C type: signed long and signed long long
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    writeOutVarNode.writeInt64(varargs, state.outIndex, asNativePrimitiveNode.toInt64(arg, true));
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        static boolean isLongBitfieldSpecifier(char c) {
            return c == FORMAT_LOWER_K || c == FORMAT_UPPER_K;
        }

        @Specialization(guards = "isLongBitfieldSpecifier(c)")
        static ParserState doUnsignedLong(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) throws InteropException, ParseArgumentsException {

            // C type: unsigned long and unsigned long long
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                try {
                    writeOutVarNode.writeUInt64(varargs, state.outIndex, asNativePrimitiveNode.toUInt64(arg, false));
                } catch (PException e) {
                    CompilerDirectives.transferToInterpreter();
                    transformExceptionToNativeNode.execute(null, e);
                    throw ParseArgumentsException.raise();
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_LOWER_N")
        static ParserState doPySsizeT(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativePrimitiveNode asNativePrimitiveNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("excToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) throws InteropException, ParseArgumentsException {

            // C type: PySSize_t
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                if (arg instanceof PythonNativeVoidPtr) {
                    writeOutVarNode.writePyObject(varargs, state.outIndex, ((PythonNativeVoidPtr) arg).getPointerObject());
                } else {
                    try {
                        // TODO(fa): AsNativePrimitiveNode coerces using '__int__', but here we must
                        // use '__index__'
                        writeOutVarNode.writeInt64(varargs, state.outIndex, asNativePrimitiveNode.toInt64(arg, true));
                    } catch (PException e) {
                        transformExceptionToNativeNode.execute(null, e);
                        throw ParseArgumentsException.raise();
                    }
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_LOWER_C")
        static ParserState doByteFromBytesOrBytearray(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength, Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached SequenceStorageNodes.LenNode lenNode,
                        @Cached SequenceStorageNodes.GetItemDynamicNode getItemNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                SequenceStorage s = null;
                if (arg instanceof PBytes) {
                    s = ((PBytes) arg).getSequenceStorage();
                } else if (arg instanceof PByteArray) {
                    s = ((PByteArray) arg).getSequenceStorage();
                }

                if (s != null && lenNode.execute(s) == 1) {
                    writeOutVarNode.writeInt8(varargs, state.outIndex, getItemNode.execute(s, 0));
                } else {
                    throw raise(raiseNode, TypeError, ErrorMessages.MUST_BE_BYTE_STRING_LEGTH1_NOT_P, arg);
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_C")
        static ParserState doIntFromString(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatIdx, @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached StringLenNode stringLenNode,
                        @Cached TruffleString.ReadCharUTF16Node readCharNode,
                        @Cached TruffleString.SwitchEncodingNode switchEncodingNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                // TODO(fa): There could be native subclasses (i.e. the Java type would not be
                // 'String' or 'PString') but we do currently not support this.
                if (!(PGuards.isString(arg) && stringLenNode.execute(arg) == 1)) {
                    throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_UNICODE_CHAR_NOT_P, arg);
                }
                // TODO(fa) use the sequence lib to get the character once available
                char singleChar;
                if (isJavaString(arg)) {
                    singleChar = ((String) arg).charAt(0);
                } else if (arg instanceof TruffleString) {
                    singleChar = readCharNode.execute(switchEncodingNode.execute((TruffleString) arg, TruffleString.Encoding.UTF_16), 0);
                } else if (arg instanceof PString) {
                    singleChar = charFromPString((PString) arg, readCharNode, switchEncodingNode);
                } else {
                    throw raise(raiseNode, SystemError, ErrorMessages.UNSUPPORTED_STR_TYPE, arg.getClass());
                }
                writeOutVarNode.writeInt32(varargs, state.outIndex, (int) singleChar);
            }
            return state.incrementOutIndex();
        }

        private static char charFromPString(PString arg, TruffleString.ReadCharUTF16Node readCharNode, TruffleString.SwitchEncodingNode switchEncodingNode) {
            if (arg.isMaterialized()) {
                return readCharNode.execute(switchEncodingNode.execute(arg.getMaterialized(), TruffleString.Encoding.UTF_16), 0);
            }
            if (arg.isNativeCharSequence()) {
                return charFromNativePString(arg);
            }
            throw shouldNotReachHere("PString is neither materialized nor native");
        }

        @TruffleBoundary
        private static char charFromNativePString(PString arg) {
            assert arg.isNativeCharSequence();
            return arg.getNativeCharSequence().charAt(0);
        }

        @Specialization(guards = "c == FORMAT_LOWER_F")
        static ParserState doFloat(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativeDoubleNode asDoubleNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                writeOutVarNode.writeFloat(varargs, state.outIndex, (float) asDoubleNode.executeDouble(arg));
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_LOWER_D")
        static ParserState doDouble(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativeDoubleNode asDoubleNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                writeOutVarNode.writeDouble(varargs, state.outIndex, asDoubleNode.execute(arg));
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_D")
        static ParserState doComplex(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached AsNativeComplexNode asComplexNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                writeOutVarNode.writeComplex(varargs, state.outIndex, asComplexNode.execute(arg));
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_UPPER_O")
        static ParserState doObject(ParserState stateIn, Object kwds, @SuppressWarnings("unused") char c, TruffleString format, @SuppressWarnings("unused") int formatIdx, int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Cached GetVaArgsNode getVaArgNode,
                        @Shared("atIndex") @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached ExecuteConverterNode executeConverterNode,
                        @Cached GetClassNode getClassNode,
                        @Cached IsSubtypeNode isSubtypeNode,
                        @CachedLibrary(limit = "2") InteropLibrary lib,
                        @Cached(value = "createTJ(stateIn)", uncached = "getUncachedTJ(stateIn)") CExtToJavaNode typeToJavaNode,
                        @Cached(value = "createTN(stateIn)", uncached = "getUncachedTN(stateIn)") CExtToNativeNode toNativeNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {
            ParserState state = stateIn;
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (isLookahead(format, formatIdx, formatLength, '!', codepointAtIndexNode)) {
                /* formatIdx++; */
                if (!skipOptionalArg(arg, state.restOptional)) {
                    Object typeObject = typeToJavaNode.execute(getVaArgNode.getPyObjectPtr(varargs, state.outIndex));
                    state = state.incrementOutIndex();
                    assert PGuards.isClass(typeObject, lib);
                    if (!isSubtypeNode.execute(getClassNode.execute(arg), typeObject)) {
                        raiseNode.raiseIntWithoutFrame(0, TypeError, ErrorMessages.EXPECTED_OBJ_TYPE_S_GOT_P, typeObject, arg);
                        throw ParseArgumentsException.raise();
                    }
                    writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(arg));
                }
            } else if (isLookahead(format, formatIdx, formatLength, '&', codepointAtIndexNode)) {
                /* formatIdx++; */
                Object converter = getVaArgNode.getPyObjectPtr(varargs, state.outIndex);
                state = state.incrementOutIndex();
                if (!skipOptionalArg(arg, state.restOptional)) {
                    Object output = getVaArgNode.getPyObjectPtr(varargs, state.outIndex);
                    executeConverterNode.execute(state.nativeContext.getSupplier(), state.outIndex, converter, arg, output);
                }
            } else {
                if (!skipOptionalArg(arg, state.restOptional)) {
                    writeOutVarNode.writePyObject(varargs, state.outIndex, toNativeNode.execute(arg));
                }
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_LOWER_W")
        static ParserState doBufferRW(ParserState state, Object kwds, @SuppressWarnings("unused") char c, TruffleString format, @SuppressWarnings("unused") int formatIdx, int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Shared("atIndex") @Cached TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @Cached GetVaArgsNode getVaArgNode,
                        @Cached PCallCExtFunction callGetBufferRwNode,
                        @Cached(value = "createTN(state)", uncached = "getUncachedTN(state)") CExtToNativeNode toNativeNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {
            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!isLookahead(format, formatIdx, formatLength, '*', codepointAtIndexNode)) {
                throw raise(raiseNode, TypeError, ErrorMessages.INVALID_USE_OF_W_FORMAT_CHAR);

            }
            if (!skipOptionalArg(arg, state.restOptional)) {
                Object pybufferPtr = getVaArgNode.getPyObjectPtr(varargs, state.outIndex);
                getbuffer(state.nativeContext, callGetBufferRwNode, raiseNode, arg, toNativeNode, pybufferPtr, false);
            }
            return state.incrementOutIndex();
        }

        private static void getbuffer(CExtContext nativeContext, PCallCExtFunction callGetBufferRwNode, PRaiseNativeNode raiseNode, Object arg, CExtToNativeNode toSulongNode, Object pybufferPtr,
                        boolean readOnly)
                        throws ParseArgumentsException {
            NativeCAPISymbol funSymbol = readOnly ? FUN_GET_BUFFER_R : FUN_GET_BUFFER_RW;
            Object rc = callGetBufferRwNode.call(nativeContext, funSymbol, toSulongNode.execute(arg), pybufferPtr);
            if (!(rc instanceof Number)) {
                throw raise(raiseNode, SystemError, ErrorMessages.RETURNED_UNEXPECTE_RET_CODE_EXPECTED_INT_BUT_WAS_S, funSymbol, rc.getClass());
            }
            int i = intValue((Number) rc);
            if (i == -1) {
                throw converterr(raiseNode, readOnly ? ErrorMessages.READ_ONLY_BYTELIKE_OBJ : ErrorMessages.READ_WRITE_BYTELIKE_OBJ, arg);
            } else if (i == -2) {
                throw converterr(raiseNode, ErrorMessages.CONTIGUOUS_BUFFER, arg);
            }
        }

        private static ParseArgumentsException converterr(PRaiseNativeNode raiseNode, TruffleString msg, Object arg) {
            if (arg == PNone.NONE) {
                throw raise(raiseNode, TypeError, ErrorMessages.MUST_BE_S_NOT_NONE, msg);
            }
            throw raise(raiseNode, TypeError, ErrorMessages.MUST_BE_S_NOT_P, msg, arg);
        }

        private static int convertbuffer(CExtContext nativeContext, PCallCExtFunction callConvertbuffer, PRaiseNativeNode raiseNode, Object arg, CExtToNativeNode toSulong, Object voidPtr)
                        throws ParseArgumentsException {
            Object rc = callConvertbuffer.call(nativeContext, FUN_CONVERTBUFFER, toSulong.execute(arg), voidPtr);
            if (!(rc instanceof Number)) {
                throw shouldNotReachHere("wrong result of internal function");
            }
            int i = intValue((Number) rc);
            // first two results are the error results from getbuffer, the third is the one from
            // convertbuffer
            if (i == -1) {
                throw converterr(raiseNode, ErrorMessages.READ_WRITE_BYTELIKE_OBJ, arg);
            } else if (i == -2) {
                throw converterr(raiseNode, ErrorMessages.CONTIGUOUS_BUFFER, arg);
            } else if (i == -3) {
                throw converterr(raiseNode, ErrorMessages.READ_ONLY_BYTELIKE_OBJ, arg);
            }
            return i;
        }

        @TruffleBoundary
        private static int intValue(Number rc) {
            return rc.intValue();
        }

        @Specialization(guards = "c == FORMAT_LOWER_P")
        static ParserState doPredicate(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, Object varargs,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Shared("writeOutVarNode") @Cached WriteOutVarNode writeOutVarNode,
                        @Cached PyObjectIsTrueNode isTrueNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (!skipOptionalArg(arg, state.restOptional)) {
                writeOutVarNode.writeInt32(varargs, state.outIndex, isTrueNode.execute(null, arg) ? 1 : 0);
            }
            return state.incrementOutIndex();
        }

        @Specialization(guards = "c == FORMAT_PAR_OPEN")
        static ParserState doParOpen(ParserState state, Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format, @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") int formatLength,
                        Object kwdnames, @SuppressWarnings("unused") Object varargs,
                        @Cached PySequenceCheckNode sequenceCheckNode,
                        @Cached TupleNodes.ConstructTupleNode constructTupleNode,
                        @Cached PythonObjectFactory factory,
                        @Shared("getArgNode") @Cached GetArgNode getArgNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException, ParseArgumentsException {

            Object arg = getArgNode.execute(state, kwds, kwdnames, state.restKeywordsOnly);
            if (skipOptionalArg(arg, state.restOptional)) {
                return state.open(new PositionalArgStack(factory.createEmptyTuple(), state.v));
            } else {
                if (!sequenceCheckNode.execute(arg)) {
                    throw raise(raiseNode, TypeError, ErrorMessages.EXPECTED_S_GOT_P, "tuple", arg);
                }
                try {
                    return state.open(new PositionalArgStack(constructTupleNode.execute(null, arg), state.v));
                } catch (PException e) {
                    throw raise(raiseNode, TypeError, ErrorMessages.FAILED_TO_CONVERT_SEQ);
                }
            }
        }

        @Specialization(guards = "c == FORMAT_PAR_CLOSE")
        static ParserState doParClose(ParserState state, @SuppressWarnings("unused") Object kwds, @SuppressWarnings("unused") char c, @SuppressWarnings("unused") TruffleString format,
                        @SuppressWarnings("unused") int formatLength,
                        @SuppressWarnings("unused") int formatIdx,
                        @SuppressWarnings("unused") Object kwdnames, @SuppressWarnings("unused") Object varargs,
                        @Cached SequenceStorageNodes.LenNode lenNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws ParseArgumentsException {
            if (state.v.prev == null) {
                CompilerDirectives.transferToInterpreter();
                raiseNode.raiseIntWithoutFrame(0, PythonBuiltinClassType.SystemError, ErrorMessages.LEFT_BRACKET_WO_RIGHT_BRACKET_IN_ARG);
                throw ParseArgumentsException.raise();
            }
            int len = lenNode.execute(state.v.argv.getSequenceStorage());
            // Only check for excess. Too few arguments are checked when obtaining them
            if (len > state.v.argnum) {
                throw raise(raiseNode, TypeError, ErrorMessages.MUST_BE_SEQ_OF_LENGTH_D_NOT_D, state.v.argnum, len);
            }
            return state.close();
        }

        private static ParseArgumentsException raise(PRaiseNativeNode raiseNode, PythonBuiltinClassType errType, TruffleString format, Object... arguments) {
            CompilerDirectives.transferToInterpreter();
            raiseNode.executeInt(null, 0, errType, format, arguments);
            throw ParseArgumentsException.raise();
        }

        private static boolean skipOptionalArg(Object arg, boolean optional) {
            return arg == null && optional;
        }

        private static boolean isLookahead(TruffleString format, int formatIdx, int formatLength, char expected, TruffleString.CodePointAtIndexNode codepointAtIndexNode) {
            return formatIdx + 1 < formatLength && codepointAtIndexNode.execute(format, formatIdx + 1, TS_ENCODING) == expected;
        }

        public static CExtToNativeNode createTN(ParserState state) {
            return state.nativeContext.getSupplier().createToNativeNode();
        }

        public static CExtToNativeNode getUncachedTN(ParserState state) {
            return state.nativeContext.getSupplier().getUncachedToNativeNode();
        }

        public static CExtToJavaNode createTJ(ParserState state) {
            return state.nativeContext.getSupplier().createToJavaNode();
        }

        public static CExtToJavaNode getUncachedTJ(ParserState state) {
            return state.nativeContext.getSupplier().getUncachedToJavaNode();
        }
    }

    /**
     * Gets a single argument from the arguments tuple or from the keywords dictionary.
     */
    @GenerateUncached
    abstract static class GetArgNode extends Node {

        public abstract Object execute(ParserState state, Object kwds, Object kwdnames, boolean keywords_only) throws InteropException;

        @Specialization(guards = {"kwds == null", "!keywordsOnly"})
        @SuppressWarnings("unused")
        static Object doNoKeywords(ParserState state, Object kwds, Object kwdnames, boolean keywordsOnly,
                        @Shared("lenNode") @Cached SequenceNodes.LenNode lenNode,
                        @Shared("getSequenceStorageNode") @Cached GetSequenceStorageNode getSequenceStorageNode,
                        @Shared("getItemNode") @Cached SequenceStorageNodes.GetItemDynamicNode getItemNode,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) {

            Object out = null;
            assert !keywordsOnly;
            int l = lenNode.execute(state.v.argv);
            if (state.v.argnum < l) {
                out = getItemNode.execute(getSequenceStorageNode.execute(state.v.argv), state.v.argnum);
            }
            if (out == null && !state.restOptional) {
                raiseNode.raiseIntWithoutFrame(0, TypeError, ErrorMessages.S_MISSING_REQUIRED_ARG_POS_D, state.funName, state.v.argnum);
                throw ParseArgumentsException.raise();
            }
            state.v.argnum++;
            return out;
        }

        @Specialization(replaces = "doNoKeywords")
        static Object doGeneric(ParserState state, Object kwds, Object kwdnames, boolean keywordsOnly,
                        @Shared("lenNode") @Cached SequenceNodes.LenNode lenNode,
                        @Shared("getSequenceStorageNode") @Cached GetSequenceStorageNode getSequenceStorageNode,
                        @Shared("getItemNode") @Cached SequenceStorageNodes.GetItemDynamicNode getItemNode,
                        @CachedLibrary(limit = "1") InteropLibrary kwdnamesLib,
                        @Cached HashingStorageGetItem getItem,
                        @Cached PCallCExtFunction callCStringToString,
                        @Shared("raiseNode") @Cached PRaiseNativeNode raiseNode) throws InteropException {

            Object out = null;
            if (!keywordsOnly) {
                int l = lenNode.execute(state.v.argv);
                if (state.v.argnum < l) {
                    out = getItemNode.execute(getSequenceStorageNode.execute(state.v.argv), state.v.argnum);
                }
            }
            // only the bottom argstack can have keyword names
            if (kwds != null && out == null && state.v.prev == null && kwdnames != null) {
                Object kwdnamePtr = kwdnamesLib.readArrayElement(kwdnames, state.v.argnum);
                // TODO(fa) check if this is the NULL pointer since the kwdnames are always
                // NULL-terminated
                Object kwdname = callCStringToString.call(state.nativeContext, NativeCAPISymbol.FUN_PY_TRUFFLE_CSTR_TO_STRING, kwdnamePtr);
                if (kwdname instanceof TruffleString) {
                    // the cast to PDict is safe because either it is null or a PDict (ensured by
                    // the guards)
                    out = getItem.execute(((PDict) kwds).getDictStorage(), (TruffleString) kwdname);
                }
            }
            if (out == null && !state.restOptional) {
                raiseNode.raiseIntWithoutFrame(0, TypeError, ErrorMessages.S_MISSING_REQUIRED_ARG_POS_D, state.funName, state.v.argnum);
                throw ParseArgumentsException.raise();
            }
            state.v.argnum++;
            return out;
        }
    }

    /**
     * Executes a custom argument converter (i.e.
     * {@code int converter_fun(PyObject *arg, void *outVar)}.
     */
    @GenerateUncached
    abstract static class ExecuteConverterNode extends Node {

        public abstract void execute(ConversionNodeSupplier supplier, int index, Object converter, Object inputArgument, Object outputArgument) throws ParseArgumentsException;

        @Specialization(guards = "cachedIndex == index", limit = "5")
        @SuppressWarnings("unused")
        static void doExecuteConverterCached(ConversionNodeSupplier supplier, int index, Object converter, Object inputArgument,
                        Object outputArgument,
                        @Cached(value = "index", allowUncached = true) @SuppressWarnings("unused") int cachedIndex,
                        @CachedLibrary("converter") InteropLibrary converterLib,
                        @CachedLibrary(limit = "1") InteropLibrary resultLib,
                        @Cached(value = "createTN(supplier)", uncached = "getUncachedTN(supplier)") CExtToNativeNode toNativeNode,
                        @Exclusive @Cached PRaiseNativeNode raiseNode,
                        @Exclusive @Cached ConverterCheckResultNode checkResultNode) throws ParseArgumentsException {
            doExecuteConverterGeneric(supplier, index, converter, inputArgument, outputArgument, converterLib, resultLib, toNativeNode, raiseNode, checkResultNode);
        }

        @Specialization(replaces = "doExecuteConverterCached", limit = "1")
        @SuppressWarnings("unused")
        static void doExecuteConverterGeneric(ConversionNodeSupplier supplier, int index, Object converter, Object inputArgument, Object outputArgument,
                        @CachedLibrary("converter") InteropLibrary converterLib,
                        @CachedLibrary(limit = "1") InteropLibrary resultLib,
                        @Cached(value = "createTN(supplier)", uncached = "getUncachedTN(supplier)") CExtToNativeNode toNativeNode,
                        @Exclusive @Cached PRaiseNativeNode raiseNode,
                        @Exclusive @Cached ConverterCheckResultNode checkResultNode) throws ParseArgumentsException {
            try {
                Object result = converterLib.execute(converter, toNativeNode.execute(inputArgument), outputArgument);
                if (resultLib.fitsInInt(result)) {
                    checkResultNode.execute(resultLib.asInt(result));
                    return;
                }
                CompilerDirectives.transferToInterpreter();
                raiseNode.raiseIntWithoutFrame(0, PythonBuiltinClassType.SystemError, ErrorMessages.CALLING_ARG_CONVERTER_FAIL_UNEXPECTED_RETURN, result);
            } catch (UnsupportedTypeException e) {
                CompilerDirectives.transferToInterpreter();
                raiseNode.raiseIntWithoutFrame(0, PythonBuiltinClassType.SystemError, ErrorMessages.CALLING_ARG_CONVERTER_FAIL_INCOMPATIBLE_PARAMS, e.getSuppliedValues());
            } catch (ArityException e) {
                CompilerDirectives.transferToInterpreter();
                raiseNode.raiseIntWithoutFrame(0, PythonBuiltinClassType.SystemError, ErrorMessages.CALLING_ARG_CONVERTER_FAIL_EXPECTED_D_GOT_P, e.getExpectedMinArity(),
                                e.getActualArity());
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                raiseNode.raiseIntWithoutFrame(0, PythonBuiltinClassType.SystemError, ErrorMessages.ARG_CONVERTED_NOT_EXECUTABLE);
            }
            throw ParseArgumentsException.raise();
        }

        static CExtToNativeNode createTN(ConversionNodeSupplier supplier) {
            return supplier.createToNativeNode();
        }

        static CExtToNativeNode getUncachedTN(ConversionNodeSupplier supplier) {
            return supplier.getUncachedToNativeNode();
        }
    }

    /**
     * Executes a custom argument converter (i.e.
     * {@code int converter_fun(PyObject *arg, void *outVar)}.
     */
    @GenerateUncached
    abstract static class ConverterCheckResultNode extends Node {

        public abstract void execute(int statusCode) throws ParseArgumentsException;

        @Specialization(guards = "statusCode != 0")
        static void doSuccess(@SuppressWarnings("unused") int statusCode) {
            // all fine
        }

        @Specialization(guards = "statusCode == 0")
        static void doError(@SuppressWarnings("unused") int statusCode,
                        @Cached GetThreadStateNode getThreadStateNode,
                        @Cached PRaiseNativeNode raiseNode) throws ParseArgumentsException {
            PException currentException = getThreadStateNode.getCurrentException();
            boolean errOccurred = currentException != null;
            if (!errOccurred) {
                // converter should have set exception
                raiseNode.raiseInt(null, 0, TypeError, ErrorMessages.CONVERTER_FUNC_FAILED_TO_SET_ERROR);
            }
            throw ParseArgumentsException.raise();
        }
    }

    /**
     * Writes to an output variable in the varargs by doing the necessary native typing and
     * dereferencing. This is mostly like
     *
     * <pre>
     *     SomeType *outVar = va_arg(valist, SomeType *);
     *     *outVar = value;
     * </pre>
     *
     * It is important to use the appropriate {@code write*} functions!
     */
    @GenerateUncached
    @ImportStatic(LLVMType.class)
    abstract static class WriteOutVarNode extends Node {

        public final void writeUInt8(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.uint8_ptr_t, value);
        }

        public final void writeInt8(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.int8_ptr_t, value);
        }

        public final void writeUInt16(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.uint16_ptr_t, value);
        }

        public final void writeInt16(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.int16_ptr_t, value);
        }

        public final void writeInt32(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.int32_ptr_t, value);
        }

        public final void writeUInt32(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.uint32_ptr_t, value);
        }

        public final void writeInt64(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.int64_ptr_t, value);
        }

        public final void writeUInt64(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.uint64_ptr_t, value);
        }

        public final void writePySsizeT(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.Py_ssize_ptr_t, value);
        }

        public final void writeFloat(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.float_ptr_t, value);
        }

        public final void writeDouble(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.double_ptr_t, value);
        }

        /**
         * Use this method if the object (pointer) to write is already a Sulong object (e.g. an LLVM
         * pointer or a native wrapper) and does not need to be wrapped.
         */
        public final void writePyObject(Object valist, int index, Object value) throws InteropException {
            execute(valist, index, LLVMType.PyObject_ptr_ptr_t, value);
        }

        public final void writeComplex(Object valist, int index, PComplex value) throws InteropException {
            execute(valist, index, LLVMType.Py_complex_ptr_t, value);
        }

        public abstract void execute(Object valist, int index, LLVMType accessType, Object value) throws InteropException;

        @Specialization(guards = "isPointerToPrimitive(accessType)", limit = "1")
        static void doPrimitive(Object valist, int index, LLVMType accessType, Number value,
                        @CachedLibrary("valist") InteropLibrary vaListLib,
                        @Shared("outVarPtrLib") @CachedLibrary(limit = "1") InteropLibrary outVarPtrLib,
                        @Shared("getLLVMType") @Cached GetLLVMType getLLVMTypeNode) {
            // The access type should be PE constant if the appropriate 'write*' method is used
            doAccess(vaListLib, outVarPtrLib, valist, index, getLLVMTypeNode.execute(accessType), value);
        }

        @Specialization(guards = "accessType == PyObject_ptr_ptr_t", limit = "1")
        static void doPointer(Object valist, int index, @SuppressWarnings("unused") LLVMType accessType, Object value,
                        @CachedLibrary("valist") InteropLibrary vaListLib,
                        @Shared("outVarPtrLib") @CachedLibrary(limit = "1") InteropLibrary outVarPtrLib,
                        @Shared("getLLVMType") @Cached GetLLVMType getLLVMTypeNode) {
            doAccess(vaListLib, outVarPtrLib, valist, index, getLLVMTypeNode.execute(accessType), value);
        }

        @Specialization(guards = "accessType == Py_complex_ptr_t", limit = "1")
        static void doComplex(Object valist, int index, @SuppressWarnings("unused") LLVMType accessType, PComplex value,
                        @CachedLibrary("valist") InteropLibrary vaListLib,
                        @CachedLibrary(limit = "1") InteropLibrary outVarPtrLib,
                        @Shared("getLLVMType") @Cached GetLLVMType getLLVMTypeNode) {
            try {
                // like 'some_type* out_var = va_arg(vl, some_type*)'
                Object outVarPtr = vaListLib.invokeMember(valist, "get", index, getLLVMTypeNode.execute(accessType));
                outVarPtrLib.writeMember(outVarPtr, "real", value.getReal());
                outVarPtrLib.writeMember(outVarPtr, "img", value.getImag());
            } catch (InteropException e) {
                throw shouldNotReachHere(e);
            }
        }

        private static void doAccess(InteropLibrary valistLib, InteropLibrary outVarPtrLib, Object valist, int index, Object llvmTypeID, Object value) {
            try {
                // like 'some_type* out_var = va_arg(vl, some_type*)'
                Object outVarPtr = valistLib.invokeMember(valist, "get", index, llvmTypeID);
                // like 'out_var[0] = value'
                outVarPtrLib.writeArrayElement(outVarPtr, 0, value);
            } catch (InteropException e) {
                throw shouldNotReachHere(e);
            }
        }
    }

    static final class ParseArgumentsException extends ControlFlowException {
        private static final long serialVersionUID = 1L;

        static ParseArgumentsException raise() {
            CompilerDirectives.transferToInterpreter();
            throw new ParseArgumentsException();
        }
    }

    @GenerateUncached
    public abstract static class SplitFormatStringNode extends Node {

        public abstract TruffleString[] execute(TruffleString format);

        @Specialization(guards = "cachedFormat.equals(format)", limit = "1")
        static TruffleString[] doCached(@SuppressWarnings("unused") TruffleString format,
                        @Cached("format") @SuppressWarnings("unused") TruffleString cachedFormat,
                        @Cached(value = "extractFormatOnly(format)", dimensions = 1) TruffleString[] cachedResult) {
            return cachedResult;
        }

        @Specialization(replaces = "doCached")
        static TruffleString[] doGeneric(TruffleString format,
                        @Cached ConditionProfile hasFunctionNameProfile,
                        @Cached TruffleString.IndexOfCodePointNode indexOfCodePointNode,
                        @Cached TruffleString.SubstringNode substringNode,
                        @Cached TruffleString.CodePointLengthNode lengthNode) {
            int len = lengthNode.execute(format, TS_ENCODING);
            int colonIdx = indexOfCodePointNode.execute(format, ':', 0, len, TS_ENCODING);
            if (hasFunctionNameProfile.profile(colonIdx >= 0)) {
                // trim off function name
                return new TruffleString[]{substringNode.execute(format, 0, colonIdx, TS_ENCODING, false), substringNode.execute(format, colonIdx + 1, len - colonIdx - 1, TS_ENCODING, false)};
            }
            return new TruffleString[]{format, T_EMPTY_STRING};
        }

        static TruffleString[] extractFormatOnly(TruffleString format) {
            return doGeneric(format, ConditionProfile.getUncached(), TruffleString.IndexOfCodePointNode.getUncached(), TruffleString.SubstringNode.getUncached(),
                            TruffleString.CodePointLengthNode.getUncached());
        }
    }
}
