package kz.vainshtein.raftscore.standings;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class DisciplineResultsIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired CalculatedDisciplineResultRepository results;
    @Autowired WebApplicationContext context;
    @Autowired AttemptResultRepository attempts;

    @Test @Transactional
    void exposesOnlyScopedCalculatedPublicDataToAuthenticatedReaders() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var other = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category, other));
        disciplines.saveAndFlush(discipline);
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew").regionOrClub("Club").representative("Private coach").startNumber(1).build());
        var otherCrew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(other).crewName("Other").regionOrClub("Club").representative("Coach").startNumber(2).build());
        attempts.saveAndFlush(AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).durationCentiseconds(7325).penaltyCentiseconds(100).status(AttemptResultStatus.FINISHED).build());
        results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(discipline).crewEntry(crew).place(1).totalCentiseconds(7425L).status(CalculatedResultStatus.FINISHED).points(100).build());
        results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(discipline).crewEntry(otherCrew).status(CalculatedResultStatus.DNF).build());
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var url = "/api/competitions/" + competition.getId() + "/disciplines/" + discipline.getId() + "/categories/" + category.getId() + "/results";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(user("reader").roles("READ_ONLY"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].crewName").value("Crew"))
                .andExpect(jsonPath("$[0].totalCentiseconds").value(7425)).andExpect(jsonPath("$[0].points").value(100))
                .andExpect(jsonPath("$[0].attempts[0].penaltyCentiseconds").value(100))
                .andExpect(jsonPath("$[0].attempts[0].startInstant").doesNotExist())
                .andExpect(jsonPath("$[0].representative").doesNotExist()).andExpect(jsonPath("$[0].summary").doesNotExist());
        mvc.perform(get(url.replace("/competitions/" + competition.getId(), "/competitions/999999")).with(user("reader").roles("READ_ONLY"))).andExpect(status().isNotFound());
    }
}
