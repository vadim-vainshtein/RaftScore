package kz.vainshtein.raftscore.bracket;

import java.util.ArrayList;
import java.util.List;

/** First-round slots in the same order used by the parallel sprint draw. */
public final class ParallelSprintDraw {
    private ParallelSprintDraw() { }
    public record Pair<T>(T first, T second) { }

    public static <T> List<Pair<T>> firstRound(List<T> ranked) {
        if (ranked.size() < 2) return List.of();
        int size = 2;
        while (size < ranked.size()) size = Math.multiplyExact(size, 2);
        int stageSize = ranked.size() == size ? size : size / 2;
        var positions = new ArrayList<>(List.of(2, 1));
        for (int width = 4; width <= stageSize; width *= 2) {
            var expanded = new ArrayList<Integer>();
            for (var seed : positions) { expanded.add(seed + width / 2); expanded.add(seed); }
            positions = expanded;
        }
        var pairs = new ArrayList<Pair<T>>();
        if (ranked.size() == size) {
            for (int i = 0; i < size; i += 2) pairs.add(new Pair<>(at(positions.get(i), ranked), at(positions.get(i + 1), ranked)));
        } else {
            for (var seed : positions) {
                int opponent = seed <= size - ranked.size() ? size + 1 : seed + ranked.size() - size / 2;
                pairs.add(new Pair<>(at(seed, ranked), at(opponent, ranked)));
            }
        }
        return List.copyOf(pairs);
    }

    private static <T> T at(int seed, List<T> ranked) { return seed <= ranked.size() ? ranked.get(seed - 1) : null; }
}
