package demos;

/**
 * ===========================================================================
 *  DEMO 2 -- DEAD CODE ELIMINATION
 * ===========================================================================
 *
 * DEFINITION
 * Dead code is code whose removal cannot change the program's observable
 * behaviour. Eliminating it makes the binary smaller, improves instruction-cache
 * behaviour, and -- crucially -- gives the remaining passes less to analyse.
 *
 * THE THREE KINDS YOU WILL SEE IN THIS FILE
 *   1. UNREACHABLE CODE  -- control flow can never arrive (after a return, or
 *      inside `if (false)`).
 *   2. DEAD STORES       -- reachable, but the value written is never read.
 *   3. DEAD BRANCHES     -- a condition the compiler can prove is always
 *      true/false, so one whole arm disappears.
 *
 * THE JAVA-SPECIFIC TWIST WORTH SAYING OUT LOUD
 * Java splits this responsibility in a way no other pass does:
 *
 *   - javac REFUSES to compile statement-level unreachable code. `int x = 1;
 *     return x; System.out.println();` is a COMPILE ERROR ("unreachable
 *     statement", JLS 14.22), not an optimization. The language decided this is a
 *     bug worth telling you about.
 *
 *   - BUT javac deliberately carves out an exception for `if (false) { ... }`.
 *     The body is NOT an error -- it is silently dropped from the bytecode.
 *     That exception exists specifically to support "conditional compilation":
 *     the classic `if (DEBUG) { ... }` idiom, where DEBUG is a `static final
 *     boolean`. Flip the flag to false and the entire block costs zero bytes.
 *
 *   - Everything else (dead stores, dead branches after inlining) is left to the
 *     JIT, which removes it once the method is hot.
 *
 * PROVE IT LIVE:  javap -c -p demos.DeadCodeEliminationDemo
 * Look at conditionalCompilation(): the DEBUG block is simply not in the bytecode.
 */
public class DeadCodeEliminationDemo {

    /** The classic conditional-compilation switch. Flip to true and recompile. */
    private static final boolean DEBUG = false;

    private static final int VERSION = 2;

    // -----------------------------------------------------------------------
    // BEFORE -- full of code that cannot affect the result
    // -----------------------------------------------------------------------

    /**
     * Four separate kinds of dead code in one method. Read each comment: none of
     * these lines can change what the method returns.
     */
    static int computeUnoptimized(int input) {

        // (1) DEAD STORE. `scratch` is written and then immediately overwritten.
        //     The first value is never read by anyone, so the whole computation
        //     feeding it is dead too.
        int scratch = input * 17 + 42;
        scratch = input + 1;

        // (2) DEAD STORE, whole variable. Nothing ever reads `neverRead`, so both
        //     the assignment AND the expensive call feeding it can go.
        int neverRead = expensiveLookingCalculation(input);

        // (3) DEAD BRANCH. VERSION is a compile-time constant, so `VERSION < 0` is
        //     provably false. The entire `if` body is unreachable.
        if (VERSION < 0) {
            return -1;
        }

        // (4) DEAD BRANCH via conditional compilation. javac drops this block
        //     entirely because DEBUG is a `static final boolean` equal to false.
        if (DEBUG) {
            System.out.println("[debug] input=" + input + " scratch=" + scratch);
        }

        return scratch * 2;
        // (5) Anything written here would be a COMPILE ERROR in Java, not dead
        //     code: "unreachable statement". Try uncommenting to see for yourself.
        // System.out.println("never");
    }

    /**
     * Deliberately expensive-looking so the audience feels the cost being removed.
     * Because nothing reads its result in computeUnoptimized, the JIT can delete
     * the whole call once it proves the method is pure.
     */
    static int expensiveLookingCalculation(int n) {
        int acc = 0;
        for (int i = 0; i < 1000; i++) acc += (n * i) % 7;
        return acc;
    }

    // -----------------------------------------------------------------------
    // AFTER -- what survives, and nothing else
    // -----------------------------------------------------------------------

    /**
     * Everything the compiler proved could not affect the result is gone.
     * Same inputs, same outputs, a fraction of the work.
     */
    static int computeOptimized(int input) {
        int scratch = input + 1;
        return scratch * 2;
    }

    // -----------------------------------------------------------------------
    // THE LIMIT: what DCE is not allowed to touch
    // -----------------------------------------------------------------------

    /**
     * `counter` is never read either -- but the increment is on a field visible to
     * other threads, and println is observable output. Neither may be removed.
     *
     * This is the whole contract: an optimizer may remove COMPUTATION, never
     * OBSERVABLE EFFECT. Deciding which is which (I/O, volatile writes, exceptions,
     * synchronization) is most of the difficulty in a real DCE implementation.
     */
    static volatile int counter = 0;

    static void hasSideEffectsSoStays() {
        counter++;                                    // visible to other threads
        System.out.println("  ...this line can never be optimized away");
    }

    public static void main(String[] args) {
        System.out.println("=== DEAD CODE ELIMINATION ===\n");
        System.out.println("DEBUG flag is " + DEBUG + " -> the debug block is not even in the bytecode\n");

        for (int input : new int[] { 0, 7, -3, 1000 }) {
            int before = computeUnoptimized(input);
            int after  = computeOptimized(input);
            System.out.printf("  input=%-6d before=%-8d after=%-8d identical=%s%n",
                    input, before, after, before == after);
        }

        System.out.println("\n-- what DCE must NOT remove --");
        hasSideEffectsSoStays();
        System.out.println("  counter is now " + counter);

        System.out.println("\nProve it yourself:  javap -c -p demos.DeadCodeEliminationDemo");
        System.out.println("  GONE from the bytecode (javac removed them):");
        System.out.println("    - the whole  if (DEBUG) { ... }  block");
        System.out.println("    - the whole  if (VERSION < 0) { ... }  block");
        System.out.println("  STILL THERE in the bytecode (javac does NOT do dead-store elimination):");
        System.out.println("    - scratch = input * 17 + 42     (imul / iadd / istore are all still emitted)");
        System.out.println("    - the call to expensiveLookingCalculation");
        System.out.println("  Those two are removed later, by the JIT, once the method gets hot.");
        System.out.println("  That split is the lesson: javac is a translator, the JIT is the optimizer.");
    }
}
