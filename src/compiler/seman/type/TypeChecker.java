/**
 * @ Author: turk
 * @ Description: Preverjanje tipov.
 */

package compiler.seman.type;

import static common.RequireNonNull.requireNonNull;

import common.Report;
import compiler.common.Visitor;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.*;
import compiler.seman.common.NodeDescription;
import compiler.seman.type.type.Type;

import java.util.ArrayList;
import java.util.HashSet;

public class TypeChecker implements Visitor {
    /**
     * Opis vozlišč in njihovih definicij.
     */
    private final NodeDescription<Def> definitions;

    /**
     * Opis vozlišč, ki jim priredimo podatkovne tipe.
     */
    private NodeDescription<Type> types;

    /**
     * Množica, kamor se shranjujejo definicije tipov, ki se trenutno obdelujejo.
     * Če se torej zgodi, da je nek typeDef že dodan, ko medtem rekurzivno pridemo do iste definicije typeDef,
     * potem pomeni, da je prišlo do cikla, zato javi napako.
     */
    private HashSet<TypeDef> typeRecord;

    public TypeChecker(NodeDescription<Def> definitions, NodeDescription<Type> types) {
        requireNonNull(definitions, types);
        this.definitions = definitions;
        this.types = types;
        this.typeRecord = new HashSet<>();
    }

    @Override
    public void visit(Call call) {
        // Pridobi definicijo funkcije
        var def = definitions.valueFor(call);
        if (def.isEmpty()) {
            Report.error(call.position, "semantic error: function " + call.name + " does not exist");
            return;
        }

        // Preveri ali je dejansko FunDef (če ne potem Java teži v nadaljevanju)
        if (!(def.get() instanceof FunDef funDef)) {
            Report.error(call.position, "semantic error: " + def.get().name + " is not a function");
            return;
        }

        // Preveri, ali ima klic funkcije ustrezne elemente (število le teh in njihovi tipi)
        if (funDef.parameters.size() != call.arguments.size()) {
            Report.error(call.position, "semantic error: function call does not have the same amount of arguments as there are parameters in function " + funDef.name);
            return;
        }

        // Preveri enakost tipov med isto ležečim parom argumentov in parametrov
        for (int i = 0; i < call.arguments.size(); i++) {
            // Pridobi tip parametra
            var paramType = types.valueFor(funDef.parameters.get(i));
            if (paramType.isEmpty()) {
                funDef.parameters.get(i).accept(this);
                paramType = types.valueFor(funDef.parameters.get(i));
                if (paramType.isEmpty()) {
                    Report.error(funDef.parameters.get(i).position, "semantic error: unable to calculate type for parameter");
                    return;
                }
            }
            // Pridobi tip argumenta
            var argType = types.valueFor(call.arguments.get(i));
            if (argType.isEmpty()) {
                call.arguments.get(i).accept(this);
                argType = types.valueFor(call.arguments.get(i));
                if (argType.isEmpty()) {
                    Report.error(call.arguments.get(i).position, "semantic error: unable to calculate type for argument");
                    return;
                }
            }
            // Primerjaj enakost para
            if (!paramType.get().equals(argType.get()))
                Report.error(call.arguments.get(i).position, "semantic error: type mismatch, expected " + paramType.get() + ", got " + argType.get());
        }

        // Če so argumenti ustrezni, potem je tip klica funkcije enak tipu rezultata funkcije
        var returnType = types.valueFor(funDef.type);
        if (returnType.isEmpty()) {
            funDef.type.accept(this);
            returnType = types.valueFor(funDef.type);
            if (returnType.isEmpty()) {
                Report.error(funDef.type.position, "semantic error: unable to calculate type for function return");
                return;
            }
        }
        types.store(call, returnType.get());
    }

    @Override
    public void visit(Binary binary) {
        // Pridobi tip prvega/levega izraza (left expr)
        var type1 = types.valueFor(binary.left);
        if (type1.isEmpty()) {
            binary.left.accept(this);       // Če še ni izračunan, ga rekurzivno izračunaj
            type1 = types.valueFor(binary.left);
            if (type1.isEmpty()) {
                Report.error(binary.left.position, "semantic error: unable to calculate expression type");
                return;
            }
        }
        // Pridobi tip drugega/desnega izraza (right expr)
        var type2 = types.valueFor(binary.right);
        if (type2.isEmpty()) {
            binary.right.accept(this);       // Če še ni izračunan, ga rekurzivno izračunaj
            type2 = types.valueFor(binary.right);
            if (type2.isEmpty()) {
                Report.error(binary.right.position, "semantic error: unable to calculate expression type");
                return;
            }
        }

        // Če je operator ARR ("expr1[expr2]"), je tip binarnega izraza enak tipu elementa v tabeli
        if (binary.operator == Binary.Operator.ARR && type1.get().asArray().isPresent() && type2.get().isInt()) {
            types.store(binary, type1.get().asArray().get().type);
        }
        // Če je znak "|" ali "&"
        else if (binary.operator.isAndOr()) {
            if (type1.get().isLog() && type2.get().isLog())
                types.store(binary, new Type.Atom(Type.Atom.Kind.LOG));
            else
                Report.error(binary.position, "semantic error: invalid types for operator " + binary.operator);

        }
        // Obe strani morata biti enakega atomarnega tipa
        else if (type1.get().isAtom() && type1.get().equals(type2.get())) {
            // Preveri, da ta tip slučajno ni void
            if (type1.get().equals(new Type.Atom(Type.Atom.Kind.VOID))) {
                Report.error(binary.position, "semantic error: cannot use operators with void types");
                return;
            }

            // Če se prireja
            if (binary.operator == Binary.Operator.ASSIGN) {
                types.store(binary, type1.get());
            }
            // Če sta oba tipa LOGICAL, potem je končni tip sigurno LOGICAL (razen pri računskih operatorjih je napaka)
            else if (type1.get().isLog() && type2.get().isLog()) {
                switch (binary.operator) {
                    case ADD, SUB, MUL, DIV, MOD ->
                        Report.error(binary.position, "semantic error: cannot calculate with LOGICAL types");
                    default -> types.store(binary, new Type.Atom(Type.Atom.Kind.INT));
                }
            }
            // Če sta oba tipa INTEGER, je lahko končni tip INTEGER ali LOGICAL (odvisno od operatorjev)
            else if (type1.get().isInt() && type2.get().isInt()) {
                switch (binary.operator) {
                    case ADD, SUB, MUL, DIV, MOD -> types.store(binary, new Type.Atom(Type.Atom.Kind.INT));
                    case AND, OR ->
                        Report.error(binary.position, "semantic error: cannot compare with INTEGER types");
                    default -> types.store(binary, new Type.Atom(Type.Atom.Kind.LOG));
                }
            }
        }
        // Če se kombinacija tipov in operatorjev ne razreši, so tipi napačni
        else
            Report.error(binary.position, "semantic error: incompatible types for given operation");
    }

    @Override
    public void visit(Block block) {
        // Izračunaj tipe v vseh izrazih
        for (var expr : block.expressions)
            expr.accept(this);

        // Tip bloka je tip zadnjega izraza
        var lastExpr = block.expressions.get(block.expressions.size()-1);
        var type = types.valueFor(lastExpr);

        if (type.isPresent())
            types.store(block, type.get());
        else
            Report.error(lastExpr.position, "semantic error: unable to calculate type for expression");
    }

    @Override
    public void visit(For forLoop) {
        // Izračunaj tip spremenljivke (counter)
        var counterType = types.valueFor(forLoop.counter);
        if (counterType.isEmpty()) {
            forLoop.counter.accept(this);
            counterType = types.valueFor(forLoop.counter);
            if (counterType.isEmpty()) {
                Report.error(forLoop.counter.position, "semantic error: unable to calculate type for " + forLoop.counter.name);
                return;
            }
        }
        // Če spremenljivka ni tipa INT, vrni napako
        if (!counterType.get().isInt()) {
            Report.error(forLoop.counter.position, "semantic error: type mismatch, expected " + Type.Atom.Kind.INT + ", got " + counterType.get());
            return;
        }

        // Izračunaj in preveri tipe vseh naslednjih izrazov
        ArrayList<Expr> expressions = new ArrayList<>();
        expressions.add(forLoop.low);
        expressions.add(forLoop.high);
        expressions.add(forLoop.step);

        for (var expr : expressions) {
            // Izračunaj tip izraza
            var exprType = types.valueFor(expr);
            if (exprType.isEmpty()) {
                expr.accept(this);
                exprType = types.valueFor(expr);
                if (exprType.isEmpty()) {
                    Report.error(expr.position, "semantic error: unable to calculate expression type");
                    return;
                }
            }
            // Če izraz ni tipa INT, vrni napako
            if (!exprType.get().isInt()) {
                Report.error(expr.position, "semantic error: type mismatch, expected " + Type.Atom.Kind.INT + ", got " + exprType.get());
                return;
            }
        }
        // Izračuna še tip v telesu zanke
        forLoop.body.accept(this);

        // Če je prestalo vse teste, potem so tipi ustrezni
        types.store(forLoop, new Type.Atom(Type.Atom.Kind.VOID));   // For zanka je tipa VOID
    }

    @Override
    public void visit(Name name) {
        // Prvo preveri ali definicija sploh obstaja
        var def = definitions.valueFor(name);
        if (def.isEmpty()) {
            Report.error(name.position, "semantic error: cannot resolve " + name.name);
            return;
        }

        // Če tip še ni izračunan, ga naračunaj
        var type = types.valueFor(def.get());
        if (type.isEmpty()) {
            def.get().accept(this);       // Izračunaj tip
            type = types.valueFor(def.get());   // Poglej, ali za ta tip sedaj obstaja izračunan tip
            if (type.isEmpty()) {
                Report.error(name.position, "semantic error: unable calculate type for " + name.name);
                return;
            }
        }
        types.store(name, type.get());  // Dodaj v tabelo tipov
    }

    @Override
    public void visit(IfThenElse ifThenElse) {
        // Izračunaj tip pogoja if stavka
        var conditionType = types.valueFor(ifThenElse.condition);
        if (conditionType.isEmpty()) {
            ifThenElse.condition.accept(this);
            conditionType = types.valueFor(ifThenElse.condition);
            if (conditionType.isEmpty()) {
                Report.error(ifThenElse.condition.position, "semantic error: unable to calculate condition type");
                return;
            }
        }

        // Izračunaj tipe za ostale izraze (izraza po then in else)
        if (types.valueFor(ifThenElse.thenExpression).isEmpty())
            ifThenElse.thenExpression.accept(this);

        // Ta ni nujno vedno prisoten
        if (ifThenElse.elseExpression.isPresent() && types.valueFor(ifThenElse.elseExpression.get()).isEmpty())
            ifThenElse.elseExpression.get().accept(this);

        // Če je pogoj tipa LOG, je to sprejemljiv izraz, sicer vrni napako
        if (conditionType.get().isLog())
            types.store(ifThenElse, new Type.Atom(Type.Atom.Kind.VOID));
        else
            Report.error(ifThenElse.condition.position, "semantic error: type mismatch, expected " + Type.Atom.Kind.LOG + ", got " + conditionType.get());
    }

    @Override
    public void visit(Literal literal) {
        Type.Atom.Kind kind = Type.Atom.Kind.VOID;  // Neka začetna vrednost, da se ne pritožuje za null
        switch(literal.type) {
            case INT -> kind = Type.Atom.Kind.INT;
            case STR -> kind = Type.Atom.Kind.STR;
            case LOG -> kind = Type.Atom.Kind.LOG;
            default -> Report.error(literal.position, "semantic error: unknown literal type");
        }
        types.store(literal, new Type.Atom(kind));
    }

    @Override
    public void visit(Unary unary) {
        // Izračunaj tip izraza (expr)
        var exprType = types.valueFor(unary.expr);
        if (exprType.isEmpty()) {
            unary.expr.accept(this);
            exprType = types.valueFor(unary.expr);
            if (exprType.isEmpty()) {
                Report.error(unary.expr.position, "semantic error: unable to calculate expression type");
                return;
            }
        }

        // Če je poleg izraza "!", gre za negacijo, kar je tipa LOG, sicer gre za "+" ali "-", kar je tipa INT
        if (unary.operator == Unary.Operator.NOT) {
            if (exprType.get().isLog())
                types.store(unary, exprType.get());
            else
                Report.error(unary.expr.position, "semantic error: type mismatch, expected log, got " + exprType.get());
        } else {
            if (exprType.get().isInt())
                types.store(unary, exprType.get());
            else
                Report.error(unary.expr.position, "semantic error: type mismatch, expected int, got " + exprType.get());
        }
    }

    @Override
    public void visit(While whileLoop) {
        // Izračunaj tip pogoja zanke
        var conditionType = types.valueFor(whileLoop.condition);
        if (conditionType.isEmpty()) {
            whileLoop.condition.accept(this);
            conditionType = types.valueFor(whileLoop.condition);
            if (conditionType.isEmpty()) {
                Report.error(whileLoop.condition.position, "semantic error: unable to calculate condition type");
                return;
            }
        }
        // Izračunaj tip telesa zanke po potrebi
        if (types.valueFor(whileLoop.body).isEmpty())
            whileLoop.body.accept(this);

        // Če je pogoj tipa LOG, je to sprejemljiv izraz, sicer vrni napako
        if (conditionType.get().isLog())
            types.store(whileLoop, new Type.Atom(Type.Atom.Kind.VOID));
        else
            Report.error(whileLoop.condition.position, "semantic error: type mismatch, expected " + Type.Atom.Kind.LOG + ", got " + conditionType.get());
    }

    @Override
    public void visit(Where where) {
        // Izračunaj tip izraza (expr)
        var exprType = types.valueFor(where.expr);
        if (exprType.isEmpty()) {
            where.expr.accept(this);
            exprType = types.valueFor(where.expr);
            if (exprType.isEmpty()) {
                Report.error(where.expr.position, "semantic error: unable to calculate expression type");
                return;
            }
        }
        where.defs.accept(this);          // Izračunaj vse tipe definicij znotraj where stavka
        types.store(where, exprType.get());     // Shrani tip where stavka kot tip izraza (expr)
    }

    @Override
    public void visit(Defs defs) {
        // Za vsako definicijo izvedi računanje tipov
        for (var def : defs.definitions)
            def.accept(this);
    }

    @Override
    public void visit(FunDef funDef) {
        ArrayList<Type> parameters = new ArrayList<>();

        // Preveri parametre in po možnosti izračunaj njihove tipe in dodaj te tipe v seznam
        for (var parameter : funDef.parameters) {
            // Preveri ali je ta tip parametra že izračunan, če ni ga pa naračunaj
            var type = types.valueFor(parameter.type);
            if (type.isEmpty()) {
                parameter.type.accept(this);    // Izračunaj tip
                type = types.valueFor(parameter.type);
                if (type.isEmpty()) {
                    Report.error(parameter.position, "semantic error: unable to calculate type for parameter " + parameter.name);
                    return;
                }
            }
            types.store(parameter, type.get());     // Shrani tip parametra
            parameters.add(type.get());
        }

        // Izračunaj tip rezultata
        var returnType = types.valueFor(funDef.type);
        if (returnType.isEmpty()) {
            funDef.type.accept(this);
            returnType = types.valueFor(funDef.type);
            if (returnType.isEmpty()) {
                Report.error(funDef.type.position, "semantic error: unable to calculate return type for function " + funDef.name);
                return;
            }
        }
        types.store(funDef.type, returnType.get());

        // Izračunaj tip izraza (body)
        var bodyType = types.valueFor(funDef.body);
        if (bodyType.isEmpty()) {
            funDef.body.accept(this);
            bodyType = types.valueFor(funDef.body);
            if (bodyType.isEmpty()) {
                Report.error(funDef.type.position, "semantic error: unable to calculate body type for function " + funDef.name);
                return;
            }
        }
        types.store(funDef.body, bodyType.get());

        // Na koncu preveri, ali se tip rezultata in izraza ujemata, če se potem je vse ok, sicer vrni napako
        if (returnType.get().equals(bodyType.get()))
            types.store(funDef, new Type.Function(parameters, returnType.get()));
        else
            Report.error(funDef.position, "semantic error: type mismatch, expected " + returnType.get() + ", got " + bodyType.get());
    }

    @Override
    public void visit(TypeDef typeDef) {
        // Preveri, ali je prišlo do cikla
        if (typeRecord.contains(typeDef)) {
            Report.error(typeDef.position, "semantic error: a cycle has occurred while calculating type of " + typeDef.name);
            return;
        }
        // Sicer se definicija doda v množico
        typeRecord.add(typeDef);

        // Pridobi tip. Če še ni izračunan, ga pa naračunaj
        var type = types.valueFor(typeDef.type);
        if (type.isEmpty()) {
            typeDef.type.accept(this);
            type = types.valueFor(typeDef.type);
            if (type.isEmpty()) {
                Report.error(typeDef.position, "semantic error: unable to calculate type for " + typeDef.name);
                return;
            }
        }
        types.store(typeDef, type.get());
        typeRecord.remove(typeDef);
    }

    @Override
    public void visit(VarDef varDef) {
        var type = types.valueFor(varDef.type);     // Ali je ta tip že izračunan

        // Če ni, ga izračunaj
        if (type.isEmpty()) {
            varDef.type.accept(this);
            type = types.valueFor(varDef.type);
            if (type.isEmpty()) {
                Report.error(varDef.position, "semantic error: unable to calculate type for " + varDef.name);
                return;
            }
        }
        types.store(varDef, type.get());
    }

    @Override
    public void visit(Parameter parameter) {
        var type = types.valueFor(parameter.type);

        // Če tip parametra še ni izračunan, ga naračunaj
        if (type.isEmpty()) {
            parameter.type.accept(this);        // Izračunaj tip
            type = types.valueFor(parameter.type);    // Preveri njegov obstoj
            if (type.isEmpty()) {
                Report.error(parameter.position, "semantic error: unable to calculate type for " + parameter.name);
                return;
            }
        }
        types.store(parameter, type.get());     // Shrani tip za ta parameter
    }

    @Override
    public void visit(Array array) {
        var type = types.valueFor(array.type);

        // Če tip tabele še ni izračunan, ga naračunaj
        if (type.isEmpty()) {
            array.type.accept(this);    // izračuna tip tabele
            type = types.valueFor(array.type);
            if (type.isEmpty()) {
                Report.error(array.type.position, "semantic error: unable to calculate array type");
                return;
            }
        }
        types.store(array, new Type.Array(array.size, type.get()));
    }

    @Override
    public void visit(Atom atom) {
        Type.Atom.Kind kind = Type.Atom.Kind.VOID;  // neka začetna vrednost, da se ne pritožuje za null
        switch(atom.type) {
            case INT -> kind = Type.Atom.Kind.INT;
            case STR -> kind = Type.Atom.Kind.STR;
            case LOG -> kind = Type.Atom.Kind.LOG;
            default -> Report.error(atom.position, "semantic error: unknown atom type");
        }
        types.store(atom, new Type.Atom(kind));
    }

    @Override
    public void visit(TypeName name) {
        // prvo preveri ali definicija sploh obstaja
        var def = definitions.valueFor(name);
        if (def.isEmpty()) {
            Report.error(name.position, "semantic error: cannot resolve " + name.identifier);
            return;
        }

        // Če tip še ni izračunan, ga naračunaj
        var type = types.valueFor(def.get());
        if (type.isEmpty()) {
            def.get().accept(this);       // izračunaj tip
            type = types.valueFor(def.get());   // poglej, ali za ta tip sedaj obstaja izračunan tip
            if (type.isEmpty()) {
                Report.error(name.position, "semantic error: cannot calculate type of " + name.identifier);
                return;
            }
        }
        types.store(name, type.get());  // dodaj v tabelo tipov
    }
}
