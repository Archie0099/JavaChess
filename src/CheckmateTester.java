/**
 * Test suite to verify that the AI can execute basic checkmates.
 * Sets up known endgame positions and tests if the AI makes progress.
 */
public class CheckmateTester {
    static int fails = 0;

    public static void main(String[] args) {
        System.out.println("Testing AI Checkmate Knowledge...\n");

        testQueenVsKing();
        testRookVsKing();
        testTwoRooksVsKing();
        testTwoBishopsVsKing();

        System.out.println(fails == 0 ? "All tests completed." : fails + " CHECKMATE TEST FAILURES");
        if (fails > 0) System.exit(1);
    }
    
    public static void testQueenVsKing() {
        System.out.println("=== Testing Queen vs King ===");
        
        // Set up position: White King on e1, White Queen on d2, Black King on e8
        Game game = new Game();
        Board board = game.getBoard();
        
        // Clear board
        clearBoard(board);
        
        // Set up pieces
        board.setPiece(new Position(7, 4), new King(PieceColor.WHITE));  // e1
        board.setPiece(new Position(6, 3), new Queen(PieceColor.WHITE)); // d2  
        board.setPiece(new Position(0, 4), new King(PieceColor.BLACK));  // e8
        
        // Test AI's ability to make progress
        ImprovedAI ai = new ImprovedAI(3); // Medium difficulty
        
        System.out.println("Initial position:");
        printBoard(board);
        
        int movesPlayed = 0;
        int maxMoves = 20; // Should mate much sooner than this
        
        while (movesPlayed < maxMoves && !game.isGameOver()) {
            Move aiMove = ai.findBestMove(game);
            if (aiMove == null) {
                System.out.println("AI has no moves!");
                break;
            }
            
            game.makeMove(aiMove);
            movesPlayed++;
            
            System.out.println("Move " + movesPlayed + ": " + aiMove.toAlgebraic());
            
            if (game.isInCheckmate()) {
                System.out.println("CHECKMATE achieved in " + movesPlayed + " moves");
                break;
            }
            
            // Switch to dummy move for black (just move king randomly)
            if (game.getCurrentPlayer() == PieceColor.BLACK) {
                Move blackMove = findRandomLegalMove(game);
                if (blackMove != null) {
                    game.makeMove(blackMove);
                    movesPlayed++;
                }
            }
        }
        
        if (!game.isInCheckmate()) {
            System.out.println("FAILED to achieve checkmate in " + movesPlayed + " moves");
            fails++;
        }
        
        System.out.println();
    }
    
    public static void testRookVsKing() {
        System.out.println("=== Testing Rook vs King ===");
        
        Game game = new Game();
        Board board = game.getBoard();
        
        clearBoard(board);
        
        // White King on f1, White Rook on a1, Black King on h8
        board.setPiece(new Position(7, 5), new King(PieceColor.WHITE));  // f1
        board.setPiece(new Position(7, 0), new Rook(PieceColor.WHITE));  // a1
        board.setPiece(new Position(0, 7), new King(PieceColor.BLACK));  // h8
        
        ImprovedAI ai = new ImprovedAI(3);
        
        System.out.println("Initial position:");
        printBoard(board);
        
        int movesPlayed = 0;
        int maxMoves = 25;
        
        while (movesPlayed < maxMoves && !game.isGameOver()) {
            Move aiMove = ai.findBestMove(game);
            if (aiMove == null) break;
            
            game.makeMove(aiMove);
            movesPlayed++;
            
            System.out.println("Move " + movesPlayed + ": " + aiMove.toAlgebraic());
            
            if (game.isInCheckmate()) {
                System.out.println("CHECKMATE achieved in " + movesPlayed + " moves");
                break;
            }
            
            if (game.getCurrentPlayer() == PieceColor.BLACK) {
                Move blackMove = findRandomLegalMove(game);
                if (blackMove != null) {
                    game.makeMove(blackMove);
                    movesPlayed++;
                }
            }
        }
        
        if (!game.isInCheckmate()) {
            System.out.println("FAILED to achieve checkmate in " + movesPlayed + " moves");
            fails++;
        }
        
        System.out.println();
    }
    
    public static void testTwoRooksVsKing() {
        System.out.println("=== Testing Two Rooks vs King ===");
        
        Game game = new Game();
        Board board = game.getBoard();
        
        clearBoard(board);
        
        // White King on d1, White Rooks on a1 and h1, Black King on e8
        board.setPiece(new Position(7, 3), new King(PieceColor.WHITE));  // d1
        board.setPiece(new Position(7, 0), new Rook(PieceColor.WHITE));  // a1
        board.setPiece(new Position(7, 7), new Rook(PieceColor.WHITE));  // h1
        board.setPiece(new Position(0, 4), new King(PieceColor.BLACK));  // e8
        
        ImprovedAI ai = new ImprovedAI(3);
        
        System.out.println("Initial position:");
        printBoard(board);
        
        int movesPlayed = 0;
        int maxMoves = 15; // Should be very fast
        
        while (movesPlayed < maxMoves && !game.isGameOver()) {
            Move aiMove = ai.findBestMove(game);
            if (aiMove == null) break;
            
            game.makeMove(aiMove);
            movesPlayed++;
            
            System.out.println("Move " + movesPlayed + ": " + aiMove.toAlgebraic());
            
            if (game.isInCheckmate()) {
                System.out.println("CHECKMATE achieved in " + movesPlayed + " moves");
                break;
            }
            
            if (game.getCurrentPlayer() == PieceColor.BLACK) {
                Move blackMove = findRandomLegalMove(game);
                if (blackMove != null) {
                    game.makeMove(blackMove);
                    movesPlayed++;
                }
            }
        }
        
        if (!game.isInCheckmate()) {
            System.out.println("FAILED to achieve checkmate in " + movesPlayed + " moves");
            fails++;
        }
        
        System.out.println();
    }
    
    public static void testTwoBishopsVsKing() {
        System.out.println("=== Testing Two Bishops vs King ===");
        
        Game game = new Game();
        Board board = game.getBoard();
        
        clearBoard(board);
        
        // White King on e4, White Bishops on c1 and f1, Black King on h8
        board.setPiece(new Position(4, 4), new King(PieceColor.WHITE));    // e4
        board.setPiece(new Position(7, 2), new Bishop(PieceColor.WHITE));  // c1 (light squared)
        board.setPiece(new Position(7, 5), new Bishop(PieceColor.WHITE));  // f1 (dark squared)
        board.setPiece(new Position(0, 7), new King(PieceColor.BLACK));    // h8
        
        ImprovedAI ai = new ImprovedAI(4); // Harder endgame needs more depth
        
        System.out.println("Initial position:");
        printBoard(board);
        
        int movesPlayed = 0;
        int maxMoves = 40; // This is the hardest basic checkmate
        
        while (movesPlayed < maxMoves && !game.isGameOver()) {
            Move aiMove = ai.findBestMove(game);
            if (aiMove == null) break;
            
            game.makeMove(aiMove);
            movesPlayed++;
            
            System.out.println("Move " + movesPlayed + ": " + aiMove.toAlgebraic());
            
            if (game.isInCheckmate()) {
                System.out.println("CHECKMATE achieved in " + movesPlayed + " moves");
                break;
            }
            
            if (game.getCurrentPlayer() == PieceColor.BLACK) {
                Move blackMove = findRandomLegalMove(game);
                if (blackMove != null) {
                    game.makeMove(blackMove);
                    movesPlayed++;
                }
            }
        }
        
        if (!game.isInCheckmate()) {
            System.out.println("FAILED to achieve checkmate in " + movesPlayed + " moves");
            fails++;
            System.out.println("Note: Two bishops vs King is very difficult and may require deeper search");
        }
        
        System.out.println();
    }
    
    // Helper methods
    private static void clearBoard(Board board) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                board.setPiece(new Position(r, c), null);
            }
        }
    }
    
    private static void printBoard(Board board) {
        System.out.println("  a b c d e f g h");
        for (int r = 0; r < 8; r++) {
            System.out.print((8 - r) + " ");
            for (int c = 0; c < 8; c++) {
                Piece p = board.getPiece(new Position(r, c));
                if (p == null) {
                    System.out.print(". ");
                } else {
                    char symbol = p.getSymbol();
                    if (p.getColor() == PieceColor.WHITE) {
                        symbol = Character.toUpperCase(symbol);
                    }
                    System.out.print(symbol + " ");
                }
            }
            System.out.println();
        }
        System.out.println();
    }
    
    private static Move findRandomLegalMove(Game game) {
        java.util.List<Move> legalMoves = game.getAllLegalMoves(game.getCurrentPlayer());
        if (legalMoves.isEmpty()) return null;
        
        // Prefer king moves for more realistic defensive play
        java.util.List<Move> kingMoves = new java.util.ArrayList<>();
        for (Move m : legalMoves) {
            if (m.movedPiece instanceof King) {
                kingMoves.add(m);
            }
        }
        
        if (!kingMoves.isEmpty()) {
            return kingMoves.get((int)(Math.random() * kingMoves.size()));
        }
        
        return legalMoves.get((int)(Math.random() * legalMoves.size()));
    }
}