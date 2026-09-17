package kz.vainshtein.raftscore.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for authenticated users. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);
}
