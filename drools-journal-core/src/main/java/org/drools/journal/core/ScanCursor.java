package org.drools.journal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.ModifyLambda;
import org.drools.journal.api.ModifyLambdaRegistry;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.core.RestoreEngine.PendingTmsLink;
import org.drools.journal.core.RestoreEngine.ScanResult;

public class ScanCursor {
    private final ModifyLambdaRegistry lambdaRegistry;
	private final ObjectStorageStrategy strategy;
	private final Map<Long, Object> survivingFacts;
	private final List<PendingTmsLink> pendingTmsLinks;
	private final List<RuleMatchRecord> firedMatches;
	private final Map<Long, RuleMatchRecord> firedMatchesById;

	ScanCursor(final ModifyLambdaRegistry lambdaRegistry,
            final ObjectStorageStrategy strategy) {
				this.lambdaRegistry = lambdaRegistry;
				this.strategy = strategy;
	        survivingFacts = new HashMap<>();
	        firedMatches = new ArrayList<>();
	        pendingTmsLinks = new ArrayList<>();
	        firedMatchesById = new HashMap<>();
    }

	public void move(final JournalRecord record, final String pageId) {
        if (record instanceof SafepointRecord) {
        	// Nothing to do here
        } else if (record instanceof CompactionPrepareRecord
                || record instanceof CompactionCommitRecord) {
            // compaction markers — no action
        } else if (record instanceof InsertRecord insert) {
            survivingFacts.put(insert.factHandleId(), strategy.load(insert.payload()));
            if (insert.logical()) {
                pendingTmsLinks.add(new PendingTmsLink(insert.factHandleId(), insert.justifyingRuleMatchId()));
            }
        } else if (record instanceof RetractRecord retract) {
            survivingFacts.remove(retract.factHandleId());
            pendingTmsLinks.removeIf(link -> link.factHandleId() == retract.factHandleId());
        } else if (record instanceof RuleMatchRecord match) {
            firedMatches.add(match);
            firedMatchesById.put(match.id(), match);
        } else if (record instanceof ModifyRecord modify) {
            ModifyLambda lambda = lambdaRegistry.lookup(modify.lambdaClassRef());
            Object fact = survivingFacts.get(modify.factHandleId());
            if (fact != null) {
                Object[] params = (Object[]) JavaSerializer.deserialize(modify.parameters());
                lambda.apply(fact, params);
            }
        }

	}
	
	public ScanResult getScanResult() {
		return new ScanResult(survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
	}
}