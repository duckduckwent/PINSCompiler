/**
 * @ Author: turk
 * @ Description: Analizator klicnih zapisov.
 */

package compiler.frm;

import static common.RequireNonNull.requireNonNull;

import common.Constants;
import common.Report;
import common.VoidOperator;
import compiler.common.Visitor;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.Array;
import compiler.parser.ast.type.Atom;
import compiler.parser.ast.type.TypeName;
import compiler.seman.common.NodeDescription;
import compiler.seman.type.type.Type;

import java.util.Stack;

public class FrameEvaluator implements Visitor {
    /**
     * Opis definicij funkcij in njihovih klicnih zapisov.
     */
    private NodeDescription<Frame> frames;

    /**
     * Opis definicij spremenljivk in njihovih dostopov.
     */
    private NodeDescription<Access> accesses;

    /**
     * Opis vozlišč in njihovih definicij.
     */
    private final NodeDescription<Def> definitions;

    /**
     * Opis vozlišč in njihovih podatkovnih tipov.
     */
    private final NodeDescription<Type> types;

    /**
     * Sklad, kamor se shranjujejo funkcije med nedokončano gradnjo
     */
    private Stack<Frame.Builder> builderStack;

    /**
     * Trenutni statični nivo
     */
    private int staticLevel;

    private int parameterOffset;

    private int localOffset;

    public FrameEvaluator(
        NodeDescription<Frame> frames, 
        NodeDescription<Access> accesses,
        NodeDescription<Def> definitions,
        NodeDescription<Type> types
    ) {
        requireNonNull(frames, accesses, definitions, types);
        this.frames = frames;
        this.accesses = accesses;
        this.definitions = definitions;
        this.types = types;
        this.builderStack = new Stack<>();
        this.staticLevel = 0;
        this.parameterOffset = 0;
        this.localOffset = 0;
    }

    private void inNewLevel(VoidOperator op) {
        staticLevel++;
        op.apply();
        staticLevel--;
    }

    @Override
    public void visit(Call call) {
        // Velikost argumentov je velikost ene besede krat število argumentov, saj se prenašajo le po referenci + SL
        int argumentsSize = Constants.WordSize * call.arguments.size() + Constants.WordSize;
        this.builderStack.peek().addFunctionCall(argumentsSize);
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
        // Ni pomembno tu
    }


    @Override
    public void visit(IfThenElse ifThenElse) {
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(expr -> expr.accept(this));
    }


    @Override
    public void visit(Literal literal) {
        // Ni pomembno tu
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
        where.defs.accept(this);
        where.expr.accept(this);
    }


    @Override
    public void visit(Defs defs) {
        // Sprehod čez vse definicije
        for (var def : defs.definitions)
            def.accept(this);
    }


    @Override
    public void visit(FunDef funDef) {
        // Če je na globalnem nivoju, je poimenovana, sicer je anonimna
        Frame.Label tempLabel = Frame.Label.named(funDef.name);
        if (this.staticLevel > 0)
            tempLabel = Frame.Label.nextAnonymous();
        Frame.Label label = tempLabel;

        // Graditelja te funkcije doda na sklad zato, da ostali znajo dostopati do ustreznega graditelja
        Frame.Builder builder = new Frame.Builder(label, staticLevel + 1);
        builderStack.push(builder);

        // Vsaka definicija funkcije poveča statični nivo
        inNewLevel(() -> {
            // Prvo obdela parametre, sami pa se dodajo k funkciji, ki je na vrhu sklada
            builder.addParameter(Constants.WordSize);   // SL
            for (var parameter : funDef.parameters)
                parameter.accept(this);

            // Nato sledi samo še obdelava vsebine funkcije
            funDef.body.accept(this);
        });

        // Na koncu se klicni zapis še zgradi in shrani
        frames.store(funDef, this.builderStack.pop().build());
    }


    @Override
    public void visit(TypeDef typeDef) {
        // Ni pomembno tu
    }


    @Override
    public void visit(VarDef varDef) {
        // Pridobi tip spremenljivke, za izračun njene velikosti
        var type = types.valueFor(varDef);
        if (type.isEmpty()) {
            Report.error(varDef.position, "semantic error: unknown type for variable " + varDef.name);
            return;
        }
        int size = type.get().sizeInBytes();

        // Če je na globalnem nivoju, je shranjena na kopici do konca programa, sicer pa začasno na skladu
        if (this.staticLevel < 1) {
            Frame.Label label = Frame.Label.named(varDef.name);
            accesses.store(varDef, new Access.Global(size, label));
        }
        else {
            // Graditelju dodaj lokalno spremenljivko, ki hkrati ob dodajanju vrne odmik te spremenljivke
            localOffset = this.builderStack.peek().addLocalVariable(size);
            accesses.store(varDef, new Access.Local(size, localOffset, staticLevel));
        }
    }


    @Override
    public void visit(Parameter parameter) {
        // Pridobi tip parametra, preko katerega dobiš njegovo velikost
        var paramType = types.valueFor(parameter);
        if (paramType.isEmpty()) {
            Report.error(parameter.position, "semantic error: unknown type for parameter " + parameter.name);
            return;
        }
        int size = paramType.get().sizeInBytesAsParam();

        // Vrhnjemu graditelju dodaj parameter, hkrati pa vrne velikost vseh dosedanjih parametrov (torej tudi odmik)
        parameterOffset = this.builderStack.peek().addParameter(size);

        // V tabelo dostopov dodaj ta parameter
        accesses.store(parameter, new Access.Parameter(size, parameterOffset, staticLevel));
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
