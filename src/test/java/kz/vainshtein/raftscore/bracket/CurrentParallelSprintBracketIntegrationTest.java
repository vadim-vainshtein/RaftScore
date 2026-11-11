package kz.vainshtein.raftscore.bracket;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class CurrentParallelSprintBracketIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired CalculatedDisciplineResultRepository results;
    @Autowired ParallelSprintBracketRepository brackets;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;

    @Test
    void scopeAuthorizationSeedAndAdvancement() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n = 1; n <= 4; n++) {
            var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + n).regionOrClub("Club").representative("Coach").startNumber(n).build());
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew).place(n).status(CalculatedResultStatus.FINISHED).build());
        }
        em.flush();
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var url = "/api/competitions/" + competition.getId() + "/disciplines/" + parallel.getId() + "/categories/" + category.getId() + "/bracket";
        var reader = user("reader").roles("READ_ONLY");
        var admin = user("admin").roles("FULL_ACCESS");
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(reader)).andExpect(status().isOk()).andExpect(jsonPath("$.heats.length()").value(0));
        mvc.perform(post(url).with(reader).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(url).with(admin).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.heats.length()").value(3));
        em.flush(); em.clear();
        var bracket = brackets.findByDisciplineIdAndCategoryId(parallel.getId(), category.getId()).orElseThrow();
        var opening = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).getFirst();
        var winnerId = opening.getCrew1().getId();
        var body = "{\"version\":" + opening.getVersion() + ",\"first\":{\"status\":\"FINISHED\",\"timeCentiseconds\":100},\"second\":{\"status\":\"FINISHED\",\"timeCentiseconds\":120}}";
        mvc.perform(put(url + "s/" + bracket.getId() + "/heats/" + opening.getId() + "/result").with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        em.flush(); em.clear();
        mvc.perform(get(url).with(reader)).andExpect(status().isOk()).andExpect(jsonPath("$.heats[0].winnerSlot").value(1)).andExpect(jsonPath("$.heats[2].first.id").value(winnerId));
        mvc.perform(get(url.replace("/competitions/" + competition.getId(), "/competitions/999999")).with(reader)).andExpect(status().isNotFound());
        mvc.perform(post(url).with(admin).with(csrf())).andExpect(status().isConflict());
    }

    private Discipline discipline(Competition competition, CompetitionCategory category, DisciplineType type) {
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name(type.name()).type(type).build());
        discipline.replaceCategories(List.of(category));
        return disciplines.saveAndFlush(discipline);
    }
}
