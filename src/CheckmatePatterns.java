import java.util.*;

/**
 * Checkmate pattern recognition and endgame guidance.
 * Teaches the AI how to execute basic checkmates.
 */
public class CheckmatePatterns {
    
    /**
     * Analyzes position and returns guidance for executing checkmates
     */
    public static CheckmateGuidance analyzePosition(Board board, PieceColor strongerSide) {
        PieceColor weakerSide = strongerSide.opposite();
        
        MaterialCount stronger = countMaterial(board, strongerSide);
        MaterialCount weaker = countMaterial(board, weakerSide);
        
        Position strongKing = findKing(board, strongerSide);
        Position weakKing = findKing(board, weakerSide);
        
        if (strongKing == null || weakKing == null) {
            return new CheckmateGuidance();
        }
        
        // Check if this is a known checkmate pattern
        CheckmatePattern pattern = identifyPattern(stronger, weaker);
        
        CheckmateGuidance guidance = new CheckmateGuidance();
        guidance.pattern = pattern;
        guidance.evaluation = evaluateCheckmateProgress(board, strongerSide, pattern);
        
        return guidance;
    }
    
    private static CheckmatePattern identifyPattern(MaterialCount stronger, MaterialCount weaker) {
        // Weaker side must have only king for basic checkmates
        if (weaker.queens > 0 || weaker.rooks > 0 || weaker.bishops > 0 || 
            weaker.knights > 0 || weaker.pawns > 0) {
            return CheckmatePattern.NONE;
        }
        
        // Queen vs King
        if (stronger.queens == 1 && stronger.rooks == 0 && stronger.bishops == 0 && 
            stronger.knights == 0 && stronger.pawns == 0) {
            return CheckmatePattern.QUEEN_VS_KING;
        }
        
        // Rook vs King  
        if (stronger.rooks == 1 && stronger.queens == 0 && stronger.bishops == 0 && 
            stronger.knights == 0 && stronger.pawns == 0) {
            return CheckmatePattern.ROOK_VS_KING;
        }
        
        // Two Rooks vs King
        if (stronger.rooks == 2 && stronger.queens == 0 && stronger.bishops == 0 && 
            stronger.knights == 0 && stronger.pawns == 0) {
            return CheckmatePattern.TWO_ROOKS_VS_KING;
        }
        
        // Two Bishops vs King
        if (stronger.bishops == 2 && stronger.queens == 0 && stronger.rooks == 0 && 
            stronger.knights == 0 && stronger.pawns == 0) {
            return CheckmatePattern.TWO_BISHOPS_VS_KING;
        }
        
        // Bishop + Knight vs King
        if (stronger.bishops == 1 && stronger.knights == 1 && stronger.queens == 0 && 
            stronger.rooks == 0 && stronger.pawns == 0) {
            return CheckmatePattern.BISHOP_KNIGHT_VS_KING;
        }
        
        return CheckmatePattern.NONE;
    }
    
    /**
     * Evaluates progress toward checkmate for known patterns
     */
    private static int evaluateCheckmateProgress(Board board, PieceColor strongerSide, CheckmatePattern pattern) {
        Position strongKing = findKing(board, strongerSide);
        Position weakKing = findKing(board, strongerSide.opposite());
        
        if (strongKing == null || weakKing == null) return 0;
        
        switch (pattern) {
            case QUEEN_VS_KING:
                return evaluateQueenVsKing(board, strongerSide, strongKing, weakKing);
            case ROOK_VS_KING:
                return evaluateRookVsKing(board, strongerSide, strongKing, weakKing);
            case TWO_ROOKS_VS_KING:
                return evaluateTwoRooksVsKing(board, strongerSide, strongKing, weakKing);
            case TWO_BISHOPS_VS_KING:
                return evaluateTwoBishopsVsKing(board, strongerSide, strongKing, weakKing);
            case BISHOP_KNIGHT_VS_KING:
                return evaluateBishopKnightVsKing(board, strongerSide, strongKing, weakKing);
            default:
                return 0;
        }
    }
    
    private static int evaluateQueenVsKing(Board board, PieceColor strongerSide, Position strongKing, Position weakKing) {
        Position queen = findPiece(board, strongerSide, Queen.class);
        if (queen == null) return 0;
        
        int score = 0;
        
        // 1. Drive enemy king to edge
        int edgeDistance = Math.min(Math.min(weakKing.row, 7 - weakKing.row), 
                                   Math.min(weakKing.col, 7 - weakKing.col));
        score += (3 - edgeDistance) * 50;
        
        // 2. Queen should maintain safe distance (not too close to avoid stalemate)
        int queenDistance = Math.abs(queen.row - weakKing.row) + Math.abs(queen.col - weakKing.col);
        if (queenDistance < 2) score -= 30; // Too close
        else if (queenDistance > 4) score -= 10; // Too far
        
        // 3. King should support queen
        int kingDistance = Math.abs(strongKing.row - weakKing.row) + Math.abs(strongKing.col - weakKing.col);
        score += (8 - kingDistance) * 10;
        
        // 4. Restrict enemy king mobility
        int mobility = countKingMobility(board, weakKing, strongerSide.opposite());
        score += (8 - mobility) * 15;
        
        return score;
    }
    
    private static int evaluateRookVsKing(Board board, PieceColor strongerSide, Position strongKing, Position weakKing) {
        Position rook = findPiece(board, strongerSide, Rook.class);
        if (rook == null) return 0;
        
        int score = 0;
        
        // 1. Drive king to edge
        int edgeDistance = Math.min(Math.min(weakKing.row, 7 - weakKing.row), 
                                   Math.min(weakKing.col, 7 - weakKing.col));
        score += (3 - edgeDistance) * 40;
        
        // 2. Rook should cut off king (same rank or file but not adjacent)
        boolean cutoff = false;
        if (rook.row == weakKing.row && Math.abs(rook.col - weakKing.col) > 1) cutoff = true;
        if (rook.col == weakKing.col && Math.abs(rook.row - weakKing.row) > 1) cutoff = true;
        if (cutoff) score += 30;
        
        // 3. Opposition: kings should face each other with rook between or supporting
        int kingDistance = Math.abs(strongKing.row - weakKing.row) + Math.abs(strongKing.col - weakKing.col);
        if (kingDistance == 2) score += 25; // Good opposition
        else if (kingDistance > 4) score -= 10; // King too far away
        
        // 4. Rook safety
        if (isSquareAttacked(board, rook, strongerSide.opposite())) {
            score -= 50; // Rook under attack
        }
        
        return score;
    }
    
    private static int evaluateTwoRooksVsKing(Board board, PieceColor strongerSide, Position strongKing, Position weakKing) {
        List<Position> rooks = findPieces(board, strongerSide, Rook.class);
        if (rooks.size() < 2) return 0;
        
        int score = 0;
        
        // Drive king to edge
        int edgeDistance = Math.min(Math.min(weakKing.row, 7 - weakKing.row), 
                                   Math.min(weakKing.col, 7 - weakKing.col));
        score += (3 - edgeDistance) * 60;
        
        // Rooks should work together - ladder mate pattern
        Position rook1 = rooks.get(0);
        Position rook2 = rooks.get(1);
        
        // Bonus if rooks are on same rank/file and cutting off king
        if (rook1.row == rook2.row && rook1.row != weakKing.row) {
            if (Math.abs(rook1.row - weakKing.row) <= 2) score += 40;
        }
        if (rook1.col == rook2.col && rook1.col != weakKing.col) {
            if (Math.abs(rook1.col - weakKing.col) <= 2) score += 40;
        }
        
        return score;
    }
    
    private static int evaluateTwoBishopsVsKing(Board board, PieceColor strongerSide, Position strongKing, Position weakKing) {
        List<Position> bishops = findPieces(board, strongerSide, Bishop.class);
        if (bishops.size() < 2) return 0;
        
        int score = 0;
        
        // Drive king to corner (more complex than edge)
        int cornerDistance = Math.min(
            Math.min(Math.abs(weakKing.row - 0) + Math.abs(weakKing.col - 0),
                    Math.abs(weakKing.row - 0) + Math.abs(weakKing.col - 7)),
            Math.min(Math.abs(weakKing.row - 7) + Math.abs(weakKing.col - 0),
                    Math.abs(weakKing.row - 7) + Math.abs(weakKing.col - 7))
        );
        score += (10 - cornerDistance) * 20;
        
        // Bishops should control different colored squares
        Position bishop1 = bishops.get(0);
        Position bishop2 = bishops.get(1);
        boolean differentColors = ((bishop1.row + bishop1.col) % 2) != ((bishop2.row + bishop2.col) % 2);
        if (differentColors) score += 50;
        
        // King support is crucial
        int kingDistance = Math.abs(strongKing.row - weakKing.row) + Math.abs(strongKing.col - weakKing.col);
        score += (6 - kingDistance) * 15;
        
        return score;
    }
    
    private static int evaluateBishopKnightVsKing(Board board, PieceColor strongerSide, Position strongKing, Position weakKing) {
        Position bishop = findPiece(board, strongerSide, Bishop.class);
        Position knight = findPiece(board, strongerSide, Knight.class);
        if (bishop == null || knight == null) return 0;
        
        int score = 0;
        
        // This is the hardest basic checkmate - drive king to corner of bishop's color
        boolean bishopOnLightSquares = (bishop.row + bishop.col) % 2 == 0;
        int targetCornerDistance;
        
        if (bishopOnLightSquares) {
            // Light squared bishop -> corners a1 or h8
            targetCornerDistance = Math.min(
                Math.abs(weakKing.row - 7) + Math.abs(weakKing.col - 0), // a1
                Math.abs(weakKing.row - 0) + Math.abs(weakKing.col - 7)  // h8
            );
        } else {
            // Dark squared bishop -> corners a8 or h1  
            targetCornerDistance = Math.min(
                Math.abs(weakKing.row - 0) + Math.abs(weakKing.col - 0), // a8
                Math.abs(weakKing.row - 7) + Math.abs(weakKing.col - 7)  // h1
            );
        }
        
        score += (12 - targetCornerDistance) * 15;
        
        // Pieces should work in coordination
        int kingSupport = Math.abs(strongKing.row - weakKing.row) + Math.abs(strongKing.col - weakKing.col);
        score += (5 - kingSupport) * 10;
        
        return score;
    }
    
    // Helper methods
    private static MaterialCount countMaterial(Board board, PieceColor color) {
        MaterialCount count = new MaterialCount();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board.getPiece(new Position(r, c));
                if (p == null || p.getColor() != color) continue;
                
                if (p instanceof Pawn) count.pawns++;
                else if (p instanceof Knight) count.knights++;
                else if (p instanceof Bishop) count.bishops++;
                else if (p instanceof Rook) count.rooks++;
                else if (p instanceof Queen) count.queens++;
            }
        }
        return count;
    }
    
    private static Position findKing(Board board, PieceColor color) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Position pos = new Position(r, c);
                Piece p = board.getPiece(pos);
                if (p != null && p.getColor() == color && p instanceof King) {
                    return pos;
                }
            }
        }
        return null;
    }
    
    private static Position findPiece(Board board, PieceColor color, Class<? extends Piece> pieceClass) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Position pos = new Position(r, c);
                Piece p = board.getPiece(pos);
                if (p != null && p.getColor() == color && pieceClass.isInstance(p)) {
                    return pos;
                }
            }
        }
        return null;
    }
    
    private static List<Position> findPieces(Board board, PieceColor color, Class<? extends Piece> pieceClass) {
        List<Position> positions = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Position pos = new Position(r, c);
                Piece p = board.getPiece(pos);
                if (p != null && p.getColor() == color && pieceClass.isInstance(p)) {
                    positions.add(pos);
                }
            }
        }
        return positions;
    }
    
    private static boolean isSquareAttacked(Board board, Position square, PieceColor attacker) {
        return board.isSquareAttacked(square, attacker);
    }
    
    private static int countKingMobility(Board board, Position kingPos, PieceColor kingColor) {
        int mobility = 0;
        int[][] directions = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
        
        for (int[] dir : directions) {
            int newRow = kingPos.row + dir[0];
            int newCol = kingPos.col + dir[1];
            
            if (newRow >= 0 && newRow < 8 && newCol >= 0 && newCol < 8) {
                Position newPos = new Position(newRow, newCol);
                Piece piece = board.getPiece(newPos);
                
                if (piece == null || piece.getColor() != kingColor) {
                    Board tempBoard = board.deepCopy();
                    tempBoard.setPiece(newPos, tempBoard.getPiece(kingPos));
                    tempBoard.setPiece(kingPos, null);
                    
                    if (!tempBoard.isSquareAttacked(newPos, kingColor.opposite())) {
                        mobility++;
                    }
                }
            }
        }
        return mobility;
    }
    
    // Supporting classes and enums
    public static class CheckmateGuidance {
        public CheckmatePattern pattern = CheckmatePattern.NONE;
        public int evaluation = 0;
        public String advice = "";
        
        public boolean isWinningEndgame() {
            return pattern != CheckmatePattern.NONE;
        }
    }
    
    public enum CheckmatePattern {
        NONE,
        QUEEN_VS_KING,
        ROOK_VS_KING,
        TWO_ROOKS_VS_KING,
        TWO_BISHOPS_VS_KING,
        BISHOP_KNIGHT_VS_KING
    }
    
    private static class MaterialCount {
        int pawns = 0, knights = 0, bishops = 0, rooks = 0, queens = 0;
    }}