package org.mozilla.javascript;

import static org.mozilla.javascript.Icode.Icode_CALLSPECIAL;
import static org.mozilla.javascript.Icode.Icode_CALLSPECIAL_OPTIONAL;
import static org.mozilla.javascript.Icode.Icode_CALL_ON_SUPER;
import static org.mozilla.javascript.Icode.Icode_DEBUGGER;
import static org.mozilla.javascript.Icode.Icode_DELNAME;
import static org.mozilla.javascript.Icode.Icode_DUP;
import static org.mozilla.javascript.Icode.Icode_DUP2;
import static org.mozilla.javascript.Icode.Icode_ELEM_AND_THIS;
import static org.mozilla.javascript.Icode.Icode_ELEM_AND_THIS_OPTIONAL;
import static org.mozilla.javascript.Icode.Icode_ELEM_INC_DEC;
import static org.mozilla.javascript.Icode.Icode_GETVAR1;
import static org.mozilla.javascript.Icode.Icode_GOSUB;
import static org.mozilla.javascript.Icode.Icode_IFEQ_POP;
import static org.mozilla.javascript.Icode.Icode_IF_NOT_NULL_UNDEF;
import static org.mozilla.javascript.Icode.Icode_IF_NULL_UNDEF;
import static org.mozilla.javascript.Icode.Icode_INTNUMBER;
import static org.mozilla.javascript.Icode.Icode_LINE;
import static org.mozilla.javascript.Icode.Icode_LITERAL_GETTER;
import static org.mozilla.javascript.Icode.Icode_LITERAL_KEY_SET;
import static org.mozilla.javascript.Icode.Icode_LITERAL_NEW_ARRAY;
import static org.mozilla.javascript.Icode.Icode_LITERAL_NEW_OBJECT;
import static org.mozilla.javascript.Icode.Icode_LITERAL_SET;
import static org.mozilla.javascript.Icode.Icode_LITERAL_SETTER;
import static org.mozilla.javascript.Icode.Icode_LOCAL_CLEAR;
import static org.mozilla.javascript.Icode.Icode_NAME_AND_THIS;
import static org.mozilla.javascript.Icode.Icode_NAME_INC_DEC;
import static org.mozilla.javascript.Icode.Icode_ONE;
import static org.mozilla.javascript.Icode.Icode_POP;
import static org.mozilla.javascript.Icode.Icode_POP_RESULT;
import static org.mozilla.javascript.Icode.Icode_PROP_AND_THIS;
import static org.mozilla.javascript.Icode.Icode_PROP_INC_DEC;
import static org.mozilla.javascript.Icode.Icode_REF_INC_DEC;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT1;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT2;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT4;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT_C0;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT_C1;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT_C2;
import static org.mozilla.javascript.Icode.Icode_REG_BIGINT_C3;
import static org.mozilla.javascript.Icode.Icode_REG_IND1;
import static org.mozilla.javascript.Icode.Icode_REG_IND2;
import static org.mozilla.javascript.Icode.Icode_REG_IND4;
import static org.mozilla.javascript.Icode.Icode_REG_IND_C0;
import static org.mozilla.javascript.Icode.Icode_REG_IND_C1;
import static org.mozilla.javascript.Icode.Icode_REG_IND_C2;
import static org.mozilla.javascript.Icode.Icode_REG_IND_C3;
import static org.mozilla.javascript.Icode.Icode_REG_IND_C4;
import static org.mozilla.javascript.Icode.Icode_REG_IND_C5;
import static org.mozilla.javascript.Icode.Icode_REG_STR1;
import static org.mozilla.javascript.Icode.Icode_REG_STR2;
import static org.mozilla.javascript.Icode.Icode_REG_STR4;
import static org.mozilla.javascript.Icode.Icode_REG_STR_C0;
import static org.mozilla.javascript.Icode.Icode_REG_STR_C1;
import static org.mozilla.javascript.Icode.Icode_REG_STR_C2;
import static org.mozilla.javascript.Icode.Icode_REG_STR_C3;
import static org.mozilla.javascript.Icode.Icode_RETSUB;
import static org.mozilla.javascript.Icode.Icode_RETUNDEF;
import static org.mozilla.javascript.Icode.Icode_SCOPE_LOAD;
import static org.mozilla.javascript.Icode.Icode_SCOPE_SAVE;
import static org.mozilla.javascript.Icode.Icode_SETCONST;
import static org.mozilla.javascript.Icode.Icode_SETCONSTVAR1;
import static org.mozilla.javascript.Icode.Icode_SETVAR1;
import static org.mozilla.javascript.Icode.Icode_SHORTNUMBER;
import static org.mozilla.javascript.Icode.Icode_SPARE_ARRAYLIT;
import static org.mozilla.javascript.Icode.Icode_STARTSUB;
import static org.mozilla.javascript.Icode.Icode_SWAP;
import static org.mozilla.javascript.Icode.Icode_TAIL_CALL;
import static org.mozilla.javascript.Icode.Icode_TEMPLATE_LITERAL_CALLSITE;
import static org.mozilla.javascript.Icode.Icode_TYPEOFNAME;
import static org.mozilla.javascript.Icode.Icode_UNDEF;
import static org.mozilla.javascript.Icode.Icode_VALUE_AND_THIS;
import static org.mozilla.javascript.Icode.Icode_VALUE_AND_THIS_OPTIONAL;
import static org.mozilla.javascript.Icode.Icode_VAR_INC_DEC;
import static org.mozilla.javascript.Icode.Icode_ZERO;

import java.util.concurrent.atomic.AtomicInteger;
import org.mozilla.classfile.ByteCode;
import org.mozilla.classfile.ClassFileWriter;
import org.mozilla.javascript.optimizer.Codegen;

// spotless:off

/** Attempt to convert icode to Java classes. */
public class BytecodeToClassCompiler implements Context.FunctionCompiler {

    // State machine for multi-bytecode constructs
    private enum CompilerState {
        NORMAL,
        BUILDING_OBJECT_LITERAL,
        BUILDING_ARRAY_LITERAL,
        IN_GENERATOR_FUNCTION,
        BUILDING_TEMPLATE_LITERAL
    }

    // State data classes for different states
    private static class ObjectLiteralState {
        final int objectLocalVar; // Local variable holding the object being built
        final Object[] propertyIds; // Property keys from literalIds
        int currentPropertyIndex = 0;

        ObjectLiteralState(int objectLocalVar, Object[] propertyIds) {
            this.objectLocalVar = objectLocalVar;
            this.propertyIds = propertyIds;
        }
    }

    private static class ArrayLiteralState {
        final int arrayLocalVar; // Local variable holding the array being built
        final int length; // Array length
        int currentIndex = 0;

        ArrayLiteralState(int arrayLocalVar, int length) {
            this.arrayLocalVar = arrayLocalVar;
            this.length = length;
        }
    }

    private CompilerState currentState = CompilerState.NORMAL;
    private Object stateData; // Context-specific data for current state

    // State management methods
    private void enterState(CompilerState newState, Object data) {
        this.currentState = newState;
        this.stateData = data;
    }

    private void exitState() {
        this.currentState = CompilerState.NORMAL;
        this.stateData = null;
    }

    private boolean isInState(CompilerState state) {
        return this.currentState == state;
    }

    @SuppressWarnings("unchecked")
    private <T> T getStateData() {
        return (T) this.stateData;
    }

    private static final String SUPER_CLASS_NAME = "org.mozilla.javascript.NativeFunction";
    private static final String ID_FIELD_NAME = "_id";
    static AtomicInteger counter = new AtomicInteger();

    // Access flags for methods/fields
    private static final short ACC_PUBLIC = 0x0001;
    private static final short ACC_PRIVATE = 0x0002;
    private static final short ACC_STATIC = 0x0008;
    private static final short ACC_FINAL = 0x0010;

    @Override
    public Callable compile(
            InterpretedFunction ifun,
            Context cx,
            Scriptable scope,
            Scriptable thisObj,
            Object[] args) {
        // BytecodeToClassCompiler is being invoked
        try {
            // Get the interpreter data
            InterpreterData idata = ifun.idata;
            if (idata == null) {
                return null; // No bytecode available
            }

            // Safety checks similar to IFnToClassCompiler
            if (idata.itsNeedsActivation) {
                ifun.compilationAttempted = true;
                return null;
            }

            // Check if this function uses constructions that can't be compiled in chunks
            if (idata.usesConstructionsThatCantBeCompiledInChunk) {
                ifun.compilationAttempted = true;
                return null;
            }

            // Create a unique class name
            String className =
                    "org.mozilla.javascript.GeneratedFunction" + counter.getAndIncrement();

            // Create a new Codegen instance
            Codegen codegen = new Codegen();

            // Create a class file writer directly
            ClassFileWriter cfw =
                    new ClassFileWriter(className, SUPER_CLASS_NAME, idata.itsSourceFile);

            cfw.startMethod(
                    "<init>",
                    "()V",
                    ACC_PUBLIC);
            cfw.addALoad(0); // this
            cfw.addInvoke(ByteCode.INVOKESPECIAL, SUPER_CLASS_NAME, "<init>", "()V");
            cfw.add(ByteCode.RETURN);
            cfw.stopMethod((short) 1);

            // Generate the standard call method that NativeFunction expects
            cfw.startMethod(
                    "call",
                    "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;Lorg/mozilla/javascript/Scriptable;[Ljava/lang/Object;)Ljava/lang/Object;",
                    ACC_PUBLIC);

            // Declare locals
            int contextLocal = 1; // first parameter: Context cx
            int scopeLocal = 2; // second parameter: Scriptable scope
            int thisObjLocal = 3; // third parameter: Scriptable thisObj
            int argsLocal = 4; // fourth parameter: Object[] args
            int stringRegLocal = 5; // string register equivalent
            int indexRegLocal = 6; // index register equivalent
            int bigIntRegLocal = 7; // BigInt register equivalent
            int firstFreeLocal = 8;
            int[] locals = new int[idata.itsMaxLocals]; // local variable map

            // Initialize local variables for faster access
            for (int i = 0; i < locals.length; i++) {
                locals[i] = firstFreeLocal++;
            }

            // Map of jump targets
            int[] targetPC = new int[idata.itsICode.length];
            // First pass to identify jump targets
            int pc = 0;
            while (pc < idata.itsICode.length) {
                int jumpTarget;
                byte opcode = idata.itsICode[pc];
                pc++;

                switch (opcode) {
                    case Token.IFEQ:
                    case Token.IFNE:
                    case Token.GOTO:
                    case Icode_GOSUB:
                        // 2 bytes offset
                        jumpTarget = pc + 2;
                        jumpTarget +=
                                (short)
                                        ((idata.itsICode[pc] << 8)
                                                | (idata.itsICode[pc + 1] & 0xff));
                        targetPC[jumpTarget]++;
                        pc += 2;
                        break;
                    // Handle other opcodes that consume bytes
                    case Token.GETVAR:
                    case Token.SETVAR:
                    case Icode_GETVAR1:
                    case Icode_SETVAR1:
                        pc++; // Skip index
                        break;
                    case Token.CALL:
                    case Token.NEW:
                        pc += 2; // Skip argument count
                        break;
                    case Icode_STARTSUB:
                    case Icode_RETSUB:
                        // Single-byte operations
                        break;
                }
            }

            // Create labels for all jump targets
            int[] labels = new int[idata.itsICode.length];
            for (int i = 0; i < targetPC.length; i++) {
                if (targetPC[i] > 0) {
                    labels[i] = cfw.acquireLabel();
                }
            }

            // Process interpreter bytecode
            pc = 0;
            boolean hasReturn = false; // Track if we've already added a return statement
            while (pc < idata.itsICode.length) {
                // Mark this location for jumps
                if (labels[pc] != 0) {
                    cfw.markLabel(labels[pc]);
                }

                byte opcode = idata.itsICode[pc];

                switch (opcode) {
                    // Stack operations
                    case Icode_DUP:
                        cfw.add(ByteCode.DUP);
                        pc++;
                        break;
                    case Icode_DUP2:
                        cfw.add(ByteCode.DUP2);
                        pc++;
                        break;
                    case Icode_SWAP:
                        cfw.add(ByteCode.SWAP);
                        pc++;
                        break;
                    case Icode_POP:
                        cfw.add(ByteCode.POP);
                        pc++;
                        break;
                    case Icode_POP_RESULT:
                        // Store to 'result' register and pop
                        int resultLocal = locals[0]; // Assuming result is first local
                        cfw.addAStore(resultLocal);
                        pc++;
                        break;

                    // Variable access
                    case Token.GETVAR:
                    case Icode_GETVAR1:
                        pc++; // Move past the opcode
                        int varIndex = idata.itsICode[pc++] & 0xFF;

                        // Check if this is a parameter access vs local variable access
                        if (idata.itsMaxLocals == 0 && varIndex < idata.argCount) {
                            // Access parameter from args array: args[varIndex]
                            cfw.addALoad(argsLocal); // Load Object[] args
                            cfw.addPush(varIndex); // Push parameter index
                            cfw.add(ByteCode.AALOAD); // args[varIndex]
                        } else if (varIndex < locals.length) {
                            // Access local variable
                            cfw.addALoad(locals[varIndex]);
                        } else {
                            throw new RuntimeException("Invalid variable index: " + varIndex);
                        }
                        break;
                    case Token.SETVAR:
                    case Icode_SETVAR1:
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        cfw.addAStore(locals[varIndex]);
                        break;
                    case Icode_LOCAL_CLEAR:
                        // Clear local variable - set it to null
                        // indexReg contains the local variable index to clear
                        cfw.add(ByteCode.ACONST_NULL); // Push null
                        cfw.addALoad(indexRegLocal); // Load index register
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "intValue",
                                "()I"); // Convert Integer to int - stack: null, index_int

                        // We need to store null in locals[index]
                        // Since we have dynamic indexing, we need to handle this carefully
                        // For simplicity, we'll use a helper approach or handle common cases
                        cfw.add(ByteCode.POP); // Remove the index for now - stack: null
                        cfw.addAStore(firstFreeLocal + 10); // Store null in a temporary location
                        pc++;
                        break;
                    case Icode_DEBUGGER:
                        // Debugger statement - call debugger if present
                        // In compiled mode, this is typically a no-op unless debugging is enabled
                        // We could add a call to ScriptRuntime.debugger or similar if needed
                        // For now, we'll implement it as a no-op (which is common in production)
                        pc++;
                        break;

                    // Constants
                    case Icode_ZERO:
                        cfw.addPush(0.0);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        pc++;
                        break;
                    case Icode_ONE:
                        cfw.addPush(1.0);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        pc++;
                        break;
                    case Icode_UNDEF:
                        // Load undefined from ScriptRuntime
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "UNDEFINED",
                                "Ljava/lang/Object;");
                        pc++;
                        break;

                    // Arithmetic operations
                    case Token.ADD:
                        // Stack: obj1, obj2 -> result
                        // Need to add Context parameter: obj1, obj2, context
                        cfw.addALoad(1); // Load Context parameter
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "add",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.SUB:
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "sub",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.MUL:
                        // Stack: obj1, obj2 -> Convert to Numbers then multiply
                        // Convert second operand to Number
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toNumber",
                                "(Ljava/lang/Object;)D");
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        // Convert first operand to Number (need to swap stack)
                        cfw.add(ByteCode.SWAP); // Now stack: Number2, obj1
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toNumber",
                                "(Ljava/lang/Object;)D");
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        cfw.add(ByteCode.SWAP); // Now stack: Number1, Number2
                        // Now multiply
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "multiply",
                                "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;");
                        pc++;
                        break;
                    case Token.DIV:
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "div",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        break;

                    // Control flow
                    case Token.IFEQ:
                        int jumpTarget = pc + 2;
                        jumpTarget +=
                                (short)
                                        ((idata.itsICode[pc] << 8)
                                                | (idata.itsICode[pc + 1] & 0xff));
                        // Convert to boolean and check
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toBoolean",
                                "(Ljava/lang/Object;)Z");
                        if (labels[jumpTarget] != 0) {
                            cfw.add(ByteCode.IFEQ, labels[jumpTarget]);
                        } else {
                            cfw.add(ByteCode.POP); // pop condition if no valid jump target
                        }
                        pc += 2; // Skip jump offset
                        break;
                    case Token.IFNE:
                        jumpTarget = pc + 2;
                        jumpTarget +=
                                (short)
                                        ((idata.itsICode[pc] << 8)
                                                | (idata.itsICode[pc + 1] & 0xff));
                        // Convert to boolean and check
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toBoolean",
                                "(Ljava/lang/Object;)Z");
                        if (labels[jumpTarget] != 0) {
                            cfw.add(ByteCode.IFNE, labels[jumpTarget]);
                        } else {
                            cfw.add(ByteCode.POP); // pop condition if no valid jump target
                        }
                        pc += 2; // Skip jump offset
                        break;
                    case Token.GOTO:
                        jumpTarget = pc + 2;
                        jumpTarget +=
                                (short)
                                        ((idata.itsICode[pc] << 8)
                                                | (idata.itsICode[pc + 1] & 0xff));
                        if (labels[jumpTarget] != 0) {
                            cfw.add(ByteCode.GOTO, labels[jumpTarget]);
                        }
                        pc += 2; // Skip jump offset
                        break;
                    case Icode_GOSUB:
                        {
                            // GOSUB: Push return address and jump to subroutine
                            jumpTarget = pc + 2;
                            jumpTarget +=
                                    (short)
                                            ((idata.itsICode[pc] << 8)
                                                    | (idata.itsICode[pc + 1] & 0xff));

                            // Push return address (pc + 2) onto stack
                            cfw.addPush(pc + 2); // Return address
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "java/lang/Integer",
                                    "valueOf",
                                    "(I)Ljava/lang/Integer;");

                            // Jump to subroutine
                            if (labels[jumpTarget] != 0) {
                                cfw.add(ByteCode.GOTO, labels[jumpTarget]);
                            }
                            pc += 2; // Skip jump offset
                        }
                        break;
                    case Icode_STARTSUB:
                        {
                            // STARTSUB: Start subroutine, store return address in local variable
                            // indexReg contains the local variable index to store return address
                            cfw.addALoad(indexRegLocal); // Load index register
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "java/lang/Integer",
                                    "intValue",
                                    "()I"); // Convert to int
                            int localIndex =
                                    firstFreeLocal + 20; // Use a high local slot for return address

                            // Pop return address from stack and store in local variable
                            // For now, use a simplified approach with fixed local
                            cfw.addAStore(localIndex);
                            pc++;
                        }
                        break;
                    case Icode_RETSUB:
                        {
                            // RETSUB: Return from subroutine using stored return address
                            // This is complex to implement in static compilation since it requires
                            // dynamic jumps

                            // For now, we'll implement a simplified version that handles the most
                            // common case
                            // The interpreter uses this primarily for exception handling and
                            // complex control flow

                            // Load the return address from the local variable
                            int localIndex = firstFreeLocal + 20; // Match the slot used in STARTSUB
                            cfw.addALoad(localIndex);

                            // Check if it's a valid return address (not null/undefined)
                            cfw.add(ByteCode.DUP);
                            cfw.add(ByteCode.ACONST_NULL);
                            int continueLabel = cfw.acquireLabel();
                            cfw.add(ByteCode.IF_ACMPEQ, continueLabel);

                            // If we have a valid return address, for now just pop it and continue
                            // A full implementation would require building a jump table at compile
                            // time
                            cfw.add(ByteCode.POP);

                            cfw.markLabel(continueLabel);
                            pc++;
                        }
                        break;

                    // Return value
                    case Token.RETURN:
                        cfw.add(ByteCode.ARETURN);
                        hasReturn = true;
                        pc++;
                        // Exit the loop since this function should end here
                        break;
                    case Icode_RETUNDEF:
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "UNDEFINED",
                                "Ljava/lang/Object;");
                        cfw.add(ByteCode.ARETURN);
                        break;

                    // String and number literals
                    case Token.STRING:
                        // Stack: -> string_value
                        // Push the string from the string register (previously loaded by
                        // Icode_REG_STR_*)
                        cfw.addALoad(stringRegLocal);
                        pc++;
                        break;
                    case Icode_SHORTNUMBER:
                        short value =
                                (short)
                                        ((idata.itsICode[pc] << 8)
                                                | (idata.itsICode[pc + 1] & 0xff));
                        pc += 2;
                        cfw.addPush((double) value);
                        // Convert to Double object
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        break;
                    case Icode_INTNUMBER:
                        int intValue =
                                (idata.itsICode[pc] << 24)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 16)
                                        | ((idata.itsICode[pc + 2] & 0xFF) << 8)
                                        | (idata.itsICode[pc + 3] & 0xFF);
                        pc += 4;
                        cfw.addPush((double) intValue);
                        // Convert to Double object
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        break;

                    // Function calls
                    case Token.CALL:
                        int argCount = (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;
                        // Create array for arguments
                        cfw.addPush(argCount);
                        cfw.add(ByteCode.ANEWARRAY, "java/lang/Object");

                        // Store arguments in reverse order (stack is reversed)
                        for (int i = argCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1); // array, arg, array
                            cfw.add(ByteCode.SWAP); // array, array, arg
                            cfw.addPush(i); // array, array, arg, index
                            cfw.add(ByteCode.SWAP); // array, array, index, arg
                            cfw.add(ByteCode.AASTORE); // array
                        }

                        // Stack now has: thisObj, function, args[]
                        // Rearrange to: function, thisObj, args[]
                        cfw.add(ByteCode.DUP_X1); // args, thisObj, function, args
                        cfw.add(ByteCode.POP); // args, thisObj, function
                        cfw.add(ByteCode.DUP_X2); // function, args, thisObj, function
                        cfw.add(ByteCode.POP); // function, args, thisObj
                        cfw.add(ByteCode.SWAP); // function, thisObj, args

                        // Load context and scope
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);

                        // Call ScriptRuntime.call
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "callFunctionMethod",
                                "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;
                    case Icode_NAME_AND_THIS:
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String name = idata.itsStringTable[varIndex];
                        // Push the name for lookup
                        cfw.addPush(name);
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        // Call ScriptRuntime.getNameFunctionAndThis
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getNameFunctionAndThis",
                                "(Ljava/lang/String;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        // This returns a Callable, but we need both function and this
                        // For now, duplicate and use as both function and this
                        cfw.add(ByteCode.DUP);
                        break;

                    // Object operations
                    case Token.GETPROP:
                        // Get the property name index
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String propName = idata.itsStringTable[varIndex];
                        // Stack: object -> result
                        cfw.addPush(propName); // object, propName
                        cfw.addALoad(contextLocal); // object, propName, context
                        cfw.addALoad(scopeLocal); // object, propName, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getObjectProp",
                                "(Ljava/lang/Object;Ljava/lang/String;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;
                    case Token.SETPROP:
                        // Get the property name index
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        propName = idata.itsStringTable[varIndex];
                        // Stack: object, value -> value
                        cfw.add(ByteCode.DUP_X1); // value, object, value
                        cfw.add(ByteCode.POP); // value, object
                        cfw.addPush(propName); // value, object, propName
                        cfw.add(ByteCode.DUP_X2); // propName, value, object, propName
                        cfw.add(ByteCode.POP); // propName, value, object
                        cfw.add(ByteCode.DUP_X2); // object, propName, value, object
                        cfw.add(ByteCode.POP); // object, propName, value
                        cfw.addALoad(contextLocal); // object, propName, value, context
                        cfw.addALoad(scopeLocal); // object, propName, value, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "setObjectProp",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;
                    case Icode_PROP_INC_DEC:
                        // Get the property name index and inc/dec type
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int propIncrType = idata.itsICode[pc++];
                        propName = idata.itsStringTable[varIndex];
                        // Stack: object -> result
                        cfw.addPush(propName); // object, propName
                        cfw.addPush(propIncrType); // object, propName, incrType
                        cfw.addALoad(contextLocal); // object, propName, incrType, context
                        cfw.addALoad(scopeLocal); // object, propName, incrType, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "propIncrDecr",
                                "(Ljava/lang/Object;Ljava/lang/String;ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;

                    // Array operations
                    case Token.GETELEM:
                        // Stack: object, index -> result
                        cfw.addALoad(contextLocal); // object, index, context
                        cfw.addALoad(scopeLocal); // object, index, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getObjectElem",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.SETELEM:
                        // Stack: object, index, value -> value
                        cfw.add(ByteCode.DUP_X2); // value, object, index, value
                        cfw.add(ByteCode.POP); // value, object, index
                        cfw.addALoad(contextLocal); // value, object, index, context
                        cfw.addALoad(scopeLocal); // value, object, index, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "setObjectElem",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        pc++;
                        break;

                    // Object/Array creation
                    case Icode_LITERAL_NEW_OBJECT:
                        {
                            // Enter object literal construction state
                            // indexReg contains the index of the property IDs in literalIds
                            int literalIndex =
                                    (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                            pc += 2;
                            boolean copyArray = idata.itsICode[pc++] != 0;

                            Object[] propertyIds = (Object[]) idata.literalIds[literalIndex];

                            // Create the object
                            cfw.addALoad(contextLocal); // context
                            cfw.addALoad(scopeLocal); // context, scope
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "org/mozilla/javascript/ScriptRuntime",
                                    "newObject",
                                    "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");

                            // Store object in a local variable for state management
                            // Use a high local variable slot that won't conflict with existing
                            // locals
                            int objectLocalVar = 100; // Temporary slot for object literal state
                            cfw.addAStore(objectLocalVar);

                            // Enter object literal building state
                            ObjectLiteralState objState =
                                    new ObjectLiteralState(objectLocalVar, propertyIds);
                            enterState(CompilerState.BUILDING_OBJECT_LITERAL, objState);

                            // The final result will be pushed when we finish building (at
                            // Token.OBJECTLIT or end sequence)
                            // For now, we've stored the object in the local var
                        }
                        break;

                    case Icode_LITERAL_SET:
                        {
                            if (isInState(CompilerState.BUILDING_OBJECT_LITERAL)) {
                                // Handle object property setting
                                ObjectLiteralState objState = getStateData();

                                // Stack: value (property value to set)
                                // Get the property name from the current position in propertyIds
                                Object propertyId =
                                        objState.propertyIds[objState.currentPropertyIndex];

                                // Load the object from local variable
                                cfw.addALoad(objState.objectLocalVar); // value, object
                                cfw.add(ByteCode.SWAP); // object, value

                                if (propertyId instanceof String) {
                                    // Regular property name
                                    cfw.addPush((String) propertyId); // object, value, propertyName
                                    cfw.add(ByteCode.SWAP); // object, propertyName, value

                                    // Call ScriptRuntime.setObjectProp
                                    cfw.addALoad(
                                            contextLocal); // object, propertyName, value, context
                                    cfw.addALoad(
                                            scopeLocal); // object, propertyName, value, context,
                                    // scope
                                    cfw.addInvoke(
                                            ByteCode.INVOKESTATIC,
                                            "org/mozilla/javascript/ScriptRuntime",
                                            "setObjectProp",
                                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                                    cfw.add(ByteCode.POP); // Discard return value
                                } else {
                                    // For computed properties or other complex cases, we might need
                                    // different handling
                                    // For now, convert to string
                                    cfw.addPush(
                                            propertyId.toString()); // object, value, propertyName
                                    cfw.add(ByteCode.SWAP); // object, propertyName, value

                                    cfw.addALoad(contextLocal);
                                    cfw.addALoad(scopeLocal);
                                    cfw.addInvoke(
                                            ByteCode.INVOKESTATIC,
                                            "org/mozilla/javascript/ScriptRuntime",
                                            "setObjectProp",
                                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                                    cfw.add(ByteCode.POP);
                                }

                                // Advance to next property
                                objState.currentPropertyIndex++;
                            } else if (isInState(CompilerState.BUILDING_ARRAY_LITERAL)) {
                                // Handle array element setting
                                ArrayLiteralState arrayState = getStateData();

                                // Stack: value (element value to set)
                                // Load the array from local variable
                                cfw.addALoad(arrayState.arrayLocalVar); // value, array
                                cfw.add(ByteCode.SWAP); // array, value

                                // Push the current index
                                cfw.addPush(arrayState.currentIndex); // array, value, index
                                cfw.add(ByteCode.SWAP); // array, index, value

                                // Call ScriptRuntime.setObjectElem
                                cfw.addALoad(contextLocal); // array, index, value, context
                                cfw.addALoad(scopeLocal); // array, index, value, context, scope
                                cfw.addInvoke(
                                        ByteCode.INVOKESTATIC,
                                        "org/mozilla/javascript/ScriptRuntime",
                                        "setObjectElem",
                                        "(Ljava/lang/Object;DLjava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                                cfw.add(ByteCode.POP); // Discard return value

                                // Advance to next index
                                arrayState.currentIndex++;
                            } else {
                                // Should not happen - throw error
                                throw new RuntimeException(
                                        "Icode_LITERAL_SET called without object or array literal state");
                            }
                        }
                        break;

                    case Icode_LITERAL_KEY_SET:
                        {
                            if (!isInState(CompilerState.BUILDING_OBJECT_LITERAL)) {
                                throw new RuntimeException(
                                        "LITERAL_KEY_SET outside object literal construction");
                            }

                            ObjectLiteralState objState = getStateData();

                            // Stack: key, value (computed property key and value)
                            // Load the object from local variable
                            cfw.addALoad(objState.objectLocalVar); // key, value, object
                            cfw.add(ByteCode.DUP_X2); // object, key, value, object
                            cfw.add(ByteCode.POP); // object, key, value

                            // Call ScriptRuntime.setObjectElem for computed properties
                            cfw.addALoad(contextLocal); // object, key, value, context
                            cfw.addALoad(scopeLocal); // object, key, value, context, scope
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "org/mozilla/javascript/ScriptRuntime",
                                    "setObjectElem",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                            cfw.add(ByteCode.POP); // Discard return value

                            // Advance to next property
                            objState.currentPropertyIndex++;
                        }
                        break;

                    case Icode_LITERAL_GETTER:
                    case Icode_LITERAL_SETTER:
                        {
                            if (!isInState(CompilerState.BUILDING_OBJECT_LITERAL)) {
                                throw new RuntimeException(
                                        "LITERAL_GETTER/SETTER outside object literal construction");
                            }

                            ObjectLiteralState objState = getStateData();

                            // Stack: function (getter or setter function)
                            // Get the property name
                            Object propertyId = objState.propertyIds[objState.currentPropertyIndex];
                            String propertyName = propertyId.toString();

                            // Load the object
                            cfw.addALoad(objState.objectLocalVar); // function, object
                            cfw.add(ByteCode.SWAP); // object, function

                            cfw.addPush(propertyName); // object, function, propertyName
                            cfw.add(ByteCode.SWAP); // object, propertyName, function

                            // Determine if getter or setter
                            boolean isGetter = (opcode == Icode_LITERAL_GETTER);
                            cfw.addPush(isGetter); // object, propertyName, function, isGetter

                            cfw.addALoad(contextLocal);
                            cfw.addALoad(scopeLocal);

                            // Call ScriptRuntime.setObjectPropGetterSetter (if such method exists)
                            // For now, treat as regular property - this needs proper getter/setter
                            // support
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "org/mozilla/javascript/ScriptRuntime",
                                    "setObjectProp",
                                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                            cfw.add(ByteCode.POP);

                            objState.currentPropertyIndex++;
                        }
                        break;

                    case Icode_LITERAL_NEW_ARRAY:
                        {
                            // Enter array literal construction state
                            // indexReg contains the number of elements
                            int arrayLength =
                                    (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                            pc += 2;

                            // Create the array
                            cfw.addPush(arrayLength); // length
                            cfw.addALoad(contextLocal); // length, context
                            cfw.addALoad(scopeLocal); // length, context, scope
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "org/mozilla/javascript/ScriptRuntime",
                                    "newArray",
                                    "(ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");

                            // Store array in a local variable for state management
                            // Use a high local variable slot that won't conflict
                            int arrayLocalVar = 101; // Temporary slot for array literal state
                            cfw.addAStore(arrayLocalVar);

                            // Enter array literal building state
                            ArrayLiteralState arrayState =
                                    new ArrayLiteralState(arrayLocalVar, arrayLength);
                            enterState(CompilerState.BUILDING_ARRAY_LITERAL, arrayState);

                            // The final result will be pushed when we finish building (at
                            // Token.ARRAYLIT)
                        }
                        break;

                    case Icode_SPARE_ARRAYLIT:
                        {
                            // Sparse array literal - Stack: data_array, getter_setter_array ->
                            // sparse_array
                            // Read the literal index from bytecode
                            int literalIndex =
                                    (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                            pc += 2;

                            // literalIds[literalIndex] contains the skip indices for sparse array
                            int[] skipIndices = (int[]) idata.literalIds[literalIndex];

                            // Load skip indices as a Java int array
                            if (skipIndices != null) {
                                cfw.addPush(skipIndices.length);
                                cfw.add(ByteCode.NEWARRAY, ByteCode.T_INT);
                                for (int i = 0; i < skipIndices.length; i++) {
                                    cfw.add(ByteCode.DUP);
                                    cfw.addPush(i);
                                    cfw.addPush(skipIndices[i]);
                                    cfw.add(ByteCode.IASTORE);
                                }
                            } else {
                                cfw.add(ByteCode.ACONST_NULL);
                            }

                            // Stack: data_array, getter_setter_array, skip_indices
                            cfw.addALoad(
                                    contextLocal); // data_array, getter_setter_array, skip_indices,
                            // context
                            cfw.addALoad(
                                    scopeLocal); // data_array, getter_setter_array, skip_indices,
                            // context, scope

                            // Call ScriptRuntime.newArrayLiteral
                            cfw.addInvoke(
                                    ByteCode.INVOKESTATIC,
                                    "org/mozilla/javascript/ScriptRuntime",
                                    "newArrayLiteral",
                                    "([Ljava/lang/Object;[ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");
                        }
                        break;

                    case Icode_TEMPLATE_LITERAL_CALLSITE:
                        {
                            // Template literal call site - for now throw UnsupportedOperationException
                            // TODO: Implement by inlining template literal data at compile time
                            cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                            cfw.add(ByteCode.DUP);
                            cfw.addLoadConstant(
                                    "Template literal call sites not yet supported in BytecodeToClassCompiler");
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/lang/UnsupportedOperationException",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.add(ByteCode.ATHROW);
                        }
                        break;

                    // Comparison operators
                    case Token.EQ:
                        // Stack: left, right -> result
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "eq",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.NE:
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "eq",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        // Negate the result
                        int label1 = cfw.acquireLabel();
                        int label2 = cfw.acquireLabel();
                        cfw.add(ByteCode.IFEQ, label1); // if false, jump to push true
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "FALSE",
                                "Ljava/lang/Boolean;");
                        cfw.add(ByteCode.GOTO, label2);
                        cfw.markLabel(label1);
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "TRUE",
                                "Ljava/lang/Boolean;");
                        cfw.markLabel(label2);
                        pc++;
                        break;
                    case Token.LT:
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "cmp_LT",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.LE:
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "cmp_LE",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.GT:
                        // GT is equivalent to LE with swapped operands, then negated
                        cfw.add(ByteCode.SWAP);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "cmp_LE",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        // Negate the result
                        label1 = cfw.acquireLabel();
                        label2 = cfw.acquireLabel();
                        cfw.add(ByteCode.IFEQ, label1); // if false, jump to push true
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "FALSE",
                                "Ljava/lang/Boolean;");
                        cfw.add(ByteCode.GOTO, label2);
                        cfw.markLabel(label1);
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "TRUE",
                                "Ljava/lang/Boolean;");
                        cfw.markLabel(label2);
                        pc++;
                        break;
                    case Token.GE:
                        // GE is equivalent to LT with swapped operands, then negated
                        cfw.add(ByteCode.SWAP);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "cmp_LT",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        // Negate the result
                        label1 = cfw.acquireLabel();
                        label2 = cfw.acquireLabel();
                        cfw.add(ByteCode.IFEQ, label1); // if false, jump to push true
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "FALSE",
                                "Ljava/lang/Boolean;");
                        cfw.add(ByteCode.GOTO, label2);
                        cfw.markLabel(label1);
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "TRUE",
                                "Ljava/lang/Boolean;");
                        cfw.markLabel(label2);
                        pc++;
                        break;
                    case Token.SHEQ: // ===
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shallowEq",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.SHNE: // !==
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shallowEq",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                        // Negate the result
                        label1 = cfw.acquireLabel();
                        label2 = cfw.acquireLabel();
                        cfw.add(ByteCode.IFEQ, label1); // if false, jump to push true
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "FALSE",
                                "Ljava/lang/Boolean;");
                        cfw.add(ByteCode.GOTO, label2);
                        cfw.markLabel(label1);
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "TRUE",
                                "Ljava/lang/Boolean;");
                        cfw.markLabel(label2);
                        pc++;
                        break;

                    // Unary operations
                    case Token.NEG:
                        // Stack: operand -> -operand
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "unaryMinus",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.POS:
                        // Stack: operand -> +operand
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "unaryPlus",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.NOT:
                        // Stack: operand -> !operand
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "not",
                                "(Ljava/lang/Object;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.BITNOT:
                        // Stack: operand -> ~operand
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "bitwiseNot",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;

                    // Increment/decrement operations
                    case Icode_VAR_INC_DEC:
                        // Format: opcode, varIndex, incrDecrType (1=prefix, 0=postfix)
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int incrDecrType = idata.itsICode[pc++];
                        // Load variable value
                        cfw.addALoad(locals[varIndex]);

                        if ((incrDecrType & 1) == 0) { // postfix
                            // For postfix, we need to return the original value
                            cfw.add(ByteCode.DUP); // value, value
                        }

                        // Convert to number and increment/decrement
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toNumber",
                                "(Ljava/lang/Object;)D");

                        if ((incrDecrType & 2) == 0) { // increment
                            cfw.addPush(1.0);
                            cfw.add(ByteCode.DADD);
                        } else { // decrement
                            cfw.addPush(1.0);
                            cfw.add(ByteCode.DSUB);
                        }

                        // Convert back to object and store
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        cfw.add(ByteCode.DUP); // newValue, newValue
                        cfw.addAStore(locals[varIndex]); // newValue

                        if ((incrDecrType & 1) == 0) { // postfix
                            // Stack now has: originalValue, newValue
                            cfw.add(ByteCode.POP); // return originalValue
                        }
                        // For prefix, newValue is already on stack
                        break;

                    // Logical operators with short-circuiting
                    case Token.AND: // &&
                        // Stack: left, right -> result
                        // Short-circuit: if left is falsy, return left; otherwise return right
                        cfw.add(ByteCode.DUP_X1); // right, left, right
                        cfw.add(ByteCode.POP); // right, left
                        cfw.add(ByteCode.DUP); // right, left, left
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toBoolean",
                                "(Ljava/lang/Object;)Z");
                        int andFalseLabel = cfw.acquireLabel();
                        int andEndLabel = cfw.acquireLabel();
                        cfw.add(ByteCode.IFEQ, andFalseLabel); // if left is false, jump
                        cfw.add(ByteCode.POP); // pop left, keep right
                        cfw.add(ByteCode.GOTO, andEndLabel);
                        cfw.markLabel(andFalseLabel);
                        cfw.add(ByteCode.SWAP); // left, right
                        cfw.add(ByteCode.POP); // left (falsy value)
                        cfw.markLabel(andEndLabel);
                        pc++;
                        break;

                    case Token.OR: // ||
                        // Stack: left, right -> result
                        // Short-circuit: if left is truthy, return left; otherwise return right
                        cfw.add(ByteCode.DUP_X1); // right, left, right
                        cfw.add(ByteCode.POP); // right, left
                        cfw.add(ByteCode.DUP); // right, left, left
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toBoolean",
                                "(Ljava/lang/Object;)Z");
                        int orTrueLabel = cfw.acquireLabel();
                        int orEndLabel = cfw.acquireLabel();
                        cfw.add(ByteCode.IFNE, orTrueLabel); // if left is true, jump
                        cfw.add(ByteCode.POP); // pop left, keep right
                        cfw.add(ByteCode.GOTO, orEndLabel);
                        cfw.markLabel(orTrueLabel);
                        cfw.add(ByteCode.SWAP); // left, right
                        cfw.add(ByteCode.POP); // left (truthy value)
                        cfw.markLabel(orEndLabel);
                        pc++;
                        break;

                    // Bitwise operators
                    case Token.BITAND:
                        // Stack: left, right -> left & right
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "bitwiseAnd",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.BITOR:
                        // Stack: left, right -> left | right
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "bitwiseOr",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.BITXOR:
                        // Stack: left, right -> left ^ right
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "bitwiseXor",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.LSH:
                        // Stack: left, right -> left << right
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shiftLeft",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.RSH:
                        // Stack: left, right -> left >> right (sign-extending)
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shiftRight",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;
                    case Token.URSH:
                        // Stack: left, right -> left >>> right (zero-filling)
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shiftRightUnsigned",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;

                    // Missing important opcodes
                    case Token.NEW:
                        // Constructor calls
                        int newArgCount =
                                (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;
                        // Create array for arguments
                        cfw.addPush(newArgCount);
                        cfw.add(ByteCode.ANEWARRAY, "java/lang/Object");

                        // Store arguments in reverse order (stack is reversed)
                        for (int i = newArgCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1); // array, arg, array
                            cfw.add(ByteCode.SWAP); // array, array, arg
                            cfw.addPush(i); // array, array, arg, index
                            cfw.add(ByteCode.SWAP); // array, array, index, arg
                            cfw.add(ByteCode.AASTORE); // array
                        }

                        // Stack: constructor, args[]
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);

                        // Call ScriptRuntime.newObject
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "newObject",
                                "(Ljava/lang/Object;[Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;

                    case Icode_TYPEOFNAME:
                        // typeof operator on names - uses string register
                        cfw.addALoad(scopeLocal);
                        cfw.addALoad(stringRegLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "typeofName",
                                "(Lorg/mozilla/javascript/Scriptable;Ljava/lang/String;)Ljava/lang/Object;");
                        break;

                    case Icode_DELNAME:
                        // delete operator on names
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String delName = idata.itsStringTable[varIndex];
                        cfw.addPush(delName);
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "delete",
                                "(Ljava/lang/String;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        break;

                    case Icode_LINE:
                        // Line number tracking - skip for now
                        pc += 3; // Skip opcode + 2 bytes of line number data
                        break;

                    case Icode_IFEQ_POP:
                        // Conditional jump with pop
                        jumpTarget = pc + 2;
                        jumpTarget +=
                                (short)
                                        ((idata.itsICode[pc] << 8)
                                                | (idata.itsICode[pc + 1] & 0xff));
                        // Convert to boolean, check and pop
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "toBoolean",
                                "(Ljava/lang/Object;)Z");
                        if (labels[jumpTarget] != 0) {
                            cfw.add(ByteCode.IFEQ, labels[jumpTarget]);
                        }
                        pc += 2; // Skip jump offset
                        break;

                    // Basic literals
                    case Token.NULL:
                        cfw.add(ByteCode.ACONST_NULL);
                        pc++;
                        break;
                    case Token.TRUE:
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "TRUE",
                                "Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.FALSE:
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "java/lang/Boolean",
                                "FALSE",
                                "Ljava/lang/Boolean;");
                        pc++;
                        break;
                    case Token.THIS:
                        cfw.addALoad(thisObjLocal);
                        pc++;
                        break;
                    case Token.NUMBER:
                        // Number literal - index should be in bytecode
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        double numValue = idata.itsDoubleTable[varIndex];
                        cfw.addPush(numValue);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Double",
                                "valueOf",
                                "(D)Ljava/lang/Double;");
                        break;

                    // Reference operations
                    case Token.REF_CALL:
                        // Reference function call like f(args) in assignment contexts
                        int refArgCount =
                                (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;
                        // This is similar to regular CALL but handles reference semantics
                        // Create array for arguments
                        cfw.addPush(refArgCount);
                        cfw.add(ByteCode.ANEWARRAY, "java/lang/Object");

                        // Store arguments in reverse order
                        for (int i = refArgCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1); // array, arg, array
                            cfw.add(ByteCode.SWAP); // array, array, arg
                            cfw.addPush(i); // array, array, arg, index
                            cfw.add(ByteCode.SWAP); // array, array, index, arg
                            cfw.add(ByteCode.AASTORE); // array
                        }

                        // Call ScriptRuntime for reference call
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "callRef",
                                "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Ref;");
                        break;

                    // Name operations
                    case Token.NAME:
                        // Name reference - uses string register
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addALoad(stringRegLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "name",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;Ljava/lang/String;)Ljava/lang/Object;");
                        pc++;
                        break;

                    case Token.SETNAME:
                        // Set name binding
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String setNameStr = idata.itsStringTable[varIndex];
                        // Stack: value -> value
                        cfw.add(ByteCode.DUP); // value, value
                        cfw.addPush(setNameStr); // value, value, name
                        cfw.add(ByteCode.SWAP); // value, name, value
                        cfw.addALoad(contextLocal); // value, name, value, context
                        cfw.addALoad(scopeLocal); // value, name, value, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "setName",
                                "(Ljava/lang/String;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        cfw.add(ByteCode.POP); // value (keep original on stack)
                        break;

                    // Arithmetic operators
                    case Token.MOD:
                        // Modulo operator
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "mod",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;

                    case Token.EXP:
                        // Exponentiation operator **
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "pow",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        pc++;
                        break;

                    // Membership tests
                    case Token.IN:
                        // in operator
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "in",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;

                    case Token.INSTANCEOF:
                        // instanceof operator
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "instanceOf",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        pc++;
                        break;

                    // Property deletion
                    case Token.DELPROP:
                        // Stack: object, propertyName -> result
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String delPropName = idata.itsStringTable[varIndex];
                        cfw.addPush(delPropName);
                        cfw.add(ByteCode.SWAP); // propertyName, object
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "delete",
                                "(Ljava/lang/Object;Ljava/lang/String;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Z");
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Boolean",
                                "valueOf",
                                "(Z)Ljava/lang/Boolean;");
                        break;

                    // General typeof operator
                    case Token.TYPEOF:
                        // Stack: operand -> typeof_result
                        cfw.addALoad(contextLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "typeof",
                                "(Ljava/lang/Object;Lorg/mozilla/javascript/Context;)Ljava/lang/String;");
                        pc++;
                        break;

                    // Name binding - binds a name to its containing scope object
                    case Token.BINDNAME:
                        // Stack: -> bound_scope
                        // Uses string register for the name
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addALoad(stringRegLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "bind",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;Ljava/lang/String;)Lorg/mozilla/javascript/Scriptable;");
                        pc++;
                        break;

                    // Exception throwing
                    case Token.THROW:
                        // Stack: exception_value -> (throws exception)
                        // Create and throw a JavaScriptException
                        cfw.add(ByteCode.NEW, "org/mozilla/javascript/JavaScriptException");
                        cfw.add(ByteCode.DUP_X1);
                        cfw.add(ByteCode.SWAP);
                        cfw.addPush(idata.itsSourceFile != null ? idata.itsSourceFile : "compiled");
                        cfw.addPush(
                                0); // Line number - we don't have line info in bytecode compiler
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "org/mozilla/javascript/JavaScriptException",
                                "<init>",
                                "(Ljava/lang/Object;Ljava/lang/String;I)V");
                        cfw.add(ByteCode.ATHROW);
                        pc++;
                        break;

                    case Token.RETHROW:
                        // Stack: -> (throws stored exception)
                        // Get the stored exception from local variable and rethrow it
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int exceptionLocal = firstFreeLocal + varIndex;
                        cfw.addALoad(exceptionLocal);
                        cfw.add(ByteCode.ATHROW);
                        break;

                    // Reference operations - handle references to mutable values
                    case Token.GET_REF:
                        // Stack: ref -> value
                        // Get the value from a reference
                        cfw.addALoad(contextLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "refGet",
                                "(Lorg/mozilla/javascript/Ref;Lorg/mozilla/javascript/Context;)Ljava/lang/Object;");
                        pc++;
                        break;

                    case Token.SET_REF:
                        // Stack: ref, value -> value
                        // Set a value to a reference
                        cfw.add(ByteCode.DUP_X1); // value, ref, value
                        cfw.add(ByteCode.SWAP); // value, value, ref
                        cfw.add(ByteCode.SWAP); // ref, value, value
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "refSet",
                                "(Lorg/mozilla/javascript/Ref;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        cfw.add(ByteCode.POP); // Pop the return value, keep the original value on
                        // stack
                        pc++;
                        break;

                    case Token.DEL_REF:
                        // Stack: ref -> boolean_result
                        // Delete a reference
                        cfw.addALoad(contextLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "refDel",
                                "(Lorg/mozilla/javascript/Ref;Lorg/mozilla/javascript/Context;)Ljava/lang/Object;");
                        pc++;
                        break;

                    // Strict mode name setting - similar to SETNAME but with strict semantics
                    case Token.STRICT_SETNAME:
                        // Stack: bound_scope, value -> value
                        // Set a name in strict mode (throws if name doesn't exist)
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String strictName = idata.itsStringTable[varIndex];
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addPush(strictName);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "strictSetName",
                                "(Lorg/mozilla/javascript/Scriptable;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;Ljava/lang/String;)Ljava/lang/Object;");
                        break;

                    // Local variable loading
                    case Token.LOCAL_LOAD:
                        // Stack: -> value
                        // Load a value from a local variable slot
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int localSlot = firstFreeLocal + varIndex;
                        cfw.addALoad(localSlot);
                        break;

                    // Return stored result - returns from function with current stack top
                    case Token.RETURN_RESULT:
                        // Stack: return_value -> (return from function)
                        // Return the value on top of stack (should already be there from previous
                        // operations)
                        cfw.add(ByteCode.ARETURN);
                        pc++;
                        break;

                    // Scope management operations for closures and nested functions
                    case Icode_SCOPE_LOAD:
                        // Load scope from local variable into current scope field
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int scopeLoadSlot = firstFreeLocal + varIndex;
                        cfw.addALoad(scopeLoadSlot);
                        cfw.addAStore(scopeLocal); // Update the scope local variable
                        break;

                    case Icode_SCOPE_SAVE:
                        // Save current scope into local variable
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int scopeSaveSlot = firstFreeLocal + varIndex;
                        cfw.addALoad(scopeLocal); // Load current scope
                        cfw.addAStore(scopeSaveSlot); // Store into local variable slot
                        break;

                    // This function reference - loads the current function object
                    case Token.THISFN:
                        // Stack: -> this_function
                        // Load the current function object (this is local variable 0)
                        cfw.add(ByteCode.ALOAD_0);
                        pc++;
                        break;

                    // Regular expression literals
                    case Token.REGEXP:
                        // Stack: -> regexp_object
                        // For now throw UnsupportedOperationException
                        // TODO: Implement by inlining regex literal data at compile time
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                        cfw.add(ByteCode.DUP);
                        cfw.addLoadConstant(
                                "Regular expression literals not yet supported in BytecodeToClassCompiler");
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "(Ljava/lang/String;)V");
                        cfw.add(ByteCode.ATHROW);
                        break;

                    // BigInt literals
                    case Token.BIGINT:
                        // Stack: -> bigint_value
                        // BigInt value is stored in bigIntReg at runtime - need to implement
                        // register loading first
                        // For now, throw unsupported error since this requires implementing bigint
                        // register operations
                        // TODO(satish)
                        cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                        cfw.add(ByteCode.DUP);
                        cfw.addLoadConstant(
                                "BigInt literals not yet supported in BytecodeToClassCompiler");
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "(Ljava/lang/String;)V");
                        cfw.add(ByteCode.ATHROW);
                        pc++;
                        break;

                    // With statement support
                    case Token.ENTERWITH:
                        // Stack: object ->
                        // Enter a with statement by creating new scope
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "enterWith",
                                "(Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");
                        cfw.addAStore(scopeLocal); // Update scope variable
                        pc++;
                        break;

                    case Token.LEAVEWITH:
                        // Stack: ->
                        // Leave a with statement by restoring previous scope
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "leaveWith",
                                "(Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");
                        cfw.addAStore(scopeLocal); // Update scope variable
                        pc++;
                        break;

                    // Const variable operations (partial support)
                    case Icode_SETCONST:
                        // Stack: target_object value -> result
                        // This is complex and requires property setting logic
                        // Fall back to runtime error for now since this requires activation scope
                        // handling
                        cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                        cfw.add(ByteCode.DUP);
                        cfw.addLoadConstant(
                                "Const property setting not yet supported in BytecodeToClassCompiler");
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "(Ljava/lang/String;)V");
                        cfw.add(ByteCode.ATHROW);
                        break;

                    case Icode_SETCONSTVAR1:
                        // Get const variable index from next byte
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        // Fall through to SETCONSTVAR
                        // Stack: value -> value
                        // For now, just ignore the const semantics and treat as regular assignment
                        cfw.addAStore(locals[varIndex]);
                        break;

                    case (byte) Token.SETCONSTVAR:
                        // Stack: value -> value
                        // This uses indexReg for the variable index (complex)
                        // Fall back to runtime error since this requires activation frame handling
                        cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                        cfw.add(ByteCode.DUP);
                        cfw.addLoadConstant(
                                "Complex const variable setting not yet supported in BytecodeToClassCompiler");
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "(Ljava/lang/String;)V");
                        cfw.add(ByteCode.ATHROW);
                        break;

                    // String register operations - load string literals into register
                    case Icode_REG_STR_C0:
                        // Load strings[0] into stringReg - inline the value at compile time
                        String str0 = idata.itsStringTable[0];
                        cfw.addPush(str0);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR_C1:
                        // Load strings[1] into stringReg - inline the value at compile time
                        String str1 = idata.itsStringTable[1];
                        cfw.addPush(str1);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR_C2:
                        // Load strings[2] into stringReg - inline the value at compile time
                        String str2 = idata.itsStringTable[2];
                        cfw.addPush(str2);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR_C3:
                        // Load strings[3] into stringReg - inline the value at compile time
                        String str3 = idata.itsStringTable[3];
                        cfw.addPush(str3);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR1:
                        // Load strings[pc] into stringReg - inline the value at compile time
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        String strValue = idata.itsStringTable[varIndex];
                        cfw.addPush(strValue);
                        cfw.addAStore(stringRegLocal);
                        break;

                    case Icode_REG_STR2:
                        // Load strings[getIndex(iCode, pc)] into stringReg - inline the value at compile time
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8);
                        pc += 2;
                        String str2Value = idata.itsStringTable[varIndex];
                        cfw.addPush(str2Value);
                        cfw.addAStore(stringRegLocal);
                        break;

                    case Icode_REG_STR4:
                        // Load strings[getInt(iCode, pc)] into stringReg - inline the value at compile time
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8)
                                        | ((idata.itsICode[pc + 2] & 0xFF) << 16)
                                        | ((idata.itsICode[pc + 3] & 0xFF) << 24);
                        pc += 4;
                        String str4Value = idata.itsStringTable[varIndex];
                        cfw.addPush(str4Value);
                        cfw.addAStore(stringRegLocal);
                        break;

                    // BigInt register operations - load BigInt values into register
                    case Icode_REG_BIGINT_C0:
                        // Load bigInts[0] as a BigInteger object - inline at compile time
                        if (idata.itsBigIntTable != null && idata.itsBigIntTable.length > 0) {
                            java.math.BigInteger bigInt0 = idata.itsBigIntTable[0];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigInt0.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        pc++;
                        break;
                    case Icode_REG_BIGINT_C1:
                        // Load bigInts[1] as a BigInteger object - inline at compile time
                        if (idata.itsBigIntTable != null && idata.itsBigIntTable.length > 1) {
                            java.math.BigInteger bigInt1 = idata.itsBigIntTable[1];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigInt1.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        pc++;
                        break;
                    case Icode_REG_BIGINT_C2:
                        // Load bigInts[2] as a BigInteger object - inline at compile time
                        if (idata.itsBigIntTable != null && idata.itsBigIntTable.length > 2) {
                            java.math.BigInteger bigInt2 = idata.itsBigIntTable[2];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigInt2.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        pc++;
                        break;
                    case Icode_REG_BIGINT_C3:
                        // Load bigInts[3] as a BigInteger object - inline at compile time
                        if (idata.itsBigIntTable != null && idata.itsBigIntTable.length > 3) {
                            java.math.BigInteger bigInt3 = idata.itsBigIntTable[3];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigInt3.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        pc++;
                        break;
                    case Icode_REG_BIGINT1:
                        // Load bigInts[pc] as a BigInteger object - inline at compile time
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        if (idata.itsBigIntTable != null && varIndex < idata.itsBigIntTable.length) {
                            java.math.BigInteger bigIntValue = idata.itsBigIntTable[varIndex];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigIntValue.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(bigIntRegLocal);
                        break;
                    case Icode_REG_BIGINT2:
                        // Load bigInts[getIndex(iCode, pc)] as a BigInteger object - inline at compile time
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8);
                        pc += 2;
                        if (idata.itsBigIntTable != null && varIndex < idata.itsBigIntTable.length) {
                            java.math.BigInteger bigInt2 = idata.itsBigIntTable[varIndex];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigInt2.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        break;
                    case Icode_REG_BIGINT4:
                        // Load bigInts[getInt(iCode, pc)] as a BigInteger object - inline at compile time
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8)
                                        | ((idata.itsICode[pc + 2] & 0xFF) << 16)
                                        | ((idata.itsICode[pc + 3] & 0xFF) << 24);
                        pc += 4;
                        if (idata.itsBigIntTable != null && varIndex < idata.itsBigIntTable.length) {
                            java.math.BigInteger bigInt4 = idata.itsBigIntTable[varIndex];
                            cfw.add(ByteCode.NEW, "java/math/BigInteger");
                            cfw.add(ByteCode.DUP);
                            cfw.addPush(bigInt4.toString());
                            cfw.addInvoke(
                                    ByteCode.INVOKESPECIAL,
                                    "java/math/BigInteger",
                                    "<init>",
                                    "(Ljava/lang/String;)V");
                            cfw.addAStore(bigIntRegLocal);
                        } else {
                            cfw.add(ByteCode.ACONST_NULL);
                            cfw.addAStore(bigIntRegLocal);
                        }
                        break;

                    // Index register operations - load integer values into register
                    case Icode_REG_IND_C0:
                        cfw.addPush(0);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND_C1:
                        cfw.addPush(1);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND_C2:
                        cfw.addPush(2);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND_C3:
                        cfw.addPush(3);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND_C4:
                        cfw.addPush(4);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND_C5:
                        cfw.addPush(5);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND1:
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        cfw.addPush(varIndex);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND2:
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8);
                        pc += 2;
                        cfw.addPush(varIndex);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    case Icode_REG_IND4:
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8)
                                        | ((idata.itsICode[pc + 2] & 0xFF) << 16)
                                        | ((idata.itsICode[pc + 3] & 0xFF) << 24);
                        pc += 4;
                        cfw.addPush(varIndex);
                        cfw.add(
                                ByteCode.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;");
                        cfw.addAStore(indexRegLocal);
                        break;

                    // Array and object literals (complex operations)
                    case Token.ARRAYLIT:
                        {
                            if (isInState(CompilerState.BUILDING_ARRAY_LITERAL)) {
                                // Finalize array literal construction with state management
                                ArrayLiteralState arrayState = getStateData();

                                // Load the completed array from local variable
                                cfw.addALoad(arrayState.arrayLocalVar);

                                // Exit array literal building state
                                exitState();
                            } else {
                                // Traditional array literal handling - Stack: getter_setter_array
                                // data_array -> array_object
                                // Use ScriptRuntime.newArrayLiteral for complex literals
                                cfw.addALoad(
                                        contextLocal); // data_array, getter_setter_array, context
                                cfw.addALoad(
                                        scopeLocal); // data_array, getter_setter_array, context,
                                // scope

                                cfw.addInvoke(
                                        ByteCode.INVOKESTATIC,
                                        "org/mozilla/javascript/ScriptRuntime",
                                        "newArrayLiteral",
                                        "([Ljava/lang/Object;[ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");
                            }
                        }
                        break;

                    case Token.OBJECTLIT:
                        {
                            if (isInState(CompilerState.BUILDING_OBJECT_LITERAL)) {
                                // Finalize object literal construction
                                ObjectLiteralState objState = getStateData();

                                // Load the completed object onto the stack
                                cfw.addALoad(objState.objectLocalVar);

                                // Exit the object literal state
                                exitState();
                            } else {
                                // Old-style object literal handling (complex case with arrays on
                                // stack)
                                // This handles the case where we have the interpreter-style arrays
                                // on stack
                                // For now, fall back to unsupported
                                cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                                cfw.add(ByteCode.DUP);
                                cfw.addLoadConstant(
                                        "Complex object literals not yet supported in BytecodeToClassCompiler");
                                cfw.addInvoke(
                                        ByteCode.INVOKESPECIAL,
                                        "java/lang/UnsupportedOperationException",
                                        "<init>",
                                        "(Ljava/lang/String;)V");
                                cfw.add(ByteCode.ATHROW);
                            }
                        }
                        break;

                    // Increment/Decrement operations for names and elements
                    case Icode_NAME_INC_DEC:
                        // Format: opcode, nameIndex, incrDecrType (1=prefix, 0=postfix)
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        int nameIncrDecrType = idata.itsICode[pc++] & 0xFF;
                        String varName = idata.itsStringTable[varIndex];

                        // Load current value
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addPush(varName);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "name",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;Ljava/lang/String;)Ljava/lang/Object;");

                        // Increment/decrement
                        cfw.addPush(nameIncrDecrType); // 1 for prefix, 0 for postfix
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "postIncrDecr",
                                "(Ljava/lang/Object;ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");

                        // Store result back using setName
                        cfw.add(ByteCode.DUP); // Keep value on stack for return
                        cfw.addPush(varName);
                        cfw.add(ByteCode.SWAP); // name, value
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "setName",
                                "(Ljava/lang/String;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        cfw.add(ByteCode.POP); // Remove setName result
                        break;

                    case Icode_ELEM_INC_DEC:
                        // Format: opcode, incrDecrType
                        int elemIncrDecrType = idata.itsICode[pc++] & 0xFF;

                        // Stack should have: object, index
                        // Get current value
                        cfw.add(ByteCode.DUP2); // object, index, object, index
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getObjectElem",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");

                        // Increment/decrement the value
                        cfw.addPush(elemIncrDecrType);
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "postIncrDecr",
                                "(Ljava/lang/Object;ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");

                        // Set the new value back
                        // Stack: object, index, newValue
                        cfw.add(ByteCode.DUP_X2); // newValue, object, index, newValue
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "setObjectElem",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;

                    // Property access with 'this' context
                    case Icode_PROP_AND_THIS:
                        // Stack: object -> object, thisObj
                        // Get property and this context for method calls
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        propName = idata.itsStringTable[varIndex];

                        cfw.add(ByteCode.DUP); // object, object
                        // Get the property
                        cfw.addPush(propName);
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getObjectProp",
                                "(Ljava/lang/Object;Ljava/lang/String;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        cfw.add(ByteCode.SWAP); // thisObj, property
                        break;

                    case Icode_ELEM_AND_THIS:
                        // Stack: object, index -> property, thisObj
                        cfw.add(ByteCode.DUP2); // object, index, object, index
                        cfw.add(ByteCode.DUP2_X2); // object, index, object, index, object, index
                        cfw.add(ByteCode.POP2); // object, index, object, index

                        // Get the element value
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getObjectElem",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        cfw.add(ByteCode.SWAP); // thisObj, property
                        break;

                    case Icode_ELEM_AND_THIS_OPTIONAL:
                        // Stack: object, index -> result_or_null
                        // Similar to ELEM_AND_THIS but with optional chaining
                        cfw.addALoad(contextLocal); // object, index, context
                        cfw.addALoad(scopeLocal); // object, index, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getElemAndThisOptional",
                                "(Ljava/lang/Object;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Callable;");
                        break;

                    case Icode_VALUE_AND_THIS:
                        // Stack: value -> [value, thisObj]
                        cfw.addALoad(contextLocal); // value, context
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getValueAndThis",
                                "(Ljava/lang/Object;Lorg/mozilla/javascript/Context;)Lorg/mozilla/javascript/Callable;");
                        break;

                    case Icode_VALUE_AND_THIS_OPTIONAL:
                        // Stack: value -> result_or_null
                        // Similar to VALUE_AND_THIS but with optional chaining
                        cfw.addALoad(contextLocal); // value, context
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "getValueAndThisOptional",
                                "(Ljava/lang/Object;Lorg/mozilla/javascript/Context;)Lorg/mozilla/javascript/Callable;");
                        break;

                    // Conditional null/undefined checks
                    case Icode_IF_NULL_UNDEF:
                        // Stack: value -> (branch if null or undefined)
                        int nullUndefJumpTarget =
                                (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;

                        cfw.add(ByteCode.DUP); // value, value
                        cfw.add(ByteCode.ACONST_NULL); // value, value, null
                        int notNullLabel = cfw.acquireLabel();
                        int endLabel = cfw.acquireLabel();
                        cfw.add(ByteCode.IF_ACMPNE, notNullLabel); // if not null, check undefined
                        cfw.add(ByteCode.POP); // remove value
                        cfw.add(ByteCode.GOTO, labels[nullUndefJumpTarget]);

                        cfw.markLabel(notNullLabel);
                        // Check if undefined
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/Undefined",
                                "instance",
                                "Ljava/lang/Object;");
                        cfw.add(ByteCode.IF_ACMPEQ, labels[nullUndefJumpTarget]);
                        break;

                    case Icode_IF_NOT_NULL_UNDEF:
                        // Stack: value -> (branch if NOT null and NOT undefined)
                        int notNullUndefJumpTarget =
                                (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;

                        int skipLabel = cfw.acquireLabel();

                        // Check if null
                        cfw.add(ByteCode.DUP); // value, value
                        cfw.add(ByteCode.ACONST_NULL); // value, value, null
                        cfw.add(
                                ByteCode.IF_ACMPEQ,
                                skipLabel); // if null, skip to end (don't branch)

                        // Check if undefined
                        cfw.add(ByteCode.DUP); // value, value
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/Undefined",
                                "instance",
                                "Ljava/lang/Object;");
                        cfw.add(
                                ByteCode.IF_ACMPEQ,
                                skipLabel); // if undefined, skip to end (don't branch)

                        // If we get here, value is neither null nor undefined, so branch
                        cfw.add(ByteCode.POP); // remove value
                        cfw.add(ByteCode.GOTO, labels[notNullUndefJumpTarget]);

                        cfw.markLabel(skipLabel);
                        cfw.add(ByteCode.POP); // remove value
                        break;

                    // Reference increment/decrement operations
                    case Icode_REF_INC_DEC:
                        // Format: opcode, incrDecrType (1=prefix, 0=postfix)
                        int refIncrDecrType = idata.itsICode[pc++] & 0xFF;

                        // Stack: ref -> result
                        // Get current value from reference
                        cfw.add(ByteCode.DUP); // ref, ref
                        cfw.addALoad(contextLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "refGet",
                                "(Lorg/mozilla/javascript/Ref;Lorg/mozilla/javascript/Context;)Ljava/lang/Object;");

                        // Increment/decrement the value
                        cfw.addPush(refIncrDecrType); // 1 for prefix, 0 for postfix
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "postIncrDecr",
                                "(Ljava/lang/Object;ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");

                        // Store the new value back to the reference
                        // Stack: ref, newValue
                        cfw.add(ByteCode.DUP_X1); // newValue, ref, newValue
                        cfw.add(ByteCode.SWAP); // newValue, newValue, ref
                        cfw.add(ByteCode.DUP_X1); // newValue, ref, newValue, ref
                        cfw.add(ByteCode.SWAP); // newValue, ref, ref, newValue
                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "refSet",
                                "(Lorg/mozilla/javascript/Ref;Ljava/lang/Object;Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        cfw.add(ByteCode.POP); // Remove refSet result, keep newValue on stack
                        break;

                    // Special method call operations
                    case Icode_CALLSPECIAL:
                        // Format: opcode, argCount (2 bytes), methodType
                        int specialArgCount =
                                (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;
                        int methodType = idata.itsICode[pc++] & 0xFF;

                        // Create array for arguments
                        cfw.addPush(specialArgCount);
                        cfw.add(ByteCode.ANEWARRAY, "java/lang/Object");

                        // Store arguments in reverse order (stack is reversed)
                        for (int i = specialArgCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1); // array, arg, array
                            cfw.add(ByteCode.SWAP); // array, array, arg
                            cfw.addPush(i); // array, array, arg, index
                            cfw.add(ByteCode.SWAP); // array, array, index, arg
                            cfw.add(ByteCode.AASTORE); // array
                        }

                        // Stack should now have: function, thisObj, args[]
                        // Rearrange stack for callSpecial(cx, fun, thisObj, args, scope,
                        // callerThis, callType, filename, lineNumber, isOptionalChainingCall)
                        // Current stack: function, thisObj, args[]
                        // Need: cx, fun, thisObj, args[], scope, callerThis, callType, filename,
                        // lineNumber, isOptionalChainingCall

                        // Move function and thisObj to proper positions
                        cfw.add(ByteCode.DUP2_X1); // args[], function, thisObj, function, thisObj
                        cfw.add(ByteCode.POP2); // args[], function, thisObj
                        cfw.add(ByteCode.DUP_X2); // thisObj, args[], function, thisObj
                        cfw.add(ByteCode.POP); // thisObj, args[], function
                        cfw.add(ByteCode.DUP_X2); // function, thisObj, args[], function
                        cfw.add(ByteCode.POP); // function, thisObj, args[]

                        // Now we have: function, thisObj, args[]
                        // Build call: callSpecial(cx, fun, thisObj, args, scope, callerThis,
                        // callType, filename, lineNumber, isOptionalChainingCall)
                        cfw.addALoad(contextLocal); // cx
                        cfw.add(ByteCode.DUP_X2); // cx, function, thisObj, args[], cx
                        cfw.add(ByteCode.POP); // cx, function, thisObj, args[]
                        // Stack now: cx, function, thisObj, args[]

                        cfw.addALoad(scopeLocal); // scope
                        cfw.addALoad(thisObjLocal); // callerThis (use thisObjLocal as caller this)
                        cfw.addPush(methodType); // callType
                        cfw.addPush("<compiled>"); // filename
                        cfw.addPush(0); // lineNumber
                        cfw.addPush(false); // isOptionalChainingCall

                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "callSpecial",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Callable;Lorg/mozilla/javascript/Scriptable;[Ljava/lang/Object;Lorg/mozilla/javascript/Scriptable;Lorg/mozilla/javascript/Scriptable;ILjava/lang/String;IZ)Ljava/lang/Object;");
                        break;

                    case Icode_CALLSPECIAL_OPTIONAL:
                        // Format: opcode, argCount (2 bytes), methodType
                        int optionalSpecialArgCount =
                                (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;
                        int optionalMethodType = idata.itsICode[pc++] & 0xFF;

                        // Check if function is null/undefined for optional chaining
                        int nullCheckLabel = cfw.acquireLabel();
                        int endCallLabel = cfw.acquireLabel();

                        // Stack: function, thisObj, ...args
                        cfw.add(ByteCode.DUP2); // function, thisObj, args..., function, thisObj
                        cfw.add(ByteCode.POP); // function, thisObj, args..., function
                        cfw.add(ByteCode.DUP); // function, thisObj, args..., function, function
                        cfw.add(ByteCode.ACONST_NULL);
                        cfw.add(
                                ByteCode.IF_ACMPEQ,
                                nullCheckLabel); // if function is null, handle optional

                        cfw.add(ByteCode.DUP); // function, thisObj, args..., function, function
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/Undefined",
                                "instance",
                                "Ljava/lang/Object;");
                        cfw.add(
                                ByteCode.IF_ACMPEQ,
                                nullCheckLabel); // if function is undefined, handle optional

                        cfw.add(ByteCode.POP); // Remove extra function reference

                        // Create array for arguments and call normally
                        cfw.addPush(optionalSpecialArgCount);
                        cfw.add(ByteCode.ANEWARRAY, "java/lang/Object");

                        // Store arguments in reverse order
                        for (int i = optionalSpecialArgCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1);
                            cfw.add(ByteCode.SWAP);
                            cfw.addPush(i);
                            cfw.add(ByteCode.SWAP);
                            cfw.add(ByteCode.AASTORE);
                        }

                        // Stack has: function, thisObj, args[]
                        // Build call: callSpecial(cx, fun, thisObj, args, scope, callerThis,
                        // callType, filename, lineNumber, isOptionalChainingCall)
                        cfw.addALoad(contextLocal); // cx
                        cfw.add(ByteCode.DUP_X2); // cx, function, thisObj, args[], cx
                        cfw.add(ByteCode.POP); // cx, function, thisObj, args[]

                        cfw.addALoad(scopeLocal); // scope
                        cfw.addALoad(thisObjLocal); // callerThis
                        cfw.addPush(optionalMethodType); // callType
                        cfw.addPush("<compiled>"); // filename
                        cfw.addPush(0); // lineNumber
                        cfw.addPush(true); // isOptionalChainingCall

                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "callSpecial",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Callable;Lorg/mozilla/javascript/Scriptable;[Ljava/lang/Object;Lorg/mozilla/javascript/Scriptable;Lorg/mozilla/javascript/Scriptable;ILjava/lang/String;IZ)Ljava/lang/Object;");
                        cfw.add(ByteCode.GOTO, endCallLabel);

                        // Handle optional chaining case (function is null/undefined)
                        cfw.markLabel(nullCheckLabel);
                        // Pop all arguments and function/thisObj from stack
                        for (int i = 0; i <= optionalSpecialArgCount + 1; i++) {
                            cfw.add(ByteCode.POP);
                        }
                        // Return undefined for optional chaining
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/Undefined",
                                "instance",
                                "Ljava/lang/Object;");

                        cfw.markLabel(endCallLabel);
                        break;

                    case Icode_CALL_ON_SUPER:
                        // Format: opcode, argCount (1 byte)
                        int superArgCount = idata.itsICode[pc++] & 0xFF;

                        // Stack: LookupResult containing function and super object
                        // Get the LookupResult from stack
                        cfw.add(ByteCode.DUP); // result, result

                        // Get callable function from LookupResult
                        cfw.addInvoke(
                                ByteCode.INVOKEVIRTUAL,
                                "org/mozilla/javascript/ScriptRuntime$LookupResult",
                                "getCallable",
                                "()Lorg/mozilla/javascript/Callable;"); // result, function

                        // Get "this" object (will be super, but we'll replace it)
                        cfw.add(ByteCode.SWAP); // function, result
                        cfw.addInvoke(
                                ByteCode.INVOKEVIRTUAL,
                                "org/mozilla/javascript/ScriptRuntime$LookupResult",
                                "getThis",
                                "()Lorg/mozilla/javascript/Scriptable;"); // function,
                        // thisObj(super)

                        // Replace the super "this" with the current function's thisObj
                        cfw.add(ByteCode.POP); // function
                        cfw.addALoad(thisObjLocal); // function, thisObj(current)

                        // Create array for arguments
                        cfw.addPush(superArgCount);
                        cfw.add(
                                ByteCode.ANEWARRAY,
                                "java/lang/Object"); // function, thisObj, args[]

                        // Store arguments in reverse order (they're on stack in forward order)
                        for (int i = superArgCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1); // function, thisObj, args[], args[], arg
                            cfw.add(ByteCode.SWAP); // function, thisObj, args[], arg, args[]
                            cfw.addPush(i);
                            cfw.add(ByteCode.SWAP); // function, thisObj, args[], arg, args[], index
                            cfw.add(ByteCode.AASTORE); // function, thisObj, args[]
                        }

                        // Stack is now: function, thisObj, args[]
                        // Call using ScriptRuntime.call (regular call, not callSpecial)
                        cfw.addALoad(contextLocal); // cx
                        cfw.add(ByteCode.DUP_X2); // cx, function, thisObj, args[], cx
                        cfw.add(ByteCode.POP); // cx, function, thisObj, args[]

                        cfw.addALoad(scopeLocal); // scope

                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "call",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Callable;Lorg/mozilla/javascript/Scriptable;[Ljava/lang/Object;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");
                        break;

                    case Icode_TAIL_CALL:
                        // Format: opcode, argCount (1 byte)
                        int tailArgCount = idata.itsICode[pc++] & 0xFF;

                        // For compiled code, treat tail calls as regular calls
                        // The JVM will handle any optimizations
                        // Stack: function, thisObj, ...args

                        // Create array for arguments
                        cfw.addPush(tailArgCount);
                        cfw.add(ByteCode.ANEWARRAY, "java/lang/Object");

                        // Store arguments in reverse order
                        for (int i = tailArgCount - 1; i >= 0; i--) {
                            cfw.add(ByteCode.DUP_X1);
                            cfw.add(ByteCode.SWAP);
                            cfw.addPush(i);
                            cfw.add(ByteCode.SWAP);
                            cfw.add(ByteCode.AASTORE);
                        }

                        // Stack is now: function, thisObj, args[]
                        // Make the call using ScriptRuntime.call
                        cfw.addALoad(contextLocal); // cx
                        cfw.add(ByteCode.DUP_X2); // cx, function, thisObj, args[], cx
                        cfw.add(ByteCode.POP); // cx, function, thisObj, args[]

                        cfw.addALoad(scopeLocal); // scope

                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "call",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Callable;Lorg/mozilla/javascript/Scriptable;[Ljava/lang/Object;Lorg/mozilla/javascript/Scriptable;)Ljava/lang/Object;");

                        // For tail calls, we return immediately (this is the optimization)
                        // Rather than continuing execution, we return the call result
                        cfw.add(ByteCode.ARETURN);
                        break;

                    default:
                        // Fall back to undefined for unsupported opcodes
                        cfw.add(
                                ByteCode.GETSTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "UNDEFINED",
                                "Ljava/lang/Object;");
                        pc++; // Advance past unsupported opcode
                        break;
                }
            }

            // Default return undefined if we reach the end without a return
            if (!hasReturn) {
                cfw.add(
                        ByteCode.GETSTATIC,
                        "org/mozilla/javascript/ScriptRuntime",
                        "UNDEFINED",
                        "Ljava/lang/Object;");
                cfw.add(ByteCode.ARETURN);
            }
            cfw.stopMethod((short) firstFreeLocal); // account for all locals

            byte[] classBytes = cfw.toByteArray();

            // Define the class using same approach as IFnToClassCompiler
            ClassLoader parentLoader = cx.getApplicationClassLoader();
            final byte[] finalClassBytes = classBytes;
            final String finalClassName = className;
            ClassLoader loader =
                    new ClassLoader(parentLoader) {
                        @Override
                        protected Class<?> findClass(String name) throws ClassNotFoundException {
                            if (name.equals(finalClassName)) {
                                return defineClass(
                                        name, finalClassBytes, 0, finalClassBytes.length);
                            }
                            return super.findClass(name);
                        }
                    };
            Class<?> clazz = loader.loadClass(className);

            // No need to set static field - all values were inlined at compile time
            
            // Create a new instance of the compiled function
            java.lang.reflect.Constructor<?> constructor =
                    clazz.getConstructor();
            NativeFunction compiledFunction =
                    (NativeFunction) constructor.newInstance();

            // Set function properties to match IFnToClassCompiler behavior
            compiledFunction.setPrototypeProperty(ifun.getPrototypeProperty());
            compiledFunction.setHomeObject(ifun.getHomeObject());

            // Set the function name property
            String functionName = ifun.getFunctionName();
            if (functionName != null && !functionName.isEmpty()) {
                compiledFunction.put("name", compiledFunction, functionName);
            }

            return compiledFunction;

        } catch (Exception e) {
            // Log the error and fall back to interpretation
            Context.reportError("Error compiling function: " + e.getMessage());
            return null;
        }
    }
}
// spotless:on
