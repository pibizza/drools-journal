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
import java.util.Set;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.Payload;
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

	
	private final Page catalog = new Page("0");
	
    /** All pages ever created, in creation order. Primary sequential structure. */
    private final List<Page> journal = new ArrayList<>();

    private long pageCounter = 0L;

    private int recordCounter = 0;
    
    /** Currently open page — accumulates records until the next safepoint or roll. */
    private Page currentPage;

    private boolean closed = false;
    private long safepointSequenceNo = 0L;

    
    public InMemoryJournalStorage() {
        currentPage = new Page(String.valueOf(pageCounter++));
    }
    
    // -------------------------------------------------------------------------
    // Semantic write API (JournalStorage SPI)
    // -------------------------------------------------------------------------

    @Override
    public synchronized long insert(final long factHandleId, final Payload payload) {
        checkOpen();
        return append(new InsertRecord(factHandleId, false, -1L, payload));
    }

    @Override
    public synchronized long insertLogical(final long factHandleId, final Payload payload,
                                           final long justifyingRuleMatchId) {
        checkOpen();
        return append(new InsertRecord(factHandleId, true, justifyingRuleMatchId, payload));
    }

    @Override
    public synchronized long retract(final long factHandleId) {
        checkOpen();
        return append(new RetractRecord(factHandleId));
    }

    @Override
    public synchronized long modify(final long factHandleId, final String lambdaClassRef, final byte[] params) {
        checkOpen();
        return append(new ModifyRecord(factHandleId, lambdaClassRef, params));
    }

    @Override
    public synchronized long ruleMatch(final long id, final String packageName,
                                       final String ruleName, final long[] factHandleIds) {
        checkOpen();
        return append(new RuleMatchRecord(id, packageName, ruleName, factHandleIds));
    }

    @Override
    public synchronized long compactionPrepare(final String preparingPageId, final String[] replacedPageIds) {
        checkOpen();
        CompactionPrepareRecord record = new CompactionPrepareRecord(preparingPageId, replacedPageIds);
        catalog.records.add(record);
        recordCounter++;
		return recordCounter - 1;
    }

    @Override
    public synchronized long compactionCommit(final String mergedPageId, final String[] replacedPageIds) {
        checkOpen();
        CompactionCommitRecord record = new CompactionCommitRecord(mergedPageId, replacedPageIds);
        catalog.records.add(record);
        recordCounter++;
		return recordCounter - 1;
    }

    @Override
    public synchronized void safepoint() {
        checkOpen();
        SafepointRecord record = new SafepointRecord(safepointSequenceNo++, System.currentTimeMillis());
		append(record);
        
    }

    // -------------------------------------------------------------------------
    // Read API
    // -------------------------------------------------------------------------

    @Override
    public synchronized void writeMergedPage(final String pageId, final List<JournalRecord> records) {
        checkOpen();
        final Page page = new Page(pageId);
        journal.add(page);
        page.records.addAll(records);
    }

    @Override
    public synchronized JournalScanner scan(final long fromPosition) {
        checkOpen();
        
        return InMemoryMultiQueueScanner.create(catalog, journal);
    }

    @Override
    public synchronized boolean isEmpty() {
        checkOpen();
        return recordCounter == 0;
    }

    @Override
    public synchronized long latestPosition() {
        checkOpen();
        return recordCounter - 1;
    }

    @Override
    public synchronized void retirePages(final String... pageIds) {
        checkOpen();
        Set<String> toRetire = Set.of(pageIds);
        journal.removeIf(page -> toRetire.contains(page.id));
    }

    @Override
    public synchronized void close() {
        closed = true;
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
        return recordCounter;
    }

    /** Convenience: insert a non-logical fact using EmbedStrategy. */
    void insert(final long factHandleId, final Object fact) {
        insert(factHandleId, new EmbedStrategy().store(fact, null));
    }

    /** Convenience: insert a logical fact using EmbedStrategy. */
    void logicalInsert(final long factHandleId, final long justifyingRuleMatchId, final Object fact) {
        insertLogical(factHandleId, new EmbedStrategy().store(fact, null), justifyingRuleMatchId);
    }

    /** Convenience: safepoint with an explicit sequence number (for deterministic tests). */
    synchronized void safepoint(final long sequenceNo) {
        checkOpen();
        SafepointRecord record = new SafepointRecord(sequenceNo, 0L);
		append(record);
		
    }

    /** Forces a physical page roll without a safepoint — simulates size-triggered rolling in tests. */
    synchronized void rollPage() {
        checkOpen();
        currentPage = new Page(String.valueOf(pageCounter++));
        journal.add(currentPage);
    }

    /** Convenience: ruleMatch without an explicit package name. */
    void ruleMatch(final long id, final String ruleName, final long... factHandleIds) {
        ruleMatch(id, "test", ruleName, factHandleIds);
    }
    
    
    synchronized List<Page> livePages() {
    	return InMemoryMultiQueueScanner.build(catalog, journal).getLivePages();
    }

    synchronized List<Page> retiredPages() {
    	return InMemoryMultiQueueScanner.build(catalog, journal).getRetiredPages();
    }
    
    synchronized Page currentPage() {
    	return currentPage;
    }

    
    @Override
    public String toString() {
        return JournalPrinter.print(this);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private long append(final JournalRecord record) {
        currentPage.records.add(record);
        recordCounter++;

        if (record instanceof SafepointRecord sp) {
            catalog.records.add(new PageRecord(currentPage.id));
            journal.add(currentPage);
            currentPage = new Page(String.valueOf(pageCounter++));
        }

        return recordCounter - 1;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("InMemoryJournalStorage has been closed");
        }
    }

}
