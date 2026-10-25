package dev.ledgerlab.corrections.internal;

import dev.ledgerlab.corrections.CorrectionException;
import dev.ledgerlab.corrections.CorrectionResult;
import dev.ledgerlab.corrections.Corrections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@ConditionalOnProperty(prefix = "ledgerlab.corrections", name = "enabled", havingValue = "true", matchIfMissing = true)
final class CorrectionController {
    private final Corrections corrections;
    private final CorrectionQueries queries;
    private final ObjectMapper mapper;

    CorrectionController(Corrections corrections, CorrectionQueries queries, ObjectMapper mapper) {
        this.corrections = corrections;
        this.queries = queries;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/corrections", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CorrectionResult> submit(@RequestBody(required = false) @Nullable String raw) {
        return ResponseEntity.status(201).body(corrections.submit(CorrectionJson.parse(mapper, raw)));
    }

    @GetMapping("/api/corrections/{id}")
    CorrectionResult detail(@PathVariable String id) {
        return queries.detail(id);
    }

    @GetMapping("/api/corrections")
    List<CorrectionResult> list(
            @RequestParam(required = false) @Nullable String subjectType,
            @RequestParam(required = false) @Nullable String subjectId) {
        return queries.list(subjectType, subjectId);
    }

    @ExceptionHandler(CorrectionException.class)
    ResponseEntity<ProblemDetail> rejected(CorrectionException error) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(error.status()), error.code());
        detail.setProperty("code", error.code());
        if (error.correctionId() != null) detail.setProperty("correctionId", error.correctionId());
        return ResponseEntity.status(error.status()).body(detail);
    }
}
