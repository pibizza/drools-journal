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

import java.io.Serializable;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalDrlPrecompilerTest {

    public static class Person implements Serializable {
        private int age;
        private String name;

        public Person() {}
        public Person(final int age, final String name) { this.age = age; this.name = name; }
        public int getAge() { return age; }
        public void setAge(final int age) { this.age = age; }
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
    }

    @Test
    void rewrite_singleModifyBlock_injectsStageModifyCall() {
        String drl = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

                rule "UpdateAge"
                when
                    $p : Person(age < 30)
                then
                    modify($p) {
                        setAge(30)
                    }
                end
                """;

        String expected = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;
                global org.drools.journal.core.JournallingRuntimeEventListener journal;

                rule "UpdateAge"
                when
                    $p : Person(age < 30)
                then
                    journal.stageModify("Rule_UpdateAge_modify_0", new Object[] { 30 });
                    modify ($p) { setAge(30) };
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        assertThat(rewritten).isEqualTo(expected);
        assertThat(registry.lookup("Rule_UpdateAge_modify_0")).isNotNull();
    }

    @Test
    void rewrite_multipleSetters_capturesAllArgs() {
        String drl = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

                rule "UpdateBoth"
                when
                    $p : Person()
                then
                    modify($p) {
                        setAge(30),
                        setName("Bob")
                    }
                end
                """;

        String expected = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;
                global org.drools.journal.core.JournallingRuntimeEventListener journal;

                rule "UpdateBoth"
                when
                    $p : Person()
                then
                    journal.stageModify("Rule_UpdateBoth_modify_0", new Object[] { 30, "Bob" });
                    modify ($p) { setAge(30), setName("Bob") };
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        assertThat(rewritten).isEqualTo(expected);

        Person person = new Person(20, "Alice");
        registry.lookup("Rule_UpdateBoth_modify_0").apply(person, new Object[]{ 30, "Bob" });
        assertThat(person.getAge()).isEqualTo(30);
        assertThat(person.getName()).isEqualTo("Bob");
    }

    @Test
    void rewrite_noModifyBlock_returnsUnchanged() {
        String drl = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

                rule "SimplePrint"
                when
                    $p : Person()
                then
                    System.out.println($p);
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        assertThat(rewritten).isEqualTo(drl);
    }

    @Test
    void rewrite_variableArgs_preservesExpressionText() {
        String drl = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

                rule "UpdateWithVar"
                when
                    $p : Person()
                    $newAge : Integer()
                then
                    modify($p) {
                        setAge($newAge)
                    }
                end
                """;

        String expected = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;
                global org.drools.journal.core.JournallingRuntimeEventListener journal;

                rule "UpdateWithVar"
                when
                    $p : Person()
                    $newAge : Integer()
                then
                    journal.stageModify("Rule_UpdateWithVar_modify_0", new Object[] { $newAge });
                    modify ($p) { setAge($newAge) };
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        assertThat(rewritten).isEqualTo(expected);
    }

    @Test
    void rewrite_multipleRules_injectsGlobalOnceAndRegistersAll() {
        String drl = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

                rule "Rule1"
                when
                    $p : Person()
                then
                    modify($p) {
                        setAge(1)
                    }
                end

                rule "Rule2"
                when
                    $p : Person()
                then
                    modify($p) {
                        setName("two")
                    }
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        int globalCount = rewritten.split(
                "global org.drools.journal.core.JournallingRuntimeEventListener journal;", -1).length - 1;
        assertThat(globalCount).isEqualTo(1);

        assertThat(rewritten).contains("Rule_Rule1_modify_0");
        assertThat(rewritten).contains("Rule_Rule2_modify_0");

        assertThat(registry.lookup("Rule_Rule1_modify_0")).isNotNull();
        assertThat(registry.lookup("Rule_Rule2_modify_0")).isNotNull();
    }
}
