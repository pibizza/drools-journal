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
 * Snapshot of current page state passed to a {@link PageRollStrategy} after
 * each append. All values reflect the state <em>after</em> the record has been
 * written.
 */
public interface PageContext {

    /** The record that was just appended. */
    JournalRecord lastRecord();

    /** Total bytes written to the current page so far. */
    long currentPageBytes();

    /** Number of records written to the current page so far. */
    long currentRecordCount();
}
