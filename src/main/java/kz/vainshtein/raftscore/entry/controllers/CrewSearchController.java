package kz.vainshtein.raftscore.entry.controllers;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import kz.vainshtein.raftscore.entry.entities.CrewMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/crew-search")
@RequiredArgsConstructor
class CrewSearchController {
    private final CrewEntryRepository entries;
    private final CrewMembershipRepository memberships;

    record SearchResult(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub, LocalDate startDate, String competitionTitle, String categoryName, int startNumber, int crewSize, java.time.Instant registeredAt, List<CrewEntryController.MemberResponse> roster) { }

    record PublicSearchResult(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub, LocalDate startDate, String competitionTitle, String categoryName, int startNumber, int crewSize, java.time.Instant registeredAt, Integer rosterVariant) { }

    @GetMapping("/public")
    @Transactional(readOnly = true)
    List<PublicSearchResult> publicSearch(@RequestParam(required = false) String name, @RequestParam(required = false) Long categoryId) {
        var matches = findMatches(name, categoryId);
        var rosters = loadRosters(matches);
        if (categoryId != null) matches = distinctVersions(matches, rosters);
        var variants = rosterVariants(matches, rosters);
        return matches.stream().map(entry -> {
            var category = entry.getCompetitionCategory();
            var competition = category.getCompetition();
            return new PublicSearchResult(entry.getId(), competition.getId(), category.getId(), entry.getCrewName(), entry.getRegionOrClub(), competition.getStartDate(), competition.getTitle(), category.getDisplayName(), entry.getStartNumber(), category.getCrewSize(), entry.getRegisteredAt(), variants.get(entry.getId()));
        }).toList();
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<SearchResult> search(@RequestParam(required = false) String name, @RequestParam(required = false) Long categoryId) {
        var matches = findMatches(name, categoryId);
        if (matches.isEmpty()) return List.of();
        var rosters = loadRosters(matches);
        if (categoryId != null) matches = distinctVersions(matches, rosters);
        return matches.stream().map(entry -> {
            var category = entry.getCompetitionCategory();
            var competition = category.getCompetition();
            var roster = rosters.getOrDefault(entry.getId(), List.of()).stream().map(CrewEntryController.MemberResponse::from).toList();
            return new SearchResult(entry.getId(), competition.getId(), category.getId(), entry.getCrewName(), entry.getRegionOrClub(), competition.getStartDate(), competition.getTitle(), category.getDisplayName(), entry.getStartNumber(), category.getCrewSize(), entry.getRegisteredAt(), roster);
        }).toList();
    }
    private List<kz.vainshtein.raftscore.entry.entities.CrewEntry> findMatches(String name, Long categoryId) {
        if (categoryId != null) return entries.findHistoryForCategory(categoryId);
        if (name == null || name.isBlank() || name.indexOf(0) >= 0) return List.of();
        return entries.searchByName(name.trim());
    }
    private java.util.Map<Long, List<kz.vainshtein.raftscore.entry.entities.CrewMembership>> loadRosters(List<kz.vainshtein.raftscore.entry.entities.CrewEntry> matches) {
        if (matches.isEmpty()) return java.util.Map.of();
        return memberships.findByCrewEntryIdInOrderByPositionAsc(matches.stream().map(entry -> entry.getId()).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(member -> member.getCrewEntry().getId()));
    }

    private record CrewVersion(String name, String club, int crewSize, int startNumber, String representative,
                               Integer representativePosition, java.util.Map<String, String> contacts, List<Long> roster) { }

    private List<kz.vainshtein.raftscore.entry.entities.CrewEntry> distinctVersions(
            List<kz.vainshtein.raftscore.entry.entities.CrewEntry> matches,
            java.util.Map<Long, List<kz.vainshtein.raftscore.entry.entities.CrewMembership>> rosters) {
        var seen = new java.util.HashSet<CrewVersion>();
        return matches.stream().filter(entry -> seen.add(new CrewVersion(
                entry.getCrewName(), entry.getRegionOrClub(), entry.getCompetitionCategory().getCrewSize(), entry.getStartNumber(),
                entry.getRepresentative(), entry.getRepresentativePosition(), entry.getRepresentativeContacts(),
                rosters.getOrDefault(entry.getId(), List.of()).stream().map(member -> member.getParticipant().getId()).toList())))
                .toList();
    }
    private java.util.Map<Long, Integer> rosterVariants(List<kz.vainshtein.raftscore.entry.entities.CrewEntry> matches, java.util.Map<Long, List<kz.vainshtein.raftscore.entry.entities.CrewMembership>> rosters) {
        var groups = new java.util.LinkedHashMap<String, java.util.LinkedHashMap<List<Long>, Integer>>();
        for (var entry : matches) {
            var key = entry.getCrewName().trim().toLowerCase(java.util.Locale.ROOT);
            var roster = rosters.getOrDefault(entry.getId(), List.of()).stream().map(member -> member.getParticipant().getId()).toList();
            var group = groups.computeIfAbsent(key, ignored -> new java.util.LinkedHashMap<>());
            group.computeIfAbsent(roster, ignored -> group.size() + 1);
        }
        var result = new java.util.HashMap<Long, Integer>();
        for (var entry : matches) {
            var group = groups.get(entry.getCrewName().trim().toLowerCase(java.util.Locale.ROOT));
            if (group.size() > 1) {
                var roster = rosters.getOrDefault(entry.getId(), List.of()).stream().map(member -> member.getParticipant().getId()).toList();
                result.put(entry.getId(), group.get(roster));
            }
        }
        return result;
    }

}
