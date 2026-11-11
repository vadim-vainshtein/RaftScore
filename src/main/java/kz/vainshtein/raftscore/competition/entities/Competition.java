package kz.vainshtein.raftscore.competition.entities;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import jakarta.persistence.Table;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The details of a competition, independent of its categories and disciplines. */
@Entity
@Table(name = "competitions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Competition extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String location;

    @Column(name = "time_zone", nullable = false, columnDefinition = "TEXT")
    private String timeZone;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "admission_confirmation_required", nullable = false)
    private boolean admissionConfirmationRequired;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tiebreak_discipline_id")
    private Discipline tiebreakDiscipline;

    public void setTiebreakDiscipline(Discipline discipline) {
        if (discipline != null && discipline.getCompetition() != this
                && (id == null || !id.equals(discipline.getCompetition().getId()))) {
            throw new IllegalArgumentException("Tiebreak discipline must belong to the competition");
        }
        this.tiebreakDiscipline = discipline;
    }

    @Builder
    private Competition(String title, LocalDate startDate, LocalDate endDate, String location,
                        String timeZone, String description, boolean admissionConfirmationRequired) {
        this.title = requireText(title, "title");
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.endDate = endDate == null ? startDate : endDate;
        if (this.endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not precede startDate");
        }
        this.location = requireText(location, "location");
        this.timeZone = requireTimeZone(timeZone);
        this.description = description;
        this.admissionConfirmationRequired = admissionConfirmationRequired;
    }

    /** Replaces editable details while enforcing creation invariants. */
    public void updateDetails(String title, LocalDate startDate, LocalDate endDate, String location,
                              String timeZone, String description, boolean admissionConfirmationRequired) {
        var validated = new Competition(title, startDate, endDate, location, timeZone, description,
                admissionConfirmationRequired);
        this.title = validated.title;
        this.startDate = validated.startDate;
        this.endDate = validated.endDate;
        this.location = validated.location;
        this.timeZone = validated.timeZone;
        this.description = validated.description;
        this.admissionConfirmationRequired = validated.admissionConfirmationRequired;
    }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireTimeZone(String timeZone) {
        // The JDK retains legacy SystemV IDs that are no longer in IANA TZDB.
        if (timeZone == null || timeZone.startsWith("SystemV/")
                || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new IllegalArgumentException("timeZone must be an IANA time zone ID");
        }
        return timeZone;
    }
}
