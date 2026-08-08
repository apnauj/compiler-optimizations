package optimizer;

import java.util.*;

/**
 * Ir.java -- the toy Intermediate Representation (IR) our mini compiler works on.
 *
 * WHY AN IR?
 * A real compiler never optimizes the text you typed. It first lowers your source
 * code into a simpler, uniform representation where every instruction does exactly
 * one thing. Ours is a classic "three-address code" (TAC): each instruction has at
 * most one operator and at most three operands, e.g.  t1 = a + b
 *
 * That uniformity is the whole trick: once everything looks the same, an optimization
 * becomes a small, local rule ("if both operands are constants, compute now") instead
 * of a giant special case for every possible expression shape in the language.
 */
public final class Ir {

    private Ir() { }

    // ---------------------------------------------------------------------
    // OPERANDS
    // ---------------------------------------------------------------------

    /**
     * An operand is either an integer literal (a constant known at compile time)
     * or a variable reference (a value only known at run time -- for now).
     *
     * The entire game of "constant propagation + constant folding" is turning
     * Operand.var("x") into Operand.num(30) as often as we legally can.
     */
    public static final class Operand {
        public final boolean isConst;
        public final int value;      // meaningful only when isConst == true
        public final String name;    // meaningful only when isConst == false

        private Operand(boolean isConst, int value, String name) {
            this.isConst = isConst;
            this.value = value;
            this.name = name;
        }

        public static Operand num(int v)      { return new Operand(true, v, null); }
        public static Operand var(String n)   { return new Operand(false, 0, n); }

        public boolean isVar() { return !isConst; }

        @Override public String toString() { return isConst ? Integer.toString(value) : name; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Operand)) return false;
            Operand other = (Operand) o;
            if (isConst != other.isConst) return false;
            return isConst ? value == other.value : name.equals(other.name);
        }

        @Override public int hashCode() { return isConst ? value : name.hashCode(); }
    }

    // ---------------------------------------------------------------------
    // INSTRUCTIONS
    // ---------------------------------------------------------------------

    /**
     * Base class for every instruction.
     *
     * Two questions drive almost every optimization pass, so every instruction
     * must be able to answer them:
     *
     *   def()  -> "which variable do I write?"   (needed by dead code elimination)
     *   uses() -> "which operands do I read?"    (needed by liveness + propagation)
     *
     * hasSideEffect() answers a third, equally important one: "is it legal to
     * delete me even if nobody reads my result?" Printing to the screen is
     * observable behaviour, so a PRINT can never be removed. An arithmetic
     * assignment nobody reads can.
     */
    public abstract static class Instr {
        /** Variable written by this instruction, or null if it writes nothing. */
        public String def() { return null; }

        /** Operands read by this instruction. Never null. */
        public List<Operand> uses() { return Collections.emptyList(); }

        /** True if removing this instruction could change what the program does. */
        public boolean hasSideEffect() { return false; }

        public abstract Instr copy();
    }

    /** x = src        (a "move": either a literal or a copy of another variable) */
    public static final class Assign extends Instr {
        public String dest;
        public Operand src;

        public Assign(String dest, Operand src) { this.dest = dest; this.src = src; }

        @Override public String def() { return dest; }
        @Override public List<Operand> uses() { return Collections.singletonList(src); }
        @Override public Instr copy() { return new Assign(dest, src); }
        @Override public String toString() { return dest + " = " + src; }
    }

    /** x = left op right        (the only place real arithmetic happens) */
    public static final class BinOp extends Instr {
        public String dest;
        public Operand left;
        public String op;      // + - * / % < > == 
        public Operand right;

        public BinOp(String dest, Operand left, String op, Operand right) {
            this.dest = dest; this.left = left; this.op = op; this.right = right;
        }

        @Override public String def() { return dest; }
        @Override public List<Operand> uses() { return Arrays.asList(left, right); }
        @Override public Instr copy() { return new BinOp(dest, left, op, right); }
        @Override public String toString() { return dest + " = " + left + " " + op + " " + right; }
    }

    /** x = shift left by n bits -- produced by our peephole strength-reduction rule. */
    public static final class Shift extends Instr {
        public String dest;
        public Operand value;
        public int bits;
        public boolean leftShift;

        public Shift(String dest, Operand value, int bits, boolean leftShift) {
            this.dest = dest; this.value = value; this.bits = bits; this.leftShift = leftShift;
        }

        @Override public String def() { return dest; }
        @Override public List<Operand> uses() { return Collections.singletonList(value); }
        @Override public Instr copy() { return new Shift(dest, value, bits, leftShift); }
        @Override public String toString() {
            return dest + " = " + value + (leftShift ? " << " : " >> ") + bits;
        }
    }

    /** x = f(args)    -- the call sites that inline expansion will erase. */
    public static final class Call extends Instr {
        public String dest;
        public String function;
        public List<Operand> args;

        public Call(String dest, String function, List<Operand> args) {
            this.dest = dest; this.function = function; this.args = new ArrayList<>(args);
        }

        @Override public String def() { return dest; }
        @Override public List<Operand> uses() { return args; }
        /*
         * NOTE: we mark calls as side-effecting. That is the CONSERVATIVE choice:
         * we do not know what f() does internally, so we must not delete the call
         * just because its result is unused. Real compilers relax this only when
         * they can prove the callee is pure (LLVM's `readnone`/`nounwind` attributes).
         */
        @Override public boolean hasSideEffect() { return true; }
        @Override public Instr copy() { return new Call(dest, function, args); }
        @Override public String toString() { return dest + " = " + function + "(" + join(args) + ")"; }
    }

    /** print(value) -- observable output. The optimizer must never delete this. */
    public static final class Print extends Instr {
        public Operand value;

        public Print(Operand value) { this.value = value; }

        @Override public List<Operand> uses() { return Collections.singletonList(value); }
        @Override public boolean hasSideEffect() { return true; }
        @Override public Instr copy() { return new Print(value); }
        @Override public String toString() { return "print(" + value + ")"; }
    }

    /** L: -- a jump target. */
    public static final class Label extends Instr {
        public String name;
        public Label(String name) { this.name = name; }
        @Override public Instr copy() { return new Label(name); }
        @Override public String toString() { return name + ":"; }
    }

    /** goto L -- unconditional jump. */
    public static final class Goto extends Instr {
        public String target;
        public Goto(String target) { this.target = target; }
        @Override public boolean hasSideEffect() { return true; }
        @Override public Instr copy() { return new Goto(target); }
        @Override public String toString() { return "goto " + target; }
    }

    /**
     * ifFalse cond goto L -- jump when cond == 0.
     *
     * This is the instruction that makes the demo spectacular: once constant
     * propagation proves `cond` is a literal, the branch stops being a branch.
     * A whole region of code becomes provably unreachable and disappears.
     */
    public static final class IfFalseGoto extends Instr {
        public Operand cond;
        public String target;

        public IfFalseGoto(Operand cond, String target) { this.cond = cond; this.target = target; }

        @Override public List<Operand> uses() { return Collections.singletonList(cond); }
        @Override public boolean hasSideEffect() { return true; }
        @Override public Instr copy() { return new IfFalseGoto(cond, target); }
        @Override public String toString() { return "ifFalse " + cond + " goto " + target; }
    }

    /** return value -- only appears inside function bodies, consumed by the inliner. */
    public static final class Return extends Instr {
        public Operand value;
        public Return(Operand value) { this.value = value; }
        @Override public List<Operand> uses() { return Collections.singletonList(value); }
        @Override public boolean hasSideEffect() { return true; }
        @Override public Instr copy() { return new Return(value); }
        @Override public String toString() { return "return " + value; }
    }

    private static String join(List<Operand> ops) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ops.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ops.get(i));
        }
        return sb.toString();
    }
}
