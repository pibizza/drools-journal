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
 * Written when a DRL {@code modify} block executes in durable mode (requires
 * the upstream compiler rewrite — see plan Task 15). Records the change rather
 * than the full updated object.
 *
 * <p>Until the compiler rewrite is available, {@code update()} calls fall back
 * to writing a full {@link InsertRecord} snapshot.
 *
 * @param factHandleId   fact being modified
 * @param lambdaClassRef deterministic compiler-generated name, e.g. {@code Rule_MyRule_modify_0}
 * @param parameters     serialized values of each property being assigned
 */
public record ModifyRecord(
        long factHandleId,
        String lambdaClassRef,
        byte[] parameters) implements JournalRecord {
}
