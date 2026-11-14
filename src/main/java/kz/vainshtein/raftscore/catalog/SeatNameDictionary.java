package kz.vainshtein.raftscore.catalog;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatNameDictionary {
    private final JdbcTemplate jdbc;

    public void registerIntroducedNames(List<String> previous, List<String> current) {
        for (var label : current.stream().filter(label -> !label.isBlank() && !previous.contains(label)).distinct().sorted().toList()) {
            jdbc.update("INSERT INTO seat_names(name) VALUES (?) ON CONFLICT (name) DO UPDATE SET deleted = false, version = seat_names.version + 1 WHERE seat_names.deleted", label);
        }
    }
}
