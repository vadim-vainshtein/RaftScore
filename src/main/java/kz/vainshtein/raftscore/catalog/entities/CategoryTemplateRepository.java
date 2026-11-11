package kz.vainshtein.raftscore.catalog.entities;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for reusable category templates. */
public interface CategoryTemplateRepository extends JpaRepository<CategoryTemplate, Long> {
    Page<CategoryTemplate> findByArchived(boolean archived, Pageable pageable);
}
