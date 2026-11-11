package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SprintRankingServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired StartListRepository lists;
    @Autowired StartSlotRepository slots;
    @Autowired AttemptResultRepository attempts;
    @Autowired SprintRankingService service;
    @Autowired EntityManager em;

    @Test @Transactional
    void ranksPersistedAttemptsWithinTheStartListCategory() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var otherCategory = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category, otherCategory));
        em.flush();
        var list = lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        var first = crew(category, 1); var second = crew(category, 2); var other = crew(otherCategory, 3);
        slots.save(StartSlot.builder().startList(list).crew(first).position(1).build());
        slots.save(StartSlot.builder().startList(list).crew(second).position(2).build());
        attempts.save(AttemptResult.builder().discipline(discipline).crewEntry(first).attemptNumber(1).durationCentiseconds(100).status(AttemptResultStatus.FINISHED).build());
        attempts.save(AttemptResult.builder().discipline(discipline).crewEntry(second).attemptNumber(1).durationCentiseconds(90).status(AttemptResultStatus.FINISHED).build());
        attempts.saveAndFlush(AttemptResult.builder().discipline(discipline).crewEntry(other).attemptNumber(1).durationCentiseconds(80).status(AttemptResultStatus.FINISHED).build());
        em.clear();
        var ranked = service.rank(list.getId());
        assertThat(ranked).extracting(result -> result.crew().getId()).containsExactly(second.getId(), first.getId());
        assertThat(ranked).extracting(SprintRanking.Result::place).containsExactly(1, 2);
    }

    private CrewEntry crew(CompetitionCategory category, int number) {
        return crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Coach").startNumber(number).build());
    }
}
