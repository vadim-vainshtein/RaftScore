package kz.vainshtein.raftscore.catalog.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Administrative dictionary; assignments retain their own label values. */
@RestController
@RequestMapping("/api/seat-names")
@RequiredArgsConstructor
class SeatNameController {
    private final JdbcTemplate jdbc;
    record SeatName(long id, String name, boolean deleted, long version) { }
    record SeatNameEdit(@NotBlank @Size(max = 200) @Pattern(regexp = "[^\\x00]*") String name, @NotNull Boolean deleted, @NotNull Long version) { }
    @GetMapping
    List<SeatName> list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return jdbc.query("SELECT id, name, deleted, version FROM seat_names WHERE (? OR NOT deleted) ORDER BY name, id",
                (rs, row) -> new SeatName(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4)), includeDeleted);
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    org.springframework.http.ResponseEntity<kz.vainshtein.raftscore.api.ApiError> duplicate() {
        return org.springframework.http.ResponseEntity.status(409).body(kz.vainshtein.raftscore.api.ApiError.of("seat_name.duplicate"));
    }
    @PutMapping("/{id}")
    @Transactional
    SeatName edit(@PathVariable long id, @Valid @RequestBody SeatNameEdit request) {
        var changed = jdbc.update("UPDATE seat_names SET name = ?, deleted = ?, version = version + 1 WHERE id = ? AND version = ?",
                request.name(), request.deleted(), id, request.version());
        if (changed == 0) {
            if (jdbc.queryForObject("SELECT count(*) FROM seat_names WHERE id = ?", Long.class, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            throw new OptimisticLockingFailureException("Stale seat name");
        }
        return new SeatName(id, request.name(), request.deleted(), request.version() + 1);
    }
}
