PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    salt BLOB NOT NULL,
    password_hash BLOB NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auctions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_id INTEGER NOT NULL REFERENCES users(id),
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    starting_price_cents INTEGER NOT NULL,
    current_price_cents INTEGER NOT NULL,
    leader_id INTEGER REFERENCES users(id),
    start_time INTEGER NOT NULL,
    end_time INTEGER NOT NULL,
    original_end_time INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    version INTEGER NOT NULL DEFAULT 0,
    bid_count INTEGER NOT NULL DEFAULT 0,
    category TEXT NOT NULL DEFAULT 'Other',
    reserve_cents INTEGER NOT NULL DEFAULT 0,
    buy_now_cents INTEGER NOT NULL DEFAULT 0,
    snipe_extended INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS bids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id INTEGER NOT NULL REFERENCES auctions(id),
    bidder_id INTEGER NOT NULL REFERENCES users(id),
    amount_cents INTEGER NOT NULL,
    is_proxy INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id INTEGER NOT NULL UNIQUE REFERENCES auctions(id),
    buyer_id INTEGER NOT NULL REFERENCES users(id),
    amount_cents INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS proxy_bids (
    auction_id INTEGER NOT NULL REFERENCES auctions(id),
    bidder_id INTEGER NOT NULL REFERENCES users(id),
    max_cents INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (auction_id, bidder_id)
);

CREATE TABLE IF NOT EXISTS auction_images (
    auction_id INTEGER PRIMARY KEY REFERENCES auctions(id),
    jpeg BLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS watches (
    user_id INTEGER NOT NULL REFERENCES users(id),
    auction_id INTEGER NOT NULL REFERENCES auctions(id),
    PRIMARY KEY (user_id, auction_id)
);

CREATE INDEX IF NOT EXISTS idx_bids_auction ON bids (auction_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_bids_bidder ON bids (bidder_id);
CREATE INDEX IF NOT EXISTS idx_auctions_status ON auctions (status, end_time);
CREATE INDEX IF NOT EXISTS idx_watches_auction ON watches (auction_id);
