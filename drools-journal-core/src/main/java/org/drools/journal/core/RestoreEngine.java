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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        // Phase 0: build the live page index from the raw stream.
        // A commit is sealed — and its source pages retired — only when a
        // SafepointRecord follows the CompactionCommitRecord.
        // Each safepoint interval may span multiple physical pages (size-triggered
        // rolls); all physical pages in the interval are added when it closes.
        final List<String> pageIndex = new ArrayList<>();
        final Map<String, String[]> pendingCommits = new LinkedHashMap<>();
        final List<String> currentIntervalPages = new ArrayList<>();
        String lastPhase0PageId = null;

        try (JournalScanner phase0 = journal.scan(0)) {
            while (phase0.hasNext()) {
                final JournalRecord record = phase0.next();
                final String pageId = phase0.currentPageId();

                // Track every physical page encountered in the current safepoint interval
                if (!pageId.equals(lastPhase0PageId)) {
                    currentIntervalPages.add(pageId);
                    lastPhase0PageId = pageId;
                }

                if (record instanceof CompactionCommitRecord commit) {
                    pendingCommits.put(commit.mergedPageId(), commit.replacedPageIds());
                } else if (record instanceof SafepointRecord) {
                    for (final Map.Entry<String, String[]> e : pendingCommits.entrySet()) {
                        spliceIntoIndex(pageIndex, e.getKey(), e.getValue());
                    }
                    pendingCommits.clear();
                    pageIndex.addAll(currentIntervalPages);
                    currentIntervalPages.clear();
                }
            }
        }
        // Unsealed commits (no following safepoint) leave original pages canonical.

        final Set<String> livePageIds = new HashSet<>(pageIndex);

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

    private static void spliceIntoIndex(final List<String> pageIndex,
                                        final String mergedId,
                                        final String[] replacedIds) {
        final Set<String> retired = Set.of(replacedIds);
        int insertPos = -1;
        for (int i = 0; i < pageIndex.size(); i++) {
            if (retired.contains(pageIndex.get(i))) {
                insertPos = i;
                break;
            }
        }
        pageIndex.removeIf(retired::contains);
        if (insertPos >= 0) {
            pageIndex.add(insertPos, mergedId);
        }
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
