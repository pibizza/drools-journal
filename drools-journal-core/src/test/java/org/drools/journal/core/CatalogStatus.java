package org.drools.journal.core;

import java.util.List;

public class CatalogStatus {
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