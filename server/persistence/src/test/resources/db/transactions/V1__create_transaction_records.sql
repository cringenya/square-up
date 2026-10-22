CREATE TABLE transaction_records (
    id INTEGER PRIMARY KEY,
    label TEXT NOT NULL,
    CONSTRAINT uq_transaction_records_label UNIQUE (label) DEFERRABLE INITIALLY DEFERRED
);
