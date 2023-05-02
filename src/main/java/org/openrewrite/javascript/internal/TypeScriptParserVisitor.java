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

import com.caoccao.javet.values.primitive.V8ValueInteger;
import org.openrewrite.FileAttributes;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.tree.*;
import org.openrewrite.javascript.TypeScriptTypeMapping;
import org.openrewrite.javascript.tree.JS;
import org.openrewrite.marker.Markers;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.java.tree.Space.EMPTY;

public class TypeScriptParserVisitor {

    private final TSC.Node source;
    private final TSC.Context cursorContext;
    private final Path sourcePath;
    private final TypeScriptTypeMapping typeMapping;

    @Nullable
    private final Path relativeTo;

    private final String charset;
    private final boolean isCharsetBomMarked;

    public TypeScriptParserVisitor(TSC.Node source, TSC.Context sourceContext, Path sourcePath, @Nullable Path relativeTo, JavaTypeCache typeCache, String charset, boolean isCharsetBomMarked) {
        this.source = source;
        this.cursorContext = sourceContext;
        this.sourcePath = sourcePath;
        this.relativeTo = relativeTo;
        this.charset = charset;
        this.isCharsetBomMarked = isCharsetBomMarked;
        this.typeMapping = new TypeScriptTypeMapping(typeCache);
    }

    public JS.CompilationUnit mapSourceFile() {
        Space prefix = whitespace();

        List<JRightPadded<Statement>> statements = source.collectChildNodes("statements",
                child -> {
                    @Nullable J mapped = mapNode(child);
                    if (mapped != null) {
                        return new JRightPadded<>((Statement) mapped, EMPTY, Markers.EMPTY);
                    } else {
                        return null;
                    }
                }
        );
        return new JS.CompilationUnit(
                randomId(),
                prefix,
                Markers.EMPTY,
                relativeTo == null ? null : relativeTo.relativize(sourcePath),
                FileAttributes.fromPath(sourcePath),
                charset,
                isCharsetBomMarked,
                null,
                // FIXME remove
                source.getText(),
                emptyList(),
                statements,
                EMPTY
        );
    }

    private J.Block mapBlock(@Nullable TSC.Node node) {
        // TODO: handle null TSC.Node.

        expect(TSCSyntaxKind.Block, node);

        Space prefix = sourceBefore(TSCSyntaxKind.OpenBraceToken);

        List<TSC.Node> statementNodes = node.getChildNodes("statements");
        List<JRightPadded<Statement>> statements = new ArrayList<>(statementNodes.size());

        for (TSC.Node statementNode : statementNodes) {
            statements.add(mapStatement(statementNode));
        }

        Space endOfBlock = sourceBefore(TSCSyntaxKind.CloseBraceToken);
        return new J.Block(
                randomId(),
                prefix,
                Markers.EMPTY,
                JRightPadded.build(false),
                statements,
                endOfBlock
        );
    }

    private J.ClassDeclaration mapClassDeclaration(TSC.Node node) {
        Space prefix = whitespace();
        Markers markers = Markers.EMPTY;

        List<J.Annotation> leadingAnnotation = emptyList(); // FIXME

        List<J.Modifier> modifiers = emptyList();
        if (node.hasProperty("modifiers")) {
            modifiers = mapModifiers(node.getChildNodes("modifiers"));
        }

        List<J.Annotation> kindAnnotations = emptyList(); // FIXME

        Space kindPrefix;
        TSCSyntaxKind syntaxKind = node.syntaxKind();
        J.ClassDeclaration.Kind.Type type;
        switch (syntaxKind) {
            case EnumDeclaration:
                kindPrefix = sourceBefore(TSCSyntaxKind.EnumKeyword);
                type = J.ClassDeclaration.Kind.Type.Enum;
                break;
            case InterfaceDeclaration:
                kindPrefix = sourceBefore(TSCSyntaxKind.InterfaceKeyword);
                type = J.ClassDeclaration.Kind.Type.Interface;
                break;
            default:
                kindPrefix = sourceBefore(TSCSyntaxKind.ClassKeyword);
                type = J.ClassDeclaration.Kind.Type.Class;
        }

        J.ClassDeclaration.Kind kind = new J.ClassDeclaration.Kind(randomId(), kindPrefix, Markers.EMPTY, kindAnnotations, type);

        J.Identifier name;
        if (node.hasProperty("name")) {
            name = mapIdentifier(node.getChildNodeRequired("name"));
        } else {
            throw new UnsupportedOperationException("Class has no name ... add support");
        }

        JContainer<J.TypeParameter> typeParams = null;

        J.Block body;
        if (node.hasProperty("members")) {
            // TODO: assess; maybe revise mapBlock with an input of List<TSC.Node> nodes.
            Space bodyPrefix = sourceBefore(TSCSyntaxKind.OpenBraceToken);

            List<TSC.Node> nodes = node.getChildNodes("members");
            List<JRightPadded<Statement>> members = new ArrayList<>(nodes.size());
            for (TSC.Node statement : nodes) {
                members.add(mapStatement(statement));
            }

            body = new J.Block(randomId(), bodyPrefix, Markers.EMPTY, new JRightPadded<>(false, EMPTY, Markers.EMPTY),
                    members, sourceBefore(TSCSyntaxKind.CloseBraceToken));
        } else {
            // This shouldn't happen.
            throw new UnsupportedOperationException("Add support for empty body");
        }

        JContainer<Statement> primaryConstructor = null;

        // // FIXME: extendings and implementings work differently in TS @Gary.
        JLeftPadded<TypeTree> extendings = null;
        JContainer<TypeTree> implementings = null;

        return new J.ClassDeclaration(
                randomId(),
                prefix,
                markers,
                leadingAnnotation,
                modifiers,
                kind,
                name,
                typeParams,
                primaryConstructor,
                extendings,
                implementings,
                null,
                body,
                (JavaType.FullyQualified) typeMapping.type(node));
    }


    // FIXME
    private J mapFunctionDeclaration(TSC.Node node) {
        Space prefix = sourceBefore(TSCSyntaxKind.FunctionKeyword);

        J.Identifier name = mapIdentifier(node.getChildNodeRequired("name"));

        JContainer<Statement> parameters = mapContainer(
                TSCSyntaxKind.OpenParenToken,
                node.getChildNodes("parameters"),
                TSCSyntaxKind.CommaToken,
                TSCSyntaxKind.CloseParenToken,
                this::mapFunctionParameter
        );

        J.Block block = mapBlock(node.getChildNode("body"));

        return new J.MethodDeclaration(
                randomId(),
                prefix,
                Markers.EMPTY,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null,
                new J.MethodDeclaration.IdentifierWithAnnotations(name, Collections.emptyList()),
                parameters,
                null,
                block,
                null,
                typeMapping.methodDeclarationType(node)
        );
    }

    private Statement mapFunctionParameter(TSC.Node node) {
        throw new UnsupportedOperationException();
    }

    private J.Identifier mapIdentifier(TSC.Node node) {
        expect(TSCSyntaxKind.Identifier, node);

        Space prefix = sourceBefore(node);
        return new J.Identifier(
                randomId(),
                prefix,
                Markers.EMPTY,
                node.getText(),
                typeMapping.type(node),
                null // FIXME
        );
    }

    private JRightPadded<Statement> mapStatement(TSC.Node node) {
        // FIXME
        Statement statement = (Statement) mapNode(node);

        assert statement != null;
        return padRight(statement, EMPTY);
    }

    private List<J.Modifier> mapModifiers(List<TSC.Node> nodes) {
        List<J.Modifier> modifiers = new ArrayList<>(nodes.size());
        for (TSC.Node node : nodes) {
            List<J.Annotation> annotations = emptyList(); // FIXME: maybe add annotations.
            switch (node.syntaxKind()) {
                case AbstractKeyword:
                    Space prefix = whitespace();
                    consumeToken(TSCSyntaxKind.AbstractKeyword);
                    modifiers.add(new J.Modifier(randomId(), prefix, Markers.EMPTY, J.Modifier.Type.Abstract, annotations));
                    break;
                default:
                    throw new UnsupportedOperationException("implement me.");
            }
        }

        return modifiers;
    }

    @Nullable
    private J mapNode(TSC.Node node) {
        J j;
        switch (node.syntaxKind()) {
            case EnumDeclaration:
            case InterfaceDeclaration:
            case ClassDeclaration:
                j = mapClassDeclaration(node);
                break;
            case FunctionDeclaration:
                j = mapFunctionDeclaration(node);
                break;
            default:
                System.err.println("unsupported syntax kind: " + node.syntaxKindName());
                j = null;
        }
        return j;
    }

    /**
     * Returns the current cursor position in the TSC.Context.
     */
    private Integer getCursorPosition() {
        return this.cursorContext.scannerTokenEnd();
    }

    /**
     * Set the cursor position to the specified index.
     */
    private void cursor(int cursor) {
        System.err.println("[scanner] reset to pos=" + cursor + " (from pos=" + this.getCursorPosition() + ")");
        this.cursorContext.resetScanner(cursor);
    }

    /**
     * Increment the cursor position to the end of the node.
     */
    private void skip(TSC.Node node) {
        this.cursorContext.resetScanner(node.getEnd());
    }

    private <T> JLeftPadded<T> padLeft(Space left, T tree) {
        return new JLeftPadded<>(left, tree, Markers.EMPTY);
    }

    private <T> JRightPadded<T> padRight(T tree, @Nullable Space right) {
        return new JRightPadded<>(tree, right == null ? EMPTY : right, Markers.EMPTY);
    }

    private TSCSyntaxKind scan() {
        System.err.println("[scanner] scanning at pos=" + this.getCursorPosition());
        TSCSyntaxKind kind = this.cursorContext.nextScannerSyntaxType();
        System.err.println("[scanner]     scan returned kind=" + kind + "; start=" + cursorContext.scannerTokenStart() + "; end=" + cursorContext.scannerTokenEnd() + ");");
        return kind;
    }

    private String lastToken() {
        return this.cursorContext.scannerTokenText();
    }

    private void consumeToken(TSCSyntaxKind kind) {
        TSCSyntaxKind actual = scan();
        if (kind != actual) {
            throw new IllegalStateException(String.format("expected kind '%s'; found '%s' at position %d", kind, actual, cursorContext.scannerTokenStart()));
        }
    }

    private TSC.Node expect(TSC.Node node) {
        if (this.getCursorPosition() != node.getEnd()) {
            throw new IllegalStateException(String.format("expected position '%d'; found '%d'", node.getEnd(), this.getCursorPosition()));
        }
        return node;
    }

    private TSC.Node expect(TSCSyntaxKind kind, TSC.Node node) {
        if (node.syntaxKind() != kind) {
            throw new IllegalStateException(String.format("expected kind '%s'; found '%s'", kind, node.syntaxKindName()));
        }
        if (this.getCursorPosition() != node.getStart()) {
            throw new IllegalStateException(
                    String.format(
                            "expected position %d; found %d (node text=`%s`, end=%d)",
                            node.getStart(),
                            this.getCursorPosition(),
                            node.getText().replace("\n", "⏎"),
                            node.getEnd()
                    )
            );
        }
        return node;
    }

    private String tokenStreamDebug() {
        return String.format("[start=%d, end=%d, text=`%s`]", this.cursorContext.scannerTokenStart(), this.cursorContext.scannerTokenEnd(), this.cursorContext.scannerTokenText().replace("\n", "⏎"));
    }

    private <T> JContainer<T> mapContainer(TSCSyntaxKind open, List<TSC.Node> nodes, @Nullable TSCSyntaxKind delimiter, TSCSyntaxKind close, Function<TSC.Node, T> mapFn) {
        Space containerPrefix = whitespace();
        consumeToken(open);
        List<JRightPadded<T>> rightPaddeds;
        if (nodes.isEmpty()) {
            Space withinContainerSpace = whitespace();
            //noinspection unchecked
            rightPaddeds = Collections.singletonList(
                    JRightPadded.build((T) new J.Empty(UUID.randomUUID(), withinContainerSpace, Markers.EMPTY))
            );
        } else {
            rightPaddeds = new ArrayList<>(nodes.size());
            for (TSC.Node node : nodes) {
                T mapped = mapFn.apply(node);
                Space after = whitespace();
                rightPaddeds.add(JRightPadded.build(mapped).withAfter(after));
                if (delimiter != null) {
                    consumeToken(delimiter);
                }
            }
        }
        consumeToken(close);
        return JContainer.build(containerPrefix, rightPaddeds, Markers.EMPTY);
    }

    private Space sourceBefore(TSC.Node node) {
        Space prefix = whitespace();
        skip(node);
        return prefix;
    }

    private Space sourceBefore(TSCSyntaxKind syntaxKind) {
        Space prefix = whitespace();
        consumeToken(syntaxKind);
        return prefix;
    }

    /**
     * Consume whitespace and leading comments until the current node.
     */
    private Space whitespace() {
        System.err.println("[scanner] consuming space, starting at pos=" + getCursorPosition());
        String initialSpace = "";
        List<Comment> comments = Collections.emptyList();
        TSCSyntaxKind kind;
        boolean done = false;
        do {
            kind = scan();
            switch (kind) {
                case WhitespaceTrivia:
                case NewLineTrivia:
                    System.err.println("[scanner]     appending whitespace");
                    if (comments.isEmpty()) {
                        initialSpace += lastToken();
                    } else {
                        comments = ListUtils.mapLast(
                                comments,
                                comment -> comment.withSuffix(comment.getSuffix() + lastToken())
                        );
                    }
                    break;
                case SingleLineCommentTrivia:
                case MultiLineCommentTrivia:
                    System.err.println("[scanner]     appending comment");
                    Comment comment = new TextComment(
                            kind == TSCSyntaxKind.MultiLineCommentTrivia,
                            lastToken(),
                            "",
                            Markers.EMPTY
                    );
                    if (comments.isEmpty()) {
                        comments = Collections.singletonList(comment);
                    } else {
                        comments = ListUtils.concat(comments, comment);
                    }
                    break;
                default:
                    // rewind to before this token
                    System.err.println("[scanner]     resetting to pos=" + cursorContext.scannerTokenStart());
                    cursor(cursorContext.scannerTokenStart());
                    done = true;
                    break;
            }
        } while (!done);
        return Space.build(initialSpace, comments);
    }
}