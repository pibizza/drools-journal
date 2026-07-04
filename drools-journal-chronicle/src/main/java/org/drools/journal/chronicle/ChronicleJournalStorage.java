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
package org.drools.journal.chronicle;

import java.util.List;

import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.PageContext;
import org.drools.journal.api.PageRollStrategies;
import org.drools.journal.api.PageRollStrategy;
import org.drools.journal.api.Payload;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RollDecision;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.chronicle.internal.ChronicleWriteOps;
import org.drools.journal.chronicle.internal.PayloadCodec;

public final class ChronicleJournalStorage implements JournalStorage {

    private final SingleChronicleQueue queue;
    private final ExcerptAppender appender;
    private final ChronicleWriteOps writer;
    private final PageRollStrategy rollStrategy;
    private int pageIdCounter;
    private String currentPageId;
    private long lastWrittenPosition;
    private long safepointSequenceNo;
    private long currentPageBytes;
    private long currentRecordCount;
    private boolean closed;

    private ChronicleJournalStorage(final SingleChronicleQueue queue, final PageRollStrategy rollStrategy) {
        this.queue = queue;
        this.appender = queue.acquireAppender();
        this.writer = appender.methodWriter(ChronicleWriteOps.class);
        this.rollStrategy = rollStrategy;
        this.pageIdCounter = 0;
        this.currentPageId = "0";
        final long queueLastIndex = queue.lastIndex();
        this.lastWrittenPosition = (queueLastIndex == Long.MIN_VALUE) ? -1L : queueLastIndex;
    }

    public static ChronicleJournalStorage atPath(final String path) {
        return atPath(path, PageRollStrategies.safepointOnly());
    }

    public static ChronicleJournalStorage atPath(final String path, final PageRollStrategy rollStrategy) {
        final SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(path).build();
        return new ChronicleJournalStorage(queue, rollStrategy);
    }

    @Override
    public long insert(final long factHandleId, final Payload payload) {
        final byte[] encoded = PayloadCodec.encode(payload);
        writer.insert(currentPageId, factHandleId, encoded);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, false, -1L, payload), encoded.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long insertLogical(final long factHandleId, final Payload payload, final long justifyingRuleMatchId) {
        final byte[] encoded = PayloadCodec.encode(payload);
        writer.insertLogical(currentPageId, factHandleId, encoded, justifyingRuleMatchId);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, true, justifyingRuleMatchId, payload), encoded.length + 16);
        return lastWrittenPosition;
    }

    @Override
    public long retract(final long factHandleId) {
        writer.retract(currentPageId, factHandleId);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new RetractRecord(factHandleId), 8);
        return lastWrittenPosition;
    }

    @Override
    public long modify(final long factHandleId, final String lambdaClassRef, final byte[] params) {
        writer.modify(currentPageId, factHandleId, lambdaClassRef, params);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new ModifyRecord(factHandleId, lambdaClassRef, params), params.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long ruleMatch(final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        writer.ruleMatch(currentPageId, id, packageName, ruleName, factHandleIds);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new RuleMatchRecord(id, packageName, ruleName, factHandleIds), factHandleIds.length * 8 + 8);
        return lastWrittenPosition;
    }

    @Override
    public long compactionPrepare(final String preparingPageId, final String[] replacedPageIds) {
        writer.compactionPrepare(currentPageId, preparingPageId, replacedPageIds);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new CompactionPrepareRecord(preparingPageId, replacedPageIds), 64);
        return lastWrittenPosition;
    }

    @Override
    public long compactionCommit(final String mergedPageId, final String[] replacedPageIds) {
        writer.compactionCommit(currentPageId, mergedPageId, replacedPageIds);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new CompactionCommitRecord(mergedPageId, replacedPageIds), 64);
        return lastWrittenPosition;
    }

    @Override
    public void safepoint() {
        final long seqNo = safepointSequenceNo++;
        final long ts = System.currentTimeMillis();
        writer.safepoint(currentPageId, seqNo, ts);
        lastWrittenPosition = appender.lastIndexAppended();
        roll();
    }

    @Override
    public JournalScanner scan(final long fromPosition) {
        return new ChronicleJournalScanner(queue.createTailer(), fromPosition);
    }

    @Override
    public long latestPosition() {
        return lastWrittenPosition;
    }

    @Override
    public void writeMergedPage(final String pageId, final List<JournalRecord> records) {
        for (final JournalRecord record : records) {
            writeRecord(pageId, record);
            lastWrittenPosition = appender.lastIndexAppended();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            queue.close();
        }
    }

    private void maybeRoll(final JournalRecord record, final long estimatedBytes) {
        currentPageBytes += estimatedBytes;
        currentRecordCount++;
        final PageContext ctx = new PageContextSnapshot(record, currentPageBytes, currentRecordCount);
        if (rollStrategy.decide(ctx) == RollDecision.ROLL) {
            roll();
        }
    }

    private void roll() {
        currentPageId = String.valueOf(++pageIdCounter);
        currentPageBytes = 0;
        currentRecordCount = 0;
    }

    private void writeRecord(final String pageId, final JournalRecord record) {
        switch (record) {
            case InsertRecord ir when !ir.logical() ->
                    writer.insert(pageId, ir.factHandleId(), PayloadCodec.encode(ir.payload()));
            case InsertRecord ir ->
                    writer.insertLogical(pageId, ir.factHandleId(), PayloadCodec.encode(ir.payload()), ir.justifyingRuleMatchId());
            case RetractRecord rr ->
                    writer.retract(pageId, rr.factHandleId());
            case ModifyRecord mr ->
                    writer.modify(pageId, mr.factHandleId(), mr.lambdaClassRef(), mr.parameters());
            case RuleMatchRecord rm ->
                    writer.ruleMatch(pageId, rm.id(), rm.packageName(), rm.ruleName(), rm.factHandleIds());
            case SafepointRecord sr ->
                    writer.safepoint(pageId, sr.sequenceNo(), sr.timestamp());
            case CompactionPrepareRecord cp ->
                    writer.compactionPrepare(pageId, cp.preparingPageId(), cp.replacedPageIds());
            case CompactionCommitRecord cc ->
                    writer.compactionCommit(pageId, cc.mergedPageId(), cc.replacedPageIds());
        }
    }

    private record PageContextSnapshot(
            JournalRecord lastRecord,
            long currentPageBytes,
            long currentRecordCount) implements PageContext {
    }
}
