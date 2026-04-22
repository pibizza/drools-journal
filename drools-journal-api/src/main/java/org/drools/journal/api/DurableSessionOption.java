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

import org.kie.api.runtime.conf.SingleValueKieSessionOption;

/**
 * {@code KieSessionConfiguration} option that activates journal-based session
 * durability.
 *
 * <p>Usage:
 * <pre>{@code
 * KieSessionConfiguration cfg = KieServices.get().newKieSessionConfiguration();
 * cfg.setOption(DurableSessionOption.newSession()
 *         .withObjectStorage(ObjectStorageMode.EMBED)
 *         .withPageRollStrategy(PageRollStrategies.safepointOnly())
 *         .withJournalStorage(myJournalStorage));
 * }</pre>
 *
 * <p>{@code journalStorage} is required; all other fields have defaults.
 */
public class DurableSessionOption implements SingleValueKieSessionOption {

    public static final String PROPERTY_NAME = "drools.journalsession";

    private ObjectStorageMode objectStorageMode = ObjectStorageMode.EMBED;
    private PageRollStrategy pageRollStrategy = PageRollStrategies.safepointOnly();
    private JournalStorage journalStorage;

    private DurableSessionOption() {
    }

    public static DurableSessionOption newSession() {
        return new DurableSessionOption();
    }

    @Override
    public String type() {
        return PROPERTY_NAME;
    }

    @Override
    @Deprecated
    public String getPropertyName() {
        return PROPERTY_NAME;
    }

    public ObjectStorageMode getObjectStorageMode() {
        return objectStorageMode;
    }

    public PageRollStrategy getPageRollStrategy() {
        return pageRollStrategy;
    }

    /**
     * Returns the configured journal storage.
     *
     * @throws IllegalStateException if {@link #withJournalStorage(JournalStorage)} was never called
     */
    public JournalStorage getJournalStorage() {
        if (journalStorage == null) {
            throw new IllegalStateException(
                    "JournalStorage has not been set on DurableSessionOption — call withJournalStorage(...)");
        }
        return journalStorage;
    }

    public DurableSessionOption withObjectStorage(final ObjectStorageMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("ObjectStorageMode must not be null");
        }
        this.objectStorageMode = mode;
        return this;
    }

    public DurableSessionOption withPageRollStrategy(final PageRollStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("PageRollStrategy must not be null");
        }
        this.pageRollStrategy = strategy;
        return this;
    }

    public DurableSessionOption withJournalStorage(final JournalStorage storage) {
        if (storage == null) {
            throw new IllegalArgumentException("JournalStorage must not be null");
        }
        this.journalStorage = storage;
        return this;
    }

    @Override
    public String toString() {
        return "DurableSessionOption(objectStorageMode=" + objectStorageMode +
                ", journalStorage=" + journalStorage + ")";
    }
}
