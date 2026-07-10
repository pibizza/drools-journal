# drools-journal

Append-only journal for durable `KieSession` survival across JVM restarts.
A new persistence module for Drools, parallel to `drools-reliability`, with
lower write overhead through sequential I/O and (future) modify-as-delta
record encoding.

Upstream tracking issue: [apache/incubator-kie-drools#6682](https://github.com/apache/incubator-kie-drools/issues/6682)

## How it works

Instead of serialising full object snapshots into a key-value store,
drools-journal appends small typed records (`InsertRecord`, `RetractRecord`,
`ModifyRecord`, `RuleMatchRecord`, `SafepointRecord`) to an ordered log.
On restore it replays the log to reconstruct session state, using a
`ReplayFilter` to suppress rules that already fired.

Key properties:

- **Append-only writes** — sequential I/O, no read-modify-write cycle
- **Safepoint-based crash consistency** — roll back to the last checkpoint
- **Background compaction** — merges sparse pages via a four-step PREPARE/COMMIT protocol
- **TMS support** — logical inserts and justifications survive restart
- **Pluggable storage** — Chronicle Queue (implemented), Aeron Archive (planned)

## Modules

```
drools-journal-api/          # JournalStorage SPI, sealed record hierarchy, config
drools-journal-core/         # Session hooks, restore engine, compaction coordinator
drools-journal-chronicle/    # Chronicle Queue OSS backend (single-host)
drools-journal-aeron/        # Aeron Archive backend (planned)
drools-journal-tests/        # Integration and contract tests
```

## Requirements

- Java 21+
- Maven 3.9+

## Build

```bash
mvn clean install
```

The Chronicle backend requires JVM flags for memory-mapped file access.
These are configured in the parent POM's `maven-surefire-plugin` and
`maven-failsafe-plugin` sections.

## Usage

### With Chronicle Queue (production)

```java
KieBase kbase = new KieHelper()
        .addContent(drl, ResourceType.DRL)
        .build();

// Open a journalled session — creates or restores from existing journal
try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath("/var/journal");
     JournalledKieSession session = JournalledSessionFactory.open(kbase, storage)) {

    session.insert(new Order(42, "widgets", 100));
    session.fireAllRules();  // appends records + safepoint

}   // session.dispose() stops compaction; storage.close() flushes Chronicle

// Later — or after a JVM restart:
try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath("/var/journal");
     JournalledKieSession session = JournalledSessionFactory.open(kbase, storage)) {

    // Working memory is restored; rules that already fired are suppressed
    session.getObjects();  // → [Order(42, "widgets", 100)]
    session.fireAllRules(); // → 0 (already fired)
}
```

### Compaction interval

The default compaction interval is 60 seconds. Use `Duration.ZERO` to
disable background compaction (useful in tests):

```java
JournalledKieSession session = JournalledSessionFactory.open(
        kbase, storage, Duration.ZERO);
```

## Project journal

The `blog/` directory contains a chronological narrative of design decisions,
TDD discoveries, and implementation progress. The `adr/` directory holds
formal architecture decision records.

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
