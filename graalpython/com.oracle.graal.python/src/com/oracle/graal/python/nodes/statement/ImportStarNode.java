/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates.
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
package com.oracle.graal.python.nodes.statement;

import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___ALL__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___DICT__;
import static com.oracle.graal.python.nodes.frame.ReadLocalsNode.fastGetCustomLocalsOrGlobals;
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.mappingproxy.PMappingproxy;
import com.oracle.graal.python.builtins.objects.module.PythonModule;
import com.oracle.graal.python.builtins.objects.object.PythonObject;
import com.oracle.graal.python.lib.GetNextNode;
import com.oracle.graal.python.lib.PyObjectGetAttr;
import com.oracle.graal.python.lib.PyObjectGetAttrNodeGen;
import com.oracle.graal.python.lib.PyObjectGetIter;
import com.oracle.graal.python.lib.PyObjectSizeNode;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.attributes.SetAttributeNode;
import com.oracle.graal.python.nodes.interop.PForeignToPTypeNode;
import com.oracle.graal.python.nodes.object.GetOrCreateDictNode;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.nodes.subscript.GetItemNode;
import com.oracle.graal.python.nodes.subscript.SetItemNode;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToTruffleStringNode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;

public class ImportStarNode extends AbstractImportNode {
    private final ConditionProfile javaImport = ConditionProfile.createBinaryProfile();
    private final ConditionProfile havePyFrame = ConditionProfile.createBinaryProfile();
    private final ConditionProfile haveCustomLocals = ConditionProfile.createBinaryProfile();

    @Child private SetItemNode dictWriteNode;
    @Child private SetAttributeNode.Dynamic setAttributeNode;
    @Child private GetItemNode getItemNode;
    @Child private CastToTruffleStringNode castToStringNode;
    @Child private GetNextNode nextNode;
    @Child private PyObjectSizeNode sizeNode;
    @Child private PyObjectGetIter getIterNode;
    @Child private PyObjectGetAttr getAllNode;
    @Child private GetOrCreateDictNode getDictNode;

    @Child private IsBuiltinClassProfile isAttributeErrorProfile;
    @Child private IsBuiltinClassProfile isStopIterationProfile;

    @Child private PRaiseNode raiseNode;
    @Child private TruffleString.FromJavaStringNode fromJavaStringNode;
    @Child private TruffleString.CodePointLengthNode codePointLengthNode;
    @Child private TruffleString.CodePointAtIndexNode codePointAtIndexNode;

    private final TruffleString moduleName;
    private final int level;

    private PException raiseTypeError(TruffleString format, Object... args) {
        if (raiseNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            raiseNode = insert(PRaiseNode.create());
        }
        throw raiseNode.raise(PythonBuiltinClassType.TypeError, format, args);
    }

    // TODO: remove once we removed PythonModule globals

    private void writeAttribute(VirtualFrame frame, PythonObject globals, TruffleString name, Object value) {
        if (globals instanceof PDict || globals instanceof PMappingproxy) {
            if (dictWriteNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                dictWriteNode = insert(SetItemNode.create());
            }
            dictWriteNode.execute(frame, globals, name, value);
        } else {
            if (setAttributeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setAttributeNode = insert(new SetAttributeNode.Dynamic());
            }
            setAttributeNode.execute(frame, globals, name, value);
        }
    }

    public ImportStarNode(TruffleString moduleName, int level) {
        this.moduleName = moduleName;
        this.level = level;
    }

    public void execute(VirtualFrame frame) {
        Object importedModule = importModule(frame, moduleName, PArguments.getGlobals(frame), T_IMPORT_ALL, level);
        PythonObject locals = fastGetCustomLocalsOrGlobals(frame, havePyFrame, haveCustomLocals);

        if (javaImport.profile(emulateJython() && getContext().getEnv().isHostObject(importedModule))) {
            try {
                InteropLibrary interopLib = InteropLibrary.getFactory().getUncached();
                Object hostAttrs = interopLib.getMembers(importedModule, true);
                int len = (int) interopLib.getArraySize(hostAttrs);
                for (int i = 0; i < len; i++) {
                    // interop protocol guarantees these are strings
                    String attrName = interopLib.asString(interopLib.readArrayElement(hostAttrs, i));
                    Object attr = interopLib.readMember(importedModule, attrName);
                    attr = PForeignToPTypeNode.getUncached().executeConvert(attr);
                    writeAttribute(frame, locals, ensureFromJavaStringNode().execute(attrName, TS_ENCODING), attr);
                }
            } catch (UnknownIdentifierException | UnsupportedMessageException | InvalidArrayIndexException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException(e);
            }
        } else {
            try {
                Object attrAll = ensureGetAllNode().execute(frame, importedModule, T___ALL__);
                int n = ensureSizeNode().execute(frame, attrAll);
                for (int i = 0; i < n; i++) {
                    Object attrName = ensureGetItemNode().executeObject(frame, attrAll, i);
                    writeAttributeToLocals(frame, (PythonModule) importedModule, locals, attrName, true);
                }
            } catch (PException e) {
                e.expectAttributeError(ensureIsAttributeErrorProfile());
                assert importedModule instanceof PythonModule;
                Object keysIterator = ensureGetIterNode().execute(frame, ensureGetDictNode().execute(importedModule));
                while (true) {
                    try {
                        Object key = ensureGetNextNode().execute(frame, keysIterator);
                        writeAttributeToLocals(frame, (PythonModule) importedModule, locals, key, false);
                    } catch (PException iterException) {
                        iterException.expectStopIteration(ensureIsStopIterationErrorProfile());
                        break;
                    }
                }
            }
        }
    }

    private void writeAttributeToLocals(VirtualFrame frame, PythonModule importedModule, PythonObject locals, Object attrName, boolean fromAll) {
        try {
            TruffleString name = ensureCastToStringNode().execute(attrName);
            // skip attributes with leading '__' if there was no '__all__' attribute (see
            // 'ceval.c: import_all_from')
            if (fromAll || !isDunder(name)) {
                Object moduleAttr = ensureGetAllNode().execute(frame, importedModule, name);
                writeAttribute(frame, locals, name, moduleAttr);
            }
        } catch (CannotCastException cce) {
            throw raiseTypeError(fromAll ? ErrorMessages.ITEM_IN_S_MUST_BE_STRING : ErrorMessages.KEY_IN_S_MUST_BE_STRING,
                            moduleName, fromAll ? T___ALL__ : T___DICT__, attrName);
        }
    }

    private boolean isDunder(TruffleString s) {
        TruffleString.CodePointLengthNode cpLenNode = ensureCodePointLengthNode();
        TruffleString.CodePointAtIndexNode cpAtIndexNode = ensureCodePointAtIndexNode();
        return cpLenNode.execute(s, TS_ENCODING) >= 2 && cpAtIndexNode.execute(s, 0, TS_ENCODING) == '_' && cpAtIndexNode.execute(s, 1, TS_ENCODING) == '_';
    }

    private GetItemNode ensureGetItemNode() {
        if (getItemNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getItemNode = insert(GetItemNode.create());
        }
        return getItemNode;
    }

    private CastToTruffleStringNode ensureCastToStringNode() {
        if (castToStringNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            castToStringNode = insert(CastToTruffleStringNode.create());
        }
        return castToStringNode;
    }

    private IsBuiltinClassProfile ensureIsAttributeErrorProfile() {
        if (isAttributeErrorProfile == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isAttributeErrorProfile = insert(IsBuiltinClassProfile.create());
        }
        return isAttributeErrorProfile;
    }

    private IsBuiltinClassProfile ensureIsStopIterationErrorProfile() {
        if (isStopIterationProfile == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isStopIterationProfile = insert(IsBuiltinClassProfile.create());
        }
        return isStopIterationProfile;
    }

    private GetNextNode ensureGetNextNode() {
        if (nextNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            nextNode = insert(GetNextNode.create());
        }
        return nextNode;
    }

    private PyObjectSizeNode ensureSizeNode() {
        if (sizeNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            sizeNode = insert(PyObjectSizeNode.create());
        }
        return sizeNode;
    }

    private PyObjectGetAttr ensureGetAllNode() {
        if (getAllNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getAllNode = insert(PyObjectGetAttrNodeGen.create());
        }
        return getAllNode;
    }

    private PyObjectGetIter ensureGetIterNode() {
        if (getIterNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getIterNode = insert(PyObjectGetIter.create());
        }
        return getIterNode;
    }

    private GetOrCreateDictNode ensureGetDictNode() {
        if (getDictNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getDictNode = insert(GetOrCreateDictNode.create());
        }
        return getDictNode;
    }

    private TruffleString.FromJavaStringNode ensureFromJavaStringNode() {
        if (fromJavaStringNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            fromJavaStringNode = insert(TruffleString.FromJavaStringNode.create());
        }
        return fromJavaStringNode;
    }

    private TruffleString.CodePointLengthNode ensureCodePointLengthNode() {
        if (codePointLengthNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            codePointLengthNode = insert(TruffleString.CodePointLengthNode.create());
        }
        return codePointLengthNode;
    }

    private TruffleString.CodePointAtIndexNode ensureCodePointAtIndexNode() {
        if (codePointAtIndexNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            codePointAtIndexNode = insert(TruffleString.CodePointAtIndexNode.create());
        }
        return codePointAtIndexNode;
    }
}
