package optimizer;

/**
 * Pass.java -- the contract every optimization implements.
 *
 * A real compiler is not one clever algorithm; it is a PIPELINE of many small,
 * boring, individually-provable rewrites. LLVM ships well over a hundred of them.
 * Each pass answers the same two questions:
 *
 *   name()  -- what am I called (so we can print a trace)
 *   run(p)  -- rewrite the program in place; return true if I changed anything
 *
 * That boolean return is what lets the driver iterate to a FIXED POINT: keep
 * running the pipeline until nobody changes anything. This matters because passes
 * feed each other -- folding creates dead code, dead code removal exposes new
 * constants, and so on. One trip through the pipeline is never enough.
 */
public interface Pass {
    String name();
    boolean run(Program program);
}
