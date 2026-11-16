package kz.vainshtein.raftscore.catalog.controllers;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PointsCatalogIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired PointsCatalogController catalog;

    @Test
    @Transactional
    void presetsAndVersionedCatalogEdits() {
        var presets = catalog.list().stream().filter(PointsCatalogController.Table::preset).toList();
        assertThat(presets).hasSize(4);
        var slalom = presets.stream().filter(t -> t.disciplineType() == DisciplineType.SLALOM).findFirst().orElseThrow();
        assertThat(slalom.rows()).hasSize(20);
        assertThat(slalom.rows().getFirst().points()).isEqualTo(300);
        assertThat(slalom.rows().getLast().points()).isEqualTo(15);
        var created = catalog.create(new PointsCatalogController.Edit("Custom", DisciplineType.SPRINT, List.of(new PointsCatalogController.Row(1, 42)), null));
        var changed = catalog.update(created.id(), new PointsCatalogController.Edit("Changed", DisciplineType.SPRINT, List.of(new PointsCatalogController.Row(1, 50)), created.version()));
        assertThat(changed.version()).isEqualTo(1);
        assertThatThrownBy(() -> catalog.update(created.id(), new PointsCatalogController.Edit("Stale", DisciplineType.SPRINT, List.of(), created.version()))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(catalog.list().stream().filter(t -> t.id() == created.id()).findFirst().orElseThrow().rows()).containsExactly(new PointsCatalogController.Row(1, 50));
    }
}
