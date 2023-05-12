/**
 * @ Author: turk
 * @ Description: Preverjanje in razreševanje imen.
 */

package compiler.seman.name;

import static common.RequireNonNull.requireNonNull;

import common.Report;
import common.Constants;
import compiler.common.Visitor;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.*;
import compiler.seman.common.NodeDescription;
import compiler.seman.name.env.SymbolTable;
import compiler.seman.name.env.SymbolTable.DefinitionAlreadyExistsException;

public class NameChecker implements Visitor {
    /**
     * Opis vozlišč, ki jih povežemo z njihovimi
     * definicijami.
     */
    private NodeDescription<Def> definitions;
    private SymbolTable symbolTable;

    /**
     * Ustvari nov razreševalnik imen.
     */
    public NameChecker(NodeDescription<Def> definitions, SymbolTable symbolTable) {
        requireNonNull(definitions, symbolTable);
        this.definitions = definitions;
        this.symbolTable = symbolTable;
    }

    @Override
    public void visit(Call call) {
        boolean isStandardFunction = switch (call.name) {
            case Constants.printIntLabel,
                    Constants.seedLabel,
                    Constants.printStringLabel,
                    Constants.printLogLabel,
                    Constants.randIntLabel -> true;
            default -> false;
        };

        if (!isStandardFunction) {
            // definicija tipa
            var definition = symbolTable.definitionFor(call.name);

            // ce obstaja definicija z istim imenom v simbolni tabeli preveri ali je to dejansko definicija spremenljivke
            if (definition.isPresent())
                if (definition.get() instanceof FunDef)
                    definitions.store(call, definition.get());      // ime spremenljivke kaže na definicijo le-te
                else {
                    // za lepši izpis napake
                    var id = definition.get().name;
                    var defName = "unknown definition";
                    if (definition.get() instanceof VarDef || definition.get() instanceof Parameter)
                        defName = "variable \""+id+"\"";
                    else if (definition.get() instanceof TypeDef)
                        defName = "type \""+id+"\"";
                    Report.error(call.position, "semantic error: expected function, got " + defName);
                }
            else
                Report.error(call.position, "semantic error: undefined function \"" + call.name + "\"");
        }

        for (var argument : call.arguments)
            argument.accept(this);
    }

    @Override
    public void visit(Binary binary) {
        binary.left.accept(this);
        binary.right.accept(this);
    }

    @Override
    public void visit(Block block) {
        for (var expr : block.expressions)
            expr.accept(this);
    }

    @Override
    public void visit(For forLoop) {
        forLoop.counter.accept(this);
        forLoop.low.accept(this);
        forLoop.high.accept(this);
        forLoop.step.accept(this);
        forLoop.body.accept(this);
    }

    @Override
    public void visit(Name name) {
        var definition = symbolTable.definitionFor(name.name);     // definicija tipa

        // ce obstaja definicija z istim imenom v simbolni tabeli preveri ali je to dejansko definicija spremenljivke
        if (definition.isPresent())
            if (definition.get() instanceof VarDef || definition.get() instanceof Parameter)
                definitions.store(name, definition.get());      // ime spremenljivke kaže na definicijo le-te
            else {
                // za lepši izpis napake
                var id = definition.get().name;
                var defName = "unknown definition";
                if (definition.get() instanceof TypeDef)
                    defName = "type \""+id+"\"";
                else if (definition.get() instanceof FunDef)
                    defName = "function \""+id+"\"";
                Report.error(name.position, "semantic error: expected variable, got " + defName);
            }
        else
            Report.error(name.position, "semantic error: undefined variable \"" + name.name + "\"");
    }

    @Override
    public void visit(IfThenElse ifThenElse) {
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(expr -> expr.accept(this));
    }

    @Override
    public void visit(Literal literal) {
        // konstante so rešljive same po sebi
    }

    @Override
    public void visit(Unary unary) {
        unary.expr.accept(this);
    }

    @Override
    public void visit(While whileLoop) {
        whileLoop.condition.accept(this);
        whileLoop.body.accept(this);
    }

    @Override
    public void visit(Where where) {
        symbolTable.inNewScope(() -> {
            where.defs.accept(this);
            where.expr.accept(this);
        });
    }

    @Override
    public void visit(Defs defs) {
        // prvo se dodajo vse definicije v tabelo, saj morajo biti vidne celotnem vidnem območju
        for (var def : defs.definitions) {
            try {
                symbolTable.insert(def);
            } catch (DefinitionAlreadyExistsException e) {
                Report.error(def.position, "semantic error: definition already exists");
            }
        }

        // v drugem obhodu se pregleda desna stran definicije
        for (var def : defs.definitions)
            def.accept(this);
    }

    @Override
    public void visit(FunDef funDef) {
        // prvo se obdelajo samo tipi parametrov in tip rezultata
        for (var parameter : funDef.parameters)
            parameter.type.accept(this);
        funDef.type.accept(this);

        symbolTable.inNewScope(() -> {
            for (var parameter : funDef.parameters)
                parameter.accept(this);
            funDef.body.accept(this);
        });
    }

    @Override
    public void visit(TypeDef typeDef) {
        typeDef.type.accept(this);
    }

    @Override
    public void visit(VarDef varDef) {
        varDef.type.accept(this);
    }

    @Override
    public void visit(Parameter parameter) {
        try {
            symbolTable.insert(parameter);
        } catch (DefinitionAlreadyExistsException e) {
            Report.error(parameter.position, "semantic error: definition already exists");
        }
    }

    @Override
    public void visit(Array array) {
        array.type.accept(this);
    }

    @Override
    public void visit(Atom atom) {
        // atomarni tipi so rešljivi sami po sebi
    }

    @Override
    public void visit(TypeName name) {
        var definition = symbolTable.definitionFor(name.identifier);     // definicija tipa

        // ce obstaja definicija z istim imenom v simbolni tabeli preveri ali je to dejansko definicija tipa
        if (definition.isPresent())
            if (definition.get() instanceof TypeDef)
                definitions.store(name, definition.get());      // ime tipa kaže na definicijo tega tipa
            else {
                // za lepši izpis napake
                var id = definition.get().name;
                var defName = "unknown definition";
                if (definition.get() instanceof VarDef || definition.get() instanceof Parameter)
                    defName = "variable \""+id+"\"";
                else if (definition.get() instanceof FunDef)
                    defName = "function \""+id+"\"";
                Report.error(name.position, "semantic error: expected type, got " + defName);
            }
        else
            Report.error(name.position, "semantic error: undefined type \"" + name.identifier + "\"");
    }
}
