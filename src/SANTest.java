import java.util.List;

public class SANTest {
    static int fails = 0;

    public static void main(String[] a) {
        // 1. Castling + capture notation (Ruy exchange)
        Game g = new Game();
        play(g, "e2e4","e7e5","g1f3","b8c6","f1b5","a7a6","b5c6","d7c6","e1g1");
        List<String> san = g.getSANHistory();
        expect(san.get(4), "Bb5");
        expect(san.get(6), "Bxc6");
        expect(san.get(7), "dxc6");
        expect(san.get(8), "O-O");

        // 2. En passant
        g = new Game();
        play(g, "e2e4","a7a6","e4e5","d7d5","e5d6");
        expect(g.getSANHistory().get(4), "exd6");

        // 3. Knight disambiguation (Nb1 and Nf3 both reach d2)
        g = new Game();
        play(g, "d2d4","d7d5","g1f3","g8f6","b1d2");
        expect(g.getSANHistory().get(4), "Nbd2");

        // 4. Fool's mate suffix
        g = new Game();
        play(g, "f2f3","e7e5","g2g4","d8h4");
        expect(g.getSANHistory().get(3), "Qh4#");

        // 5. Promotion with check
        g = new Game();
        Board b = g.getBoard();
        for (int r=0;r<8;r++) for(int c=0;c<8;c++) b.setPiece(new Position(r,c), null);
        b.setPiece(new Position(7,6), new King(PieceColor.WHITE));   // g1
        b.setPiece(new Position(1,1), new Pawn(PieceColor.WHITE));   // b7
        b.setPiece(new Position(3,1), new King(PieceColor.BLACK));   // b5
        Move promo = null;
        for (Move m : g.getAllLegalMoves(PieceColor.WHITE))
            if (m.isPromotion && m.promotion == 'Q') promo = m;
        g.makeMove(promo);
        expect(g.getSANHistory().get(0), "b8=Q+");

        System.out.println(fails == 0 ? "ALL SAN TESTS PASSED" : fails + " SAN FAILURES");
        if (fails > 0) System.exit(1);
    }

    static void expect(String got, String want) {
        boolean ok = want.equals(got);
        if (!ok) fails++;
        System.out.printf("%-8s %s %s%n", want, ok ? "==" : "!=", got);
    }

    static void play(Game g, String... moves) {
        for (String uci : moves) {
            int fc=uci.charAt(0)-'a', fr=8-(uci.charAt(1)-'0');
            int tc=uci.charAt(2)-'a', tr=8-(uci.charAt(3)-'0');
            boolean done = false;
            for (Move m : g.getAllLegalMoves(g.getCurrentPlayer()))
                if (m.from.row==fr && m.from.col==fc && m.to.row==tr && m.to.col==tc) {
                    g.makeMove(m); done = true; break;
                }
            if (!done) throw new IllegalStateException("bad test move " + uci);
        }
    }
}
