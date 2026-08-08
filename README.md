# Compiler Optimizations — Live Demo

Five classic compiler optimizations, each with commented **before / after** Java
code, plus a **working mini-optimizer** that actually performs all five on a toy
intermediate representation and *proves* the optimized program still behaves
identically.

| # | Optimization | Demo file |
|---|---|---|
| 1 | Constant Folding | `src/demos/ConstantFoldingDemo.java` |
| 2 | Dead Code Elimination | `src/demos/DeadCodeEliminationDemo.java` |
| 3 | Constant Propagation | `src/demos/ConstantPropagationDemo.java` |
| 4 | Peephole Optimization | `src/demos/PeepholeOptimizationDemo.java` |
| 5 | Inline Expansion | `src/demos/InlineExpansionDemo.java` |

## Quick start

```bash
./run.sh              # compile + run the mini optimizer  (the headline demo)
./run.sh all          # compile + run all five demos, then the optimizer
./run.sh peephole     # run a single demo
```

Windows: `run.bat`, `run.bat all`, `run.bat demos.PeepholeOptimizationDemo`

Requires **JDK 11 or newer**. No external dependencies, no build tool.

## The headline demo

`optimizer.Main` builds a 12-instruction program in three-address code, then runs
this pipeline to a **fixed point**:

```
Inline Expansion  →  Constant Propagation  →  Constant Folding
                  →  Peephole  →  Dead Code Elimination        (repeat)
```

It prints the IR after every single pass, so the program visibly shrinks on
screen, and finishes with a correctness check:

```
  original  printed: [900, 60]
  optimized printed: [900, 60]
  PASS - both versions produce identical observable output.

  IR instructions in the program      12  ->    2   -83%
  Instructions actually executed      13  ->    2   -85%
```

**12 instructions become 2.** No pass on its own gets there — it takes six full
rounds of the pipeline, because every pass creates work for the others.

## Project layout

```
src/optimizer/     the working mini-compiler
  Ir.java                    three-address IR: operands + instructions
  Program.java               instruction list + callee table + pretty printer
  Pass.java                  the interface every optimization implements
  InlineExpansionPass.java   ─┐
  ConstantPropagationPass.java│
  ConstantFoldingPass.java    ├─ the five optimizations
  PeepholePass.java           │
  DeadCodeEliminationPass.java┘
  Interpreter.java           runs the IR — this is what proves we did not break it
  MiniOptimizer.java         the pipeline driver, iterating to a fixed point
  Main.java                  the live demo
  Console.java               ANSI colours (set NO_COLOR=1 to disable)

src/demos/         five standalone before/after files, heavily commented
```

## Things worth trying live

```bash
# javac already folded 1024*1024*4 into a single constant — see for yourself:
javap -c -p -cp out demos.ConstantFoldingDemo

# the if(DEBUG) block is not even in the bytecode:
javap -c -p -cp out demos.DeadCodeEliminationDemo

# watch HotSpot's inliner make its decisions in real time:
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -cp out demos.InlineExpansionDemo

# turn the JIT off and watch the same code crawl:
java -Xint -cp out demos.InlineExpansionDemo
```

## A note on honesty

Two things in this project are deliberately *not* oversold:

- The inlining benchmark shows **almost no speedup**. That is the point: HotSpot
  already inlined the "unoptimized" version. The abstraction was free.
- `DeadCodeEliminationPass` uses a single backward scan, which is correct for
  straight-line code but not for loops with back-edges. The limitation is
  documented in the source rather than hidden.
