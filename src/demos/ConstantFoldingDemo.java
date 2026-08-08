package demos;

/**
 * ===========================================================================
 *  DEMO 1 -- CONSTANT FOLDING
 * ===========================================================================
 *
 * DEFINITION
 * Constant folding is the compiler evaluating an expression AT COMPILE TIME when
 * all of its operands are already known constants, and replacing the expression
 * with the resulting literal.
 *
 * THE KEY INSIGHT FOR THIS FILE
 * You do not need `javac -O` for this. Java has NO optimization flag -- and yet
 * folding still happens, in TWO separate places:
 *
 *   (1) javac itself folds "compile-time constant expressions" (JLS 15.29). Any
 *       expression built only from literals and `static final` primitives is
 *       replaced by its value IN THE .class FILE. You can see it with `javap -c`.
 *
 *   (2) The JIT (HotSpot C1/C2) folds everything javac could not prove, once the
 *       method gets hot.
 *
 * HOW TO PROVE IT DURING THE PRESENTATION (do this live -- it is the best moment):
 *     javac -d out demos/ConstantFoldingDemo.java
 *     javap -c -p -cp out demos.ConstantFoldingDemo
 * Look at bufferSizeUnoptimized(): the bytecode contains NO imul instructions.
 * javac already did the multiplication and emitted a single `ldc 4194304`.
 * The "before" and "after" methods compile to literally the same bytecode.
 */
public class ConstantFoldingDemo {

    // `static final` + a constant initializer == a COMPILE-TIME CONSTANT.
    // Every use of these below is substituted by its value inside the .class file.
    private static final int KILOBYTE = 1024;
    private static final int PAGES    = 4;

    // -----------------------------------------------------------------------
    // BEFORE -- written for humans, with the arithmetic spelled out
    // -----------------------------------------------------------------------

    /**
     * A reader instantly sees "4 pages of 1024 KB". Naively you would expect three
     * multiplications every call.
     * In reality javac emits: ldc 4194304; ireturn.  Zero multiplications.
     */
    static int bufferSizeUnoptimized() {
        return KILOBYTE * KILOBYTE * PAGES;      // 1024 * 1024 * 4
    }

    /**
     * Milliseconds in a day, written so the units are obvious.
     * Again: folded by javac into the single literal 86400000.
     */
    static long millisPerDayUnoptimized() {
        return 24L * 60L * 60L * 1000L;
    }

    /**
     * Constant folding inside a LOOP is where the win becomes enormous. The
     * expression below does not depend on `i` at all, so recomputing it every
     * iteration would waste `iterations` multiplications.
     */
    static long loopUnoptimized(int iterations) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += i * (KILOBYTE * PAGES);     // (1024*4) is constant -> folded to 4096
        }
        return total;
    }

    // -----------------------------------------------------------------------
    // AFTER -- what the compiler actually produced (hand-written for comparison)
    // -----------------------------------------------------------------------

    /** Exactly the bytecode javac generated for bufferSizeUnoptimized(). */
    static int bufferSizeOptimized() {
        return 4194304;                          // folded: 1024 * 1024 * 4
    }

    /** Exactly the bytecode javac generated for millisPerDayUnoptimized(). */
    static long millisPerDayOptimized() {
        return 86400000L;                        // folded: 24 * 60 * 60 * 1000
    }

    /** The multiplication that never depended on `i` is now a single literal. */
    static long loopOptimized(int iterations) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += i * 4096;                   // folded
        }
        return total;
    }

    // -----------------------------------------------------------------------
    // WHERE FOLDING MUST *REFUSE* TO FIRE -- the correctness half of the story
    // -----------------------------------------------------------------------

    /**
     * `divisor` is NOT final, so it is not a compile-time constant. javac cannot
     * fold `100 / divisor` even though we can see it is 0 -- and it must not,
     * because the program is REQUIRED to throw ArithmeticException here.
     *
     * The rule every optimization obeys: if folding would change observable
     * behaviour (including which exceptions are thrown), do not fold.
     */
    static int mustNotBeFolded() {
        int divisor = 0;
        try {
            return 100 / divisor;                // must still throw at run time
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    /**
     * Floating point is the other refusal case. 0.1 + 0.2 is not 0.3, and a
     * compiler folding this must reproduce the target CPU's rounding EXACTLY.
     * Java pins this down with strict IEEE-754 semantics so the folded value and
     * the runtime value can never disagree.
     */
    static boolean floatingPointIsNotAlgebra() {
        return (0.1 + 0.2) == 0.3;               // false -- and folding must preserve that
    }

    public static void main(String[] args) {
        System.out.println("=== CONSTANT FOLDING ===\n");

        System.out.println("bufferSize   before=" + bufferSizeUnoptimized()
                         + "  after=" + bufferSizeOptimized()
                         + "  identical=" + (bufferSizeUnoptimized() == bufferSizeOptimized()));

        System.out.println("millisPerDay before=" + millisPerDayUnoptimized()
                         + "  after=" + millisPerDayOptimized()
                         + "  identical=" + (millisPerDayUnoptimized() == millisPerDayOptimized()));

        System.out.println("loop(1000)   before=" + loopUnoptimized(1000)
                         + "  after=" + loopOptimized(1000)
                         + "  identical=" + (loopUnoptimized(1000) == loopOptimized(1000)));

        System.out.println("\n-- where folding must refuse --");
        System.out.println("100/0 still throws at run time -> caught, returned " + mustNotBeFolded());
        System.out.println("(0.1 + 0.2) == 0.3 ? " + floatingPointIsNotAlgebra()
                         + "   <- folding must reproduce this exactly");

        System.out.println("\nProve it yourself:  javap -c -p demos.ConstantFoldingDemo");
        System.out.println("bufferSizeUnoptimized() contains NO imul -- javac already folded it.");
    }
}
