import java.util.Random;

/**
 * Fast internal board used by the AI search.
 *
 *   • int-coded pieces in a flat 64-array (no object allocation in the tree)
 *   • make / unmake instead of deep copies          (~30x faster search)
 *   • incremental Zobrist hash including side, castling rights and en-passant file
 *   • int-encoded moves:  bits 0-5 from | 6-11 to | 12-15 promo piece | flags
 *
 * Row convention matches the rest of the project: row 0 = rank 8, row 7 = rank 1,
 * square index = row*8 + col  (a8 = 0, h1 = 63).
 */
final class SearchBoard {

    // ---- piece codes -------------------------------------------------------
    static final int EMPTY = 0;
    static final int WP = 1, WN = 2, WB = 3, WR = 4, WQ = 5, WK = 6;
    static final int BP = 7, BN = 8, BB = 9, BR = 10, BQ = 11, BK = 12;

    static final int WHITE = 0, BLACK = 1;

    // ---- move encoding -----------------------------------------------------
    static final int F_EP     = 1 << 16;   // en-passant capture
    static final int F_CASTLE = 1 << 17;   // castling
    static final int F_DPP    = 1 << 18;   // double pawn push

    static int from(int m)  { return m & 63; }
    static int to(int m)    { return (m >>> 6) & 63; }
    static int promo(int m) { return (m >>> 12) & 15; }

    // ---- state -------------------------------------------------------------
    final int[] sq = new int[64];
    int side;                 // WHITE / BLACK to move
    int castle;               // bits: 1=W O-O  2=W O-O-O  4=B O-O  8=B O-O-O
    int ep = -1;              // en-passant target square, or -1
    int halfmove;             // half-moves since last pawn move / capture
    long hash;
    final int[] kingSq = new int[2];

    // ---- undo stacks -------------------------------------------------------
    private static final int STACK = 2048;   // deep enough for very long UCI games
    private final int[]  uMove     = new int[STACK];
    private final int[]  uCaptured = new int[STACK];
    private final int[]  uCastle   = new int[STACK];
    private final int[]  uEp       = new int[STACK];
    private final int[]  uHalf     = new int[STACK];
    private final long[] uHash     = new long[STACK];
    private int sp = 0;

    // ---- Zobrist keys ------------------------------------------------------
    static final long[][] Z   = new long[13][64];
    static final long[]   ZC  = new long[16];
    static final long[]   ZEP = new long[8];
    static final long     ZSIDE;
    static {
        Random r = new Random(0x9E3779B97F4A7C15L);
        for (int p = 1; p < 13; p++)
            for (int s = 0; s < 64; s++) Z[p][s] = r.nextLong();
        for (int i = 0; i < 16; i++) ZC[i]  = r.nextLong();
        for (int f = 0; f < 8;  f++) ZEP[f] = r.nextLong();
        ZSIDE = r.nextLong();
    }

    /** Which castle-rights bits survive when a move touches this square. */
    private static final int[] CASTLE_MASK = new int[64];
    static {
        java.util.Arrays.fill(CASTLE_MASK, 15);
        CASTLE_MASK[60] = 15 & ~3;   // e1: white loses both
        CASTLE_MASK[63] = 15 & ~1;   // h1
        CASTLE_MASK[56] = 15 & ~2;   // a1
        CASTLE_MASK[4]  = 15 & ~12;  // e8: black loses both
        CASTLE_MASK[7]  = 15 & ~4;   // h8
        CASTLE_MASK[0]  = 15 & ~8;   // a8
    }

    // ========================================================================
    //  Setup
    // ========================================================================

    static SearchBoard startpos() {
        return fromFEN("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    static SearchBoard fromFEN(String fen) {
        SearchBoard b = new SearchBoard();
        String[] parts = fen.trim().split("\\s+");
        int s = 0;
        for (char ch : parts[0].toCharArray()) {
            if (ch == '/') continue;
            if (ch >= '1' && ch <= '8') { s += ch - '0'; continue; }
            int code;
            switch (ch) {
                case 'P': code = WP; break; case 'N': code = WN; break;
                case 'B': code = WB; break; case 'R': code = WR; break;
                case 'Q': code = WQ; break; case 'K': code = WK; break;
                case 'p': code = BP; break; case 'n': code = BN; break;
                case 'b': code = BB; break; case 'r': code = BR; break;
                case 'q': code = BQ; break; case 'k': code = BK; break;
                default: throw new IllegalArgumentException("bad FEN piece: " + ch);
            }
            b.sq[s++] = code;
        }
        b.side = (parts.length > 1 && parts[1].equals("b")) ? BLACK : WHITE;
        b.castle = 0;
        if (parts.length > 2) {
            String c = parts[2];
            if (c.indexOf('K') >= 0) b.castle |= 1;
            if (c.indexOf('Q') >= 0) b.castle |= 2;
            if (c.indexOf('k') >= 0) b.castle |= 4;
            if (c.indexOf('q') >= 0) b.castle |= 8;
        }
        b.ep = -1;
        if (parts.length > 3 && !parts[3].equals("-")) b.ep = algToSq(parts[3]);
        b.halfmove = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;
        for (int i = 0; i < 64; i++) {
            if (b.sq[i] == WK) b.kingSq[WHITE] = i;
            if (b.sq[i] == BK) b.kingSq[BLACK] = i;
        }
        b.hash = b.computeHash();
        return b;
    }

    static int algToSq(String a) {
        int col = a.charAt(0) - 'a';
        int row = 8 - (a.charAt(1) - '0');
        return row * 8 + col;
    }

    static String sqToAlg(int s) {
        return "" + (char) ('a' + (s & 7)) + (8 - (s >> 3));
    }

    long computeHash() {
        long h = 0;
        for (int i = 0; i < 64; i++) if (sq[i] != EMPTY) h ^= Z[sq[i]][i];
        if (side == BLACK) h ^= ZSIDE;
        h ^= ZC[castle];
        if (ep != -1) h ^= ZEP[ep & 7];
        return h;
    }

    static boolean isWhite(int piece) { return piece >= WP && piece <= WK; }

    // ========================================================================
    //  Make / unmake
    // ========================================================================

    void make(int m) {
        uMove[sp] = m; uCastle[sp] = castle; uEp[sp] = ep;
        uHalf[sp] = halfmove; uHash[sp] = hash;

        int f = m & 63, t = (m >>> 6) & 63;
        int piece = sq[f];
        int captured = EMPTY;
        long h = hash;

        if (ep != -1) h ^= ZEP[ep & 7];
        h ^= ZC[castle];
        int newEp = -1;

        if ((m & F_CASTLE) != 0) {
            h ^= Z[piece][f] ^ Z[piece][t];
            sq[t] = piece; sq[f] = EMPTY;
            int rFrom, rTo;
            if (t == f + 2) { rFrom = f + 3; rTo = f + 1; }   // king side
            else            { rFrom = f - 4; rTo = f - 1; }   // queen side
            int rook = sq[rFrom];
            h ^= Z[rook][rFrom] ^ Z[rook][rTo];
            sq[rTo] = rook; sq[rFrom] = EMPTY;
            kingSq[side] = t;
            halfmove++;
        } else if ((m & F_EP) != 0) {
            int capSq = t + (side == WHITE ? 8 : -8);
            captured = sq[capSq];
            h ^= Z[piece][f] ^ Z[piece][t] ^ Z[captured][capSq];
            sq[t] = piece; sq[f] = EMPTY; sq[capSq] = EMPTY;
            halfmove = 0;
        } else {
            captured = sq[t];
            int pr = (m >>> 12) & 15;
            if (captured != EMPTY) { h ^= Z[captured][t]; halfmove = 0; }
            else halfmove++;
            if (piece == WP || piece == BP) halfmove = 0;
            h ^= Z[piece][f];
            if (pr != 0) { h ^= Z[pr][t]; sq[t] = pr; }
            else         { h ^= Z[piece][t]; sq[t] = piece; }
            sq[f] = EMPTY;
            if (piece == WK || piece == BK) kingSq[side] = t;
            if ((m & F_DPP) != 0) newEp = (f + t) >> 1;
        }

        uCaptured[sp] = captured;
        castle &= CASTLE_MASK[f] & CASTLE_MASK[t];
        h ^= ZC[castle];
        ep = newEp;
        if (ep != -1) h ^= ZEP[ep & 7];
        side ^= 1;
        h ^= ZSIDE;
        hash = h;
        sp++;
    }

    void unmake() {
        sp--;
        int m = uMove[sp];
        castle = uCastle[sp]; ep = uEp[sp];
        halfmove = uHalf[sp]; hash = uHash[sp];
        side ^= 1;

        int f = m & 63, t = (m >>> 6) & 63;
        if ((m & F_CASTLE) != 0) {
            int piece = sq[t];
            sq[f] = piece; sq[t] = EMPTY;
            kingSq[side] = f;
            int rFrom, rTo;
            if (t == f + 2) { rFrom = f + 3; rTo = f + 1; }
            else            { rFrom = f - 4; rTo = f - 1; }
            sq[rFrom] = sq[rTo]; sq[rTo] = EMPTY;
        } else if ((m & F_EP) != 0) {
            int piece = sq[t];
            sq[f] = piece; sq[t] = EMPTY;
            int capSq = t + (side == WHITE ? 8 : -8);
            sq[capSq] = uCaptured[sp];
        } else {
            int pr = (m >>> 12) & 15;
            int piece = (pr != 0) ? (side == WHITE ? WP : BP) : sq[t];
            sq[f] = piece;
            sq[t] = uCaptured[sp];
            if (piece == WK || piece == BK) kingSq[side] = f;
        }
    }

    void makeNull() {
        uMove[sp] = 0; uCastle[sp] = castle; uEp[sp] = ep;
        uHalf[sp] = halfmove; uHash[sp] = hash; uCaptured[sp] = EMPTY;
        if (ep != -1) hash ^= ZEP[ep & 7];
        ep = -1;
        side ^= 1;
        hash ^= ZSIDE;
        halfmove++;
        sp++;
    }

    void unmakeNull() {
        sp--;
        castle = uCastle[sp]; ep = uEp[sp];
        halfmove = uHalf[sp]; hash = uHash[sp];
        side ^= 1;
    }

    // ========================================================================
    //  Attack detection
    // ========================================================================

    private static final int[][] KNIGHT_OFF = {{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}};
    private static final int[][] KING_OFF   = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final int[][] ROOK_DIRS  = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final int[][] BISHOP_DIRS= {{1,1},{1,-1},{-1,1},{-1,-1}};

    boolean isSquareAttacked(int s, int bySide) {
        int r = s >> 3, c = s & 7;

        // pawns
        if (bySide == WHITE) {
            if (r + 1 < 8) {
                if (c > 0 && sq[(r + 1) * 8 + c - 1] == WP) return true;
                if (c < 7 && sq[(r + 1) * 8 + c + 1] == WP) return true;
            }
        } else {
            if (r - 1 >= 0) {
                if (c > 0 && sq[(r - 1) * 8 + c - 1] == BP) return true;
                if (c < 7 && sq[(r - 1) * 8 + c + 1] == BP) return true;
            }
        }

        int n = (bySide == WHITE) ? WN : BN;
        for (int[] o : KNIGHT_OFF) {
            int rr = r + o[0], cc = c + o[1];
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && sq[rr * 8 + cc] == n) return true;
        }

        int k = (bySide == WHITE) ? WK : BK;
        for (int[] o : KING_OFF) {
            int rr = r + o[0], cc = c + o[1];
            if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && sq[rr * 8 + cc] == k) return true;
        }

        int rk = (bySide == WHITE) ? WR : BR;
        int q  = (bySide == WHITE) ? WQ : BQ;
        for (int[] d : ROOK_DIRS) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                int p = sq[rr * 8 + cc];
                if (p != EMPTY) { if (p == rk || p == q) return true; break; }
                rr += d[0]; cc += d[1];
            }
        }

        int bi = (bySide == WHITE) ? WB : BB;
        for (int[] d : BISHOP_DIRS) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                int p = sq[rr * 8 + cc];
                if (p != EMPTY) { if (p == bi || p == q) return true; break; }
                rr += d[0]; cc += d[1];
            }
        }
        return false;
    }

    boolean inCheck(int s) { return isSquareAttacked(kingSq[s], s ^ 1); }

    // ========================================================================
    //  Move generation (pseudo-legal; caller filters with make + inCheck)
    //  Castling legality (not through / out of check) IS checked here.
    // ========================================================================

    /** All pseudo-legal moves for the side to move. Returns count. */
    int genMoves(int[] buf) {
        int n = genCaptures(buf);
        n = genQuiets(buf, n);
        return n;
    }

    /** Captures, en-passant and ALL promotions (incl. quiet ones). Returns count. */
    int genCaptures(int[] buf) {
        int n = 0;
        boolean white = (side == WHITE);
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == EMPTY || isWhite(p) != white) continue;
            int r = s >> 3, c = s & 7;
            switch (p) {
                case WP: case BP: {
                    int dir = white ? -1 : 1;
                    int promoRow = white ? 0 : 7;
                    int nr = r + dir;
                    if (nr < 0 || nr > 7) break;
                    // captures
                    for (int dc = -1; dc <= 1; dc += 2) {
                        int nc = c + dc;
                        if (nc < 0 || nc > 7) continue;
                        int t = nr * 8 + nc;
                        int tp = sq[t];
                        if (tp != EMPTY && isWhite(tp) != white) {
                            if (nr == promoRow) {
                                int base = s | (t << 6);
                                if (white) {
                                    buf[n++] = base | (WQ << 12); buf[n++] = base | (WR << 12);
                                    buf[n++] = base | (WB << 12); buf[n++] = base | (WN << 12);
                                } else {
                                    buf[n++] = base | (BQ << 12); buf[n++] = base | (BR << 12);
                                    buf[n++] = base | (BB << 12); buf[n++] = base | (BN << 12);
                                }
                            } else buf[n++] = s | (t << 6);
                        } else if (t == ep && tp == EMPTY) {
                            buf[n++] = s | (t << 6) | F_EP;
                        }
                    }
                    // quiet promotions (pushed to last rank)
                    if (nr == promoRow && sq[nr * 8 + c] == EMPTY) {
                        int t = nr * 8 + c;
                        int base = s | (t << 6);
                        if (white) {
                            buf[n++] = base | (WQ << 12); buf[n++] = base | (WR << 12);
                            buf[n++] = base | (WB << 12); buf[n++] = base | (WN << 12);
                        } else {
                            buf[n++] = base | (BQ << 12); buf[n++] = base | (BR << 12);
                            buf[n++] = base | (BB << 12); buf[n++] = base | (BN << 12);
                        }
                    }
                    break;
                }
                case WN: case BN:
                    for (int[] o : KNIGHT_OFF) {
                        int rr = r + o[0], cc = c + o[1];
                        if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                        int t = rr * 8 + cc, tp = sq[t];
                        if (tp != EMPTY && isWhite(tp) != white) buf[n++] = s | (t << 6);
                    }
                    break;
                case WK: case BK:
                    for (int[] o : KING_OFF) {
                        int rr = r + o[0], cc = c + o[1];
                        if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                        int t = rr * 8 + cc, tp = sq[t];
                        if (tp != EMPTY && isWhite(tp) != white) buf[n++] = s | (t << 6);
                    }
                    break;
                case WB: case BB:
                    n = slideCaps(buf, n, s, r, c, BISHOP_DIRS, white);
                    break;
                case WR: case BR:
                    n = slideCaps(buf, n, s, r, c, ROOK_DIRS, white);
                    break;
                case WQ: case BQ:
                    n = slideCaps(buf, n, s, r, c, BISHOP_DIRS, white);
                    n = slideCaps(buf, n, s, r, c, ROOK_DIRS, white);
                    break;
            }
        }
        return n;
    }

    private int slideCaps(int[] buf, int n, int s, int r, int c, int[][] dirs, boolean white) {
        for (int[] d : dirs) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                int t = rr * 8 + cc, tp = sq[t];
                if (tp != EMPTY) {
                    if (isWhite(tp) != white) buf[n++] = s | (t << 6);
                    break;
                }
                rr += d[0]; cc += d[1];
            }
        }
        return n;
    }

    /** Quiet (non-capture, non-promotion) moves, appended starting at index n0. */
    private int genQuiets(int[] buf, int n) {
        boolean white = (side == WHITE);
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == EMPTY || isWhite(p) != white) continue;
            int r = s >> 3, c = s & 7;
            switch (p) {
                case WP: case BP: {
                    int dir = white ? -1 : 1;
                    int startRow = white ? 6 : 1;
                    int promoRow = white ? 0 : 7;
                    int nr = r + dir;
                    if (nr < 0 || nr > 7 || nr == promoRow) break; // promos done in genCaptures
                    int one = nr * 8 + c;
                    if (sq[one] == EMPTY) {
                        buf[n++] = s | (one << 6);
                        if (r == startRow) {
                            int two = (r + 2 * dir) * 8 + c;
                            if (sq[two] == EMPTY) buf[n++] = s | (two << 6) | F_DPP;
                        }
                    }
                    break;
                }
                case WN: case BN:
                    for (int[] o : KNIGHT_OFF) {
                        int rr = r + o[0], cc = c + o[1];
                        if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                        int t = rr * 8 + cc;
                        if (sq[t] == EMPTY) buf[n++] = s | (t << 6);
                    }
                    break;
                case WK: case BK: {
                    for (int[] o : KING_OFF) {
                        int rr = r + o[0], cc = c + o[1];
                        if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                        int t = rr * 8 + cc;
                        if (sq[t] == EMPTY) buf[n++] = s | (t << 6);
                    }
                    // castling
                    if (white && s == 60) {
                        if ((castle & 1) != 0 && sq[61] == EMPTY && sq[62] == EMPTY
                                && !isSquareAttacked(60, BLACK) && !isSquareAttacked(61, BLACK)
                                && !isSquareAttacked(62, BLACK))
                            buf[n++] = 60 | (62 << 6) | F_CASTLE;
                        if ((castle & 2) != 0 && sq[59] == EMPTY && sq[58] == EMPTY && sq[57] == EMPTY
                                && !isSquareAttacked(60, BLACK) && !isSquareAttacked(59, BLACK)
                                && !isSquareAttacked(58, BLACK))
                            buf[n++] = 60 | (58 << 6) | F_CASTLE;
                    } else if (!white && s == 4) {
                        if ((castle & 4) != 0 && sq[5] == EMPTY && sq[6] == EMPTY
                                && !isSquareAttacked(4, WHITE) && !isSquareAttacked(5, WHITE)
                                && !isSquareAttacked(6, WHITE))
                            buf[n++] = 4 | (6 << 6) | F_CASTLE;
                        if ((castle & 8) != 0 && sq[3] == EMPTY && sq[2] == EMPTY && sq[1] == EMPTY
                                && !isSquareAttacked(4, WHITE) && !isSquareAttacked(3, WHITE)
                                && !isSquareAttacked(2, WHITE))
                            buf[n++] = 4 | (2 << 6) | F_CASTLE;
                    }
                    break;
                }
                case WB: case BB:
                    n = slideQuiets(buf, n, s, r, c, BISHOP_DIRS);
                    break;
                case WR: case BR:
                    n = slideQuiets(buf, n, s, r, c, ROOK_DIRS);
                    break;
                case WQ: case BQ:
                    n = slideQuiets(buf, n, s, r, c, BISHOP_DIRS);
                    n = slideQuiets(buf, n, s, r, c, ROOK_DIRS);
                    break;
            }
        }
        return n;
    }

    private int slideQuiets(int[] buf, int n, int s, int r, int c, int[][] dirs) {
        for (int[] d : dirs) {
            int rr = r + d[0], cc = c + d[1];
            while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
                int t = rr * 8 + cc;
                if (sq[t] != EMPTY) break;
                buf[n++] = s | (t << 6);
                rr += d[0]; cc += d[1];
            }
        }
        return n;
    }

    // ========================================================================
    //  Perft (validation)
    // ========================================================================

    long perft(int depth) {
        if (depth == 0) return 1;
        int[] buf = new int[256];
        int n = genMoves(buf);
        long total = 0;
        for (int i = 0; i < n; i++) {
            make(buf[i]);
            if (!inCheck(side ^ 1)) total += perft(depth - 1);
            unmake();
        }
        return total;
    }

    /** Perft that also asserts the incremental hash equals a from-scratch hash. */
    long perftHashCheck(int depth) {
        if (hash != computeHash())
            throw new IllegalStateException("hash mismatch!");
        if (depth == 0) return 1;
        int[] buf = new int[256];
        int n = genMoves(buf);
        long total = 0;
        for (int i = 0; i < n; i++) {
            make(buf[i]);
            if (!inCheck(side ^ 1)) total += perftHashCheck(depth - 1);
            unmake();
        }
        return total;
    }

    String moveToString(int m) {
        StringBuilder sb = new StringBuilder();
        sb.append(sqToAlg(m & 63)).append(sqToAlg((m >>> 6) & 63));
        int pr = (m >>> 12) & 15;
        if (pr != 0) {
            switch (pr) {
                case WQ: case BQ: sb.append('q'); break;
                case WR: case BR: sb.append('r'); break;
                case WB: case BB: sb.append('b'); break;
                case WN: case BN: sb.append('n'); break;
            }
        }
        return sb.toString();
    }
}
