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

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.SafepointRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PageIndex {

    record PageIndexStatus(Set<String> livePages, Set<String> retiredPages) {}

    private PageIndex() {}

    static PageIndexStatus buildLivePageSet(final JournalScanner scanner) {
        final List<String> pageIndex = new ArrayList<>();
        final Map<String, String[]> pendingCommits = new LinkedHashMap<>();
        final List<String> currentIntervalPages = new ArrayList<>();
        final Set<String> retiredPages = new HashSet<>();
        String lastPageId = null;

        while (scanner.hasNext()) {
            final JournalRecord record = scanner.next();
            final String pageId = scanner.currentPageId();

            if (!pageId.equals(lastPageId)) {
                currentIntervalPages.add(pageId);
                lastPageId = pageId;
            }

            if (record instanceof CompactionCommitRecord commit) {
                pendingCommits.put(commit.mergedPageId(), commit.replacedPageIds());
            } else if (record instanceof SafepointRecord) {
                for (final Map.Entry<String, String[]> e : pendingCommits.entrySet()) {
                    for (final String replaced : e.getValue()) {
                        retiredPages.add(replaced);
                    }
                    spliceIntoIndex(pageIndex, e.getKey(), e.getValue());
                }
                pendingCommits.clear();
                pageIndex.addAll(currentIntervalPages);
                currentIntervalPages.clear();
            }
        }

        return new PageIndexStatus(new HashSet<>(pageIndex), retiredPages);
    }

    static void spliceIntoIndex(final List<String> pageIndex,
                                final String mergedId,
                                final String[] replacedIds) {
        final Set<String> retired = Set.of(replacedIds);
        int insertPos = -1;
        for (int i = 0; i < pageIndex.size(); i++) {
            if (retired.contains(pageIndex.get(i))) {
                insertPos = i;
                break;
            }
        }
        pageIndex.removeIf(retired::contains);
        if (insertPos >= 0) {
            pageIndex.add(insertPos, mergedId);
        }
    }
}
