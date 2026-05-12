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

import org.drools.base.rule.EntryPointId;
import org.drools.core.RuleBaseConfiguration;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.ObjectStore;
import org.drools.core.common.PropagationContext;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.rule.consequence.InternalMatch;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.kiesession.entrypoints.NamedEntryPoint;

public class JournalledNamedEntryPoint extends NamedEntryPoint {

    private final JournalStorage journal;
    private final ObjectStorageStrategy strategy;

    public JournalledNamedEntryPoint(final JournalStorage journal, final ObjectStorageStrategy strategy) {
        super();
        this.journal = journal;
        this.strategy = strategy;
    }

    @Override
    protected ObjectStore createObjectStore(final EntryPointId entryPoint, final RuleBaseConfiguration conf, final ReteEvaluator reteEvaluator) {
        return new JournalledObjectStore(journal, strategy);
    }

    @Override
    protected void beforeUpdate(final InternalFactHandle handle, final Object object, final InternalMatch internalMatch,
                                final Object originalObject, final PropagationContext propagationContext) {
        journal.append(new InsertRecord(handle.getId(), false, -1L, JournalPayloadBuilder.build(object, handle, strategy)));
    }
}
