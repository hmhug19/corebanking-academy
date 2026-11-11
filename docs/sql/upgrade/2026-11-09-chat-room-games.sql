BEGIN;

CREATE TABLE IF NOT EXISTS learning_hub.chat_room_wallets (
    user_id varchar(20) PRIMARY KEY,
    balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
    last_claim_day date
);
CREATE INDEX IF NOT EXISTS ix_chat_room_wallet_rank ON learning_hub.chat_room_wallets (balance DESC, user_id);
ALTER TABLE learning_hub.chat_room_wallets ADD COLUMN IF NOT EXISTS last_room_transfer uuid;

CREATE TABLE IF NOT EXISTS learning_hub.chat_room_dice_rounds (
    round_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    closes_at timestamptz NOT NULL,
    dice smallint[],
    CHECK (dice IS NULL OR (cardinality(dice) = 3 AND 1 <= ALL(dice) AND 6 >= ALL(dice)))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_open_dice_round ON learning_hub.chat_room_dice_rounds ((1)) WHERE dice IS NULL;

CREATE TABLE IF NOT EXISTS learning_hub.chat_room_dice_bets (
    round_id bigint NOT NULL,
    user_id varchar(20) NOT NULL,
    side varchar(5) NOT NULL CHECK (side IN ('upper', 'lower')),
    stake bigint NOT NULL CHECK (stake BETWEEN 1 AND 1000000),
    PRIMARY KEY (round_id, user_id)
);

-- Refund any unfinished matches from the previous, database-backed implementation.
DO $$
BEGIN
    IF to_regclass('learning_hub.chat_room_caro_games') IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(72151, 1);
        WITH closed AS (
            UPDATE learning_hub.chat_room_caro_games
            SET result = 'draw', expires_at = now()
            WHERE result IS NULL
            RETURNING x_user_id, o_user_id, stake
        ), refunds AS (
            SELECT x_user_id AS user_id, stake FROM closed
            UNION ALL
            SELECT o_user_id, stake FROM closed
        )
        INSERT INTO learning_hub.chat_room_wallets AS wallet (user_id, balance)
        SELECT user_id, sum(stake) FROM refunds GROUP BY user_id
        ON CONFLICT (user_id) DO UPDATE SET balance = wallet.balance + EXCLUDED.balance;
    END IF;
END;
$$;

-- An XP entry credits the same account in the transaction that records the award.
-- Existing XP rows predate the token programme and are not replayed.
CREATE OR REPLACE FUNCTION learning_hub.credit_chat_room_tokens_from_xp() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO learning_hub.chat_room_wallets AS wallet (user_id, balance)
    VALUES (NEW.user_id, NEW.amount)
    ON CONFLICT (user_id) DO UPDATE SET balance = wallet.balance + EXCLUDED.balance;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE TRIGGER trg_chat_room_tokens_from_xp AFTER INSERT ON learning_hub.xp_ledger
FOR EACH ROW WHEN (NEW.amount > 0) EXECUTE FUNCTION learning_hub.credit_chat_room_tokens_from_xp();

COMMIT;
