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
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final ObjectStorageStrategy strategy;

    RestoreEngine(final JournalStorage journal, final ModifyLambdaRegistry lambdaRegistry) {
        this(journal, lambdaRegistry, new EmbedStrategy());
    }

    RestoreEngine(final JournalStorage journal, final ModifyLambdaRegistry lambdaRegistry,
                  final ObjectStorageStrategy strategy) {
        this.journal = journal;
        this.lambdaRegistry = lambdaRegistry;
        this.strategy = strategy;
    }

    ScanResult scan() {
        final Set<String> livePageIds;
        try (JournalScanner phase0 = journal.scan(0)) {
            livePageIds = PageIndex.buildLivePageSet(phase0);
        }

        // Phase 1: replay raw stream, flushing live pages, discarding retired ones.
        // Flushes occur at physical page boundaries (size-triggered rolls) and at
        // SafepointRecords (safepoint-triggered rolls).
        final Map<Long, Object> survivingFacts = new HashMap<>();
        final List<RuleMatchRecord> firedMatches = new ArrayList<>();
        final List<PendingTmsLink> pendingTmsLinks = new ArrayList<>();
        final Map<Long, RuleMatchRecord> firedMatchesById = new HashMap<>();
        final List<JournalRecord> pending = new ArrayList<>();
        String lastPhase1PageId = null;

        try (JournalScanner scanner = journal.scan(0)) {
            while (scanner.hasNext()) {
                final JournalRecord record = scanner.next();
                final String pageId = scanner.currentPageId();

                // Physical page boundary (size-triggered roll — no SafepointRecord at edge)
                if (!pageId.equals(lastPhase1PageId) && lastPhase1PageId != null) {
                    if (livePageIds.contains(lastPhase1PageId)) {
                        flush(pending, survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
                    }
                    pending.clear();
                }
                lastPhase1PageId = pageId;

                if (record instanceof SafepointRecord) {
                    // Safepoint seals the physical page — flush if live
                    if (livePageIds.contains(pageId)) {
                        flush(pending, survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
                    }
                    pending.clear();
                } else if (record instanceof CompactionPrepareRecord
                        || record instanceof CompactionCommitRecord) {
                    // compaction markers — no action
                } else {
                    pending.add(record);
                }
            }
        }
        // Trailing records after the last safepoint are silently discarded.

        return new ScanResult(survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
    }

    private void flush(final List<JournalRecord> pending,
                       final Map<Long, Object> survivingFacts,
                       final List<RuleMatchRecord> firedMatches,
                       final List<PendingTmsLink> pendingTmsLinks,
                       final Map<Long, RuleMatchRecord> firedMatchesById) {
        for (JournalRecord record : pending) {
            if (record instanceof InsertRecord insert) {
                survivingFacts.put(insert.factHandleId(), strategy.load(insert.payload()));
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
