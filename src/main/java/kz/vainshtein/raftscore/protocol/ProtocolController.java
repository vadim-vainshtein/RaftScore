package kz.vainshtein.raftscore.protocol;

import java.time.Instant;
import java.util.List;
import kz.vainshtein.raftscore.competition.CompletionCheck;
import kz.vainshtein.raftscore.competition.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/competitions/{competitionId}/protocols")
@RequiredArgsConstructor
class ProtocolController {
    private final CompetitionRepository competitions;
    private final CompletionCheck completion;
    private final IssuedProtocolRepository protocols;
    private final ProtocolSnapshot snapshots;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    record Correction(long id, Instant correctedAt, String reason, JsonNode detail) { }
    record Edition(int edition, Instant issuedAt, boolean stale, JsonNode snapshot, List<Correction> changes) { }

    @GetMapping
    @Transactional(readOnly = true)
    List<Edition> history(@PathVariable long competitionId) {
        if (!competitions.existsById(competitionId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return protocols.findByCompetitionIdOrderByEditionDesc(competitionId).stream().map(this::view).toList();
    }

    @PostMapping
    @Transactional
    Edition issue(@PathVariable long competitionId) {
        var competition = competitions.findForDisciplineConfiguration(competitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (competition.getStatus() != CompetitionStatus.COMPLETED || !completion.competition(competitionId).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Competition results are incomplete");
        var current = protocols.findByCompetitionIdAndStaleFalse(competitionId);
        if (current.isPresent()) return view(current.get());
        var history = protocols.findByCompetitionIdOrderByEditionDesc(competitionId);
        var pending = pending(competitionId);
        var document = mapper.writeValueAsString(snapshots.capture(competition));
        var saved = protocols.saveAndFlush(new IssuedProtocol(competitionId,
                history.isEmpty() ? 1 : history.getFirst().getEdition() + 1, Instant.now(), document,
                mapper.writeValueAsString(pending)));
        jdbc.update("update protocol_corrections set issued_protocol_id = ? where competition_id = ? and issued_protocol_id is null", saved.getId(), competitionId);
        return view(saved);
    }

    private List<Correction> pending(long competitionId) {
        return jdbc.query("select id, corrected_at, reason, detail from protocol_corrections where competition_id = ? and issued_protocol_id is null order by id",
                (rs, row) -> new Correction(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), mapper.readTree(rs.getString(4))), competitionId);
    }

    private Edition view(IssuedProtocol protocol) {
        return new Edition(protocol.getEdition(), protocol.getIssuedAt(), protocol.isStale(), mapper.readTree(protocol.getSnapshot()),
                mapper.readValue(protocol.getChanges(), mapper.getTypeFactory().constructCollectionType(List.class, Correction.class)));
    }
}
