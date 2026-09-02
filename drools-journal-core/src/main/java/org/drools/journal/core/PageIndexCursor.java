package org.drools.journal.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.core.PageIndex.PageIndexStatus;

class PageIndexCursor {
	private List<String> pageIndex;
	private Set<String> retiredPages;
	private Map<String, String[]> pendingCommits;
	private List<String> currentIntervalPages;
	private String lastPageId;

	PageIndexCursor() {
        pageIndex = new ArrayList<>();
        pendingCommits = new LinkedHashMap<>();
        currentIntervalPages = new ArrayList<>();
        retiredPages = new HashSet<>();
        lastPageId = null;
	}

	public PageIndexStatus getPageIndexStatus() {
		return new PageIndexStatus(new HashSet<>(pageIndex), retiredPages);
	}
	public void move(String pageId, JournalRecord record) {
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
                spliceIntoIndex(e.getKey(), e.getValue());
            }
            pendingCommits.clear();
            pageIndex.addAll(currentIntervalPages);
            currentIntervalPages.clear();
        }
	}

	void spliceIntoIndex(final String mergedId, final String[] replacedIds) {
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