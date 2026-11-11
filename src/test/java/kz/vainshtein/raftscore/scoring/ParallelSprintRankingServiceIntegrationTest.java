package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ParallelSprintRankingServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired ParallelSprintBracketRepository brackets;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired ParallelSprintRankingService ranking;
    @Autowired EntityManager entityManager;

    @Test @Transactional
    void ranksPersistedResultsWithinTheRequestedBracket() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Parallel").type(DisciplineType.PARALLEL_SPRINT).build());
        discipline.replaceCategories(List.of(category));
        disciplines.flush();
        var bracket = brackets.saveAndFlush(ParallelSprintBracket.builder().discipline(discipline).category(category).build());
        var one = crew(category, 1); var two = crew(category, 2);
        heats.saveAndFlush(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(one).crew2(two)
                .status1(AttemptResultStatus.FINISHED).time1Centiseconds(100).status2(AttemptResultStatus.FINISHED).time2Centiseconds(110).winnerSlot(1).build());
        var bracketId = bracket.getId(); var oneId = one.getId(); var twoId = two.getId();
        entityManager.clear();
        var results = ranking.rank(bracketId);
        assertThat(results).extracting(r -> r.crew().getId()).containsExactly(oneId, twoId);
        assertThat(results).extracting(ParallelSprintRanking.Result::place).containsExactly(1, 2);
    }

    private CrewEntry crew(CompetitionCategory category, int number) {
        return crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Person").startNumber(number).build());
    }
}
