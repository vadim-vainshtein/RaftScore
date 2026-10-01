package kz.vainshtein.raftscore.auth.entities;

import java.util.Optional;

import kz.vainshtein.raftscore.auth.models.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for authenticated users. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByRole(UserRole role);
}
