package org.drools.journal.core;

import org.drools.journal.api.JournalRecord;

public record PageRecord(String pageId) implements JournalRecord {};