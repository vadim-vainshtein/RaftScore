package kz.vainshtein.raftscore.competition.entities;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Serializes result mutations with lifecycle transitions. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class CompetitionLifecycle {
    private final CompetitionRepository competitions;
    private final jakarta.persistence.EntityManager entityManager;
    public void requireEditable(long id) {
        var competition = competitions.findForDisciplineConfiguration(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        entityManager.refresh(competition);
        if (competition.getStatus() == CompetitionStatus.COMPLETED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Competition is completed");
    }
    public void requireStarted(long id) {
        var competition = competitions.findForDisciplineConfiguration(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (competition.getStatus() != CompetitionStatus.STARTED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Competition is not started");
    }
}
