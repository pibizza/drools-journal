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
package org.drools.journal.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalRefStrategyTest {

    private final ExternalRefStrategy strategy = new ExternalRefStrategy(fact -> "key-" + fact);

    @Test
    void store_producesExternalRefWithCorrectTypeNameAndKey() {
        ExternalRef ref = (ExternalRef) strategy.store(42, null);

        assertThat(ref.typeName()).isEqualTo(Integer.class.getName());
        assertThat(ref.dbKey()).isEqualTo("key-42");
    }

    @Test
    void load_throwsUnsupportedOperation() {
        ExternalRef ref = new ExternalRef("com.example.Fact", "key-123");

        assertThatThrownBy(() -> strategy.load(ref))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
