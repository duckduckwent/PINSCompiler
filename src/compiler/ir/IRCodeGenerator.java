/**
 * @ Author: turk
 * @ Description: Generator vmesne kode.
 */

package compiler.ir;

import static common.RequireNonNull.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import common.Constants;
import common.Report;
import common.VoidOperator;
import compiler.common.Visitor;
import compiler.frm.Access;
import compiler.frm.Frame;
import compiler.frm.Frame.Label;
import compiler.ir.chunk.Chunk;
import compiler.ir.code.IRNode;
import compiler.ir.code.expr.*;
import compiler.ir.code.stmt.*;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.Array;
import compiler.parser.ast.type.Atom;
import compiler.parser.ast.type.TypeName;
import compiler.seman.common.NodeDescription;
import compiler.seman.type.type.Type;


public class IRCodeGenerator implements Visitor {
    /**
     * Preslikava iz vozlišč AST v vmesno kodo.
     */
    private NodeDescription<IRNode> imcCode;

    /**
     * Razrešeni klicni zapisi.
     */
    private final NodeDescription<Frame> frames;

    /**
     * Razrešeni dostopi.
     */
    private final NodeDescription<Access> accesses;

    /**
     * Razrešene definicije.
     */
    private final NodeDescription<Def> definitions;

    /**
     * Razrešeni tipi.
     */
    private final NodeDescription<Type> types;

    /**
     * **Rezultat generiranja vmesne kode** - seznam fragmentov.
     */
    public List<Chunk> chunks = new ArrayList<>();

    private int currentStaticLevel;

    public IRCodeGenerator(
        NodeDescription<IRNode> imcCode,
        NodeDescription<Frame> frames, 
        NodeDescription<Access> accesses,
        NodeDescription<Def> definitions,
        NodeDescription<Type> types
    ) {
        requireNonNull(imcCode, frames, accesses, definitions, types);
        this.types = types;
        this.imcCode = imcCode;
        this.frames = frames;
        this.accesses = accesses;
        this.definitions = definitions;
        this.currentStaticLevel = 0;
    }

    private void inNewLevel(VoidOperator op) {
        currentStaticLevel++;
        op.apply();
        currentStaticLevel--;
    }

    private BinopExpr.Operator operatorMapping(Binary.Operator op) {
        switch (op) {
            case EQ ->  { return BinopExpr.Operator.EQ;  }
            case NEQ -> { return BinopExpr.Operator.NEQ; }
            case GT ->  { return BinopExpr.Operator.GT;  }
            case LT ->  { return BinopExpr.Operator.LT;  }
            case GEQ -> { return BinopExpr.Operator.GEQ; }
            case LEQ -> { return BinopExpr.Operator.LEQ; }
            case OR ->  { return BinopExpr.Operator.OR;  }
            case AND -> { return BinopExpr.Operator.AND; }
            case ADD -> { return BinopExpr.Operator.ADD; }
            case SUB -> { return BinopExpr.Operator.SUB; }
            case MUL -> { return BinopExpr.Operator.MUL; }
            case DIV -> { return BinopExpr.Operator.DIV; }
            default ->  { return BinopExpr.Operator.MOD; }
        }
    }

    @Override
    public void visit(Call call) {
        List<IRExpr> args = new ArrayList<>();
        Label functionLabel;
        int staticLevel = 1;
        Optional<MoveStmt> oldFPCode = Optional.empty();

        switch (call.name) {
            case Constants.printIntLabel ->    functionLabel = Label.named(Constants.printIntLabel);
            case Constants.printStringLabel -> functionLabel = Label.named(Constants.printStringLabel);
            case Constants.printLogLabel ->    functionLabel = Label.named(Constants.printLogLabel);
            case Constants.randIntLabel ->     functionLabel = Label.named(Constants.randIntLabel);
            case Constants.seedLabel ->        functionLabel = Label.named(Constants.seedLabel);
            default -> {
                // Pridobi labelo in statični nivo funkcije
                var def = definitions.valueFor(call);
                if (def.isEmpty()) {
                    Report.error(call.position, "ir error: cannot find function definition");
                    return;
                }
                var frame = frames.valueFor(def.get());
                if (frame.isEmpty()) {
                    Report.error(call.position, "ir error: frame for function " + call.name + " does not exist");
                    return;
                }
                functionLabel = frame.get().label;
                staticLevel = frame.get().staticLevel;

                BinopExpr oldFPAddress = new BinopExpr(NameExpr.SP(), new ConstantExpr(-frame.get().oldFPOffset()), BinopExpr.Operator.ADD);
                oldFPCode = Optional.of(new MoveStmt(new MemExpr(oldFPAddress), NameExpr.FP()));
            }
        }

        // Generiraj dodaten argument za statično povezavo
        // Če je funkcija globalna, je lahko statična povezava neka konstanta
        if (staticLevel <= 1)
            args.add(new ConstantExpr(0));
        // Če je to funkcijo klicala starševska funkcija
        else if (staticLevel > currentStaticLevel)
            args.add(NameExpr.FP());
        // Če je funkcija klicala sama sebe
        else if (staticLevel == currentStaticLevel)
            args.add(new MemExpr(NameExpr.FP()));
        // Če je funkcija klicala katerekoli izmed svojih starševskih funkcij (sepravi vse funkcije, ki so nad to)
        else {
            MemExpr staticJumps = new MemExpr(new MemExpr(NameExpr.FP()));      // Skok za dva nivoja višje
            // Če je razlika večja kot 1 nivo, se naknadno dodajajo skoki
            for (int i = 0; i < currentStaticLevel - staticLevel - 1; i++)
                staticJumps = new MemExpr(staticJumps);
            args.add(staticJumps);
        }

        // Generiraj kodo ostalih argumentov
        for (var argument : call.arguments) {
            // Pridobi vmesno kodo argumenta
            argument.accept(this);
            var argCode = imcCode.valueFor(argument);

            // Dodaj argument, sicer vrni napako
            if (argCode.isPresent() && argCode.get() instanceof IRExpr irExpr) {
                args.add(irExpr);
                continue;
            }
            Report.error(argument.position, "ir error: could not generate intermediate code for argument");
        }

        CallExpr callExpr = new CallExpr(functionLabel, args);
        if (oldFPCode.isEmpty())
            imcCode.store(call, new EseqExpr(SeqStmt.empty(), callExpr));
        else
            imcCode.store(call, new EseqExpr(oldFPCode.get(), callExpr));
    }

    @Override
    public void visit(Binary binary) {
        // Izračunaj vmesno kodo
        binary.left.accept(this);
        binary.right.accept(this);

        // Preveri ali obstaja vmesna koda za oba izraza
        var left = imcCode.valueFor(binary.left);
        var right = imcCode.valueFor(binary.right);
        if (left.isEmpty() || right.isEmpty()) {
            Report.error(binary.position, "ir error: could not generate intermediate code");
            return;
        }

        if (left.get() instanceof IRExpr lhs && right.get() instanceof IRExpr rhs) {
            if (binary.operator == Binary.Operator.ASSIGN) {
                imcCode.store(binary, new EseqExpr(new MoveStmt(lhs, rhs), lhs));   // lhs pričakuje, da že ima MemExpr
            }
            else if (binary.operator == Binary.Operator.ARR) {
                var arrType = types.valueFor(binary);
                if (arrType.isEmpty()) {
                    Report.error(binary.position, "ir error: could not get the type of binary expression");
                    return;
                }

                // Če je že prišlo do dereferenciranja, obdrži samo naslov
                if (lhs instanceof MemExpr)
                    lhs = ((MemExpr) lhs).expr;

                // Shrani kot dostop do vrednosti (če se ponovno kliče, se ta mem odstrani in nadomesti z novim)
                imcCode.store(binary, new MemExpr(new BinopExpr(lhs, new BinopExpr(rhs, new ConstantExpr(arrType.get().sizeInBytes()), BinopExpr.Operator.MUL), BinopExpr.Operator.ADD)));
            }
            else {
                BinopExpr.Operator op = operatorMapping(binary.operator);
                imcCode.store(binary, new BinopExpr(lhs, rhs, op));
            }
        }
    }

    @Override
    public void visit(Block block) {
        List<IRStmt> statements = new ArrayList<>();
        for (var expr : block.expressions) {
            // Izračunaj vmesno kodo izraza
            expr.accept(this);
            var exprCode = imcCode.valueFor(expr);
            if (exprCode.isEmpty()) {
                Report.error(expr.position, "ir error: could not generate intermediate code for expression");
                return;
            }

            // Če je to stavek, ga doda direktno, če je pa izraz, ga pa doda preko ExpStmt
            if (exprCode.get() instanceof IRExpr irExpr)
                statements.add(new ExpStmt(irExpr));
            else if (exprCode.get() instanceof IRStmt irStmt)
                statements.add(irStmt);
        }

        // Preveri zadnji stavek (če je izraz, potem je blok veljaven)
        if (statements.get(statements.size()-1) instanceof ExpStmt expStmt) {
            statements.remove(expStmt);
            imcCode.store(block, new EseqExpr(new SeqStmt(statements), expStmt.expr));
        }
        else
            Report.error(block.position, "ir error: in a block, the last statement should be an expression");
    }

    @Override
    public void visit(For forLoop) {
        // Pridobi vmesne kode
        forLoop.counter.accept(this);
        forLoop.low.accept(this);
        forLoop.high.accept(this);
        forLoop.step.accept(this);
        forLoop.body.accept(this);
        var counterCode = imcCode.valueFor(forLoop.counter);
        var lowCode = imcCode.valueFor(forLoop.low);
        var highCode = imcCode.valueFor(forLoop.high);
        var stepCode = imcCode.valueFor(forLoop.step);
        var bodyCode = imcCode.valueFor(forLoop.body);

        if (counterCode.isEmpty() || lowCode.isEmpty() || highCode.isEmpty() || stepCode.isEmpty() || bodyCode.isEmpty()) {
            Report.error(forLoop.position, "ir error: could not generate for loop intermediate code");
            return;
        }

        if (counterCode.get() instanceof IRExpr counter && lowCode.get() instanceof IRExpr low && highCode.get() instanceof IRExpr high && stepCode.get() instanceof IRExpr step && bodyCode.get() instanceof IRExpr body) {
            // Premakni vrednost low v counter
            MoveStmt initCounter = new MoveStmt(counter, low);

            // Ustvari vmesno kodo za pogoj
            IRExpr condition = new BinopExpr(counter, high, BinopExpr.Operator.LT);

            // Ustvari vmesno kodo za povečevanje števca
            MoveStmt incrementCounter = new MoveStmt(counter, new BinopExpr(counter, step, BinopExpr.Operator.ADD));

            // Pripravi labele za skakanje
            List<IRStmt> statements = new ArrayList<>();
            Label beginLabel = Label.nextAnonymous();
            Label endLabel = Label.nextAnonymous();
            Label conditionLabel = Label.nextAnonymous();

            // Generiraj stavke za zanko
            statements.add(initCounter);
            statements.add(new LabelStmt(conditionLabel));
            statements.add(new CJumpStmt(condition, beginLabel, endLabel));
            statements.add(new LabelStmt(beginLabel));
            statements.add(new ExpStmt(body));
            statements.add(incrementCounter);
            statements.add(new JumpStmt(conditionLabel));
            statements.add(new LabelStmt(endLabel));

            imcCode.store(forLoop, new SeqStmt(statements));
            return;
        }
        Report.error(forLoop.position, "ir error: counter, low, high, step or body is not an expression");
    }

    @Override
    public void visit(Name name) {
        // Pridobi definicijo in dostop do spremenljivke
        var def = this.definitions.valueFor(name);
        if (def.isEmpty()) {
            Report.error(name.position, "ir error: no definition for " + name.name);
            return;
        }
        var access = this.accesses.valueFor(def.get());
        if (access.isEmpty()) {
            Report.error(name.position, "ir error: no access for " + name.name);
            return;
        }

        if (access.get() instanceof Access.Global global) {
            imcCode.store(name, new MemExpr(new NameExpr(global.label)));
        }
        else if (access.get() instanceof Access.Stack stack) {
            // Pridobi ustrezno število MemExpr glede na statični nivo
            Optional<MemExpr> mems = Optional.empty();
            for (int i = 0; i < currentStaticLevel - stack.staticLevel; i++) {
                if (mems.isEmpty())
                    mems = Optional.of(new MemExpr(NameExpr.FP()));
                else
                    mems = Optional.of(new MemExpr(mems.get()));
            }

            // Vmesna koda skokov po statičnih povezavah (če so potrebni)
            IRExpr address = mems.isEmpty() ? NameExpr.FP() : mems.get();

            // Shrani vmesno kodo dostopa do spremenljivke
            imcCode.store(name, new MemExpr(new BinopExpr(address, new ConstantExpr(stack.offset), BinopExpr.Operator.ADD)));
        }
    }

    @Override
    public void visit(IfThenElse ifThenElse) {
        // Pridobi vmesne kode
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(expr -> expr.accept(this));

        var conditionCode = imcCode.valueFor(ifThenElse.condition);
        var thenCode = imcCode.valueFor(ifThenElse.thenExpression);
        Optional<Optional<IRNode>> elseCode = Optional.empty();
        if (ifThenElse.elseExpression.isPresent())
            elseCode = Optional.of(imcCode.valueFor(ifThenElse.elseExpression.get()));

        if (conditionCode.isEmpty() || thenCode.isEmpty()) {
            Report.error(ifThenElse.position, "ir error: could not generate intermediate code for if statement");
            return;
        }

        if (conditionCode.get() instanceof IRExpr condition && thenCode.get() instanceof IRExpr then) {
            boolean elsePresent = elseCode.isPresent() && elseCode.get().isPresent();

            // Pripravi labele za skakanje
            List<IRStmt> statements = new ArrayList<>();
            Label thenLabel = Label.nextAnonymous();
            Label elseLabel = Label.nextAnonymous();
            Label endLabel = Label.nextAnonymous();

            // Začetni pogojni skok je odvisen od tega, ali obstaja else ali ne
            if (elsePresent && elseCode.get().get() instanceof IRExpr)
                statements.add(new CJumpStmt(condition, thenLabel, elseLabel));
            else
                statements.add(new CJumpStmt(condition, thenLabel, endLabel));

            // Then je vedno
            statements.add(new LabelStmt(thenLabel));
            statements.add(new ExpStmt(then));
            statements.add(new JumpStmt(endLabel));

            // Else se doda po potrebi
            if (elsePresent && elseCode.get().get() instanceof IRExpr elseIRExpr) {
                statements.add(new LabelStmt(elseLabel));
                statements.add(new ExpStmt(elseIRExpr));
                statements.add(new JumpStmt(endLabel));
            }

            statements.add(new LabelStmt(endLabel));
            imcCode.store(ifThenElse, new SeqStmt(statements));
        }
        else
            Report.error(ifThenElse.position, "ir error: condition, then and else should be expressions");
    }

    @Override
    public void visit(Literal literal) {
        // Preveri ali obstaja izračunan tip
        var type = types.valueFor(literal);
        if (type.isEmpty()) {
            Report.error(literal.position, "undefined type for literal");
            return;
        }

        if (type.get().isInt())
            imcCode.store(literal, new ConstantExpr(Integer.parseInt(literal.value)));
        else if (type.get().isLog())
            imcCode.store(literal, new ConstantExpr(literal.value.equals("false") ? 0 : 1));
        else {
            // Ustvari novo anonimno labelo, ki kaže na DataChunk tega niza (ta niz je shranjen globalno na kopici)
            Label anonymousLabel = Label.nextAnonymous();
            chunks.add(new Chunk.DataChunk(new Access.Global(Constants.WordSize, anonymousLabel), literal.value));
            imcCode.store(literal, new NameExpr(anonymousLabel));
        }
    }

    @Override
    public void visit(Unary unary) {
        // Izračunaj vmesno kodo izraza
        unary.expr.accept(this);
        var exprCode = imcCode.valueFor(unary.expr);
        if (exprCode.isEmpty()) {
            Report.error(unary.expr.position, "ir error: unary expression is not present");
            return;
        }

        // Prepričaj javo, da je ok :)
        IRExpr irExpr;
        if (exprCode.get() instanceof IRExpr expr)
            irExpr = expr;
        else {
            Report.error(unary.position, "ir error: could not generate intermediate code for unary expression");
            return;
        }

        // Izračuna vmesno kodo celotnega unary izraza
        switch (unary.operator) {
            case SUB -> imcCode.store(unary, new BinopExpr(new ConstantExpr(0), irExpr, BinopExpr.Operator.SUB));
            case ADD -> imcCode.store(unary, irExpr);
            case NOT -> {
                // Prvo logičnem izrazu (0 ali 1) prišteje 1, nato pa izračuna ostanek pri deljenju z 2
                BinopExpr addOne = new BinopExpr(irExpr, new ConstantExpr(1), BinopExpr.Operator.ADD);
                imcCode.store(unary, new BinopExpr(addOne, new ConstantExpr(2), BinopExpr.Operator.MOD));
            }
        }
    }

    @Override
    public void visit(While whileLoop) {
        // Pridobi vmesne kode
        whileLoop.condition.accept(this);
        whileLoop.body.accept(this);
        var conditionCode = imcCode.valueFor(whileLoop.condition);
        var bodyCode = imcCode.valueFor(whileLoop.body);

        if (conditionCode.isEmpty() || bodyCode.isEmpty()) {
            Report.error(whileLoop.position, "ir error: could not generate intermediate code for while loop");
            return;
        }

        if (conditionCode.get() instanceof IRExpr condition && bodyCode.get() instanceof IRExpr body) {
            // Pripravi labele za skakanje
            List<IRStmt> statements = new ArrayList<>();
            Label beginLabel = Label.nextAnonymous();
            Label endLabel = Label.nextAnonymous();
            Label conditionLabel = Label.nextAnonymous();

            // Generiraj stavke za zanko
            statements.add(new LabelStmt(conditionLabel));
            statements.add(new CJumpStmt(condition, beginLabel, endLabel));
            statements.add(new LabelStmt(beginLabel));
            statements.add(new ExpStmt(body));
            statements.add(new JumpStmt(conditionLabel));
            statements.add(new LabelStmt(endLabel));

            imcCode.store(whileLoop, new SeqStmt(statements));
        }
        else
            Report.error(whileLoop.position, "ir error: condition or body is not an expression");
    }

    @Override
    public void visit(Where where) {
        where.defs.accept(this);
        where.expr.accept(this);

        // Vmesna koda je kar tista, ki je generirana v where izrazu na začetku
        var exprCode = imcCode.valueFor(where.expr);
        if (exprCode.isPresent() && exprCode.get() instanceof IRExpr expr)
            imcCode.store(where, expr);
        else
            Report.error(where.expr.position, "ir error: could not generate intermediate code for where expression");
    }

    @Override
    public void visit(Defs defs) {
        for (var def : defs.definitions)
            def.accept(this);
    }

    @Override
    public void visit(FunDef funDef) {
        // Pridobi izračunan klicni zapis funkcije
        var frame = frames.valueFor(funDef);
        if (frame.isEmpty()) {
            Report.error(funDef.position, "ir error: no frame found for function " + funDef.name);
            return;
        }

        // Izračuna drevesno vmesno kodo v telesu funkcije
        inNewLevel(() -> {
            funDef.body.accept(this);
        });

        var bodyCode = imcCode.valueFor(funDef.body);
        if (bodyCode.isEmpty()) {
            Report.error(funDef.body.position, "ir error: intermediate code could not be calculated");
            return;
        }

        if (bodyCode.get() instanceof IRExpr irExpr) {
            // Izračunaj ostalo ustrezno vmesno kodo, kamor se pripne vmesna koda telesa funkcije
            var code = new MoveStmt(new MemExpr(NameExpr.FP()), irExpr);

            // Dodaj CodeChunk funkcije
            chunks.add(new Chunk.CodeChunk(frame.get(), code));
            return;
        }
        Report.error(funDef.body.position, "ir error: intermediate code should be an expression, not a statement");
    }

    @Override
    public void visit(TypeDef typeDef) {
        // Ni pomembno tu
    }

    @Override
    public void visit(VarDef varDef) {
        var varAccess = accesses.valueFor(varDef);
        if (varAccess.isEmpty()) {
            Report.error(varDef.position, "ir error: cannot access variable " + varDef.name);
            return;
        }

        // Če je globalna jo dodaj kot fragment globalne spremenljivke
        if (varAccess.get() instanceof Access.Global access)
            chunks.add(new Chunk.GlobalChunk(access));
    }

    @Override
    public void visit(Parameter parameter) {
        // Ni pomembno tu
    }

    @Override
    public void visit(Array array) {
        // Ni pomembno tu
    }

    @Override
    public void visit(Atom atom) {
        // Ni pomembno tu
    }

    @Override
    public void visit(TypeName name) {
        // Ni pomembno tu
    }
}
