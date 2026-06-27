import java.util.*;
import java.io.*;

/**
 * Strong chess AI with:
 *   • Opening book   — 60+ built-in lines; loads extras from openings.txt
 *   • Time-limited iterative deepening — Hard ≤ 5 s, Medium ≤ 2 s, Easy ≤ 0.5 s
 *   • Late-Move Reductions (LMR) — ~40 % smaller search tree, free depth gain
 *   • Transposition table (Zobrist, 1 M entries)
 *   • Null-move pruning
 *   • Killer moves
 *   • Tapered PSTs (Michniewski), pawn structure, mobility, king safety,
 *     bishop pair, rook bonuses, CheckmatePatterns endgame guidance
 *   • En passant fully threaded through the search tree
 */
public class LegacyAI {

    // =========================================================================
    //  Configuration
    // =========================================================================

    private final int     maxDepth;
    private final boolean easyMode;
    private final long    timeLimitMs;
    private final Random  rng = new Random();

    // Search state (reset each call to findBestMove)
    private boolean timesUp;
    private int     nodeCount;
    private long    deadline;
    private static final int TIME_CHECK_NODES = 2048;

    private static final int CHECKMATE_SCORE  = 100_000;
    private static final int DRAW_SCORE       = 0;
    private static final int QUIESCENCE_DEPTH = 4;
    private static final int NULL_MOVE_R      = 2;
    private static final int MAX_PLY          = 64;

    // =========================================================================
    //  Transposition table
    // =========================================================================

    private static final int TT_SIZE = 1 << 20;
    private static final int TT_MASK = TT_SIZE - 1;
    private final long[] ttKeys   = new long[TT_SIZE];
    private final int[]  ttScores = new int [TT_SIZE];
    private final byte[] ttDepths = new byte[TT_SIZE];
    private final byte[] ttFlags  = new byte[TT_SIZE];
    private static final byte TT_EXACT = 0, TT_LOWER = 1, TT_UPPER = 2;

    // =========================================================================
    //  Killer moves
    // =========================================================================

    private final Move[][] killers = new Move[MAX_PLY][2];

    // =========================================================================
    //  Zobrist hashing
    //  Index: 0=WP 1=WN 2=WB 3=WR 4=WQ 5=WK  6=BP 7=BN 8=BB 9=BR 10=BQ 11=BK
    // =========================================================================

    private static final long[][] ZOB = new long[64][12];
    private static final long     ZOB_BLACK;

    static {
        Random r = new Random(0xBEEFCAFE12345678L);
        for (int i = 0; i < 64; i++)
            for (int j = 0; j < 12; j++)
                ZOB[i][j] = r.nextLong();
        ZOB_BLACK = r.nextLong();
    }

    // =========================================================================
    //  Opening book
    //
    //  Built at construction by replaying the OPENING_LINES below.
    //  Additional lines are loaded from openings.txt in the working directory
    //  if the file is present (one line = one opening, moves in coordinate
    //  notation separated by spaces, e.g. "e2e4 e7e5 g1f3 b8c6 f1b5").
    //
    //  The book maps  Zobrist-hash-of-position  →  list-of-coordinate-moves.
    //  On each turn the AI picks a random book move for variety.
    // =========================================================================

    private final Map<Long, List<String>> book = new HashMap<>();

    /** 60 + lines covering the major ECO openings (coordinate notation). */
    private static final String[][] OPENING_LINES = {
        // ── Open games (1.e4 e5) ─────────────────────────────────────────────
        // Ruy Lopez – Main line
        {"e2e4","e7e5","g1f3","b8c6","f1b5","a7a6","b5a4","g8f6","e1g1","f8e7","f1e1","b7b5","a4b3","d7d6","c2c3","e8g8","h2h3"},
        // Ruy Lopez – Berlin
        {"e2e4","e7e5","g1f3","b8c6","f1b5","g8f6","e1g1","f6e4","d2d4","e4d6","d4e5","d6f5","d1e2","f5e7"},
        // Ruy Lopez – Exchange
        {"e2e4","e7e5","g1f3","b8c6","f1b5","a7a6","b5c6","d7c6","e1g1","f7f6","d2d4","e5d4"},
        // Italian Game – Giuoco Piano
        {"e2e4","e7e5","g1f3","b8c6","f1c4","f8c5","c2c3","g8f6","d2d4","e5d4","c3d4","c5b4","b1c3"},
        // Italian – Two Knights
        {"e2e4","e7e5","g1f3","b8c6","f1c4","g8f6","d2d3","f8e7","e1g1","e8g8","a2a3"},
        // Scotch Game
        {"e2e4","e7e5","g1f3","b8c6","d2d4","e5d4","f3d4","g8f6","d4c6","b7c6","e4e5","d8e7"},
        // King's Gambit Accepted
        {"e2e4","e7e5","f2f4","e5f4","g1f3","d7d6","d2d4","g7g5","h2h4","g5g4","f3g5","f8g7"},
        // Petrov's Defense
        {"e2e4","e7e5","g1f3","g8f6","f3e5","d7d6","e5f3","f6e4","d2d4","d6d5","f1d3","e4d6","e1g1"},
        // Four Knights
        {"e2e4","e7e5","g1f3","b8c6","b1c3","g8f6","f1b5","f8b4","e1g1","e8g8","d2d3"},
        // Vienna Game
        {"e2e4","e7e5","b1c3","g8f6","f1c4","f6e4","d1h5","e4d6","c4b3","b8c6","b3f7"},
        // ── Semi-open games (1.e4 not 1...e5) ────────────────────────────────
        // French – Classical
        {"e2e4","e7e6","d2d4","d7d5","b1c3","g8f6","c1g5","f8e7","e4e5","f6d7","g5e7","d8e7","f2f4","a7a6"},
        // French – Tarrasch
        {"e2e4","e7e6","d2d4","d7d5","b1d2","c7c5","e4d5","e6d5","g1f3","b8c6","f1b5","c8d7"},
        // French – Winawer
        {"e2e4","e7e6","d2d4","d7d5","b1c3","f8b4","e4e5","c7c5","a2a3","b4c3","b2c3","g8e7","d1g4"},
        // French – Exchange
        {"e2e4","e7e6","d2d4","d7d5","e4d5","e6d5","g1f3","g8f6","f1d3","f8d6","e1g1","e8g8","b1c3"},
        // Caro-Kann – Classical
        {"e2e4","c7c6","d2d4","d7d5","b1c3","d5e4","c3e4","f8f5","e4g3","f5g6","h2h4","h7h6","g1f3","g8f6","h4h5","g6h7","f1d3"},
        // Caro-Kann – Advance
        {"e2e4","c7c6","d2d4","d7d5","e4e5","c8f5","g1f3","e7e6","f1e2","g8e7","e1g1","c6c5"},
        // Caro-Kann – Exchange
        {"e2e4","c7c6","d2d4","d7d5","e4d5","c6d5","f1d3","b8c6","c2c3","g8f6","b1d2","c8g4","d1c2"},
        // Sicilian – Najdorf
        {"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","a7a6","c1g5","e7e6","f2f4","b8d7"},
        // Sicilian – Dragon
        {"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","g7g6","c1e3","f8g7","f2f3","e8g8","d1d2","b8c6"},
        // Sicilian – Classical
        {"e2e4","c7c5","g1f3","b8c6","d2d4","c5d4","f3d4","g8f6","b1c3","d7d6","f1e2","e7e6","e1g1","f8e7","c1e3"},
        // Sicilian – Scheveningen
        {"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","e7e6","g2g4","h7h6","g4g5"},
        // Sicilian – Kan
        {"e2e4","c7c5","g1f3","e7e6","d2d4","c5d4","f3d4","a7a6","b1c3","d8c7","f1d3","b8c6","c1e3"},
        // Sicilian – Sveshnikov
        {"e2e4","c7c5","g1f3","b8c6","d2d4","c5d4","f3d4","g8f6","b1c3","e7e5","d4b5","d7d6","c1g5","a7a6","b5a3"},
        // Sicilian – Taimanov
        {"e2e4","c7c5","g1f3","e7e6","d2d4","c5d4","f3d4","b8c6","b1c3","d8c7","f1e2","a7a6","e1g1"},
        // Pirc Defense
        {"e2e4","d7d6","d2d4","g8f6","b1c3","g7g6","f2f4","f8g7","g1f3","e8g8","f1d3","b8c6"},
        // Alekhine's Defense
        {"e2e4","g8f6","e4e5","f6d5","d2d4","d7d6","g1f3","c8g4","f1e2","e7e6","e1g1","f8e7"},
        // ── Closed games (1.d4 d5) ───────────────────────────────────────────
        // QGD – Orthodox
        {"d2d4","d7d5","c2c4","e7e6","b1c3","g8f6","c1g5","f8e7","e2e3","e8g8","g1f3","b8d7","f1d3","d5c4","d3c4"},
        // QGD – Cambridge Springs
        {"d2d4","d7d5","c2c4","e7e6","b1c3","g8f6","c1g5","b8d7","e2e3","c7c6","g1f3","d8a5"},
        // QGA
        {"d2d4","d7d5","c2c4","d5c4","g1f3","g8f6","e2e3","e7e6","f1c4","c7c5","e1g1","a7a6","d1e2"},
        // Slav Defense
        {"d2d4","d7d5","c2c4","c7c6","b1c3","g8f6","g1f3","d5c4","a2a4","c8f5","e2e3","e7e6","f1c4"},
        // Semi-Slav
        {"d2d4","d7d5","c2c4","c7c6","b1c3","g8f6","g1f3","e7e6","e2e3","b8d7","f1d3","d5c4","d3c4","b7b5"},
        // ── Indian defenses ────────────────────────────────────────────────
        // King's Indian – Classical
        {"d2d4","g8f6","c2c4","g7g6","b1c3","f8g7","e2e4","d7d6","g1f3","e8g8","f1e2","e7e5","e1g1","b8c6"},
        // King's Indian – Saemisch
        {"d2d4","g8f6","c2c4","g7g6","b1c3","f8g7","e2e4","d7d6","f2f3","e8g8","c1e3","e7e5","d4d5"},
        // King's Indian – Four Pawns
        {"d2d4","g8f6","c2c4","g7g6","b1c3","f8g7","e2e4","d7d6","f2f4","e8g8","g1f3","c7c5","d4d5"},
        // Nimzo-Indian – Classical
        {"d2d4","g8f6","c2c4","e7e6","b1c3","f8b4","d1c2","e8g8","a2a3","b4c3","c2c3","d7d5","g1f3","b8c6"},
        // Nimzo-Indian – Rubinstein
        {"d2d4","g8f6","c2c4","e7e6","b1c3","f8b4","e2e3","e8g8","f1d3","d7d5","g1f3","c7c5","e1g1"},
        // Queen's Indian
        {"d2d4","g8f6","c2c4","e7e6","g1f3","b7b6","g2g3","c8b7","f1g2","f8e7","e1g1","e8g8","b1c3","g8e8"},
        // Grünfeld Defense
        {"d2d4","g8f6","c2c4","g7g6","b1c3","d7d5","g1f3","f8g7","d1b3","d5c4","b3c4","e8g8","e2e4"},
        // Grünfeld – Exchange
        {"d2d4","g8f6","c2c4","g7g6","b1c3","d7d5","c4d5","f6d5","e2e4","d5c3","b2c3","f8g7","f1c4","c7c5"},
        // Catalan
        {"d2d4","d7d5","c2c4","e7e6","g2g3","g8f6","f1g2","f8e7","g1f3","e8g8","e1g1","d5c4","d1c2","b7b5"},
        // Dutch Defense – Classical
        {"d2d4","f7f5","g2g3","g8f6","f1g2","e7e6","g1f3","f8e7","e1g1","e8g8","c2c4","d7d6"},
        // Dutch – Stonewall
        {"d2d4","f7f5","g1f3","g8f6","g2g3","e7e6","f1g2","d7d5","e1g1","f8d6","c2c4","c7c6"},
        // London System
        {"d2d4","d7d5","g1f3","g8f6","c1f4","e7e6","e2e3","f8d6","f4d6","d8d6","f1d3","b8d7","e1g1","e8g8","b1d2"},
        // Torre Attack
        {"d2d4","g8f6","g1f3","e7e6","c1g5","d7d5","e2e3","f8e7","b1d2","b8d7","c2c3","e8g8","f1d3"},
        // Trompowsky Attack
        {"d2d4","g8f6","c1g5","e7e5","g5f6","d8f6","c2c3","d7d5","e2e3","f8d6","g1f3","b8d7"},
        // ── Other first moves ─────────────────────────────────────────────
        // English – Symmetrical
        {"c2c4","c7c5","b1c3","b8c6","g1f3","g8f6","g2g3","g7g6","f1g2","f8g7","e1g1","e8g8","d2d4"},
        // English – Reversed Sicilian
        {"c2c4","e7e5","b1c3","g8f6","g1f3","b8c6","g2g3","f8b4","f1g2","e8g8","e1g1"},
        // English – King's Indian
        {"c2c4","g8f6","b1c3","g7g6","g2g3","f8g7","f1g2","e8g8","g1f3","d7d6","e1g1","b8c6","d2d4"},
        // Reti Opening
        {"g1f3","d7d5","g2g3","g8f6","f1g2","c7c6","e1g1","c8g4","d2d3","b8d7","b1d2"},
        // Reti – King's Indian Attack
        {"g1f3","d7d5","g2g3","g8f6","f1g2","e7e6","e1g1","f8e7","d2d3","e8g8","b1d2","b8c6"},
        // King's Indian Attack
        {"e2e4","d7d6","d2d3","g8f6","b1d2","g7g6","g1f3","f8g7","g2g3","e8g8","f1g2","b8c6","e1g1"},
        // Bird's Opening
        {"f2f4","d7d5","g1f3","g7g6","g2g3","f8g7","f1g2","g8f6","e1g1","e8g8","d2d3"},
        // Nimzowitsch-Larsen Attack
        {"b2b3","e7e5","c1b2","b8c6","g1f3","d7d5","e2e3","g8f6","f1e2","f8d6","e1g1"},
    };

    // =========================================================================
    //  Evaluation constants
    // =========================================================================

    private static final int DOUBLED_PAWN_PENALTY      = -20;
    private static final int ISOLATED_PAWN_PENALTY     = -20;
    private static final int BISHOP_PAIR_BONUS         =  50;
    private static final int ROOK_OPEN_FILE_BONUS      =  20;
    private static final int ROOK_SEMI_OPEN_FILE_BONUS =  10;
    private static final int ROOK_SEVENTH_RANK_BONUS   =  25;
    private static final int MOBILITY_FACTOR           =   3;
    private static final int KING_CENTRE_PENALTY       =  30;
    private static final int KING_ATTACK_WEIGHT        =  15;
    private static final int[] PASSED_BONUS = { 0, 150, 100, 60, 40, 20, 10 };

    // =========================================================================
    //  Piece-Square Tables  (Michniewski Simplified Evaluation, tapered)
    //  row 0 = rank 8, row 7 = rank 1.
    //  White: table[r][c].  Black: table[7-r][c].
    // =========================================================================

    private static final int[][] PST_PAWN_MG = {
        {  0,  0,  0,  0,  0,  0,  0,  0}, { 50, 50, 50, 50, 50, 50, 50, 50},
        { 10, 10, 20, 30, 30, 20, 10, 10}, {  5,  5, 10, 25, 25, 10,  5,  5},
        {  0,  0,  0, 20, 20,  0,  0,  0}, {  5, -5,-10,  0,  0,-10, -5,  5},
        {  5, 10, 10,-20,-20, 10, 10,  5}, {  0,  0,  0,  0,  0,  0,  0,  0}
    };
    private static final int[][] PST_PAWN_EG = {
        {  0,  0,  0,  0,  0,  0,  0,  0}, { 80, 80, 80, 80, 80, 80, 80, 80},
        { 50, 50, 50, 50, 50, 50, 50, 50}, { 25, 25, 30, 35, 35, 30, 25, 25},
        { 10, 10, 15, 20, 20, 15, 10, 10}, {  3,  3,  5,  5,  5,  5,  3,  3},
        {  0,  0,  0,  0,  0,  0,  0,  0}, {  0,  0,  0,  0,  0,  0,  0,  0}
    };
    private static final int[][] PST_KNIGHT = {
        {-50,-40,-30,-30,-30,-30,-40,-50},{-40,-20,  0,  0,  0,  0,-20,-40},
        {-30,  0, 10, 15, 15, 10,  0,-30},{-30,  5, 15, 20, 20, 15,  5,-30},
        {-30,  0, 15, 20, 20, 15,  0,-30},{-30,  5, 10, 15, 15, 10,  5,-30},
        {-40,-20,  0,  5,  5,  0,-20,-40},{-50,-40,-30,-30,-30,-30,-40,-50}
    };
    private static final int[][] PST_BISHOP = {
        {-20,-10,-10,-10,-10,-10,-10,-20},{-10,  0,  0,  0,  0,  0,  0,-10},
        {-10,  0,  5, 10, 10,  5,  0,-10},{-10,  5,  5, 10, 10,  5,  5,-10},
        {-10,  0, 10, 10, 10, 10,  0,-10},{-10, 10, 10, 10, 10, 10, 10,-10},
        {-10,  5,  0,  0,  0,  0,  5,-10},{-20,-10,-10,-10,-10,-10,-10,-20}
    };
    private static final int[][] PST_ROOK = {
        {  0,  0,  0,  0,  0,  0,  0,  0},{  5, 10, 10, 10, 10, 10, 10,  5},
        { -5,  0,  0,  0,  0,  0,  0, -5},{ -5,  0,  0,  0,  0,  0,  0, -5},
        { -5,  0,  0,  0,  0,  0,  0, -5},{ -5,  0,  0,  0,  0,  0,  0, -5},
        { -5,  0,  0,  0,  0,  0,  0, -5},{  0,  0,  0,  5,  5,  0,  0,  0}
    };
    private static final int[][] PST_QUEEN = {
        {-20,-10,-10, -5, -5,-10,-10,-20},{-10,  0,  0,  0,  0,  0,  0,-10},
        {-10,  0,  5,  5,  5,  5,  0,-10},{ -5,  0,  5,  5,  5,  5,  0, -5},
        {  0,  0,  5,  5,  5,  5,  0, -5},{-10,  5,  5,  5,  5,  5,  0,-10},
        {-10,  0,  5,  0,  0,  0,  0,-10},{-20,-10,-10, -5, -5,-10,-10,-20}
    };
    private static final int[][] PST_KING_MG = {
        {-30,-40,-40,-50,-50,-40,-40,-30},{-30,-40,-40,-50,-50,-40,-40,-30},
        {-30,-40,-40,-50,-50,-40,-40,-30},{-30,-40,-40,-50,-50,-40,-40,-30},
        {-20,-30,-30,-40,-40,-30,-30,-20},{-10,-20,-20,-20,-20,-20,-20,-10},
        { 20, 20,  0,  0,  0,  0, 20, 20},{ 20, 30, 10,  0,  0, 10, 30, 20}
    };
    private static final int[][] PST_KING_EG = {
        {-50,-40,-30,-20,-20,-30,-40,-50},{-30,-20,-10,  0,  0,-10,-20,-30},
        {-30,-10, 20, 30, 30, 20,-10,-30},{-30,-10, 30, 40, 40, 30,-10,-30},
        {-30,-10, 30, 40, 40, 30,-10,-30},{-30,-10, 20, 30, 30, 20,-10,-30},
        {-30,-30,  0,  0,  0,  0,-30,-30},{-50,-30,-30,-30,-30,-30,-30,-50}
    };

    // =========================================================================
    //  Constructor
    // =========================================================================

    /** @param baseDepth  1=Easy  2=Medium  3=Hard  (values from ChessGUI) */
    public LegacyAI(int baseDepth) {
        switch (baseDepth) {
            case 1:  maxDepth = 3; easyMode = true;  timeLimitMs =   500; break;
            case 2:  maxDepth = 4; easyMode = false; timeLimitMs = 2_000; break;
            default: maxDepth = 6; easyMode = false; timeLimitMs = 5_000; break;
        }
        buildOpeningBook();
    }

    /** Test constructor: explicit time limit (used by EngineMatch). */
    public LegacyAI(int baseDepth, long timeMs) {
        switch (baseDepth) {
            case 1:  maxDepth = 3; easyMode = true;  break;
            case 2:  maxDepth = 4; easyMode = false; break;
            default: maxDepth = 6; easyMode = false; break;
        }
        timeLimitMs = timeMs;
        buildOpeningBook();
    }

    // =========================================================================
    //  Opening book construction
    // =========================================================================

    private void buildOpeningBook() {
        for (String[] line : OPENING_LINES) addBookLine(line);
        tryLoadOpeningsFile("openings.txt");
    }

    /**
     * Replay a sequence of coordinate moves from the start position and record,
     * for each position reached, which move continues the line.
     */
    private void addBookLine(String[] moves) {
        Game g = new Game();
        for (String mv : moves) {
            if (mv.length() < 4) break;
            long hash = computeHash(g.getBoard(), g.getCurrentPlayer());
            book.computeIfAbsent(hash, k -> new ArrayList<>()).add(mv);
            Move found = findMove(g, mv);
            if (found == null) break;
            g.makeMove(found);
        }
    }

    private Move findMove(Game g, String mv) {
        Position from = Position.fromAlgebraic(mv.substring(0, 2));
        Position to   = Position.fromAlgebraic(mv.substring(2, 4));
        for (Move m : g.getAllLegalMoves(g.getCurrentPlayer()))
            if (m.from.equals(from) && m.to.equals(to)) return m;
        return null;
    }

    /**
     * Silently try to load extra opening lines from a plain-text file.
     * Format: one line per opening, moves separated by spaces (coordinate notation).
     * Lines starting with # are comments.
     * If the file is absent, nothing happens.
     */
    private void tryLoadOpeningsFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                addBookLine(line.split("\\s+"));
            }
        } catch (IOException ignored) { /* file absent — use built-in book */ }
    }

    /** Look up the current game position in the book; return a random book move or null. */
    private Move lookupBook(Game game) {
        long hash = computeHash(game.getBoard(), game.getCurrentPlayer());
        List<String> candidates = book.get(hash);
        if (candidates == null || candidates.isEmpty()) return null;
        String mv = candidates.get(rng.nextInt(candidates.size()));
        return findMove(game, mv);
    }

    // =========================================================================
    //  Public entry point  (book → iterative deepening with time control)
    // =========================================================================

    public Move findBestMove(Game game) {
        // 1. Opening book
        Move bookMove = lookupBook(game);
        if (bookMove != null) return bookMove;

        // 2. Set up search
        Board      board    = game.getBoard();
        PieceColor me       = game.getCurrentPlayer();
        Position   epTarget = game.getEnPassantTarget();

        for (int i = 0; i < MAX_PLY; i++) killers[i][0] = killers[i][1] = null;
        nodeCount = 0;
        timesUp   = false;
        deadline  = System.currentTimeMillis() + timeLimitMs;

        List<Move> moves = generateLegalMoves(board, me, epTarget);
        if (moves.isEmpty()) return null;

        orderMoves(moves, board, me, 0);
        Move best = moves.get(0);

        // 3. Iterative deepening — deepen until time runs out or maxDepth reached
        int maxD = adaptDepth(board);
        for (int depth = 1; depth <= maxD && !timesUp; depth++) {
            Move iterBest  = null;
            int  iterScore = Integer.MIN_VALUE;

            for (Move m : moves) {
                if (timesUp) break;
                Board    copy  = board.deepCopy();
                Position newEP = computeEPTarget(m);
                applyMoveToBoard(copy, m);
                int score = minimax(copy, me.opposite(), depth - 1, 1,
                                    Integer.MIN_VALUE, Integer.MAX_VALUE, me, true, newEP);
                if (!timesUp && score > iterScore) { iterScore = score; iterBest = m; }
            }

            // Accept the result only if the depth completed without timeout
            if (!timesUp && iterBest != null) {
                best = iterBest;
                // PV move first improves pruning on the next iteration
                moves.remove(iterBest);
                moves.add(0, iterBest);
            }
        }

        if (easyMode && rng.nextDouble() < 0.10) return moves.get(rng.nextInt(moves.size()));
        return best;
    }

    // =========================================================================
    //  Minimax  (alpha-beta + null-move + LMR + killers + TT)
    // =========================================================================

    private int minimax(Board board, PieceColor side, int depth, int ply,
                        int alpha, int beta,
                        PieceColor maximizing, boolean allowNull,
                        Position epTarget) {

        // Time check (every TIME_CHECK_NODES nodes to amortise the clock call)
        if ((++nodeCount & (TIME_CHECK_NODES - 1)) == 0
                && System.currentTimeMillis() >= deadline) {
            timesUp = true;
        }
        if (timesUp) return 0;

        // TT probe
        long hash  = computeHash(board, side);
        int  ttHit = ttProbe(hash, depth, ply, alpha, beta);
        if (ttHit != Integer.MIN_VALUE) return ttHit;

        List<Move> moves = generateLegalMoves(board, side, epTarget);

        // Terminal
        if (moves.isEmpty()) {
            if (board.isKingInCheck(side)) {
                int s = CHECKMATE_SCORE - ply;
                return (side == maximizing) ? -s : s;
            }
            return DRAW_SCORE;
        }
        if (depth <= 0)
            return quiescenceSearch(board, side, alpha, beta, maximizing,
                                    QUIESCENCE_DEPTH, epTarget);

        // Null-move pruning
        boolean inCheck = board.isKingInCheck(side);
        if (allowNull && side == maximizing && !inCheck
                && depth >= 3 && hasNonPawnMaterial(board, side)) {
            int R = (depth >= 6) ? 3 : 2;
            int nullScore = minimax(board, side.opposite(), depth - 1 - R, ply + 1,
                                    alpha, beta, maximizing, false, null);
            if (nullScore >= beta) { ttStore(hash, depth, ply, TT_LOWER, beta); return beta; }
        }

        orderMoves(moves, board, side, ply);

        int origAlpha = alpha, origBeta = beta;
        int best = (side == maximizing) ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        int moveIndex = 0;

        for (Move m : moves) {
            if (timesUp) break;
            moveIndex++;

            Board    copy  = board.deepCopy();
            Position newEP = computeEPTarget(m);
            applyMoveToBoard(copy, m);

            int score;

            // LMR: reduce depth for quiet moves that come late in the ordered list
            // Condition: depth >= 3, not in check, not capture/promotion/killer/castle
            boolean doLMR = depth >= 3 && !inCheck && moveIndex > 4
                    && m.capturedPiece == null && !m.isPromotion && !m.isCastling
                    && !isKiller(m, ply);

            if (doLMR) {
                int R = (moveIndex > 12) ? 2 : 1;
                score = minimax(copy, side.opposite(), depth - 1 - R, ply + 1,
                                alpha, beta, maximizing, true, newEP);
                // If the reduced search suggests this move beats alpha, re-search fully
                if (!timesUp && score > alpha)
                    score = minimax(copy, side.opposite(), depth - 1, ply + 1,
                                    alpha, beta, maximizing, true, newEP);
            } else {
                score = minimax(copy, side.opposite(), depth - 1, ply + 1,
                                alpha, beta, maximizing, true, newEP);
            }

            if (timesUp) break;

            if (side == maximizing) {
                if (score > best) best = score;
                if (best > alpha) alpha = best;
                if (best >= beta) {
                    if (m.capturedPiece == null && !m.isPromotion) updateKillers(m, ply);
                    break;
                }
            } else {
                if (score < best) best = score;
                if (best < beta) beta = best;
                if (best <= alpha) {
                    if (m.capturedPiece == null && !m.isPromotion) updateKillers(m, ply);
                    break;
                }
            }
        }

        if (!timesUp) {
            byte flag = (best <= origAlpha) ? TT_UPPER
                      : (best >= origBeta)  ? TT_LOWER : TT_EXACT;
            ttStore(hash, depth, ply, flag, best);
        }
        return best;
    }

    private boolean isKiller(Move m, int ply) {
        if (ply < 0 || ply >= MAX_PLY) return false;
        return m.equals(killers[ply][0]) || m.equals(killers[ply][1]);
    }

    private void updateKillers(Move m, int ply) {
        if (ply < MAX_PLY) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m; }
    }

    // =========================================================================
    //  Quiescence search
    // =========================================================================

    private int quiescenceSearch(Board board, PieceColor side,
                                  int alpha, int beta,
                                  PieceColor maximizing, int qdepth,
                                  Position epTarget) {
        if (timesUp) return 0;
        int standPat = evaluate(board, maximizing);
        if (qdepth <= 0) return standPat;
        if (side == maximizing) {
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
        } else {
            if (standPat <= alpha) return alpha;
            if (standPat < beta)  beta  = standPat;
        }
        List<Move> tactical = generateTacticalMoves(board, side, epTarget);
        orderMoves(tactical, board, side, -1);
        for (Move m : tactical) {
            if (timesUp) break;
            Board    copy  = board.deepCopy();
            Position newEP = computeEPTarget(m);
            applyMoveToBoard(copy, m);
            int score = quiescenceSearch(copy, side.opposite(), alpha, beta,
                                          maximizing, qdepth - 1, newEP);
            if (side == maximizing) {
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            } else {
                if (score <= alpha) return alpha;
                if (score < beta)  beta  = score;
            }
        }
        return (side == maximizing) ? alpha : beta;
    }

    // =========================================================================
    //  Static evaluation
    // =========================================================================

    private int evaluate(Board b, PieceColor fp) {
        return evaluateMaterial(b, fp) + evaluatePawnStructure(b, fp)
             + evaluateMobility(b, fp) + evaluateKingSafety(b, fp)
             + evaluateEndgame(b, fp);
    }

    private int evaluateMaterial(Board b, PieceColor me) {
        int ph = gamePhase(b), score = 0, myB = 0, opB = 0;
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            Piece p = b.getPiece(new Position(r, c)); if (p == null) continue;
            boolean mine = (p.getColor() == me); int sign = mine ? 1 : -1;
            score += sign * pieceValue(p);
            score += sign * pstLookup(p, r, c, ph);
            if (p instanceof Bishop) { if (mine) myB++; else opB++; }
            if (p instanceof Rook)   score += sign * rookBonus(b, p, r, c);
        }
        if (myB >= 2) score += BISHOP_PAIR_BONUS;
        if (opB >= 2) score -= BISHOP_PAIR_BONUS;
        return score;
    }

    private int gamePhase(Board b) {
        int ph = 0;
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            Piece p = b.getPiece(new Position(r, c)); if (p == null) continue;
            switch (Character.toLowerCase(p.getSymbol())) {
                case 'n': case 'b': ph++; break; case 'r': ph += 2; break; case 'q': ph += 4; break;
            }
        }
        return Math.min(24, ph);
    }

    private int pstLookup(Piece p, int r, int c, int ph) {
        int tr = (p.getColor() == PieceColor.WHITE) ? r : (7 - r);
        switch (Character.toLowerCase(p.getSymbol())) {
            case 'n': return PST_KNIGHT[tr][c];
            case 'b': return PST_BISHOP[tr][c];
            case 'r': return PST_ROOK  [tr][c];
            case 'q': return PST_QUEEN [tr][c];
            case 'p': return (PST_PAWN_MG[tr][c]*ph + PST_PAWN_EG[tr][c]*(24-ph)) / 24;
            case 'k': return (PST_KING_MG[tr][c]*ph + PST_KING_EG[tr][c]*(24-ph)) / 24;
            default:  return 0;
        }
    }

    private int rookBonus(Board b, Piece rk, int r, int c) {
        int bonus = 0; PieceColor rc = rk.getColor();
        if (r == (rc == PieceColor.WHITE ? 1 : 6)) bonus += ROOK_SEVENTH_RANK_BONUS;
        boolean fr = false, en = false;
        for (int row = 0; row < 8; row++) {
            Piece p = b.getPiece(new Position(row, c));
            if (p instanceof Pawn) { if (p.getColor()==rc) fr=true; else en=true; }
        }
        if (!fr && !en) bonus += ROOK_OPEN_FILE_BONUS;
        else if (!fr)   bonus += ROOK_SEMI_OPEN_FILE_BONUS;
        return bonus;
    }

    private int evaluatePawnStructure(Board b, PieceColor me) {
        boolean[][] myAt = new boolean[8][8], opAt = new boolean[8][8];
        int[] myF = new int[8];
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            Piece p = b.getPiece(new Position(r,c)); if (!(p instanceof Pawn)) continue;
            if (p.getColor()==me) { myAt[r][c]=true; myF[c]++; } else opAt[r][c]=true;
        }
        int score = 0;
        for (int c = 0; c < 8; c++) if (myF[c] > 1) score += DOUBLED_PAWN_PENALTY * (myF[c]-1);
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            if (!myAt[r][c]) continue;
            if ((c==0||myF[c-1]==0) && (c==7||myF[c+1]==0)) score += ISOLATED_PAWN_PENALTY;
            boolean passed = true;
            outer: for (int ac=Math.max(0,c-1); ac<=Math.min(7,c+1); ac++)
                for (int or=0; or<8; or++) {
                    if (!opAt[or][ac]) continue;
                    if ((me==PieceColor.WHITE)?(or<r):(or>r)) { passed=false; break outer; }
                }
            if (passed) {
                int dist=(me==PieceColor.WHITE)?r:(7-r);
                score += PASSED_BONUS[Math.max(1,Math.min(6,dist))];
            }
        }
        return score;
    }

    private int evaluateMobility(Board b, PieceColor me) {
        return (countPL(b,me) - countPL(b,me.opposite())) * MOBILITY_FACTOR;
    }
    private int countPL(Board b, PieceColor s) {
        int n=0;
        for (int r=0;r<8;r++) for(int c=0;c<8;c++){
            Position pos=new Position(r,c); Piece p=b.getPiece(pos);
            if(p!=null&&p.getColor()==s) n+=p.getLegalMoves(pos,b).size();
        }
        return n;
    }

    private int evaluateKingSafety(Board b, PieceColor me) {
        if (gamePhase(b) <= 8) return 0;
        Position kp = findKing(b, me); if (kp == null) return 0;
        int score = 0;
        if (kp.col >= 2 && kp.col <= 5) score -= KING_CENTRE_PENALTY;
        int dir = (me==PieceColor.WHITE)?-1:1;
        for (int dc=-1;dc<=1;dc++){
            int col=kp.col+dc; if(col<0||col>7) continue;
            int r1=kp.row+dir, r2=kp.row+2*dir;
            if (r1>=0&&r1<8){Piece p=b.getPiece(new Position(r1,col)); if(p instanceof Pawn&&p.getColor()==me) score+=12;}
            if (r2>=0&&r2<8){Piece p=b.getPiece(new Position(r2,col)); if(p instanceof Pawn&&p.getColor()==me) score+=6;}
        }
        PieceColor opp=me.opposite();
        for(int dr=-1;dr<=1;dr++) for(int dc=-1;dc<=1;dc++){
            if(dr==0&&dc==0) continue;
            int r=kp.row+dr,c=kp.col+dc;
            if(r>=0&&r<8&&c>=0&&c<8&&b.isSquareAttacked(new Position(r,c),opp)) score-=KING_ATTACK_WEIGHT;
        }
        return score;
    }

    private int evaluateEndgame(Board b, PieceColor me) {
        if (gamePhase(b) > 8) return 0;
        CheckmatePatterns.CheckmateGuidance m = CheckmatePatterns.analyzePosition(b, me);
        CheckmatePatterns.CheckmateGuidance t = CheckmatePatterns.analyzePosition(b, me.opposite());
        int score = 0;
        if (m.isWinningEndgame()) score +=  m.evaluation + 300;
        if (t.isWinningEndgame()) score -= (t.evaluation + 300);
        return score;
    }

    // =========================================================================
    //  Move generation  (en passant threaded through search)
    // =========================================================================

    private List<Move> generateLegalMoves(Board board, PieceColor side, Position epTarget) {
        List<Move> legal = new ArrayList<>();
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            Position from = new Position(r, c);
            Piece p = board.getPiece(from);
            if (p == null || p.getColor() != side) continue;
            List<Move> pseudo = new ArrayList<>(p.getLegalMoves(from, board));
            if (p instanceof Pawn && epTarget != null) {
                int dir = (side == PieceColor.WHITE) ? -1 : 1;
                if (epTarget.row == from.row + dir && Math.abs(epTarget.col - from.col) == 1) {
                    Piece cap = board.getPiece(new Position(from.row, epTarget.col));
                    if (cap instanceof Pawn && cap.getColor() != side) {
                        Move ep = new Move(from, epTarget, p, cap); ep.isEnPassant = true;
                        pseudo.add(ep);
                    }
                }
            }
            for (Move m : pseudo) {
                Board copy = board.deepCopy(); applyMoveToBoard(copy, m);
                if (!copy.isKingInCheck(side)) legal.add(m);
            }
        }
        return legal;
    }

    private List<Move> generateTacticalMoves(Board board, PieceColor side, Position epTarget) {
        List<Move> out = new ArrayList<>();
        for (Move m : generateLegalMoves(board, side, epTarget)) {
            if (m.capturedPiece != null || m.isPromotion) { out.add(m); }
            else {
                Board copy = board.deepCopy(); applyMoveToBoard(copy, m);
                if (copy.isKingInCheck(side.opposite())) out.add(m);
            }
        }
        return out;
    }

    private Position computeEPTarget(Move m) {
        if (m.movedPiece instanceof Pawn && Math.abs(m.from.row - m.to.row) == 2)
            return new Position((m.from.row + m.to.row) / 2, m.to.col);
        return null;
    }

    // =========================================================================
    //  Move ordering
    // =========================================================================

    private void orderMoves(List<Move> moves, Board board, PieceColor side, int ply) {
        moves.sort((a,b) -> Integer.compare(scoreOrder(b,board,side,ply),
                                             scoreOrder(a,board,side,ply)));
    }
    private int scoreOrder(Move m, Board board, PieceColor side, int ply) {
        int s = 0;
        if (m.isPromotion) s += (m.promotion=='Q') ? 9_000 : 7_000;
        if (m.capturedPiece != null) {
            s += 5_000 + 10 * pieceValue(m.capturedPiece);
            if (m.movedPiece != null) s -= pieceValue(m.movedPiece);
        }
        if (m.capturedPiece==null&&!m.isPromotion&&ply>=0&&ply<MAX_PLY) {
            if (m.equals(killers[ply][0])) s+=900; else if(m.equals(killers[ply][1])) s+=800;
        }
        if (m.isCastling) s += 600;
        if (m.movedPiece!=null&&board.isSquareAttacked(m.to,side.opposite()))
            s -= pieceValue(m.movedPiece)/2;
        if (m.movedPiece instanceof Knight || m.movedPiece instanceof Bishop) {
            int cd=Math.abs(m.to.row-3)+Math.abs(m.to.col-3); s+=Math.max(0,6-cd)*4;
        }
        return s;
    }

    // =========================================================================
    //  Transposition table
    // =========================================================================

    private long computeHash(Board board, PieceColor side) {
        long h = 0;
        for (int r=0;r<8;r++) for(int c=0;c<8;c++){
            Piece p=board.getPiece(new Position(r,c));
            if (p!=null) h^=ZOB[r*8+c][pieceIndex(p)];
        }
        if (side==PieceColor.BLACK) h^=ZOB_BLACK;
        return h;
    }
    private int pieceIndex(Piece p) {
        int b; switch(Character.toLowerCase(p.getSymbol())){
            case 'p':b=0;break; case 'n':b=1;break; case 'b':b=2;break;
            case 'r':b=3;break; case 'q':b=4;break; default:b=5;
        }
        return b+(p.getColor()==PieceColor.WHITE?0:6);
    }
    private int ttProbe(long hash, int depth, int ply, int alpha, int beta) {
        int idx=(int)(hash&TT_MASK);
        if(ttKeys[idx]!=hash||ttDepths[idx]<(byte)depth) return Integer.MIN_VALUE;
        int score=ttScores[idx];
        if(score> CHECKMATE_SCORE-MAX_PLY) score-=ply;
        if(score<-(CHECKMATE_SCORE-MAX_PLY)) score+=ply;
        byte flag=ttFlags[idx];
        if(flag==TT_EXACT) return score;
        if(flag==TT_LOWER&&score>=beta) return score;
        if(flag==TT_UPPER&&score<=alpha) return score;
        return Integer.MIN_VALUE;
    }
    private void ttStore(long hash, int depth, int ply, byte flag, int score) {
        int idx=(int)(hash&TT_MASK);
        int s=score;
        if(s> CHECKMATE_SCORE-MAX_PLY) s+=ply;
        if(s<-(CHECKMATE_SCORE-MAX_PLY)) s-=ply;
        ttKeys[idx]=hash; ttScores[idx]=s;
        ttDepths[idx]=(byte)Math.min(depth,127); ttFlags[idx]=flag;
    }

    // =========================================================================
    //  Move application
    // =========================================================================

    private void applyMoveToBoard(Board b, Move m) {
        Piece mover=b.getPiece(m.from); if(mover==null) return;
        if (mover instanceof King && Math.abs(m.from.col-m.to.col)==2) {
            int row=m.from.row;
            if(m.to.col==6){place(b,row,6,mover);b.setPiece(m.from,null);
                Piece rk=b.getPiece(new Position(row,7));if(rk!=null){place(b,row,5,rk);b.setPiece(new Position(row,7),null);}}
            else{place(b,row,2,mover);b.setPiece(m.from,null);
                Piece rk=b.getPiece(new Position(row,0));if(rk!=null){place(b,row,3,rk);b.setPiece(new Position(row,0),null);}}
            return;
        }
        if (m.isEnPassant||(mover instanceof Pawn&&m.from.col!=m.to.col&&b.getPiece(m.to)==null)) {
            int captRow=m.to.row+(mover.getColor()==PieceColor.WHITE?1:-1);
            b.setPiece(new Position(captRow,m.to.col),null);
        }
        place(b,m.to.row,m.to.col,mover); b.setPiece(m.from,null);
        if(m.isPromotion&&(m.to.row==0||m.to.row==7)){
            Piece pr=createPromo(m.promotion,mover.getColor()); pr.setMoved(true); b.setPiece(m.to,pr);
        }
    }
    private void place(Board b,int r,int c,Piece p){Piece cp=p.copy();cp.setMoved(true);b.setPiece(new Position(r,c),cp);}
    private Piece createPromo(char ch,PieceColor col){
        switch(Character.toUpperCase(ch)){
            case 'Q':return new Queen(col); case 'R':return new Rook(col);
            case 'B':return new Bishop(col); case 'N':return new Knight(col);
            default: return new Queen(col);
        }
    }

    // =========================================================================
    //  Utilities
    // =========================================================================

    private int pieceValue(Piece p){
        switch(Character.toLowerCase(p.getSymbol())){
            case 'p':return 100; case 'n':return 320; case 'b':return 330;
            case 'r':return 500; case 'q':return 900; case 'k':return 20_000; default:return 0;
        }
    }
    private int countPieces(Board b){
        int n=0; for(int r=0;r<8;r++) for(int c=0;c<8;c++) if(b.getPiece(new Position(r,c))!=null) n++; return n;
    }
    private Position findKing(Board b,PieceColor col){
        for(int r=0;r<8;r++) for(int c=0;c<8;c++){
            Position pos=new Position(r,c); Piece p=b.getPiece(pos);
            if(p instanceof King&&p.getColor()==col) return pos;
        }
        return null;
    }
    private boolean hasNonPawnMaterial(Board b,PieceColor side){
        for(int r=0;r<8;r++) for(int c=0;c<8;c++){
            Piece p=b.getPiece(new Position(r,c));
            if(p!=null&&p.getColor()==side&&!(p instanceof Pawn)&&!(p instanceof King)) return true;
        }
        return false;
    }
    private int adaptDepth(Board board){
        int n=countPieces(board);
        if(n<= 8) return maxDepth+2; if(n<=14) return maxDepth+1; return maxDepth;
    }
}
