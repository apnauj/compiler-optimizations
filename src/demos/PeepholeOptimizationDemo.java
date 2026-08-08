package demos;

/**
 * ===========================================================================
 *  DEMO 4 -- PEEPHOLE OPTIMIZATION
 * ===========================================================================
 *
 * DEFINITION
 * Slide a small window -- typically 2 to 4 instructions -- across the generated
 * code. When the instructions in the window match a known-suboptimal pattern,
 * replace them with a cheaper sequence that provably computes the same thing.
 * Then slide the window on and repeat.
 *
 * ORIGIN: W. M. McKeeman, "Peephole optimization", CACM 8(7), 1965. Sixty years
 * later it is still one of the highest-value passes in every production compiler:
 * LLVM's InstCombine and GCC's `combine` pass are peepholes with thousands of rules.
 *
 * THE OBJECTION YOU WILL GET FROM THE AUDIENCE
 * "Nobody writes `x * 1`." Correct -- and irrelevant. Nobody WRITES it; compilers
 * GENERATE it. These patterns are produced by:
 *   - inlining a method whose default parameter happens to be 0 or 1,
 *   - generic/templated code specialised for a particular type,
 *   - array indexing lowered to address arithmetic (base + i*elementSize),
 *   - and above all, by the other optimization passes.
 * Peephole is the pass that cleans up after the rest of the pipeline.
 *
 * THE FOUR RULE FAMILIES DEMONSTRATED HERE
 *   1. ALGEBRAIC IDENTITIES  x+0, x*1, x-0, x/1   -> same answer, no instruction
 *   2. STRENGTH REDUCTION    x*8 -> x<<3, x%8 -> x&7 on a cheaper functional unit
 *   3. REDUNDANT MOVES       store then immediately load the same slot
 *   4. JUMP THREADING        a jump whose target is the very next instruction
 *
 * ROUGH COSTS ON A MODERN x86-64 CORE (latency in cycles -- why this pays off):
 *   shift/add/and : 1        integer multiply : 3-5        integer divide : 20-40
 */
public class PeepholeOptimizationDemo {

    // -----------------------------------------------------------------------
    // BEFORE -- patterns exactly like what a code generator emits
    // -----------------------------------------------------------------------

    /** RULE 1 -- algebraic identities. Four operations that do nothing. */
    static int identitiesUnoptimized(int x) {
        int a = x + 0;      // adding zero
        int b = a * 1;      // multiplying by one
        int c = b - 0;      // subtracting zero
        int d = c / 1;      // dividing by one
        return d;
    }

    /** RULE 2 -- strength reduction. Multiply/divide/modulo by powers of two. */
    static int strengthReductionUnoptimized(int x) {
        int scaled    = x * 8;      // multiply: 3-5 cycles
        int halved    = x / 2;      // divide:  20-40 cycles (the expensive one)
        int remainder = x % 8;      // modulo is a divide underneath
        return scaled + halved + remainder;
    }

    /** RULE 3 -- redundant store/load. The second line re-reads what we just wrote. */
    static int redundantMovesUnoptimized(int x) {
        int temp = x;
        int y = temp;       // y and temp and x are all the same value
        int z = y;
        return z;
    }

    /**
     * RULE 4 -- jump threading. Each branch here jumps to code that immediately
     * jumps again. A peephole collapses the chain into a single direct jump.
     */
    static int jumpThreadingUnoptimized(int x) {
        if (x > 0) {
            if (x > 0) {            // the same test, immediately re-tested
                return 1;
            }
            return 0;
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // AFTER -- the window slid over, the patterns rewritten
    // -----------------------------------------------------------------------

    /** All four identities collapse to "return the input". */
    static int identitiesOptimized(int x) {
        return x;
    }

    /**
     * Multiply and modulo become shift and mask.
     *
     * IMPORTANT SUBTLETY -- read this before claiming x/2 == x>>1:
     * For NEGATIVE x they DISAGREE. Java's `/` truncates toward zero (-9/2 == -4)
     * while `>>` floors (-9>>1 == -5). Likewise -9 % 8 == -1 but -9 & 7 == 7.
     * A real compiler either proves x >= 0 first, or emits a small correction
     * sequence. We keep the honest version below: only the multiply is reduced,
     * and the signed divide is left alone.
     *
     * This is the single most valuable thing to say about peepholes: a rewrite
     * that is *usually* right is a bug, not an optimization.
     */
    static int strengthReductionOptimized(int x) {
        int scaled    = x << 3;     // safe for all x: multiply by 8 == shift left 3
        int halved    = x / 2;      // left as-is: x may be negative
        int remainder = x % 8;      // left as-is: x may be negative
        return scaled + halved + remainder;
    }

    /** The version that IS safe, once non-negativity is proven. */
    static int strengthReductionOptimizedWhenNonNegative(int x) {
        if (x < 0) throw new IllegalArgumentException("precondition: x >= 0");
        return (x << 3) + (x >> 1) + (x & 7);
    }

    /** The chain of copies is just the original value. */
    static int redundantMovesOptimized(int x) {
        return x;
    }

    /** The duplicated test is folded into one. */
    static int jumpThreadingOptimized(int x) {
        return x > 0 ? 1 : -1;
    }

    public static void main(String[] args) {
        System.out.println("=== PEEPHOLE OPTIMIZATION ===\n");

        int[] inputs = { 0, 1, 7, 64, -9, -1 };

        System.out.println("  x      identities        strength-red.      moves        jump-thread.");
        System.out.println("  ----   ---------------   ----------------   ----------   -------------");
        boolean allMatch = true;
        for (int x : inputs) {
            boolean i1 = identitiesUnoptimized(x)        == identitiesOptimized(x);
            boolean i2 = strengthReductionUnoptimized(x) == strengthReductionOptimized(x);
            boolean i3 = redundantMovesUnoptimized(x)    == redundantMovesOptimized(x);
            boolean i4 = jumpThreadingUnoptimized(x)     == jumpThreadingOptimized(x);
            allMatch &= i1 && i2 && i3 && i4;
            System.out.printf("  %-6d %-17s %-18s %-12s %s%n",
                    x, ok(i1), ok(i2), ok(i3), ok(i4));
        }
        System.out.println("\n  every rewrite agrees on every input: " + allMatch);

        System.out.println("\n-- the trap: why we did NOT reduce signed division --");
        System.out.println("  -9 / 2  = " + (-9 / 2)  + "     but  -9 >> 1 = " + (-9 >> 1));
        System.out.println("  -9 % 8  = " + (-9 % 8)  + "     but  -9 &  7 = " + (-9 & 7));
        System.out.println("  -> identical for x >= 0, WRONG for x < 0. The compiler must prove x >= 0 first.");

        System.out.println("\n-- the version that is safe once non-negativity is proven --");
        for (int x : new int[] { 0, 7, 64 }) {
            System.out.println("  x=" + x + "  generic=" + strengthReductionUnoptimized(x)
                             + "  reduced=" + strengthReductionOptimizedWhenNonNegative(x));
        }
    }

    private static String ok(boolean b) { return b ? "same" : "DIFFERENT!"; }
}
