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
 * Written when a fact is inserted into the session, or when a plain
 * {@code update()} is called (full-object snapshot fallback).
 *
 * @param factHandleId            stable identity of the FactHandle within the session
 * @param logical                 {@code true} if inserted via {@code insertLogical()}
 * @param justifyingRuleMatchId   ID of the {@link RuleMatchRecord} that caused this
 *                                logical insert; {@code -1} when {@code logical} is {@code false}
 * @param payload                 object data inline or reference to an external store
 */
public record InsertRecord(
        long factHandleId,
        boolean logical,
        long justifyingRuleMatchId,
        Payload payload) implements JournalRecord {
}
