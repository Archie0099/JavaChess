import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * UCI (Universal Chess Interface) adapter — lets the engine run inside any
 * standard chess GUI (Arena, CuteChess, BanksiaGUI, …) or play automated
 * engine-vs-engine matches.
 *
 * Build & use:
 *     javac *.java
 *     java UCI            ← point your chess GUI at a script/bat that runs this
 *
 * Windows: create JavaChess.bat next to the classes containing
 *     @echo off
 *     java -cp "%~dp0" UCI
 * and add that .bat as a UCI engine in Arena.
 *
 * Supported: uci, isready, ucinewgame, position [startpos|fen …] [moves …],
 *            go [movetime|wtime/btime/winc/binc|depth|infinite], stop, quit.
 */
public class UCI {

    private final ImprovedAI engine = new ImprovedAI(3, 5_000);  // full strength
    private SearchBoard board = SearchBoard.startpos();
    private final long[] histHashes = new long[2048];
    private int histCount = 0;
    private Thread searchThread = null;

    public static void main(String[] args) throws Exception {
        new UCI().loop();
    }

    private void loop() throws Exception {
        engine.infoConsumer = line -> { System.out.println(line); System.out.flush(); };
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        setPosition(new String[]{"position", "startpos"});

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] tok = line.split("\\s+");

            try {
                switch (tok[0]) {
                    case "uci":
                        System.out.println("id name JavaChess 2.0");
                        System.out.println("id author Ankit");
                        System.out.println("uciok");
                        break;
                    case "isready":
                        System.out.println("readyok");
                        break;
                    case "ucinewgame":
                        waitForSearch();
                        setPosition(new String[]{"position", "startpos"});
                        break;
                    case "position":
                        waitForSearch();
                        setPosition(tok);
                        break;
                    case "go":
                        waitForSearch();
                        startGo(tok);
                        break;
                    case "stop":
                        engine.stopSearch();
                        waitForSearch();
                        break;
                    case "quit":
                        engine.stopSearch();
                        waitForSearch();
                        return;
                    default:
                        // silently ignore unknown commands (per UCI spec)
                }
            } catch (Exception e) {
                // a malformed command must never kill the engine process
                System.out.println("info string error: " + e);
            }
            System.out.flush();
        }
    }

    // ---------------------------------------------------------------------

    private void setPosition(String[] tok) {
        int i = 1;
        if (i < tok.length && tok[i].equals("startpos")) {
            board = SearchBoard.startpos();
            i++;
        } else if (i < tok.length && tok[i].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            i++;
            while (i < tok.length && !tok[i].equals("moves"))
                fen.append(tok[i++]).append(' ');
            try {
                board = SearchBoard.fromFEN(fen.toString().trim());
            } catch (Exception e) {                       // malformed FEN — don't crash
                System.out.println("info string bad fen, using startpos");
                board = SearchBoard.startpos();
            }
        } else {
            board = SearchBoard.startpos();
        }
        histCount = 0;
        histHashes[histCount++] = board.hash;

        if (i < tok.length && tok[i].equals("moves")) {
            i++;
            int[] buf = new int[256];
            for (; i < tok.length; i++) {
                int m = parseMove(tok[i], buf);
                if (m == 0) break;                 // illegal — stop replaying
                board.make(m);
                if (histCount < histHashes.length) histHashes[histCount++] = board.hash;
            }
        }
    }

    /** Parse a coordinate move (e2e4, e7e8q, e1g1) against the current board. */
    private int parseMove(String mv, int[] buf) {
        if (mv.length() < 4) return 0;
        int from = SearchBoard.algToSq(mv.substring(0, 2));
        int to   = SearchBoard.algToSq(mv.substring(2, 4));
        char promo = mv.length() > 4 ? Character.toLowerCase(mv.charAt(4)) : 0;
        int n = board.genMoves(buf);
        for (int i = 0; i < n; i++) {
            int m = buf[i];
            if ((m & 63) != from || ((m >>> 6) & 63) != to) continue;
            int pr = (m >>> 12) & 15;
            if (promo == 0 && pr != 0) continue;
            if (promo != 0) {
                char c;
                switch (pr) {
                    case SearchBoard.WQ: case SearchBoard.BQ: c = 'q'; break;
                    case SearchBoard.WR: case SearchBoard.BR: c = 'r'; break;
                    case SearchBoard.WB: case SearchBoard.BB: c = 'b'; break;
                    case SearchBoard.WN: case SearchBoard.BN: c = 'n'; break;
                    default: c = 0;
                }
                if (c != promo) continue;
            }
            board.make(m);
            boolean legal = !board.inCheck(board.side ^ 1);
            board.unmake();
            if (legal) return m;
        }
        return 0;
    }

    // ---------------------------------------------------------------------

    /** Parse tok[idx] as a long if present and numeric, else return def (never throws). */
    private static long longArg(String[] tok, int idx, long def) {
        if (idx < tok.length && tok[idx].matches("-?\\d+")) {
            try { return Long.parseLong(tok[idx]); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private void startGo(String[] tok) {
        long movetime = -1, wtime = -1, btime = -1, winc = 0, binc = 0;
        int depth = 64;
        boolean infinite = false, depthSet = false;
        for (int i = 1; i < tok.length; i++) {
            switch (tok[i]) {
                case "movetime": movetime = longArg(tok, ++i, movetime); break;
                case "wtime":    wtime    = longArg(tok, ++i, wtime);    break;
                case "btime":    btime    = longArg(tok, ++i, btime);    break;
                case "winc":     winc     = longArg(tok, ++i, winc);     break;
                case "binc":     binc     = longArg(tok, ++i, binc);     break;
                case "depth":    depth    = (int) longArg(tok, ++i, depth); depthSet = true; break;
                case "infinite": infinite = true; break;
                default: if (i + 1 < tok.length && tok[i + 1].matches("-?\\d+")) i++;
            }
        }

        long alloc;
        long time = (board.side == SearchBoard.WHITE) ? wtime : btime;
        long inc  = (board.side == SearchBoard.WHITE) ? winc  : binc;
        if (infinite)            alloc = Long.MAX_VALUE / 4;      // until "stop"
        else if (movetime > 0)   alloc = Math.max(20, movetime - 20);
        else if (time > 0) {
            alloc = time / 30 + inc * 3 / 4;                      // simple allocator
            // clamp: floor for responsiveness, but never more than half the clock.
            // (min OUTSIDE max, so under ~100 ms the time/2 cap wins and we don't flag)
            alloc = Math.min(Math.max(50, alloc), time / 2);
        }
        else if (depthSet)       alloc = Long.MAX_VALUE / 4;      // "go depth N": reach the depth, no time cap
        else                     alloc = 5_000;                   // bare "go" with no limits

        final long allocF = alloc;
        final int depthF = depth;
        final SearchBoard pos = board;

        if (!infinite) {                                      // book (not in analysis)
            String bm = engine.bookMoveUci(pos);
            if (bm != null) {
                System.out.println("info string book move");
                System.out.println("bestmove " + bm);
                System.out.flush();
                return;
            }
        }

        engine.prepareForSearch();   // clear stop flag on the controller thread
        searchThread = new Thread(() -> {
            String best = "0000";
            try {
                best = engine.searchUci(pos, histHashes, histCount, allocF, depthF);
            } catch (Throwable t) {
                // never die silently: a UCI "go" must always be answered with a
                // bestmove, or the controlling GUI hangs forever waiting for one
                System.out.println("info string search error: " + t);
            }
            System.out.println("bestmove " + best);
            System.out.flush();
        }, "search");
        searchThread.setDaemon(true);
        searchThread.start();
    }

    private void waitForSearch() {
        if (searchThread != null && searchThread.isAlive()) {
            engine.stopSearch();
            try { searchThread.join(2_000); } catch (InterruptedException ignored) {}
        }
        searchThread = null;
    }
}
