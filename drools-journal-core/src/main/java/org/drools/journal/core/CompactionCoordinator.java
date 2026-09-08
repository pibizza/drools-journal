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
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CompactionCoordinator {

    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

    private final JournalStorage storage;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    CompactionCoordinator(final JournalStorage storage, final Duration interval) {
        this.storage = storage;
        this.interval = interval;
    }

    public static CompactionCoordinator onDemand(final JournalStorage storage) {
        return new CompactionCoordinator(storage, Duration.ZERO);
    }

    void start() {
        if (Duration.ZERO.equals(interval)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drools-journal-compactor");
            t.setDaemon(true);
            return t;
        });
        long millis = interval.toMillis();
        scheduler.scheduleWithFixedDelay(this::runCycle, millis, millis, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    void runCycle() {
        PageIndex.PageIndexStatus pageStatus = PageIndex.buildLivePageSet(storage);

        if (!pageStatus.retiredPages().isEmpty()) {
            storage.retirePages(pageStatus.retiredPages().toArray(new String[0]));
        }

        Map<String, long[]> liveness = scanLiveness();
        Set<String> candidates = new HashSet<>();
        liveness.forEach((id, counts) -> {
            if (isSparse(counts)) {
                candidates.add(id);
            }
        });
        if (!candidates.isEmpty()) {
            compact(candidates);
        }
    }

    Map<String, long[]> scanLiveness() {
        final Map<String, long[]> liveness = new HashMap<>();
        final Map<Long, String> factToPage = new HashMap<>();
        final List<JournalRecord> pending = new ArrayList<>();
        String lastPageId = null;

        try (JournalScanner scanner = storage.scan(0)) {
            while (scanner.hasNext()) {
                final JournalRecord record = scanner.next();
                final String pageId = scanner.currentPageId();

                // Physical page boundary (size-triggered roll, no SafepointRecord)
                if (!pageId.equals(lastPageId) && lastPageId != null) {
                	flushPageLiveness(lastPageId, pending, liveness, factToPage);
                    pending.clear();
                }
                lastPageId = pageId;

                if (record instanceof SafepointRecord) {
                	flushPageLiveness(pageId, pending, liveness, factToPage);
                    pending.clear();
                } else {
                    pending.add(record);
                }
            }
            // Trailing open page (no safepoint yet) — not eligible for compaction; discard
        }

        return liveness;
    }

    private static void flushPageLiveness(final String pageId,
                                          final List<JournalRecord> pending,
                                          final Map<String, long[]> liveness,
                                          final Map<Long, String> factToPage) {
        if (pending.isEmpty()) {
            return;
        }
        liveness.computeIfAbsent(pageId, k -> new long[2]);
        for (final JournalRecord r : pending) {
            if (r instanceof InsertRecord insert) {
                liveness.get(pageId)[0]++;
                liveness.get(pageId)[1]++;
                String previousPage = factToPage.put(insert.factHandleId(), pageId);
                if (previousPage != null) {
                    liveness.get(previousPage)[0]--;
                }
            } else if (r instanceof RuleMatchRecord || r instanceof ModifyRecord) {
                liveness.get(pageId)[1]++;
            } else if (r instanceof RetractRecord retract) {
                liveness.get(pageId)[1]++;
                final String origin = factToPage.remove(retract.factHandleId());
                if (origin != null) {
                    liveness.get(origin)[0]--;
                }
            }
        }
    }

    static boolean isSparse(final long[] counts) {
        return (double) counts[0] / counts[1] < 0.30;
    }

    public void compact(final Set<String> pageIds) {
        if (pageIds.isEmpty()) {
            return;
        }
        final String mergedPageId = "m-" + UUID.randomUUID();
        final String[] replacedPageIds = pageIds.toArray(new String[0]);

        // Phase 1 — PREPARE
        storage.compactionPrepare(mergedPageId, replacedPageIds);

        // Phase 2 — WRITE: collect live InsertRecords from source pages.
        // A fact is live if it was inserted in a source page and never retracted.
        final Set<Long> retractedIds = new HashSet<>();
        final Map<Long, InsertRecord> liveInserts = new LinkedHashMap<>();

        try (JournalScanner scanner = storage.scan(0)) {
            String lastPageId = null;
            final List<InsertRecord> pageBuffer = new ArrayList<>();
            while (scanner.hasNext()) {
                final JournalRecord record = scanner.next();
                final String pageId = scanner.currentPageId();

                // Physical page boundary (size-triggered roll)
                if (!pageId.equals(lastPageId) && lastPageId != null) {
                    if (pageIds.contains(lastPageId)) {
                        for (final InsertRecord insert : pageBuffer) {
                            liveInserts.put(insert.factHandleId(), insert);
                        }
                    }
                    pageBuffer.clear();
                }
                lastPageId = pageId;

                if (record instanceof SafepointRecord) {
                    if (pageIds.contains(pageId)) {
                        for (final InsertRecord insert : pageBuffer) {
                            liveInserts.put(insert.factHandleId(), insert);
                        }
                    }
                    pageBuffer.clear();
                } else if (record instanceof InsertRecord insert) {
                    pageBuffer.add(insert);
                } else if (record instanceof RetractRecord retract) {
                    retractedIds.add(retract.factHandleId());
                }
            }
            // Trailing open page — no source-page inserts; discard.
        }
        liveInserts.keySet().removeAll(retractedIds);

        List<JournalRecord> mergedRecords = new ArrayList<>(liveInserts.values());
        mergedRecords.add(new SafepointRecord(-1, 0L));
        storage.writeMergedPage(mergedPageId, mergedRecords);

        // Phase 3 — COMMIT
        storage.compactionCommit(mergedPageId, replacedPageIds);
    }
}
