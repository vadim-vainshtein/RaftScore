package kz.vainshtein.raftscore.entry.entities;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CrewMembershipRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private CrewMembershipRepository memberships;
    @Autowired private ParticipantRepository participants;
    @Autowired private CrewEntryRepository crews;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void persistsOrderedMembershipAndVersionsChanges() {
        var crew = createCrew();
        var first = participants.saveAndFlush(Participant.builder().surname("Smith").givenName("Alex").build());
        var second = participants.saveAndFlush(Participant.builder().surname("Jones").givenName("Sam").patronymic("Lee").build());
        var later = memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(second).position(2).build());
        var earlier = memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(first).position(1).build());
        entityManager.clear();

        var ordered = memberships.findByCrewEntryIdOrderByPositionAsc(crew.getId());
        assertThat(ordered).extracting(CrewMembership::getId).containsExactly(earlier.getId(), later.getId());
        assertThat(ordered.get(0).getParticipant().getGivenName()).isEqualTo("Alex");
        assertThat(ordered.get(0).getParticipant().getPatronymic()).isNull();
        assertThat(ordered.get(1).getParticipant().getPatronymic()).isEqualTo("Lee");
        ordered.get(0).getParticipant().updateName("Smith", "Alexander", null);
        ordered.get(1).changePosition(3);
        entityManager.flush();
        entityManager.clear();
        assertThat(participants.findById(first.getId()).orElseThrow().getVersion()).isOne();
        assertThat(memberships.findById(later.getId()).orElseThrow().getVersion()).isOne();
    }

    @Test
    void databaseEnforcesMembershipConstraints() {
        assertInvalid(null, 1L, 1);
        assertInvalid(1L, null, 1);
        assertInvalid(-1L, -1L, 1);
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var crew = createCrew();
            var person = participants.saveAndFlush(Participant.builder().surname("Smith").givenName("Alex").build());
            assertThatThrownBy(() -> insert(crew.getId(), person.getId(), 0)).isInstanceOf(DataIntegrityViolationException.class);
        });
        assertDuplicate(true);
        assertDuplicate(false);
    }

    @Test
    void participantCannotJoinTwoCrewsInSameCategory() {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var firstCrew = createCrew();
            var secondCrew = createCrew(firstCrew.getCompetitionCategory(), 2);
            var person = participants.saveAndFlush(Participant.builder().surname("Smith").givenName("Alex").build());
            memberships.saveAndFlush(CrewMembership.builder().crewEntry(firstCrew).participant(person).position(1).build());
            assertThatThrownBy(() -> memberships.saveAndFlush(CrewMembership.builder().crewEntry(secondCrew).participant(person).position(1).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    @Test
    @Transactional
    void participantCanJoinCrewsInDifferentCategories() {
        var firstCrew = createCrew();
        var otherCategory = categories.saveAndFlush(CompetitionCategory.builder()
                .competition(firstCrew.getCompetitionCategory().getCompetition())
                .classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        var secondCrew = createCrew(otherCategory, 1);
        var person = participants.saveAndFlush(Participant.builder().surname("Smith").givenName("Alex").build());
        memberships.saveAndFlush(CrewMembership.builder().crewEntry(firstCrew).participant(person).position(1).build());
        memberships.saveAndFlush(CrewMembership.builder().crewEntry(secondCrew).participant(person).position(1).build());
    }

    @Test
    void databaseRejectsSpoofedCategory() {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var crew = createCrew();
            var otherCrew = createCrew();
            var person = participants.saveAndFlush(Participant.builder().surname("Smith").givenName("Alex").build());
            assertThatThrownBy(() -> jdbc.update("INSERT INTO crew_memberships (crew_entry_id, competition_category_id, participant_id, position) VALUES (?, ?, ?, ?)",
                    crew.getId(), otherCrew.getCompetitionCategory().getId(), person.getId(), 1))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }
    private void assertInvalid(Long crewId, Long participantId, int position) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThatThrownBy(() -> insert(crewId, participantId, position)).isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void assertDuplicate(boolean samePosition) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var crew = createCrew();
            var first = participants.saveAndFlush(Participant.builder().surname("Smith").givenName("Alex").build());
            var second = participants.saveAndFlush(Participant.builder().surname("Jones").givenName("Sam").build());
            insert(crew.getId(), first.getId(), 1);
            assertThatThrownBy(() -> insert(crew.getId(), samePosition ? second.getId() : first.getId(), samePosition ? 1 : 2))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void insert(Long crewId, Long participantId, int position) {
        jdbc.update("INSERT INTO crew_memberships (crew_entry_id, competition_category_id, participant_id, position) VALUES (?, (SELECT competition_category_id FROM crew_entries WHERE id = ?), ?, ?)", crewId, crewId, participantId, position);
    }

    private CrewEntry createCrew() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                .location("River").timeZone("Europe/London").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        return createCrew(category, 1);
    }

    private CrewEntry createCrew(CompetitionCategory category, int startNumber) {
        return crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Rapids")
                .regionOrClub("Club").representative("Coach").startNumber(startNumber).build());
    }
}
