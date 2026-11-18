package kz.vainshtein.raftscore.entry.services;

import java.util.stream.IntStream;
import kz.vainshtein.raftscore.competition.entities.EntryRequirementRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.entry.entities.CrewMembershipRepository;
import kz.vainshtein.raftscore.entry.entities.CrewRequirementValueRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import kz.vainshtein.raftscore.entry.entities.CrewMembership;
import kz.vainshtein.raftscore.entry.entities.CrewRequirementValue;
import kz.vainshtein.raftscore.competition.entities.EntryRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes eligibility from current competition configuration and crew data. */
@Service
@RequiredArgsConstructor
public class CrewEligibilityPolicy {
    private final CrewMembershipRepository memberships;
    private final EntryRequirementRepository requirements;
    private final CrewRequirementValueRepository values;
    private final CrewEntryRepository entries;

    @Transactional(readOnly = true)
    public boolean isEligible(CrewEntry entry) {
        var competition = entry.getCompetitionCategory().getCompetition();
        return isEligible(entry, memberships.findByCrewEntryIdOrderByPositionAsc(entry.getId()),
                values.findByCrewEntryId(entry.getId()), requirements.findByCompetitionId(competition.getId()));
    }

    @Transactional(readOnly = true)
    public java.util.Set<Long> eligibleIds(long categoryId) {
        var crews = entries.findByCompetitionCategoryId(categoryId);
        if (crews.isEmpty()) return java.util.Set.of();
        var ids = crews.stream().map(CrewEntry::getId).toList();
        var rosters = memberships.findByCrewEntryIdInOrderByPositionAsc(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(m -> m.getCrewEntry().getId()));
        var answers = values.findByCrewEntryIdIn(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(v -> v.getCrewEntry().getId()));
        var required = requirements.findByCompetitionId(crews.getFirst().getCompetitionCategory().getCompetition().getId());
        return crews.stream().filter(c -> isEligible(c, rosters.getOrDefault(c.getId(), java.util.List.of()),
                        answers.getOrDefault(c.getId(), java.util.List.of()), required))
                .map(CrewEntry::getId).collect(java.util.stream.Collectors.toSet());
    }

    private boolean isEligible(CrewEntry entry, java.util.List<CrewMembership> roster,
                               java.util.List<CrewRequirementValue> answers, java.util.List<EntryRequirement> required) {
        var category = entry.getCompetitionCategory();
        var competition = category.getCompetition();
        if (competition.isAdmissionConfirmationRequired() && !Boolean.TRUE.equals(entry.getAdmissionDecision())) return false;
        if (roster.size() != category.getCrewSize()
                || !IntStream.range(0, roster.size()).allMatch(i -> roster.get(i).getPosition() == i + 1)
                || roster.stream().map(m -> m.getParticipant().getId()).distinct().count() != roster.size()) return false;
        return required.stream().filter(r -> r.isRequired())
                .allMatch(r -> answers.stream().anyMatch(a -> a.getRequirement().getId().equals(r.getId())
                        && a.getValueType() == r.getValueType()));
    }
}
