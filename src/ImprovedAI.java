import java.util.*;
import java.io.*;

/**
 * Chess engine v2 — complete search rewrite on top of SearchBoard.
 *
 * What changed vs the old engine (and why):
 *   • make/unmake + incremental Zobrist instead of deep-copying the board at
 *     every node  →  ~30-50x more nodes/sec, several plies deeper in the same time
 *   • Negamax + principal-variation search; TT scores are side-to-move relative,
 *     fixing the old bug where flipping the root colour poisoned the table
 *   • TT now also stores the best move (huge move-ordering win)
 *   • Quiescence search handles checks correctly: when in check it searches all
 *     evasions instead of standing pat (old engine missed forced sequences)
 *   • Check extensions, killer moves, history heuristic, LMR, null-move pruning
 *   • Repetition + 50-move awareness inside the search: the engine no longer
 *     shuffles into threefold draws from winning positions
 *   • Mop-up endgame evaluation (drive the bare king to the edge, bring our
 *     king up) replaces the CheckmatePatterns hook
 *   • Opening book: two broken lines fixed (Caro-Kann c8f5, Queen's Indian f8e8),
 *     book replay now runs on SearchBoard so loading openings.txt takes
 *     milliseconds instead of seconds
 *
 * Public interface is unchanged: new ImprovedAI(difficulty).findBestMove(game).
 */
public class ImprovedAI {

    // =========================================================================
    //  Configuration
    // =========================================================================

    private final int     maxDepth;
    private final boolean easyMode;
    private final long    timeLimitMs;
    private final Random  rng = new Random();

    private static final int MATE        = 100_000;
    private static final int MATE_BOUND  = MATE - 1_000;
    private static final int MAX_PLY     = 128;
    private static final int INF         = MATE + 1;

    // search state
    private SearchBoard B;
    private long    deadline;
    private volatile boolean timesUp;
    private volatile boolean stopRequested;
    private long    nodeCount;
    private int     selDepth;

    /** Diagnostics / UCI: depth & score of the last completed iteration. */
    public int lastDepth;
    public int lastScore;
    public boolean debugRoot = false;
    /** Optional UCI "info ..." line consumer, called once per completed depth. */
    public java.util.function.Consumer<String> infoConsumer = null;

    // per-ply move buffers (no allocation inside the tree)
    private final int[][] moveBuf  = new int[MAX_PLY + 2][256];
    private final int[][] scoreBuf = new int[MAX_PLY + 2][256];

    // killers + history heuristic
    private final int[][]   killer  = new int[MAX_PLY + 2][2];
    private final int[][][] history = new int[2][64][64];

    // =========================================================================
    //  Transposition table  (side-to-move-relative scores + best move)
    // =========================================================================

    private static final int TT_SIZE = 1 << 21;          // 2M entries
    private static final int TT_MASK = TT_SIZE - 1;
    private final long[] ttKey   = new long[TT_SIZE];
    private final int[]  ttScore = new int[TT_SIZE];
    private final int[]  ttMove  = new int[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag  = new byte[TT_SIZE];
    private static final byte TT_EXACT = 0, TT_LOWER = 1, TT_UPPER = 2;

    // =========================================================================
    //  Repetition stack: game-history hashes + current search path
    // =========================================================================

    private final long[] repStack = new long[1024];
    private int repSp;        // next free slot; repStack[repSp-1] == current position

    // =========================================================================
    //  Opening book   (position hash -> list of int moves)
    // =========================================================================

    private final Map<Long, List<Integer>> book = new HashMap<>();

    /**
     * 60+ lines covering the major ECO openings (coordinate notation).
     * FIXED vs old version: Caro-Kann Classical had "f8f5" (illegal — bishop is
     * on c8), Queen's Indian had "g8e8" (illegal — that's the castled king);
     * both lines silently truncated. Now c8f5 / f8e8.
     */
    private static final String[][] OPENING_LINES = {
        // ── Open games (1.e4 e5) ─────────────────────────────────────────────
        {"e2e4","e7e5","g1f3","b8c6","f1b5","a7a6","b5a4","g8f6","e1g1","f8e7","f1e1","b7b5","a4b3","d7d6","c2c3","e8g8","h2h3"},
        {"e2e4","e7e5","g1f3","b8c6","f1b5","g8f6","e1g1","f6e4","d2d4","e4d6","d4e5","d6f5","d1e2","f5e7"},
        {"e2e4","e7e5","g1f3","b8c6","f1b5","a7a6","b5c6","d7c6","e1g1","f7f6","d2d4","e5d4"},
        {"e2e4","e7e5","g1f3","b8c6","f1c4","f8c5","c2c3","g8f6","d2d4","e5d4","c3d4","c5b4","b1c3"},
        {"e2e4","e7e5","g1f3","b8c6","f1c4","g8f6","d2d3","f8e7","e1g1","e8g8","a2a3"},
        {"e2e4","e7e5","g1f3","b8c6","d2d4","e5d4","f3d4","g8f6","d4c6","b7c6","e4e5","d8e7"},
        {"e2e4","e7e5","f2f4","e5f4","g1f3","d7d6","d2d4","g7g5","h2h4","g5g4","f3g5","f8g7"},
        {"e2e4","e7e5","g1f3","g8f6","f3e5","d7d6","e5f3","f6e4","d2d4","d6d5","f1d3","e4d6","e1g1"},
        {"e2e4","e7e5","g1f3","b8c6","b1c3","g8f6","f1b5","f8b4","e1g1","e8g8","d2d3"},
        {"e2e4","e7e5","b1c3","g8f6","f1c4","f6e4","d1h5","e4d6","c4b3","b8c6","b3f7"},
        // ── Semi-open games (1.e4 not 1...e5) ────────────────────────────────
        {"e2e4","e7e6","d2d4","d7d5","b1c3","g8f6","c1g5","f8e7","e4e5","f6d7","g5e7","d8e7","f2f4","a7a6"},
        {"e2e4","e7e6","d2d4","d7d5","b1d2","c7c5","e4d5","e6d5","g1f3","b8c6","f1b5","c8d7"},
        {"e2e4","e7e6","d2d4","d7d5","b1c3","f8b4","e4e5","c7c5","a2a3","b4c3","b2c3","g8e7","d1g4"},
        {"e2e4","e7e6","d2d4","d7d5","e4d5","e6d5","g1f3","g8f6","f1d3","f8d6","e1g1","e8g8","b1c3"},
        // Caro-Kann Classical — FIXED: c8f5 (was f8f5)
        {"e2e4","c7c6","d2d4","d7d5","b1c3","d5e4","c3e4","c8f5","e4g3","f5g6","h2h4","h7h6","g1f3","g8f6","h4h5","g6h7","f1d3"},
        {"e2e4","c7c6","d2d4","d7d5","e4e5","c8f5","g1f3","e7e6","f1e2","g8e7","e1g1","c6c5"},
        {"e2e4","c7c6","d2d4","d7d5","e4d5","c6d5","f1d3","b8c6","c2c3","g8f6","b1d2","c8g4","d1c2"},
        {"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","a7a6","c1g5","e7e6","f2f4","b8d7"},
        {"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","g7g6","c1e3","f8g7","f2f3","e8g8","d1d2","b8c6"},
        {"e2e4","c7c5","g1f3","b8c6","d2d4","c5d4","f3d4","g8f6","b1c3","d7d6","f1e2","e7e6","e1g1","f8e7","c1e3"},
        {"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","e7e6","g2g4","h7h6","g4g5"},
        {"e2e4","c7c5","g1f3","e7e6","d2d4","c5d4","f3d4","a7a6","b1c3","d8c7","f1d3","b8c6","c1e3"},
        {"e2e4","c7c5","g1f3","b8c6","d2d4","c5d4","f3d4","g8f6","b1c3","e7e5","d4b5","d7d6","c1g5","a7a6","b5a3"},
        {"e2e4","c7c5","g1f3","e7e6","d2d4","c5d4","f3d4","b8c6","b1c3","d8c7","f1e2","a7a6","e1g1"},
        {"e2e4","d7d6","d2d4","g8f6","b1c3","g7g6","f2f4","f8g7","g1f3","e8g8","f1d3","b8c6"},
        {"e2e4","g8f6","e4e5","f6d5","d2d4","d7d6","g1f3","c8g4","f1e2","e7e6","e1g1","f8e7"},
        // ── Closed games (1.d4 d5) ───────────────────────────────────────────
        {"d2d4","d7d5","c2c4","e7e6","b1c3","g8f6","c1g5","f8e7","e2e3","e8g8","g1f3","b8d7","f1d3","d5c4","d3c4"},
        {"d2d4","d7d5","c2c4","e7e6","b1c3","g8f6","c1g5","b8d7","e2e3","c7c6","g1f3","d8a5"},
        {"d2d4","d7d5","c2c4","d5c4","g1f3","g8f6","e2e3","e7e6","f1c4","c7c5","e1g1","a7a6","d1e2"},
        {"d2d4","d7d5","c2c4","c7c6","b1c3","g8f6","g1f3","d5c4","a2a4","c8f5","e2e3","e7e6","f1c4"},
        {"d2d4","d7d5","c2c4","c7c6","b1c3","g8f6","g1f3","e7e6","e2e3","b8d7","f1d3","d5c4","d3c4","b7b5"},
        // ── Indian defenses ──────────────────────────────────────────────────
        {"d2d4","g8f6","c2c4","g7g6","b1c3","f8g7","e2e4","d7d6","g1f3","e8g8","f1e2","e7e5","e1g1","b8c6"},
        {"d2d4","g8f6","c2c4","g7g6","b1c3","f8g7","e2e4","d7d6","f2f3","e8g8","c1e3","e7e5","d4d5"},
        {"d2d4","g8f6","c2c4","g7g6","b1c3","f8g7","e2e4","d7d6","f2f4","e8g8","g1f3","c7c5","d4d5"},
        {"d2d4","g8f6","c2c4","e7e6","b1c3","f8b4","d1c2","e8g8","a2a3","b4c3","c2c3","d7d5","g1f3","b8c6"},
        {"d2d4","g8f6","c2c4","e7e6","b1c3","f8b4","e2e3","e8g8","f1d3","d7d5","g1f3","c7c5","e1g1"},
        // Queen's Indian — FIXED: f8e8 (was g8e8)
        {"d2d4","g8f6","c2c4","e7e6","g1f3","b7b6","g2g3","c8b7","f1g2","f8e7","e1g1","e8g8","b1c3","f8e8"},
        {"d2d4","g8f6","c2c4","g7g6","b1c3","d7d5","g1f3","f8g7","d1b3","d5c4","b3c4","e8g8","e2e4"},
        {"d2d4","g8f6","c2c4","g7g6","b1c3","d7d5","c4d5","f6d5","e2e4","d5c3","b2c3","f8g7","f1c4","c7c5"},
        {"d2d4","d7d5","c2c4","e7e6","g2g3","g8f6","f1g2","f8e7","g1f3","e8g8","e1g1","d5c4","d1c2","b7b5"},
        {"d2d4","f7f5","g2g3","g8f6","f1g2","e7e6","g1f3","f8e7","e1g1","e8g8","c2c4","d7d6"},
        {"d2d4","f7f5","g1f3","g8f6","g2g3","e7e6","f1g2","d7d5","e1g1","f8d6","c2c4","c7c6"},
        {"d2d4","d7d5","g1f3","g8f6","c1f4","e7e6","e2e3","f8d6","f4d6","d8d6","f1d3","b8d7","e1g1","e8g8","b1d2"},
        {"d2d4","g8f6","g1f3","e7e6","c1g5","d7d5","e2e3","f8e7","b1d2","b8d7","c2c3","e8g8","f1d3"},
        {"d2d4","g8f6","c1g5","e7e5","g5f6","d8f6","c2c3","d7d5","e2e3","f8d6","g1f3","b8d7"},
        // ── Other first moves ────────────────────────────────────────────────
        {"c2c4","c7c5","b1c3","b8c6","g1f3","g8f6","g2g3","g7g6","f1g2","f8g7","e1g1","e8g8","d2d4"},
        {"c2c4","e7e5","b1c3","g8f6","g1f3","b8c6","g2g3","f8b4","f1g2","e8g8","e1g1"},
        {"c2c4","g8f6","b1c3","g7g6","g2g3","f8g7","f1g2","e8g8","g1f3","d7d6","e1g1","b8c6","d2d4"},
        {"g1f3","d7d5","g2g3","g8f6","f1g2","c7c6","e1g1","c8g4","d2d3","b8d7","b1d2"},
        {"g1f3","d7d5","g2g3","g8f6","f1g2","e7e6","e1g1","f8e7","d2d3","e8g8","b1d2","b8c6"},
        {"e2e4","d7d6","d2d3","g8f6","b1d2","g7g6","g1f3","f8g7","g2g3","e8g8","f1g2","b8c6","e1g1"},
        {"f2f4","d7d5","g1f3","g7g6","g2g3","f8g7","f1g2","g8f6","e1g1","e8g8","d2d3"},
        {"b2b3","e7e5","c1b2","b8c6","g1f3","d7d5","e2e3","g8f6","f1e2","f8d6","e1g1"},
    };

    // =========================================================================
    //  Evaluation constants  (values carried over from the old engine)
    // =========================================================================

    private static final int[] PIECE_VAL = {0, 100, 320, 330, 500, 900, 20_000,
                                               100, 320, 330, 500, 900, 20_000};

    private static final int DOUBLED_PAWN_PENALTY      = -20;
    private static final int ISOLATED_PAWN_PENALTY     = -20;
    private static final int BISHOP_PAIR_BONUS         =  50;
    private static final int ROOK_OPEN_FILE_BONUS      =  20;
    private static final int ROOK_SEMI_OPEN_FILE_BONUS =  10;
    private static final int ROOK_SEVENTH_RANK_BONUS   =  25;
    private static final int MOBILITY_FACTOR           =   3;
    private static final int KING_CENTRE_PENALTY       =  30;
    private static final int KING_ATTACK_WEIGHT        =  15;
    private static final int TEMPO_BONUS               =  10;
    private static final int[] PASSED_BONUS = {0, 150, 100, 60, 40, 20, 10, 0};

    // Piece-square tables (Michniewski), row 0 = rank 8. White reads [r][c],
    // black reads [7-r][c].
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
    //  Construction
    // =========================================================================

    /** @param baseDepth 1=Easy 2=Medium 3=Hard (values from ChessGUI) */
    public ImprovedAI(int baseDepth) {
        this(baseDepth, defaultTime(baseDepth));
    }

    /** Test constructor: explicit time limit (used by EngineMatch). */
    public ImprovedAI(int baseDepth, long timeMs) {
        switch (baseDepth) {
            case 1:  maxDepth = 3;  easyMode = true;  break;
            case 2:  maxDepth = 6;  easyMode = false; break;   // deliberate cap
            default: maxDepth = 64; easyMode = false; break;   // time-limited
        }
        timeLimitMs = timeMs;
        buildOpeningBook();
    }

    private static long defaultTime(int baseDepth) {
        switch (baseDepth) {
            case 1:  return 500;
            case 2:  return 2_000;
            default: return 5_000;
        }
    }

    // =========================================================================
    //  Opening book  (replayed on SearchBoard — loads in milliseconds)
    // =========================================================================

    private void buildOpeningBook() {
        for (String[] line : OPENING_LINES) addBookLine(line);
        tryLoadOpeningsFile("openings.txt");
    }

    private void addBookLine(String[] moves) {
        SearchBoard b = SearchBoard.startpos();
        int[] buf = new int[256];
        for (String mv : moves) {
            if (mv.length() < 4) break;
            int from = SearchBoard.algToSq(mv.substring(0, 2));
            int to   = SearchBoard.algToSq(mv.substring(2, 4));
            long posHash = b.hash;
            int found = 0;
            int n = b.genMoves(buf);
            for (int i = 0; i < n; i++) {
                int m = buf[i];
                if ((m & 63) == from && ((m >>> 6) & 63) == to) {
                    b.make(m);
                    if (b.inCheck(b.side ^ 1)) { b.unmake(); continue; }  // illegal
                    found = m;
                    break;   // board left in post-move state
                }
            }
            if (found == 0) break;   // illegal / unmatched move ends the line
            List<Integer> lst = book.computeIfAbsent(posHash, k -> new ArrayList<>());
            if (!lst.contains(found)) lst.add(found);
        }
    }

    /**
     * Load extra opening lines from a plain-text file (one line per opening,
     * coordinate moves separated by spaces, '#' for comments). Absent file = no-op.
     */
    private void tryLoadOpeningsFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                addBookLine(line.split("\\s+"));
            }
        } catch (IOException ignored) { /* file absent — built-in book only */ }
    }

    /** Number of distinct positions known to the book (for diagnostics). */
    public int bookSize() { return book.size(); }

    /** UCI: random book move for this position in coordinate notation, or null. */
    public String bookMoveUci(SearchBoard b) {
        List<Integer> cands = book.get(b.hash);
        if (cands == null || cands.isEmpty()) return null;
        return b.moveToString(cands.get(rng.nextInt(cands.size())));
    }

    /** Dev check: returns how many built-in lines fail to replay fully (should be 0). */
    static int validateBuiltinLines() {
        int bad = 0;
        int[] buf = new int[256];
        for (String[] line : OPENING_LINES) {
            SearchBoard b = SearchBoard.startpos();
            int played = 0;
            for (String mv : line) {
                int from = SearchBoard.algToSq(mv.substring(0, 2));
                int to   = SearchBoard.algToSq(mv.substring(2, 4));
                int found = 0;
                int n = b.genMoves(buf);
                for (int i = 0; i < n; i++) {
                    int m = buf[i];
                    if ((m & 63) == from && ((m >>> 6) & 63) == to) {
                        b.make(m);
                        if (b.inCheck(b.side ^ 1)) { b.unmake(); continue; }
                        found = m;
                        break;
                    }
                }
                if (found == 0) break;
                played++;
            }
            if (played != line.length) {
                bad++;
                System.out.println("  truncated at move " + (played + 1) + ": " + String.join(" ", line));
            }
        }
        return bad;
    }

    // =========================================================================
    //  Public entry point
    // =========================================================================

    public Move findBestMove(Game game) {
        prepareForSearch();
        PieceColor me = game.getCurrentPlayer();
        List<Move> legalObjs = game.getAllLegalMoves(me);
        if (legalObjs.isEmpty()) return null;
        if (legalObjs.size() == 1) return legalObjs.get(0);   // forced — instant

        B = boardFromGame(game);

        // 1. Opening book
        List<Integer> cands = book.get(B.hash);
        if (cands != null && !cands.isEmpty()) {
            Move bm = toMoveObject(cands.get(rng.nextInt(cands.size())), legalObjs);
            if (bm != null) return bm;
        }

        // 2. Search
        buildRepStackFromGame(game);
        int bestMove = runSearch(timeLimitMs, maxDepth);
        if (bestMove == 0) return null;

        // 3. Easy mode keeps its 10% blunder chance
        if (easyMode && rng.nextDouble() < 0.10)
            return legalObjs.get(rng.nextInt(legalObjs.size()));

        Move result = toMoveObject(bestMove, legalObjs);
        return result != null ? result : legalObjs.get(0);
    }

    /**
     * UCI entry point: search a prepared SearchBoard. {@code histHashes[0..n-1]}
     * are the position hashes of the game so far (last one == board.hash) for
     * repetition detection. Returns the best move in coordinate notation.
     */
    public String searchUci(SearchBoard board, long[] histHashes, int n,
                            long timeMs, int depthCap) {
        B = board;
        repSp = 0;
        if (n > 0 && histHashes[n - 1] == board.hash) {
            int copy = Math.min(n, repStack.length - MAX_PLY);
            System.arraycopy(histHashes, n - copy, repStack, 0, copy);
            repSp = copy;
        } else {
            repStack[repSp++] = board.hash;
        }
        int best = runSearch(timeMs, Math.min(depthCap, 64));
        return best == 0 ? "0000" : board.moveToString(best);
    }

    /** Ask a running search to stop ASAP (UCI "stop"). */
    public void stopSearch() { stopRequested = true; }

    /**
     * Clear the stop flag before a new search. Call this on the SAME thread that
     * issues the next search, so the clear is sequenced-before any later
     * stopSearch(). It must NOT be cleared inside runSearch: in the UCI adapter
     * runSearch runs on a worker thread and clearing there races with the
     * controller's stopSearch() — a lost "stop" would hang an infinite search.
     * See UCI.startGo.
     */
    public void prepareForSearch() { stopRequested = false; }

    /**
     * Iterative-deepening search of the current B/repStack. Returns best int
     * move (0 only if there are no legal moves). Resets per-search state.
     */
    private int runSearch(long timeMs, int depthCap) {
        for (int i = 0; i < MAX_PLY; i++) killer[i][0] = killer[i][1] = 0;
        for (int[][] h : history) for (int[] row : h) Arrays.fill(row, 0);
        nodeCount = 0;
        selDepth  = 0;
        timesUp   = false;
        long start = System.currentTimeMillis();
        deadline  = start + timeMs;

        int[] rootMoves  = new int[256];
        int[] rootScores = new int[256];
        int nRoot = legalRootMoves(rootMoves);
        if (nRoot == 0) return 0;

        int bestMove = rootMoves[0];

        for (int depth = 1; depth <= depthCap && !timesUp; depth++) {
            int alpha = -INF, beta = INF;
            int iterBest = 0, iterScore = -INF;

            for (int i = 0; i < nRoot && !timesUp; i++) {
                int m = rootMoves[i];
                B.make(m);
                repPush();
                int s;
                if (i == 0) {
                    s = -negamax(depth - 1, 1, -beta, -alpha, true);
                } else {
                    s = -negamax(depth - 1, 1, -alpha - 1, -alpha, true);   // PVS probe
                    if (!timesUp && s > alpha)
                        s = -negamax(depth - 1, 1, -beta, -alpha, true);
                }
                repPop();
                B.unmake();
                rootScores[i] = timesUp ? -INF : s;
                if (!timesUp && s > iterScore) { iterScore = s; iterBest = m; }
                if (s > alpha) alpha = s;
            }

            if (!timesUp && iterBest != 0) {
                bestMove = iterBest;
                lastDepth = depth;
                lastScore = iterScore;
                if (debugRoot) System.out.println("  d=" + depth + " score=" + iterScore + " best=" + B.moveToString(iterBest));
                if (infoConsumer != null)
                    infoConsumer.accept(uciInfoLine(depth, iterScore,
                            System.currentTimeMillis() - start, bestMove));
                sortRoot(rootMoves, rootScores, nRoot);    // best-first next iteration
                // stop only when the mate is short enough to be PROVEN at this
                // depth — a raw TT hit can claim a distant "mate" that deeper
                // search would refute (stale entries from a previous move)
                if (iterScore > MATE_BOUND && (MATE - iterScore) <= depth) break;
            }
        }
        return bestMove;
    }

    /** Generate fully-legal root moves into buf; returns count. */
    private int legalRootMoves(int[] buf) {
        int[] tmp = moveBuf[MAX_PLY];
        int n = B.genMoves(tmp), out = 0;
        for (int i = 0; i < n; i++) {
            B.make(tmp[i]);
            boolean ok = !B.inCheck(B.side ^ 1);
            B.unmake();
            if (ok) buf[out++] = tmp[i];
        }
        return out;
    }

    /** True if the side to move has at least one legal move (uses moveBuf[ply]). */
    private boolean hasLegalMove(int ply) {
        int[] buf = moveBuf[ply];
        int n = B.genMoves(buf);
        for (int i = 0; i < n; i++) {
            B.make(buf[i]);
            boolean ok = !B.inCheck(B.side ^ 1);
            B.unmake();
            if (ok) return true;
        }
        return false;
    }

    private void sortRoot(int[] moves, int[] scores, int n) {
        for (int i = 1; i < n; i++) {                      // insertion sort, stable
            int m = moves[i], s = scores[i], j = i - 1;
            while (j >= 0 && scores[j] < s) { moves[j+1] = moves[j]; scores[j+1] = scores[j]; j--; }
            moves[j+1] = m; scores[j+1] = s;
        }
    }

    // =========================================================================
    //  Negamax with alpha-beta, PVS, TT, null move, LMR, check extensions
    // =========================================================================

    private int negamax(int depth, int ply, int alpha, int beta, boolean allowNull) {
        if (stopRequested
                || (((++nodeCount) & 2047) == 0 && System.currentTimeMillis() >= deadline))
            timesUp = true;
        if (timesUp) return 0;
        if (ply > selDepth) selDepth = ply;

        // draw detection: 50-move rule and repetition inside the search tree.
        // Checkmate takes precedence over the 50-move rule — a side mated on the
        // 100th half-move has lost, it is not a draw.
        if (B.halfmove >= 100) {
            if (!B.inCheck(B.side) || hasLegalMove(ply)) return 0;
            return -(MATE - ply);
        }
        if (isRepetition()) return 0;
        if (ply >= MAX_PLY - 1) return evaluate();

        boolean inCheck = B.inCheck(B.side);
        if (inCheck && depth < 64) depth++;                 // check extension

        if (depth <= 0) return qsearch(ply, alpha, beta);

        // TT probe (scores stored side-to-move relative — correct for any root)
        long key = B.hash;
        int idx = (int) (key & TT_MASK);
        int hashMove = 0;
        if (ttKey[idx] == key) {
            hashMove = ttMove[idx];
            if (ttDepth[idx] >= depth) {
                int s = fromTT(ttScore[idx], ply);
                byte f = ttFlag[idx];
                if (f == TT_EXACT) return s;
                if (f == TT_LOWER && s >= beta)  return s;
                if (f == TT_UPPER && s <= alpha) return s;
            }
        }

        // Null-move pruning (both sides — negamax makes this symmetric)
        if (allowNull && !inCheck && depth >= 3 && beta < MATE_BOUND
                && hasNonPawnMaterial(B.side)) {
            int R = (depth >= 6) ? 3 : 2;
            B.makeNull();
            repPush();
            int s = -negamax(depth - 1 - R, ply + 1, -beta, -beta + 1, false);
            repPop();
            B.unmakeNull();
            if (timesUp) return 0;
            if (s >= beta) return beta;
        }

        int[] buf = moveBuf[ply];
        int n = B.genMoves(buf);
        scoreMoves(buf, n, hashMove, ply);

        int best = -INF, bestMove = 0, legal = 0;
        int origAlpha = alpha;

        for (int i = 0; i < n; i++) {
            pickBest(buf, scoreBuf[ply], i, n);
            int m = buf[i];
            int captured = B.sq[(m >>> 6) & 63];
            boolean quiet = (captured == SearchBoard.EMPTY)
                    && (m & SearchBoard.F_EP) == 0
                    && ((m >>> 12) & 15) == 0;

            B.make(m);
            if (B.inCheck(B.side ^ 1)) { B.unmake(); continue; }   // illegal
            legal++;
            repPush();

            int s;
            if (legal == 1) {
                s = -negamax(depth - 1, ply + 1, -beta, -alpha, true);
            } else {
                // Late-move reduction for quiet moves deep in the list
                int red = 0;
                if (depth >= 3 && !inCheck && quiet && legal > 4 && !isKiller(m, ply))
                    red = (legal > 12) ? 2 : 1;
                s = -negamax(depth - 1 - red, ply + 1, -alpha - 1, -alpha, true);
                if (!timesUp && s > alpha && red > 0)
                    s = -negamax(depth - 1, ply + 1, -alpha - 1, -alpha, true);
                if (!timesUp && s > alpha && s < beta)
                    s = -negamax(depth - 1, ply + 1, -beta, -alpha, true);
            }

            repPop();
            B.unmake();
            if (timesUp) return 0;

            if (s > best) {
                best = s; bestMove = m;
                if (s > alpha) {
                    alpha = s;
                    if (alpha >= beta) {
                        if (quiet) {
                            updateKiller(m, ply);
                            int side = B.side;
                            int h = history[side][m & 63][(m >>> 6) & 63] + depth * depth;
                            history[side][m & 63][(m >>> 6) & 63] = Math.min(h, 400_000);
                        }
                        break;
                    }
                }
            }
        }

        if (legal == 0) return inCheck ? -(MATE - ply) : 0;   // mate / stalemate

        byte flag = (best <= origAlpha) ? TT_UPPER
                  : (best >= beta)      ? TT_LOWER : TT_EXACT;
        ttKey[idx]   = key;
        ttScore[idx] = toTT(best, ply);
        ttMove[idx]  = bestMove;
        ttDepth[idx] = (byte) Math.min(depth, 127);
        ttFlag[idx]  = flag;
        return best;
    }

    // =========================================================================
    //  Quiescence search — searches all evasions when in check (old engine
    //  incorrectly stood pat while in check)
    // =========================================================================

    private int qsearch(int ply, int alpha, int beta) {
        if (stopRequested
                || (((++nodeCount) & 2047) == 0 && System.currentTimeMillis() >= deadline))
            timesUp = true;
        if (timesUp) return 0;
        if (ply >= MAX_PLY - 1) return evaluate();

        boolean inCheck = B.inCheck(B.side);
        int[] buf = moveBuf[ply];
        int n;
        int best;

        if (inCheck) {
            best = -INF;                       // must find an evasion
            n = B.genMoves(buf);
        } else {
            int stand = evaluate();
            if (stand >= beta) return stand;
            if (stand > alpha) alpha = stand;
            best = stand;
            n = B.genCaptures(buf);
        }

        scoreMoves(buf, n, 0, ply);
        int legal = 0;
        for (int i = 0; i < n; i++) {
            pickBest(buf, scoreBuf[ply], i, n);
            int m = buf[i];
            B.make(m);
            if (B.inCheck(B.side ^ 1)) { B.unmake(); continue; }
            legal++;
            int s = -qsearch(ply + 1, -beta, -alpha);
            B.unmake();
            if (timesUp) return 0;
            if (s > best) {
                best = s;
                if (s > alpha) {
                    alpha = s;
                    if (alpha >= beta) break;
                }
            }
        }

        if (inCheck && legal == 0) return -(MATE - ply);     // checkmated
        return best;
    }

    // =========================================================================
    //  Move ordering: TT move > captures (MVV-LVA) > promotions > killers > history
    // =========================================================================

    private void scoreMoves(int[] buf, int n, int hashMove, int ply) {
        int[] sc = scoreBuf[ply];
        for (int i = 0; i < n; i++) {
            int m = buf[i];
            if (m == hashMove) { sc[i] = 2_000_000; continue; }
            int t = (m >>> 6) & 63;
            int victim = B.sq[t];
            int pr = (m >>> 12) & 15;
            int s = 0;
            if ((m & SearchBoard.F_EP) != 0) {
                s = 1_000_000 + 100 * 16 - 100;
            } else if (victim != SearchBoard.EMPTY) {
                s = 1_000_000 + PIECE_VAL[victim] * 16 - PIECE_VAL[B.sq[m & 63]];
            }
            if (pr != 0) s += (pr == SearchBoard.WQ || pr == SearchBoard.BQ) ? 950_000 : 300_000;
            if (s == 0) {
                if (m == killer[ply][0])      s = 900_000;
                else if (m == killer[ply][1]) s = 850_000;
                else if ((m & SearchBoard.F_CASTLE) != 0)
                    s = 800_000;
                else s = history[B.side][m & 63][t];
            }
            sc[i] = s;
        }
    }

    /** Selection: swap the best-scored remaining move into position i. */
    private void pickBest(int[] buf, int[] sc, int i, int n) {
        int bi = i;
        for (int j = i + 1; j < n; j++) if (sc[j] > sc[bi]) bi = j;
        if (bi != i) {
            int tm = buf[i]; buf[i] = buf[bi]; buf[bi] = tm;
            int ts = sc[i];  sc[i]  = sc[bi];  sc[bi]  = ts;
        }
    }

    private boolean isKiller(int m, int ply) {
        return m == killer[ply][0] || m == killer[ply][1];
    }

    private void updateKiller(int m, int ply) {
        if (m != killer[ply][0]) { killer[ply][1] = killer[ply][0]; killer[ply][0] = m; }
    }

    // mate-score <-> TT conversion (store distance from this node, not from root)
    private static int toTT(int s, int ply) {
        if (s >  MATE_BOUND) return s + ply;
        if (s < -MATE_BOUND) return s - ply;
        return s;
    }
    private static int fromTT(int s, int ply) {
        if (s >  MATE_BOUND) return s - ply;
        if (s < -MATE_BOUND) return s + ply;
        return s;
    }

    // =========================================================================
    //  Repetition handling
    // =========================================================================

    private void repPush() { if (repSp < repStack.length) repStack[repSp++] = B.hash; }
    private void repPop()  { repSp--; }

    /** Any earlier identical position within the 50-move window counts as a draw. */
    private boolean isRepetition() {
        long h = B.hash;
        int limit = Math.max(0, repSp - 1 - B.halfmove);
        for (int i = repSp - 3; i >= limit; i -= 2)
            if (repStack[i] == h) return true;
        return false;
    }

    /** Prefill the repetition stack with the actual game's position hashes. */
    private void buildRepStackFromGame(Game game) {
        repSp = 0;
        SearchBoard t = SearchBoard.startpos();
        repStack[repSp++] = t.hash;
        int[] buf = new int[256];
        boolean ok = true;
        for (Move mv : game.getHistory()) {
            int from = mv.from.row * 8 + mv.from.col;
            int to   = mv.to.row * 8 + mv.to.col;
            int found = 0;
            int n = t.genMoves(buf);
            for (int i = 0; i < n; i++) {
                int m = buf[i];
                if ((m & 63) != from || ((m >>> 6) & 63) != to) continue;
                int pr = (m >>> 12) & 15;
                if (mv.isPromotion) {
                    if (pr == 0 || promoChar(pr) != Character.toUpperCase(mv.promotion)) continue;
                } else if (pr != 0) continue;
                found = m;
                break;
            }
            if (found == 0) { ok = false; break; }      // custom setup — bail out
            t.make(found);
            repStack[repSp++] = t.hash;
            if (repSp >= repStack.length - MAX_PLY) { ok = false; break; }
        }
        if (!ok || t.hash != B.hash) {                  // fall back to current pos only
            repSp = 0;
            repStack[repSp++] = B.hash;
        }
    }

    private static char promoChar(int code) {
        switch (code) {
            case SearchBoard.WQ: case SearchBoard.BQ: return 'Q';
            case SearchBoard.WR: case SearchBoard.BR: return 'R';
            case SearchBoard.WB: case SearchBoard.BB: return 'B';
            case SearchBoard.WN: case SearchBoard.BN: return 'N';
            default: return '?';
        }
    }

    // =========================================================================
    //  Static evaluation (white-minus-black, returned side-to-move relative)
    // =========================================================================

    private int evaluate() {
        int[] sq = B.sq;
        int phase = 0;
        int wBish = 0, bBish = 0;
        int wKn = 0, bKn = 0;
        int wMat = 0, bMat = 0;          // non-pawn, non-king material
        int wPawns = 0, bPawns = 0;
        final int[] wPawnFile = new int[8], bPawnFile = new int[8];

        // pass 1: counts
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == SearchBoard.EMPTY) continue;
            switch (p) {
                case SearchBoard.WP: wPawns++; wPawnFile[s & 7]++; break;
                case SearchBoard.BP: bPawns++; bPawnFile[s & 7]++; break;
                case SearchBoard.WN: case SearchBoard.WB: phase++; wMat += PIECE_VAL[p]; if (p == SearchBoard.WB) wBish++; else wKn++; break;
                case SearchBoard.BN: case SearchBoard.BB: phase++; bMat += PIECE_VAL[p]; if (p == SearchBoard.BB) bBish++; else bKn++; break;
                case SearchBoard.WR: phase += 2; wMat += PIECE_VAL[p]; break;
                case SearchBoard.BR: phase += 2; bMat += PIECE_VAL[p]; break;
                case SearchBoard.WQ: phase += 4; wMat += PIECE_VAL[p]; break;
                case SearchBoard.BQ: phase += 4; bMat += PIECE_VAL[p]; break;
            }
        }
        phase = Math.min(24, phase);

        // dead-draw shortcut: bare kings / king + single minor each, no pawns
        if (wPawns == 0 && bPawns == 0 && wMat <= 330 && bMat <= 330)
            return 0;

        int e = 0;

        // pass 2: material + PSTs + per-piece terms + mobility
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == SearchBoard.EMPTY) continue;
            int r = s >> 3, c = s & 7;
            boolean white = SearchBoard.isWhite(p);
            int tr = white ? r : 7 - r;
            int v = PIECE_VAL[p];
            int pst;
            switch (p) {
                case SearchBoard.WP: case SearchBoard.BP:
                    pst = (PST_PAWN_MG[tr][c] * phase + PST_PAWN_EG[tr][c] * (24 - phase)) / 24;
                    break;
                case SearchBoard.WN: case SearchBoard.BN: pst = PST_KNIGHT[tr][c]; break;
                case SearchBoard.WB: case SearchBoard.BB: pst = PST_BISHOP[tr][c]; break;
                case SearchBoard.WR: case SearchBoard.BR:
                    pst = PST_ROOK[tr][c];
                    pst += rookBonus(white, r, c, wPawnFile, bPawnFile);
                    break;
                case SearchBoard.WQ: case SearchBoard.BQ: pst = PST_QUEEN[tr][c]; break;
                default:
                    pst = (PST_KING_MG[tr][c] * phase + PST_KING_EG[tr][c] * (24 - phase)) / 24;
                    v = 0;
                    break;
            }
            int mob = mobilityOf(p, s, r, c) * MOBILITY_FACTOR;
            int term = v + pst + mob;
            e += white ? term : -term;
        }

        if (wBish >= 2) e += BISHOP_PAIR_BONUS;
        if (bBish >= 2) e -= BISHOP_PAIR_BONUS;

        // pawn structure
        e += pawnStructure(true,  wPawnFile, bPawnFile);
        e -= pawnStructure(false, bPawnFile, wPawnFile);

        // king safety (middlegame only)
        if (phase > 8) {
            e += kingSafety(SearchBoard.WHITE);
            e -= kingSafety(SearchBoard.BLACK);
        }

        // mop-up: drive a bare king to the edge in won endgames
        if (phase <= 8) {
            int wHeavy = wMat - 330 * wBish - 320 * wKn;   // rook/queen material
            int bHeavy = bMat - 330 * bBish - 320 * bKn;
            if (bMat == 0 && bPawns == 0
                    && hasMatingMaterial(wPawns, wBish, wKn, wHeavy, wBishopSquares()))
                e += mopUp(B.kingSq[SearchBoard.WHITE], B.kingSq[SearchBoard.BLACK],
                           mateCorner(wHeavy, wBish, wKn, wBishopSquares()));
            if (wMat == 0 && wPawns == 0
                    && hasMatingMaterial(bPawns, bBish, bKn, bHeavy, bBishopSquares()))
                e -= mopUp(B.kingSq[SearchBoard.BLACK], B.kingSq[SearchBoard.WHITE],
                           mateCorner(bHeavy, bBish, bKn, bBishopSquares()));
        }

        e += (B.side == SearchBoard.WHITE) ? TEMPO_BONUS : -TEMPO_BONUS;
        return (B.side == SearchBoard.WHITE) ? e : -e;
    }

    /** Pseudo-mobility: cheap target-square counts (no allocation, no legality). */
    private int mobilityOf(int p, int s, int r, int c) {
        switch (p) {
            case SearchBoard.WN: case SearchBoard.BN: {
                int n = 0;
                boolean white = SearchBoard.isWhite(p);
                for (int[] o : KNIGHT_OFF) {
                    int rr = r + o[0], cc = c + o[1];
                    if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                    int tp = B.sq[rr * 8 + cc];
                    if (tp == SearchBoard.EMPTY || SearchBoard.isWhite(tp) != white) n++;
                }
                return n;
            }
            case SearchBoard.WB: case SearchBoard.BB: return rays(s, r, c, BISHOP_DIRS);
            case SearchBoard.WR: case SearchBoard.BR: return rays(s, r, c, ROOK_DIRS);
            case SearchBoard.WQ: case SearchBoard.BQ:
                return rays(s, r, c, BISHOP_DIRS) + rays(s, r, c, ROOK_DIRS);
            default: return 0;
        }
    }

    private static final int[][] KNIGHT_OFF  = {{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}};
    private static final int[][] ROOK_DIRS   = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final int[][] BISHOP_DIRS = {{1,1},{1,-1},{-1,1},{-1,-1}};

    private int rays(int s, int r, int c, int[][] dirs) {
        int n = 0;
        for (int[] d : dirs) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                n++;
                if (B.sq[rr * 8 + cc] != SearchBoard.EMPTY) break;
                rr += d[0]; cc += d[1];
            }
        }
        return n;
    }

    private int rookBonus(boolean white, int r, int c, int[] wPF, int[] bPF) {
        int bonus = 0;
        if (r == (white ? 1 : 6)) bonus += ROOK_SEVENTH_RANK_BONUS;
        int own = white ? wPF[c] : bPF[c];
        int opp = white ? bPF[c] : wPF[c];
        if (own == 0 && opp == 0) bonus += ROOK_OPEN_FILE_BONUS;
        else if (own == 0)        bonus += ROOK_SEMI_OPEN_FILE_BONUS;
        return bonus;
    }

    private int pawnStructure(boolean white, int[] ownFile, int[] oppFile) {
        int e = 0;
        for (int c = 0; c < 8; c++)
            if (ownFile[c] > 1) e += DOUBLED_PAWN_PENALTY * (ownFile[c] - 1);

        int ownPawn = white ? SearchBoard.WP : SearchBoard.BP;
        int oppPawn = white ? SearchBoard.BP : SearchBoard.WP;
        for (int s = 0; s < 64; s++) {
            if (B.sq[s] != ownPawn) continue;
            int r = s >> 3, c = s & 7;
            if ((c == 0 || ownFile[c - 1] == 0) && (c == 7 || ownFile[c + 1] == 0))
                e += ISOLATED_PAWN_PENALTY;
            boolean passed = true;
            outer:
            for (int ac = Math.max(0, c - 1); ac <= Math.min(7, c + 1); ac++) {
                if (white) {
                    for (int or = r - 1; or >= 0; or--)
                        if (B.sq[or * 8 + ac] == oppPawn) { passed = false; break outer; }
                } else {
                    for (int or = r + 1; or < 8; or++)
                        if (B.sq[or * 8 + ac] == oppPawn) { passed = false; break outer; }
                }
            }
            if (passed) {
                int dist = white ? r : 7 - r;          // 1 = about to promote
                e += PASSED_BONUS[Math.max(1, Math.min(6, dist))];
            }
        }
        return e;
    }

    private int kingSafety(int side) {
        int kp = B.kingSq[side];
        int r = kp >> 3, c = kp & 7;
        int e = 0;
        if (c >= 2 && c <= 5) e -= KING_CENTRE_PENALTY;
        boolean white = (side == SearchBoard.WHITE);
        int dir = white ? -1 : 1;
        int pawn = white ? SearchBoard.WP : SearchBoard.BP;
        for (int dc = -1; dc <= 1; dc++) {
            int cc = c + dc;
            if (cc < 0 || cc > 7) continue;
            int r1 = r + dir, r2 = r + 2 * dir;
            if (r1 >= 0 && r1 < 8 && B.sq[r1 * 8 + cc] == pawn) e += 12;
            if (r2 >= 0 && r2 < 8 && B.sq[r2 * 8 + cc] == pawn) e += 6;
        }
        int enemy = side ^ 1;
        for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
            if (dr == 0 && dc == 0) continue;
            int rr = r + dr, cc = c + dc;
            if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
            if (B.isSquareAttacked(rr * 8 + cc, enemy)) e -= KING_ATTACK_WEIGHT;
        }
        return e;
    }

    /** Can this side force mate against a bare king? (KNN vs K cannot.) */
    private boolean hasMatingMaterial(int pawns, int bishops, int knights,
                                      int heavyMat, long bishopColors) {
        if (pawns > 0)   return true;                       // pawn promotes
        if (heavyMat > 0) return true;                      // any rook or queen
        if (bishops >= 2 && bishopColors == 3) return true; // bishops on both colours
        if (bishops >= 1 && knights >= 1) return true;      // bishop + knight
        if (knights >= 3) return true;                      // 3 knights (promotion)
        return false;                                       // lone minor / two knights
    }

    /** bit0 = bishop on light squares, bit1 = bishop on dark squares. */
    private long wBishopSquares() { return bishopColors(SearchBoard.WB); }
    private long bBishopSquares() { return bishopColors(SearchBoard.BB); }
    private long bishopColors(int code) {
        long m = 0;
        for (int s = 0; s < 64; s++)
            if (B.sq[s] == code) m |= (((s >> 3) + (s & 7)) & 1) == 0 ? 1 : 2;
        return m;
    }

    /**
     * Push the lone king to the edge and bring our king up.
     * cornerColor: 0 = steer toward the light corners (a8/h1), 1 = the dark
     * corners (a1/h8), -1 = any corner. K+B+N can only mate in a corner that
     * matches the bishop's colour, so it MUST be driven to the right one — a
     * corner-blind mop-up would shuffle into a 50-move draw in a won position.
     */
    private int mopUp(int strongKing, int weakKing, int cornerColor) {
        int wr = weakKing >> 3, wc = weakKing & 7;
        int edge = Math.min(Math.min(wr, 7 - wr), Math.min(wc, 7 - wc));
        int dist = Math.abs((strongKing >> 3) - wr) + Math.abs((strongKing & 7) - wc);
        int score = 300 + (3 - edge) * 50 + (8 - dist) * 15;
        if (cornerColor >= 0) {
            int toLight = Math.min(wr + wc, (7 - wr) + (7 - wc));   // dist to a8 / h1
            int toDark  = Math.min((7 - wr) + wc, wr + (7 - wc));   // dist to a1 / h8
            int cornerDist = (cornerColor == 0) ? toLight : toDark;
            score += (14 - cornerDist) * 20;                        // reward the RIGHT corner
        }
        return score;
    }

    /**
     * Which corner colour this mating material must mate in: 0 = light (a8/h1),
     * 1 = dark (a1/h8), or -1 when a mate is possible in any corner (a heavy
     * piece, or the two-bishop pair). Only K+B+N is corner-colour-locked.
     */
    private int mateCorner(int heavy, int bishops, int knights, long bishopColors) {
        if (heavy == 0 && bishops == 1 && knights == 1)
            return (bishopColors == 1) ? 0 : 1;   // bishopColors: 1 = light-squared only
        return -1;
    }

    private boolean hasNonPawnMaterial(int side) {
        int lo = (side == SearchBoard.WHITE) ? SearchBoard.WN : SearchBoard.BN;
        int hi = (side == SearchBoard.WHITE) ? SearchBoard.WQ : SearchBoard.BQ;
        for (int s = 0; s < 64; s++) {
            int p = B.sq[s];
            if (p >= lo && p <= hi) return true;
        }
        return false;
    }

    // =========================================================================
    //  Conversion: Game / Move objects  <->  SearchBoard / int moves
    // =========================================================================

    static SearchBoard boardFromGame(Game game) {
        SearchBoard b = new SearchBoard();
        Board g = game.getBoard();
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
            Piece p = g.getPiece(new Position(r, c));
            b.sq[r * 8 + c] = (p == null) ? SearchBoard.EMPTY : codeOf(p);
        }
        b.side = (game.getCurrentPlayer() == PieceColor.WHITE)
                ? SearchBoard.WHITE : SearchBoard.BLACK;

        // castling rights from hasMoved flags
        b.castle = 0;
        Piece wk = g.getPiece(new Position(7, 4));
        if (wk instanceof King && wk.getColor() == PieceColor.WHITE && !wk.hasMoved()) {
            Piece rh = g.getPiece(new Position(7, 7));
            if (rh instanceof Rook && rh.getColor() == PieceColor.WHITE && !rh.hasMoved()) b.castle |= 1;
            Piece ra = g.getPiece(new Position(7, 0));
            if (ra instanceof Rook && ra.getColor() == PieceColor.WHITE && !ra.hasMoved()) b.castle |= 2;
        }
        Piece bk = g.getPiece(new Position(0, 4));
        if (bk instanceof King && bk.getColor() == PieceColor.BLACK && !bk.hasMoved()) {
            Piece rh = g.getPiece(new Position(0, 7));
            if (rh instanceof Rook && rh.getColor() == PieceColor.BLACK && !rh.hasMoved()) b.castle |= 4;
            Piece ra = g.getPiece(new Position(0, 0));
            if (ra instanceof Rook && ra.getColor() == PieceColor.BLACK && !ra.hasMoved()) b.castle |= 8;
        }

        Position ep = game.getEnPassantTarget();
        b.ep = (ep == null) ? -1 : ep.row * 8 + ep.col;
        b.halfmove = game.getHalfmoveClock();
        for (int i = 0; i < 64; i++) {
            if (b.sq[i] == SearchBoard.WK) b.kingSq[SearchBoard.WHITE] = i;
            if (b.sq[i] == SearchBoard.BK) b.kingSq[SearchBoard.BLACK] = i;
        }
        b.hash = b.computeHash();
        return b;
    }

    private static int codeOf(Piece p) {
        boolean w = p.getColor() == PieceColor.WHITE;
        switch (Character.toLowerCase(p.getSymbol())) {
            case 'p': return w ? SearchBoard.WP : SearchBoard.BP;
            case 'n': return w ? SearchBoard.WN : SearchBoard.BN;
            case 'b': return w ? SearchBoard.WB : SearchBoard.BB;
            case 'r': return w ? SearchBoard.WR : SearchBoard.BR;
            case 'q': return w ? SearchBoard.WQ : SearchBoard.BQ;
            default:  return w ? SearchBoard.WK : SearchBoard.BK;
        }
    }

    /** Match an int move back to one of the game's legal Move objects. */
    private Move toMoveObject(int m, List<Move> legalObjs) {
        if (m == 0) return null;
        int f = m & 63, t = (m >>> 6) & 63;
        int pr = (m >>> 12) & 15;
        char prChar = (pr != 0) ? promoChar(pr) : '\0';
        for (Move mv : legalObjs) {
            if (mv.from.row * 8 + mv.from.col != f) continue;
            if (mv.to.row * 8 + mv.to.col != t) continue;
            if (pr != 0) {
                if (mv.isPromotion && Character.toUpperCase(mv.promotion) == prChar) return mv;
            } else if (!mv.isPromotion) {
                return mv;
            }
        }
        return null;
    }

    // diagnostics
    public long lastNodeCount() { return nodeCount; }

    // =========================================================================
    //  UCI helpers
    // =========================================================================

    /** Build a UCI "info" line for a completed iteration. */
    private String uciInfoLine(int depth, int score, long ms, int bestMove) {
        StringBuilder sb = new StringBuilder("info depth ").append(depth)
                .append(" seldepth ").append(selDepth);
        if (score > MATE_BOUND)       sb.append(" score mate ").append((MATE - score + 1) / 2);
        else if (score < -MATE_BOUND) sb.append(" score mate ").append(-((MATE + score + 1) / 2));
        else                          sb.append(" score cp ").append(score);
        long nps = ms > 0 ? nodeCount * 1000 / ms : nodeCount;
        sb.append(" nodes ").append(nodeCount)
          .append(" nps ").append(nps)
          .append(" time ").append(ms)
          .append(" pv ").append(extractPV(bestMove, depth));
        return sb.toString();
    }

    /** Walk the TT best-move chain from the root to recover the PV. */
    private String extractPV(int rootMove, int maxLen) {
        StringBuilder sb = new StringBuilder(B.moveToString(rootMove));
        int made = 0;
        B.make(rootMove); made++;
        int[] buf = new int[256];
        while (made < maxLen && made < 30) {
            int idx = (int) (B.hash & TT_MASK);
            if (ttKey[idx] != B.hash) break;
            int m = ttMove[idx];
            if (m == 0) break;
            // verify the TT move is pseudo-legal & legal here (hash collisions!)
            boolean ok = false;
            int n = B.genMoves(buf);
            for (int i = 0; i < n; i++) if (buf[i] == m) { ok = true; break; }
            if (!ok) break;
            B.make(m);
            if (B.inCheck(B.side ^ 1)) { B.unmake(); break; }
            made++;
            sb.append(' ').append(B.moveToString(m));
        }
        while (made-- > 0) B.unmake();
        return sb.toString();
    }
}
