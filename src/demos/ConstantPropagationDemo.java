package demos;

/**
 * ===========================================================================
 *  DEMO 3 -- CONSTANT PROPAGATION
 * ===========================================================================
 *
 * DEFINITION
 * Constant propagation replaces every READ of a variable with the literal value
 * that variable is provably holding at that point.
 *
 * FOLDING vs PROPAGATION -- the distinction people always blur
 *
 *      CONSTANT FOLDING       evaluates    2 * 3        ->  6
 *      CONSTANT PROPAGATION   substitutes  x, given x=6 ->  6
 *
 * Folding needs literals to work on. Propagation is what MANUFACTURES those
 * literals. Neither is very powerful alone; together they cascade, and that
 * cascade is the point of this file:
 *
 *      RATE = 3                 (start)
 *      base  = 100
 *      total = base * RATE      --prop--> 100 * 3   --fold--> 300
 *      tax   = total / 10       --prop--> 300 / 10  --fold--> 30
 *      final = total + tax      --prop--> 300 + 30  --fold--> 330
 *
 * One known constant at the top turned five run-time operations into one literal.
 * That is why LLVM does not ship "constant propagation" and "constant folding" as
 * separate user-visible passes but as SCCP -- Sparse Conditional Constant
 * Propagation (Wegman & Zadeck, 1991) -- which interleaves both plus reachability
 * in a single fixed-point algorithm.
 *
 * THE RULE THAT KEEPS IT CORRECT: KILL ON REDEFINITION
 * The moment a variable is assigned something the compiler cannot prove constant,
 * everything it knew about that variable becomes stale and must be discarded.
 * See mustStopPropagating() below -- forgetting this is how optimizers miscompile.
 */
public class ConstantPropagationDemo {

    private static final int RATE = 3;

    // -----------------------------------------------------------------------
    // BEFORE -- a chain of named intermediate values, as a human would write it
    // -----------------------------------------------------------------------

    /**
     * Every line here is readable and every line is redundant. The compiler walks
     * forward carrying "what do I currently know?" and substitutes:
     *
     *   after line 1  known = { base: 100 }
     *   after line 2  known = { base: 100, total: 300 }     (propagated, then folded)
     *   after line 3  known = { ..., tax: 30 }
     *   after line 4  known = { ..., result: 330 }
     */
    static int invoiceUnoptimized() {
        int base   = 100;
        int total  = base * RATE;      // -> 100 * 3  -> 300
        int tax    = total / 10;       // -> 300 / 10 -> 30
        int result = total + tax;      // -> 300 + 30 -> 330
        return result;
    }

    /**
     * Propagation's most valuable effect is not arithmetic -- it is DECIDING
     * BRANCHES. Once the compiler knows `mode` is 1, the condition is not a
     * condition any more, and one whole arm becomes dead code.
     * This is precisely the hand-off between propagation and DCE.
     */
    static String selectStrategyUnoptimized() {
        int mode = 1;
        if (mode == 0) {
            return "batch";            // provably unreachable
        } else if (mode == 1) {
            return "streaming";        // provably the only outcome
        } else {
            return "hybrid";           // provably unreachable
        }
    }

    // -----------------------------------------------------------------------
    // AFTER -- the propagation cascade run to completion
    // -----------------------------------------------------------------------

    /** Five operations and four locals collapsed into one literal. */
    static int invoiceOptimized() {
        return 330;
    }

    /** The branch is gone because there was never really a choice. */
    static String selectStrategyOptimized() {
        return "streaming";
    }

    // -----------------------------------------------------------------------
    // THE LIMIT: where propagation must give up
    // -----------------------------------------------------------------------

    /**
     * `x` starts known, but then gets a value that depends on a run-time argument.
     * At that instant the compiler must FORGET x. Everything after the kill point
     * stays as real run-time arithmetic.
     *
     * Say this out loud in the presentation: an optimizer's intelligence is
     * measured by how much it can prove, and its CORRECTNESS is measured by how
     * fast it admits it cannot prove something.
     */
    static int mustStopPropagating(int runtimeInput) {
        int x = 5;
        int a = x * 2;          // known: x == 5   -> propagates -> folds to 10

        x = runtimeInput;       // KILL POINT: x is no longer a known constant

        int b = x * 2;          // must stay a real multiply -- x is unknown now
        return a + b;
    }

    /**
     * The join-point problem. Two paths reach the same statement with two
     * different values, so nothing is known afterwards. Real compilers solve this
     * with SSA form and phi nodes; simple implementations (like our MiniOptimizer)
     * conservatively forget everything at a merge point.
     */
    static int joinPoint(boolean flag) {
        int v;
        if (flag) { v = 10; } else { v = 20; }
        return v * 2;           // v is 10 OR 20 -> not a constant -> no propagation
    }

    public static void main(String[] args) {
        System.out.println("=== CONSTANT PROPAGATION ===\n");

        System.out.println("invoice        before=" + invoiceUnoptimized()
                         + "  after=" + invoiceOptimized()
                         + "  identical=" + (invoiceUnoptimized() == invoiceOptimized()));

        System.out.println("selectStrategy before=" + selectStrategyUnoptimized()
                         + "  after=" + selectStrategyOptimized()
                         + "  identical=" + selectStrategyUnoptimized().equals(selectStrategyOptimized()));

        System.out.println("\n-- the cascade, step by step --");
        System.out.println("  base   = 100");
        System.out.println("  total  = base * RATE   -> 100 * 3   -> 300");
        System.out.println("  tax    = total / 10    -> 300 / 10  -> 30");
        System.out.println("  result = total + tax   -> 300 + 30  -> 330");

        System.out.println("\n-- where propagation must stop --");
        for (int in : new int[] { 1, 50 }) {
            System.out.println("  mustStopPropagating(" + in + ") = " + mustStopPropagating(in)
                             + "   (10 is folded; the rest is real run-time work)");
        }
        System.out.println("  joinPoint(true)=" + joinPoint(true)
                         + "  joinPoint(false)=" + joinPoint(false)
                         + "   -> v is not a constant at the merge point");
    }
}
