package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RecalculationServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired StartListRepository lists;
    @Autowired StartSlotRepository slots;
    @Autowired AttemptResultRepository attempts;
    @Autowired PlacementPointsTableRepository tables;
    @Autowired CalculatedDisciplineResultRepository results;
    @Autowired RecalculationService recalculation;
    @Autowired EntityManager em;
    @Autowired org.springframework.web.context.WebApplicationContext context;

    @Test @Transactional
    void correctionUpdatesPlacementsPointsAndRemovesStaleRows() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category));
        discipline.setPointsTable(tables.saveAndFlush(new PlacementPointsTable(competition, "Points", TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of(new PlacementPointsRow(1, 100), new PlacementPointsRow(2, 50)))));
        var list = lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        var one = crew(category, 1); var two = crew(category, 2);
        slots.save(StartSlot.builder().startList(list).crew(one).position(1).build());
        slots.save(StartSlot.builder().startList(list).crew(two).position(2).build());
        var first = attempts.save(AttemptResult.builder().discipline(discipline).crewEntry(one).attemptNumber(1).durationCentiseconds(100).status(AttemptResultStatus.FINISHED).build());
        var second = attempts.save(AttemptResult.builder().discipline(discipline).crewEntry(two).attemptNumber(1).durationCentiseconds(90).status(AttemptResultStatus.FINISHED).build());
        em.flush();
        recalculation.recalculateCompetition(competition.getId());
        em.flush(); em.clear();
        assertThat(results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(discipline.getId(), category.getId()))
                .extracting(r -> r.getCrewEntry().getId(), CalculatedDisciplineResult::getPlace, CalculatedDisciplineResult::getPoints)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(one.getId(), 2, 50), org.assertj.core.groups.Tuple.tuple(two.getId(), 1, 100));
        attempts.findById(first.getId()).orElseThrow().updateResult(80, null, null, 0, AttemptResultStatus.FINISHED);
        attempts.deleteById(second.getId());
        em.flush();
        recalculation.recalculateCompetition(competition.getId());
        em.flush(); em.clear();
        assertThat(results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(discipline.getId(), category.getId()))
                .extracting(r -> r.getCrewEntry().getId(), CalculatedDisciplineResult::getPlace, CalculatedDisciplineResult::getPoints)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(one.getId(), 1, 100));
    }
    @Test @Transactional
    void emptySlalomWithoutConfigurationAllowsRecalculationAndDisciplineUpdate() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Empty slalom").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category));
        em.flush();
        lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("FULL_ACCESS");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/competitions/{competitionId}/recalculate", competition.getId())
                .with(admin).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/competitions/{competitionId}/disciplines/{disciplineId}", competition.getId(), discipline.getId())
                .with(admin).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content("{\"displayOrder\":2,\"version\":" + discipline.getVersion() + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(discipline.getId(), category.getId())).isEmpty();
        assertThat(disciplines.findById(discipline.getId()).orElseThrow().getDisplayOrder()).isEqualTo(2);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/competitions/{competitionId}/recalculate", 999999999L)
                .with(admin).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    private CrewEntry crew(CompetitionCategory category, int number) {
        return crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Coach").startNumber(number).build());
    }
}
