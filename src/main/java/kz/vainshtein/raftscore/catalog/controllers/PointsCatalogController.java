package kz.vainshtein.raftscore.catalog.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import org.springframework.transaction.annotation.Isolation;
import java.util.List;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/points-catalog")
@RequiredArgsConstructor
class PointsCatalogController {
    private final JdbcTemplate jdbc;
    record Row(@NotNull @Positive @JsonDeserialize(using = StrictInteger.class) Integer place, @NotNull @PositiveOrZero @JsonDeserialize(using = StrictInteger.class) Integer points) { }
    record Edit(@NotBlank @Size(max = 200) @Pattern(regexp = "[^\\x00]*") String name,
                @NotNull DisciplineType disciplineType, @NotNull List<@Valid @NotNull Row> rows, @JsonDeserialize(using = StrictLong.class) Long version) { }
    record Table(long id, String name, DisciplineType disciplineType, long version, boolean preset, List<Row> rows) { }

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    List<Table> list() {
        return jdbc.query("SELECT id, name, discipline_type, version, preset FROM points_catalog ORDER BY name, id",
                (rs, n) -> new Table(rs.getLong(1), rs.getString(2), DisciplineType.valueOf(rs.getString(3)), rs.getLong(4), rs.getBoolean(5), rows(rs.getLong(1))));
    }
    private List<Row> rows(long id) {
        return jdbc.query("SELECT place, points FROM points_catalog_rows WHERE table_id = ? ORDER BY place", (rs, n) -> new Row(rs.getInt(1), rs.getInt(2)), id);
    }
    private void validate(Edit request) {
        if (request.rows().stream().map(Row::place).distinct().count() != request.rows().size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate place");
    }
    private void writeRows(long id, List<Row> rows) {
        for (var row : rows) jdbc.update("INSERT INTO points_catalog_rows(table_id, place, points) VALUES (?, ?, ?)", id, row.place(), row.points());
    }
    @PostMapping
    @Transactional
    Table create(@Valid @RequestBody Edit request) {
        validate(request);
        var id = jdbc.queryForObject("INSERT INTO points_catalog(name, discipline_type) VALUES (?, ?) RETURNING id", Long.class, request.name().trim(), request.disciplineType().name());
        writeRows(id, request.rows());
        return new Table(id, request.name().trim(), request.disciplineType(), 0, false, rows(id));
    }
    @PutMapping("/{id}")
    @Transactional
    Table update(@PathVariable long id, @Valid @RequestBody Edit request) {
        validate(request);
        if (request.version() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version required");
        var count = jdbc.update("UPDATE points_catalog SET name = ?, discipline_type = ?, version = version + 1 WHERE id = ? AND version = ?", request.name().trim(), request.disciplineType().name(), id, request.version());
        if (count == 0) {
            if (jdbc.queryForObject("SELECT count(*) FROM points_catalog WHERE id = ?", Long.class, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            throw new OptimisticLockingFailureException("Stale points table");
        }
        jdbc.update("DELETE FROM points_catalog_rows WHERE table_id = ?", id);
        writeRows(id, request.rows());
        return new Table(id, request.name().trim(), request.disciplineType(), request.version() + 1, jdbc.queryForObject("SELECT preset FROM points_catalog WHERE id = ?", Boolean.class, id), rows(id));
    }
    static class StrictInteger extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("integer required");
            return parser.getIntValue();
        }
    }
    static class StrictLong extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("integer required");
            return parser.getLongValue();
        }
    }
}
