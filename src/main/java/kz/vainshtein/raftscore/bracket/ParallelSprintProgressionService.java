package kz.vainshtein.raftscore.bracket;

import java.util.Objects;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeat;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Propagates a resolved winner or a known empty feeder, including semifinal losers. */
@Service
@RequiredArgsConstructor
public class ParallelSprintProgressionService {
    private final ParallelSprintHeatRepository heats;

    public record Outcome(CrewEntry winner, CrewEntry loser, boolean resolved) { }

    public Outcome outcome(ParallelSprintHeat heat) {
        var winner = heat.winner();
        var loser = winner == null || heat.getCrew1() == null || heat.getCrew2() == null ? null
                : heat.getWinnerSlot() == 1 ? heat.getCrew2() : heat.getCrew1();
        return new Outcome(winner, loser, heat.outcomeResolved());
    }

    public void propagate(ParallelSprintHeat heat, Outcome before) {
        var after = outcome(heat);
        if (same(before, after) || heat.getNextHeat() == null) return;
        var next = heat.getNextHeat();
        update(next, heat.getNextSlot(), before.winner(), after.winner(), after.resolved());
        if (next.getNextHeat() == null && next.getHeatNumber() == 1) {
            var bronze = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(heat.getBracket().getId()).stream()
                    .filter(h -> h.getRoundNumber() == next.getRoundNumber() && h.getHeatNumber() == 2)
                    .findFirst().orElse(null);
            if (bronze != null) update(bronze, heat.getNextSlot(), before.loser(), after.loser(), after.resolved());
        }
    }

    private void update(ParallelSprintHeat destination, int slot, CrewEntry previous, CrewEntry replacement, boolean resolved) {
        var before = outcome(destination);
        destination.replaceWithdrawnFeeder(slot, previous, replacement, resolved);
        propagate(destination, before);
    }

    private boolean same(Outcome a, Outcome b) {
        return a.resolved() == b.resolved() && Objects.equals(id(a.winner()), id(b.winner())) && Objects.equals(id(a.loser()), id(b.loser()));
    }

    private Long id(CrewEntry crew) { return crew == null ? null : crew.getId(); }
}
