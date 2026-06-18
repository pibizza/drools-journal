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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

/**
 * Non-durable, in-process {@link JournalStorage} for use in tests. Thread-safe.
 *
 * <p>Maintains a raw append-only sequential journal. {@link #scan} returns records
 * in creation order. The canonical live-page ordering for restore is the
 * responsibility of {@link RestoreEngine}, not this class.
 */
public class InMemoryJournalStorage implements JournalStorage {

    static final class Page {
        String id;
        final List<JournalRecord> records = new ArrayList<>();
    }

    /** All pages ever created, in creation order. Primary sequential structure. */
    private final List<Page> journal = new ArrayList<>();

    /** Lookup helper — not the primary structure. */
    private final Map<String, Page> pageById = new HashMap<>();

    /** Currently open page — accumulates records until the next safepoint. */
    private Page currentPage = new Page();

    private boolean closed = false;
    private long safepointSequenceNo = 0L;

    @Override
    public synchronized long append(final JournalRecord record) {
        checkOpen();
        currentPage.records.add(record);

        if (record instanceof SafepointRecord sp) {
            currentPage.id = String.valueOf(sp.sequenceNo());
            journal.add(currentPage);
            pageById.put(currentPage.id, currentPage);
            currentPage = new Page();
        }

        return globalSize() - 1;
    }

    @Override
    public synchronized void writeMergedPage(final String pageId, final List<JournalRecord> records) {
        checkOpen();
        final Page page = new Page();
        page.id = pageId;
        page.records.addAll(records);
        journal.add(page);
        pageById.put(pageId, page);
    }

    @Override
    public synchronized JournalScanner scan(final long fromPosition) {
        checkOpen();
        final List<JournalRecord> flat = new ArrayList<>();
        for (final Page page : journal) {
            flat.addAll(page.records);
        }
        flat.addAll(currentPage.records);
        return new InMemoryJournalScanner(List.copyOf(flat), fromPosition);
    }

    @Override
    public synchronized long latestPosition() {
        checkOpen();
        return globalSize() - 1;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    @Override
    public synchronized void safepoint() {
        append(new SafepointRecord(safepointSequenceNo++, System.currentTimeMillis()));
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /** Number of closed pages in the journal (session pages + merged pages). */
    synchronized int currentPageNumber() {
        return journal.size();
    }

    /** Total number of records in the raw journal plus the open page. */
    synchronized int size() {
        return globalSize();
    }

    void insert(final long factHandleId, final Object fact) {
        append(new InsertRecord(factHandleId, false, -1L, new EmbedStrategy().store(fact, null)));
    }

    void logicalInsert(final long factHandleId, final long justifyingRuleMatchId, final Object fact) {
        append(new InsertRecord(factHandleId, true, justifyingRuleMatchId, new EmbedStrategy().store(fact, null)));
    }

    void modify(final long factHandleId, final String lambdaClassRef, final byte[] parameters) {
        append(new ModifyRecord(factHandleId, lambdaClassRef, parameters));
    }

    void retract(final long factHandleId) {
        append(new RetractRecord(factHandleId));
    }

    void safepoint(final long sequenceNo) {
        append(new SafepointRecord(sequenceNo, 0L));
    }

    void ruleMatch(final long id, final String ruleName, final long... factHandleIds) {
        append(new RuleMatchRecord(id, "test", ruleName, factHandleIds));
    }

    void compactionPrepare(final String mergedPageId, final String... replacedPageIds) {
        append(new CompactionPrepareRecord(mergedPageId, replacedPageIds));
    }

    void compactionCommit(final String mergedPageId, final String... replacedPageIds) {
        append(new CompactionCommitRecord(mergedPageId, replacedPageIds));
    }

    @Override
    public String toString() {
        return JournalPrinter.print(this);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private int globalSize() {
        int total = currentPage.records.size();
        for (final Page page : journal) {
            total += page.records.size();
        }
        return total;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("InMemoryJournalStorage has been closed");
        }
    }
}
