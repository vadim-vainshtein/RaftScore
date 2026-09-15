package kz.vainshtein.raftscore.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Base persistence mapping for aggregates that can be edited concurrently.
 *
 * <p>Concrete entities retain ownership of their identifier and table mapping.
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class VersionedEntity {

    @Version
    @Column(nullable = false)
    private long version;
}
