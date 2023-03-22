/**
 * @Author: turk
 * @Description: Sintaksni analizator.
 */

package compiler.parser;

import static common.RequireNonNull.requireNonNull;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import common.Report;
import compiler.lexer.Position;
import compiler.lexer.Symbol;
import compiler.lexer.TokenType;
import compiler.parser.ast.Ast;
import compiler.parser.ast.def.*;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.Array;
import compiler.parser.ast.type.Atom;
import compiler.parser.ast.type.Type;
import compiler.parser.ast.type.TypeName;

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
    public Ast parse() {
        return parseSource();
    }

    private Ast parseSource() {
        if (checkSkip(TokenType.EOF))
            Report.error("syntax error: empty file");
        dump("source -> definitions");
        var definitions = parseDefinitions();

        if (!checkSkip(TokenType.EOF))
            Report.error(symbol.position,"syntax error: expected EOF, got " + symbol.lexeme);
        return definitions;
    }

    private Defs parseDefinitions() {
        dump("definitions -> definition definitions1");
        var startLocation = symbol.position.start;
        List<Def> definitions = new ArrayList<>();
        definitions.add(parseDefinition());
        definitions = parseDefinitions1(definitions);
        var endLocaton = definitions.get(definitions.size()-1).position.end;
        return new Defs(new Position(startLocation, endLocaton), definitions);
    }

    private List<Def> parseDefinitions1(List<Def> definitions) {
        if (checkSkip(TokenType.OP_SEMICOLON)) {
            dump("definitions1 -> ; definition definitions1");
            definitions.add(parseDefinition());
            return parseDefinitions1(definitions);
        }
        else {
            dump("definitions1 -> e");
            return definitions;
        }
    }

    private Def parseDefinition() {
        var startLocation = symbol.position.start;
        if (checkSkip(TokenType.KW_TYP)) {
            dump("definition -> type_definition");
            return parseTypeDefinition(startLocation);
        }
        else if (checkSkip(TokenType.KW_VAR)) {
            dump("definition -> variable_definition");
            return parseVariableDefinition(startLocation);
        }
        else if (checkSkip(TokenType.KW_FUN)) {
            dump("definition -> function_definition");
            return parseFunctionDefinition(startLocation);
        }
        else {
            Report.error(symbol.position, "syntax error: unexpected definition");
            return null;
        }
    }

    private TypeDef parseTypeDefinition(Position.Location startLocation) {
        dump("type_definition -> typ identifier : type");
        var name = symbol.lexeme;
        if (checkSkip(TokenType.IDENTIFIER)) {
            if (checkSkip(TokenType.OP_COLON)) {
                var type = parseType();
                return new TypeDef(new Position(startLocation, type.position.end), name, type);
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
        return null;
    }

    private VarDef parseVariableDefinition(Position.Location startLocation) {
        dump("variable_definition -> var identifier : type");
        var name = symbol.lexeme;
        if (checkSkip(TokenType.IDENTIFIER)) {
            if (checkSkip(TokenType.OP_COLON)) {
                var type = parseType();
                return new VarDef(new Position(startLocation, type.position.end), name, type);
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
        return null;
    }

    private FunDef parseFunctionDefinition(Position.Location startLocation) {
        dump("function_definition -> fun identifier ( parameters ) : type = expression");
        var name = symbol.lexeme;
        if (checkSkip(TokenType.IDENTIFIER)) {
            if (checkSkip(TokenType.OP_LPARENT)) {
                var parameters = parseParameters();
                if (checkSkip(TokenType.OP_RPARENT)) {
                    if (checkSkip(TokenType.OP_COLON)) {
                        var type = parseType();
                        if (checkSkip(TokenType.OP_ASSIGN)) {
                            var body = parseExpression();
                            return new FunDef(new Position(startLocation, body.position.end), name, parameters, type, body);
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
        return null;
    }

    private Type parseType() {
        var symbolPosition = symbol.position;
        var symbolLexeme = symbol.lexeme;
        if (checkSkip(TokenType.IDENTIFIER)) {
            dump("type -> identifier");
            return new TypeName(symbolPosition, symbolLexeme);
        }
        else if (checkSkip(TokenType.AT_LOGICAL)) {
            dump("type -> logical");
            return Atom.LOG(symbolPosition);
        }
        else if (checkSkip(TokenType.AT_INTEGER)) {
            dump("type -> integer");
            return Atom.INT(symbolPosition);
        }
        else if (checkSkip(TokenType.AT_STRING)) {
            dump("type -> string");
            return Atom.STR(symbolPosition);
        }
        else if (checkSkip(TokenType.KW_ARR)) {
            dump("type -> arr [ int_const ] type");
            if (checkSkip(TokenType.OP_LBRACKET)) {
                var size = symbol.lexeme;
                if (checkSkip(TokenType.C_INTEGER)) {
                    if (checkSkip(TokenType.OP_RBRACKET)) {
                        var type = parseType();
                        return new Array(new Position(symbolPosition.start, type.position.end), Integer.parseInt(size), type);
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
        return null;
    }

    private List<FunDef.Parameter> parseParameters() {
        dump("parameters -> parameter parameters1");
        List<FunDef.Parameter> parameters = new ArrayList<>();
        parameters.add(parseParameter());
        return parseParameters1(parameters);
    }

    private List<FunDef.Parameter> parseParameters1(List<FunDef.Parameter> parameters) {
        if (checkSkip(TokenType.OP_COMMA)) {
            dump("parameters1 -> , parameter parameters1");
            parameters.add(parseParameter());
            return parseParameters1(parameters);
        }
        else {
            dump("parameters1 -> e");
            return parameters;
        }
    }

    private FunDef.Parameter parseParameter() {
        var startLocation = symbol.position.start;
        var name = symbol.lexeme;
        if (checkSkip(TokenType.IDENTIFIER)) {
            dump("parameter -> identifier : type");
            if (checkSkip(TokenType.OP_COLON)) {
                var type = parseType();
                return new FunDef.Parameter(new Position(startLocation, type.position.end), name, type);
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else
            Report.error(symbol.position, "syntax error: expected identifier, got " + symbol.lexeme);
        return null;
    }

    private Expr parseExpression() {
        dump("expression -> logical_ior_expression expression1");
        var startLocation = symbol.position.start;
        var expr = parseLogicalIorExpression();
        return parseExpression1(startLocation, expr);
    }

    private Expr parseExpression1(Position.Location startLocation, Expr expr) {
        if (checkSkip(TokenType.OP_LBRACE)) {
            dump("expression1 -> { where definitions }");
            if (checkSkip(TokenType.KW_WHERE)) {
                var defs = parseDefinitions();
                var endLocation = symbol.position.end;
                if (!checkSkip(TokenType.OP_RBRACE))
                    Report.error(symbol.position, "syntax error: expected \"}\", got " + symbol.lexeme);
                return new Where(new Position(startLocation, endLocation), expr, defs);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"where\", got " + symbol.lexeme);
        }
        else {
            dump("expression1 -> e");
            return expr;
        }
        return null;
    }

    private Expr parseLogicalIorExpression() {
        dump("logical_ior_expression -> logical_and_expression logical_ior_expression1");
        var startLocation = symbol.position.start;
        var left = parseLogicalAndExpression();
        return parseLogicalIorExpression1(startLocation, left);
    }

    private Expr parseLogicalIorExpression1(Position.Location startLocation, Expr left) {
        if (checkSkip(TokenType.OP_OR)) {
            dump("logical_ior_expression1 -> | logical_and_expression logical_ior_expression1");
            var right = parseLogicalAndExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.OR, right);
            return parseLogicalIorExpression1(startLocation, bin);
        }
        else
            dump("logical_ior_expression1 -> e");
        return left;
    }

    private Expr parseLogicalAndExpression() {
        dump("logical_and_expression -> compare_expression logical_and_expression1");
        var startLocation = symbol.position.start;
        var left = parseCompareExpression();
        return parseLogicalAndExpression1(startLocation, left);
    }

    private Expr parseLogicalAndExpression1(Position.Location startLocation, Expr left) {
        if (checkSkip(TokenType.OP_AND)) {
            dump("logical_and_expression1 -> & compare_expression logical_and_expression1");
            var right = parseCompareExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.AND, right);
            return parseLogicalAndExpression1(startLocation, bin);
        }
        else
            dump("logical_and_expression1 -> e");
        return left;
    }

    private Expr parseCompareExpression() {
        dump("compare_expression -> additive_expression compare_expression1");
        var startLocation = symbol.position.start;
        var left = parseAdditiveExpression();
        return parseCompareExpression1(startLocation, left);
    }

    private Expr parseCompareExpression1(Position.Location startLocation, Expr left) {
        if (checkSkip(TokenType.OP_EQ)) {
            dump("compare_expression1 -> == additive_expression");
            var right = parseAdditiveExpression();
            var endLocation = right.position.end;
            return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.EQ, right);
        }
        else if (checkSkip(TokenType.OP_NEQ)) {
            dump("compare_expression1 -> != additive_expression");
            var right = parseAdditiveExpression();
            var endLocation = right.position.end;
            return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.NEQ, right);
        }
        else if (checkSkip(TokenType.OP_LEQ)) {
            dump("compare_expression1 -> <= additive_expression");
            var right = parseAdditiveExpression();
            var endLocation = right.position.end;
            return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.LEQ, right);
        }
        else if (checkSkip(TokenType.OP_GEQ)) {
            dump("compare_expression1 -> >= additive_expression");
            var right = parseAdditiveExpression();
            var endLocation = right.position.end;
            return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.GEQ, right);
        }
        else if (checkSkip(TokenType.OP_LT)) {
            dump("compare_expression1 -> < additive_expression");
            var right = parseAdditiveExpression();
            var endLocation = right.position.end;
            return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.LT, right);
        }
        else if (checkSkip(TokenType.OP_GT)) {
            dump("compare_expression1 -> > additive_expression");
            var right = parseAdditiveExpression();
            var endLocation = right.position.end;
            return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.GT, right);
        }
        else
            dump("compare_expression1 -> e");
        return left;
    }

    private Expr parseAdditiveExpression() {
        dump("additive_expression -> multiplicative_expression additive_expression1");
        var startLocation = symbol.position.start;
        var left = parseMultiplicativeExpression();
        return parseAdditiveExpression1(startLocation, left);
    }

    private Expr parseAdditiveExpression1(Position.Location startLocation, Expr left) {
        if (checkSkip(TokenType.OP_ADD)) {
            dump("additive_expression1 -> + multiplicative_expression additive_expression1");
            var right = parseMultiplicativeExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.ADD, right);
            return parseAdditiveExpression1(startLocation, bin);
        }
        else if (checkSkip(TokenType.OP_SUB)) {
            dump("additive_expression1 -> - multiplicative_expression additive_expression1");
            var right = parseMultiplicativeExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.SUB, right);
            return parseAdditiveExpression1(startLocation, bin);
        }
        else
            dump("additive_expression1 -> e");
        return left;
    }

    private Expr parseMultiplicativeExpression() {
        dump("multiplicative_expression -> prefix_expression multiplicative_expression1");
        var startLocation = symbol.position.start;
        var left = parsePrefixExpression();
        return parseMultiplicativeExpression1(startLocation, left);
    }

    private Expr parseMultiplicativeExpression1(Position.Location startLocation, Expr left) {
        if (checkSkip(TokenType.OP_MUL)) {
            dump("multiplicative_expression1 -> * prefix_expression multiplicative_expression1");
            var right = parsePrefixExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.MUL, right);
            return parseMultiplicativeExpression1(startLocation, bin);
        }
        else if (checkSkip(TokenType.OP_DIV)) {
            dump("multiplicative_expression1 -> / prefix_expression multiplicative_expression1");
            var right = parsePrefixExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.DIV, right);
            return parseMultiplicativeExpression1(startLocation, bin);
        }
        else if (checkSkip(TokenType.OP_MOD)) {
            dump("multiplicative_expression1 -> % prefix_expression multiplicative_expression1");
            var right = parsePrefixExpression();
            var endLocation = right.position.end;
            var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.MOD, right);
            return parseMultiplicativeExpression1(startLocation, bin);
        }
        else
            dump("multiplicative_expression1 -> e");
        return left;
    }

    private Expr parsePrefixExpression() {
        var startLocation = symbol.position.start;
        if (checkSkip(TokenType.OP_ADD)) {
            dump("prefix_expression -> + prefix_expression");
            var expr = parsePrefixExpression();
            var endLocation = expr.position.end;
            return new Unary(new Position(startLocation, endLocation), expr, Unary.Operator.ADD);
        }
        else if (checkSkip(TokenType.OP_SUB)) {
            dump("prefix_expression -> - prefix_expression");
            var expr = parsePrefixExpression();
            var endLocation = expr.position.end;
            return new Unary(new Position(startLocation, endLocation), expr, Unary.Operator.SUB);
        }
        else if (checkSkip(TokenType.OP_NOT)) {
            dump("prefix_expression -> ! prefix_expression");
            var expr = parsePrefixExpression();
            var endLocation = expr.position.end;
            return new Unary(new Position(startLocation, endLocation), expr, Unary.Operator.NOT);
        }
        else {
            dump("prefix_expression -> postfix_expression");
            return parsePostfixExpression();
        }
    }

    private Expr parsePostfixExpression() {
        dump("postfix_expression -> atom_expression postfix_expression1");
        var startLocation = symbol.position.start;
        var left = parseAtomExpression();
        return parsePostfixExpression1(startLocation, left);
    }

    private Expr parsePostfixExpression1(Position.Location startLocation, Expr left) {
        if (checkSkip(TokenType.OP_LBRACKET)) {
            dump("postfix_expression1 -> [ expression ] postfix_expression1");
            var right = parseExpression();
            var endLocation = symbol.position.end;
            if (checkSkip(TokenType.OP_RBRACKET)) {
                var bin = new Binary(new Position(startLocation, endLocation), left, Binary.Operator.ARR, right);
                return parsePostfixExpression1(startLocation, bin);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"]\", got " + symbol.lexeme);
        }
        else
            dump("postfix_expression1 -> e");
        return left;
    }

    private Expr parseAtomExpression() {
        var symbolPosition = symbol.position;       // meant only for constants
        var symbolLexeme = symbol.lexeme;           // meant only for constants

        if (checkSkip(TokenType.C_LOGICAL)) {
            dump("atom_expression -> log_constant");
            return new Literal(symbolPosition, symbolLexeme, Atom.Type.LOG);
        }
        else if (checkSkip(TokenType.C_INTEGER)) {
            dump("atom_expression -> int_constant");
            return new Literal(symbolPosition, symbolLexeme, Atom.Type.INT);
        }
        else if (checkSkip(TokenType.C_STRING)) {
            dump("atom_expression -> str_constant");
            return new Literal(symbolPosition, symbolLexeme, Atom.Type.STR);
        }
        else if (checkSkip(TokenType.OP_LPARENT)) {
            dump("atom_expression -> ( expressions )");
            var listExpressions = parseExpressions();
            var endLocation = symbol.position.end;
            if (!checkSkip(TokenType.OP_RPARENT))
                Report.error(symbol.position, "syntax error: expected \")\", got " + symbol.lexeme);
            return new Block(new Position(symbolPosition.start, endLocation), listExpressions);
        }
        else if (checkSkip(TokenType.OP_LBRACE)) {
            dump("atom_expression -> { atom_expression1 }");
            var expr = parseAtomExpression1(symbolPosition.start);
            if (!checkSkip(TokenType.OP_RBRACE))
                Report.error(symbol.position, "syntax error: expected \"}\", got " + symbol.lexeme);
            return expr;
        }
        else if (checkSkip(TokenType.IDENTIFIER)) {
            dump("atom_expression -> identifier atom_expression3");
            return parseAtomExpression3(symbolPosition.start, new Name(symbolPosition, symbolLexeme));
        }
        else {
            Report.error(symbol.position, "syntax error: unexpected expression");
            return null;
        }
    }

    private Expr parseAtomExpression1(Position.Location startLocation) {
        if (checkSkip(TokenType.KW_IF)) {
            dump("atom_expression1 -> if expression then expression atom_expression2");
            var condition = parseExpression();
            if (checkSkip(TokenType.KW_THEN)) {
                var thenExpression = parseExpression();
                var endLocation = symbol.position.end;
                var ifThenElse = new IfThenElse(new Position(startLocation, endLocation), condition, thenExpression);
                return parseAtomExpression2(startLocation, ifThenElse);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"then\", got " + symbol.lexeme);
        }
        else if (checkSkip(TokenType.KW_WHILE)) {
            dump("atom_expression1 -> while expression : expression");
            var condition = parseExpression();
            if (checkSkip(TokenType.OP_COLON)) {
                var body = parseExpression();
                var endLocation = symbol.position.end;
                return new While(new Position(startLocation, endLocation), condition, body);
            }
            else
                Report.error(symbol.position, "syntax error: expected \":\", got " + symbol.lexeme);
        }
        else if (checkSkip(TokenType.KW_FOR)) {
            dump("atom_expression1 -> for identifier = expression , expression , expression : expression");
            var counter = new Name(symbol.position, symbol.lexeme);
            if (checkSkip(TokenType.IDENTIFIER)) {
                if (checkSkip(TokenType.OP_ASSIGN)) {
                    var low = parseExpression();
                    if (checkSkip(TokenType.OP_COMMA)) {
                        var high = parseExpression();
                        if (checkSkip(TokenType.OP_COMMA)) {
                            var step = parseExpression();
                            if (checkSkip(TokenType.OP_COLON)) {
                                var body = parseExpression();
                                var endLocation = symbol.position.end;
                                return new For(new Position(startLocation, endLocation), counter, low, high, step, body);
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
            var left = parseExpression();
            if (checkSkip(TokenType.OP_ASSIGN)) {
                var right = parseExpression();
                var endLocation = symbol.position.end;
                return new Binary(new Position(startLocation, endLocation), left, Binary.Operator.ASSIGN, right);
            }
            else
                Report.error(symbol.position, "syntax error: expected \"=\", got " + symbol.lexeme);
        }
        return null;
    }

    private Expr parseAtomExpression2(Position.Location startLocation, IfThenElse ifThenElse) {
        if (checkSkip(TokenType.KW_ELSE)) {
            var elseExpression = parseExpression();
            var endLocation = symbol.position.end;
            return new IfThenElse(new Position(startLocation, endLocation), ifThenElse.condition, ifThenElse.thenExpression, elseExpression);
        }
        else {
            dump("atom_expression2 -> e");
            return ifThenElse;
        }
    }

    private Expr parseAtomExpression3(Position.Location startLocation, Name name) {
        if (checkSkip(TokenType.OP_LPARENT)) {
            dump("atom_expression3 -> ( expressions )");
            var arguments = parseExpressions();
            var endLocation = symbol.position.end;
            if (!checkSkip(TokenType.OP_RPARENT))
                Report.error(symbol.position, "syntax error: expected \")\", got " + symbol.lexeme);
            return new Call(new Position(startLocation, endLocation), arguments, name.name);
        }
        else {
            dump("atom_expression3 -> e");
            return name;
        }
    }

    private List<Expr> parseExpressions() {
        dump("expressions -> expression expressions1");
        List<Expr> expressions = new ArrayList<>();
        expressions.add(parseExpression());
        return parseExpressions1(expressions);
    }

    private List<Expr> parseExpressions1(List<Expr> expressions) {
        if (checkSkip(TokenType.OP_COMMA)) {
            dump("expressions1 -> , expression expressions1");
            expressions.add(parseExpression());
            return parseExpressions1(expressions);
        }
        else {
            dump("expressions1 -> e");
            return expressions;
        }
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
