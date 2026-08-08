package optimizer;

import java.util.*;

/**
 * Interpreter.java -- runs the IR and records everything it printed, plus how many
 * instructions it had to execute.
 *
 * THIS CLASS IS THE POINT OF THE WHOLE DEMO.
 * Anyone can write a program that deletes instructions. The hard claim an optimizer
 * makes is: "the program still does exactly the same thing". So we run the ORIGINAL
 * IR and the OPTIMIZED IR through the same interpreter and compare their outputs.
 * If the two output lists differ, the optimizer is broken -- and Main will say so.
 *
 * The step counter gives us an honest, machine-independent performance number:
 * how many IR instructions actually executed. No JIT warm-up, no noisy wall clock.
 */
public final class Interpreter {

    public static final class Result {
        public final List<Integer> output;
        public final int steps;
        Result(List<Integer> output, int steps) { this.output = output; this.steps = steps; }
    }

    public Result run(Program program) {
        Map<String, Integer> env = new HashMap<>();
        List<Integer> output = new ArrayList<>();
        int steps = 0;

        // Pre-index labels so `goto` is a jump, not a search.
        Map<String, Integer> labels = new HashMap<>();
        for (int i = 0; i < program.main.size(); i++) {
            if (program.main.get(i) instanceof Ir.Label) {
                labels.put(((Ir.Label) program.main.get(i)).name, i);
            }
        }

        int pc = 0;
        int guard = 0;   // safety net so a buggy program cannot hang the demo
        while (pc < program.main.size() && guard++ < 1_000_000) {
            Ir.Instr instr = program.main.get(pc);

            if (instr instanceof Ir.Label) { pc++; continue; }   // labels cost nothing
            steps++;

            if (instr instanceof Ir.Assign) {
                Ir.Assign a = (Ir.Assign) instr;
                env.put(a.dest, value(a.src, env));

            } else if (instr instanceof Ir.BinOp) {
                Ir.BinOp b = (Ir.BinOp) instr;
                env.put(b.dest, apply(value(b.left, env), b.op, value(b.right, env)));

            } else if (instr instanceof Ir.Shift) {
                Ir.Shift s = (Ir.Shift) instr;
                int v = value(s.value, env);
                env.put(s.dest, s.leftShift ? (v << s.bits) : (v >> s.bits));

            } else if (instr instanceof Ir.Print) {
                output.add(value(((Ir.Print) instr).value, env));

            } else if (instr instanceof Ir.Goto) {
                pc = labels.get(((Ir.Goto) instr).target);
                continue;

            } else if (instr instanceof Ir.IfFalseGoto) {
                Ir.IfFalseGoto f = (Ir.IfFalseGoto) instr;
                if (value(f.cond, env) == 0) { pc = labels.get(f.target); continue; }

            } else if (instr instanceof Ir.Call) {
                Ir.Call c = (Ir.Call) instr;
                Program.Function fn = program.functions.get(c.function);
                if (fn == null) throw new IllegalStateException("unknown function " + c.function);

                // A real call: bind parameters in a FRESH scope, run the body, return.
                // Notice how much work this is compared to the inlined version --
                // that is precisely the overhead inline expansion removes.
                Map<String, Integer> frame = new HashMap<>();
                for (int i = 0; i < fn.params.size(); i++) {
                    frame.put(fn.params.get(i), value(c.args.get(i), env));
                }
                Integer returned = null;
                for (Ir.Instr bi : fn.body) {
                    steps++;
                    if (bi instanceof Ir.Assign) {
                        Ir.Assign a = (Ir.Assign) bi;
                        frame.put(a.dest, value(a.src, frame));
                    } else if (bi instanceof Ir.BinOp) {
                        Ir.BinOp b = (Ir.BinOp) bi;
                        frame.put(b.dest, apply(value(b.left, frame), b.op, value(b.right, frame)));
                    } else if (bi instanceof Ir.Return) {
                        returned = value(((Ir.Return) bi).value, frame);
                        break;
                    }
                }
                env.put(c.dest, returned == null ? 0 : returned);
            }

            pc++;
        }
        return new Result(output, steps);
    }

    private static int value(Ir.Operand op, Map<String, Integer> env) {
        if (op.isConst) return op.value;
        Integer v = env.get(op.name);
        if (v == null) throw new IllegalStateException("read of uninitialised variable " + op.name);
        return v;
    }

    private static int apply(int a, String op, int b) {
        switch (op) {
            case "+":  return a + b;
            case "-":  return a - b;
            case "*":  return a * b;
            case "/":  return a / b;
            case "%":  return a % b;
            case "<":  return a <  b ? 1 : 0;
            case ">":  return a >  b ? 1 : 0;
            case "<=": return a <= b ? 1 : 0;
            case ">=": return a >= b ? 1 : 0;
            case "==": return a == b ? 1 : 0;
            case "!=": return a != b ? 1 : 0;
            default: throw new IllegalStateException("unknown operator " + op);
        }
    }
}
