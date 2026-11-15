package com.platform;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ProbeEvidenceReader {
    static final int BATCH_SIZE = 100;
    private static final Set<String> FAILURES = Set.of("CONNECT_FAILED", "TIMEOUT", "HEALTH_STATUS_DOWN",
            "HTTP_UNEXPECTED_STATUS", "HEALTH_INVALID_RESPONSE");

    record Event(long id, boolean opening, String evidence, Instant sampledAt) {}
    record Batch(String stream, List<Event> events) {}

    Batch read(Path file, int port, long after) throws SQLException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Probe evidence file is missing");
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + "?mode=ro")) {
            db.setAutoCommit(false);
            try (var query = db.createStatement();
                 var row = query.executeQuery("""
                         SELECT stream_id, port FROM probe_identity CROSS JOIN probe_state
                         WHERE probe_identity.id=1 AND probe_state.id=1
                         """)) {
                if (!row.next() || row.getInt("port") != port) {
                    throw new IllegalArgumentException("Probe target does not match");
                }
                String stream = row.getString("stream_id");
                if (!UUID.fromString(stream).toString().equals(stream)) {
                    throw new IllegalArgumentException("Invalid evidence stream");
                }
                try (var tail = db.createStatement();
                     var latest = tail.executeQuery("SELECT COALESCE(MAX(id),0) FROM probe_events")) {
                    if (!latest.next() || latest.getLong(1) < after) {
                        throw new IllegalArgumentException("Probe evidence was truncated");
                    }
                }
                List<Event> events = new ArrayList<>();
                try (var statement = db.prepareStatement("""
                        SELECT id,kind,evidence,sampled_at FROM probe_events WHERE id>? ORDER BY id LIMIT ?
                        """)) {
                    statement.setLong(1, after);
                    statement.setInt(2, BATCH_SIZE);
                    try (var result = statement.executeQuery()) {
                        while (result.next()) {
                            String kind = result.getString("kind");
                            String evidence = result.getString("evidence");
                            boolean opening = "OPEN".equals(kind);
                            if (evidence == null || (opening ? !FAILURES.contains(evidence)
                                    : !"RECOVERED".equals(kind) || !"UP".equals(evidence))) {
                                throw new IllegalArgumentException("Invalid probe evidence");
                            }
                            events.add(new Event(result.getLong("id"), opening, evidence,
                                    Instant.parse(result.getString("sampled_at"))));
                        }
                    }
                }
                return new Batch(stream, List.copyOf(events));
            }
        }
    }
}
