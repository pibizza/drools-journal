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

class ModifyLambdaRegistryTest {

    @Test
    void lookupReturnsRegisteredLambda() {
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        ModifyLambda lambda = (fact, params) -> {};

        registry.register("Rule_MyRule_modify_0", lambda);

        assertThat(registry.lookup("Rule_MyRule_modify_0")).isSameAs(lambda);
    }

    @Test
    void lookupThrowsForUnknownRef() {
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();

        assertThatThrownBy(() -> registry.lookup("Rule_Missing_modify_0"))
                .isInstanceOf(JournalSchemaEvolutionException.class)
                .hasMessageContaining("Rule_Missing_modify_0");
    }

    @Test
    void registerOverwritesPreviousLambda() {
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        ModifyLambda original = (fact, params) -> {};
        ModifyLambda replacement = (fact, params) -> {};

        registry.register("Rule_MyRule_modify_0", original);
        registry.register("Rule_MyRule_modify_0", replacement);

        assertThat(registry.lookup("Rule_MyRule_modify_0")).isSameAs(replacement);
    }

    @Test
    void multipleLambdasLookedUpIndependently() {
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        ModifyLambda first = (fact, params) -> {};
        ModifyLambda second = (fact, params) -> {};

        registry.register("Rule_A_modify_0", first);
        registry.register("Rule_B_modify_0", second);

        assertThat(registry.lookup("Rule_A_modify_0")).isSameAs(first);
        assertThat(registry.lookup("Rule_B_modify_0")).isSameAs(second);
    }
}
