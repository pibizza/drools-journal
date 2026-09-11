package org.drools.journal.api;

import java.util.List;

public record IndexStatus(List<String> livePageIds, List<String> retiredPageIds) {}