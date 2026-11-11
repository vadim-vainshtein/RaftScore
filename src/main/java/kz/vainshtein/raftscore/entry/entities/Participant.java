package kz.vainshtein.raftscore.entry.entities;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A person with an identity independent of crew membership. */
@Entity
@Table(name = "participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Participant extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String surname;

    @Column(name = "given_name", nullable = false, columnDefinition = "TEXT")
    private String givenName;

    @Column(columnDefinition = "TEXT")
    private String patronymic;

    @Builder
    private Participant(String surname, String givenName, String patronymic) {
        updateName(surname, givenName, patronymic);
    }

    public void updateName(String surname, String givenName, String patronymic) {
        if (surname == null || surname.isBlank() || givenName == null || givenName.isBlank()) {
            throw new IllegalArgumentException("surname and givenName must not be blank");
        }
        this.surname = surname;
        this.givenName = givenName;
        this.patronymic = patronymic;
    }
}
