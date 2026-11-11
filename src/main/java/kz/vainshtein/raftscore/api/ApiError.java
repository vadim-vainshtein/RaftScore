package kz.vainshtein.raftscore.api;

import java.util.List;
import java.util.Map;

/** Stable, localization-ready API error; parameters must contain only safe values. */
public record ApiError(String code, Map<String, Object> parameters, List<FieldError> fieldErrors) {

    public static ApiError of(String code) {
        return new ApiError(code, Map.of(), List.of());
    }

    public record FieldError(String field, String code, Map<String, Object> parameters) {
    }
}
