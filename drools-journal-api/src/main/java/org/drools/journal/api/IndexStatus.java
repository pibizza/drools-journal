package org.drools.journal.api;

import java.util.Set;

public record IndexStatus(Set<String> livePages, Set<String> retiredPages) {}