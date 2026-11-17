# Intelligent Login Monitoring (ILM)

Web application that collects login events, puts them into one standard format, scores every
login from 0 to 100 with rules plus a machine learning model, explains the score, and opens
alerts for analysts to investigate. It reads data from the Audit Trail Integrator (ATI) and
application logs. It does not replace LDAP/Active Directory or ATI, and it never blocks a user.

| Part | Choice |
|---|---|
| Server | Apache Tomcat 11.0.x (use the newest patch; 11.0.27 on 16 Nov 2026) |
| Language | Java 21 |
| Web | Spring MVC 7 + Spring Security 7, JSP pages, Bootstrap 5 served from the app (no CDN) |
| Database | PostgreSQL, plain JDBC (`PreparedStatement` / `ResultSet`), no JPA or ORM |
| Machine learning | Isolation Forest written in Java inside the app (no Python, no extra service) |
| Build | Apache Ant (`build.xml`) |
| Sign-in | Microsoft Active Directory over LDAPS; roles kept in PostgreSQL |

## 1. What you need

- JDK 21, Apache Ant 1.10.x, Apache Tomcat 11.0.x, PostgreSQL 14 or newer
- For `ant resolve` only: access to Maven Central, or to your internal Nexus/Artifactory
  (set `maven.repo` in `build.properties`). Every jar is checked against its SHA-1 file.

## 2. First run (about 15 minutes)

```bash
# 1. Database
sudo -u postgres createuser --pwprompt ilm
sudo -u postgres createdb --owner=ilm ilm
psql -U ilm -d ilm -h localhost -f db/schema.sql

# 2. Build settings: set catalina.home to your Tomcat folder
nano build.properties

# 3. Download libraries, test the engine, build
ant resolve      # Spring, JSTL, mail, PostgreSQL driver, Bootstrap -> lib/ and web/assets/vendor/
ant test         # parses demo data, trains a model, prints the riskiest events (no DB or Tomcat needed)
ant war          # dist/ilm.war
ant deploy       # copies the WAR to Tomcat/webapps and the PostgreSQL driver to Tomcat/lib

# 4. Tomcat: database connection and app configuration
cp conf/tomcat-context-example.xml $CATALINA_BASE/conf/Catalina/localhost/ilm.xml   # set DB password
cp conf/ilm.properties             $CATALINA_BASE/conf/ilm.properties               # see section 3
chmod 600 $CATALINA_BASE/conf/Catalina/localhost/ilm.xml $CATALINA_BASE/conf/ilm.properties
```

Start Tomcat and open `https://<server>:8443/ilm`. Then go to **Upload data** and upload the three
files in `demo-data/` (application code `APP-DEMO-A` for the two `.log` files). The first model is
trained automatically once 200 login events are stored.

## 3. Sign-in

Edit `$CATALINA_BASE/conf/ilm.properties`.

**Active Directory (production)**

```properties
auth.ad.url=ldaps://dc01.yourdomain.local:636 ldaps://dc02.yourdomain.local:636
auth.ad.domain=yourdomain.local
auth.bootstrap.admins=your.userid
```

- The user's own password is used for the LDAP bind. No service account password is stored.
- Only `ldaps://` is accepted. Tomcat's JVM must trust the certificate authority of your domain controllers:
  `keytool -importcert -alias corp-ca -file corp-ca.cer -keystore /opt/ilm/truststore.p12 -storetype PKCS12`
  then start Tomcat with `-Djavax.net.ssl.trustStore=/opt/ilm/truststore.p12 -Djavax.net.ssl.trustStorePassword=...`
- A valid AD password is not enough: the user must also be registered on the **Users** page.
  IDs in `auth.bootstrap.admins` become Administrator at their first sign-in; remove the line afterwards.
- Five wrong passwords lock sign-in to this app for that user ID for 15 minutes.

**Without AD (development laptop)**

```bash
ant compile-core
java -cp build/core com.ilm.core.auth.PasswordHasher     # type a password of 12+ characters, copy the hash
```

```properties
auth.local.enabled=true
auth.local.admin.user=localadmin
auth.local.admin.hash=pbkdf2$310000$...
```

Keep `auth.local.enabled=false` in production unless an emergency account is approved.

**Roles**

| Role | Can do |
|---|---|
| ANALYST | Dashboard, events, alerts, investigate and close alerts, upload |
| ADMIN | Settings, users, model, upload. Cannot change an investigation result |
| MANAGER | Dashboard and reports. User IDs, names and IP addresses are masked |
| AUDITOR | Read-only, including the audit trail |

## 4. How data comes in

`ingest.mode` in `ilm.properties` is the switch.

- `upload` (default): files are uploaded on the Upload page. Re-uploading a file is safe; records
  already stored are skipped.
- `realtime`: the app also collects by itself every `realtime.poll.seconds`:
  - **ATI database**: add a second, read-only `<Resource name="jdbc/ati" ...>` in `ilm.xml` and set
    `realtime.ati.jndi` and `realtime.ati.sql`. The query must contain one `?` (last Aua Id already
    collected). Adjust table and column names when ATI gives you the real schema. If ATI is not
    PostgreSQL, put that database's JDBC driver jar in `Tomcat/lib` as well.
  - **Log files on this server**: `realtime.log.files=/path/web-user.log|APP-REGIS,...`. Only new
    lines are read; log rotation is handled.

| Source | Format | What is read |
|---|---|---|
| ATI user activity | `.xlsx`, `.csv`, or DB rows | Aua Id, App Code, User Id, Action Code, Description, Date Activity, Endpoint |
| `web-user.log` | text | time, IP, PC Name, User ID, Name |
| `web-security.log` | text | time, IP, PC Name of "Unauthorized access attempt" |

## 5. How the score is made

1. **Rules** for known patterns: repeated failures, success right after failures, 3+ requests in
   5 seconds, night or outside working hours, unusual hour for this user, first login to an
   application, many user IDs failing together, privileged account, bursts of unauthorized access.
2. **Isolation Forest** on 15 behaviour features (hour, rarity of hour and day for this user, bursts,
   failures, time since last login, and so on). It needs no labelled incidents. When its anomaly
   score passes `ml.threshold` it adds up to `ml.max.points`, and names the three features that
   moved the score most.
3. Risk score = rule points + model points, capped at 100.
   Low 0-25, Medium 26-50, High 51-75, Critical 76-100. Score 51 or more opens an alert.
4. A user's own profile is used after 30 days and 10 logins; before that the login is compared
   with all users.

Every number above can be changed by an Administrator on **Settings**; every change is written to
the audit trail. **Model and data** trains a new model version and can score all history again.

## 6. What the sample files showed (confirmed by the project owner, 16 Nov 2026)

- `web-user.log` does not say if a login succeeded. In the sample, `Name : null` appears for wrong
  or mistyped user IDs, so it is read as **Failed**, and a filled name as **Success**.
- Since June 2026 the line `LoginController:doLogin:180` writes `Name : null` on every row. The parser
  notices this and stores those logins as **Unknown** instead of Failed.
- The sample `web-user.log` and `web-security.log` come from APP-REGIS.
- All IP addresses are `127.0.0.1` or `0:0:0:0:0:0:0:1`: that is the proxy, not the user's PC. Network
  rules are skipped for these rows. Ask the application team to log the real client IP
  (`X-Forwarded-For`), then "new device/network" can be added as a feature.
- The same login is often written twice within milliseconds (IPv4 and IPv6 line). The burst rule
  needs 3 lines in 5 seconds, so a normal pair does not trigger it.
- ATI exports contain successful logins only.

## 7. Email alerts

Set `mail.enabled=true` and the SMTP values in `ilm.properties`. The email has a masked user ID and
a link, no personal details. At most `mail.max.per.run` emails are sent per analysis run.

## 8. Before production

- Serve HTTPS only (Tomcat `<Connector ... SSLEnabled="true">` or TLS at your load balancer plus
  `RemoteIpValve`, so the audit trail keeps the real client IP and the session cookie is Secure).
- Run Tomcat as its own non-root user; delete the default `ROOT`, `docs`, `examples`, `manager` and
  `host-manager` webapps; disable the shutdown port (`<Server port="-1">`).
- Use a separate database account for ILM, and `sslmode=verify-full` if PostgreSQL is on another host.
- Back up the database daily. `login_event`, `risk_assessment`, `alert` and `audit_trail` are the audit evidence.
- Raise library versions in `build.properties` to the newest patch, then `ant clean resolve war`.
  Spring Security 7.0.x gets free fixes only until 31 Dec 2026; plan the move to 7.1.x.
- Run the pilot, measure false positives (Reports page: closed as false positive vs confirmed), and tune Settings.

## 9. Project layout

```
build.xml, build.properties   Ant build and library versions
conf/                         ilm.properties (app), tomcat-context-example.xml (database)
db/schema.sql                 PostgreSQL tables
src/com/ilm/core/             parsers, features, rules, Isolation Forest, AD sign-in (JDK only)
src/com/ilm/web/              Spring MVC controllers, services, JDBC DAOs, security
web/                          JSP views, CSS, JavaScript
demo-data/                    made-up sample files (regenerate with: ant demo-data)
```

Check new log formats before uploading, without Tomcat or a database:

```bash
java -cp build/core com.ilm.core.tools.EngineSelfTest web-user.log web-security.log activity.xlsx --app=APP-REGIS
```

## 10. Not in this version

Automatic account blocking, automatic incident response, SIEM integration, a data-retention
(archive) job, and precision/recall reporting. The last one needs labelled incidents; closing
alerts as "False positive" or "Confirmed risk" builds those labels over time.
