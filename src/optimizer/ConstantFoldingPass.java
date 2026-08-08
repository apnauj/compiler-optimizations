package optimizer;

/**
 * OPTIMIZATION 1 -- CONSTANT FOLDING
 *
 * IDEA
 * If every operand of an operation is already a literal, the compiler can just do
 * the arithmetic ITSELF, at compile time, and replace the whole operation with its
 * result. The CPU should never be asked to recompute an answer that could not
 * possibly have changed.
 *
 *     w = 10 * 3        ==>     w = 30
 *     flag = 3 > 5      ==>     flag = 0
 *
 * WHY IT MATTERS
 * The saving is not one multiply. It is one multiply MULTIPLIED BY how often that
 * code runs. Fold a constant expression inside a loop that runs a billion times and
 * you removed a billion operations. It also shrinks the code, which helps the
 * instruction cache and helps later passes see more constants.
 *
 * THE HARD PART: FOLDING MUST NOT LIE
 * A compile-time evaluation is only legal if it produces EXACTLY the value the
 * program would have produced at run time, on the target machine. That is why this
 * pass is full of guard clauses:
 *   - 1/0 must NOT be folded. If we folded it we would silently delete a divide-by-
 *     zero exception the program was supposed to throw.
 *   - Integer overflow must wrap the way Java specifies (two's complement, 32-bit),
 *     which is why we compute in `int` and not in `long`.
 *   - Floating point is the classic trap: the compiler must round exactly the way
 *     the target CPU would. Getting this wrong is how cross-compilers historically
 *     produced binaries that disagreed with the machine that built them.
 *
 * This is the rule for EVERY optimization: an optimization that changes observable
 * behaviour is not an optimization, it is a bug.
 *
 * COMPLEXITY: O(n), a single linear scan.
 */
public final class ConstantFoldingPass implements Pass {

    @Override public String name() { return "Constant Folding"; }

    @Override public boolean run(Program program) {
        boolean changed = false;

        for (int i = 0; i < program.main.size(); i++) {
            Ir.Instr instr = program.main.get(i);

            if (instr instanceof Ir.BinOp) {
                Ir.BinOp b = (Ir.BinOp) instr;

                // Fire only when BOTH operands are literals. If either is still a
                // variable, we simply do nothing -- constant propagation may make
                // it a literal on the next iteration, and then we will fold it.
                if (b.left.isConst && b.right.isConst) {
                    Integer folded = evaluate(b.left.value, b.op, b.right.value);
                    if (folded != null) {                       // null == "refused to fold"
                        program.main.set(i, new Ir.Assign(b.dest, Ir.Operand.num(folded)));
                        changed = true;
                    }
                }

            } else if (instr instanceof Ir.Shift) {
                Ir.Shift s = (Ir.Shift) instr;
                if (s.value.isConst) {
                    int v = s.leftShift ? (s.value.value << s.bits) : (s.value.value >> s.bits);
                    program.main.set(i, new Ir.Assign(s.dest, Ir.Operand.num(v)));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Evaluate a binary operation at compile time.
     * Returns null to mean "I refuse to fold this" -- the safe answer whenever
     * folding could change observable behaviour.
     */
    private static Integer evaluate(int a, String op, int b) {
        switch (op) {
            case "+":  return a + b;      // wraps in 32-bit two's complement, exactly like the JVM
            case "-":  return a - b;
            case "*":  return a * b;
            case "/":
                // GUARD: do not fold division by zero. At run time this must throw
                // ArithmeticException; folding it away would erase that behaviour.
                if (b == 0) return null;
                return a / b;
            case "%":
                if (b == 0) return null;
                return a % b;
            // Comparisons produce 1 / 0 so they can feed our ifFalse instruction.
            case "<":  return a <  b ? 1 : 0;
            case ">":  return a >  b ? 1 : 0;
            case "<=": return a <= b ? 1 : 0;
            case ">=": return a >= b ? 1 : 0;
            case "==": return a == b ? 1 : 0;
            case "!=": return a != b ? 1 : 0;
            default:   return null;       // unknown operator -> stay safe, do nothing
        }
    }
}
