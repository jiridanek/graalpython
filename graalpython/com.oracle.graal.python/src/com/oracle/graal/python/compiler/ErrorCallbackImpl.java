/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.compiler;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.exception.PBaseException;
import com.oracle.graal.python.builtins.objects.exception.SyntaxErrorBuiltins;
import com.oracle.graal.python.pegparser.ErrorCallback;
import com.oracle.graal.python.pegparser.tokenizer.SourceRange;
import com.oracle.graal.python.runtime.PythonParser;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;

import static com.oracle.graal.python.util.PythonUtils.toTruffleStringUncached;
import static com.oracle.graal.python.util.PythonUtils.tsLiteral;

public class ErrorCallbackImpl implements ErrorCallback {

    private static final TruffleString DEFAULT_FILENAME = tsLiteral("<string>");

    private final Source source;
    private final boolean withJavaStackTrace;

    public ErrorCallbackImpl(Source source, boolean withJavaStackTrace) {
        this.source = source;
        this.withJavaStackTrace = withJavaStackTrace;
    }

    @Override
    public void reportIncompleteSource(int line) {
        throw new PythonParser.PIncompleteSourceException("", null, line, source);
    }

    @Override
    public void onError(ErrorType errorType, SourceRange sourceRange, String message) {
        throw raiseSyntaxError(errorType, sourceRange, toTruffleStringUncached(message));
    }

    private PException raiseSyntaxError(ErrorCallback.ErrorType errorType, SourceRange sourceRange, TruffleString message) {
        Node location = new Node() {
            @Override
            public boolean isAdoptable() {
                return false;
            }

            @Override
            public SourceSection getSourceSection() {
                // TODO these checks should not be necessary, the parser should probably guarantee correct coordinates
                if (sourceRange.startLine > source.getLineCount() || sourceRange.endLine > source.getLineCount()) {
                    // Tokenizer pretends the input ends with a newline, which is not in the source
                    return source.createSection(source.getLength(), 0);
                }
                int startCol = Math.max(sourceRange.startColumn, 0) + 1;
                startCol = Math.min(startCol, source.getLineLength(sourceRange.startLine) + 1);
                int endCol;
                if (sourceRange.endColumn < 0) {
                    if (sourceRange.startLine == sourceRange.endLine) {
                        endCol = startCol;
                    } else {
                        endCol = 1;
                    }
                } else {
                    endCol = sourceRange.endColumn + 1;
                }
                endCol = Math.min(endCol, source.getLineLength(sourceRange.endLine) + 1);
                if (sourceRange.endLine == source.getLineCount() && endCol == source.getLineLength(sourceRange.endLine) + 1) {
                    // Source.createSection does not like it when the end coord points past the source.
                    if (endCol > 1 && (sourceRange.startLine < sourceRange.endLine || startCol < endCol)) {
                        // Column index must be at least 1.
                        endCol--;
                    } else {
                        // This is a problem - there's no correct line:column coord for the last empty line
                        return source.createSection(source.getLength(), 0);
                    }
                }
                return source.createSection(sourceRange.startLine, startCol, sourceRange.endLine, endCol);
            }
        };
        PBaseException instance;
        PythonBuiltinClassType cls = PythonBuiltinClassType.SyntaxError;
        switch (errorType) {
            case Indentation:
                cls = PythonBuiltinClassType.IndentationError;
                break;
            case Tab:
                cls = PythonBuiltinClassType.TabError;
                break;
        }
        instance = PythonObjectFactory.getUncached().createBaseException(cls, message, PythonUtils.EMPTY_OBJECT_ARRAY);
        final Object[] excAttrs = SyntaxErrorBuiltins.SYNTAX_ERROR_ATTR_FACTORY.create();
        TruffleString filename = toTruffleStringUncached(source.getPath());
        if (filename == null) {
            filename = toTruffleStringUncached(source.getName());
            if (filename == null) {
                filename = DEFAULT_FILENAME;
            }
        }
        excAttrs[SyntaxErrorBuiltins.IDX_FILENAME] = filename;
        excAttrs[SyntaxErrorBuiltins.IDX_LINENO] = sourceRange.startLine;
        excAttrs[SyntaxErrorBuiltins.IDX_OFFSET] = sourceRange.startColumn + 1;
        // Not very nice. This counts on the implementation in traceback.py where if the value of
        // text attribute is NONE, then the line is not printed
        Object text = PNone.NONE;
        if (sourceRange.startLine <= source.getLineCount()) {
            text = toTruffleStringUncached(source.getCharacters(sourceRange.startLine).toString());
        }
        excAttrs[SyntaxErrorBuiltins.IDX_MSG] = message;
        excAttrs[SyntaxErrorBuiltins.IDX_TEXT] = text;
        instance.setExceptionAttributes(excAttrs);
        throw PException.fromObject(instance, location, withJavaStackTrace);
    }
}
