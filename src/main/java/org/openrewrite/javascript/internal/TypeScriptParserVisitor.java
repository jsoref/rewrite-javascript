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

import org.openrewrite.FileAttributes;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.Semicolon;
import org.openrewrite.java.marker.TrailingComma;
import org.openrewrite.java.tree.*;
import org.openrewrite.javascript.TypeScriptTypeMapping;
import org.openrewrite.javascript.internal.tsc.TSCNode;
import org.openrewrite.javascript.internal.tsc.TSCSourceFileContext;
import org.openrewrite.javascript.internal.tsc.generated.TSCSyntaxKind;
import org.openrewrite.javascript.tree.JS;
import org.openrewrite.marker.Markers;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.java.tree.Space.EMPTY;

@SuppressWarnings("DataFlowIssue")
public class TypeScriptParserVisitor {

    private final TSCNode source;
    private final TSCSourceFileContext cursorContext;
    private final Path sourcePath;
    private final TypeScriptTypeMapping typeMapping;

    @Nullable
    private final Path relativeTo;

    private final String charset;
    private final boolean isCharsetBomMarked;

    public TypeScriptParserVisitor(TSCNode source, TSCSourceFileContext sourceContext, Path sourcePath, @Nullable Path relativeTo, JavaTypeCache typeCache, String charset, boolean isCharsetBomMarked) {
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
                        if (!(mapped instanceof Statement) && mapped instanceof Expression) {
                            mapped = new JS.ExpressionStatement(randomId(), (Expression) mapped);
                        }
                        return maybeSemicolon((Statement) mapped);
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

    private J.Block mapBlock(@Nullable TSCNode node) {
        if (node == null) {
            // Some bodies can return a null block.
            throw new UnsupportedOperationException("FIXME");
        }

        Space prefix = sourceBefore(TSCSyntaxKind.OpenBraceToken);

        List<TSCNode> statementNodes = node.getChildNodes("statements");
        List<JRightPadded<Statement>> statements = new ArrayList<>(statementNodes.size());

        for (TSCNode statementNode : statementNodes) {
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

    private J.ClassDeclaration mapClassDeclaration(TSCNode node) {
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
        List<JRightPadded<Statement>> members;
        if (node.hasProperty("members")) {
            Space bodyPrefix = sourceBefore(TSCSyntaxKind.OpenBraceToken);

            TSCNode membersNode = node.getChildNodeRequired("members");
            List<TSCNode> nodes = node.getChildNodes("members");
            if (kind.getType() == J.ClassDeclaration.Kind.Type.Enum) {
                Space enumPrefix = whitespace();

                members = new ArrayList<>(1);
                List<JRightPadded<J.EnumValue>> enumValues = new ArrayList<>(nodes.size());
                for (int i = 0; i < nodes.size(); i++) {
                    TSCNode enumValue = nodes.get(i);
                    J.EnumValue value = (J.EnumValue) mapNode(enumValue);
                    if (value != null) {
                        boolean hasTrailingComma = i == nodes.size() - 1 && membersNode.getBooleanPropertyValue("hasTrailingComma");
                        Space after = i < nodes.size() - 1 ? sourceBefore(TSCSyntaxKind.CommaToken) :
                                hasTrailingComma ? sourceBefore(TSCSyntaxKind.CommaToken) : EMPTY;
                        JRightPadded<J.EnumValue> ev = padRight(value, after);
                        if (i == nodes.size() - 1) {
                            if (hasTrailingComma) {
                                ev = ev.withMarkers(ev.getMarkers().addIfAbsent(new TrailingComma(randomId(), EMPTY)));
                            }
                        }
                        enumValues.add(ev);
                    }
                }

                JRightPadded<Statement> enumSet = padRight(
                        new J.EnumValueSet(
                                randomId(),
                                enumPrefix,
                                Markers.EMPTY,
                                enumValues,
                                false
                        ),
                        EMPTY
                );
                members.add(enumSet);
            } else {
                members = new ArrayList<>(nodes.size());
                for (TSCNode statement : nodes) {
                    members.add(mapStatement(statement));
                }
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

    private J.Assignment mapAssignment(TSCNode node) {
        Space prefix = whitespace();
        Expression left = (Expression) mapNode(node.getChildNodeRequired("left"));
        Space before = sourceBefore(TSCSyntaxKind.EqualsToken);
        Expression right = (Expression) mapNode(node.getChildNodeRequired("right"));
        return new J.Assignment(
                randomId(),
                prefix,
                Markers.EMPTY,
                left,
                padLeft(before, right),
                typeMapping.type(node)
        );
    }

    private J.AssignmentOperation mapAssignmentOperation(TSCNode node) {
        Space prefix = whitespace();
        Expression left = (Expression) mapNode(node.getChildNodeRequired("left"));
        JLeftPadded<J.AssignmentOperation.Type> op = null;
        TSCSyntaxKind opKind = node.getChildNodeRequired("operatorToken").syntaxKind();
        switch (opKind) {
            case AsteriskEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.AsteriskEqualsToken), J.AssignmentOperation.Type.Multiplication);
                break;
            case MinusEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.MinusEqualsToken), J.AssignmentOperation.Type.Subtraction);
                break;
            case PlusEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.PlusEqualsToken), J.AssignmentOperation.Type.Addition);
                break;
            case SlashEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.SlashEqualsToken), J.AssignmentOperation.Type.Division);
                break;
            default:
                implementMe(node);
        }

        Expression right = (Expression) mapNode(node.getChildNodeRequired("right"));
        return new J.AssignmentOperation(
                randomId(),
                prefix,
                Markers.EMPTY,
                left,
                op,
                right,
                typeMapping.type(node)
        );
    }

    private J.Binary mapBinary(TSCNode node) {
        Space prefix = whitespace();
        Expression left = (Expression) mapNode(node.getChildNodeRequired("left"));

        JLeftPadded<J.Binary.Type> op = null;
        TSCSyntaxKind opKind = node.getChildNodeRequired("operatorToken").syntaxKind();
        switch (opKind) {
            // Bitwise ops
            case AmpersandToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.AmpersandToken), J.Binary.Type.BitAnd);
                break;
            case BarToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.BarToken), J.Binary.Type.BitOr);
                break;
            case CaretToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.CaretToken), J.Binary.Type.BitXor);
                break;
            case GreaterThanGreaterThanToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.GreaterThanGreaterThanToken), J.Binary.Type.RightShift);
                break;
            case LessThanLessThanToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.LessThanLessThanToken), J.Binary.Type.LeftShift);
                break;
            // Logical ops
            case EqualsEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.EqualsEqualsToken), J.Binary.Type.Equal);
                break;
            case ExclamationEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.ExclamationEqualsToken), J.Binary.Type.NotEqual);
                break;
            case GreaterThanToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.GreaterThanToken), J.Binary.Type.GreaterThan);
                break;
            case GreaterThanEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.GreaterThanEqualsToken), J.Binary.Type.GreaterThanOrEqual);
                break;
            case GreaterThanGreaterThanGreaterThanToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.GreaterThanGreaterThanGreaterThanToken), J.Binary.Type.UnsignedRightShift);
                break;
            case LessThanToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.LessThanToken), J.Binary.Type.LessThan);
                break;
            case LessThanEqualsToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.LessThanEqualsToken), J.Binary.Type.LessThanOrEqual);
                break;
            // Arithmetic ops
            case AsteriskToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.AsteriskToken), J.Binary.Type.Multiplication);
                break;
            case MinusToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.MinusToken), J.Binary.Type.Subtraction);
                break;
            case PercentToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.PercentToken), J.Binary.Type.Modulo);
                break;
            case PlusToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.PlusToken), J.Binary.Type.Addition);
                break;
            case SlashToken:
                op = padLeft(sourceBefore(TSCSyntaxKind.SlashToken), J.Binary.Type.Division);
                break;
            default:
                implementMe(node);
        }

        Expression right = (Expression) mapNode(node.getChildNodeRequired("right"));
        return new J.Binary(
                randomId(),
                prefix,
                Markers.EMPTY,
                left,
                op,
                right,
                typeMapping.type(node)
        );
    }

    private J mapBinaryExpression(TSCNode node) {

        TSCSyntaxKind opKind = node.getChildNodeRequired("operatorToken").syntaxKind();
        // TS represents J.Assignment, J.AssignmentOperation, and J.Binary as a BinaryExpression.
        switch (opKind) {
            case EqualsToken:
                return mapAssignment(node);
            case AsteriskEqualsToken:
            case MinusEqualsToken:
            case PlusEqualsToken:
            case SlashEqualsToken:
                return mapAssignmentOperation(node);
            case AmpersandToken:
            case AsteriskToken:
            case BarToken:
            case CaretToken:
            case EqualsEqualsToken:
            case ExclamationEqualsToken:
            case GreaterThanToken:
            case GreaterThanEqualsToken:
            case GreaterThanGreaterThanToken:
            case GreaterThanGreaterThanGreaterThanToken:
            case LessThanToken:
            case LessThanEqualsToken:
            case LessThanLessThanToken:
            case MinusToken:
            case PercentToken:
            case PlusToken:
            case SlashToken:
                return mapBinary(node);
            default:
                implementMe(node.getChildNodeRequired("operatorToken"));
        }

        return null;
    }

    private Statement mapEmptyStatement(TSCNode ignored) {
        return new J.Empty(randomId(), EMPTY, Markers.EMPTY);
    }

    private J.EnumValue mapEnumMember(TSCNode node) {
        Space prefix = whitespace();

        List<J.Annotation> annotations = null; // FIXME
        return new J.EnumValue(
                randomId(),
                prefix,
                Markers.EMPTY,
                annotations == null ? emptyList() : annotations,
                mapIdentifier(node.getChildNodeRequired("name")),
                null);
    }

    public Expression mapExpressionStatement(TSCNode node) {
        return (Expression) mapNode(node.getChildNodeRequired("expression"));
    }

    // FIXME
    private J.MethodDeclaration mapFunctionDeclaration(TSCNode node) {
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

    private Statement mapFunctionParameter(TSCNode node) {
        Space prefix = whitespace();
        implementMe(node, "modifiers");

        Space variablePrefix = whitespace();
        J.Identifier name = mapIdentifier(node.getChildNodeRequired("name"));

        implementMe(node, "questionToken");

        Space afterName = EMPTY;
        TypeTree typeTree = null;
        if (node.hasProperty("type")) {
            // FIXME: method(x: { suit: string; card: number }[])
            afterName = sourceBefore(TSCSyntaxKind.ColonToken);
            TSCNode type = node.getChildNode("type");
            assert type != null;
            typeTree = (TypeTree) mapNode(type);
        }
        List<JRightPadded<J.VariableDeclarations.NamedVariable>> variables = new ArrayList<>(1);
        variables.add(padRight(new J.VariableDeclarations.NamedVariable(
                randomId(),
                variablePrefix,
                Markers.EMPTY,
                name,
                emptyList(),
                null,
                typeMapping.variableType(node)
        ), afterName));

        implementMe(node, "initializer");

        Space varargs = null;
        List<JLeftPadded<Space>> dimensionsBeforeName = emptyList();

        return new J.VariableDeclarations(
                randomId(),
                prefix,
                Markers.EMPTY,
                emptyList(), // TODO:
                emptyList(), // TODO:
                typeTree,
                varargs,
                dimensionsBeforeName,
                variables
        );
    }

    private J.Identifier mapIdentifier(TSCNode node) {
        Space prefix = sourceBefore(node.getText());
        // TODO: check on escapedText property.
        return new J.Identifier(
                randomId(),
                prefix,
                Markers.EMPTY,
                node.getText(),
                typeMapping.type(node),
                null // FIXME
        );
    }

    private J.Identifier mapKeyword(TSCNode node) {
        return mapIdentifier(node);
    }

    private J mapNumericLiteral(TSCNode node) {
        return new J.Literal(
                randomId(),
                sourceBefore(TSCSyntaxKind.NumericLiteral),
                Markers.EMPTY,
                node.getStringPropertyValue("text"),
                node.getText(),
                null, // TODO
                typeMapping.primitive(node)
        );
    }

    private J.Literal mapStringLiteral(TSCNode node) {
        // singleQuote
        // hasExtendedUnicodeEscape
        return new J.Literal(
                randomId(),
                sourceBefore(TSCSyntaxKind.StringLiteral),
                Markers.EMPTY,
                node.getStringPropertyValue("text"),
                node.getText(),
                null, // TODO
                JavaType.Primitive.String
        );
    }

    private JS.JSVariableDeclaration mapVariableStatement(TSCNode node) {
        Space prefix = whitespace();

        List<J.Annotation> annotations = emptyList();
        List<J.Modifier> modifiers = emptyList();
        implementMe(node, "modifiers");

        JS.JSVariableDeclaration.VariableModifier modifier = null;
        TSCSyntaxKind keyword = scan();
        if (keyword == TSCSyntaxKind.ConstKeyword) {
            modifier = JS.JSVariableDeclaration.VariableModifier.CONST;
        } else if (keyword == TSCSyntaxKind.LetKeyword) {
            modifier = JS.JSVariableDeclaration.VariableModifier.LET;
        } else if (keyword == TSCSyntaxKind.VarKeyword) {
            modifier = JS.JSVariableDeclaration.VariableModifier.VAR;
        } else {
            // Unclear if the modifier should be `@Nullable` in the `JSVariableDeclaration`.
            implementMe(node);
        }

        List<JRightPadded<J.VariableDeclarations.NamedVariable>> namedVariables = emptyList();
        TypeTree typeTree = null;
        if (node.hasProperty("declarationList")) {
            TSCNode declarationList = node.getChildNode("declarationList");
            assert declarationList != null;

            List<TSCNode> declarations = declarationList.getChildNodes("declarations");
            namedVariables = new ArrayList<>(declarations.size());
            for (int i = 0; i < declarations.size(); i++) {
                TSCNode declaration = declarations.get(i);

                Space variablePrefix = whitespace();
                J.Identifier name = mapIdentifier(declaration.getChildNodeRequired("name"));
                Space afterName = i < declarations.size() - 1 ? sourceBefore(TSCSyntaxKind.CommaToken) : EMPTY;
                if (declaration.hasProperty("type")) {
                    // FIXME: method(x: { suit: string; card: number }[])
                    afterName = sourceBefore(TSCSyntaxKind.ColonToken);
                    TSCNode type = declaration.getChildNode("type");
                    assert type != null;
                    typeTree = (TypeTree) mapNode(type);
                }
                J.VariableDeclarations.NamedVariable variable = new J.VariableDeclarations.NamedVariable(
                        randomId(),
                        variablePrefix,
                        Markers.EMPTY,
                        name,
                        emptyList(),
                        declaration.hasProperty("initializer") ?
                                padLeft(sourceBefore(TSCSyntaxKind.EqualsToken),
                                        (Expression) Objects.requireNonNull(mapNode(declaration.getChildNodeRequired("initializer")))) : null,
                        typeMapping.variableType(node)
                );

                namedVariables.add(padRight(variable, afterName));
            }
        }

        return new JS.JSVariableDeclaration(
                randomId(),
                prefix,
                Markers.EMPTY,
                modifier,
                new J.VariableDeclarations(
                        randomId(),
                        prefix,
                        Markers.EMPTY,
                        annotations,
                        modifiers,
                        typeTree,
                        null,
                        emptyList(),
                        namedVariables
                )
        );
    }

    private JRightPadded<Statement> mapStatement(TSCNode node) {
        // FIXME
        Statement statement = (Statement) mapNode(node);

        assert statement != null;
        return padRight(statement, EMPTY);
    }

    private List<J.Modifier> mapModifiers(List<TSCNode> nodes) {
        List<J.Modifier> modifiers = new ArrayList<>(nodes.size());
        for (TSCNode node : nodes) {
            List<J.Annotation> annotations = emptyList(); // FIXME: maybe add annotations.
            switch (node.syntaxKind()) {
                case AbstractKeyword:
                    Space prefix = whitespace();
                    consumeToken(TSCSyntaxKind.AbstractKeyword);
                    modifiers.add(new J.Modifier(randomId(), prefix, Markers.EMPTY, J.Modifier.Type.Abstract, annotations));
                    break;
                default:
                    implementMe(node);
            }
        }

        return modifiers;
    }

    private void implementMe(TSCNode node) {
        throw new UnsupportedOperationException(String.format("Implement syntax kind: %s.", node.syntaxKind()));
    }

    private void implementMe(TSCNode node, String propertyName) {
        if (node.hasProperty(propertyName)) {
            throw new UnsupportedOperationException(String.format("Implement syntax kind: %s property %s", node.syntaxKind(), propertyName));
        }
    }

    @Nullable
    private J mapNode(TSCNode node) {
        J j;
        switch (node.syntaxKind()) {
            case EnumDeclaration:
            case ClassDeclaration:
            case InterfaceDeclaration:
                j = mapClassDeclaration(node);
                break;
            case NumberKeyword:
            case StringKeyword:
                j = mapKeyword(node);
                break;
            case BinaryExpression:
                j = mapBinaryExpression(node);
                break;
            case EmptyStatement:
                j = mapEmptyStatement(node);
                break;
            case EnumMember:
                j = mapEnumMember(node);
                break;
            case ExpressionStatement:
                j = mapExpressionStatement(node);
                break;
            case FunctionDeclaration:
                j = mapFunctionDeclaration(node);
                break;
            case Identifier:
                j = mapIdentifier(node);
                break;
            case NumericLiteral:
                j = mapNumericLiteral(node);
                break;
            case StringLiteral:
                j = mapStringLiteral(node);
                break;
            case VariableStatement:
                j = mapVariableStatement(node);
                break;
            default:
                implementMe(node); // TODO: remove ... temp for velocity.
                System.err.println("unsupported syntax kind: " + node.syntaxKind());
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
     * Increment the cursor position past the text.
     */
    private void skip(String text) {
        this.cursorContext.resetScanner(getCursorPosition() + text.length());
    }

    private <T> JLeftPadded<T> padLeft(Space left, T tree) {
        return new JLeftPadded<>(left, tree, Markers.EMPTY);
    }

    private <T> JRightPadded<T> padRight(T tree, @Nullable Space right) {
        return new JRightPadded<>(tree, right == null ? EMPTY : right, Markers.EMPTY);
    }

    private <K2 extends J> JRightPadded<K2> maybeSemicolon(K2 k) {
        int saveCursor = getCursorPosition();
        Space beforeSemi = whitespace();
        Semicolon semicolon = null;
        if (getCursorPosition() < source.getText().length() && source.getText().charAt(getCursorPosition()) == ';') {
            semicolon = new Semicolon(randomId());
            consumeToken(TSCSyntaxKind.SemicolonToken);
        } else {
            beforeSemi = EMPTY;
            cursor(saveCursor);
        }

        JRightPadded<K2> padded = JRightPadded.build(k).withAfter(beforeSemi);
        if (semicolon != null) {
            padded = padded.withMarkers(padded.getMarkers().add(semicolon));
        }

        return padded;
    }

    private TSCSyntaxKind scan() {
//        System.err.println("[scanner] scanning at pos=" + this.getCursorPosition());
        TSCSyntaxKind kind = this.cursorContext.nextScannerSyntaxType();
//        System.err.println("[scanner]     scan returned kind=" + kind + "; start=" + cursorContext.scannerTokenStart() + "; end=" + cursorContext.scannerTokenEnd() + ");");
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

    private String tokenStreamDebug() {
        return String.format("[start=%d, end=%d, text=`%s`]", this.cursorContext.scannerTokenStart(), this.cursorContext.scannerTokenEnd(), this.cursorContext.scannerTokenText().replace("\n", "⏎"));
    }

    private <T> JContainer<T> mapContainer(TSCSyntaxKind open, List<TSCNode> nodes, @Nullable TSCSyntaxKind delimiter, TSCSyntaxKind close, Function<TSCNode, T> mapFn) {
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
            for (int i = 0; i < nodes.size(); i++) {
                TSCNode node = nodes.get(i);
                T mapped = mapFn.apply(node);
                Space after = whitespace();
                rightPaddeds.add(JRightPadded.build(mapped).withAfter(after));
                // FIXME: check on trailing commas. Trailing comma property may not be available here. pass in bool val?
                if (i < nodes.size() - 1 && delimiter != null) {
                    consumeToken(delimiter);
                }
            }
        }
        consumeToken(close);
        return JContainer.build(containerPrefix, rightPaddeds, Markers.EMPTY);
    }

    private Space sourceBefore(String text) {
        Space prefix = whitespace();
        skip(text);
        return prefix;
    }

    private Space sourceBefore(TSCSyntaxKind syntaxKind) {
        Space prefix = whitespace();
        consumeToken(syntaxKind);
        return prefix;
    }

    /**
     * Consume whitespace and leading comments until the current node.
     * The type-script spec is not actively maintained, so we need to rely on the parser elements to collect
     * whitespace and comments.
     */
    private Space whitespace() {
//        System.err.println("[scanner] consuming space, starting at pos=" + getCursorPosition());
        String initialSpace = "";
        List<Comment> comments = Collections.emptyList();
        TSCSyntaxKind kind;
        boolean done = false;
        do {
            kind = scan();
            switch (kind) {
                case WhitespaceTrivia:
                case NewLineTrivia:
//                    System.err.println("[scanner]     appending whitespace");
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
//                    System.err.println("[scanner]     appending comment");
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
//                    System.err.println("[scanner]     resetting to pos=" + cursorContext.scannerTokenStart());
                    cursor(cursorContext.scannerTokenStart());
                    done = true;
                    break;
            }
        } while (!done);
        return Space.build(initialSpace, comments);
    }
}