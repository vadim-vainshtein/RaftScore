package kz.vainshtein.raftscore.discipline.entities;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for disciplines. */
public interface DisciplineRepository extends JpaRepository<Discipline, Long> {
}
