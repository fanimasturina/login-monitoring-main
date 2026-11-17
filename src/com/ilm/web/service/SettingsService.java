package com.ilm.web.service;

import com.ilm.core.detect.Settings;
import com.ilm.web.dao.AdminDao;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Detection settings stored in the database, cached in memory. */
@Service
public class SettingsService {
    private static final String UNKNOWN_LOCATIONS = "internal.parser.unknown.locations";
    private final AdminDao dao;
    private volatile Settings cached;
    private volatile int version;

    public SettingsService(AdminDao dao) { this.dao = dao; }

    public Settings current() {
        Settings s = cached;
        if (s == null) {
            s = new Settings(dao.settings());
            cached = s;
        }
        return s;
    }

    /** Changes every time a setting is saved, so the engine knows it must reload. */
    public int version() { return version; }

    public synchronized void save(String key, String value, String by) {
        dao.saveSetting(key, value.trim(), by);
        cached = null;
        version++;
    }

    /** Log code locations (e.g. LoginController:doLogin:180) where the app never writes the user's name. */
    public Set<String> unknownResultLocations() {
        String v = dao.settings().get(UNKNOWN_LOCATIONS);
        if (v == null || v.isBlank()) return Set.of();
        return Arrays.stream(v.split(";")).filter(s -> !s.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public synchronized void addUnknownResultLocations(Set<String> found) {
        if (found.isEmpty()) return;
        Set<String> all = new LinkedHashSet<>(unknownResultLocations());
        if (all.addAll(found)) {
            String joined = String.join(";", all);
            if (joined.length() <= 1000) dao.saveSetting(UNKNOWN_LOCATIONS, joined, "system");
        }
    }

    public Map<String, String[]> definitions() { return Settings.DEFAULTS; }
}
