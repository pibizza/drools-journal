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
import java.util.List;

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
 * <p>Records are organised into pages. Each page accumulates records until a
 * {@link SafepointRecord} closes it, at which point a new page opens.
 * Positions are global zero-based indices across all pages.
 */
public class InMemoryJournalStorage implements JournalStorage {

    private static class Page {
        final List<JournalRecord> records = new ArrayList<>();
    }

    private final List<Page> pages = new ArrayList<>();
    private Page currentPage = new Page();
    private boolean closed = false;
    private long safepointSequenceNo = 0L;

    public InMemoryJournalStorage() {
        pages.add(currentPage);
    }

    @Override
    public synchronized long append(final JournalRecord record) {
        checkOpen();
        currentPage.records.add(record);
        long position = globalSize() - 1;
        if (record instanceof SafepointRecord) {
            currentPage = new Page();
            pages.add(currentPage);
        }
        return position;
    }

    @Override
    public synchronized JournalScanner scan(final long fromPosition) {
        checkOpen();
        List<JournalRecord> flat = new ArrayList<>();
        for (Page page : pages) {
            flat.addAll(page.records);
        }
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

    /** Returns the index of the current open page (0-based). */
    synchronized int currentPageNumber() {
        return pages.size() - 1;
    }

    /** Returns the total number of records across all pages. */
    synchronized int size() {
        return globalSize();
    }

    @Override
    public synchronized void safepoint() {
        append(new SafepointRecord(safepointSequenceNo++, System.currentTimeMillis()));
    }

    void insert(final long factHandleId, final Object fact) {
        append(new InsertRecord(factHandleId, false, -1L, JournalPayloadBuilder.embed(fact)));
    }

    void logicalInsert(final long factHandleId, final long justifyingRuleMatchId, final Object fact) {
        append(new InsertRecord(factHandleId, true, justifyingRuleMatchId, JournalPayloadBuilder.embed(fact)));
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

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("InMemoryJournalStorage has been closed");
        }
    }

    private int globalSize() {
        int total = 0;
        for (Page page : pages) {
            total += page.records.size();
        }
        return total;
    }
}
