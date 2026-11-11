package kz.vainshtein.raftscore.catalog.entities;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Storage access for reusable category templates. */
public interface CategoryTemplateRepository extends JpaRepository<CategoryTemplate, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from CategoryTemplate t where t.id = :id")
    Optional<CategoryTemplate> findForSnapshot(@Param("id") Long id);

    Page<CategoryTemplate> findByArchived(boolean archived, Pageable pageable);

    boolean existsByClassLabelAndScoringGroupLabel(String classLabel, String scoringGroupLabel);
    boolean existsByClassLabelAndScoringGroupLabelAndIdNot(String classLabel, String scoringGroupLabel, Long id);
}
