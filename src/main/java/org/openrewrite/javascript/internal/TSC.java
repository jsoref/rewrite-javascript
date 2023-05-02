/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.javascript.internal;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interception.logging.JavetStandardConsoleInterceptor;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueBoolean;
import com.caoccao.javet.values.primitive.V8ValueInteger;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.primitive.V8ValueUndefined;
import com.caoccao.javet.values.reference.*;
import org.openrewrite.IOUtils;
import org.openrewrite.internal.lang.Nullable;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface TSC {

    static String getJSEntryProgramText() {
        try (InputStream is = TSC.class.getClassLoader().getResourceAsStream("index.js")) {
            if (is == null) throw new IllegalStateException("entry JS resource does not exist");
            return IOUtils.readFully(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    class Runtime implements Closeable {
        public final V8Runtime v8Runtime;

        @Nullable
        public V8ValueFunction tsParse = null;

        private final JavetStandardConsoleInterceptor javetStandardConsoleInterceptor;

        public static Runtime init() {
            try {
                V8Runtime v8Runtime = V8Host.getV8Instance().createV8Runtime();
                JavetStandardConsoleInterceptor javetStandardConsoleInterceptor = new JavetStandardConsoleInterceptor(v8Runtime);
                javetStandardConsoleInterceptor.register(v8Runtime.getGlobalObject());
                return new Runtime(v8Runtime, javetStandardConsoleInterceptor);
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public Runtime(V8Runtime v8Runtime, JavetStandardConsoleInterceptor javetStandardConsoleInterceptor) {
            this.v8Runtime = v8Runtime;
            this.javetStandardConsoleInterceptor = javetStandardConsoleInterceptor;
        }

        public void importTS() {
            if (tsParse != null) {
                return;
            }
            try {
                v8Runtime.getExecutor("const require = () => undefined;").executeVoid();
                v8Runtime.getExecutor("const module = {exports: {}};").executeVoid();
                v8Runtime.getExecutor(getJSEntryProgramText()).executeVoid();
                this.tsParse = v8Runtime.getExecutor("module.exports.default").execute();
                this.tsParse.setWeak();
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public void parseSourceText(String sourceText, BiConsumer<Node, Context> callback) {
            importTS();
            try {
                assert tsParse != null;
                try (V8Value tmp = tsParse.call(null, sourceText)) {
                    if (!(tmp instanceof V8ValueObject)) {
                        throw new RuntimeException();
                    }

                    V8ValueObject obj = (V8ValueObject) tmp;
                    TSC.Context context;
                    TSC.Node node;

                    context = TSC.Context.fromJS(obj);

                    try (V8Value nodeV8 = obj.get("sourceFile")) {
                        node = context.tscNode(nodeV8);
                        callback.accept(node, context);
                    }
                }
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            if (!v8Runtime.isClosed()) {
                v8Runtime.await();
                v8Runtime.lowMemoryNotification();

                try {
                    javetStandardConsoleInterceptor.unregister(v8Runtime.getGlobalObject());
                } catch (JavetException ignored) {
                }
                v8Runtime.await();
                v8Runtime.lowMemoryNotification();
            }
            try {
                v8Runtime.close();
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }
    }

    interface ContextCallback {
        V8Value apply(V8Value... args);
    }

    class Context {
        private static final Method CONTEXT_CALLBACK_APPLY_METHOD;

        static {
            try {
                CONTEXT_CALLBACK_APPLY_METHOD = ContextCallback.class.getDeclaredMethod("apply", V8Value[].class);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        private final V8Runtime runtime;
        private final WeakHashMap<V8Value, Node> cache = new WeakHashMap<>();
        private final V8ValueObject scanner;

        public static Context fromJS(V8ValueObject contextV8) {
            try {
                V8ValueObject metaV8Object = contextV8.get("meta");
                metaV8Object.setWeak();

                V8Value syntaxKinds = metaV8Object.get("syntaxKinds");

                V8ValueObject scanner = contextV8.get("scanner");
                scanner.setWeak();

                Context context = new Context(metaV8Object.getV8Runtime(), scanner);
                if (syntaxKinds instanceof V8ValueMap) {
                    ((V8ValueMap) syntaxKinds).forEach((V8Value keyV8, V8Value valueV8) -> {
                        if (keyV8 instanceof V8ValueString && valueV8 instanceof V8ValueInteger) {
                            int code = ((V8ValueInteger) valueV8).getValue();
                            String name = ((V8ValueString) keyV8).getValue();
                            context.syntaxKindsByCode.put(code, name);
                            context.syntaxKindsByName.put(name, code);
                        }
                    });
                }
                return context;
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        private final Map<String, Integer> syntaxKindsByName = new HashMap<>();
        private final Map<Integer, String> syntaxKindsByCode = new HashMap<>();

        private Context(V8Runtime runtime, V8ValueObject scanner) {
            this.runtime = runtime;
            this.scanner = scanner;
            resetScanner(0);
        }

        public Node tscNode(V8Value v8) {
            return this.cache.computeIfAbsent(v8, (arg) -> {
                if (!(arg instanceof V8ValueObject)) {
                    throw new IllegalArgumentException("can only wrap a V8 object as a Node");
                }
//                System.out.println("*** creating new Node instance");
                return new Node(this, (V8ValueObject) arg);
            });
        }

        public Integer scannerTokenStart() {
            try {
                return this.scanner.invokeInteger("getTokenPos");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public Integer scannerTokenEnd() {
            try {
                return this.scanner.invokeInteger("getTextPos");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public String scannerTokenText() {
            try {
                return this.scanner.invokeString("getTokenText");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public void resetScanner(int offset) {
            try {
                this.scanner.invokeVoid("setTextPos", offset);
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public TSCSyntaxKind nextScannerSyntaxType() {
            try {
                final int code = this.scanner.invokeInteger("scan");
                final String name = this.syntaxKindName(code);
                return TSCSyntaxKind.fromJS(name);
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }


        public V8ValueFunction asJSFunction(ContextCallback func) {
            JavetCallbackContext callbackContext = new JavetCallbackContext(func, CONTEXT_CALLBACK_APPLY_METHOD);
            try {
                return this.runtime.createV8ValueFunction(callbackContext);
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public V8ValueFunction asJSFunction(Function<? super V8Value, ? extends V8Value> func) {
            ContextCallback callback = (V8Value[] args) -> func.apply(args[0]);
            return asJSFunction(callback);
        }

        public V8ValueFunction asJSFunction(Consumer<? super V8Value> func) {
            ContextCallback callback = (V8Value[] args) -> {
                func.accept(args[0]);
                return runtime.createV8ValueUndefined();
            };
            return asJSFunction(callback);
        }

        public int syntaxKindCode(String name) {
            return this.syntaxKindsByName.get(name);
        }

        public String syntaxKindName(int code) {
            return this.syntaxKindsByCode.get(code);
        }
    }

    class Node {
        private final Context context;
        private final V8ValueObject object;

        public Node(Context context, V8ValueObject object) {
            this.context = context;
            this.object = object;
        }

        public int syntaxKindCode() {
            try {
                return object.getInteger("kind");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public String syntaxKindName() {
            return context.syntaxKindName(this.syntaxKindCode());
        }

        public TSCSyntaxKind syntaxKind() {
            return TSCSyntaxKind.fromJS(syntaxKindName());
        }

        public int getStart() {
            try {
                return this.object.getPropertyInteger("pos");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public int getEnd() {
            try {
                return this.object.getPropertyInteger("end");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public int getChildCount() {
            try {
                return this.object.invokeInteger("getChildCount");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public @Nullable Node getChildNode(String name) {
            try {
                V8Value child = this.object.getProperty(name);
                if (child == null) {
                    return null;
                }
                return context.tscNode(child);
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public Node getChildNodeRequired(String name) {
            Node child = this.getChildNode(name);
            if (child == null) {
                throw new IllegalArgumentException("property " + name + " is not required");
            }
            return child;
        }

        public List<Node> getChildNodes(String name) {
            try (V8ValueArray children = this.object.getProperty(name)) {
                final int childCount = children.getLength();
                List<Node> result = new ArrayList<>(childCount);
                for (int i = 0; i < childCount; i++) {
                    result.add(context.tscNode(children.get(i)));
                }
                return result;
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public boolean getBooleanPropertyValue(String propertyName) {
            boolean propertyValue = false;
            try {
                V8Value val = this.object.getProperty(propertyName);
                propertyValue = val instanceof V8ValueBoolean && ((V8ValueBoolean) val).getValue();
            } catch (JavetException ignored) {
            }
            return propertyValue;
        }

        public boolean hasProperty(String propertyName) {
            boolean isFound = false;
            try {
                isFound = !(this.object.getProperty(propertyName) instanceof V8ValueUndefined);
            } catch (JavetException ignored) {
            }
            return isFound;
        }

        public String getText() {
            try {
                return this.object.invokeString("getText");
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public <T> List<T> collectChildNodes(String name, Function<Node, @Nullable T> fn) {
            List<T> results = new ArrayList<>();
            for (Node child : this.getChildNodes(name)) {
                @Nullable T result = fn.apply(child);
                if (result != null) {
                    results.add(result);
                }
            }
            return results;
        }

        public <T> List<T> mapChildNodes(String name, Function<Node, @Nullable T> fn) {
            List<T> results = new ArrayList<>();
            for (Node child : this.getChildNodes(name)) {
                results.add(fn.apply(child));
            }
            return results;
        }

        public void forEachChild(Consumer<Node> callback) {
            Consumer<V8Value> v8Callback = v8Value -> callback.accept(context.tscNode(v8Value));
            try (V8Value v8Function = context.asJSFunction(v8Callback)) {
                this.object.invoke("forEachChild", v8Function);
            } catch (JavetException e) {
                throw new RuntimeException(e);
            }
        }

        public void printTree(PrintStream ps) {
            printTree(ps, "");
        }

        // FIXME: Remove. Temporary method to view object
        public V8ValueObject getObject() {
            return object;
        }


        // FIXME: Remove. Temporary method to view context
        public Context getContext() {
            return context;
        }

        public List<String> getOwnPropertyNames() {
            try {
                return this.object.getOwnPropertyNames().getOwnPropertyNameStrings();
            } catch (JavetException ex) {
                throw new RuntimeException(ex);
            }
        }

        public List<String> getPropertyNames() {
            try {
                return this.object.getPropertyNames().getOwnPropertyNameStrings();
            } catch (JavetException ex) {
                throw new RuntimeException(ex);
            }
        }

        private void printTree(PrintStream ps, String indent) {
            ps.print(indent);
            ps.print(syntaxKindName());
            if (syntaxKindName().contains("Literal")) {
                ps.print(" (" + this.getText() + ")");
            }
            ps.println();

            String childIndent = indent + "  ";
            forEachChild(child -> {
                child.printTree(ps, childIndent);
            });

        }
    }

}
