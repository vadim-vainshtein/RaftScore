package kz.vainshtein.raftscore.catalog.entities;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CategoryTemplateTest {
    @Test
    void buildsDisplayNameFromFreeLabels() {
        var template = CategoryTemplate.builder().classLabel("C1").scoringGroupLabel("W U.24").crewSize(1).build();
        assertThat(template.getDisplayName()).isEqualTo("C1 / W U.24");
        assertThat(template.isArchived()).isFalse();
    }

    @Test
    void rejectsInvalidValuesOnCreationAndUpdate() {
        assertThatIllegalArgumentException().isThrownBy(() -> CategoryTemplate.builder()
                .classLabel("R4").scoringGroupLabel("Men Open").crewSize(0).build());
        assertThatIllegalArgumentException().isThrownBy(() -> CategoryTemplate.builder()
                .classLabel(" ").scoringGroupLabel("Men Open").crewSize(4).build());
        var template = CategoryTemplate.builder().classLabel("R4").scoringGroupLabel("Men Open").crewSize(4).build();
        assertThatIllegalArgumentException().isThrownBy(() -> template.setCrewSize(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> template.setScoringGroupLabel(null));
        assertThat(template.getCrewSize()).isEqualTo(4);
        assertThat(template.getScoringGroupLabel()).isEqualTo("Men Open");
    }
}
