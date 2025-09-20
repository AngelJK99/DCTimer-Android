package solver;

import java.util.Random;

/**
 * A state model for the 15-Puzzle (4x4 sliding puzzle).
 * <p>
 * This class provides methods to generate random solvable states and to
 * visualize scrambles. It correctly handles the permutation parity required
 * for a solvable 15-Puzzle.
 */
public class FifteenPuzzle {

    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_TILES = 16;
    private static final int BLANK_TILE_ID = 15;
    private static final int INVALID_MOVE = -1;

    // --- State & Lookup Tables ---
    // The current state of the puzzle, holding the tile in each of the 16 slots.
    private static int[] puzzleState = new int[16];
    private static int[][] MOVE_MAP = {
            {-1, 4, -1, 1}, {-1, 5, 0, 2}, {-1, 6, 1, 3}, {-1, 7, 2, -1}, {0, 8, -1, 5}, {1, 9, 4, 6}, {2, 10, 5, 7}, {3, 11, 6, -1},
            {4, 12, -1, 9}, {5, 13, 8, 10}, {6, 14, 9, 11}, {7, 15, 10, -1}, {8, -1, -1, 13}, {9, -1, 12, 14}, {10, -1, 13, 15}, {11, -1, 14, -1}
    };
    //</editor-fold>

    //<editor-fold desc="Public API">
    /**
     * Generates a visual representation of the puzzle state from a scramble string.
     *
     * @param scramble The scramble string to apply.
     * @param isPieceMove If true, the move directions (U/D, L/R) are inverted,
     * simulating moving a piece instead of the blank tile.
     * @return An integer array representing the final positions of the 16 tiles.
     */
    public static int[] getStateFromScramble(String scramble, boolean isPieceMove) {
        // --- 1. Initialize State ---
        // Reset the puzzle state to the solved configuration (0, 1, 2, ..., 15).
        for (int i = 0; i < NUM_TILES; i++) {
            puzzleState[i] = i;
        }
        // Keep track of the blank tile's current position.
        int blankTilePosition = BLANK_TILE_ID;
        String[] scrambleMoves = scramble.split(" ");
        // --- 2. Apply Scramble ---
        // Apply each move from the scramble string.
        for (String moveString : scrambleMoves) {
            if (!moveString.isEmpty()) {
                int moveDirection = 0;
                // Determine the move direction index (0=U, 1=D, 2=L, 3=R).
                // The isPieceMove flag inverts the standard directions.
                switch (moveString.charAt(0)) {
                    case 'U':
                        moveDirection = isPieceMove ? 1 : 0;
                        break;
                    case 'D':
                        moveDirection = isPieceMove ? 0 : 1;
                        break;
                    case 'L':
                        moveDirection = isPieceMove ? 3 : 2;
                        break;
                    case 'R':
                        moveDirection = isPieceMove ? 2 : 3;
                        break;
                }

                // --- Apply the move one or more times based on the suffix ---
                int turnCount = (moveString.length() > 1 && moveString.charAt(1) == '3') ? 3 :
                        (moveString.length() > 1) ? 2 : 1;

                for (int i = 0; i < turnCount; i++) {
                    // Find the destination for the blank tile using the move map.
                    int newBlankPosition = MOVE_MAP[blankTilePosition][moveDirection];
                    if (newBlankPosition != INVALID_MOVE) { // Check if the move is valid
                        // Swap the blank tile with the adjacent tile.
                        Utils.swap(puzzleState, blankTilePosition, newBlankPosition);
                        // Update the blank tile's position.
                        blankTilePosition = newBlankPosition;
                    }
                }
            }
        }
        return puzzleState;
    }
    //</editor-fold>

    //<editor-fold desc="Internal State Logic">
    /**
     * Creates a random, solvable state by shuffling the pieces and then correcting
     * the permutation parity if necessary.
     */
    private static void generateRandomSolvableState(Random r) {
        // --- 1. Reset to Solved State ---
        for (int i = 0; i < NUM_TILES; i++) {
            puzzleState[i] = i;
        }
        // --- 2. Shuffle the Tiles ---
        // This is a standard Fisher-Yates shuffle algorithm.
        for (int i = 0; i < NUM_TILES; i++) {
            // Pick a random index from the unshuffled part of the array.
            int swapIndex = i + r.nextInt(16 - i);
            if (swapIndex != i)
                Utils.swap(puzzleState, i, swapIndex);
        }

        // --- 3. Correct Parity if Necessary ---
        // An 8/15-Puzzle is only solvable if its permutation parity is even.
        // If the shuffled state has odd parity, we perform one more swap to make it even.
        if (getPermutationParity() != 0) {// 0 means even parity
            // This logic swaps two non-blank tiles to fix the parity.
            if (puzzleState[0] == BLANK_TILE_ID  || puzzleState[1] == BLANK_TILE_ID ) {
                Utils.swap(puzzleState, 2, 3);
            }
            else {
                Utils.swap(puzzleState, 0, 1);
            }
        }
    }



    /**
     * Calculates the permutation parity by counting inversions, ignoring the blank tile.
     * <p>
     * An inversion is any pair of tiles (a, b) such that a appears before b, but a > b.
     * The parity is crucial for determining if a 15-Puzzle state is solvable.
     *
     * @return 0 if the permutation is even (even number of inversions), 1 if it is odd.
     */

    private static int getPermutationParity() {
        int parity = 0; // 0 for even, 1 for odd.

        // Use nested loops to check every unique pair of tiles.
        for (int i = 0; i < NUM_TILES - 1; i++) {
            // The blank tile is not included in the parity calculation.
            if (puzzleState[i] == BLANK_TILE_ID) {
                continue;
            }
            for (int j = i + 1; j < NUM_TILES ; j++) {
                if (puzzleState[j] == BLANK_TILE_ID) {
                    continue;
                }
                // If a tile is larger than one that comes after it, it's an inversion.
                if (puzzleState[i] > puzzleState[j]) {
                    parity ^= 1;
                }
            }
        }
        return parity;
    }
    //</editor-fold>

}
