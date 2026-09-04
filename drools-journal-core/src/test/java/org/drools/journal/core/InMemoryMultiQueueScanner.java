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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
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

    static class CatalogStatus {
    	private List<Page> livePages;
		private List<Page> retiredPages;

		CatalogStatus(List<Page> livePages, List<Page> retiredPages) {
			this.livePages = livePages;
			this.retiredPages = retiredPages;
    		
    	}

		public List<Page> getLivePages() {
			return livePages;
		}

		public List<Page> getRetiredPages() {
			return retiredPages;
		}
    }
    
    static InMemoryMultiQueueScanner create(final Page catalog, List<Page> pages) {
        CatalogStatus status = build(catalog, pages);
        return new InMemoryMultiQueueScanner(status.getLivePages());
    }

    static CatalogStatus build(Page catalog, List<Page> pages) {
    	List<Page> retiredPages = new ArrayList<>();
    	List<Page> livePages = new ArrayList<>();
    	Map<String, Page> pageIdToPage = new HashMap<>();
    	
    	for (Page page: pages) {
    		pageIdToPage.put(page.id, page);
    	}

    	List<Page> bufferedPages = new ArrayList<>();
    	
    	
		for (JournalRecord record: catalog.records) {
			if (record instanceof CompactionPrepareRecord cp) {
				// ignore CompactionPrepare. We don't need anything here.
			} else if (record instanceof CompactionCommitRecord cc) {
		    	List<String> pagesToSkip = List.of(cc.replacedPageIds());
				boolean addedMergedPage = false;
				for (Page bufferedPage: bufferedPages) {
					// the page is part of a Compaction Cycle? In that case we add only the merged page
					if (pagesToSkip.contains(bufferedPage.id)) {
						if (!addedMergedPage) {
							livePages.add(pageIdToPage.get(cc.mergedPageId()));
							addedMergedPage = true;
						}
						retiredPages.add(bufferedPage);
					} else {
						livePages.add(bufferedPage);
					}
				}
				// we copied all the pages in the live pages, so we can restart accumulating
				bufferedPages.clear();
				
			} else if (record instanceof PageRecord pr) {
				bufferedPages.add(pageIdToPage.get(pr.pageId()));
			} 
			
		}
		// if we have no or partial compaction, we would miss some of the pages.
		for (Page bufferedPage: bufferedPages) {
			livePages.add(bufferedPage);
		}
		return new CatalogStatus(livePages, retiredPages);
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
