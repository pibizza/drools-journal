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
import org.kie.api.runtime.rule.AgendaFilter;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.runtime.rule.Match;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// NOT thread-safe — installed on a single-threaded session
public class ReplayFilter implements AgendaFilter {

    private final Set<MatchKey> firedMatches;

    public ReplayFilter(final Collection<RuleMatchRecord> records) {
        this.firedMatches = new HashSet<>(records.size() * 2);
        for (final RuleMatchRecord r : records) {
            firedMatches.add(new MatchKey(r.packageName(), r.ruleName(), r.factHandleIds()));
        }
    }

    @Override
    public boolean accept(final Match match) {
        final List<? extends FactHandle> handles = match.getFactHandles();
        final long[] ids = new long[handles.size()];
        for (int i = 0; i < handles.size(); i++) {
            ids[i] = handles.get(i).getId();
        }
        return !firedMatches.contains(new MatchKey(match.getRule().getPackageName(), match.getRule().getName(), ids));
    }

    private record MatchKey(String packageName, String ruleName, long[] factHandleIds) {

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MatchKey other)) return false;
            return packageName.equals(other.packageName)
                    && ruleName.equals(other.ruleName)
                    && Arrays.equals(factHandleIds, other.factHandleIds);
        }

        @Override
        public int hashCode() {
            int result = packageName.hashCode();
            result = 31 * result + ruleName.hashCode();
            result = 31 * result + Arrays.hashCode(factHandleIds);
            return result;
        }
    }
}
