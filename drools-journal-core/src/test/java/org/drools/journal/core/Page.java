package org.drools.journal.core;

import java.util.ArrayList;
import java.util.List;

import org.drools.journal.api.JournalRecord;

final class Page {
    final String id;
    final List<JournalRecord> records = new ArrayList<>();

    Page(final String id) {
        this.id = id;
    }
}