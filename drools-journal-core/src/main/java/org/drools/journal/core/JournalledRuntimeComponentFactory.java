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

import org.drools.base.RuleBase;
import org.drools.core.SessionConfiguration;
import org.drools.core.common.AgendaFactory;
import org.drools.core.common.EntryPointFactory;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.kiesession.factory.RuntimeComponentFactoryImpl;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.kie.api.runtime.Environment;

// Always active via SPI; each factory method checks the Environment before engaging journal behaviour.
public class JournalledRuntimeComponentFactory extends RuntimeComponentFactoryImpl {

    @Override
    public EntryPointFactory getEntryPointFactory() {
        return new JournalledEntryPointFactory();
    }

    @Override
    public AgendaFactory getAgendaFactory(final SessionConfiguration config) {
        return new JournalledAgendaFactory();
    }

    @Override
    public InternalWorkingMemory createStatefulSession(final RuleBase ruleBase,
                                                       final Environment environment,
                                                       final SessionConfiguration sessionConfig,
                                                       final boolean fromPool) {
        if (environment == null || environment.get(JournalledSessionFactory.JOURNAL_KEY) == null) {
            return super.createStatefulSession(ruleBase, environment, sessionConfig, fromPool);
        }
        InternalKnowledgeBase kbase = (InternalKnowledgeBase) ruleBase;
        if (fromPool || kbase.getSessionPool() == null) {
            JournalledKieSession session = new JournalledKieSession(
                    kbase.nextWorkingMemoryCounter(), kbase, true, sessionConfig, environment);
            if (sessionConfig.isKeepReference()) {
                kbase.addStatefulSession(session);
            }
            return session;
        }
        return (InternalWorkingMemory) kbase.getSessionPool().newKieSession(sessionConfig);
    }

    @Override
    public int servicePriority() {
        return 1;
    }
}
