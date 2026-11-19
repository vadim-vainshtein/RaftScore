package kz.vainshtein.raftscore.protocol;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface IssuedProtocolRepository extends JpaRepository<IssuedProtocol, Long> {
    List<IssuedProtocol> findByCompetitionIdOrderByEditionDesc(long competitionId);
    Optional<IssuedProtocol> findByCompetitionIdAndEdition(long competitionId, int edition);
    Optional<IssuedProtocol> findByCompetitionIdAndStaleFalse(long competitionId);
}
