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
import org.drools.core.WorkingMemory;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.ObjectStore;
import org.drools.core.common.PropagationContext;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.impl.InternalRuleBase;
import org.drools.core.reteoo.EntryPointNode;
import org.drools.core.rule.accessor.FactHandleFactory;
import org.drools.core.rule.consequence.InternalMatch;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.StorageDecision;
import org.drools.kiesession.entrypoints.NamedEntryPoint;

public class JournalledNamedEntryPoint extends NamedEntryPoint {

    private JournalStorage journal;
    private ObjectStorageStrategy strategy;

    // Used by unit tests — does not call createObjectStore()
    public JournalledNamedEntryPoint(final JournalStorage journal, final ObjectStorageStrategy strategy) {
        super();
        this.journal = journal;
        this.strategy = strategy;
    }

    // Used by JournalledEntryPointFactory — createObjectStore() reads journal from the Environment
    JournalledNamedEntryPoint(final InternalRuleBase ruleBase,
                              final ReteEvaluator reteEvaluator,
                              final FactHandleFactory factHandleFactory,
                              final EntryPointId entryPoint,
                              final EntryPointNode entryPointNode) {
        super(ruleBase, reteEvaluator, factHandleFactory, entryPoint, entryPointNode);
    }

    @Override
    protected ObjectStore createObjectStore(final EntryPointId entryPoint, final RuleBaseConfiguration conf, final ReteEvaluator reteEvaluator) {
        // Called from the full constructor via super() — read journal from the session Environment
        this.journal = (JournalStorage) ((WorkingMemory) reteEvaluator).getEnvironment().get(JournalledSessionFactory.JOURNAL_KEY);
        this.strategy = (fact, handle) -> StorageDecision.EMBED;
        return new JournalledObjectStore();
    }

    @Override
    protected void beforeUpdate(final InternalFactHandle handle, final Object object, final InternalMatch internalMatch,
                                final Object originalObject, final PropagationContext propagationContext) {
        // Journal write moved to JournallingRuntimeEventListener.objectUpdated()
    }
}
