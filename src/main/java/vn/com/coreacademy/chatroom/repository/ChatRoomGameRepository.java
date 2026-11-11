package vn.com.coreacademy.chatroom.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatRoomGameRepository {
    private final JdbcClient jdbc;

    public record Rank(String name, long balance) {}
    public record DiceRound(long id, Instant closesAt, List<Integer> dice) {}
    public record Bet(String userId, String side, long stake) {}

    /** The dice round and the two wallets of a Caro wager must change as one operation. */
    public void lockGames() {
        jdbc.sql("select pg_advisory_xact_lock(72151, 1)").query((row, index) -> true).single();
    }

    public void claim(String userId, LocalDate day, long amount) {
        jdbc.sql("""
            insert into learning_hub.chat_room_wallets as wallet (user_id, balance, last_claim_day)
            values (?, ?, ?)
            on conflict (user_id) do update
              set balance = wallet.balance + excluded.balance, last_claim_day = excluded.last_claim_day
            where wallet.last_claim_day is null or wallet.last_claim_day < excluded.last_claim_day
            """).params(userId, amount, day).update();
    }

    public Map<String, Long> balances(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select user_id, balance from learning_hub.chat_room_wallets where user_id in (:ids)")
            .param("ids", ids)
            .query((row, index) -> Map.entry(row.getString("user_id"), row.getLong("balance")))
            .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<Rank> rank() {
        return jdbc.sql("""
            select u.full_name, w.balance from learning_hub.chat_room_wallets w
            join learning_hub.users u on u.user_id = w.user_id and u.status = 'active'
            where w.balance > 0 order by w.balance desc, w.user_id limit 10
            """).query((row, index) -> new Rank(row.getString(1), row.getLong(2))).list();
    }

    public boolean debit(String userId, long amount, long reserved) {
        return amount == 0 || jdbc.sql("""
            update learning_hub.chat_room_wallets set balance = balance - ? where user_id = ? and balance >= ?
            """).params(amount, userId, amount + reserved).update() == 1;
    }

    public boolean credit(String userId, long amount) {
        return amount == 0 || jdbc.sql("update learning_hub.chat_room_wallets set balance = balance + ? where user_id = ?")
            .params(amount, userId).update() == 1;
    }

    public boolean debitRoomTransfer(String userId, long amount, UUID transfer) {
        return jdbc.sql("""
            update learning_hub.chat_room_wallets
            set balance = balance - ?, last_room_transfer = ?
            where user_id = ? and balance >= ? and last_room_transfer is distinct from ?
            """).params(amount, transfer, userId, amount, transfer).update() == 1;
    }

    public boolean roomTransferApplied(String userId, UUID transfer) {
        return jdbc.sql("""
            select exists (select 1 from learning_hub.chat_room_wallets where user_id = ? and last_room_transfer = ?)
            """).params(userId, transfer).query(Boolean.class).single();
    }

    public Optional<DiceRound> openDice() {
        return jdbc.sql("select * from learning_hub.chat_room_dice_rounds where dice is null")
            .query(ChatRoomGameRepository::diceRound).optional();
    }

    public DiceRound startDice(Instant closesAt) {
        return jdbc.sql("insert into learning_hub.chat_room_dice_rounds (closes_at) values (?) returning *")
            .param(at(closesAt)).query(ChatRoomGameRepository::diceRound).single();
    }

    public List<DiceRound> diceHistory() {
        return jdbc.sql("""
            select * from learning_hub.chat_room_dice_rounds where dice is not null order by round_id desc limit 12
            """).query(ChatRoomGameRepository::diceRound).list();
    }

    public void finishDice(long round, List<Integer> dice) {
        jdbc.sql("update learning_hub.chat_room_dice_rounds set dice = array[?,?,?]::smallint[] where round_id = ?")
            .params(dice.get(0), dice.get(1), dice.get(2), round).update();
    }

    public List<Bet> diceBets(long round) {
        return jdbc.sql("""
            select user_id, side, stake from learning_hub.chat_room_dice_bets where round_id = ? order by user_id
            """).param(round).query((row, index) -> new Bet(row.getString(1), row.getString(2), row.getLong(3))).list();
    }

    public void bet(long round, String userId, String side, long stake) {
        jdbc.sql("insert into learning_hub.chat_room_dice_bets (round_id, user_id, side, stake) values (?,?,?,?)")
            .params(round, userId, side, stake).update();
    }

    private static DiceRound diceRound(ResultSet row, int index) throws SQLException {
        Array array = row.getArray("dice");
        List<Integer> dice = List.of();
        if (array != null) {
            try {
                dice = Arrays.stream((Number[]) array.getArray()).map(Number::intValue).toList();
            } finally {
                array.free();
            }
        }
        return new DiceRound(row.getLong("round_id"), row.getObject("closes_at", OffsetDateTime.class).toInstant(), dice);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
