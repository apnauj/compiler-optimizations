package optimizer;

import java.util.*;

/**
 * OPTIMIZATION 5 -- INLINE EXPANSION (method inlining)
 *
 * IDEA
 * Replace a call with a copy of the callee's body.
 *
 *     t = square(w)                    $sq0_n = w
 *     ...                    ==>       $sq0_r = $sq0_n * $sq0_n
 *     square(n) { return n*n }         t      = $sq0_r
 *
 * WHY IT IS CALLED "THE MOTHER OF ALL OPTIMIZATIONS"
 * Removing the call overhead (push arguments, jump, build a frame, jump back) is
 * the small win. The big win is SECOND-ORDER: once the callee's body is sitting in
 * the caller, all the OTHER passes can finally see through the call boundary.
 * In our pipeline, inlining `square(w)` is what lets constant propagation learn
 * n == 30, which lets constant folding compute 900, which lets dead code
 * elimination delete the leftovers. None of that could happen while the call was
 * still opaque. Inlining does not just optimize -- it CREATES optimization
 * opportunities. That is why it runs first in our pipeline.
 *
 * THE COST: CODE SIZE
 * Every inlined copy duplicates the body. Inline too eagerly and the program grows,
 * stops fitting in the CPU instruction cache, and gets SLOWER. So every real
 * compiler applies a heuristic budget:
 *   - HotSpot C2: -XX:MaxInlineSize=35 (inline any method under 35 bytes of
 *     bytecode), -XX:FreqInlineSize=325 (a bigger budget for hot call sites),
 *     -XX:MaxInlineLevel=9 (how deep the nesting may go). You can watch it happen
 *     live with -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining.
 *   - Recursion must be bounded, or inlining never terminates.
 *   - Virtual calls are the hard case: the JVM cannot inline what it cannot resolve,
 *     so it uses profiling data to guess the likely target and guards the inlined
 *     copy with a type check, falling back to a real call if the guess is wrong.
 *
 * THE CORRECTNESS TRAP: VARIABLE CAPTURE
 * If the callee has a local called `n` and the caller ALSO has an `n`, naive copying
 * makes them collide and silently corrupts both. We avoid it by ALPHA-RENAMING:
 * every name in the copied body gets a fresh unique prefix ($sq0_) that no user
 * variable can produce.
 *
 * COMPLEXITY: O(number of call sites * size of callee).
 */
public final class InlineExpansionPass implements Pass {

    /** Our inlining budget, in IR instructions. HotSpot's equivalent is MaxInlineSize=35. */
    private static final int MAX_INLINE_SIZE = 12;

    @Override public String name() { return "Inline Expansion"; }

    @Override public boolean run(Program program) {
        List<Ir.Instr> out = new ArrayList<>();
        boolean changed = false;

        for (Ir.Instr instr : program.main) {
            if (!(instr instanceof Ir.Call)) { out.add(instr); continue; }

            Ir.Call call = (Ir.Call) instr;
            Program.Function callee = program.functions.get(call.function);

            // Refuse to inline when we cannot see the body, when the arity does not
            // match, or when the body busts the budget. Refusing is always safe:
            // the call simply stays a call.
            if (callee == null
                    || callee.params.size() != call.args.size()
                    || callee.size() > MAX_INLINE_SIZE) {
                out.add(instr);
                continue;
            }

            String prefix = program.freshPrefix(call.function);   // e.g. "$square0_"

            // STEP 1 -- bind arguments to (renamed) parameters.
            // This is what a real call does at run time; we do it at compile time.
            for (int i = 0; i < callee.params.size(); i++) {
                out.add(new Ir.Assign(prefix + callee.params.get(i), call.args.get(i)));
            }

            // STEP 2 -- copy the body, renaming every variable it mentions,
            // and turning `return v` into `dest = v`.
            for (Ir.Instr body : callee.body) {
                if (body instanceof Ir.Return) {
                    Ir.Return r = (Ir.Return) body;
                    out.add(new Ir.Assign(call.dest, rename(r.value, prefix)));
                } else {
                    out.add(renameInstr(body, prefix));
                }
            }

            changed = true;
        }

        program.main = out;
        return changed;
    }

    /** Alpha-renaming: give a copied variable a prefix nothing else can collide with. */
    private static Ir.Operand rename(Ir.Operand op, String prefix) {
        return op.isConst ? op : Ir.Operand.var(prefix + op.name);
    }

    private static Ir.Instr renameInstr(Ir.Instr instr, String prefix) {
        if (instr instanceof Ir.Assign) {
            Ir.Assign a = (Ir.Assign) instr;
            return new Ir.Assign(prefix + a.dest, rename(a.src, prefix));
        }
        if (instr instanceof Ir.BinOp) {
            Ir.BinOp b = (Ir.BinOp) instr;
            return new Ir.BinOp(prefix + b.dest, rename(b.left, prefix), b.op, rename(b.right, prefix));
        }
        if (instr instanceof Ir.Print) {
            return new Ir.Print(rename(((Ir.Print) instr).value, prefix));
        }
        if (instr instanceof Ir.Shift) {
            Ir.Shift s = (Ir.Shift) instr;
            return new Ir.Shift(prefix + s.dest, rename(s.value, prefix), s.bits, s.leftShift);
        }
        // Labels/jumps inside a callee would also need renaming; our callees are
        // straight-line, so we keep the pass small and honest about its scope.
        return instr.copy();
    }
}
