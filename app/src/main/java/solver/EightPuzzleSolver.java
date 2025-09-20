package solver;

import android.util.Log;

import java.util.Arrays;
import java.util.Random;
/**
 * A solver and visualizer for the 8-Puzzle (9-tile sliding puzzle).
 * <p>
 * This class uses a coordinate system for the permutation of the 9 tiles.
 * It pre-computes a pruning table that stores the minimum distance from every
 * state to the solved state, allowing for fast optimal solutions. It can
 * generate random scrambles and a visual representation of the puzzle state.
 */
public class EightPuzzleSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_PERMUTATION_STATES = 362880; // 9!
    private static final int NUM_TILES = 9;
    private static final int BLANK_TILE_ID = 8;
    private static final int NUM_MOVES = 4; // U, D, L, R
    private static final int MAX_SOLUTION_DEPTH = 25;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int NUM_TURN_TYPES = 2; // e.g., U and U2
    private static final int UNVISITED_STATE = -1;

    // --- Lookup Tables & State ---
    // Pruning table storing the minimum moves to the solved state.
    private static byte[] pruningTable = new byte[NUM_PERMUTATION_STATES];

    // A temporary array to hold the puzzle state during solving and visualization.
    private static int[] puzzleState = new int[NUM_TILES];

    // An array to store the found solution sequence.
    static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
    private static final String[] SUFFIXES = {" ", "2 "};
    // A map defining valid moves from each of the 9 tile positions.
    // Index: current blank position. Value: new blank position for moves U,D,L,R.
    private static final int[][] MOVE_MAP = {
            {-1, 3, -1, 1}, {-1, 4, 0, 2}, {-1, 5, 1, -1},
            {0, 6, -1, 4}, {1, 7, 3, 5}, {2, 8, 4, -1},
            {3, -1, -1, 7}, {4, -1, 6, 8}, {5, -1, 7, -1}
    };
    // --- General Variables ---
    private static boolean isInitialized = false;
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initialize();
    }
    /**
     * Initializes and pre-computes the pruning table for the 8-Puzzle.
     * <p>
     * This heavy computation is run only once. It uses a Breadth-First Search (BFS)
     * to populate a pruning table, storing the distance from every possible state
     * of the 9 tiles to the solved state.
     */
    private static void initialize() {
        // A guard to ensure this computation is only run once.
        if (isInitialized) {
            return;
        }
        long time = System.currentTimeMillis();

        // --- 1. Setup ---
        // A temporary array to hold the puzzle state during calculation.
        int[] basePermutation = new int[NUM_TILES];

        // Initialize the pruning table with an "unvisited" marker.
        Arrays.fill(pruningTable, (byte) UNVISITED_STATE);
        // Set the distance of the solved state (coordinate 0) to 0.
        pruningTable[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;

        // --- 2. Breadth-First Search (BFS) to Populate Table ---
        // This loop represents the layers of the BFS, starting from the solved state.
        int c = 1;
        for (int currentDepth = 0; currentDepth < MAX_SOLUTION_DEPTH; currentDepth++) {
            //c = 0;
            for (int currentStateCoord = 0; currentStateCoord < NUM_PERMUTATION_STATES; currentStateCoord++) {
                if (pruningTable[currentStateCoord] == currentDepth) {

                    // Unpack the current state's coordinate into a permutation array.
                    Utils.set11Perm(basePermutation, currentStateCoord, 9);

                    // For each state found, explore all possible next moves (U,D,L,R).
                    for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                        System.arraycopy(basePermutation, 0, puzzleState, 0, NUM_TILES);

                        // Try both single and double moves (e.g., U and U2).
                        for (int turn = 0; turn < NUM_TURN_TYPES; turn++) {
                            // Apply the physical move to the puzzle state.
                            int newBlankPos = applyMove(moveIndex);
                            if (newBlankPos == -1) break; // Break if the move is invalid.

                            // Re-pack the new permutation into its integer coordinate.
                            int nextStateCoord = Utils.get11Perm(puzzleState, NUM_TILES);

                            // If this new state has not been visited yet...
                            if (pruningTable[nextStateCoord] < 0) {
                                // ...mark its distance as one greater than the current depth.
                                pruningTable[nextStateCoord] = (byte) (currentDepth + 1);
                                c++;
                            }
                        }
                    }
                }
            } //Log.w("dct", d + 1 + "\t" + c);
        }
        time = System.currentTimeMillis() - time;
        Log.w("dct", "init "+time+"ms");
        isInitialized = true;
    }
    //</editor-fold>



    //<editor-fold desc="Public API">
    /**
     * Generates a random-state scramble for the 8-Puzzle.
     * <p>
     * This method creates a scramble by picking a random state that is guaranteed
     * to be at least a certain number of moves away from solved, and then finding
     * its optimal solution. The reversed solution is returned as the scramble string.
     *
     * @param randomGenerator A Random object instance.
     * @return A string representing the scramble.
     */
    public static String scramble(Random randomGenerator) {
        // --- Define Constants ---
        final int MIN_SCRAMBLE_DISTANCE = 4; // Ensures the scramble is not too easy.

        // Ensure the solver's lookup tables are initialized.
        initialize();

        // --- 1. Pick a Sufficiently Complex Random State ---
        int randomStateCoord;
        do {
            randomStateCoord = randomGenerator.nextInt(NUM_PERMUTATION_STATES);
        } while (pruningTable[randomStateCoord] < MIN_SCRAMBLE_DISTANCE);

        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(randomStateCoord, searchDepth)) {

                // --- 3. Format Solution ---
                // If a solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int moveDirection = moveCode >> 1; // U,D,L,R
                    int turnType = moveCode & 1;   // single or double move
                    scrambleBuilder.append("UDLR".charAt(moveDirection))
                                   .append(SUFFIXES[turnType]);
                }
                return scrambleBuilder.toString();
            }
        }
        // If no solution is found (should not happen in practice), return an error.
        return "error";
    }

    /**
     * Generates a state array representing the 8-Puzzle for a given scramble.
     * <p>
     * This method applies a sequence of moves to a solved-state model of the
     * puzzle and returns an array representing the final positions of the 9 tiles.
     *
     * @param scramble The scramble string to apply (e.g., "U L2 D").
     * @return An integer array representing the final state of the puzzle.
     */
    public static int[] getImageForScramble(String scramble) {
        // --- Define Constants ---
        // Note: The move character order may differ from the solver's internal order.
        final String MOVE_CHARS_INPUT = "DURL";

        // --- 1. Initialize State ---
        // Reset the puzzle state to the solved configuration (0, 1, 2, ..., 8).
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
                int moveDirection = MOVE_CHARS_INPUT.indexOf(moveString.charAt(0));

                // --- Apply first move (e.g., U) ---
                // Find the destination for the blank tile using the move map.
                int newBlankPosition = MOVE_MAP[blankTilePosition][moveDirection];
                if (newBlankPosition != -1) {
                    // Swap the blank tile with the adjacent tile.
                    Utils.swap(puzzleState, blankTilePosition, newBlankPosition);
                    // Update the blank tile's position.
                    blankTilePosition = newBlankPosition;
                }
                // --- Apply second move if it's a double turn (e.g., U2) ---
                if (moveString.length() > 1 && moveString.charAt(1) == '2') {
                    newBlankPosition = MOVE_MAP[blankTilePosition][moveDirection];
                    if (newBlankPosition  != -1) {
                        Utils.swap(puzzleState, blankTilePosition, newBlankPosition);
                        blankTilePosition = newBlankPosition ;
                    }
                }
            }
        }
        // Return the final state of the puzzle array.
        return puzzleState;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The recursive IDA* search function for the 8-Puzzle solver.
     *
     * @param currentStateCoord The permutation coordinate of the current puzzle state.
     * @param depthRemaining The number of moves left in the current search path.
     * @return True if a solution is found, false otherwise.
     */
    static boolean search(int currentStateCoord, int depthRemaining) {
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return currentStateCoord == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // If the pruning table says the minimum distance to solve is greater than
        // the depth we have left, this path is a dead end.
        if (pruningTable[currentStateCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        // Unpack the current state coordinate into a physical permutation array.
        int[] basePermutation = new int[NUM_TILES];
        Utils.set11Perm(basePermutation, currentStateCoord, NUM_TILES);
        for (int moveDirection = 0; moveDirection < NUM_MOVES; moveDirection++) {
            // Copy the base state to the global puzzleState array for the move function.
            System.arraycopy(basePermutation, 0, puzzleState, 0, NUM_TILES);

            // Try both single and double moves (e.g., U and U2).
            for (int turnCount = 0; turnCount < NUM_TURN_TYPES; turnCount++) {
                // Apply the physical move to the global puzzleState array.
                int newBlankPos = applyMove(moveDirection);
                if (newBlankPos == -1) { // Break if the move is invalid (e.g., off the board).
                    break;
                }

                // Re-pack the new state from the global array into its integer coordinate.
                int nextStateCoord  = Utils.get11Perm(puzzleState, NUM_TILES);

                // Make the recursive call for the new state.
                if (search(nextStateCoord, depthRemaining - 1)) {
                    // --- Solution Found! ---
                    // Record the successful move in the solution sequence array.
                    solutionSequence[depthRemaining] = moveDirection << 1 | turnCount;
                    return true;
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    /**
     * Helper method to apply a single move to the internal puzzle state.
     * <p>
     * This function finds the current position of the blank tile, calculates its
     * destination based on the move map, and performs the swap.
     *
     * @param moveIndex The index of the move to apply (0=U, 1=D, 2=L, 3=R).
     * @return The new position of the blank tile, or -1 if the move is invalid.
     */
    private static int applyMove(int moveIndex) {
        // --- 1. Find the Blank Tile ---
        int blankTilePosition = 0;
        for (; blankTilePosition < 9; blankTilePosition++) {
            if (puzzleState[blankTilePosition] == BLANK_TILE_ID) {
                break;
            }
        }

        // --- 2. Determine and Perform the Move ---
        // Look up the new position for the blank tile from the move map.
        int newBlankPosition = MOVE_MAP[blankTilePosition][moveIndex];

        // If the move is valid (not -1), swap the blank tile with the adjacent tile.
        if (newBlankPosition != -1) {
            Utils.swap(puzzleState, blankTilePosition, newBlankPosition);
        }

        // Return the new position of the blank tile.
        return newBlankPosition;
    }
    //</editor-fold>

}
