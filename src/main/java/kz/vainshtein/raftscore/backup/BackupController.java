package kz.vainshtein.raftscore.backup;

import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/backups")
@RequiredArgsConstructor
class BackupController {
    private final BackupService service;

    @PostMapping
    ResponseEntity<BackupMetadata> create() throws IOException, InterruptedException {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create());
    }

    @GetMapping
    List<BackupMetadata> list() throws IOException { return service.list(); }

    @GetMapping("/{id}/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) throws IOException {
        var stream = service.archive(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        StreamingResponseBody body = output -> { try (stream) { stream.transferTo(output); } };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"backup-" + id + ".zip\"")
                .body(body);
    }
}
