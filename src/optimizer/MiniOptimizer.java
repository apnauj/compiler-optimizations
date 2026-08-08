package optimizer;

import java.util.*;

/**
 * MiniOptimizer.java -- the pipeline driver.
 *
 * THE TWO IDEAS THAT MAKE A COMPILER A COMPILER
 *
 * 1. ORDER MATTERS. Our pipeline is deliberately arranged so each pass sets up the
 *    next one:
 *        Inline Expansion    -> exposes the callee's body to everything else
 *        Constant Propagation-> turns variable reads into literals
 *        Constant Folding    -> evaluates the now-all-literal expressions
 *        Peephole            -> cleans up identities and resolves constant branches
 *        Dead Code Elimination-> deletes what the passes above made useless
 *
 * 2. ITERATE TO A FIXED POINT. One pass through is never enough, because every pass
 *    creates new work for the others. Folding a comparison to 0 lets peephole turn a
 *    branch into a goto, which lets DCE delete a block, which frees more variables...
 *    So we run the whole pipeline over and over until NOBODY reports a change.
 *    LLVM and GCC do the same thing (LLVM even repeats InstCombine several times in
 *    the -O2 pipeline for exactly this reason).
 *
 * The `trace` flag prints the IR after every single pass, which is what makes this
 * good on a projector: the audience watches the program shrink line by line.
 */
public final class MiniOptimizer {

    private final List<Pass> pipeline = Arrays.asList(
            new InlineExpansionPass(),
            new ConstantPropagationPass(),
            new ConstantFoldingPass(),
            new PeepholePass(),
            new DeadCodeEliminationPass()
    );

    private static final int MAX_ROUNDS = 10;

    public int optimize(Program program, boolean trace) {
        int round = 0;

        while (round < MAX_ROUNDS) {
            round++;
            boolean changedThisRound = false;

            if (trace) System.out.println(Console.dim("  ---- round " + round + " ----"));

            for (Pass pass : pipeline) {
                int before = program.realInstructionCount();
                boolean changed = pass.run(program);
                changedThisRound |= changed;

                if (trace) {
                    int after = program.realInstructionCount();
                    String delta = changed ? Console.green(" (changed, " + before + " -> " + after + " instr)")
                                           : Console.dim(" (no change)");
                    System.out.println("  " + Console.cyan(pad(pass.name())) + delta);
                    if (changed) System.out.println(indent(program.render()));
                }
            }

            // FIXED POINT REACHED: a full lap with nothing to do means we are done.
            if (!changedThisRound) {
                if (trace) System.out.println(Console.dim("  fixed point reached after " + round + " round(s)\n"));
                return round;
            }
        }
        return round;
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 24) sb.append(' ');
        return sb.toString();
    }

    private static String indent(String block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n")) sb.append("        ").append(Console.dim(line)).append('\n');
        return sb.toString();
    }
}
