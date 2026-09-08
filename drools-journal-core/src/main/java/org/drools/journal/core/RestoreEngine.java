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

import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyLambdaRegistry;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.RuleMatchRecord;

import java.util.List;
import java.util.Map;

// NOT thread-safe — Drools sessions fire on a single thread
public class RestoreEngine {

    public record PendingTmsLink(long factHandleId, long justifyingRuleMatchId) {}

    public record ScanResult(Map<Long, Object> survivingFacts,
                      List<RuleMatchRecord> firedMatches,
                      List<PendingTmsLink> pendingTmsLinks,
                      Map<Long, RuleMatchRecord> firedMatchesById) {}
    
    private final JournalStorage journal;
    private final ModifyLambdaRegistry lambdaRegistry;
    private final ObjectStorageStrategy strategy;

    public RestoreEngine(final JournalStorage journal, final ModifyLambdaRegistry lambdaRegistry) {
        this(journal, lambdaRegistry, new EmbedStrategy());
    }

    RestoreEngine(final JournalStorage journal, final ModifyLambdaRegistry lambdaRegistry,
                  final ObjectStorageStrategy strategy) {
        this.journal = journal;
        this.lambdaRegistry = lambdaRegistry;
        this.strategy = strategy;
    }

    public ScanResult scan() {

        // Phase 1: replay raw stream, flushing live pages, discarding retired ones.
        // Flushes occur at physical page boundaries (size-triggered rolls) and at
        // SafepointRecords (safepoint-triggered rolls).

    	ScanCursor cursor = new ScanCursor(lambdaRegistry, strategy);
        try (JournalScanner scanner = journal.scan(0)) {
            while (scanner.hasNext()) {
                final JournalRecord record = scanner.next();
                final String pageId = scanner.currentPageId();
                cursor.move(record, pageId);
            }
        }
        // Trailing records after the last safepoint are silently discarded.

        return cursor.getScanResult();
    }

}
