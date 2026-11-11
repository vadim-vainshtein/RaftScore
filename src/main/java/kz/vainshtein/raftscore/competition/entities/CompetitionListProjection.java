package kz.vainshtein.raftscore.competition.entities;

import java.time.LocalDate;

/** Database-backed fields needed for the competition list. */
public interface CompetitionListProjection {
    Long getId();
    String getTitle();
    LocalDate getStartDate();
    LocalDate getEndDate();
    String getLocation();
    String getTimeZone();
    long getCategoryCount();
    long getCrewCount();
}
