/**
 * Validates SearchBoard move generation + make/unmake against the standard
 * perft test suite. If all of these pass, the move generator is correct
 * (these positions cover castling through check, en-passant pins, promotions,
 * underpromotion checks, etc).
 */
public class Perft {

    private static final Object[][] TESTS = {
        // { name, FEN, depth, expected }
        {"startpos d5",  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 5, 4_865_609L},
        {"kiwipete d4",  "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 4, 4_085_603L},
        {"pos3 d5",      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 5, 674_624L},
        {"pos4 d4",      "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 4, 422_333L},
        {"pos5 d4",      "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 4, 2_103_487L},
        {"pos6 d4",      "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 4, 3_894_594L},
    };

    public static void main(String[] args) {
        boolean all = true;
        long grandNodes = 0;
        long t0 = System.currentTimeMillis();

        for (Object[] t : TESTS) {
            String name = (String) t[0];
            SearchBoard b = SearchBoard.fromFEN((String) t[1]);
            int depth = (Integer) t[2];
            long expected = (Long) t[3];

            long s = System.currentTimeMillis();
            long got = b.perft(depth);
            long ms = System.currentTimeMillis() - s;
            grandNodes += got;

            boolean ok = (got == expected);
            all &= ok;
            System.out.printf("%-13s expected %,12d  got %,12d  %5d ms  %s%n",
                    name, expected, got, ms, ok ? "OK" : "FAIL");
        }

        // incremental-hash integrity check (shallower depths, asserts every node)
        System.out.println();
        for (Object[] t : TESTS) {
            SearchBoard b = SearchBoard.fromFEN((String) t[1]);
            b.perftHashCheck(3);
        }
        System.out.println("Incremental Zobrist hash verified on all positions (depth 3, every node).");

        long total = System.currentTimeMillis() - t0;
        System.out.printf("%nTotal: %,d nodes in %d ms  (%,.0f nodes/sec)%n",
                grandNodes, total, grandNodes * 1000.0 / Math.max(1, total));
        System.out.println(all ? "\nALL PERFT TESTS PASSED" : "\nPERFT FAILURES — DO NOT SHIP");
        if (!all) System.exit(1);
    }
}
