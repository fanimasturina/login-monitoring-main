package com.ilm.web.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;
import org.springframework.stereotype.Component;

/**
 * Deployment configuration. Defaults come from WEB-INF/classes/ilm.properties inside the WAR.
 * Values in an external file win, so passwords and server addresses stay out of the WAR:
 *   -Dilm.config=/path/ilm.properties, or $CATALINA_BASE/conf/ilm.properties when it exists.
 */
@Component
public class AppProps {
    private static final Logger LOG = Logger.getLogger(AppProps.class.getName());
    private final Properties props = new Properties();

    public AppProps() {
        try (InputStream in = AppProps.class.getResourceAsStream("/ilm.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read ilm.properties", e);
        }
        String ext = System.getProperty("ilm.config");
        if (ext == null && System.getProperty("catalina.base") != null) {
            ext = System.getProperty("catalina.base") + "/conf/ilm.properties";
        }
        if (ext != null && Files.isReadable(Path.of(ext))) {
            try (InputStream in = Files.newInputStream(Path.of(ext))) {
                props.load(in);
                LOG.info("ILM configuration loaded from " + ext);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + ext, e);
            }
        } else {
            LOG.warning("No external ilm.properties found; using the defaults packaged in the WAR");
        }
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public String get(String key) { return get(key, null); }

    public boolean bool(String key, boolean def) { return Boolean.parseBoolean(get(key, String.valueOf(def))); }

    public int i(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }
}
