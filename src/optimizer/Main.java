package optimizer;

import java.util.*;

/**
 * Main.java -- the live demo.
 *
 * WHAT THE AUDIENCE SEES
 *   1. A small program in our IR, written the way a naive front end would emit it.
 *   2. The optimizer running, printing the program after EVERY pass, so the code
 *      visibly shrinks on screen.
 *   3. Both versions executed by the same interpreter, and a check that they
 *      printed IDENTICAL results -- proof we optimized rather than broke it.
 *   4. A scoreboard: instructions before/after and steps executed before/after.
 *
 * Run it with:   java -cp out optimizer.Main
 */
public final class Main {

    public static void main(String[] args) {
        Program program = buildDemoProgram();
        Program original = program.copy();

        Console.rule("1. THE PROGRAM AS THE FRONT END EMITTED IT");
        System.out.println(sourceView());
        System.out.println(Console.bold("  Lowered to three-address IR:"));
        System.out.println(program.render());

        Console.rule("2. THE OPTIMIZER RUNNING, PASS BY PASS");
        MiniOptimizer optimizer = new MiniOptimizer();
        int rounds = optimizer.optimize(program, true);

        Console.rule("3. THE OPTIMIZED PROGRAM");
        System.out.println(program.render());

        Console.rule("4. CORRECTNESS CHECK -- did we optimize, or did we break it?");
        Interpreter interpreter = new Interpreter();
        Interpreter.Result before = interpreter.run(original);
        Interpreter.Result after  = interpreter.run(program);

        System.out.println("  original  printed: " + before.output);
        System.out.println("  optimized printed: " + after.output);

        boolean equivalent = before.output.equals(after.output);
        System.out.println();
        System.out.println(equivalent
                ? Console.green("  PASS - both versions produce identical observable output.")
                : Console.red("  FAIL - the optimizer changed the program's behaviour!"));

        Console.rule("5. SCOREBOARD");
        int instrBefore = original.realInstructionCount();
        int instrAfter  = program.realInstructionCount();
        row("IR instructions in the program", instrBefore, instrAfter);
        row("Instructions actually executed", before.steps, after.steps);
        System.out.println("  pipeline reached a fixed point after " + Console.yellow(rounds + " round(s)"));
        System.out.println();

        if (!equivalent) System.exit(1);   // make the demo fail loudly if it ever regresses
    }

    private static void row(String label, int before, int after) {
        double pct = before == 0 ? 0 : (100.0 * (before - after) / before);
        System.out.printf("  %-34s %3d  ->  %3d   %s%n",
                label, before, after, Console.green(String.format("-%.0f%%", pct)));
    }

    /** The pseudo-source the IR below corresponds to -- shown so the demo reads clearly. */
    private static String sourceView() {
        return String.join("\n",
            "  int square(int n) { return n * n; }",
            "",
            "  int scale  = 3;",
            "  int base   = 10;",
            "  int width  = base * scale;   // constant propagation + folding",
            "  int area   = width * 2;      // folding, then strength reduction",
            "  int unused = area + 99;      // dead store: nobody reads `unused`",
            "  int flag   = scale > 5;      // folds to 0 -> the branch is decided",
            "  if (flag) { print(999); }    // becomes unreachable",
            "  int t = square(width);       // inline expansion",
            "  int r = t + 0;               // peephole identity",
            "  print(r);",
            "  print(area);",
            "");
    }

    /**
     * Builds the demo program directly in IR form -- exactly what a front end would
     * hand the optimizer after parsing the source shown above.
     */
    private static Program buildDemoProgram() {
        Program p = new Program();

        // int square(int n) { return n * n; }
        p.addFunction("square", Arrays.asList("n"), Arrays.asList(
                new Ir.BinOp("r", Ir.Operand.var("n"), "*", Ir.Operand.var("n")),
                new Ir.Return(Ir.Operand.var("r"))
        ));

        List<Ir.Instr> m = p.main;
        m.add(new Ir.Assign("scale", Ir.Operand.num(3)));
        m.add(new Ir.Assign("base",  Ir.Operand.num(10)));
        m.add(new Ir.BinOp("width", Ir.Operand.var("base"),  "*", Ir.Operand.var("scale")));
        m.add(new Ir.BinOp("area",  Ir.Operand.var("width"), "*", Ir.Operand.num(2)));
        m.add(new Ir.BinOp("unused", Ir.Operand.var("area"), "+", Ir.Operand.num(99)));
        m.add(new Ir.BinOp("flag",  Ir.Operand.var("scale"), ">", Ir.Operand.num(5)));
        m.add(new Ir.IfFalseGoto(Ir.Operand.var("flag"), "SKIP"));
        m.add(new Ir.Print(Ir.Operand.num(999)));
        m.add(new Ir.Label("SKIP"));
        m.add(new Ir.Call("t", "square", Arrays.asList(Ir.Operand.var("width"))));
        m.add(new Ir.BinOp("r2", Ir.Operand.var("t"), "+", Ir.Operand.num(0)));
        m.add(new Ir.Print(Ir.Operand.var("r2")));
        m.add(new Ir.Print(Ir.Operand.var("area")));
        return p;
    }
}
