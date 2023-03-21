/**
 * @Author: turk
 * @Description: Sintaksni analizator.
 */

package compiler.parser;

import static common.RequireNonNull.requireNonNull;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import common.Report;
import compiler.lexer.Symbol;
import compiler.lexer.TokenType;

public class Parser {
    /**
     * Seznam leksikalnih simbolov.
     */
    private final List<Symbol> symbols;
    private Symbol symbol;
    private int index;

    /**
     * Ciljni tok, kamor izpisujemo produkcije. Če produkcij ne želimo izpisovati,
     * vrednost opcijske spremenljivke nastavimo na Optional.empty().
     */
    private final Optional<PrintStream> productionsOutputStream;

    public Parser(List<Symbol> symbols, Optional<PrintStream> productionsOutputStream) {
        requireNonNull(symbols, productionsOutputStream);
        this.symbols = symbols;
        this.productionsOutputStream = productionsOutputStream;
        this.index = 0;
        this.symbol = symbols.get(index);
    }

    public boolean checkSkip(TokenType tokenType) {
        if (symbol.tokenType == tokenType) {
            skip();
            return true;
        }
        return false;
    }

    public void skip() {
        if (symbols.size() > ++index)
            symbol = symbols.get(index);
    }

    /**
     * Izvedi sintaksno analizo.
     */
    public void parse() {
        parseSource();
    }

    private void parseSource() {
        if (checkSkip(TokenType.EOF))
            Report.error("syntax error: empty file");
        dump("source -> definitions");
        parseDefinitions();

        if (!checkSkip(TokenType.EOF))
            Report.error(symbol.position,"syntax error: expected EOF, got " + symbol.lexeme);
    }

    private void parseDefinitions() {
        dump("definitions -> definition definitions1");
        parseDefinition();
        parseDefinitions1();
    }

    private void parseDefinitions1() {
        if (checkSkip(TokenType.OP_SEMICOLON)) {
            dump("definitions1 -> ; definitions");
            parseDefinitions();
        }
        else
            dump("definitions1 -> e");
    }

    private void parseDefinition() {
        if (checkSkip(TokenType.KW_TYP)) {
            dump("definition -> type_definition");
            parseTypeDefinition();
        }
        else if (checkSkip(TokenType.KW_VAR)) {
            dump("definition -> variable_definition");
            parseVariableDefinition();
        }
        else if (checkSkip(TokenType.KW_FUN)) {
            dump("definition -> function_definition");
            parseFunctionDefinition();
        }
        else
            Report.error(symbol.position, "syntax error: unexpected definition");
    }

    private void parseTypeDefinition() {
        dump("type_definition -> typ identifier : type");
        parseTypeVariableDefinition();
    }

    private void parseVariableDefinition() {
        dump("variable_definition -> var identifier : type");
        parseTypeVariableDefinition();
    }

    private void parseTypeVariableDefinition() {
        if (checkSkip(TokenType.IDENTIFIER)) {
            if (checkSkip(TokenType.OP_COLON)) {
                parseType();
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
    }

    private void parseFunctionDefinition() {
        dump("function_definition -> fun identifier ( parameters ) : type = expression");
        if (checkSkip(TokenType.IDENTIFIER)) {
            if (checkSkip(TokenType.OP_LPARENT)) {
                parseParameters();
                if (checkSkip(TokenType.OP_RPARENT)) {
                    if (checkSkip(TokenType.OP_COLON)) {
                        parseType();
                        if (checkSkip(TokenType.OP_ASSIGN)) {
                            parseExpression();
                        }
                        else
                            Report.error(symbol.position, "syntax error: expected \"=\", got " + symbol.lexeme);
                    }
                    else
                        Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
                }
                else
                    Report.error(symbol.position, "syntax error: expected \")\", got " + symbol.lexeme);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"(\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
    }

    private void parseType() {
        if (checkSkip(TokenType.IDENTIFIER)) {
            dump("type -> identifier");
        }
        else if (checkSkip(TokenType.AT_LOGICAL)) {
            dump("type -> logical");
        }
        else if (checkSkip(TokenType.AT_INTEGER)) {
            dump("type -> integer");
        }
        else if (checkSkip(TokenType.AT_STRING)) {
            dump("type -> string");
        }
        else if (checkSkip(TokenType.KW_ARR)) {
            dump("type -> arr [ int_const ] type");
            if (checkSkip(TokenType.OP_LBRACKET)) {
                if (checkSkip(TokenType.C_INTEGER)) {
                    if (checkSkip(TokenType.OP_RBRACKET)) {
                        parseType();
                    }
                    else
                        Report.error(symbol.position, "syntax error: expected \"]\", got " + symbol.lexeme);
                }
                else
                    Report.error(symbol.position, "syntax error: expected integer constant, got " + symbol.lexeme);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"[\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: unexpected type");
    }

    private void parseParameters() {
        dump("parameters -> parameter parameters1");
        parseParameter();
        parseParameters1();
    }

    private void parseParameters1() {
        if (checkSkip(TokenType.OP_COMMA)) {
            dump("parameters1 -> , parameters");
            parseParameters();
        }
        else
            dump("parameters1 -> e");
    }

    private void parseParameter() {
        if (checkSkip(TokenType.IDENTIFIER)) {
            dump("parameter -> identifier : type");
            if (checkSkip(TokenType.OP_COLON)) {
                parseType();
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
    }

    private void parseExpression() {
        dump("expression -> logical_ior_expression expression1");
        parseLogicalIorExpression();
        parseExpression1();
    }

    private void parseExpression1() {
        if (checkSkip(TokenType.OP_LBRACE)) {
            dump("expression1 -> { where definitions }");
            if (checkSkip(TokenType.KW_WHERE)) {
                parseDefinitions();
                if (!checkSkip(TokenType.OP_RBRACE))
                    Report.error(symbol.position, "syntax error: expected \"}\", got " + symbol.lexeme);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"where\", got " + symbol.lexeme);
        }
        else
            dump("expression1 -> e");
    }

    private void parseLogicalIorExpression() {
        dump("logical_ior_expression -> logical_and_expression logical_ior_expression1");
        parseLogicalAndExpression();
        parseLogicalIorExpression1();
    }

    private void parseLogicalIorExpression1() {
        if (checkSkip(TokenType.OP_OR)) {
            dump("logical_ior_expression1 -> | logical_ior_expression");
            parseLogicalIorExpression();
        }
        else
            dump("logical_ior_expression1 -> e");
    }

    private void parseLogicalAndExpression() {
        dump("logical_and_expression -> compare_expression logical_and_expression1");
        parseCompareExpression();
        parseLogicalAndExpression1();
    }

    private void parseLogicalAndExpression1() {
        if (checkSkip(TokenType.OP_AND)) {
            dump("logical_and_expression1 -> & logical_and_expression");
            parseLogicalAndExpression();
        }
        else
            dump("logical_and_expression1 -> e");
    }

    private void parseCompareExpression() {
        dump("compare_expression -> additive_expression compare_expression1");
        parseAdditiveExpression();
        parseCompareExpression1();
    }

    private void parseCompareExpression1() {
        if (checkSkip(TokenType.OP_EQ)) {
            dump("compare_expression1 -> == additive_expression");
            parseAdditiveExpression();
        }
        else if (checkSkip(TokenType.OP_NEQ)) {
            dump("compare_expression1 -> != additive_expression");
            parseAdditiveExpression();
        }
        else if (checkSkip(TokenType.OP_LEQ)) {
            dump("compare_expression1 -> <= additive_expression");
            parseAdditiveExpression();
        }
        else if (checkSkip(TokenType.OP_GEQ)) {
            dump("compare_expression1 -> >= additive_expression");
            parseAdditiveExpression();
        }
        else if (checkSkip(TokenType.OP_LT)) {
            dump("compare_expression1 -> < additive_expression");
            parseAdditiveExpression();
        }
        else if (checkSkip(TokenType.OP_GT)) {
            dump("compare_expression1 -> > additive_expression");
            parseAdditiveExpression();
        }
        else
            dump("compare_expression1 -> e");
    }

    private void parseAdditiveExpression() {
        dump("additive_expression -> multiplicative_expression additive_expression1");
        parseMultiplicativeExpression();
        parseAdditiveExpression1();
    }

    private void parseAdditiveExpression1() {
        if (checkSkip(TokenType.OP_ADD)) {
            dump("additive_expression1 -> + additive_expression");
            parseAdditiveExpression();
        }
        else if (checkSkip(TokenType.OP_SUB)) {
            dump("additive_expression1 -> - additive_expression");
            parseAdditiveExpression();
        }
        else
            dump("additive_expression1 -> e");
    }

    private void parseMultiplicativeExpression() {
        dump("multiplicative_expression -> prefix_expression multiplicative_expression1");
        parsePrefixExpression();
        parseMultiplicativeExpression1();
    }

    private void parseMultiplicativeExpression1() {
        if (checkSkip(TokenType.OP_MUL)) {
            dump("multiplicative_expression1 -> * multiplicative_expression");
            parseMultiplicativeExpression();
        }
        else if (checkSkip(TokenType.OP_DIV)) {
            dump("multiplicative_expression1 -> / multiplicative_expression");
            parseMultiplicativeExpression();
        }
        else if (checkSkip(TokenType.OP_MOD)) {
            dump("multiplicative_expression1 -> % multiplicative_expression");
            parseMultiplicativeExpression();
        }
        else
            dump("multiplicative_expression1 -> e");
    }

    private void parsePrefixExpression() {
        if (checkSkip(TokenType.OP_ADD)) {
            dump("prefix_expression -> + prefix_expression");
            parsePrefixExpression();
        }
        else if (checkSkip(TokenType.OP_SUB)) {
            dump("prefix_expression -> - prefix_expression");
            parsePrefixExpression();
        }
        else if (checkSkip(TokenType.OP_NOT)) {
            dump("prefix_expression -> ! prefix_expression");
            parsePrefixExpression();
        }
        else {
            dump("prefix_expression -> postfix_expression");
            parsePostfixExpression();
        }
    }

    private void parsePostfixExpression() {
        dump("postfix_expression -> atom_expression postfix_expression1");
        parseAtomExpression();
        parsePostfixExpression1();
    }

    private void parsePostfixExpression1() {
        if (checkSkip(TokenType.OP_LBRACKET)) {
            parseExpression();
            if (checkSkip(TokenType.OP_RBRACKET)) {
                parsePostfixExpression1();
            }
            else
                Report.error(symbol.position, "syntax error: expected \"]\", got " + symbol.lexeme);
        }
        else
            dump("postfix_expression1 -> e");
    }

    private void parseAtomExpression() {
        if (checkSkip(TokenType.C_LOGICAL)) {
            dump("atom_expression -> log_constant");
        }
        else if (checkSkip(TokenType.C_INTEGER)) {
            dump("atom_expression -> int_constant");
        }
        else if (checkSkip(TokenType.C_STRING)) {
            dump("atom_expression -> str_constant");
        }
        else if (checkSkip(TokenType.OP_LPARENT)) {
            dump("atom_expression -> ( expressions )");
            parseExpressions();
            if (!checkSkip(TokenType.OP_RPARENT))
                Report.error(symbol.position, "syntax error: expected \")\", got " + symbol.lexeme);
        }
        else if (checkSkip(TokenType.OP_LBRACE)) {
            dump("atom_expression -> { atom_expression1 }");
            parseAtomExpression1();
            if (!checkSkip(TokenType.OP_RBRACE))
                Report.error(symbol.position, "syntax error: expected \"}\", got " + symbol.lexeme);
        }
        else if (checkSkip(TokenType.IDENTIFIER)) {
            dump("atom_expression -> identifier atom_expression3");
            parseAtomExpression3();
        }
        else
            Report.error(symbol.position, "syntax error: unexpected expression");
    }

    private void parseAtomExpression1() {
        if (checkSkip(TokenType.KW_IF)) {
            dump("atom_expression1 -> if expression then expression atom_expression2");
            parseExpression();
            if (checkSkip(TokenType.KW_THEN)) {
                parseExpression();
                parseAtomExpression2();
            }
            else
                Report.error(symbol.position, "syntax error: expected \"then\", got " + symbol.lexeme);
        }
        else if (checkSkip(TokenType.KW_WHILE)) {
            dump("atom_expression1 -> while expression : expression");
            parseExpression();
            if (checkSkip(TokenType.OP_COLON)) {
                parseExpression();
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else if (checkSkip(TokenType.KW_FOR)) {
            dump("atom_expression1 -> for identifier = expression , expression , expression : expression");
            if (checkSkip(TokenType.IDENTIFIER)) {
                if (checkSkip(TokenType.OP_ASSIGN)) {
                    parseExpression();
                    if (checkSkip(TokenType.OP_COMMA)) {
                        parseExpression();
                        if (checkSkip(TokenType.OP_COMMA)) {
                            parseExpression();
                            if (checkSkip(TokenType.OP_COLON)) {
                                parseExpression();
                            }
                            else
                                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
                        }
                        else
                            Report.error(symbol.position, "syntax error: expected \",\", got " + symbol.lexeme);
                    }
                    else
                        Report.error(symbol.position, "syntax error: expected \",\", got " + symbol.lexeme);
                }
                else
                    Report.error(symbol.position, "syntax error: expected \"=\", got " + symbol.lexeme);
            }
            else
                Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
        }
        else {
            dump("atom_expression1 -> expression = expression");
            parseExpression();
            if (checkSkip(TokenType.OP_ASSIGN)) {
                parseExpression();
            }
            else
                Report.error(symbol.position, "syntax error: expected \"=\", got " + symbol.lexeme);
        }
    }

    private void parseAtomExpression2() {
        if (checkSkip(TokenType.KW_ELSE)) {
            parseExpression();
        }
        else
            dump("atom_expression2 -> e");
    }

    private void parseAtomExpression3() {
        if (checkSkip(TokenType.OP_LPARENT)) {
            parseExpressions();
            if (!checkSkip(TokenType.OP_RPARENT))
                Report.error(symbol.position, "syntax error: expected \")\", got " + symbol.lexeme);
        }
        else
            dump("atom_expression3 -> e");
    }

    private void parseExpressions() {
        dump("expressions -> expression expressions1");
        parseExpression();
        parseExpressions1();
    }

    private void parseExpressions1() {
        if (checkSkip(TokenType.OP_COMMA)) {
            dump("expressions1 -> , expressions");
            parseExpressions();
        }
        else
            dump("expressions1 -> e");
    }

    /**
     * Izpiše produkcijo na izhodni tok.
     */
    private void dump(String production) {
        if (productionsOutputStream.isPresent()) {
            productionsOutputStream.get().println(production);
        }
    }
}
