package kz.vainshtein.raftscore.competition.entities;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Storage access for competitions. */
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Competition c where c.id = :id")
    java.util.Optional<Competition> findForDisciplineConfiguration(@Param("id") Long id);

    @Query(value = """
            select c.id as id, c.title as title, c.startDate as startDate, c.endDate as endDate,
                   c.location as location, c.timeZone as timeZone,
                   (select count(category) from CompetitionCategory category where category.competition = c) as categoryCount,
                   (select count(crew) from CrewEntry crew where crew.competitionCategory.competition = c) as crewCount
            from Competition c
            where :search = '' or locate(lower(:search), lower(c.title)) > 0
            """, countQuery = """
            select count(c) from Competition c
            where :search = '' or locate(lower(:search), lower(c.title)) > 0
            """)
    Page<CompetitionListProjection> findList(@Param("search") String search, Pageable pageable);
}
