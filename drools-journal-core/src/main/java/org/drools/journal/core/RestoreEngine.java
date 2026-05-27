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

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // Phase 0: a page is retired only when its compaction commit is sealed by a safepoint.
        // An unsealed commit (crash between COMMIT and the next safepoint) leaves the original pages canonical.
        Set<String> retiredPageIds = new HashSet<>();
        Set<String> sealedMergeIds = new HashSet<>();
        Map<String, String[]> unsealedCommits = new HashMap<>();

        try (JournalScanner phase0 = journal.scan(0)) {
            while (phase0.hasNext()) {
                JournalRecord record = phase0.next();
                if (record instanceof CompactionCommitRecord commit) {
                    unsealedCommits.put(commit.mergedPageId(), commit.replacedPageIds());
                } else if (record instanceof SafepointRecord) {
                    unsealedCommits.forEach((mergedId, replacedIds) -> {
                        sealedMergeIds.add(mergedId);
                        for (String replacedId : replacedIds) {
                            retiredPageIds.add(replacedId);
                        }
                    });
                    unsealedCommits.clear();
                }
            }
        }

        // Phase 1: replay in order, skipping retired pages.
        // Entering the content of a sealed merge resets the retired flag so merged records are applied.
        Map<Long, Object> survivingFacts = new HashMap<>();
        List<RuleMatchRecord> firedMatches = new ArrayList<>();
        List<PendingTmsLink> pendingTmsLinks = new ArrayList<>();
        Map<Long, RuleMatchRecord> firedMatchesById = new HashMap<>();
        List<JournalRecord> pending = new ArrayList<>();

        boolean inRetiredPage = false;

        try (JournalScanner scanner = journal.scan(0)) {
            while (scanner.hasNext()) {
                JournalRecord record = scanner.next();

                if (record instanceof SafepointRecord sp) {
                    inRetiredPage = retiredPageIds.contains(String.valueOf(sp.sequenceNo()));
                    if (!inRetiredPage) {
                        flush(pending, survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
                    }
                    pending.clear();
                } else if (record instanceof CompactionPrepareRecord prepare) {
                    if (sealedMergeIds.contains(prepare.preparingPageId())) {
                        inRetiredPage = false;
                    }
                } else if (record instanceof CompactionCommitRecord) {
                    // marker only
                } else if (!inRetiredPage) {
                    pending.add(record);
                }
            }
        }
        // Trailing pending records after the last safepoint are silently discarded

        return new ScanResult(survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
    }

    private void flush(final List<JournalRecord> pending,
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
            } else if (record instanceof ModifyRecord modify) {
                if (lambdaRegistry.lookup(modify.lambdaClassRef()) == null) {
                    throw new JournalSchemaEvolutionException(modify.lambdaClassRef());
                }
            }
        }
    }
}
