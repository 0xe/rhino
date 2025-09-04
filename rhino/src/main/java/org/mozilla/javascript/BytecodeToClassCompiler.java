package org.mozilla.javascript;

import static org.mozilla.javascript.Icode.Icode_DELNAME;
import static org.mozilla.javascript.Icode.Icode_DUP;
import static org.mozilla.javascript.Icode.Icode_DUP2;
import static org.mozilla.javascript.Icode.Icode_GETVAR1;
import static org.mozilla.javascript.Icode.Icode_IFEQ_POP;
import static org.mozilla.javascript.Icode.Icode_INTNUMBER;
import static org.mozilla.javascript.Icode.Icode_LINE;
import static org.mozilla.javascript.Icode.Icode_LITERAL_NEW_ARRAY;
import static org.mozilla.javascript.Icode.Icode_LITERAL_NEW_OBJECT;
import static org.mozilla.javascript.Icode.Icode_NAME_AND_THIS;
import static org.mozilla.javascript.Icode.Icode_ONE;
import static org.mozilla.javascript.Icode.Icode_POP;
import static org.mozilla.javascript.Icode.Icode_POP_RESULT;
import static org.mozilla.javascript.Icode.Icode_PROP_INC_DEC;
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
import static org.mozilla.javascript.Icode.Icode_RETUNDEF;
import static org.mozilla.javascript.Icode.Icode_SCOPE_LOAD;
import static org.mozilla.javascript.Icode.Icode_SCOPE_SAVE;
import static org.mozilla.javascript.Icode.Icode_SETCONST;
import static org.mozilla.javascript.Icode.Icode_SETCONSTVAR1;
import static org.mozilla.javascript.Icode.Icode_SETVAR1;
import static org.mozilla.javascript.Icode.Icode_SHORTNUMBER;
import static org.mozilla.javascript.Icode.Icode_SWAP;
import static org.mozilla.javascript.Icode.Icode_TYPEOFNAME;
import static org.mozilla.javascript.Icode.Icode_UNDEF;
import static org.mozilla.javascript.Icode.Icode_VAR_INC_DEC;
import static org.mozilla.javascript.Icode.Icode_ZERO;

import java.util.concurrent.atomic.AtomicInteger;
import org.mozilla.classfile.ByteCode;
import org.mozilla.classfile.ClassFileWriter;
import org.mozilla.javascript.optimizer.Codegen;

// spotless:off

/** Attempt to convert icode to Java classes. */
public class BytecodeToClassCompiler implements Context.FunctionCompiler {
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

            // Add constructor matching IFnToClassCompiler pattern
            cfw.startMethod(
                    "<init>",
                    "(Lorg/mozilla/javascript/Scriptable;Lorg/mozilla/javascript/Context;I)V",
                    ACC_PUBLIC);
            cfw.addALoad(0); // this
            cfw.addInvoke(ByteCode.INVOKESPECIAL, SUPER_CLASS_NAME, "<init>", "()V");
            cfw.add(ByteCode.RETURN);
            cfw.stopMethod((short) 4);

            // Generate the class file from interpreter bytecode
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
            int firstFreeLocal = 7;
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
                        break;

                    // Object/Array creation
                    case Icode_LITERAL_NEW_OBJECT:
                        // Create new object using ScriptRuntime
                        cfw.addALoad(contextLocal); // context
                        cfw.addALoad(scopeLocal); // context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "newObject",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");
                        break;
                    case Icode_LITERAL_NEW_ARRAY:
                        int length = (idata.itsICode[pc] << 8) | (idata.itsICode[pc + 1] & 0xff);
                        pc += 2;
                        // Create new array using ScriptRuntime
                        cfw.addPush(length); // length
                        cfw.addALoad(contextLocal); // length, context
                        cfw.addALoad(scopeLocal); // length, context, scope
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "newArray",
                                "(ILorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;)Lorg/mozilla/javascript/Scriptable;");
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
                        break;
                    case Token.RSH:
                        // Stack: left, right -> left >> right (sign-extending)
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shiftRight",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        break;
                    case Token.URSH:
                        // Stack: left, right -> left >>> right (zero-filling)
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "shiftRightUnsigned",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
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
                        break;

                    case Token.EXP:
                        // Exponentiation operator **
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "pow",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
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
                        // Create a RegExp object from precompiled pattern
                        varIndex = idata.itsICode[pc++] & 0xFF;

                        cfw.addALoad(contextLocal);
                        cfw.addALoad(scopeLocal);
                        // Access the regexp data from the InterpreterData at runtime
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsRegExpLiterals",
                                "[Ljava/lang/Object;");
                        cfw.addPush(varIndex);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addInvoke(
                                ByteCode.INVOKESTATIC,
                                "org/mozilla/javascript/ScriptRuntime",
                                "wrapRegExp",
                                "(Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/Scriptable;Ljava/lang/Object;)Lorg/mozilla/javascript/Scriptable;");
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
                        // Load strings[0] into stringReg
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(0);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR_C1:
                        // Load strings[1] into stringReg
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(1);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR_C2:
                        // Load strings[2] into stringReg
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(2);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR_C3:
                        // Load strings[3] into stringReg
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(3);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
                        pc++;
                        break;

                    case Icode_REG_STR1:
                        // Load strings[pc] into stringReg
                        varIndex = idata.itsICode[pc++] & 0xFF;
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(varIndex);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
                        break;

                    case Icode_REG_STR2:
                        // Load strings[getIndex(iCode, pc)] into stringReg
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8);
                        pc += 2;
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(varIndex);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
                        break;

                    case Icode_REG_STR4:
                        // Load strings[getInt(iCode, pc)] into stringReg
                        varIndex =
                                (idata.itsICode[pc] & 0xFF)
                                        | ((idata.itsICode[pc + 1] & 0xFF) << 8)
                                        | ((idata.itsICode[pc + 2] & 0xFF) << 16)
                                        | ((idata.itsICode[pc + 3] & 0xFF) << 24);
                        pc += 4;
                        cfw.addALoad(0); // Load 'this'
                        cfw.add(
                                ByteCode.GETFIELD,
                                className,
                                "idata",
                                "Lorg/mozilla/javascript/InterpreterData;");
                        cfw.add(
                                ByteCode.GETFIELD,
                                "org/mozilla/javascript/InterpreterData",
                                "itsStringTable",
                                "[Ljava/lang/String;");
                        cfw.addPush(varIndex);
                        cfw.add(ByteCode.AALOAD);
                        cfw.addAStore(stringRegLocal);
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
                        // Stack: getter_setter_array data_array -> array_object
                        // Complex array literal creation - requires handling arrays on stack
                        cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                        cfw.add(ByteCode.DUP);
                        cfw.addLoadConstant(
                                "Array literals not yet supported in BytecodeToClassCompiler");
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "(Ljava/lang/String;)V");
                        cfw.add(ByteCode.ATHROW);
                        break;

                    case Token.OBJECTLIT:
                        // Stack: object getter_setter_array values_array keys_array -> object
                        // Complex object literal creation - requires handling multiple arrays on
                        // stack
                        cfw.add(ByteCode.NEW, "java/lang/UnsupportedOperationException");
                        cfw.add(ByteCode.DUP);
                        cfw.addLoadConstant(
                                "Object literals not yet supported in BytecodeToClassCompiler");
                        cfw.addInvoke(
                                ByteCode.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "(Ljava/lang/String;)V");
                        cfw.add(ByteCode.ATHROW);
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

            // Create a new instance of the compiled function
            java.lang.reflect.Constructor<?> constructor =
                    clazz.getConstructor(Scriptable.class, Context.class, int.class);
            NativeFunction compiledFunction =
                    (NativeFunction) constructor.newInstance(scope, cx, 1);

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
