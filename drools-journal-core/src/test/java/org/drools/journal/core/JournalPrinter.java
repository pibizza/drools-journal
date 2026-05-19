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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

/**
 * Renders a {@link JournalStorage} as a human-readable multiline string for use
 * in golden-output test assertions. One line per record; non-deterministic fields
 * (e.g. timestamps) are omitted.
 */
public final class JournalPrinter {

    private JournalPrinter() {}

    public static String print(final JournalStorage storage) {
        final StringBuilder sb = new StringBuilder();
        final var scanner = storage.scan(0);
        while (scanner.hasNext()) {
            sb.append(render(scanner.next())).append('\n');
        }
        return sb.toString();
    }

    private static String render(final JournalRecord record) {
        if (record instanceof InsertRecord r)            return renderInsert(r);
        if (record instanceof RetractRecord r)           return "RETRACT  id=" + r.factHandleId();
        if (record instanceof RuleMatchRecord r)         return renderMatch(r);
        if (record instanceof SafepointRecord r)         return "SAFEPOINT  seq=" + r.sequenceNo();
        if (record instanceof ModifyRecord r)            return "MODIFY  id=" + r.factHandleId() + "  lambda=" + r.lambdaClassRef();
        if (record instanceof CompactionPrepareRecord r) return "COMPACT_PREPARE  page=" + r.preparingPageId()
                + "  replacing=" + Arrays.toString(r.replacedPageIds());
        if (record instanceof CompactionCommitRecord r)  return "COMPACT_COMMIT  page=" + r.mergedPageId()
                + "  retired=" + Arrays.toString(r.replacedPageIds());
        return "UNKNOWN  " + record.getClass().getSimpleName();
    }

    private static String renderInsert(final InsertRecord r) {
        final StringBuilder sb = new StringBuilder("INSERT  id=").append(r.factHandleId());
        if (r.logical()) {
            sb.append("  logical  justifiedBy=").append(r.justifyingRuleMatchId());
        }
        sb.append("  ").append(renderPayload(r));
        return sb.toString();
    }

    private static String renderMatch(final RuleMatchRecord r) {
        final String facts = Arrays.stream(r.factHandleIds())
                .mapToObj(Long::toString)
                .collect(Collectors.joining(",", "[", "]"));
        return "MATCH  id=" + r.id() + "  pkg=" + r.packageName() + "  rule=" + r.ruleName() + "  facts=" + facts;
    }

    private static String renderPayload(final InsertRecord r) {
        if (r.payload() instanceof EmbeddedPayload p) {
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(p.bytes()))) {
                final Object obj = ois.readObject();
                return obj.getClass().getSimpleName() + "(" + obj + ")";
            } catch (IOException | ClassNotFoundException e) {
                return "EmbeddedPayload(unreadable)";
            }
        }
        if (r.payload() instanceof ExternalRef p) {
            return "ExternalRef(" + simpleClassName(p.typeName()) + "," + p.dbKey() + ")";
        }
        return r.payload().getClass().getSimpleName();
    }

    private static String simpleClassName(final String fqn) {
        final int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
