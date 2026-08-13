# OpenBid

An online auction house in Java. Multiple clients connect to a central server, list items, and bid in real time while a countdown runs on each auction. Concurrent bids are serialized so two people cannot both win the same item; every state change is a JDBC transaction.

CS-GY 6103 Introduction to Java — final project (dm6602). A full write-up is in [REPORT.md](REPORT.md).

## Build

Requires **Java 17+**. The repo includes the Maven Wrapper.

```bash
./mvnw package
```

Writes `target/openbid-server.jar` and `target/openbid-client.jar`.

## Run the demo

If you already ran an older demo, you can keep `openbid.db`: `--demo` refreshes unsold listing clocks so a few lots are live again (sold lots stay sold). Delete the file only if you want a full re-seed.

```bash
java -jar target/openbid-server.jar --demo
```

Then, in other terminals:

```bash
java -jar target/openbid-client.jar
```

**Demo accounts** are printed in the server terminal when you start with `--demo` (password `password000`).

The seed includes 10 lots: photos, categories, a reserve, Buy It Now, lots ending in under a minute, and a live proxy battle on the comic.

## What you can do in the client

- Search and filter by category; views for All / Ending soon / Watching / My listings / My bids / Won
- Item photos, reserve status, Buy It Now (next to Place bid, until the price reaches it), watchlist, relist unsold lots
- Outbid and you-won toasts, a site-wide activity ticker, dark/light theme
- Save a CSV receipt when you win (winner only)
- Anti-snipe (+30s, shown as “Extended +30s”) and proxy bidding

## Concurrent-bid proof

```bash
java -cp target/openbid-server.jar com.openbid.tools.StressTestClient
```

## Tests

`./mvnw test` runs the JUnit suite (concurrency, rollback, lifecycle, reserve, Buy It Now, relist, anti-snipe, proxy).
