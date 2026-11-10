package kz.vainshtein.raftscore.catalog.entities;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for reusable category templates. */
public interface CategoryTemplateRepository extends JpaRepository<CategoryTemplate, Long> {
}
