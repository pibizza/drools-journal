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
package org.drools.journal.api;

import java.util.List;

/**
 * SPI for append-only journal storage.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>Appends are atomic — a partial write is never visible to a reader.</li>
 *   <li>Positions are monotonically increasing {@code long} values that can be
 *       passed directly to {@link #scan(long)} in a subsequent call.</li>
 * </ul>
 *
 * <p>The write side is intentionally semantic: callers describe <em>what happened</em>
 * (insert, retract, ruleMatch, …) rather than constructing record objects.
 * Record types are an internal concern of each storage implementation.
 */
public interface JournalStorage extends AutoCloseable {

    // -------------------------------------------------------------------------
    // Semantic write API
    // -------------------------------------------------------------------------

    /**
     * Records that a fact was inserted into the session.
     *
     * @param factHandleId ID of the fact handle
     * @param payload      serialized fact
     * @return position assigned to this record
     */
    long insert(long factHandleId, Payload payload);

    /**
     * Records that a fact was inserted logically — driven by a rule consequence
     * and justified by a specific activation.
     *
     * @param factHandleId          ID of the fact handle
     * @param payload               serialized fact
     * @param justifyingRuleMatchId ID of the activation that caused the insert
     * @return position assigned to this record
     */
    long insertLogical(long factHandleId, Payload payload, long justifyingRuleMatchId);

    /**
     * Records that a fact was retracted from the session.
     *
     * @param factHandleId ID of the fact handle
     * @return position assigned to this record
     */
    long retract(long factHandleId);

    /**
     * Records a delta-style modification of a fact.
     *
     * @param factHandleId   ID of the fact handle
     * @param lambdaClassRef class reference of the modify lambda
     * @param params         serialized lambda parameters
     * @return position assigned to this record
     */
    long modify(long factHandleId, String lambdaClassRef, byte[] params);

    /**
     * Records that a rule activation fired.
     *
     * @param id            surrogate key for this activation
     * @param packageName   package of the rule that fired
     * @param ruleName      name of the rule that fired
     * @param factHandleIds IDs of the facts that matched the rule
     * @return position assigned to this record
     */
    long ruleMatch(long id, String packageName, String ruleName, long[] factHandleIds);

    /**
     * Records the start of a compaction cycle. The preparing page is not yet
     * canonical; {@code replacedPageIds} remain live until the matching
     * {@link #compactionCommit}.
     *
     * @param preparingPageId ID of the merged page being written
     * @param replacedPageIds IDs of the source pages being compacted
     * @return position assigned to this record
     */
    long compactionPrepare(String preparingPageId, String[] replacedPageIds);

    /**
     * Records the successful completion of a compaction cycle. From this point
     * {@code mergedPageId} is canonical and {@code replacedPageIds} are retired.
     *
     * @param mergedPageId    ID of the now-canonical merged page
     * @param replacedPageIds IDs of the retired source pages
     * @return position assigned to this record
     */
    long compactionCommit(String mergedPageId, String[] replacedPageIds);

    /**
     * Appends a {@link SafepointRecord} with the next sequence number and the
     * current wall-clock time. All records appended before this call are
     * considered durable; records appended after are pending until the next
     * safepoint.
     */
    void safepoint();

    // -------------------------------------------------------------------------
    // Read API
    // -------------------------------------------------------------------------

    /**
     * Opens a forward-only scanner starting at {@code fromPosition}.
     * The first call to {@link JournalScanner#next()} returns the record at
     * {@code fromPosition}, or the first record after it if that exact position
     * holds no record boundary.
     *
     * @param fromPosition position returned by a previous write call,
     *                     or {@code 0} to scan from the beginning
     * @return a scanner positioned at {@code fromPosition}; caller must close it
     */
    JournalScanner scan(long fromPosition);

    /**
     * Returns the position of the most recently written record, or {@code -1}
     * if the journal is empty.
     */
    long latestPosition();

    /**
     * Writes a merged page produced by compaction. The page is stored in the
     * journal but is not part of the live page sequence until the caller calls
     * {@link #compactionCommit} followed by {@link #safepoint()}.
     *
     * @param pageId  the unique identifier for the merged page
     * @param records the live records to include in the merged page
     */
    void writeMergedPage(String pageId, List<JournalRecord> records);

    /**
     * Releases all resources held by this storage instance.
     */
    @Override
    void close();
}
