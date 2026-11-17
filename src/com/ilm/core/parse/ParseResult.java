package com.ilm.core.parse;

import com.ilm.core.model.LoginEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ParseResult {
    public record ParseError(int lineNo, String raw, String message) {}

    public final List<LoginEvent> events = new ArrayList<>();
    public final List<ParseError> errors = new ArrayList<>();
    /** Rows that are valid but not authentication events (e.g. other ATI action codes). */
    public int skipped;
    /** Code locations where "Name" was null on every line, so success/failure cannot be inferred. */
    public final Set<String> alwaysNullLocations = new LinkedHashSet<>();

    public void error(int lineNo, String raw, String message) {
        String r = raw == null ? "" : raw;
        errors.add(new ParseError(lineNo, r.length() > 2000 ? r.substring(0, 2000) : r, message));
    }
}
