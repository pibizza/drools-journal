/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.drools.journal.api;

/**
 * Written when a rule activation fires. The {@code id} is the surrogate key
 * referenced by logical {@link InsertRecord}s to identify their justifying
 * activation.
 *
 * @param id            auto-incrementing session-scoped counter assigned at write time
 * @param ruleName      name of the rule that fired
 * @param factHandleIds tuple of fact handle IDs that matched the rule's conditions
 */
public record RuleMatchRecord(
        long id,
        String ruleName,
        long[] factHandleIds) implements JournalRecord {
}
