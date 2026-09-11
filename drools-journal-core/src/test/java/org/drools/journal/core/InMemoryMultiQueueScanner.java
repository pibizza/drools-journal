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

import java.util.List;

import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;

final class InMemoryMultiQueueScanner implements JournalScanner {

    private final List<Page> livePages;
    private int nextPageIndex;
    private int nextRecordIndex;
    private long syntheticPosition;
    private String currentPageId;
    private boolean closed;

    InMemoryMultiQueueScanner(final List<Page> livePages) {
        this.livePages = livePages;
        this.nextPageIndex = 0;
        this.nextRecordIndex = 0;
    }

    @Override
    public boolean hasNext() {
        return nextPageIndex < livePages.size() && nextRecordIndex < livePages.get(nextPageIndex).records.size();
    }

    @Override
    public JournalRecord next() {
    	if (!hasNext()) {
    		return null;
    	}
        JournalRecord record = livePages.get(nextPageIndex).records.get(nextRecordIndex);
        currentPageId = livePages.get(nextPageIndex).id;
        if (nextRecordIndex < livePages.get(nextPageIndex).records.size() - 1) {
			nextRecordIndex++;
		} else {
		    nextPageIndex++;
		    nextRecordIndex = 0;
		}
		syntheticPosition++;
		return record;
    }

    @Override
    public long position() {
        return syntheticPosition;
    }

    @Override
    public String currentPageId() {
        return currentPageId;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
        }
    }


}
