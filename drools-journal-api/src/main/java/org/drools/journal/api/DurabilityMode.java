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

/**
 * Session durability mode, selected via {@link DurableSessionOption#withMode}.
 *
 * <p>{@code JOURNAL_DELTA} (modify-as-lambda, Phase 5) is reserved and will be
 * added when that mode lands; it is intentionally not declared yet.
 */
public enum DurabilityMode {

    /** Full append-only log with compaction — the default, current behaviour. */
    JOURNAL,

    /**
     * STORES_ONLY-parity mode offering O(live-facts) restore. Semantically a
     * JOURNAL session whose compaction policy periodically merges all live pages
     * into one snapshot page (see issue #64).
     */
    SNAPSHOT;
}
