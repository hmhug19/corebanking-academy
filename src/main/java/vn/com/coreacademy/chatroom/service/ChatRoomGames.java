package vn.com.coreacademy.chatroom.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.Bet;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.DiceRound;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.Rank;

@Service
@RequiredArgsConstructor
public class ChatRoomGames {
    public static final long MAX_STAKE = 1_000_000;
    static final long MIN_CARO_STAKE = 10;
    private static final int DAILY_TOKENS = 50;
    private static final int ROUND_SECONDS = 30;
    private static final ZoneId ROOM_DAY = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> DICE_SIDES = Set.of("upper", "lower");
    private final ChatRoomGameRepository repository;

    public record DiceResult(long round, List<Integer> dice, int total, String outcome) {}
    public record DiceTable(DiceRound round, List<DiceResult> history, List<Bet> bets, List<Bet> lastBets) {}
    public record DiceTick(DiceTable table, long openRound, long closesAt) {}

    @Transactional
    public long claim(String userId) {
        repository.claim(userId, LocalDate.now(ROOM_DAY), DAILY_TOKENS);
        return balances(Set.of(userId)).getOrDefault(userId, 0L);
    }

    public Map<String, Long> balances(Set<String> users) {
        return repository.balances(users);
    }

    public List<Rank> rank() {
        return repository.rank();
    }

    @Transactional
    public DiceTick tickDice(boolean diceViewers, long knownRound, boolean refreshDice) {
        repository.lockGames();
        Instant now = Instant.now();
        DiceRound round = repository.openDice().orElse(null);
        Instant nextDeadline = now.plusSeconds(ROUND_SECONDS);
        if (round != null && !round.closesAt().isAfter(now)) {
            Instant nextFromRound = round.closesAt().plusSeconds(ROUND_SECONDS);
            if (nextFromRound.isAfter(now)) {
                nextDeadline = nextFromRound;
            }
            finishDice(round);
            round = null;
        }
        if (round == null && diceViewers) {
            round = repository.startDice(nextDeadline);
        }
        DiceTable table = null;
        if (diceViewers && (refreshDice || round.id() != knownRound)) {
            List<DiceResult> history = repository.diceHistory().stream().map(ChatRoomGames::diceResult).toList();
            List<Bet> lastBets = history.isEmpty() ? List.of() : repository.diceBets(history.get(0).round());
            table = new DiceTable(round, history, repository.diceBets(round.id()), lastBets);
        }
        return round == null ? new DiceTick(table, 0, 0)
            : new DiceTick(table, round.id(), round.closesAt().toEpochMilli());
    }

    @Transactional
    public boolean bet(String userId, long roundId, String side, long stake, long reserved) {
        if (!DICE_SIDES.contains(side) || stake <= 0 || stake > MAX_STAKE) {
            return false;
        }
        repository.lockGames();
        DiceRound round = repository.openDice().orElse(null);
        if (round == null || round.id() != roundId || !round.closesAt().isAfter(Instant.now())
            || repository.diceBets(roundId).stream().anyMatch(bet -> bet.userId().equals(userId))
            || !repository.debit(userId, stake, reserved)) {
            return false;
        }
        repository.bet(roundId, userId, side, stake);
        return true;
    }

    private void finishDice(DiceRound round) {
        List<Integer> dice = List.of(RANDOM.nextInt(1, 7), RANDOM.nextInt(1, 7), RANDOM.nextInt(1, 7));
        DiceResult result = diceResult(new DiceRound(round.id(), round.closesAt(), dice));
        for (Bet bet : repository.diceBets(round.id())) {
            if (bet.side().equals(result.outcome())) {
                repository.credit(bet.userId(), bet.stake() * 2);
            }
        }
        repository.finishDice(round.id(), dice);
    }

    private static DiceResult diceResult(DiceRound round) {
        List<Integer> dice = round.dice();
        int total = dice.stream().mapToInt(Integer::intValue).sum();
        boolean triple = dice.get(0).equals(dice.get(1)) && dice.get(1).equals(dice.get(2));
        return new DiceResult(round.id(), dice, total, triple ? "triple" : (total <= 10 ? "lower" : "upper"));
    }

    @Transactional
    public void settleCaro(String winner, String loser, long points, UUID transfer) {
        repository.lockGames();
        if (repository.debitRoomTransfer(loser, points, transfer)) {
            if (!repository.credit(winner, points)) {
                throw new DataIntegrityViolationException("Winner's room wallet unavailable");
            }
        } else if (!repository.roomTransferApplied(loser, transfer)) {
            throw new DataIntegrityViolationException("Reserved Caro points unavailable");
        }
    }
}
