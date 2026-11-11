package kz.vainshtein.raftscore.competition.entities;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryRequirementValueTest {
    @Test
    void missingValuesDependOnRequiredFlagForEveryType() {
        for (var type : EntryRequirementValueType.values()) {
            var requirement = requirement(type, false);
            assertThatCode(() -> requirement.validateValue(null)).doesNotThrowAnyException();
            requirement.setRequired(true);
            assertThatThrownBy(() -> requirement.validateValue(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsOnlyValidTypedValues() {
        assertValid(EntryRequirementValueType.YES_NO, true, false);
        assertValid(EntryRequirementValueType.TEXT, "answer", " text ");
        assertValid(EntryRequirementValueType.DATE, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31));
        assertValid(EntryRequirementValueType.NUMBER, BigDecimal.ZERO, new BigDecimal("-123.45"));
    }

    @Test
    void rejectsWrongTypesAndInvalidValuesEvenWhenOptional() {
        assertInvalid(EntryRequirementValueType.YES_NO, "true", 1, BigDecimal.ONE);
        assertInvalid(EntryRequirementValueType.TEXT, "", " \t\n", "a\0b", false);
        assertInvalid(EntryRequirementValueType.DATE, LocalDate.of(0, 12, 31), LocalDate.of(10000, 1, 1), "2026-01-01");
        assertInvalid(EntryRequirementValueType.NUMBER, 0, 1.0, "1", true);
    }

    private void assertValid(EntryRequirementValueType type, Object... values) {
        var requirement = requirement(type, true);
        for (var value : values) {
            assertThatCode(() -> requirement.validateValue(value)).doesNotThrowAnyException();
        }
    }

    private void assertInvalid(EntryRequirementValueType type, Object... values) {
        var requirement = requirement(type, false);
        for (var value : values) {
            assertThatThrownBy(() -> requirement.validateValue(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private EntryRequirement requirement(EntryRequirementValueType type, boolean required) {
        var competition = Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                .location("River").timeZone("Europe/London").build();
        return EntryRequirement.builder().competition(competition).name("Requirement")
                .valueType(type).required(required).build();
    }
}
