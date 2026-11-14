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

    record SearchResult(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub, LocalDate startDate, String competitionTitle, String categoryName, int startNumber, List<CrewEntryController.MemberResponse> roster) { }

    record PublicSearchResult(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub, LocalDate startDate, String competitionTitle, String categoryName, int startNumber, Integer rosterVariant) { }

    @GetMapping("/public")
    @Transactional(readOnly = true)
    List<PublicSearchResult> publicSearch(@RequestParam String name) {
        if (name.isBlank() || name.indexOf(0) >= 0) return List.of();
        var matches = entries.searchByName(name.trim());
        var rosters = loadRosters(matches);
        var variants = rosterVariants(matches, rosters);
        return matches.stream().map(entry -> {
            var category = entry.getCompetitionCategory();
            var competition = category.getCompetition();
            return new PublicSearchResult(entry.getId(), competition.getId(), category.getId(), entry.getCrewName(), entry.getRegionOrClub(), competition.getStartDate(), competition.getTitle(), category.getDisplayName(), entry.getStartNumber(), variants.get(entry.getId()));
        }).toList();
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<SearchResult> search(@RequestParam String name) {
        if (name.isBlank() || name.indexOf(0) >= 0) return List.of();
        var matches = entries.searchByName(name.trim());
        if (matches.isEmpty()) return List.of();
        var rosters = memberships.findByCrewEntryIdInOrderByPositionAsc(matches.stream().map(entry -> entry.getId()).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(member -> member.getCrewEntry().getId()));
        return matches.stream().map(entry -> {
            var category = entry.getCompetitionCategory();
            var competition = category.getCompetition();
            var roster = rosters.getOrDefault(entry.getId(), List.of()).stream().map(CrewEntryController.MemberResponse::from).toList();
            return new SearchResult(entry.getId(), competition.getId(), category.getId(), entry.getCrewName(), entry.getRegionOrClub(), competition.getStartDate(), competition.getTitle(), category.getDisplayName(), entry.getStartNumber(), roster);
        }).toList();
    }
    private java.util.Map<Long, List<kz.vainshtein.raftscore.entry.entities.CrewMembership>> loadRosters(List<kz.vainshtein.raftscore.entry.entities.CrewEntry> matches) {
        if (matches.isEmpty()) return java.util.Map.of();
        return memberships.findByCrewEntryIdInOrderByPositionAsc(matches.stream().map(entry -> entry.getId()).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(member -> member.getCrewEntry().getId()));
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
