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

import org.drools.journal.api.RuleMatchRecord;
import org.junit.jupiter.api.Test;
import org.kie.api.definition.KieDefinition;
import org.kie.api.definition.rule.Rule;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.runtime.rule.Match;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayFilterTest {

    @Test
    void accept_returns_false_for_match_in_fired_set() {
        final ReplayFilter filter = new ReplayFilter(List.of(
                ruleMatchRecord(1L, "org.example", "MyRule", 10L, 20L)));

        assertThat(filter.accept(match("org.example", "MyRule", 10L, 20L))).isFalse();
    }

    @Test
    void accept_returns_true_for_match_not_in_fired_set() {
        final ReplayFilter filter = new ReplayFilter(List.of(
                ruleMatchRecord(1L, "org.example", "MyRule", 10L, 20L)));

        assertThat(filter.accept(match("org.example", "MyRule", 10L, 99L))).isTrue();
    }

    @Test
    void accept_distinguishes_same_rule_name_in_different_packages() {
        final ReplayFilter filter = new ReplayFilter(List.of(
                ruleMatchRecord(1L, "org.example.a", "MyRule", 10L)));

        assertThat(filter.accept(match("org.example.a", "MyRule", 10L))).isFalse();
        assertThat(filter.accept(match("org.example.b", "MyRule", 10L))).isTrue();
    }

    @Test
    void accept_uses_array_content_equality_not_reference() {
        final ReplayFilter filter = new ReplayFilter(List.of(
                ruleMatchRecord(1L, "org.example", "MyRule", 10L, 20L)));

        // Different array instance, same content — must still suppress
        assertThat(filter.accept(match("org.example", "MyRule", 10L, 20L))).isFalse();
    }

    @Test
    void empty_fired_set_accepts_everything() {
        final ReplayFilter filter = new ReplayFilter(List.of());

        assertThat(filter.accept(match("org.example", "AnyRule", 1L))).isTrue();
    }

    // --- helpers ---

    private static RuleMatchRecord ruleMatchRecord(final long id, final String pkg, final String rule, final long... factHandleIds) {
        return new RuleMatchRecord(id, pkg, rule, factHandleIds);
    }

    private static Match match(final String pkg, final String rule, final long... ids) {
        final Rule ruleObj = new Rule() {
            @Override public String getPackageName() { return pkg; }
            @Override public String getName() { return rule; }
            @Override public Map<String, Object> getMetaData() { throw new UnsupportedOperationException(); }
            @Override public int getLoadOrder() { throw new UnsupportedOperationException(); }
            @Override public KieDefinition.KnowledgeType getKnowledgeType() { throw new UnsupportedOperationException(); }
            @Override public String getNamespace() { throw new UnsupportedOperationException(); }
            @Override public String getId() { throw new UnsupportedOperationException(); }
        };
        final List<FactHandle> handles = Arrays.stream(ids)
                .mapToObj(id -> (FactHandle) new FactHandle() {
                    @Override public long getId() { return id; }
                    @Override public Object getObject() { throw new UnsupportedOperationException(); }
                    @Override public long getRecency() { throw new UnsupportedOperationException(); }
                    @Override public boolean isNegated() { throw new UnsupportedOperationException(); }
                    @Override public boolean isEvent() { throw new UnsupportedOperationException(); }
                    @Override public <K> K as(Class<K> klass) { throw new UnsupportedOperationException(); }
                    @Override public boolean isValid() { throw new UnsupportedOperationException(); }
                    @Override public String toExternalForm() { throw new UnsupportedOperationException(); }
                })
                .toList();
        return new Match() {
            @Override public Rule getRule() { return ruleObj; }
            @Override public List<? extends FactHandle> getFactHandles() { return handles; }
            @Override public List<Object> getObjects() { throw new UnsupportedOperationException(); }
            @Override public List<String> getDeclarationIds() { throw new UnsupportedOperationException(); }
            @Override public Object getDeclarationValue(String declarationId) { throw new UnsupportedOperationException(); }
            @Override public int getSalience() { throw new UnsupportedOperationException(); }
        };
    }
}
