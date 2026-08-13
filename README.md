# OpenBid

An online auction house in Java. Multiple clients connect to a central server, list items, and bid in real time while a countdown runs on each auction. Concurrent bids are serialized so two people cannot both win the same item; every state change is a JDBC transaction.

CS-GY 6103 Introduction to Java — final project (dm6602). The project report is [REPORT.pdf](REPORT.pdf).

## Requirements

- **Java 17+** (`java -version`)
- The repo includes the Maven Wrapper (`./mvnw`); you do not need a separate Maven install, SQLite server, or other tools

## Dependencies

`./mvnw package` downloads these from Maven Central and **shades them into the jars**, so `java -jar` needs only Java 17+.

| Dependency | Version | Role |
| --- | --- | --- |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) (`org.xerial:sqlite-jdbc`) | 3.47.2.0 | JDBC driver; the database is the local file `openbid.db` |
| [FlatLaf](https://www.formdev.com/flatlaf/) (`com.formdev:flatlaf`) | 3.5.4 | Look and feel (dark/light). The UI is still Java Swing |
| [JUnit Jupiter](https://junit.org/junit5/) (`org.junit.jupiter:junit-jupiter`) | 5.11.4 | Tests only (`./mvnw test`); not required to run the app |

Everything else is **JDK 17**: Swing, TCP sockets (`java.net`), concurrency (`ExecutorService`, `synchronized`, `ScheduledExecutorService`), JDBC (`java.sql`), and `javax.crypto` (PBKDF2 password hashing).

Optional: set `OPENBID_PEPPER` in the environment for a non-default password pepper.

## Build

From the project directory (the folder that contains `pom.xml`):

```bash
./mvnw package
```

Writes `target/openbid-server.jar` and `target/openbid-client.jar`. Also runs the JUnit suite.

On Windows use `mvnw.cmd package`.

## Run the demo

**1. Start the server** (leave this terminal open)

```bash
java -jar target/openbid-server.jar --demo
```

You should see something like:

```
Demo accounts: peter / harry / jane / mohan  (password000)
OpenBid server listening on port 9000
```

`--demo` seeds those four accounts and sample lots. If `openbid.db` already exists, unsold listing clocks are refreshed so a few lots are live again (sold lots stay sold). Delete `openbid.db` (and `openbid.db-wal` / `openbid.db-shm` if present) only if you want a full re-seed.

Optional flags: `--port 9000`, `--db openbid.db`. Pepper: set `OPENBID_PEPPER` in the environment for a non-default secret.

**2. Start a client** (new terminal for each window)

```bash
java -jar target/openbid-client.jar
```

Sign in with:

- **Server:** `localhost`
- **Port:** `9000`
- **Username / password:** `peter`, `harry`, `jane`, or `mohan` / `password000`

Or use **Create account** for a new user. Open several clients to bid against each other.

## What you can do in the client

- Search and filter by category; views for All / Ending soon / Watching / My listings / My bids / Won
- Item photos, reserve status, Buy It Now (next to Place bid, until the price reaches it), watchlist, relist unsold lots
- Outbid and you-won toasts, a site-wide activity ticker, dark/light theme
- Save a CSV receipt when you win (winner only)
- Anti-snipe (+30s, shown as “Extended +30s”) and proxy bidding

The seed includes 10 lots: photos, categories, a reserve, Buy It Now, lots ending in under a minute, and a live proxy battle on the comic.

## Concurrent-bid proof

Server must already be running in the same working directory:

```bash
java -cp target/openbid-server.jar com.openbid.tools.StressTestClient
```

## Tests

```bash
./mvnw test
```

Runs the JUnit suite (concurrency, rollback, lifecycle, reserve, Buy It Now, relist, anti-snipe, proxy).

## Eclipse

**File → Import → Existing Maven Projects**, run `ServerMain` with program argument `--demo`, then run `ClientMain` several times.
