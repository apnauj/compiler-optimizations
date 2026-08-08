package optimizer;

import java.util.*;

/**
 * Program.java -- a whole compilation unit: one "main" instruction list plus a
 * table of small functions that the inliner is allowed to expand.
 *
 * It also owns two things every optimizer needs:
 *   1. a fresh-name generator (so inlining can rename the callee's locals and
 *      avoid capturing variables that already exist in the caller), and
 *   2. a pretty-printer, so we can show the audience the IR after every pass.
 */
public final class Program {

    public List<Ir.Instr> main = new ArrayList<>();
    public final Map<String, Function> functions = new LinkedHashMap<>();

    private int freshCounter = 0;

    /** A callee: a parameter list plus a body that ends in `return`. */
    public static final class Function {
        public final String name;
        public final List<String> params;
        public final List<Ir.Instr> body;

        public Function(String name, List<String> params, List<Ir.Instr> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }

        /**
         * "Size" of the callee, used by our inlining heuristic.
         * HotSpot does exactly this, but counts JVM bytecodes instead of IR
         * instructions: -XX:MaxInlineSize=35 means "inline any method whose
         * bytecode is 35 bytes or smaller, no questions asked".
         */
        public int size() { return body.size(); }
    }

    public void addFunction(String name, List<String> params, List<Ir.Instr> body) {
        functions.put(name, new Function(name, params, body));
    }

    /** Generates names like "$sq_0_n" that cannot collide with user variables. */
    public String freshPrefix(String hint) {
        return "$" + hint + freshCounter++ + "_";
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Ir.Instr i : main) {
            // Labels sit flush left, everything else is indented -- same convention
            // you see in LLVM IR and in `javap -c` output.
            sb.append(i instanceof Ir.Label ? "" : "    ").append(i).append('\n');
        }
        return sb.toString();
    }

    /** Instruction count, ignoring labels (labels cost nothing at run time). */
    public int realInstructionCount() {
        int n = 0;
        for (Ir.Instr i : main) if (!(i instanceof Ir.Label)) n++;
        return n;
    }

    public Program copy() {
        Program p = new Program();
        for (Ir.Instr i : main) p.main.add(i.copy());
        p.functions.putAll(functions);
        p.freshCounter = freshCounter;
        return p;
    }
}
