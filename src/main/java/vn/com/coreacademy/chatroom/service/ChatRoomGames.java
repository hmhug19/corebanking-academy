package vn.com.coreacademy.chatroom.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.Bet;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.CaroGame;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.DiceRound;
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.Rank;

@Service
@RequiredArgsConstructor
public class ChatRoomGames {
    public static final long MAX_STAKE = 1_000_000;
    static final long MIN_CARO_STAKE = 10;
    private static final int DAILY_TOKENS = 50;
    private static final int ROUND_SECONDS = 30;
    private static final int CARO_IDLE_SECONDS = 120;
    private static final int BOARD_SIZE = 50;
    static final int MAX_CARO_CELLS = BOARD_SIZE * BOARD_SIZE;
    private static final int[][] LINES = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
    private static final ZoneId ROOM_DAY = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> DICE_SIDES = Set.of("upper", "lower");
    private final ChatRoomGameRepository repository;

    public record DiceResult(long round, List<Integer> dice, int total, String outcome) {}
    public record DiceTable(DiceRound round, List<DiceResult> history, List<Bet> bets, List<Bet> lastBets) {}
    public record GameTick(DiceTable dice, List<CaroGame> expired) {}
    public record Participant(String userId, String name) {}

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
    public GameTick tick(boolean diceViewers, long knownRound, boolean refreshDice) {
        repository.lockGames();
        Instant now = Instant.now();
        List<CaroGame> expired = repository.expiredCaro(now).stream()
            .map(game -> finishCaro(game, game.moves() % 2 == 0 ? "O" : "X", now)).toList();
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
        return new GameTick(table, expired);
    }

    @Transactional
    public boolean bet(String userId, long roundId, String side, long stake) {
        if (!DICE_SIDES.contains(side) || stake <= 0 || stake > MAX_STAKE) {
            return false;
        }
        repository.lockGames();
        DiceRound round = repository.openDice().orElse(null);
        if (round == null || round.id() != roundId || !round.closesAt().isAfter(Instant.now())
            || repository.diceBets(roundId).stream().anyMatch(bet -> bet.userId().equals(userId))
            || !repository.debit(userId, stake)) {
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

    public Optional<CaroGame> activeCaro(String userId) {
        return repository.activeCaro(userId);
    }

    @Transactional
    public Optional<CaroGame> startCaro(Participant x, Participant o, long stake) {
        if (stake < MIN_CARO_STAKE || stake > MAX_STAKE || x.userId().equals(o.userId())) {
            return Optional.empty();
        }
        repository.lockGames();
        if (activeCaro(x.userId()).isPresent() || activeCaro(o.userId()).isPresent()) {
            return Optional.empty();
        }
        List<String> users = Set.of(x.userId(), o.userId()).stream().sorted().toList();
        Map<String, Long> balances = balances(Set.copyOf(users));
        if (users.stream().anyMatch(user -> balances.getOrDefault(user, 0L) < stake)) {
            return Optional.empty();
        }
        for (String user : users) {
            if (!repository.debit(user, stake)) {
                throw new IllegalStateException("Caro stake changed while games were locked");
            }
        }
        CaroGame game = new CaroGame(UUID.randomUUID(), x.userId(), o.userId(), x.name(), o.name(), stake,
            ".".repeat(MAX_CARO_CELLS), 0, null, null, Instant.now().plusSeconds(CARO_IDLE_SECONDS));
        repository.startCaro(game);
        return Optional.of(game);
    }

    @Transactional
    public Optional<CaroGame> playCaro(String userId, String opponentId, String gameId, int move, int cell) {
        if (cell < 0 || cell >= MAX_CARO_CELLS) {
            return Optional.empty();
        }
        repository.lockGames();
        Instant now = Instant.now();
        CaroGame game = activeCaro(userId).orElse(null);
        if (game == null || !game.id().toString().equals(gameId) || game.moves() != move
            || !game.expiresAt().isAfter(now) || game.board().charAt(cell) != '.') {
            return Optional.empty();
        }
        boolean xTurn = move % 2 == 0;
        if (!(xTurn ? game.xUser() : game.oUser()).equals(userId)
            || !(xTurn ? game.oUser() : game.xUser()).equals(opponentId)) {
            return Optional.empty();
        }
        char piece = xTurn ? 'X' : 'O';
        char[] board = game.board().toCharArray();
        board[cell] = piece;
        CaroGame played = new CaroGame(game.id(), game.xUser(), game.oUser(), game.xName(), game.oName(),
            game.stake(), String.valueOf(board), move + 1, cell, null, now.plusSeconds(CARO_IDLE_SECONDS));
        if (wins(board, cell, piece)) {
            return Optional.of(finishCaro(played, String.valueOf(piece), now));
        }
        if (played.moves() == board.length) {
            return Optional.of(finishCaro(played, "draw", now));
        }
        repository.updateCaro(played);
        return Optional.of(played);
    }

    private CaroGame finishCaro(CaroGame game, String result, Instant now) {
        switch (result) {
            case "X" -> repository.credit(game.xUser(), game.stake() * 2);
            case "O" -> repository.credit(game.oUser(), game.stake() * 2);
            default -> {
                repository.credit(game.xUser(), game.stake());
                repository.credit(game.oUser(), game.stake());
            }
        }
        CaroGame finished = new CaroGame(game.id(), game.xUser(), game.oUser(), game.xName(), game.oName(),
            game.stake(), game.board(), game.moves(), game.lastCell(), result, now);
        repository.updateCaro(finished);
        return finished;
    }

    static boolean wins(char[] board, int cell, char piece) {
        int x = cell % BOARD_SIZE;
        int y = cell / BOARD_SIZE;
        for (int[] line : LINES) {
            int dx = line[0];
            int dy = line[1];
            int before = 1;
            while (pieceAt(board, x - dx * before, y - dy * before) == piece) {
                before++;
            }
            int after = 1;
            while (pieceAt(board, x + dx * after, y + dy * after) == piece) {
                after++;
            }
            int length = before + after - 1;
            if (length > 5 || length == 5 && (pieceAt(board, x - dx * before, y - dy * before) == '.'
                || pieceAt(board, x + dx * after, y + dy * after) == '.')) {
                return true;
            }
        }
        return false;
    }

    private static char pieceAt(char[] board, int x, int y) {
        return x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE ? board[y * BOARD_SIZE + x] : '#';
    }
}
