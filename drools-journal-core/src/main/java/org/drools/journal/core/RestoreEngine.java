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

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// NOT thread-safe — Drools sessions fire on a single thread
class RestoreEngine {

    record PendingTmsLink(long factHandleId, long justifyingRuleMatchId) {}

    record ScanResult(Map<Long, Object> survivingFacts,
                      List<RuleMatchRecord> firedMatches,
                      List<PendingTmsLink> pendingTmsLinks,
                      Map<Long, RuleMatchRecord> firedMatchesById) {}

    private final JournalStorage journal;
    private final ModifyLambdaRegistry lambdaRegistry;

    RestoreEngine(final JournalStorage journal, final ModifyLambdaRegistry lambdaRegistry) {
        this.journal = journal;
        this.lambdaRegistry = lambdaRegistry;
    }

    ScanResult scan() {
        Map<Long, Object> survivingFacts = new HashMap<>();
        List<RuleMatchRecord> firedMatches = new ArrayList<>();
        List<PendingTmsLink> pendingTmsLinks = new ArrayList<>();
        Map<Long, RuleMatchRecord> firedMatchesById = new HashMap<>();
        List<JournalRecord> pending = new ArrayList<>();

        JournalScanner scanner = journal.scan(0);
        while (scanner.hasNext()) {
            JournalRecord record = scanner.next();
            if (record instanceof SafepointRecord) {
                flush(pending, survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
                pending.clear();
            } else {
                pending.add(record);
            }
        }
        // Trailing pending records after the last safepoint are silently discarded

        return new ScanResult(survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
    }

    private static void flush(final List<JournalRecord> pending,
                              final Map<Long, Object> survivingFacts,
                              final List<RuleMatchRecord> firedMatches,
                              final List<PendingTmsLink> pendingTmsLinks,
                              final Map<Long, RuleMatchRecord> firedMatchesById) {
        for (JournalRecord record : pending) {
            if (record instanceof InsertRecord insert) {
                survivingFacts.put(insert.factHandleId(), JournalPayloadBuilder.deserialize(insert.payload()));
                if (insert.logical()) {
                    pendingTmsLinks.add(new PendingTmsLink(insert.factHandleId(), insert.justifyingRuleMatchId()));
                }
            } else if (record instanceof RetractRecord retract) {
                survivingFacts.remove(retract.factHandleId());
                pendingTmsLinks.removeIf(link -> link.factHandleId() == retract.factHandleId());
            } else if (record instanceof RuleMatchRecord match) {
                firedMatches.add(match);
                firedMatchesById.put(match.id(), match);
            }
        }
    }
}
