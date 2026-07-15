/*
 * Copyright (c) 2026 Drools Journal Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.journal.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.drools.drl.ast.descr.BaseDescr;
import org.drools.drl.ast.descr.PackageDescr;
import org.drools.drl.ast.descr.PatternDescr;
import org.drools.drl.ast.descr.RuleDescr;
import org.drools.drl.parser.DrlParser;
import org.drools.drl.parser.DroolsParserException;
import org.drools.mvel.parser.MvelParser;
import org.drools.mvel.parser.ast.expr.ModifyStatement;
import org.drools.mvel.parser.printer.PrintUtil;
import org.drools.util.ClassTypeResolver;
import org.drools.util.TypeResolver;

public final class JournalDrlPrecompiler {

    private static final String GLOBAL_DECL =
            "global org.drools.journal.core.JournallingRuntimeEventListener journal;";

    private JournalDrlPrecompiler() {}

    public static String rewrite(final String drl,
                                 final ModifyLambdaRegistry registry,
                                 final ClassLoader classLoader) {
        PackageDescr pkg = parseDrl(drl);
        StringBuilder result = new StringBuilder(drl);
        boolean modified = false;
        int searchFrom = 0;

        for (RuleDescr rule : pkg.getRules()) {
            String consequence = (String) rule.getConsequence();
            if (consequence == null || !consequence.contains("modify")) {
                continue;
            }

            String rewritten = rewriteConsequence(consequence, rule, pkg, registry, classLoader);
            if (!rewritten.equals(consequence)) {
                int pos = result.indexOf(consequence, searchFrom);
                if (pos >= 0) {
                    result.replace(pos, pos + consequence.length(), rewritten);
                    searchFrom = pos + rewritten.length();
                    modified = true;
                }
            }
        }

        if (modified) {
            return injectGlobalDeclaration(result.toString(), pkg);
        }

        return result.toString();
    }

    private static String rewriteConsequence(final String consequence,
                                             final RuleDescr rule,
                                             final PackageDescr pkg,
                                             final ModifyLambdaRegistry registry,
                                             final ClassLoader classLoader) {
        BlockStmt block = MvelParser.parseBlock("{" + consequence + "}");
        List<ModifyStatement> modifyStmts = block.findAll(ModifyStatement.class);
        if (modifyStmts.isEmpty()) {
            return consequence;
        }

        NodeList<Statement> stmts = block.getStatements();
        int modifyIndex = 0;

        for (int i = 0; i < stmts.size(); i++) {
            if (!(stmts.get(i) instanceof ModifyStatement modifyStmt)) {
                continue;
            }
            String targetVar = PrintUtil.printNode(modifyStmt.getModifyObject());
            List<SetterCall> setterCalls = extractSetterCalls(modifyStmt);
            if (setterCalls.isEmpty()) {
                continue;
            }

            String lambdaClassRef = buildLambdaClassRef(rule.getName(), modifyIndex);
            registerLambda(lambdaClassRef, targetVar, setterCalls, rule, pkg, registry, classLoader);

            String stageCallText = buildStageModifyCall(lambdaClassRef, setterCalls);
            Statement stageStmt = MvelParser.parseBlock("{" + stageCallText + "}").getStatement(0);
            stmts.add(i, stageStmt);
            i++;

            modifyIndex++;
        }

        return unwrapBlock(PrintUtil.printNode(block));
    }

    private static String unwrapBlock(final String blockText) {
        String trimmed = blockText.strip();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed.substring(1, trimmed.length() - 1).strip() + "\n";
        }
        return trimmed;
    }

    private static List<SetterCall> extractSetterCalls(final ModifyStatement modifyStmt) {
        List<SetterCall> calls = new ArrayList<>();
        for (Statement stmt : modifyStmt.getExpressions()) {
            if (stmt instanceof ExpressionStmt exprStmt
                    && exprStmt.getExpression() instanceof MethodCallExpr methodCall) {
                String setterName = methodCall.getNameAsString();
                String argText = methodCall.getArguments().isEmpty()
                        ? ""
                        : PrintUtil.printNode(methodCall.getArgument(0));
                calls.add(new SetterCall(setterName, argText));
            }
        }
        return calls;
    }

    private static void registerLambda(final String lambdaClassRef,
                                       final String targetVar,
                                       final List<SetterCall> setterCalls,
                                       final RuleDescr rule,
                                       final PackageDescr pkg,
                                       final ModifyLambdaRegistry registry,
                                       final ClassLoader classLoader) {
        String typeName = resolveFactType(targetVar, rule);
        Class<?> factType = resolveClass(typeName, pkg, classLoader);
        String[] setterNames = setterCalls.stream()
                .map(SetterCall::name)
                .toArray(String[]::new);
        registry.register(lambdaClassRef, MethodHandleModifyLambda.forSetters(factType, setterNames));
    }

    private static String resolveFactType(final String varName, final RuleDescr rule) {
        for (BaseDescr descr : rule.getLhs().getDescrs()) {
            if (descr instanceof PatternDescr pattern
                    && varName.equals(pattern.getIdentifier())) {
                return pattern.getObjectType();
            }
        }
        throw new IllegalArgumentException(
                "No LHS pattern binds variable '" + varName + "' in rule '" + rule.getName() + "'");
    }

    private static Class<?> resolveClass(final String typeName,
                                         final PackageDescr pkg,
                                         final ClassLoader classLoader) {
        Set<String> imports = new HashSet<>();
        for (var imp : pkg.getImports()) {
            imports.add(imp.getTarget());
        }
        TypeResolver resolver = new ClassTypeResolver(imports, classLoader, pkg.getName());
        try {
            return resolver.resolveType(typeName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot resolve type: " + typeName, e);
        }
    }

    private static String buildLambdaClassRef(final String ruleName, final int index) {
        return "Rule_" + ruleName.replace(" ", "_") + "_modify_" + index;
    }

    private static String buildStageModifyCall(final String lambdaClassRef,
                                               final List<SetterCall> setterCalls) {
        StringBuilder sb = new StringBuilder();
        sb.append("journal.stageModify(\"").append(lambdaClassRef).append("\", new Object[]{ ");
        for (int i = 0; i < setterCalls.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(setterCalls.get(i).argText());
        }
        sb.append(" });");
        return sb.toString();
    }

    private static String injectGlobalDeclaration(final String drl, final PackageDescr pkg) {
        String packageLine = "package " + pkg.getName() + ";";
        int packageEnd = drl.indexOf(packageLine);
        if (packageEnd < 0) {
            return GLOBAL_DECL + "\n" + drl;
        }
        // Find the end of the import block (last import line), or just after the package line
        int insertPos = packageEnd + packageLine.length();
        int lastImportEnd = drl.lastIndexOf("import ");
        if (lastImportEnd >= 0) {
            insertPos = drl.indexOf(";", lastImportEnd) + 1;
        }
        return drl.substring(0, insertPos) + "\n" + GLOBAL_DECL + drl.substring(insertPos);
    }

    private static PackageDescr parseDrl(final String drl) {
        try {
            return new DrlParser().parse(null, drl);
        } catch (DroolsParserException e) {
            throw new IllegalArgumentException("Failed to parse DRL", e);
        }
    }

    private record SetterCall(String name, String argText) {}
}
