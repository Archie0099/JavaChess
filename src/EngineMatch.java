import java.util.List;

/** New ImprovedAI vs old LegacyAI. Alternating colors, ~250ms/move. */
public class EngineMatch {
    public static void main(String[] args) throws Exception {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        long ms   = args.length > 1 ? Long.parseLong(args[1])  : 250;
        int newWins = 0, oldWins = 0, draws = 0;
        boolean hadError = false;

        for (int g = 1; g <= games; g++) {
            boolean newIsWhite = (g % 2 == 1);
            ImprovedAI ni = new ImprovedAI(3, ms);
            LegacyAI   li = new LegacyAI(3, ms);
            Game game = new Game();
            int plies = 0;
            String result;

            while (true) {
                if (game.isGameOver()) { result = adjudicate(game); break; }
                if (plies >= 300)      { result = "1/2-1/2 (move cap)"; break; }
                boolean whiteToMove = game.getCurrentPlayer() == PieceColor.WHITE;
                Move m = (whiteToMove == newIsWhite) ? ni.findBestMove(game)
                                                     : li.findBestMove(game);
                if (m == null) {
                    if (!game.isGameOver()) hadError = true;   // engine gave no move in a live position
                    result = adjudicate(game); break;
                }
                if (!game.makeMove(m)) { result = "makeMove failed (" + (whiteToMove==newIsWhite?"NEW":"OLD") + ")"; hadError = true; break; }
                plies++;
            }

            String winner;
            if (result.startsWith("White wins"))      winner = newIsWhite ? "NEW" : "OLD";
            else if (result.startsWith("Black wins")) winner = newIsWhite ? "OLD" : "NEW";
            else                                      winner = "DRAW";
            if (winner.equals("NEW")) newWins++;
            else if (winner.equals("OLD")) oldWins++;
            else draws++;

            System.out.println("Game " + g + ": NEW=" + (newIsWhite?"White":"Black")
                + "  result=" + result + "  -> " + winner + "  (" + plies + " plies)");
            System.out.flush();
        }
        System.out.println("FINAL: NEW " + newWins + " - OLD " + oldWins + " - draws " + draws);
        if (hadError) {
            System.out.println("ERROR: an engine produced an illegal or missing move (see per-game lines above)");
            System.exit(1);
        }
    }

    static String adjudicate(Game game) {
        String s = game.getResultString();
        if (s != null && !s.isEmpty()) return s;
        if (game.isInCheckmate())
            return game.getCurrentPlayer() == PieceColor.WHITE ? "Black wins by checkmate" : "White wins by checkmate";
        return "1/2-1/2";
    }
}
