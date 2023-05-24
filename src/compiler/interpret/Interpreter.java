/**
 * @ Author: turk
 * @ Description: Navidezni stroj (intepreter).
 */

package compiler.interpret;

import static common.RequireNonNull.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import common.Constants;
import common.Report;
import compiler.frm.Frame;
import compiler.gen.Memory;
import compiler.ir.chunk.Chunk.CodeChunk;
import compiler.ir.code.IRNode;
import compiler.ir.code.expr.*;
import compiler.ir.code.stmt.*;
import compiler.ir.IRPrettyPrint;

public class Interpreter {
    /**
     * Pomnilnik navideznega stroja.
     */
    private Memory memory;
    
    /**
     * Izhodni tok, kamor izpisujemo rezultate izvajanja programa.
     * 
     * V primeru, da rezultatov ne želimo izpisovati, nastavimo na `Optional.empty()`.
     */
    private Optional<PrintStream> outputStream;

    /**
     * Generator naključnih števil.
     */
    private Random random;

    /**
     * Skladovni kazalec (kaže na dno sklada).
     */
    private int stackPointer;

    /**
     * Klicni kazalec (kaže na vrh aktivnega klicnega zapisa).
     */
    private int framePointer;


    public Interpreter(Memory memory, Optional<PrintStream> outputStream) {
        requireNonNull(memory, outputStream);
        this.memory = memory;
        this.outputStream = outputStream;
        this.stackPointer = memory.size - Constants.WordSize;
        this.framePointer = memory.size - Constants.WordSize;
    }

    // --------- izvajanje navideznega stroja ----------

    public void interpret(CodeChunk chunk) {
        memory.stM(framePointer + Constants.WordSize, 999); // argument v funkcijo main
        memory.stM(framePointer - chunk.frame.oldFPOffset(), framePointer); // oldFP
        internalInterpret(chunk, new HashMap<>());
    }

    private void internalInterpret(CodeChunk chunk, Map<Frame.Temp, Object> temps) {
        // Nato prestavi FP na SP in SP zamakni za velikost klicnega zapisa
        memory.stM(stackPointer - chunk.frame.oldFPOffset(), framePointer);
        framePointer = stackPointer;
        stackPointer = stackPointer - chunk.frame.size();

        memory.registerLabel(NameExpr.FP().label, framePointer);
        memory.registerLabel(NameExpr.SP().label, stackPointer);

        Object result;
        if (chunk.code instanceof SeqStmt seq) {
            for (int pc = 0; pc < seq.statements.size(); pc++) {
                var stmt = seq.statements.get(pc);
                result = execute(stmt, temps);
                if (result instanceof Frame.Label label) {
                    for (int q = 0; q < seq.statements.size(); q++) {
                        if (seq.statements.get(q) instanceof LabelStmt labelStmt && labelStmt.label.equals(label)) {
                            pc = q;
                            break;
                        }
                    }
                }
            }
        } else {
            throw new RuntimeException("Linearize IR!");
        }

        // Prvo premakni SP na FP, potem pa FP nastavi na OldFP trenutne funkcije
        stackPointer = framePointer;
        framePointer = toInt(memory.ldM(framePointer - chunk.frame.oldFPOffset()));

        memory.registerLabel(NameExpr.FP().label, framePointer);
        memory.registerLabel(NameExpr.SP().label, stackPointer);
    }

    private Object execute(IRStmt stmt, Map<Frame.Temp, Object> temps) {
        if (stmt instanceof CJumpStmt cjump) {
            return execute(cjump, temps);
        } else if (stmt instanceof ExpStmt exp) {
            return execute(exp, temps);
        } else if (stmt instanceof JumpStmt jump) {
            return execute(jump, temps);
        } else if (stmt instanceof LabelStmt) {
            return null;
        } else if (stmt instanceof MoveStmt move) {
            return execute(move, temps);
        } else {
            throw new RuntimeException("Cannot execute this statement!");
        }
    }

    private Object execute(CJumpStmt cjump, Map<Frame.Temp, Object> temps) {
        // Vrne labelo odvisno od pogoja
        return toBool(execute(cjump.condition, temps)) ? cjump.thenLabel : cjump.elseLabel;
    }

    private Object execute(ExpStmt exp, Map<Frame.Temp, Object> temps) {
        return execute(exp.expr, temps);
    }

    private Object execute(JumpStmt jump, Map<Frame.Temp, Object> temps) {
        return jump.label;
    }

    private Object execute(MoveStmt move, Map<Frame.Temp, Object> temps) {
        Object dst;
        if (move.dst instanceof MemExpr memExpr) {
            dst = execute(memExpr.expr, temps);
            if (dst instanceof Frame.Label label) {
                memory.stM(label, execute(move.src, temps));
                return memory.ldM(label);
            }
            else {
                memory.stM(toInt(dst), execute(move.src, temps));
                return memory.ldM(toInt(dst));
            }
        }
        else if (move.dst instanceof TempExpr tempExpr) {
            temps.put(tempExpr.temp, execute(move.src, temps));
            return temps.get(tempExpr.temp);
        }
        Report.error("interpreter error: move should have mem");
        return 0;
    }

    private Object execute(IRExpr expr, Map<Frame.Temp, Object> temps) {
        if (expr instanceof BinopExpr binopExpr) {
            return execute(binopExpr, temps);
        } else if (expr instanceof CallExpr callExpr) {
            return execute(callExpr, temps);
        } else if (expr instanceof ConstantExpr constantExpr) {
            return execute(constantExpr);
        } else if (expr instanceof EseqExpr) {
            throw new RuntimeException("Cannot execute ESEQ; linearize IRCode!");
        } else if (expr instanceof MemExpr memExpr) {
            return execute(memExpr, temps);
        } else if (expr instanceof NameExpr nameExpr) {
            return execute(nameExpr);
        } else if (expr instanceof TempExpr tempExpr) {
            return execute(tempExpr, temps);
        } else {
            throw new IllegalArgumentException("Unknown expr type");
        }
    }

    private Object execute(BinopExpr binop, Map<Frame.Temp, Object> temps) {
        Object lhs = execute(binop.lhs, temps);
        Object rhs = execute(binop.rhs, temps);
        int num1, num2;

        if (lhs instanceof Frame.Temp temp)
            num1 = toInt(temps.get(temp));
        else if (lhs instanceof Frame.Label label)
            num1 = memory.address(label);
        else
            num1 = toInt(lhs);

        if (rhs instanceof Frame.Temp temp)
            num2 = toInt(temps.get(temp));
        else if (rhs instanceof Frame.Label label)
            num2 = memory.address(label);
        else
            num2 = toInt(rhs);

        return switch (binop.op) {
            case ADD -> num1 + num2;
            case SUB -> num1 - num2;
            case MUL -> num1 * num2;
            case DIV -> num1 / num2;
            case MOD -> num1 % num2;
            case AND -> toInt(toBool(num1) && toBool(num2));
            case OR  -> toInt(toBool(num1) || toBool(num2));
            case EQ  -> toInt(num1 == num2);
            case NEQ -> toInt(num1 != num2);
            case LT  -> toInt(num1 < num2);
            case GT  -> toInt(num1 > num2);
            case LEQ -> toInt(num1 <= num2);
            case GEQ -> toInt(num1 >= num2);
        };
    }

    private Object execute(CallExpr call, Map<Frame.Temp, Object> temps) {
        if (call.label.name.equals(Constants.printIntLabel)) {
            if (call.args.size() != 2) { throw new RuntimeException("Invalid argument count!"); }
            var arg = execute(call.args.get(1), temps);
            outputStream.ifPresent(stream -> stream.println(arg));
            return null;
        } else if (call.label.name.equals(Constants.printStringLabel)) {
            if (call.args.size() != 2) { throw new RuntimeException("Invalid argument count!"); }
            var address = execute(call.args.get(1), temps);
            var res = memory.ldM(toInt(address));
            outputStream.ifPresent(stream -> stream.println("\""+res+"\""));
            return null;
        } else if (call.label.name.equals(Constants.printLogLabel)) {
            if (call.args.size() != 2) { throw new RuntimeException("Invalid argument count!"); }
            var arg = execute(call.args.get(1), temps);
            outputStream.ifPresent(stream -> stream.println(toBool(arg)));
            return null;
        } else if (call.label.name.equals(Constants.randIntLabel)) {
            if (call.args.size() != 3) { throw new RuntimeException("Invalid argument count!"); }
            var min = toInt(execute(call.args.get(1), temps));
            var max = toInt(execute(call.args.get(2), temps));
            return random.nextInt(min, max);
        } else if (call.label.name.equals(Constants.seedLabel)) {
            if (call.args.size() != 2) { throw new RuntimeException("Invalid argument count!"); }
            var seed = toInt(execute(call.args.get(1), temps));
            random = new Random(seed);
            return null;
        } else if (memory.ldM(call.label) instanceof CodeChunk chunk) {
            // prvo ustrezno shrani nove argumenta na svoje naslove, nato izvedi klic funkcije
            int argCounter = 0;
            for (var arg : call.args) {
                memory.stM(stackPointer + argCounter, execute(arg, temps));
                argCounter += Constants.WordSize;
            }
            internalInterpret(chunk, new HashMap<>());
            return memory.ldM(stackPointer);
        } else {
            throw new RuntimeException("Only functions can be called!");
        }
    }

    private Object execute(ConstantExpr constant) {
        return constant.constant;
    }

    private Object execute(MemExpr mem, Map<Frame.Temp, Object> temps) {
        Object expr = execute(mem.expr, temps);
        if (expr instanceof Frame.Label label)
            return memory.ldM(label);
        else if (expr instanceof Frame.Temp temp)
            return temps.get(temp);
        return memory.ldM(toInt(expr));
    }

    private Object execute(NameExpr name) {
        return memory.address(name.label);
    }

    private Object execute(TempExpr temp, Map<Frame.Temp, Object> temps) {
        return temps.get(temp.temp);
    }

    // ----------- pomožne funkcije -----------

    private int toInt(Object obj) {
        if (obj instanceof Integer integer) {
            return integer;
        }
        throw new IllegalArgumentException("Could not convert obj to integer!");
    }

    private boolean toBool(Object obj) {
        return toInt(obj) == 0 ? false : true;
    }

    private int toInt(boolean bool) {
        return bool ? 1 : 0;
    }

    private String prettyDescription(IRNode ir, int indent) {
        var os = new ByteArrayOutputStream();
        var ps = new PrintStream(os);
        new IRPrettyPrint(ps, indent).print(ir);
        return os.toString(Charset.defaultCharset());
    }

    private String prettyDescription(IRNode ir) {
        return prettyDescription(ir, 2);
    }

    private void prettyPrint(IRNode ir, int indent) {
        System.out.println(prettyDescription(ir, indent));
    }

    private void prettyPrint(IRNode ir) {
        System.out.println(prettyDescription(ir));
    }
}
