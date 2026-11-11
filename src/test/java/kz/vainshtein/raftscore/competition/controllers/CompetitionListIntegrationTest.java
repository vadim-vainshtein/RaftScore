package kz.vainshtein.raftscore.competition.controllers;

import java.time.Instant;
import java.time.LocalDate;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionListProjection;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class CompetitionListIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired CrewEntryRepository crews;

    @Test void listsForBothRolesWithSearchPagingCountsAndStableSort() throws Exception {
        var date = LocalDate.of(2026, 5, 1);
        var first = competition("Unique list race A", date);
        competition("Unique list race B", date);
        var later = competition("Unique list race C", date.plusDays(1));
        competition("Unrelated event", date);
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(first)
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        categories.saveAndFlush(CompetitionCategory.builder().competition(first)
                .classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew")
                .regionOrClub("Club").representative("Person").startNumber(1).build());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/competitions")).andExpect(status().isUnauthorized());
        for (var role : new String[]{"READ_ONLY", "FULL_ACCESS"}) {
            mvc.perform(get("/api/competitions").with(user("user").roles(role))
                            .param("search", "UNIQUE LIST RACE").param("size", "2").param("sort", "ASC"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content[0].id").value(first.getId()))
                    .andExpect(jsonPath("$.content[0].categoryCount").value(2)).andExpect(jsonPath("$.content[0].crewCount").value(1))
                    .andExpect(jsonPath("$.content[0].description").doesNotExist());
            mvc.perform(get("/api/competitions").with(user("user").roles(role))
                            .param("search", "Unique list race").param("size", "2").param("sort", "DESC"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.content[0].id").value(later.getId()));
            mvc.perform(get("/api/competitions").with(user("user").roles(role))
                            .param("search", "Unique list race").param("size", "1").param("page", "1").param("sort", "ASC"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].title").value("Unique list race B"));
        }
        for (var query : new String[]{"?page=-1", "?size=101", "?page=abc", "?sort=bad"}) {
            mvc.perform(get("/api/competitions" + query).with(user("user").roles("READ_ONLY")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
        }
    }

    @Test void computesInclusiveStateUsingCompetitionZone() {
        var item = mock(CompetitionListProjection.class);
        when(item.getStartDate()).thenReturn(LocalDate.of(2026, 5, 1));
        when(item.getEndDate()).thenReturn(LocalDate.of(2026, 5, 2));
        when(item.getTimeZone()).thenReturn("Pacific/Auckland");
        assertThat(CompetitionController.CompetitionListItem.from(item, Instant.parse("2026-04-30T11:59:59Z")).state())
                .isEqualTo(CompetitionController.CompetitionState.UPCOMING);
        assertThat(CompetitionController.CompetitionListItem.from(item, Instant.parse("2026-04-30T12:00:00Z")).state())
                .isEqualTo(CompetitionController.CompetitionState.IN_PROGRESS);
        assertThat(CompetitionController.CompetitionListItem.from(item, Instant.parse("2026-05-02T11:59:59Z")).state())
                .isEqualTo(CompetitionController.CompetitionState.IN_PROGRESS);
        assertThat(CompetitionController.CompetitionListItem.from(item, Instant.parse("2026-05-02T12:00:00Z")).state())
                .isEqualTo(CompetitionController.CompetitionState.COMPLETED);
    }

    private Competition competition(String title, LocalDate date) {
        return competitions.saveAndFlush(Competition.builder().title(title).startDate(date).location("River")
                .timeZone("UTC").description("private list detail").build());
    }
}
