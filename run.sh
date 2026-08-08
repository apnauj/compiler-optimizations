#!/usr/bin/env bash
# Compiler Optimizations -- build & run everything.
#   ./run.sh            compile, then run the mini optimizer
#   ./run.sh all        compile, then run every demo in order
#   ./run.sh folding    run one demo (folding|dce|propagation|peephole|inline|optimizer)
set -euo pipefail
cd "$(dirname "$0")"

JAVAC=${JAVAC:-javac}
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"

echo ">> compiling..."
rm -rf out 2>/dev/null || true      # ignore a locked/undeletable out/ dir
mkdir -p out
$JAVAC -d out $(find src -name '*.java')
echo ">> ok"
echo

run() { echo; echo "############ $1"; java -cp out "$2"; }

case "${1:-optimizer}" in
  optimizer)   run "MINI OPTIMIZER"          optimizer.Main ;;
  folding)     run "CONSTANT FOLDING"        demos.ConstantFoldingDemo ;;
  dce)         run "DEAD CODE ELIMINATION"   demos.DeadCodeEliminationDemo ;;
  propagation) run "CONSTANT PROPAGATION"    demos.ConstantPropagationDemo ;;
  peephole)    run "PEEPHOLE OPTIMIZATION"   demos.PeepholeOptimizationDemo ;;
  inline)      run "INLINE EXPANSION"        demos.InlineExpansionDemo ;;
  all)
    run "1. CONSTANT FOLDING"      demos.ConstantFoldingDemo
    run "2. DEAD CODE ELIMINATION" demos.DeadCodeEliminationDemo
    run "3. CONSTANT PROPAGATION"  demos.ConstantPropagationDemo
    run "4. PEEPHOLE OPTIMIZATION" demos.PeepholeOptimizationDemo
    run "5. INLINE EXPANSION"      demos.InlineExpansionDemo
    run "6. MINI OPTIMIZER"        optimizer.Main ;;
  *) echo "unknown target: $1"; exit 1 ;;
esac
