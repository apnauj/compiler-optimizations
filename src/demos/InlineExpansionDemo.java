package demos;

/**
 * ===========================================================================
 *  DEMO 5 -- INLINE EXPANSION (method inlining)
 * ===========================================================================
 *
 * DEFINITION
 * Replace a call to a method with a copy of that method's body, substituting the
 * arguments for the parameters.
 *
 * WHY IT IS CALLED "THE MOTHER OF ALL OPTIMIZATIONS"
 * Removing call overhead (arguments, jump, stack frame, return) is the SMALL win.
 * The big win is second-order: with the body now sitting in the caller, every
 * other pass can finally see across the call boundary. Constant propagation can
 * push a caller's constant into the callee's code, folding can evaluate it, dead
 * code elimination can delete the leftovers. Inlining does not just optimize --
 * it CREATES the opportunities the rest of the pipeline feeds on.
 *
 * WHY THIS MATTERS ENORMOUSLY IN JAVA SPECIFICALLY
 * Java style is built on tiny methods: getters, setters, one-line helpers,
 * Optional.get, List.size. Without inlining, idiomatic Java would be crippled by
 * call overhead. HotSpot's inliner is the reason "write clean small methods" is
 * not performance advice you have to argue about.
 *
 * HOTSPOT'S ACTUAL BUDGET (these are the real flag names -- show them live):
 *   -XX:MaxInlineSize=35      inline any method whose bytecode is <= 35 bytes
 *   -XX:FreqInlineSize=325    a much bigger budget for HOT call sites
 *   -XX:MaxInlineLevel=9      how deep the nesting is allowed to go
 *   -XX:MinInliningThreshold  invocations required before a method is considered
 *
 * WATCH IT HAPPEN LIVE (this is the best moment in the whole presentation):
 *   java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining \
 *        -cp out demos.InlineExpansionDemo
 * You will see lines like "@ 12 demos.Point::getX (5 bytes) inlined (hot)".
 *
 * THE COST: CODE SIZE. Every inlined copy duplicates the body. Inline too much and
 * the program stops fitting in the CPU instruction cache and gets SLOWER. Every
 * inliner is a budget, not a rule.
 *
 * THE HARD CASE: VIRTUAL CALLS. The JVM cannot inline a call whose target it
 * cannot determine. It uses runtime profiling to guess the likely receiver type,
 * inlines that one, and guards it with a type check that falls back to a real call
 * if the guess was wrong. That is why a call site with ONE implementation
 * ("monomorphic") is far faster than one with three ("megamorphic").
 */
public class InlineExpansionDemo {

    /** A textbook inlining candidate: 3 lines, trivially small, called constantly. */
    static final class Point {
        private final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        int getX() { return x; }                 // ~5 bytes of bytecode
        int getY() { return y; }                 // ~5 bytes of bytecode
    }

    private static final int ITERATIONS = 20_000_000;

    // -----------------------------------------------------------------------
    // BEFORE -- clean, layered, four method calls per iteration
    // -----------------------------------------------------------------------

    static int square(int n)            { return n * n; }
    static int distanceSquared(Point a, Point b) {
        int dx = a.getX() - b.getX();            // 2 calls
        int dy = a.getY() - b.getY();            // 2 more
        return square(dx) + square(dy);          // 2 more
    }

    /** Six method calls per iteration, times twenty million. */
    static long sumUnoptimized(Point a, Point b, int iterations) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += distanceSquared(a, b);
        }
        return total;
    }

    // -----------------------------------------------------------------------
    // AFTER -- what the JIT produces: every call replaced by its body
    // -----------------------------------------------------------------------

    /**
     * This is the SAME code, hand-inlined so you can read what the JIT built.
     * Note what became possible only AFTER inlining: dx and dy are now ordinary
     * locals in the loop, so the JIT can additionally see they never change and
     * hoist the whole computation out of the loop (loop-invariant code motion).
     * None of that was visible while `distanceSquared` was an opaque call.
     */
    static long sumOptimized(Point a, Point b, int iterations) {
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            int dx = a.x - b.x;                  // getX() bodies inlined
            int dy = a.y - b.y;                  // getY() bodies inlined
            total += (dx * dx) + (dy * dy);      // square() bodies inlined
        }
        return total;
    }

    // -----------------------------------------------------------------------
    // THE LIMIT: a method too big to inline
    // -----------------------------------------------------------------------

    /**
     * Well over the 35-byte MaxInlineSize budget and not hot enough for the
     * 325-byte FreqInlineSize budget, so HotSpot leaves this one as a real call.
     * Refusing to inline is always safe -- the call simply stays a call.
     */
    static int tooBigToInline(int n) {
        int acc = 0;
        for (int i = 0; i < 32; i++) {
            acc += (n * i) % 13;
            acc ^= (acc << 3);
            acc += (i * i) - n;
            acc %= 1_000_003;
        }
        return acc;
    }

    public static void main(String[] args) {
        System.out.println("=== INLINE EXPANSION ===\n");

        Point a = new Point(10, 20);
        Point b = new Point(4, 8);

        // 1) CORRECTNESS FIRST. Inlining must not change any answer.
        boolean same = true;
        for (int n = 1; n <= 5; n++) {
            same &= sumUnoptimized(a, b, n) == sumOptimized(a, b, n);
        }
        System.out.println("  same result for every input: " + same);
        System.out.println("  distanceSquared((10,20),(4,8)) = " + distanceSquared(a, b)
                         + "   (6*6 + 12*12 = 36 + 144)\n");

        // 2) WARM-UP. Nothing is inlined until HotSpot decides the code is hot.
        //    Without this the first timing measures the interpreter, not the JIT.
        System.out.println("  warming up the JIT (this is why benchmarks need warm-up)...");
        for (int i = 0; i < 5; i++) {
            sumUnoptimized(a, b, 1_000_000);
            sumOptimized(a, b, 1_000_000);
        }

        // 3) TIMING.
        long t0 = System.nanoTime();
        long r1 = sumUnoptimized(a, b, ITERATIONS);
        long t1 = System.nanoTime();
        long r2 = sumOptimized(a, b, ITERATIONS);
        long t2 = System.nanoTime();

        System.out.printf("%n  layered (6 calls/iter) : %6.1f ms   result=%d%n", (t1 - t0) / 1e6, r1);
        System.out.printf("  hand-inlined           : %6.1f ms   result=%d%n", (t2 - t1) / 1e6, r2);

        // NOTE: plain string concatenation rather than a text block, so this file
        // compiles on Java 11 as well as on modern JDKs.
        System.out.println();
        System.out.println("  READ THE NUMBERS HONESTLY: they should be nearly IDENTICAL.");
        System.out.println("  That is not a failed demo -- it is the POINT. HotSpot already inlined the");
        System.out.println("  layered version for us, so the hand-inlined code has nothing left to win.");
        System.out.println("  The abstraction was free. Run with -Xint (interpreter only, no JIT) and the");
        System.out.println("  gap appears, because then nobody is inlining anything.");
        System.out.println();
        System.out.println("  See the inliner's decisions for yourself:");
        System.out.println("    java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -cp out demos.InlineExpansionDemo");
        System.out.println("    java -Xint -cp out demos.InlineExpansionDemo      (JIT off -- watch it crawl)");

        System.out.println("\n  tooBigToInline(7) = " + tooBigToInline(7)
                         + "   <- over the 35-byte budget, stays a real call");
    }
}
