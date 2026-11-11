package kz.vainshtein.raftscore.scoring.entities;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlacementPointsTableRepository extends JpaRepository<PlacementPointsTable, Long> {
    java.util.List<PlacementPointsTable> findByCompetitionIdOrderByIdAsc(Long competitionId);
}
