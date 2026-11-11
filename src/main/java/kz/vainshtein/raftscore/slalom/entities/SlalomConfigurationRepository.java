package kz.vainshtein.raftscore.slalom.entities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlalomConfigurationRepository extends JpaRepository<SlalomConfiguration, Long> { Optional<SlalomConfiguration> findByDisciplineId(Long disciplineId); }
