package optimizer;

import java.util.*;

/**
 * OPTIMIZATION 4 -- PEEPHOLE OPTIMIZATION
 *
 * IDEA
 * Slide a tiny window -- the "peephole" -- over the instruction stream, usually
 * just one to three instructions wide. Whenever the instructions inside the window
 * match a known bad pattern, swap them for a cheaper sequence that does the same
 * thing. Then slide on.
 *
 * The name comes from Bill McKeeman's 1965 CACM paper "Peephole optimization",
 * and the technique is still everywhere: LLVM's InstCombine and GCC's combine pass
 * are industrial-strength peepholes with thousands of rules.
 *
 * WHY BOTHER, IF THE PROGRAMMER WOULD NEVER WRITE `x * 1`?
 * Because the programmer is not who wrote it. These silly patterns are GENERATED:
 * by macro expansion, by generic/templated code, by array-index address arithmetic,
 * and -- most of all -- by the other optimization passes. Inlining a method with a
 * default argument of 0 produces `x + 0` all by itself. Peephole is the janitor
 * that cleans up after the rest of the pipeline.
 *
 * TWO FLAVOURS OF RULE IMPLEMENTED HERE
 *   1. ALGEBRAIC IDENTITIES -- x+0, x*1, x*0, x/1. Same answer, less work.
 *   2. STRENGTH REDUCTION -- replace an expensive operator with a cheap one that is
 *      provably equivalent: x*2 becomes x+x, x*8 becomes x<<3. On real hardware an
 *      integer multiply costs roughly 3-5 cycles while a shift costs 1.
 * Plus two control-flow cleanups that matter enormously in practice:
 *   3. `ifFalse <literal> goto L` is not a branch at all -- resolve it now.
 *   4. `goto L` immediately followed by `L:` is a jump to the next line. Delete it.
 *
 * CAREFUL WITH x*0
 * For integers, x*0 == 0 always, so the rewrite is safe. For floating point it is
 * NOT: if x is NaN or -0.0 the answer is not 0.0. This is exactly why `-ffast-math`
 * exists as an opt-in flag and is not the default.
 *
 * COMPLEXITY: O(n) per pass; each rule is O(1) because the window is fixed-size.
 */
public final class PeepholePass implements Pass {

    @Override public String name() { return "Peephole Optimization"; }

    @Override public boolean run(Program program) {
        boolean changed = false;
        List<Ir.Instr> out = new ArrayList<>();

        for (int i = 0; i < program.main.size(); i++) {
            Ir.Instr instr = program.main.get(i);
            Ir.Instr next = (i + 1 < program.main.size()) ? program.main.get(i + 1) : null;

            // ---------- RULE 4: `goto L` followed immediately by `L:` ----------
            // The jump lands on the instruction that was going to run anyway.
            if (instr instanceof Ir.Goto && next instanceof Ir.Label
                    && ((Ir.Goto) instr).target.equals(((Ir.Label) next).name)) {
                changed = true;
                continue;                                   // drop the goto entirely
            }

            // ---------- RULE 3: a branch on a literal is not a branch ----------
            if (instr instanceof Ir.IfFalseGoto) {
                Ir.IfFalseGoto f = (Ir.IfFalseGoto) instr;
                if (f.cond.isConst) {
                    if (f.cond.value == 0) {
                        out.add(new Ir.Goto(f.target));      // always taken
                    }
                    // else: never taken -> emit nothing, the branch vanishes
                    changed = true;
                    continue;
                }
            }

            // ---------- RULE 0: `x = x` is a no-op move ----------
            if (instr instanceof Ir.Assign) {
                Ir.Assign a = (Ir.Assign) instr;
                if (a.src.isVar() && a.src.name.equals(a.dest)) {
                    changed = true;
                    continue;
                }
            }

            // ---------- RULES 1 & 2: algebraic identities + strength reduction ----------
            if (instr instanceof Ir.BinOp) {
                Ir.BinOp b = (Ir.BinOp) instr;
                Ir.Instr rewritten = rewriteBinOp(b);
                if (rewritten != null) {
                    out.add(rewritten);
                    changed = true;
                    continue;
                }
            }

            out.add(instr);
        }

        program.main = out;
        return changed;
    }

    /** Returns a cheaper equivalent instruction, or null if no rule matched. */
    private static Ir.Instr rewriteBinOp(Ir.BinOp b) {
        boolean leftIsConst  = b.left.isConst;
        boolean rightIsConst = b.right.isConst;

        switch (b.op) {
            case "+":
                // x + 0  ->  x        and        0 + x  ->  x
                if (rightIsConst && b.right.value == 0) return new Ir.Assign(b.dest, b.left);
                if (leftIsConst  && b.left.value  == 0) return new Ir.Assign(b.dest, b.right);
                break;

            case "-":
                // x - 0  ->  x   (note: 0 - x is NOT x, so only the right side folds)
                if (rightIsConst && b.right.value == 0) return new Ir.Assign(b.dest, b.left);
                break;

            case "*":
                // x * 0  ->  0    (safe for integers; NOT safe for floats -- see class doc)
                if (rightIsConst && b.right.value == 0) return new Ir.Assign(b.dest, Ir.Operand.num(0));
                if (leftIsConst  && b.left.value  == 0) return new Ir.Assign(b.dest, Ir.Operand.num(0));
                // x * 1  ->  x
                if (rightIsConst && b.right.value == 1) return new Ir.Assign(b.dest, b.left);
                if (leftIsConst  && b.left.value  == 1) return new Ir.Assign(b.dest, b.right);
                // STRENGTH REDUCTION: multiply by a power of two -> left shift.
                if (rightIsConst && isPowerOfTwo(b.right.value))
                    return new Ir.Shift(b.dest, b.left, log2(b.right.value), true);
                if (leftIsConst && isPowerOfTwo(b.left.value))
                    return new Ir.Shift(b.dest, b.right, log2(b.left.value), true);
                break;

            case "/":
                // x / 1  ->  x
                if (rightIsConst && b.right.value == 1) return new Ir.Assign(b.dest, b.left);
                /*
                 * We deliberately do NOT rewrite x / 8 into x >> 3 here.
                 * For NEGATIVE x they disagree: -9 / 8 == -1 (Java truncates toward
                 * zero) but -9 >> 3 == -2 (arithmetic shift floors). A real compiler
                 * only applies this when it can prove x >= 0, or it emits a small
                 * correction sequence. A tempting rewrite that is subtly wrong is
                 * worse than no rewrite at all.
                 */
                break;
        }
        return null;
    }

    private static boolean isPowerOfTwo(int v) { return v > 1 && (v & (v - 1)) == 0; }

    private static int log2(int v) { return Integer.numberOfTrailingZeros(v); }
}
