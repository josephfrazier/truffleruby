/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2004-2007 Thomas E Enebo <enebo@acm.org>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.truffleruby.parser.lexer;

import static org.truffleruby.parser.lexer.RubyLexer.EOF;
import static org.truffleruby.parser.lexer.RubyLexer.STR_FUNC_EXPAND;
import static org.truffleruby.parser.lexer.RubyLexer.STR_FUNC_INDENT;

import org.jcodings.Encoding;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.parser.parser.Tokens;

/**
 * A lexing unit for scanning a heredoc element.
 * Example:
 * <pre>
 * foo(<<EOS, bar)
 * This is heredoc country!
 * EOF
 *
 * Where:
 * EOS = marker
 * ',bar)\n' = lastLine
 * </pre>
 *
 */
public class HeredocTerm extends StrTerm {
    // Marker delimiting heredoc boundary
    private final Rope nd_lit;

    // Expand variables, Indentation of final marker
    private final int flags;

    protected final int nth;

    protected final int line;

    // Portion of line right after beginning marker
    protected final Rope lastLine;

    public HeredocTerm(Rope marker, int func, int nth, int line, Rope lastLine) {
        this.nd_lit = marker;
        this.flags = func;
        this.nth = nth;
        this.line = line;
        this.lastLine = lastLine;
    }

    @Override
    public int getFlags() {
        return flags;
    }

    protected int error(RubyLexer lexer, Rope eos) {
        lexer.compile_error("can't find string \"" + RopeOperations.decodeRope(eos) + "\" anywhere before EOF");
        return -1;
    }

    protected int restore(RubyLexer lexer) {
        lexer.heredoc_restore(this);
        lexer.setStrTerm(null);

        return EOF;
    }

    @Override
    public int parseString(RubyLexer lexer) {
        RopeBuilder str = null;
        Rope eos = nd_lit;
        boolean indent = (flags & STR_FUNC_INDENT) != 0;
        int c = lexer.nextc();

        if (c == EOF) {
            return error(lexer, eos);
        }

        // Found end marker for this heredoc
        if (lexer.was_bol() && lexer.whole_match_p(nd_lit, indent)) {
            lexer.heredoc_restore(this);
            return Tokens.tSTRING_END;
        }

        if ((flags & STR_FUNC_EXPAND) == 0) {
            do {
                Rope lbuf = lexer.lex_lastline;
                int p = 0;
                int pend = lexer.lex_pend;
                if (pend > p) {
                    switch (lexer.p(pend - 1)) {
                        case '\n':
                            pend--;
                            if (pend == p || lexer.p(pend - 1) == '\r') {
                                pend++;
                                break;
                            }
                            break;
                        case '\r':
                            pend--;
                            break;
                    }
                }

                if (lexer.getHeredocIndent() > 0) {
                    for (long i = 0; p + i < pend && lexer.update_heredoc_indent(lexer.p(p)); i++) {
                    }
                    lexer.setHeredocLineIndent(0);
                }

                if (str != null) {
                    str.append(lbuf.getBytes(), p, pend - p);
                } else {
                    final RopeBuilder builder = RopeBuilder.createRopeBuilder(lbuf.getBytes(), p, pend - p);
                    builder.setEncoding(lbuf.getEncoding());
                    str = builder;
                }

                if (pend < lexer.lex_pend) {
                    str.append('\n');
                }
                lexer.lex_goto_eol();

                if (lexer.getHeredocIndent() > 0) {
                    lexer.setValue(lexer.createStr(str, 0));
                    return Tokens.tSTRING_CONTENT;
                }
                // MRI null checks str in this case but it is unconditionally non-null?
                if (lexer.nextc() == -1) {
                    return error(lexer, eos);
                }
            } while (!lexer.whole_match_p(eos, indent));
        } else {
            RopeBuilder tok = new RopeBuilder();
            tok.setEncoding(lexer.getEncoding());
            if (c == '#') {
                switch (c = lexer.nextc()) {
                    case '$':
                    case '@':
                        lexer.pushback(c);
                        return Tokens.tSTRING_DVAR;
                    case '{':
                        lexer.commandStart = true;
                        return Tokens.tSTRING_DBEG;
                }
                tok.append('#');
            }

            // MRI has extra pointer which makes our code look a little bit more strange in comparison
            do {
                lexer.pushback(c);

                Encoding enc[] = new Encoding[1];
                enc[0] = lexer.getEncoding();

                if ((c = new StringTerm(flags, '\0', '\n').parseStringIntoBuffer(lexer, tok, enc)) == EOF) {
                    if (lexer.eofp) {
                        return error(lexer, eos);
                    }
                    return restore(lexer);
                }
                if (c != '\n') {
                    lexer.setValue(lexer.createStr(tok, 0));
                    return Tokens.tSTRING_CONTENT;
                }
                tok.append(lexer.nextc());

                if (lexer.getHeredocIndent() > 0) {
                    lexer.lex_goto_eol();
                    lexer.setValue(lexer.createStr(tok, 0));
                    return Tokens.tSTRING_CONTENT;
                }

                if ((c = lexer.nextc()) == EOF) {
                    return error(lexer, eos);
                }
            } while (!lexer.whole_match_p(eos, indent));
            str = tok;
        }

        lexer.heredoc_restore(this);
        lexer.setStrTerm(new StringTerm(-1, '\0', '\0'));
        lexer.setValue(lexer.createStr(str, 0));
        return Tokens.tSTRING_CONTENT;
    }
}
