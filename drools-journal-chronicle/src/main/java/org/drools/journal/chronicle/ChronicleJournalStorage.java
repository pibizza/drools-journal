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

import java.nio.file.Path;
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
import org.drools.journal.chronicle.internal.CatalogIndex;
import org.drools.journal.chronicle.internal.ChronicleCatalogWriteOps;
import org.drools.journal.chronicle.internal.ChronicleDataWriteOps;
import org.drools.journal.chronicle.internal.PayloadCodec;

public final class ChronicleJournalStorage implements JournalStorage {

    private final Path rootDir;
    private final SingleChronicleQueue catalogQueue;
    private final ChronicleCatalogWriteOps catalogWriter;
    private final PageRollStrategy rollStrategy;
    private SingleChronicleQueue activePageQueue;
    private ChronicleDataWriteOps sessionWriter;
    private int pageIdCounter;
    private String currentPageId;
    private long lastWrittenPosition;
    private long safepointSequenceNo;
    private long currentPageBytes;
    private long currentRecordCount;
    private boolean empty;
    private boolean closed;

    private ChronicleJournalStorage(final Path rootDir,
                                    final SingleChronicleQueue catalogQueue,
                                    final ChronicleCatalogWriteOps catalogWriter,
                                    final SingleChronicleQueue activePageQueue,
                                    final ChronicleDataWriteOps sessionWriter,
                                    final PageRollStrategy rollStrategy,
                                    final int pageIdCounter,
                                    final String currentPageId,
                                    final long lastWrittenPosition,
                                    final boolean empty) {
        this.rootDir = rootDir;
        this.catalogQueue = catalogQueue;
        this.catalogWriter = catalogWriter;
        this.activePageQueue = activePageQueue;
        this.sessionWriter = sessionWriter;
        this.rollStrategy = rollStrategy;
        this.pageIdCounter = pageIdCounter;
        this.currentPageId = currentPageId;
        this.lastWrittenPosition = lastWrittenPosition;
        this.empty = empty;
    }

    public static ChronicleJournalStorage atPath(final String path) {
        return atPath(path, PageRollStrategies.safepointOnly());
    }

    public static ChronicleJournalStorage atPath(final String path, final PageRollStrategy rollStrategy) {
        Path rootDir = Path.of(path);
        SingleChronicleQueue catalogQueue = openQueue(rootDir.resolve("catalog"));
        CatalogIndex index = CatalogIndex.build(catalogQueue);

        if (index.livePages().isEmpty()) {
            return createFresh(rootDir, catalogQueue, rollStrategy);
        }
        return reopenExisting(rootDir, catalogQueue, index, rollStrategy);
    }

    private static ChronicleJournalStorage createFresh(final Path rootDir,
                                                       final SingleChronicleQueue catalogQueue,
                                                       final PageRollStrategy rollStrategy) {
        ChronicleCatalogWriteOps catalogWriter = newCatalogWriter(catalogQueue);
        SingleChronicleQueue pageQueue = openQueue(pageDir(rootDir, "0"));
        catalogWriter.pageCreated("0");
        return new ChronicleJournalStorage(rootDir, catalogQueue, catalogWriter,
                pageQueue, newDataWriter(pageQueue), rollStrategy, 0, "0", -1L, true);
    }

    private static ChronicleJournalStorage reopenExisting(final Path rootDir,
                                                          final SingleChronicleQueue catalogQueue,
                                                          final CatalogIndex index,
                                                          final PageRollStrategy rollStrategy) {
        ChronicleCatalogWriteOps catalogWriter = newCatalogWriter(catalogQueue);
        List<String> livePages = index.livePages();
        String activePageId = livePages.get(livePages.size() - 1);
        SingleChronicleQueue pageQueue = openQueue(pageDir(rootDir, activePageId));
        long queueLastIndex = pageQueue.lastIndex();
        long lastPos = (queueLastIndex == Long.MIN_VALUE) ? -1L : queueLastIndex;
        return new ChronicleJournalStorage(rootDir, catalogQueue, catalogWriter,
                pageQueue, newDataWriter(pageQueue), rollStrategy,
                index.highestPageCounter(), activePageId, lastPos, false);
    }

    @Override
    public long insert(final long factHandleId, final Payload payload) {
        empty = false;
        byte[] encoded = PayloadCodec.encode(payload);
        sessionWriter.insert(factHandleId, encoded);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, false, -1L, payload), encoded.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long insertLogical(final long factHandleId, final Payload payload, final long justifyingRuleMatchId) {
        byte[] encoded = PayloadCodec.encode(payload);
        sessionWriter.insertLogical(factHandleId, encoded, justifyingRuleMatchId);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, true, justifyingRuleMatchId, payload), encoded.length + 16);
        return lastWrittenPosition;
    }

    @Override
    public long retract(final long factHandleId) {
        sessionWriter.retract(factHandleId);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new RetractRecord(factHandleId), 8);
        return lastWrittenPosition;
    }

    @Override
    public long modify(final long factHandleId, final String lambdaClassRef, final byte[] params) {
        sessionWriter.modify(factHandleId, lambdaClassRef, params);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new ModifyRecord(factHandleId, lambdaClassRef, params), params.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long ruleMatch(final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        sessionWriter.ruleMatch(id, packageName, ruleName, factHandleIds);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new RuleMatchRecord(id, packageName, ruleName, factHandleIds), factHandleIds.length * 8 + 8);
        return lastWrittenPosition;
    }

    @Override
    public long compactionPrepare(final String preparingPageId, final String[] replacedPageIds) {
        catalogWriter.compactionPrepare(preparingPageId, replacedPageIds);
        return catalogAppender().lastIndexAppended();
    }

    @Override
    public long compactionCommit(final String mergedPageId, final String[] replacedPageIds) {
        catalogWriter.compactionCommit(mergedPageId, replacedPageIds);
        return catalogAppender().lastIndexAppended();
    }

    @Override
    public void safepoint() {
        long seqNo = safepointSequenceNo++;
        long ts = System.currentTimeMillis();
        sessionWriter.safepoint(seqNo, ts);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        roll();
    }

    @Override
    public JournalScanner scan(final long fromPosition) {
        return MultiQueueScanner.create(rootDir, catalogQueue);
    }

    @Override
    public boolean isEmpty() {
        return empty;
    }

    @Override
    public long latestPosition() {
        return lastWrittenPosition;
    }

    @Override
    public void writeMergedPage(final String pageId, final List<JournalRecord> records) {
        try (SingleChronicleQueue mergedQueue = openQueue(pageDir(rootDir, pageId))) {
            ChronicleDataWriteOps w = newDataWriter(mergedQueue);
            for (JournalRecord record : records) {
                writeRecord(w, record);
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            activePageQueue.close();
            catalogQueue.close();
        }
    }

    private ExcerptAppender activeAppender() {
        return activePageQueue.acquireAppender();
    }

    private ExcerptAppender catalogAppender() {
        return catalogQueue.acquireAppender();
    }

    private void maybeRoll(final JournalRecord record, final long estimatedBytes) {
        currentPageBytes += estimatedBytes;
        currentRecordCount++;
        PageContext ctx = new PageContextSnapshot(record, currentPageBytes, currentRecordCount);
        if (rollStrategy.decide(ctx) == RollDecision.ROLL) {
            roll();
        }
    }

    private void roll() {
        activePageQueue.close();
        currentPageId = String.valueOf(++pageIdCounter);
        currentPageBytes = 0;
        currentRecordCount = 0;
        activePageQueue = openQueue(pageDir(rootDir, currentPageId));
        sessionWriter = newDataWriter(activePageQueue);
        catalogWriter.pageCreated(currentPageId);
    }

    private static Path pageDir(final Path rootDir, final String pageId) {
        return rootDir.resolve("page-" + pageId);
    }

    private static ChronicleCatalogWriteOps newCatalogWriter(final SingleChronicleQueue catalogQueue) {
        return catalogQueue.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
    }

    private static SingleChronicleQueue openQueue(final Path path) {
        return SingleChronicleQueueBuilder.binary(path).build();
    }

    private static ChronicleDataWriteOps newDataWriter(final SingleChronicleQueue queue) {
        return queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
    }

    private static void writeRecord(final ChronicleDataWriteOps w, final JournalRecord record) {
        switch (record) {
            case InsertRecord ir when !ir.logical() ->
                    w.insert(ir.factHandleId(), PayloadCodec.encode(ir.payload()));
            case InsertRecord ir ->
                    w.insertLogical(ir.factHandleId(), PayloadCodec.encode(ir.payload()), ir.justifyingRuleMatchId());
            case RetractRecord rr ->
                    w.retract(rr.factHandleId());
            case ModifyRecord mr ->
                    w.modify(mr.factHandleId(), mr.lambdaClassRef(), mr.parameters());
            case RuleMatchRecord rm ->
                    w.ruleMatch(rm.id(), rm.packageName(), rm.ruleName(), rm.factHandleIds());
            case SafepointRecord sr ->
                    w.safepoint(sr.sequenceNo(), sr.timestamp());
            case CompactionPrepareRecord ignored ->
                    throw new IllegalArgumentException("CompactionPrepareRecord belongs in the catalog, not data pages");
            case CompactionCommitRecord ignored ->
                    throw new IllegalArgumentException("CompactionCommitRecord belongs in the catalog, not data pages");
        }
    }

    private record PageContextSnapshot(
            JournalRecord lastRecord,
            long currentPageBytes,
            long currentRecordCount) implements PageContext {
    }
}
