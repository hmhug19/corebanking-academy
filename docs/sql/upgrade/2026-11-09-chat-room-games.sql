BEGIN;

CREATE TABLE IF NOT EXISTS learning_hub.chat_room_wallets (
    user_id varchar(20) PRIMARY KEY,
    balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
    last_claim_day date
);
CREATE INDEX IF NOT EXISTS ix_chat_room_wallet_rank ON learning_hub.chat_room_wallets (balance DESC, user_id);

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

CREATE TABLE IF NOT EXISTS learning_hub.chat_room_caro_games (
    game_id uuid PRIMARY KEY,
    x_user_id varchar(20) NOT NULL,
    o_user_id varchar(20) NOT NULL,
    x_name varchar(24) NOT NULL,
    o_name varchar(24) NOT NULL,
    stake bigint NOT NULL CHECK (stake BETWEEN 0 AND 1000000),
    board varchar(2500) NOT NULL,
    moves smallint NOT NULL DEFAULT 0,
    result varchar(4) CHECK (result IN ('X', 'O', 'draw')),
    expires_at timestamptz NOT NULL,
    CHECK (x_user_id <> o_user_id)
);
-- Normalize the unpublished draft schema if it was already applied.
ALTER TABLE learning_hub.chat_room_caro_games
    DROP CONSTRAINT IF EXISTS chat_room_caro_games_board_check,
    DROP CONSTRAINT IF EXISTS chat_room_caro_games_moves_check,
    ALTER COLUMN board TYPE varchar(2500),
    ADD CONSTRAINT chat_room_caro_games_board_check CHECK (char_length(board) = 2500 AND board !~ '[^.XO]'),
    ADD CONSTRAINT chat_room_caro_games_moves_check CHECK (moves BETWEEN 0 AND 2500);

CREATE INDEX IF NOT EXISTS ix_chat_room_active_caro_x ON learning_hub.chat_room_caro_games (x_user_id) WHERE result IS NULL;
CREATE INDEX IF NOT EXISTS ix_chat_room_active_caro_o ON learning_hub.chat_room_caro_games (o_user_id) WHERE result IS NULL;
CREATE INDEX IF NOT EXISTS ix_chat_room_active_caro_expiry ON learning_hub.chat_room_caro_games (expires_at) WHERE result IS NULL;

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
