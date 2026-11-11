package kz.vainshtein.raftscore.standings;

import java.util.List;

import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/competitions/{competitionId}/categories/{categoryId}/standings")
@RequiredArgsConstructor
class OverallStandingsController {
    private final CompetitionCategoryRepository categories;
    private final OverallStandingsService standings;

    @GetMapping
    ResponseEntity<?> get(@PathVariable Long competitionId, @PathVariable Long categoryId) {
        if (categories.findByIdAndCompetitionId(categoryId, competitionId).isEmpty()) {
            return ResponseEntity.status(404).body(ApiError.of("competition_category.not_found"));
        }
        return ResponseEntity.ok(standings.standings(categoryId));
    }
}
