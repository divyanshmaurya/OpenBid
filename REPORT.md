# OpenBid: A Concurrent Client–Server Auction House in Java

**IEEE-style Final Project Report**

CS-GY 6103 Introduction to Java  
New York University Tandon School of Engineering

Divyansh Maurya  
Net ID: `dm6602` · N Number: `N11376464`

---

## Abstract

OpenBid is a multi-user online auction house implemented in Java 17. Desktop clients built with Java Swing connect over TCP sockets to a multithreaded server that serializes every bid, close, and listing through a single monitor (`BidManager`) and commits each state change as one JDBC transaction against SQLite. The motivating question is the same as on a live auction site: if two people bid in the last second, how can the system guarantee that they cannot both win? OpenBid’s answer is structural. Concurrent handler threads cannot mutate an auction except by entering `synchronized` methods; a version-checked `UPDATE` rolls back if a lost update is ever attempted; and the countdown that closes a lot runs on a `ScheduledExecutorService` that calls the *same* lock as bidding. The four advanced concepts required by the course—graphical user interfaces, networking, multithreading, and databases—are therefore not four separate demos. They are four stages of one path: a Swing action becomes a socket line, a pooled handler thread calls `BidManager`, a transaction commits the new price, and a broadcast is applied on every client’s Event Dispatch Thread via `SwingUtilities.invokeLater`. Stretch goals (anti-sniping and proxy bidding) and product features (photos, reserve price, Buy It Now, watchlist, receipts, search, and themed UI) ride on that same path. Twenty-six JUnit 5 tests plus a TCP stress client confirm a single winner, transactional rollback, and the full auction lifecycle.

**Index Terms**—Java, Swing, TCP sockets, multithreading, JDBC, SQLite, optimistic locking, online auctions, concurrency control, PBKDF2.

---

## I. Introduction

### A. Motivation

Commercial auction platforms must decide a unique winner even when two bids arrive in the same millisecond. A naive sequence—*read the current price, check that the new bid is higher, write the new price*—loses that race. Both threads can read the same price, both can decide they are high enough, and both can write. The database and the two clients then disagree about who won. The interesting property of an auction is that users compete for the *same row*. Concurrency control and transactional atomicity are therefore the core of the design, not an extra feature bolted onto a form.

### B. Contribution

This report describes a complete, runnable system that:

1. Presents a professional Swing client (login, catalog, live detail panel, listing dialog).
2. Speaks a line-oriented TCP protocol so every connected window stays live without polling.
3. Serializes bids, proxy battles, Buy It Now, and expiry on one server-side lock.
4. Persists users, auctions, bids, sales, images, proxies, and watches in SQLite with explicit commit/rollback and an optimistic `version` column.
5. Implements the proposal’s stretch goals (30-second anti-snipe; private proxy maxima) and a full set of auction-house features listed in Section VI.

### C. Mapping to the Course Requirement

The course asks for at least three advanced Java concepts. OpenBid implements four, each load-bearing:

| Concept | Role in OpenBid |
| --- | --- |
| GUIs (Java Swing) | The entire client; live updates marshalled onto the Event Dispatch Thread (EDT) with `SwingUtilities.invokeLater`. |
| Networking (TCP sockets) | Line-based client/server protocol; the server *pushes* events so clients never poll. |
| Multithreading | Accept loop + 64-thread `ExecutorService`; `synchronized BidManager`; `ScheduledExecutorService` countdowns; dedicated client listener thread. |
| Databases (JDBC) | SQLite with `inTransaction`, rollback, foreign keys, and optimistic locking on `auctions.version`. |

Supporting core-language techniques—records, enums, collections, exception handling, `PreparedStatement`, `CountDownLatch` in tests, and constant-time password verification—are called out in Section V.

### D. Organization

Section II states requirements. Section III summarizes architecture. Section IV walks through the user-visible application. Section V is the technical heart of the report: the Java concepts and where they appear. Section VI catalogs every product feature. Section VII covers protocol, schema, and security. Section VIII traces one bid end-to-end. Section IX reports evaluation. Section X discusses design decisions. Section XI concludes. Appendices give build instructions and a file map.

---

## II. Problem Statement and Requirements

### A. Functional Requirements

- Multiple users must register or log in and operate from separate client windows.
- A seller must list an item with title, description, category, optional photo, starting price, duration, optional reserve, and optional Buy It Now.
- Bidders must place ordinary bids, set a private proxy maximum, or (when offered) buy immediately.
- Every connected client must see price, leader, countdown, and bid-feed changes without pressing refresh.
- When time expires, the server must close the lot, record a sale if and only if there is a valid winner, and notify everyone.
- A last-second bid must not steal the lot without giving others a chance to respond (anti-snipe).
- Two concurrent bids must never both be recorded as the winning bid.

### B. Non-Functional Requirements

- The Swing UI must remain selectable while other clients bid (no I/O on the EDT).
- Money must never pass through floating-point types.
- Passwords must not be stored in plaintext.
- A crash or unique-constraint failure mid-write must leave the database consistent (rollback).
- The project must build with Maven on Java 17+ and demonstrate concurrency with both JUnit and a live socket stress client.

---

## III. System Architecture

OpenBid is one Maven module with two shaded entry points: `openbid-server.jar` (`com.openbid.server.ServerMain`) and `openbid-client.jar` (`com.openbid.client.ClientMain`). Shared types live in `com.openbid.shared` so the wire format cannot drift between sides.

```
com.openbid.shared      Protocol, AuctionInfo, BidInfo, Money, Categories, CatalogImage
com.openbid.security    PasswordHasher (PBKDF2-HMAC-SHA256 + salt + pepper)
com.openbid.db          Database.inTransaction, DAOs, schema.sql
com.openbid.server      AuctionServer, ClientHandler, BidManager, AuctionScheduler
com.openbid.client      ServerConnection, LoginFrame, MainFrame, AuctionDetailPanel, ListItemDialog
com.openbid.tools       StressTestClient
```

The runtime topology is:

```
  Swing UI  --EDT--  ServerConnection (listener thread)
                         |
                         |  TCP, one UTF-8 line per message
                         v
  Accept loop  -->  ExecutorService (64 ClientHandlers)
                         |
                         v
                   synchronized BidManager
                    |                |
                    v                v
            JDBC transaction   AuctionScheduler
            (SQLite + version) (ScheduledExecutorService)
                    |
                    v
              broadcast to all logged-in handlers
                         |
                         v
              invokeLater --> table, price, feed, toasts
```

`AuctionServer` accepts connections on the calling thread and submits each `ClientHandler` to `Executors.newFixedThreadPool(64)`. Handlers are kept in a `CopyOnWriteArrayList` so broadcasts can iterate while accept/remove proceed. Outbound writes on one socket are synchronized on a per-handler lock so a reply and a broadcast cannot interleave bytes.

`AuctionScheduler` owns a single-thread `ScheduledExecutorService` and a `ConcurrentHashMap<Long, ScheduledFuture<?>>` of close tasks. Listing arms a task at `end_time`; anti-snipe cancels and re-arms; the task calls `BidManager.closeAuction`, which is the same monitor as `placeBid`.

On process restart, `recoverOpenAuctions()` re-arms still-open lots and closes any that are already past due. The `--demo` flag additionally restaggers unsold clocks *before* recovery so a demonstration restart still has live listings; sold lots are never reopened. A start without `--demo` does not rewrite end times.

---

## IV. Application Walkthrough

### A. Login

The client opens on a login card: server host (default `localhost`), port (default `9000`), username, and password, with **Sign in** and **Create account**. Demo account names are *not* printed on this screen. When the server is started with `--demo`, the terminal prints:

```
Demo accounts: peter / harry / jane / mohan  (password000)
```

Unauthenticated sockets may send only `REGISTER` or `LOGIN`. Usernames are 3–20 letters, digits, or underscores; passwords must be at least six characters.

### B. Main Window

After authentication the main window is a three-pane dashboard:

- **Views** (left): All auctions, Ending soon, Watching, My listings, My bids, Won.
- **Catalog table** (center): item, category, seller, price, leader, time remaining, status (Open / Ending… / Sold / Unsold / Reserve met or not met). A one-second `javax.swing.Timer` refreshes countdowns. Inside the 30-second anti-snipe window the remaining time is drawn in red; lots ending within two minutes are emphasized in amber.
- **Detail panel** (right): photo, title, seller, description, current price, time remaining, leader, reserve status, live bid feed, Place bid, Buy It Now, proxy maximum, Watch, Relist, and Save receipt.

A **Live activity** ticker at the bottom shows site-wide events (listings, bids, closes) without a page refresh. The status bar reads “Live · no refresh needed.” Dark/light theme is a FlatLaf look-and-feel switch; the programming model remains Swing.

Search and a category combo box filter the table on the EDT. Selecting a row loads the detail panel and requests `GET_BIDS` and `GET_IMAGE`. After the user lists an item, that new lot is selected immediately rather than leaving the previously viewed row on screen.

### C. Selling

**Sell an item** opens a modal `ListItemDialog`: title, description, category (`Electronics`, `Collectibles`, `Fashion`, `Sports`, `Music`, `Home`, `Other`), starting price, optional reserve, optional Buy It Now, duration (45 seconds through 1 hour), and an optional JPEG. If no photo is chosen, the server generates a catalog image. On success the server broadcasts `AUCTION_NEW`; every connected client inserts the row without refresh.

### D. Bidding Rules Visible in the UI

- The seller cannot bid or set a proxy on their own lot; the panel states this explicitly and disables the controls.
- Buy It Now appears next to **Place bid** when the lot has a BIN price still above the current price and the viewer is not the seller.
- **Save receipt** appears only for the *winner* of a *sold* lot (not for the seller, not for losing bidders, not for unsold lots).
- **Relist** appears only for the seller of a closed unsold lot.

---

## V. Core Java Concepts Used

This section is the explicit mapping from course concepts and core language features to source types. Each subsection names the Java API and the OpenBid class that uses it.

### A. Graphical User Interfaces (Java Swing)

The client is written entirely in Swing (`JFrame`, `JDialog`, `JTable`, `JList`, `JSplitPane`, `JTextField`, `JButton`, `GridBagLayout`, `BorderLayout`). `AuctionTableModel` extends `AbstractTableModel`. `ListItemDialog` is a modal dialog. Countdowns use `javax.swing.Timer` (EDT-safe), not a raw background thread painting labels.

Swing is not thread-safe. `ServerConnection` owns a dedicated listener thread that **never** calls a component method. Every incoming line is posted with:

```java
SwingUtilities.invokeLater(() -> handler.accept(copy));
```

That is the required pattern: blocking `readLine()` off the EDT, mutation of the table and labels on the EDT. FlatLaf (`FlatIntelliJLaf` / `FlatDarculaLaf`) is only a look-and-feel; tables, lists, dialogs, and `invokeLater` remain plain Swing.

### B. Networking (TCP Sockets)

`java.net.Socket` and `ServerSocket` carry a UTF-8, line-oriented protocol. `BufferedReader.readLine()` and `PrintWriter.println` define message boundaries. `socket.setTcpNoDelay(true)` reduces latency for short bid lines. Default port is **9000**.

`Protocol.encode` / `Protocol.decode` escape `\`, `|`, and newlines so a title such as `Rare | Blue\Note` survives a round trip (`ProtocolTest`). Money on the wire is integer cents (`long`), never `double`. Photos travel as Base64 inside `LIST_ITEM` and `IMAGE_OK`.

The server *pushes* `NEW_BID`, `AUCTION_UPDATED`, `AUCTION_EXTENDED`, `AUCTION_CLOSED`, `AUCTION_NEW`, `YOU_OUTBID`, `YOU_WON`, and `TICKER`. Clients do not poll. That is the same architectural idea as a stock ticker, applied to auctions.

### C. Multithreading and Concurrency

Several thread pools and locks cooperate:

| Mechanism | Java API | Where |
| --- | --- | --- |
| Accept loop | calling thread | `AuctionServer.start` |
| Per-connection handlers | `Executors.newFixedThreadPool(64)` | `AuctionServer` |
| Bid/close serialization | `synchronized` methods | `BidManager` |
| Countdown / close | `ScheduledExecutorService` | `AuctionScheduler` |
| Task index | `ConcurrentHashMap` | `AuctionScheduler.tasks` |
| Broadcast list | `CopyOnWriteArrayList` | `AuctionServer.clients` |
| Client I/O | dedicated `Thread` | `ServerConnection` |
| UI | Event Dispatch Thread | `invokeLater`, `javax.swing.Timer` |
| Tests | `CountDownLatch`, `ExecutorService` | `BidManagerTest`, `StressTestClient` |

`placeBid`, `setProxyBid`, `createAuction`, `closeAuction`, `buyNow`, `relist`, and image reads used by handlers are `synchronized` on the `BidManager` instance. That single monitor is the serialization point that guarantees exactly one winner. Scheduler close tasks call `closeAuction` and therefore take the same lock: expiry cannot interleave with a bid still in flight.

Per-connection outbound writes use a private `writeLock` so a `BID_OK` reply and an `AUCTION_UPDATED` broadcast cannot corrupt the TCP stream.

### D. Databases and Transactions (JDBC)

SQLite is accessed through JDBC (`org.xerial:sqlite-jdbc`). `Database` owns the single connection (SQLite allows one writer at a time) and exposes:

```java
public synchronized <T> T inTransaction(SqlWork<T> work) throws Exception
```

Auto-commit is turned off, the work runs, then the connection commits—or, on any exception, rolls back and rethrows. Nested calls on the same thread join the outer transaction. DAOs use `PreparedStatement` (no string-concatenated SQL) and `try-with-resources` so statements and result sets close even on failure.

Two operations are required to be atomic:

1. **Accept a bid** — `INSERT` into `bids` and `UPDATE` the auction (price, leader, end time, bid count, version) in one transaction.
2. **Settle an auction** — set `status = CLOSED` and `INSERT` into `sales` in one transaction.

Every mutating `UPDATE` includes `AND status = 'OPEN' AND version = ?` and increments `version`. Zero rows changed becomes `OptimisticLockException` and a rollback. The version column is intentionally redundant with the `BidManager` lock: if a future bug lets two updates race, the failure mode is a rejected bid, not a corrupted lot. `PRAGMA foreign_keys = ON` and WAL journal mode are enabled at open.

### E. Object-Oriented Design

Packages separate protocol, security, persistence, server, and client. Domain snapshots are Java **records** (`AuctionInfo`, `BidInfo`, `ListingRequest`, `UserRecord`, `SaleRecord`, `ProxyBid`)—immutable data carriers with generated accessors. `View` in `MainFrame` is an **enum**. Money parsing is centralized in `Money` so the UI, protocol, and database share one definition of a cent. DAOs encapsulate SQL; `BidManager` encapsulates business rules; Swing classes do not issue SQL.

### F. Collections Framework

| Structure | Use |
| --- | --- |
| `ArrayList` | Catalog of `AuctionInfo` on the client; bid lists |
| `HashMap` / `HashSet` | Photo cache, watch IDs, “my bids” IDs |
| `CopyOnWriteArrayList` | Connected handlers (safe iteration during broadcast) |
| `ConcurrentHashMap` | Scheduled close tasks by auction id |
| `DefaultListModel` | Live bid feed and ticker |

### G. Exception Handling and Robustness

Checked `SQLException` and `IOException` are handled at the boundary (handler sends `*_FAIL`; client shows a dialog). Business-rule failures return `BidResult.rejected(...)` rather than throwing through the socket. `OptimisticLockException` is a dedicated type so a version mismatch cannot be mistaken for a generic SQL error. Password hashing failures (missing JCE algorithm) throw `IllegalStateException` because the process cannot operate safely without PBKDF2.

### H. Security APIs (javax.crypto)

`PasswordHasher` uses `SecureRandom` for a 16-byte per-user salt, `PBEKeySpec` / `SecretKeyFactory` for **PBKDF2-HMAC-SHA256** at **210,000** iterations and a 256-bit key, and `MessageDigest.isEqual` for constant-time compare. An application **pepper** is read from `OPENBID_PEPPER` (a development default is used only when unset). A stolen `openbid.db` is not sufficient for an offline attack: the pepper lives outside the database.

### I. Language and Tooling

The project targets **Java 17** (`maven.compiler.release`). It uses text blocks in SQL, switch expressions in the client view filter, and records. Tests are **JUnit 5** (`org.junit.jupiter`). The Maven Wrapper, compiler plugin, Surefire, and Shade plugin produce two executable JARs. This is standard professional Java packaging, not an IDE-only run configuration.

---

## VI. Feature Catalog

All of the following are implemented in the delivered code, not mocked in the UI.

### A. Accounts and Session

- Register and log in over TCP.
- Salted, peppered password hashes; never plaintext.
- Session bound to the socket: after `LOGIN_OK` / `REGISTER_OK` the handler stores `userId` and `username`.
- Log out returns to `LoginFrame` and closes the socket.

### B. Catalog and Discovery

- Live table of all lots with price, leader, countdown, and status.
- Views: All, Ending soon (≤ 2 minutes), Watching, My listings, My bids, Won.
- Search over title, description, and seller name.
- Category filter.
- Ending-soon and anti-snipe color in the time column.
- Site-wide live activity ticker.
- Outbid and you-won toast banners.
- Dark / light theme.

### C. Listing

- Title, description, category, starting price, duration.
- Optional **reserve** (private floor). Other users see only “Reserve met” / “Reserve not met”; the seller sees the amount. If time expires below reserve, the lot is **unsold**—the high bidder does not win.
- Optional **Buy It Now**. A buyer may pay that price immediately. The button remains available until the current price reaches the BIN amount. The seller never sees BIN on their own lot.
- Optional photo (resized JPEG). If omitted, `CatalogImage` generates a catalog JPEG.
- After listing, the seller’s table selects the new lot immediately.

### D. Bidding

- Ordinary bid with a $1.00 (100 cent) minimum increment.
- First bid may equal the starting price.
- Current leader cannot place another ordinary bid (must use proxy to raise their max).
- Seller cannot bid.
- Bids after close are rejected.
- Live bid feed; proxy raises tagged `auto`.

### E. Proxy Bidding (Stretch Goal)

A user sets a private maximum (`PROXY_BID`), stored in `proxy_bids` and never broadcast. After every accepted bid or new proxy, `resolveProxies` runs **under the BidManager lock**. It does not raise by one increment in a loop (that would spam the feed). It jumps to the deciding amounts:

- No leader yet → the strongest eligible proxy bids at the starting price.
- Challenger’s max **>** leader’s max → challenger leads at one increment over the leader’s defense.
- Challenger’s max **<** leader’s max → challenger is pushed to their max; the leader auto-defends at one increment above that.
- Equal maxima → the earlier proxy keeps the lead.

The highest maximum wins at one increment over the second-highest. Every automatic raise is a real `bids` row with `is_proxy = 1`. Unit test `proxyHighestWinsAtIncrementOverSecond`: Alice $50, Bob $30, start $10 → Alice leads at $31.00.

### F. Anti-Snipe (Stretch Goal)

Any *accepted* bid within 30 seconds of `end_time` moves `end_time` to `now + 30s` **inside the same transaction as the bid**. The scheduler cancels and re-arms. `AUCTION_EXTENDED` is broadcast. The detail panel shows “Anti-snipe · extended +30 seconds.” Two last-second bids are still serialized: the first may extend the clock; the second either raises or is rejected against the new price. They cannot both be the winner.

### G. Close, Sale, Relist, Receipt

- Scheduler close and Buy It Now both call `closeAuction` under the same lock.
- Sale is recorded if and only if there is a leader *and* the reserve (if any) is met.
- `sales.auction_id` is `UNIQUE`; closing twice records one sale.
- Seller may **relist** an unsold closed lot (new open auction, same photo and prices).
- Winner may **Save receipt** as CSV (item, category, seller, buyer, price, end time).

### H. Photos and Watchlist

- Images persist in `auction_images`. Clients prefetch on `AUCTION_NEW` / `GET_AUCTIONS_OK` and always `GET_IMAGE` when a row is selected, so a photo attached by the seller is visible to every other logged-in client.
- Watch / unwatch; Watching view; watch alerts on the ticker/toasts.

### I. Demo Mode

`--demo` seeds peter, harry, jane, and mohan (`password000`) and ten lots (photos, categories, a reserve, Buy It Now, short timers, and a live proxy battle on the comic). A later `--demo` start restaggers unsold clocks (up to ten live lots with mixed remaining times) and leaves sold lots sold. Demo names are printed only in the server terminal.

---

## VII. Protocol, Persistence, and Security

### A. Wire Protocol

Requests and replies:

| Request | Reply |
| --- | --- |
| `REGISTER\|user\|password` | `REGISTER_OK` / `REGISTER_FAIL` |
| `LOGIN\|user\|password` | `LOGIN_OK` / `LOGIN_FAIL` |
| `LIST_ITEM\|title\|desc\|cents\|seconds\|category\|reserve\|buyNow\|jpegB64` | `LIST_ITEM_OK` / `LIST_ITEM_FAIL` |
| `BID\|auctionId\|cents` | `BID_OK` / `BID_REJECTED` |
| `PROXY_BID\|auctionId\|maxCents` | `PROXY_BID_OK` / `PROXY_BID_FAIL` |
| `BUY_NOW\|auctionId` | `BUY_NOW_OK` / `BUY_NOW_FAIL` |
| `GET_AUCTIONS` | `GET_AUCTIONS_OK` |
| `GET_BIDS\|auctionId` | `GET_BIDS_OK` |
| `GET_IMAGE\|auctionId` | `IMAGE_OK` / `IMAGE_FAIL` |
| `WATCH` / `UNWATCH` / `GET_WATCHES` | `WATCHES_OK` / `WATCH_FAIL` |
| `RELIST\|auctionId\|seconds` | `RELIST_OK` / `RELIST_FAIL` |

Pushed events: `NEW_BID`, `AUCTION_UPDATED`, `AUCTION_EXTENDED`, `AUCTION_CLOSED`, `AUCTION_NEW`, `YOU_OUTBID`, `YOU_WON`, `TICKER`, `WATCH_ALERT`.

`AuctionInfo` occupies 20 wire fields, including category, reserve, buy-now, original end time, snipe-extended flag, and `hasImage`.

### B. Schema

| Table | Purpose |
| --- | --- |
| `users` | id, unique username, salt, password hash, created_at |
| `auctions` | seller, title, description, prices, leader, times, category, reserve, buy-now, `OPEN`/`CLOSED`, **version**, bid_count |
| `bids` | auction, bidder, amount_cents, is_proxy, created_at |
| `sales` | auction (unique), buyer, amount, created_at |
| `proxy_bids` | `(auction_id, bidder_id)`, private max_cents |
| `auction_images` | JPEG blob keyed by auction id |
| `watches` | `(user_id, auction_id)` |

Times are epoch milliseconds. Money is integer cents.

### C. Authentication Boundary

Until `LOGIN_OK` / `REGISTER_OK`, the handler rejects every command except register and login. That is a core security property of the socket protocol, not only of the Swing form.

---

## VIII. Path of a Bid (Four Concepts, One Sequence)

The following sequence is the load-bearing argument of the project.

1. The user types an amount in `AuctionDetailPanel`. The client writes `BID|<auctionId>|<amountCents>` to its `Socket`.
2. The `ClientHandler` for that connection—running on the pool of 64 threads—parses the line and calls `BidManager.placeBid`.
3. `placeBid` is `synchronized`. A second bid on another handler thread waits. Validation: lot is `OPEN`, bidder is not the seller, bidder is not already leading, amount is at least one increment above the current price (or at start if there is no leader).
4. A valid bid is one JDBC transaction: insert `bids`, then `UPDATE auctions SET … version = version + 1 WHERE id = ? AND status = 'OPEN' AND version = ?`. Zero rows → `OptimisticLockException` → rollback.
5. Still inside the lock, proxy resolution may insert automatic bids. If the bid landed in the last 30 seconds, `end_time` is extended in the same transaction and the scheduler is re-armed.
6. The server broadcasts `NEW_BID` and `AUCTION_UPDATED` (and `AUCTION_EXTENDED` / `YOU_OUTBID` as needed). The losing concurrent bidder then enters the lock, fails the price check, and receives `BID_REJECTED` with the *new* current price.
7. Each client’s listener thread is blocked on `readLine()`. It posts the line to the EDT. The table, price label, countdown color, and bid feed repaint together.

Buy It Now follows the same lock: a bid at the BIN amount, then `closeAuction`. Scheduler expiry follows the same lock: if `now < end_time` (anti-snipe moved the clock), the close is skipped and the task is re-armed.

---

## IX. Evaluation

The proposal listed four evaluation criteria. Each was tested. The suite is **26 JUnit 5 tests** plus `StressTestClient`. `./mvnw package` runs the JUnit suite.

### A. Concurrency — Simultaneous Bids, Exactly One Winner

`BidManagerTest.concurrentBidsSingleWinner` starts 16 threads, releases them from a `CountDownLatch`, and has each fire 25 bids (400 attempts) at one in-memory auction. After join it asserts:

- accepted + rejected = 400;
- rows in `bids` = number accepted;
- amounts in time order are **strictly increasing**;
- current price equals the last accepted bid;
- a single `leader_id`, equal to that last bidder.

`StressTestClient` repeats the experiment over real TCP: 12 socket clients, a latch, 20 bids each (240 attempts) against a live server, then reads `openbid.db` and prints `recorded==accepted`, strictly increasing amounts, price matching the top bid, and a single leader.

### B. Atomicity — Failure Mid-Transaction Leaves a Consistent Database

- `forcedFailureAfterFirstWriteRollsBack` — insert a user, throw; the username is absent afterwards.
- `uniqueViolationRollsBackEarlierWrite` — insert `newcomer` then a duplicate `taken` in one transaction; `newcomer` does not survive.
- `versionMismatchRollsBackInsertedBid` — bid insert plus a wrong-version `UPDATE`; bid count stays 0.
- `closeTwiceRecordsOneSale` — `sales` still has exactly one row.

### C. Functionality — Lifecycle and Features

| Test | What it shows |
| --- | --- |
| `listItemAppearsOpen` | Listing creates an `OPEN` row at the starting price |
| `uploadedPhotoIsStoredAndReadableUnderLock` | JPEG stored and read through `BidManager` |
| `firstBidAtStartingPrice` | First bid may equal the start |
| `rejectTooLow` / `rejectSeller` / `rejectSelfOutbid` | Bid rules |
| `antiSnipeExtendsEndTime` | Bid on a 10 s lot extends `end_time` by ~30 s |
| `closeRecordsSale` / `unsoldWhenNoBids` / `bidAfterCloseRejected` | Settlement |
| `proxyHighestWinsAtIncrementOverSecond` / `proxySellerRejected` | Proxy semantics |
| `reserveNotMetEndsUnsold` | Close below reserve is not a sale |
| `buyNowClosesAndRecordsSale` | BIN closes and records a sale |
| `buyNowStillAvailableAfterALowerBid` | BIN remains until price reaches it |
| `refreshDemoListingsReopensUnsoldLeavesSold` | Demo clocks; sold lots stay sold |
| `relistUnsoldCreatesNewOpenLot` | Seller relist |
| `hashAndVerifyRoundTrip` / `wrongPasswordIsRejected` | Password hashing |
| `roundTripPlainFields` / `escapedPipeAndNewlineSurvive` | Wire protocol |

Manually, several client windows logged in as peter, harry, jane, and mohan show a bid in one window appearing in the others without refresh, and a close announced everywhere.

### D. Usability — UI Stays Responsive

Network I/O never runs on the EDT. Updates are small pushed events, not full-table polls. Countdowns are a 1-second Swing timer that only repaints. The table stays selectable while other clients bid.

---

## X. Design Decisions and Limitations

**Where to put the lock.** An early sketch locked per auction. Proxy resolution and settlement need a consistent view of the bid table and the auction row, and SQLite already allows only one writer. A single `BidManager` monitor is simpler to prove correct. At the scale of this project (hundreds of in-process bids in the concurrent test) it is not the bottleneck.

**Belt and suspenders.** The `version` column is redundant with the lock on purpose. The evaluation question is what happens if a bug lets two updates race: the optimistic check turns silent corruption into a rolled-back transaction.

**Money as integer cents.** Amounts are `long` cents from `Money.parseToCents` through the protocol into SQLite. The field the whole system serializes on never goes through `double`.

**Request/response and broadcasts on one socket.** Every request has a typed reply distinct from event types, so a client (and `StressTestClient`) can wait for `BID_OK` / `BID_REJECTED` while still collecting `NEW_BID` in the same inbox.

**Proxy battles without bid spam.** Raising by one increment in a loop would insert hundreds of rows when two large maxima meet. Jumping to the loser’s maximum and then one increment above matches auction-house behaviour and keeps the feed readable.

**Demo clocks versus production clocks.** `--demo` may restagger unsold end times on restart so a class demonstration is not a board of expired lots. A start without `--demo` does not rewrite clocks; that would be incorrect for a real auction house.

**Limitations.** The server is a single process with one SQLite writer—appropriate for a course project, not a geo-replicated marketplace. Photos are modest JPEGs on the same line protocol, not a separate blob store. There is no payment gateway; a CSV receipt is the settlement artifact.

---

## XI. Conclusion

OpenBid is the application described in the proposal: Swing, sockets, multithreading, and JDBC transactions in one system where the pieces depend on each other. Concurrent requests arrive over the network, are serialized by the server’s lock, are committed atomically to SQLite (with a version check as a second safeguard), and the result is pushed back to every GUI client on the Event Dispatch Thread. Anti-sniping, proxy bidding, Buy It Now, reserve prices, photos, and receipts use that same path rather than sitting beside it.

The original question—how does an auction site stop two last-second bidders from both winning?—has a concrete answer in this codebase: make every bid pass through one serialization point, make every state change a single transaction, close the lot under the same lock as bidding, and tell everyone what happened the moment it does.

---

## References

[1] Oracle, *The Java Tutorials: Concurrency*, “Synchronization,” and “The Event Dispatch Thread.”  
[2] Oracle, *JDBC Database Access* (The Java Tutorials).  
[3] IETF RFC 8018, PKCS #5: Password-Based Cryptography Specification Version 2.1 (PBKDF2).  
[4] SQLite, *Atomic Commit In SQLite* and *Write-Ahead Logging*.  
[5] eBay, proxy bidding and reserve-price semantics (industry reference for increment-over-second-highest and unmet reserve).  
[6] CS-GY 6103 course project specification: advanced concepts (GUI, networking, multithreading, databases) and stretch goals.

---

## Appendix A — How to Build and Run

Requires **Java 17+**. The repository includes the Maven Wrapper.

```bash
cd ~/Documents/Java/Project/OpenBid
./mvnw package
```

That compiles the sources, runs all 26 tests, and writes `target/openbid-server.jar` and `target/openbid-client.jar`.

**Server** (leave this terminal open):

```bash
java -jar target/openbid-server.jar --demo
```

Demo accounts are printed in that terminal (`password000`). Optional flags: `--port 9000`, `--db openbid.db`. Pepper: `OPENBID_PEPPER`. Delete `openbid.db` only for a full re-seed of the ten sample lots.

**Clients** — one window per user:

```bash
java -jar target/openbid-client.jar
```

Host `localhost`, port `9000`.

**Concurrent-bid proof** (server already running):

```bash
java -cp target/openbid-server.jar com.openbid.tools.StressTestClient
```

In Eclipse: **File → Import → Existing Maven Projects**, run `ServerMain` with program argument `--demo`, then run `ClientMain` several times.

---

## Appendix B — Principal Types

| Area | Types |
| --- | --- |
| Protocol | `Protocol`, `AuctionInfo`, `BidInfo`, `Money`, `Categories`, `CatalogImage` |
| Security | `PasswordHasher` |
| Persistence | `Database`, `SqlWork`, `OptimisticLockException`, `UserDao`, `AuctionDao`, `BidDao`, `SaleDao`, `ProxyBidDao`, `ImageDao`, `WatchDao`, `schema.sql` |
| Server | `ServerMain`, `AuctionServer`, `ClientHandler`, `BidManager`, `AuctionScheduler`, `BidResult`, `CloseResult`, `ListingRequest` |
| Client | `ClientMain`, `ServerConnection`, `UiTheme`, `LoginFrame`, `MainFrame`, `AuctionTableModel`, `AuctionDetailPanel`, `ListItemDialog` |
| Tools | `StressTestClient` |
| Tests | `PasswordHasherTest`, `ProtocolTest`, `DatabaseTransactionTest`, `BidManagerTest` (20 cases) |
