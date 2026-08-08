package optimizer;

import java.util.*;

/**
 * OPTIMIZATION 2 -- DEAD CODE ELIMINATION (DCE)
 *
 * "Dead code" is code whose removal cannot change what the program does.
 * It comes in two distinct flavours and we handle both:
 *
 *   A) UNREACHABLE CODE -- control flow can never get there at all.
 *        goto END
 *        print(999)      <-- nothing can reach this
 *      This appears constantly AFTER other passes: peephole turns a constant
 *      branch into a plain `goto`, and suddenly an entire block is orphaned.
 *
 *   B) DEAD STORES (useless assignments) -- reachable, but the value written is
 *      never read by anyone before being overwritten or before the program ends.
 *        unused = area + 99      <-- nobody ever reads `unused`
 *
 * ALGORITHM FOR (B): BACKWARD LIVENESS ANALYSIS
 * A variable is LIVE at a point if some later instruction reads it before it is
 * rewritten. So we walk the program BACKWARDS carrying a set of live variables:
 *
 *   for each instruction, from last to first:
 *       if it defines x, and x is NOT live, and it has no side effect  -> DELETE it
 *       otherwise:
 *           kill:  remove its defined variable from the live set
 *           gen:   add every variable it reads to the live set
 *
 * Order matters: KILL before GEN, so that `x = x + 1` correctly keeps x live.
 *
 * THE SIDE-EFFECT ESCAPE HATCH
 * `print(...)`, `goto`, and calls are marked hasSideEffect(). We never delete them,
 * even though they define nothing. Without that rule the optimizer would "optimize"
 * the program into an empty file -- technically fast, entirely useless. Deciding
 * what counts as observable behaviour is the compiler's real contract with you;
 * it is why C++ calls it the "as-if rule".
 *
 * WHY THIS PASS MUST RUN LAST *AND* REPEATEDLY
 * Constant propagation makes assignments useless but does not remove them. DCE is
 * the pass that actually shrinks the program. And removing one instruction can make
 * an earlier one dead in turn, so it pays to run the whole pipeline to a fixed point.
 *
 * KNOWN LIMITATION (on purpose -- ask us about this in the demo!)
 * Our liveness scan is a single straight-line backward pass. That is exactly right
 * for code without loops, which is what our demo program is. With a BACK-EDGE
 * (`goto` jumping upward), a store can be read on the NEXT iteration by an
 * instruction that appears EARLIER in the list, and a single backward scan would
 * wrongly call it dead. A production compiler fixes this by building a control-flow
 * graph and iterating the live-in/live-out equations to a fixed point. We kept the
 * simple version so the core idea stays visible -- but we know where the edge is.
 *
 * COMPLEXITY: O(n) per iteration (one backward scan), O(1) set operations.
 */
public final class DeadCodeEliminationPass implements Pass {

    @Override public String name() { return "Dead Code Elimination"; }

    @Override public boolean run(Program program) {
        boolean changed = removeUnreachable(program);
        changed |= removeDeadStores(program);
        return changed;
    }

    // ------------------------------------------------------------------
    // (A) UNREACHABLE CODE
    // ------------------------------------------------------------------
    private boolean removeUnreachable(Program program) {
        // Which labels can anything actually jump to? A label nobody targets cannot
        // be re-entered, so code after a `goto` really is orphaned.
        Set<String> targeted = new HashSet<>();
        for (Ir.Instr i : program.main) {
            if (i instanceof Ir.Goto)        targeted.add(((Ir.Goto) i).target);
            if (i instanceof Ir.IfFalseGoto) targeted.add(((Ir.IfFalseGoto) i).target);
        }

        List<Ir.Instr> out = new ArrayList<>();
        boolean reachable = true;
        boolean changed = false;

        for (Ir.Instr instr : program.main) {
            if (instr instanceof Ir.Label) {
                // Control can land here from a jump, so we are reachable again.
                if (targeted.contains(((Ir.Label) instr).name)) {
                    reachable = true;
                    out.add(instr);
                } else {
                    changed = true;      // a label nobody jumps to is pure noise
                }
                continue;
            }

            if (!reachable) { changed = true; continue; }   // drop orphaned instruction

            out.add(instr);

            // After an unconditional jump (or a return), the next instruction can
            // only be entered via a label. Mark everything until then unreachable.
            if (instr instanceof Ir.Goto || instr instanceof Ir.Return) reachable = false;
        }

        program.main = out;
        return changed;
    }

    // ------------------------------------------------------------------
    // (B) DEAD STORES -- backward liveness
    // ------------------------------------------------------------------
    private boolean removeDeadStores(Program program) {
        Set<String> live = new HashSet<>();
        Deque<Ir.Instr> out = new ArrayDeque<>();
        boolean changed = false;

        // Walk BACKWARDS: to know whether a store is useful we must first know
        // what the code AFTER it reads.
        for (int i = program.main.size() - 1; i >= 0; i--) {
            Ir.Instr instr = program.main.get(i);

            /*
             * Conservative handling of control flow: at a jump we do not know which
             * path we came from, so we must not claim a variable is dead. Clearing
             * the live set would be WRONG (it would delete live stores), so instead
             * we treat labels/jumps as "everything stays live" by simply not
             * removing anything from the set at those points. The variables read by
             * the jump itself are added below via uses().
             */
            String defined = instr.def();

            if (defined != null && !instr.hasSideEffect() && !live.contains(defined)) {
                // Nobody downstream reads this variable, and computing it cannot be
                // observed. The instruction is dead. Delete it.
                changed = true;
                continue;
            }

            // KILL first: this instruction supplies `defined`, so earlier writes to
            // it are not needed for anything after this point.
            if (defined != null) live.remove(defined);

            // GEN second: everything this instruction reads must be live before it.
            for (Ir.Operand op : instr.uses()) {
                if (op != null && op.isVar()) live.add(op.name);
            }

            out.addFirst(instr);
        }

        program.main = new ArrayList<>(out);
        return changed;
    }
}
