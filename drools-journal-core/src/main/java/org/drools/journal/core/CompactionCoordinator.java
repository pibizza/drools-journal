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

import org.drools.journal.api.DurabilityMode;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.IndexStatus;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private final Duration mergingInterval;
    private final Duration retirementInterval;
    private final DurabilityMode mode;
    private ScheduledExecutorService mergingExecutor;
    private ScheduledExecutorService retirementExecutor;

    CompactionCoordinator(final JournalStorage storage, final Duration mergingInterval) {
        this(storage, mergingInterval, DurabilityMode.JOURNAL);
    }

    CompactionCoordinator(final JournalStorage storage, final Duration mergingInterval,
                          final DurabilityMode mode) {
        this.storage = storage;
        this.mergingInterval = mergingInterval;
        this.retirementInterval = Duration.ZERO;
        this.mode = mode;
    }

    public static CompactionCoordinator onDemand(final JournalStorage storage) {
        return new CompactionCoordinator(storage, Duration.ZERO);
    }

    DurabilityMode mode() {
        return mode;
    }

    void start() {
    	mergingExecutor = startThread(mergingInterval, "drools-journal-merger", this::runMergingCycle);
        
    	retirementExecutor = startThread(retirementInterval, "drools-journal-retirement", this::runRetirementCycle);
    }

	private ScheduledExecutorService startThread(Duration interval, String threadName, Runnable command) {
        if (Duration.ZERO.equals(interval)) {
        	return null;
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
		    Thread t = new Thread(r, threadName);
		    t.setDaemon(true);
		    return t;
		});
		long millis = interval.toMillis();
		executor.scheduleWithFixedDelay(command, millis, millis, TimeUnit.MILLISECONDS);
		return executor;
	}
	

    void stop() {
    	stopThread(mergingExecutor);

    	stopThread(retirementExecutor);
    
    }

	private void stopThread(ScheduledExecutorService executor) {
		if (executor == null) {
			return;
		}
 		executor.shutdown();
		try {
		    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
		    	executor.shutdownNow();
		    }
		} catch (InterruptedException e) {
			executor.shutdownNow();
		    Thread.currentThread().interrupt();
		}
	}


    void runMergingCycle() {
        final Map<String, long[]> liveness = scanLiveness();
        // Merge only contiguous runs of sparse pages. Pages separated by a still-live page
        // must never be merged together: the merged page occupies a single slot in the
        // journal, so collapsing records that straddle a live gap would reorder them on
        // restore (fatal for a fact's modify-delta chain). Walk pages in journal order,
        // group maximal runs of consecutive sparse pages, then merge each run of 2+ pages.
        final List<List<String>> runs = new ArrayList<>();
        List<String> run = new ArrayList<>();
        for (final String pageId : storage.indexStatus().livePageIds()) {
            final long[] counts = liveness.get(pageId);
            if (counts != null && isSparse(counts)) {
                run.add(pageId);
            } else if (!run.isEmpty()) {
                runs.add(run);
                run = new ArrayList<>();
            }
        }
        if (!run.isEmpty()) {
            runs.add(run);
        }
        for (final List<String> contiguousRun : runs) {
            // A lone sparse page has no sparse neighbour to merge with — skip it; it will be
            // swept up once an adjacent page also goes sparse.
            if (contiguousRun.size() >= 2) {
                compact(new LinkedHashSet<>(contiguousRun));
            }
        }
    }

    void runRetirementCycle() {
        IndexStatus pageStatus = storage.indexStatus();

        if (!pageStatus.retiredPageIds().isEmpty()) {
            storage.retirePages(pageStatus.retiredPageIds().toArray(new String[0]));
        }
    }
    
    Map<String, long[]> scanLiveness() {
        final Map<String, long[]> liveness = new HashMap<>();
        // A fact's live state can span several pages: its insert plus every page it was
        // modified on. We track all of them so a retract can reverse the fact's live
        // contribution everywhere, not just on its insert page.
        final Map<Long, List<String>> factToPages = new HashMap<>();
        final List<JournalRecord> pending = new ArrayList<>();
        String lastPageId = null;

        try (JournalScanner scanner = storage.scan(0)) {
            while (scanner.hasNext()) {
                final JournalRecord record = scanner.next();
                final String pageId = scanner.currentPageId();

                // Physical page boundary (size-triggered roll, no SafepointRecord)
                if (!pageId.equals(lastPageId) && lastPageId != null) {
                	flushPageLiveness(lastPageId, pending, liveness, factToPages);
                    pending.clear();
                }
                lastPageId = pageId;

                if (record instanceof SafepointRecord) {
                	flushPageLiveness(pageId, pending, liveness, factToPages);
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
                                          final Map<Long, List<String>> factToPages) {
        if (pending.isEmpty()) {
            return;
        }
        liveness.computeIfAbsent(pageId, k -> new long[2]);
        for (final JournalRecord r : pending) {
            if (r instanceof InsertRecord insert) {
                // A re-insert supersedes any prior live state for the fact.
                reverseLiveness(insert.factHandleId(), liveness, factToPages);
                liveness.get(pageId)[0]++;
                liveness.get(pageId)[1]++;
                factToPages.computeIfAbsent(insert.factHandleId(), k -> new ArrayList<>()).add(pageId);
            } else if (r instanceof ModifyRecord modify) {
                // A modify is current state of a live fact — live content, like an insert.
                liveness.get(pageId)[0]++;
                liveness.get(pageId)[1]++;
                factToPages.computeIfAbsent(modify.factHandleId(), k -> new ArrayList<>()).add(pageId);
            } else if (r instanceof RuleMatchRecord) {
                liveness.get(pageId)[1]++;
            } else if (r instanceof RetractRecord retract) {
                liveness.get(pageId)[1]++;
                reverseLiveness(retract.factHandleId(), liveness, factToPages);
            }
        }
    }

    // Drops a fact's live contribution from every page that held it (insert + modifies),
    // leaving each page's total untouched. Used on retract and on re-insert supersession.
    private static void reverseLiveness(final long factHandleId,
                                        final Map<String, long[]> liveness,
                                        final Map<Long, List<String>> factToPages) {
        final List<String> origins = factToPages.remove(factHandleId);
        if (origins != null) {
            for (final String origin : origins) {
                liveness.get(origin)[0]--;
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

        // Phase 2 — WRITE: carry every live fact's records (its insert plus any modify deltas)
        // and every still-live rule match from the source pages into the merged page, in
        // original order. A fact is live if it was never retracted; a RuleMatchRecord is live
        // while all the facts it references are live (once any is retracted the match tuple can
        // no longer re-form, so the match is dead weight). Carrying live matches preserves the
        // restore "already-fired" set (so rules do not re-fire) and the TMS justification for
        // live logical facts. Order is preserved so restore replays each insert before its
        // modifies; because the source pages form a contiguous run (see runMergingCycle), no
        // record outside the run interleaves, so the merged page may occupy the run's slot.
        final Set<Long> retractedIds = new HashSet<>();
        final List<JournalRecord> mergedLive = new ArrayList<>();

        try (JournalScanner scanner = storage.scan(0)) {
            String lastPageId = null;
            final List<JournalRecord> pageBuffer = new ArrayList<>();
            while (scanner.hasNext()) {
                final JournalRecord record = scanner.next();
                final String pageId = scanner.currentPageId();

                // Physical page boundary (size-triggered roll)
                if (!pageId.equals(lastPageId) && lastPageId != null) {
                    if (pageIds.contains(lastPageId)) {
                        mergedLive.addAll(pageBuffer);
                    }
                    pageBuffer.clear();
                }
                lastPageId = pageId;

                if (record instanceof SafepointRecord) {
                    if (pageIds.contains(pageId)) {
                        mergedLive.addAll(pageBuffer);
                    }
                    pageBuffer.clear();
                } else if (record instanceof InsertRecord || record instanceof ModifyRecord
                        || record instanceof RuleMatchRecord) {
                    pageBuffer.add(record);
                } else if (record instanceof RetractRecord retract) {
                    retractedIds.add(retract.factHandleId());
                }
            }
            // Trailing open page — no source-page records; discard.
        }
        mergedLive.removeIf(r -> isDead(r, retractedIds));

        final List<JournalRecord> mergedRecords = new ArrayList<>(mergedLive);
        mergedRecords.add(new SafepointRecord(-1, 0L));
        storage.writeMergedPage(mergedPageId, mergedRecords);

        // Phase 3 — COMMIT
        storage.compactionCommit(mergedPageId, replacedPageIds);
    }

    // A carried record is dead if the fact it concerns was retracted. For an insert or modify
    // that is its own factHandleId; for a rule match it is dead as soon as ANY referenced fact
    // is retracted, since the match tuple can then never re-form.
    private static boolean isDead(final JournalRecord record, final Set<Long> retractedIds) {
        if (record instanceof InsertRecord insert) {
            return retractedIds.contains(insert.factHandleId());
        }
        if (record instanceof ModifyRecord modify) {
            return retractedIds.contains(modify.factHandleId());
        }
        if (record instanceof RuleMatchRecord match) {
            for (final long factHandleId : match.factHandleIds()) {
                if (retractedIds.contains(factHandleId)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
