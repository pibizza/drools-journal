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

import java.time.Duration;
import java.util.function.Function;

/**
 * Configuration option that activates journal-based session durability.
 * Passed to the session via {@link org.kie.api.runtime.Environment}.
 *
 * <p>Usage:
 * <pre>{@code
 * Environment env = KieServices.get().newEnvironment();
 * env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
 *         .withJournalStorage(myStorage)
 *         .withCompactionInterval(Duration.ofSeconds(30)));
 * KieSession session = kbase.newKieSession(null, env);
 * }</pre>
 *
 * <p>{@code journalStorage} is required; all other fields have defaults.
 */
public class DurableSessionOption {

    public static final String PROPERTY_NAME = "drools.journalsession";

    private ObjectStorageMode objectStorageMode = ObjectStorageMode.EMBED;
    private PageRollStrategy pageRollStrategy = PageRollStrategies.safepointOnly();
    private JournalStorage journalStorage;
    private Duration compactionInterval = Duration.ofSeconds(60);
    private ModifyLambdaRegistry modifyLambdaRegistry = new ModifyLambdaRegistry();
    private Function<Object, String> externalRefKeySupplier;
    private Function<ExternalRef, Object> externalRefLoader;

    private DurableSessionOption() {
    }

    public static DurableSessionOption newSession() {
        return new DurableSessionOption();
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

    public DurableSessionOption withExternalRefStorage(final Function<Object, String> keySupplier,
                                                       final Function<ExternalRef, Object> loader) {
        if (keySupplier == null) {
            throw new IllegalArgumentException("keySupplier must not be null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        this.objectStorageMode = ObjectStorageMode.EXTERNAL_REF;
        this.externalRefKeySupplier = keySupplier;
        this.externalRefLoader = loader;
        return this;
    }

    public Function<Object, String> getExternalRefKeySupplier() {
        return externalRefKeySupplier;
    }

    public Function<ExternalRef, Object> getExternalRefLoader() {
        return externalRefLoader;
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

    public Duration getCompactionInterval() {
        return compactionInterval;
    }

    public DurableSessionOption withCompactionInterval(final Duration interval) {
        if (interval == null) {
            throw new IllegalArgumentException("Compaction interval must not be null");
        }
        this.compactionInterval = interval;
        return this;
    }

    public ModifyLambdaRegistry getModifyLambdaRegistry() {
        return modifyLambdaRegistry;
    }

    public DurableSessionOption withModifyLambdaRegistry(final ModifyLambdaRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("ModifyLambdaRegistry must not be null");
        }
        this.modifyLambdaRegistry = registry;
        return this;
    }

    @Override
    public String toString() {
        return "DurableSessionOption(objectStorageMode=" + objectStorageMode +
                ", compactionInterval=" + compactionInterval +
                ", journalStorage=" + journalStorage + ")";
    }
}
