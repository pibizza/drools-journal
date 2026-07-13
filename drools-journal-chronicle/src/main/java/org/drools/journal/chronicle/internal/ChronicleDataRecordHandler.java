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
package org.drools.journal.chronicle.internal;

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

public final class ChronicleDataRecordHandler implements ChronicleDataWriteOps {

    private JournalRecord pending;

    public JournalRecord pending() {
        return pending;
    }

    public void reset() {
        pending = null;
    }

    @Override
    public void insert(final long factHandleId, final byte[] payload) {
        pending = new InsertRecord(factHandleId, false, -1L, PayloadCodec.decode(payload));
    }

    @Override
    public void insertLogical(final long factHandleId, final byte[] payload, final long justifyingRuleMatchId) {
        pending = new InsertRecord(factHandleId, true, justifyingRuleMatchId, PayloadCodec.decode(payload));
    }

    @Override
    public void retract(final long factHandleId) {
        pending = new RetractRecord(factHandleId);
    }

    @Override
    public void modify(final long factHandleId, final String lambdaClassRef, final byte[] parameters) {
        pending = new ModifyRecord(factHandleId, lambdaClassRef, parameters);
    }

    @Override
    public void ruleMatch(final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        pending = new RuleMatchRecord(id, packageName, ruleName, factHandleIds);
    }

    @Override
    public void safepoint(final long sequenceNo, final long timestamp) {
        pending = new SafepointRecord(sequenceNo, timestamp);
    }
}
