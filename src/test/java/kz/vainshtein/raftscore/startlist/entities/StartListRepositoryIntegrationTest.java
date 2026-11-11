package kz.vainshtein.raftscore.startlist.entities;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class StartListRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired StartListRepository lists;
    @Autowired StartSlotRepository slots;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test @Transactional
    void persistsOrderedSlotsAndEnforcesConstraints() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("Europe/London").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var other = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category));
        em.flush();
        var list = lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        var first = crew(category, 1);
        var second = crew(category, 2);
        var foreign = crew(other, 3);
        var unused = crew(category, 4);
        var time = Instant.parse("2026-07-01T10:15:30Z");
        slots.save(StartSlot.builder().startList(list).crew(second).position(2).build());
        slots.saveAndFlush(StartSlot.builder().startList(list).crew(first).position(1).plannedTime(time).build());
        em.clear();
        var found = slots.findByStartListIdOrderByPositionAsc(list.getId());
        assertThat(found).extracting(StartSlot::getPosition).containsExactly(1, 2);
        assertThat(found.get(0).getPlannedTime()).isEqualTo(time);
        assertThat(found.get(1).getPlannedTime()).isNull();
        assertThat(found.get(0).getCrew().getId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> StartSlot.builder().startList(list).crew(foreign).position(3).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StartSlot.builder().startList(list).crew(first).position(0).build()).isInstanceOf(IllegalArgumentException.class);
        invalid("INSERT INTO start_lists (discipline_id, category_id) VALUES (?, ?)", discipline.getId(), category.getId());
        invalid("INSERT INTO start_lists (discipline_id, category_id) VALUES (?, ?)", discipline.getId(), other.getId());
        var insert = "INSERT INTO start_slots (start_list_id, category_id, crew_entry_id, position) VALUES (?, ?, ?, ?)";
        invalid(insert, list.getId(), category.getId(), second.getId(), 1);
        invalid(insert, list.getId(), category.getId(), first.getId(), 3);
        invalid(insert, list.getId(), category.getId(), foreign.getId(), 3);
        invalid(insert, list.getId(), other.getId(), foreign.getId(), 3);
        invalid(insert, list.getId(), category.getId(), unused.getId(), 0);
    }

    private CrewEntry crew(CompetitionCategory category, int number) {
        return crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Coach").startNumber(number).build());
    }

    private void invalid(String sql, Object... args) throws Exception {
        var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
        var savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
