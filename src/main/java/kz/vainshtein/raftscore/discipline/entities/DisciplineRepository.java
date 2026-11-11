package kz.vainshtein.raftscore.discipline.entities;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for disciplines. */
public interface DisciplineRepository extends JpaRepository<Discipline, Long> {
    java.util.List<Discipline> findByCompetitionIdOrderByDisplayOrderAscIdAsc(Long competitionId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from Discipline d where d.id = :id")
    java.util.Optional<Discipline> findForStartListUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Query("select d.id from Discipline d where d.competition.id = :competitionId order by d.id")
    java.util.List<Long> findIdsByCompetitionId(@org.springframework.data.repository.query.Param("competitionId") Long competitionId);

    java.util.List<Discipline> findByCompetitionIdAndTypeOrderByDisplayOrderAscIdAsc(Long competitionId, DisciplineType type);
}
