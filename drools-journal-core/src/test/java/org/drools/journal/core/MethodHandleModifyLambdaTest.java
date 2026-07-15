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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodHandleModifyLambdaTest {

    public static class Person {
        private int age;
        private String name;

        public Person(final int age, final String name) {
            this.age = age;
            this.name = name;
        }

        public int getAge() { return age; }
        public void setAge(final int age) { this.age = age; }
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
    }

    @Test
    void apply_singleSetter_modifiesFact() {
        MethodHandleModifyLambda lambda =
                MethodHandleModifyLambda.forSetters(Person.class, "setAge");
        Person person = new Person(20, "Alice");

        lambda.apply(person, new Object[]{ 30 });

        assertThat(person.getAge()).isEqualTo(30);
        assertThat(person.getName()).isEqualTo("Alice");
    }

    @Test
    void apply_multipleSetters_modifiesAllProperties() {
        MethodHandleModifyLambda lambda =
                MethodHandleModifyLambda.forSetters(Person.class, "setAge", "setName");
        Person person = new Person(20, "Alice");

        lambda.apply(person, new Object[]{ 30, "Bob" });

        assertThat(person.getAge()).isEqualTo(30);
        assertThat(person.getName()).isEqualTo("Bob");
    }

    @Test
    void forSetters_unknownSetter_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                MethodHandleModifyLambda.forSetters(Person.class, "setNoSuchField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setNoSuchField");
    }

    @Test
    void registryRoundTrip_lookupAndApply_modifiesFact() {
        MethodHandleModifyLambda lambda =
                MethodHandleModifyLambda.forSetters(Person.class, "setAge");
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        registry.register("Rule_Test_modify_0", lambda);

        ModifyLambda looked = registry.lookup("Rule_Test_modify_0");
        Person person = new Person(20, "Alice");
        looked.apply(person, new Object[]{ 99 });

        assertThat(person.getAge()).isEqualTo(99);
    }
}
