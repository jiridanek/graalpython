/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.python.parser.sst;

import com.ibm.icu.lang.UCharacter;
import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;

import java.util.ArrayList;
import java.util.List;

import com.oracle.graal.python.builtins.objects.bytes.BytesUtils;
import com.oracle.graal.python.nodes.NodeFactory;
import com.oracle.graal.python.nodes.PNode;
import com.oracle.graal.python.nodes.control.BaseBlockNode;
import com.oracle.graal.python.nodes.expression.ExpressionNode;
import com.oracle.graal.python.nodes.literal.FormatStringLiteralNode;
import com.oracle.graal.python.nodes.literal.StringLiteralNode;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.nodes.statement.StatementNode;
import com.oracle.graal.python.runtime.PythonParser;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.source.Source;

public class StringUtils {

    private static final String CANNOT_MIX_MESSAGE = "cannot mix bytes and nonbytes literals";

    public static StringLiteralNode extractDoc(StatementNode node) {
        if (node instanceof ExpressionNode.ExpressionStatementNode) {
            return extractDoc(((ExpressionNode.ExpressionStatementNode) node).getExpression());
        } else if (node instanceof BaseBlockNode) {
            StatementNode[] statements = ((BaseBlockNode) node).getStatements();
            if (statements != null && statements.length > 0) {
                return extractDoc(statements[0]);
            }
            return null;
        }
        return null;
    }

    public static StringLiteralNode extractDoc(ExpressionNode node) {
        if (node instanceof StringLiteralNode) {
            return (StringLiteralNode) node;
        } else if (node instanceof ExpressionNode.ExpressionWithSideEffect) {
            return extractDoc(((ExpressionNode.ExpressionWithSideEffect) node).getSideEffect());
        } else if (node instanceof ExpressionNode.ExpressionWithSideEffects) {
            StatementNode[] sideEffects = ((ExpressionNode.ExpressionWithSideEffects) node).getSideEffects();
            if (sideEffects != null && sideEffects.length > 0) {
                return extractDoc(sideEffects[0]);
            }
        }
        return null;
    }

    private static class BytesBuilder {
        List<byte[]> bytes = new ArrayList<>();
        int len = 0;

        void append(byte[] b) {
            len += b.length;
            bytes.add(b);
        }

        byte[] build() {
            byte[] output = new byte[len];
            int offset = 0;
            for (byte[] bs : bytes) {
                PythonUtils.arraycopy(bs, 0, output, offset, bs.length);
                offset += bs.length;
            }
            return output;
        }
    }

    public static PNode parseString(Source source, StringLiteralSSTNode node, NodeFactory nodeFactory, PythonParser.ParserErrorCallback errors) {
        StringBuilder sb = null;
        BytesBuilder bb = null;
        boolean isFormatString = false;
        List<FormatStringLiteralNode.StringPart> formatStrings = null;
        for (String text : node.values) {
            boolean isRaw = false;
            boolean isBytes = false;
            boolean isFormat = false;

            int strStartIndex = 1;
            int strEndIndex = text.length() - 1;

            for (int i = 0; i < 3; i++) {
                char chr = Character.toLowerCase(text.charAt(i));

                if (chr == 'r') {
                    isRaw = true;
                } else if (chr == 'u') {
                    // unicode case (default)
                } else if (chr == 'b') {
                    isBytes = true;
                } else if (chr == 'f') {
                    isFormat = true;
                } else if (chr == '\'' || chr == '"') {
                    strStartIndex = i + 1;
                    break;
                }
            }

            if (text.endsWith("'''") || text.endsWith("\"\"\"")) {
                strStartIndex += 2;
                strEndIndex -= 2;
            }

            text = text.substring(strStartIndex, strEndIndex);
            if (isBytes) {
                if (sb != null || isFormatString) {
                    throw errors.raiseInvalidSyntax(source, source.createSection(node.startOffset, node.endOffset - node.startOffset), CANNOT_MIX_MESSAGE);
                }
                if (bb == null) {
                    bb = new BytesBuilder();
                }
                if (isRaw) {
                    bb.append(text.getBytes());
                } else {
                    bb.append(BytesUtils.fromString(errors, text));
                }
            } else {
                if (bb != null) {
                    throw errors.raiseInvalidSyntax(source, source.createSection(node.startOffset, node.endOffset - node.startOffset), CANNOT_MIX_MESSAGE);
                }
                if (!isRaw) {
                    try {
                        text = unescapeJavaString(text);
                    } catch (PException e) {
                        e.expect(PythonBuiltinClassType.UnicodeDecodeError, IsBuiltinClassProfile.getUncached());
                        String message = e.getMessage();
                        message = "(unicode error)" + message.substring(PythonBuiltinClassType.UnicodeDecodeError.getName().length() + 1);
                        throw errors.raiseInvalidSyntax(source, source.createSection(node.startOffset, node.endOffset - node.startOffset), message);
                    }
                }
                if (isFormat) {
                    isFormatString = true;
                    if (formatStrings == null) {
                        formatStrings = new ArrayList<>();
                    }
                    if (sb != null && sb.length() > 0) {
                        formatStrings.add(new FormatStringLiteralNode.StringPart(sb.toString(), false));
                        sb = null;
                    }
                    formatStrings.add(new FormatStringLiteralNode.StringPart(text, true));
                } else {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(text);
                }
            }
        }

        if (bb != null) {
            return nodeFactory.createBytesLiteral(bb.build());
        } else if (isFormatString) {
            if (sb != null && sb.length() > 0) {
                formatStrings.add(new FormatStringLiteralNode.StringPart(sb.toString(), false));
            }
            return nodeFactory.createFormatStringLiteral(formatStrings.toArray(new FormatStringLiteralNode.StringPart[formatStrings.size()]));
        }
        if (sb != null) {
            return nodeFactory.createStringLiteral(sb.toString());
        } else {
            return nodeFactory.createStringLiteral("");
        }
    }

    public static String unescapeJavaString(String st) {
        if (st.indexOf("\\") == -1) {
            return st;
        }
        StringBuilder sb = new StringBuilder(st.length());
        for (int i = 0; i < st.length(); i++) {
            char ch = st.charAt(i);
            if (ch == '\\') {
                char nextChar = (i == st.length() - 1) ? '\\' : st.charAt(i + 1);
                // Octal escape?
                if (nextChar >= '0' && nextChar <= '7') {
                    String code = "" + nextChar;
                    i++;
                    if ((i < st.length() - 1) && st.charAt(i + 1) >= '0' && st.charAt(i + 1) <= '7') {
                        code += st.charAt(i + 1);
                        i++;
                        if ((i < st.length() - 1) && st.charAt(i + 1) >= '0' && st.charAt(i + 1) <= '7') {
                            code += st.charAt(i + 1);
                            i++;
                        }
                    }
                    sb.append((char) Integer.parseInt(code, 8));
                    continue;
                }
                switch (nextChar) {
                    case '\\':
                        ch = '\\';
                        break;
                    case 'a':
                        ch = '\u0007';
                        break;
                    case 'b':
                        ch = '\b';
                        break;
                    case 'f':
                        ch = '\f';
                        break;
                    case 'n':
                        ch = '\n';
                        break;
                    case 'r':
                        ch = '\r';
                        break;
                    case 't':
                        ch = '\t';
                        break;
                    case 'v':
                        ch = '\u000b';
                        break;
                    case '\"':
                        ch = '\"';
                        break;
                    case '\'':
                        ch = '\'';
                        break;
                    case '\r':
                        nextChar = (i == st.length() - 2) ? '\\' : st.charAt(i + 2);
                        if (nextChar == '\n') {
                            i++;
                        }
                        i++;
                        continue;
                    case '\n':
                        i++;
                        continue;
                    // Hex Unicode: u????
                    case 'u':
                        if (i >= st.length() - 5) {
                            ch = 'u';
                            break;
                        }
                        int code = Integer.parseInt(
                                        "" + st.charAt(i + 2) + st.charAt(i + 3) + st.charAt(i + 4) + st.charAt(i + 5), 16);
                        sb.append(Character.toChars(code));
                        i += 5;
                        continue;
                    // Hex Unicode: U????????
                    case 'U':
                        if (i >= st.length() - 9) {
                            ch = 'U';
                            break;
                        }
                        code = Integer.parseInt(st.substring(i + 2, i + 10), 16);
                        sb.append(Character.toChars(code));
                        i += 9;
                        continue;
                    // Hex Unicode: x??
                    case 'x':
                        if (i >= st.length() - 3) {
                            ch = 'u';
                            break;
                        }
                        int hexCode = Integer.parseInt("" + st.charAt(i + 2) + st.charAt(i + 3), 16);
                        sb.append(Character.toChars(hexCode));
                        i += 3;
                        continue;
                    case 'N':
                        // a character from Unicode Data Database
                        i = doCharacterName(st, sb, i + 2);
                        continue;
                    default:
                        sb.append(ch);
                        sb.append(nextChar);
                        i++;
                        continue;
                }
                i++;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static final String UNICODE_ERROR = "'unicodeescape' codec can't decode bytes in position %d-%d:";
    private static final String MALFORMED_ERROR = " malformed \\N character escape";
    private static final String UNKNOWN_UNICODE_ERROR = " unknown Unicode character name";

    /**
     * Replace '/N{Unicode Character Name}' with the code point of the character. In JDK some
     * characters use different naming. The subset is bigger in JDK 9 than in JDK 8 etc. Also the
     * subset doesn't have to match with CPython.
     * 
     * @param text a text that contains /N{...} escape sequence
     * @param sb string builder where the result code point will be written
     * @param offset this is offset of the open brace
     * @return offset of the close brace
     */
    @CompilerDirectives.TruffleBoundary
    private static int doCharacterName(String text, StringBuilder sb, int offset) {
        char ch = text.charAt(offset);
        if (ch != '{') {
            throw PythonLanguage.getCore().raise(PythonBuiltinClassType.UnicodeDecodeError, UNICODE_ERROR + MALFORMED_ERROR, offset - 2, offset - 1);
        }
        int closeIndex = text.indexOf("}", offset + 1);
        if (closeIndex == -1) {
            throw PythonLanguage.getCore().raise(PythonBuiltinClassType.UnicodeDecodeError, UNICODE_ERROR + MALFORMED_ERROR, offset - 2, text.length() - 1);
        }
        String charName = text.substring(offset + 1, closeIndex).toUpperCase();
        // When JDK 1.8 will not be supported, we can replace with Character.codePointOf(String
        // name) in the
        int cp = getCodePoint(charName);
        if (cp >= 0) {
            sb.append(Character.toChars(cp));
        } else {
            throw PythonLanguage.getCore().raise(PythonBuiltinClassType.UnicodeDecodeError, UNICODE_ERROR + UNKNOWN_UNICODE_ERROR, offset - 2, closeIndex);
        }
        return closeIndex;
    }

    @CompilerDirectives.TruffleBoundary
    public static int getCodePoint(String charName) {
        int possibleChar = UCharacter.getCharFromName(charName);
        if (possibleChar > -1) {
            return possibleChar;
        }
        possibleChar = UCharacter.getCharFromExtendedName(charName);
        if (possibleChar > -1) {
            return possibleChar;
        }
        possibleChar = UCharacter.getCharFromNameAlias(charName);
        if (possibleChar > -1) {
            return possibleChar;
        }
        return -1;
    }
}
