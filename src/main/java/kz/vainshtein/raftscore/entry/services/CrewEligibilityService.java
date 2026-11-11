package kz.vainshtein.raftscore.entry.services;

import java.util.stream.IntStream;
import kz.vainshtein.raftscore.competition.entities.EntryRequirementRepository;
import kz.vainshtein.raftscore.entry.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes eligibility from current competition configuration and crew data. */
@Service
@RequiredArgsConstructor
public class CrewEligibilityService {
    private final CrewMembershipRepository memberships;
    private final EntryRequirementRepository requirements;
    private final CrewRequirementValueRepository values;

    @Transactional(readOnly = true)
    public boolean isEligible(CrewEntry entry) {
        var category = entry.getCompetitionCategory();
        var competition = category.getCompetition();
        if (competition.isAdmissionConfirmationRequired() && !Boolean.TRUE.equals(entry.getAdmissionDecision())) return false;
        var roster = memberships.findByCrewEntryIdOrderByPositionAsc(entry.getId());
        if (roster.size() != category.getCrewSize()
                || !IntStream.range(0, roster.size()).allMatch(i -> roster.get(i).getPosition() == i + 1)
                || roster.stream().map(m -> m.getParticipant().getId()).distinct().count() != roster.size()) return false;
        var answers = values.findByCrewEntryId(entry.getId());
        return requirements.findByCompetitionId(competition.getId()).stream().filter(r -> r.isRequired())
                .allMatch(r -> answers.stream().anyMatch(a -> a.getRequirement().getId().equals(r.getId())
                        && a.getValueType() == r.getValueType()));
    }
}
