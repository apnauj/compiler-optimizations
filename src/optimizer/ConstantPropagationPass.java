package optimizer;

import java.util.*;

/**
 * OPTIMIZATION 3 -- CONSTANT PROPAGATION
 *
 * IDEA
 * If we can prove a variable holds a known literal at some program point, then
 * every READ of that variable at that point can be replaced by the literal itself.
 *
 *     scale = 3          scale = 3
 *     w = base * scale   ==>   w = base * 3
 *
 * Notice this pass does not compute anything. It only substitutes. Its job is to
 * FEED constant folding: folding can only fire when both operands are already
 * literals, and propagation is what makes them literals.
 *
 * HOW IT WORKS (forward data-flow analysis)
 * We walk the instruction list top to bottom carrying an environment
 * `known : variable -> literal`. For each instruction we:
 *   1. substitute any operand whose variable is currently known,
 *   2. update `known` based on what this instruction writes.
 *
 * THE THREE RULES THAT KEEP IT CORRECT
 *   (a) KILL on redefinition. If an instruction writes x and we cannot prove the
 *       new value is a literal, x must be REMOVED from `known`. Forgetting this is
 *       the single most common way to write a miscompiling optimizer.
 *   (b) CLEAR at merge points. When control flow can arrive from more than one
 *       place (a label), a variable may hold different values on different paths,
 *       so nothing is known any more. We conservatively drop the whole environment.
 *       Real compilers do better here using SSA form with phi nodes -- see
 *       Wegman & Zadeck's Sparse Conditional Constant Propagation (SCCP).
 *   (c) Never propagate INTO a definition, only into uses.
 *
 * COMPLEXITY: O(n) per iteration over n instructions, with O(1) map operations.
 */
public final class ConstantPropagationPass implements Pass {

    @Override public String name() { return "Constant Propagation"; }

    @Override public boolean run(Program program) {
        Map<String, Integer> known = new HashMap<>();
        boolean changed = false;

        for (Ir.Instr instr : program.main) {

            // --- Rule (b): a label is a control-flow merge point. Two different
            // paths may reach it with two different values, so we know nothing. ---
            if (instr instanceof Ir.Label) {
                known.clear();
                continue;
            }

            // --- Step 1: substitute known variables inside the operands we READ ---
            if (instr instanceof Ir.Assign) {
                Ir.Assign a = (Ir.Assign) instr;
                Operandish s = subst(a.src, known);
                if (s.changed) { a.src = s.operand; changed = true; }

            } else if (instr instanceof Ir.BinOp) {
                Ir.BinOp b = (Ir.BinOp) instr;
                Operandish l = subst(b.left, known);
                Operandish r = subst(b.right, known);
                if (l.changed) { b.left = l.operand; changed = true; }
                if (r.changed) { b.right = r.operand; changed = true; }

            } else if (instr instanceof Ir.Shift) {
                Ir.Shift s = (Ir.Shift) instr;
                Operandish v = subst(s.value, known);
                if (v.changed) { s.value = v.operand; changed = true; }

            } else if (instr instanceof Ir.Print) {
                Ir.Print p = (Ir.Print) instr;
                Operandish v = subst(p.value, known);
                if (v.changed) { p.value = v.operand; changed = true; }

            } else if (instr instanceof Ir.IfFalseGoto) {
                Ir.IfFalseGoto f = (Ir.IfFalseGoto) instr;
                Operandish c = subst(f.cond, known);
                if (c.changed) { f.cond = c.operand; changed = true; }

            } else if (instr instanceof Ir.Return) {
                Ir.Return r = (Ir.Return) instr;
                Operandish v = subst(r.value, known);
                if (v.changed) { r.value = v.operand; changed = true; }

            } else if (instr instanceof Ir.Call) {
                Ir.Call c = (Ir.Call) instr;
                for (int i = 0; i < c.args.size(); i++) {
                    Operandish a = subst(c.args.get(i), known);
                    if (a.changed) { c.args.set(i, a.operand); changed = true; }
                }
            }

            // --- Step 2: update what we know, based on what this instruction WRITES ---
            String written = instr.def();
            if (written != null) {
                if (instr instanceof Ir.Assign && ((Ir.Assign) instr).src.isConst) {
                    // x = 30  -> we now know x
                    known.put(written, ((Ir.Assign) instr).src.value);
                } else {
                    // Rule (a): x was written with something we cannot prove constant.
                    // Anything we used to know about x is now stale. KILL it.
                    known.remove(written);
                }
            }
        }
        return changed;
    }

    private static final class Operandish {
        final Ir.Operand operand; final boolean changed;
        Operandish(Ir.Operand o, boolean c) { operand = o; changed = c; }
    }

    /** Replace a variable operand by its literal value if we currently know it. */
    private static Operandish subst(Ir.Operand op, Map<String, Integer> known) {
        if (op.isVar() && known.containsKey(op.name)) {
            return new Operandish(Ir.Operand.num(known.get(op.name)), true);
        }
        return new Operandish(op, false);
    }
}
