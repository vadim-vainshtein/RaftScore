package kz.vainshtein.raftscore.protocol;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/** Records corrections inside the result update transaction and invalidates the current document. */
@Service
@RequiredArgsConstructor
public class ProtocolCorrections {
    private final IssuedProtocolRepository protocols;
    private final JdbcTemplate jdbc;

    public void record(long competitionId, String reason, String detail) {
        protocols.findByCompetitionIdAndStaleFalse(competitionId).ifPresent(IssuedProtocol::markStale);
        jdbc.update("insert into protocol_corrections (competition_id, corrected_at, reason, detail) values (?, ?, ?, ?)",
                competitionId, java.sql.Timestamp.from(Instant.now()), reason, detail);
    }
}
