package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RecalculationPerformanceIntegrationTest extends PostgreSqlIntegrationTest {
    private static final int CREWS = 100;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired StartListRepository lists;
    @Autowired StartSlotRepository slots;
    @Autowired AttemptResultRepository attempts;
    @Autowired PlacementPointsTableRepository tables;
    @Autowired RecalculationService recalculation;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fullCompetitionRecalculationCompletesWithinThreeSeconds() {
        long competitionId = transactions.execute(status -> createFixture());
        try {
            // Warm up the same transactional path, then change every input so the measured
            // invocation must update persisted results and points rather than be a no-op.
            recalculation.recalculateCompetition(competitionId);
            jdbc.update("update attempt_results set duration_centiseconds = 1000 - duration_centiseconds where discipline_id in (select id from disciplines where competition_id = ?)", competitionId);
            long start = System.nanoTime();
            recalculation.recalculateCompetition(competitionId);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.println("100-crew, two-discipline transactional recalculation: " + elapsedMillis + " ms; processors="
                    + Runtime.getRuntime().availableProcessors() + "; Java=" + System.getProperty("java.version")
                    + "; OS=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

            assertThat(jdbc.queryForObject("select count(*) from calculated_discipline_results r join disciplines d on d.id = r.discipline_id where d.competition_id = ?", Integer.class, competitionId)).isEqualTo(200);
            var placements = jdbc.queryForList("select c.start_number, r.place, r.points from calculated_discipline_results r join crew_entries c on c.id = r.crew_entry_id join disciplines d on d.id = r.discipline_id where d.competition_id = ? and c.start_number in (1, 100) order by d.id, c.start_number", competitionId);
            assertThat(placements).hasSize(4);
            for (var row : placements) {
                int number = ((Number) row.get("start_number")).intValue();
                assertThat(((Number) row.get("place")).intValue()).isEqualTo(number == 100 ? 1 : 100);
                assertThat(((Number) row.get("points")).intValue()).isEqualTo(number == 100 ? 100 : 1);
            }
            assertThat(elapsedMillis).as("full recalculation including transaction commit, in milliseconds").isLessThanOrEqualTo(3000);
        } finally {
            cleanup(competitionId);
        }
    }

    private long createFixture() {
        var competition = competitions.save(Competition.builder().title("Performance race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var entries = new ArrayList<CrewEntry>();
        for (int i = 1; i <= CREWS; i++) entries.add(crews.save(CrewEntry.builder().competitionCategory(category).crewName("Crew " + i).regionOrClub("Club").representative("Coach").startNumber(i).build()));
        for (var type : List.of(DisciplineType.SPRINT, DisciplineType.LONG_RACE)) {
            var discipline = disciplines.save(Discipline.builder().competition(competition).name(type.name()).type(type).build());
            discipline.replaceCategories(List.of(category));
            var pointRows = new ArrayList<PlacementPointsRow>();
            for (int i = 1; i <= CREWS; i++) pointRows.add(new PlacementPointsRow(i, 101 - i));
            discipline.setPointsTable(tables.save(new PlacementPointsTable(competition, type.name() + " points", TiePointsPolicy.SHARED_PLACE_SAME_POINTS, pointRows)));
            disciplines.flush();
            var list = lists.save(StartList.builder().discipline(discipline).category(category).build());
            for (int i = 1; i <= CREWS; i++) {
                var crew = entries.get(i - 1);
                slots.save(StartSlot.builder().startList(list).crew(crew).position(i).build());
                attempts.save(AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).durationCentiseconds(i).status(AttemptResultStatus.FINISHED).build());
            }
        }
        return competition.getId();
    }

    private void cleanup(long competitionId) {
        transactions.executeWithoutResult(status -> {
            var disciplineIds = "select id from disciplines where competition_id = ?";
            var listIds = "select id from start_lists where discipline_id in (" + disciplineIds + ")";
            jdbc.update("delete from calculated_discipline_results where discipline_id in (" + disciplineIds + ")", competitionId);
            jdbc.update("delete from attempt_results where discipline_id in (" + disciplineIds + ")", competitionId);
            jdbc.update("delete from start_slots where start_list_id in (" + listIds + ")", competitionId);
            jdbc.update("delete from start_lists where id in (" + listIds + ")", competitionId);
            jdbc.update("delete from crew_entries where competition_category_id in (select id from competition_categories where competition_id = ?)", competitionId);
            jdbc.update("delete from discipline_categories where discipline_id in (" + disciplineIds + ")", competitionId);
            jdbc.update("delete from disciplines where competition_id = ?", competitionId);
            jdbc.update("delete from placement_points_rows where table_id in (select id from placement_points_tables where competition_id = ?)", competitionId);
            jdbc.update("delete from placement_points_tables where competition_id = ?", competitionId);
            jdbc.update("delete from competition_categories where competition_id = ?", competitionId);
            jdbc.update("delete from competitions where id = ?", competitionId);
        });
    }
}
