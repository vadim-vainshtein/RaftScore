package kz.vainshtein.raftscore.discipline.entities;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.scoring.entities.PlacementPointsTable;
import kz.vainshtein.raftscore.scoring.entities.TiePointsPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DisciplineTest {
    @Test
    void requiresSameTransientOwnerForPointsTable() {
        var owner = competition();
        var discipline = Discipline.builder().competition(owner).name("Sprint").type(DisciplineType.SPRINT).build();
        var sameOwnerTable = table(owner);
        discipline.setPointsTable(sameOwnerTable);
        assertThat(discipline.getPointsTable()).isSameAs(sameOwnerTable);
        assertThatThrownBy(() -> discipline.setPointsTable(table(competition())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(discipline.getPointsTable()).isSameAs(sameOwnerTable);
    }

    private Competition competition() {
        return Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1))
                .location("River").timeZone("UTC").build();
    }

    private PlacementPointsTable table(Competition owner) {
        return new PlacementPointsTable(owner, null, TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of());
    }
}
