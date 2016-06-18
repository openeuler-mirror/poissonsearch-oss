/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Constant;
import org.elasticsearch.painless.Definition.Method;
import org.elasticsearch.painless.Definition.MethodKey;
import org.elasticsearch.painless.Executable;
import org.elasticsearch.painless.Globals;
import org.elasticsearch.painless.Locals;
import org.elasticsearch.painless.Locals.Variable;
import org.elasticsearch.painless.node.SFunction.Reserved;
import org.elasticsearch.painless.WriterConstants;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.TraceClassVisitor;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.painless.WriterConstants.BASE_CLASS_TYPE;
import static org.elasticsearch.painless.WriterConstants.CLASS_TYPE;
import static org.elasticsearch.painless.WriterConstants.CONSTRUCTOR;
import static org.elasticsearch.painless.WriterConstants.EXECUTE;
import static org.elasticsearch.painless.WriterConstants.MAP_GET;
import static org.elasticsearch.painless.WriterConstants.MAP_TYPE;

/**
 * The root of all Painless trees.  Contains a series of statements.
 */
public final class SSource extends AStatement {

    final String name;
    final String source;
    final Printer debugStream;
    final MainMethodReserved reserved;
    final List<SFunction> functions;
    final Globals globals;
    final List<AStatement> statements;

    private Locals mainMethod;
    private byte[] bytes;

    public SSource(String name, String source, Printer debugStream, MainMethodReserved reserved, Location location, 
                   List<SFunction> functions, Globals globals, List<AStatement> statements) {
        super(location);

        this.name = name;
        this.source = source;
        this.debugStream = debugStream;
        this.reserved = reserved;
        // process any synthetic functions generated by walker (because right now, thats still easy)
        functions.addAll(globals.getSyntheticMethods().values());
        globals.getSyntheticMethods().clear();
        this.functions = Collections.unmodifiableList(functions);
        this.statements = Collections.unmodifiableList(statements);
        this.globals = globals;
    }

    public void analyze() {
        Map<MethodKey, Method> methods = new HashMap<>();

        for (SFunction function : functions) {
            function.generate();

            MethodKey key = new MethodKey(function.name, function.parameters.size());

            if (methods.put(key, function.method) != null) {
                throw createError(new IllegalArgumentException("Duplicate functions with name [" + function.name + "]."));
            }
        }

        analyze(Locals.newProgramScope(methods.values()));
    }

    @Override
    void analyze(Locals program) {
        for (SFunction function : functions) {
            Locals functionLocals = Locals.newFunctionScope(program, function.rtnType, function.parameters, 
                                                            function.reserved.getMaxLoopCounter());
            function.analyze(functionLocals);
        }

        if (statements == null || statements.isEmpty()) {
            throw createError(new IllegalArgumentException("Cannot generate an empty script."));
        }

        mainMethod = Locals.newMainMethodScope(program, reserved.usesScore(), reserved.usesCtx(), reserved.getMaxLoopCounter());

        AStatement last = statements.get(statements.size() - 1);

        for (AStatement statement : statements) {
            // Note that we do not need to check after the last statement because
            // there is no statement that can be unreachable after the last.
            if (allEscape) {
                throw createError(new IllegalArgumentException("Unreachable statement."));
            }

            statement.lastSource = statement == last;

            statement.analyze(mainMethod);

            methodEscape = statement.methodEscape;
            allEscape = statement.allEscape;
        }
    }

    public void write() {
        // Create the ClassWriter.

        int classFrames = ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS;
        int classVersion = Opcodes.V1_8;
        int classAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_FINAL;
        String classBase = BASE_CLASS_TYPE.getInternalName();
        String className = CLASS_TYPE.getInternalName();
        String classInterfaces[] = reserved.usesScore() ? new String[] { WriterConstants.NEEDS_SCORE_TYPE.getInternalName() } : null;

        ClassWriter writer = new ClassWriter(classFrames);
        ClassVisitor visitor = writer;
        
        if (debugStream != null) {
            visitor = new TraceClassVisitor(visitor, debugStream, null);
        }
        visitor.visit(classVersion, classAccess, className, null, classBase, classInterfaces);
        visitor.visitSource(Location.computeSourceName(name, source), null);

        // Write the constructor:
        MethodWriter constructor = new MethodWriter(Opcodes.ACC_PUBLIC, CONSTRUCTOR, visitor, globals.getStatements());
        constructor.visitCode();
        constructor.loadThis();
        constructor.loadArgs();
        constructor.invokeConstructor(org.objectweb.asm.Type.getType(Executable.class), CONSTRUCTOR);
        constructor.returnValue();
        constructor.endMethod();

        // Write the execute method:
        MethodWriter execute = new MethodWriter(Opcodes.ACC_PUBLIC, EXECUTE, visitor, globals.getStatements());
        execute.visitCode();
        write(execute, globals);
        execute.endMethod();
        
        // Write all functions:
        for (SFunction function : functions) {
            function.write(visitor, globals);
        }
        
        // Write all synthetic functions. Note that this process may add more :)
        while (!globals.getSyntheticMethods().isEmpty()) {
            List<SFunction> current = new ArrayList<>(globals.getSyntheticMethods().values());
            globals.getSyntheticMethods().clear();
            for (SFunction function : current) {
                function.write(visitor, globals);
            }
        }

        // Write the constants
        if (false == globals.getConstantInitializers().isEmpty()) {
            Collection<Constant> inits = globals.getConstantInitializers().values();

            // Fields
            for (Constant constant : inits) {
                visitor.visitField(
                        Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                        constant.name,
                        constant.type.getDescriptor(),
                        null,
                        null).visitEnd();
            }

            // Initialize the constants in a static initializer
            final MethodWriter clinit = new MethodWriter(Opcodes.ACC_STATIC, 
                    WriterConstants.CLINIT, visitor, globals.getStatements());
            for (Constant constant : inits) {
                constant.initializer.accept(clinit);
                clinit.putStatic(CLASS_TYPE, constant.name, constant.type);
            }
            clinit.returnValue();
            clinit.endMethod();
        }
        
        // End writing the class and store the generated bytes.

        visitor.visitEnd();
        bytes = writer.toByteArray();
    }
    
    @Override
    void write(MethodWriter writer, Globals globals) {
        if (reserved.usesScore()) {
            // if the _score value is used, we do this once:
            // final double _score = scorer.score();
            Variable scorer = mainMethod.getVariable(null, Locals.SCORER);
            Variable score = mainMethod.getVariable(null, Locals.SCORE);

            writer.visitVarInsn(Opcodes.ALOAD, scorer.getSlot());
            writer.invokeVirtual(WriterConstants.SCORER_TYPE, WriterConstants.SCORER_SCORE);
            writer.visitInsn(Opcodes.F2D);
            writer.visitVarInsn(Opcodes.DSTORE, score.getSlot());
        }

        if (reserved.usesCtx()) {
            // if the _ctx value is used, we do this once:
            // final Map<String,Object> ctx = input.get("ctx");

            Variable input = mainMethod.getVariable(null, Locals.PARAMS);
            Variable ctx = mainMethod.getVariable(null, Locals.CTX);

            writer.visitVarInsn(Opcodes.ALOAD, input.getSlot());
            writer.push(Locals.CTX);
            writer.invokeInterface(MAP_TYPE, MAP_GET);
            writer.visitVarInsn(Opcodes.ASTORE, ctx.getSlot());
        }

        if (reserved.getMaxLoopCounter() > 0) {
            // if there is infinite loop protection, we do this once:
            // int #loop = settings.getMaxLoopCounter()

            Variable loop = mainMethod.getVariable(null, Locals.LOOP);

            writer.push(reserved.getMaxLoopCounter());
            writer.visitVarInsn(Opcodes.ISTORE, loop.getSlot());
        }

        for (AStatement statement : statements) {
            statement.write(writer, globals);
        }

        if (!methodEscape) {
            writer.visitInsn(Opcodes.ACONST_NULL);
            writer.returnValue();
        }
    }

    public BitSet getStatements() {
        return globals.getStatements();
    }

    public byte[] getBytes() {
        return bytes;
    }
    
    
    public static final class MainMethodReserved implements Reserved {
        private boolean score = false;
        private boolean ctx = false;
        private int maxLoopCounter = 0;

        @Override
        public void markReserved(String name) {
            if (Locals.SCORE.equals(name)) {
                score = true;
            } else if (Locals.CTX.equals(name)) {
                ctx = true;
            }
        }

        @Override
        public boolean isReserved(String name) {
            return Locals.KEYWORDS.contains(name);
        }

        public boolean usesScore() {
            return score;
        }

        public boolean usesCtx() {
            return ctx;
        }

        @Override
        public void setMaxLoopCounter(int max) {
            maxLoopCounter = max;
        }

        @Override
        public int getMaxLoopCounter() {
            return maxLoopCounter;
        }
    }
}
