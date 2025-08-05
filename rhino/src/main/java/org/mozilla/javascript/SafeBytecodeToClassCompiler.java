package org.mozilla.javascript;

/**
 * A safer version of BytecodeToClassCompiler that only compiles simple functions to avoid
 * VerifyErrors. Falls back to interpretation for complex constructs.
 */
public class SafeBytecodeToClassCompiler implements Context.FunctionCompiler {

    private final BytecodeToClassCompiler actualCompiler = new BytecodeToClassCompiler();

    @Override
    public Callable compile(
            InterpretedFunction ifun,
            Context cx,
            Scriptable scope,
            Scriptable thisObj,
            Object[] args) {
        // Check if the function is safe to compile
        if (isSafeToCompile(ifun)) {
            try {
                return actualCompiler.compile(ifun, cx, scope, thisObj, args);
            } catch (Exception e) {
                // If compilation fails, fall back to interpretation
                System.err.println(
                        "Compilation failed for function "
                                + ifun.getFunctionName()
                                + ", falling back to interpretation: "
                                + e.getMessage());
                return null;
            }
        }

        // Fall back to interpretation for complex functions
        return null;
    }

    /**
     * Check if a function is safe to compile based on its bytecode. Returns true only for simple
     * functions that are unlikely to cause VerifyErrors.
     */
    private boolean isSafeToCompile(InterpretedFunction ifun) {
        InterpreterData idata = ifun.idata;
        if (idata == null) {
            return false;
        }

        // Check for potentially problematic opcodes
        byte[] bytecode = idata.itsICode;
        for (int i = 0; i < bytecode.length; i++) {
            int opcode = bytecode[i] & 0xFF; // Convert to unsigned

            // Skip function calls, object operations, and complex control flow
            switch (opcode) {
                case Token.CALL:
                case Token.NEW:
                case Token.GETPROP:
                case Token.SETPROP:
                case Token.GETELEM:
                case Token.SETELEM:
                case Icode.Icode_NAME_AND_THIS:
                case Icode.Icode_LITERAL_NEW_OBJECT:
                case Icode.Icode_LITERAL_NEW_ARRAY:
                case Token.TRY:
                case Token.THROW:
                    return false; // Too complex, avoid compilation

                // Skip complex increment/decrement that might cause stack issues
                case Icode.Icode_VAR_INC_DEC:
                case Icode.Icode_PROP_INC_DEC:
                    return false;

                default:
                    // Skip byte based on opcode requirements
                    switch (opcode) {
                        case Token.GETVAR:
                        case Token.SETVAR:
                        case Icode.Icode_GETVAR1:
                        case Icode.Icode_SETVAR1:
                        case Token.STRING:
                            i++; // Skip index
                            break;
                        case Token.CALL:
                        case Token.NEW:
                        case Icode.Icode_SHORTNUMBER:
                        case Token.IFEQ:
                        case Token.IFNE:
                        case Token.GOTO:
                            i += 2; // Skip 2-byte operands
                            break;
                        case Icode.Icode_INTNUMBER:
                            i += 4; // Skip 4-byte operands
                            break;
                    }
                    break;
            }
        }

        // Only compile very simple functions (under 100 bytes of bytecode)
        return bytecode.length < 100;
    }
}
