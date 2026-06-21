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
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RetractRecord;

/**
 * Runs the {@link JournalStorageContractTest} suite against {@link InMemoryJournalStorage}.
 */
class InMemoryStorageContractTest extends JournalStorageContractTest {

    @Override
    protected JournalStorage createStorage() {
        return new InMemoryJournalStorage();
    }

    @Override
    protected long appendTestRecord(final JournalStorage storage, final JournalRecord record) {
        return storage.retract(((RetractRecord) record).factHandleId());
    }
}
