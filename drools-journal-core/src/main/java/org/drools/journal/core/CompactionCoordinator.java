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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class CompactionCoordinator {

    static Map<String, long[]> scanLiveness(final JournalStorage storage) {
        Map<String, long[]> liveness = new HashMap<>();
        Map<Long, String> factToPage = new HashMap<>();
        List<JournalRecord> pending = new ArrayList<>();

        try (JournalScanner scanner = storage.scan(0)) {
            while (scanner.hasNext()) {
                JournalRecord record = scanner.next();
                if (record instanceof SafepointRecord sp) {
                    String pageId = String.valueOf(sp.sequenceNo());
                    liveness.put(pageId, new long[2]);
                    for (JournalRecord r : pending) {
                        if (r instanceof InsertRecord insert) {
                            liveness.get(pageId)[0]++;
                            liveness.get(pageId)[1]++;
                            factToPage.put(insert.factHandleId(), pageId);
                        } else if (r instanceof RuleMatchRecord || r instanceof ModifyRecord) {
                            liveness.get(pageId)[1]++;
                        } else if (r instanceof RetractRecord retract) {
                            liveness.get(pageId)[1]++;
                            String origin = factToPage.remove(retract.factHandleId());
                            if (origin != null) {
                                liveness.get(origin)[0]--;
                            }
                        }
                    }
                    pending.clear();
                } else {
                    pending.add(record);
                }
            }
        }

        return liveness;
    }

    static boolean isSparse(final long[] counts) {
        return (double) counts[0] / counts[1] < 0.30;
    }
}
