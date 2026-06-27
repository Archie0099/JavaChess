import java.util.*;

/**
 * Cross-checks the GUI rules engine (Game/Board, the original code) against
 * the perft-proven SearchBoard on thousands of random positions. Any
 * disagreement in legal moves or check status means a rules bug in Game.java.
 */
public class CrossCheck {
    public static void main(String[] args) {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        Random rng = new Random(42);
        long positions = 0;
        int mismatches = 0;

        for (int g = 0; g < games && mismatches < 10; g++) {
            Game game = new Game();
            for (int ply = 0; ply < 140 && !game.isGameOver(); ply++) {
                positions++;
                SearchBoard sb = ImprovedAI.boardFromGame(game);

                // legal (from,to) pairs from the perft-proven board
                Set<String> sbMoves = new TreeSet<>();
                int[] buf = new int[256];
                int n = sb.genMoves(buf);
                for (int i = 0; i < n; i++) {
                    sb.make(buf[i]);
                    boolean ok = !sb.inCheck(sb.side ^ 1);
                    sb.unmake();
                    if (ok) sbMoves.add(SearchBoard.sqToAlg(buf[i] & 63)
                                      + SearchBoard.sqToAlg((buf[i] >>> 6) & 63));
                }

                // legal (from,to) pairs from Game
                List<Move> legal = game.getAllLegalMoves(game.getCurrentPlayer());
                Set<String> gMoves = new TreeSet<>();
                for (Move m : legal)
                    gMoves.add(m.from.toAlgebraic() + m.to.toAlgebraic());

                boolean checkAgree =
                        game.isInCheck(game.getCurrentPlayer()) == sb.inCheck(sb.side);

                if (!sbMoves.equals(gMoves) || !checkAgree) {
                    mismatches++;
                    System.out.println("MISMATCH at game " + g + " ply " + ply
                            + (checkAgree ? "" : "  [check-status differs]"));
                    Set<String> onlyG = new TreeSet<>(gMoves);  onlyG.removeAll(sbMoves);
                    Set<String> onlyS = new TreeSet<>(sbMoves); onlyS.removeAll(gMoves);
                    if (!onlyG.isEmpty()) System.out.println("  only in Game:        " + onlyG);
                    if (!onlyS.isEmpty()) System.out.println("  only in SearchBoard: " + onlyS);
                    System.out.println("  history: " + history(game));
                    break;
                }

                if (legal.isEmpty()) break;
                game.makeMove(legal.get(rng.nextInt(legal.size())));
            }
        }

        System.out.printf("%nChecked %,d positions across %d random games.%n", positions, games);
        System.out.println(mismatches == 0
                ? "Game.java and SearchBoard AGREE EVERYWHERE - both rule engines are consistent."
                : mismatches + " MISMATCHES FOUND");
        if (mismatches > 0) System.exit(1);
    }

    static String history(Game g) {
        StringBuilder sb = new StringBuilder();
        for (Move m : g.getHistory())
            sb.append(m.from.toAlgebraic()).append(m.to.toAlgebraic())
              .append(m.isPromotion ? String.valueOf(m.promotion) : "").append(' ');
        return sb.toString();
    }
}
