/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.drools.journal.benchmarks.providers;

import org.drools.journal.benchmarks.model.A;
import org.drools.journal.benchmarks.model.ConsequenceBlackhole;

public class RulesWithJoinsProvider {

    private int numberOfJoins = 0;
    private boolean withCep = false;
    private boolean withImports = true;
    private boolean withGeneratedConsequence = true;
    private boolean prioritizedBySalience = false;
    private String global = "";
    private String consequence = "";
    private String rootConstraintValueOperator = ">";
    private String joinConstraintValueOperator = ">";
    private boolean withNot = false;

    public RulesWithJoinsProvider() { }

    public RulesWithJoinsProvider(final int numberOfJoins, final boolean withCep, final boolean withImports) {
        this(numberOfJoins, withCep, withImports, "", "");
    }

    public RulesWithJoinsProvider(final int numberOfJoins, final boolean withCep, final boolean withImports,
                                  final String global, final String consequence) {
        this(numberOfJoins, withCep, withImports, false, global, consequence, ">", ">");
    }

    public RulesWithJoinsProvider(final int numberOfJoins, final boolean withCep, final boolean withImports,
                                  final boolean prioritizedBySalience, final String global, final String consequence,
                                  final String rootConstraintValueOperator, final String joinConstraintValueOperator) {
        if (numberOfJoins > 4) {
            throw new IllegalArgumentException(
                    "Unsupported number of joins! Maximal allowed number of joins is 4, actual is " + numberOfJoins);
        }
        this.numberOfJoins = numberOfJoins;
        this.withCep = withCep;
        this.withImports = withImports;
        this.prioritizedBySalience = prioritizedBySalience;
        this.global = global;
        this.consequence = consequence;
        this.rootConstraintValueOperator = rootConstraintValueOperator;
        this.joinConstraintValueOperator = joinConstraintValueOperator;
    }

    public RulesWithJoinsProvider withNumberOfJoins(final int numberOfJoins) {
        this.numberOfJoins = numberOfJoins;
        return this;
    }

    public RulesWithJoinsProvider withCep(final boolean withCep) {
        this.withCep = withCep;
        return this;
    }

    public RulesWithJoinsProvider withImports(final boolean withImports) {
        this.withImports = withImports;
        return this;
    }

    public RulesWithJoinsProvider withGeneratedConsequence(final boolean withGeneratedConsequence) {
        this.withGeneratedConsequence = withGeneratedConsequence;
        return this;
    }

    public RulesWithJoinsProvider withNot(final boolean withNot) {
        this.withNot = withNot;
        return this;
    }

    public RulesWithJoinsProvider withPrioritizedBySalience(final boolean prioritizedBySalience) {
        this.prioritizedBySalience = prioritizedBySalience;
        return this;
    }

    public RulesWithJoinsProvider withGlobal(final String global) {
        this.global = global;
        return this;
    }

    public RulesWithJoinsProvider withConsequence(final String consequence) {
        this.consequence = consequence;
        return this;
    }

    public String getDrl(final int numberOfRules) {
        return getDrl(numberOfRules, "R");
    }

    public String getDrl(final int numberOfRules, final String ruleNameBase) {
        if (withGeneratedConsequence) {
            this.consequence = generateConsequence();
        }

        final StringBuilder drlBuilder = new StringBuilder();

        if (withImports) {
            drlBuilder.append("import ").append(A.class.getPackage().getName()).append(".*;\n");
        }
        drlBuilder.append(global).append("\n");
        if (withCep) {
            appendCepHeader(drlBuilder);
        }
        for (int i = 0; i < numberOfRules; i++) {
            drlBuilder.append("rule \"").append(ruleNameBase).append(i).append("\"\n");
            if (prioritizedBySalience) {
                drlBuilder.append("salience ").append(i).append("\n");
            }
            drlBuilder.append("  when\n");
            appendJoins(drlBuilder, i);
            drlBuilder.append("  then\n");
            drlBuilder.append(consequence).append("\n");
            drlBuilder.append("end\n");
        }
        return drlBuilder.toString();
    }

    private void appendCepHeader(final StringBuilder drlBuilder) {
        final String[] domainClassNames = new String[]{"B", "C", "D", "E"};
        drlBuilder.append("declare A @role( event ) @timestamp( value ) end\n");
        for (int i = 0; i < numberOfJoins; i++) {
            drlBuilder.append("declare ").append(domainClassNames[i]).append(" @role( event ) @timestamp( value ) end\n");
        }
    }

    private void appendJoins(final StringBuilder drlBuilder, final int valueInConstraint) {
        drlBuilder.append("  $a : A( value ").append(rootConstraintValueOperator).append(" ").append(valueInConstraint).append(")\n");
        for (int i = 0; i < numberOfJoins; i++) {
            drlBuilder.append(withCep ? getJoinConstraintsCep(i) : getJoinConstraints(i));
        }
    }

    private String getJoinConstraintsCep(final int index) {
        return "  $" + (char) ('b' + index) + " : " + (char) ('B' + index) + "( this after $" + (char) ('a' + index) + " )\n";
    }

    private String getJoinConstraints(final int index) {
        String pattern = "  $" + (char) ('b' + index) + " : " + (char) ('B' + index)
                + "( value " + joinConstraintValueOperator + " $" + (char) ('a' + index) + ".value )\n";
        if (withNot) {
            String notPattern = "  not " + (char) ('B' + index) + "( value < $" + (char) ('a' + index) + ".value )\n";
            return notPattern + pattern;
        } else {
            return pattern;
        }
    }

    private String generateConsequence() {
        StringBuilder sb = new StringBuilder("    long result = $a.getId()");
        for (int i = 0; i < numberOfJoins; i++) {
            sb.append(" + $").append((char) ('b' + i)).append(".getId()");
        }
        sb.append(";\n");
        sb.append("    ").append(ConsequenceBlackhole.class.getCanonicalName()).append(".consume( result );");
        return sb.toString();
    }
}
