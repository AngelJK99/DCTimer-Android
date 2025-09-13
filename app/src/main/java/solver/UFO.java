package solver;

import java.util.*;

/**
 * A solver for a UFO-type puzzle, likely a Megaminx.
 * <p>
 * This class uses a single coordinate system for the permutation of 11 pieces.
 * It pre-computes a pruning table that stores the minimum distance from every
 * state to the solved state, allowing for fast optimal solves.
 */
public class UFO {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_PERMUTATION_STATES = 39916800; // 11!
    // The table is packed, storing 2 distance values per byte.
    private static final int PRUNING_TABLE_SIZE = NUM_PERMUTATION_STATES / 8;
    private static final int NUM_TRACKED_PIECES = 11;
    private static final int MAX_PRUNING_DEPTH = 14;
    private static final int SOLVED_STATE_COORD = 0;

    // --- Class Variables ---
    // Pruning table storing the minimum moves to the solved state.
    static int[] pruningTable;

    // Defines how many times a base move is repeated for a full turn.
    static int[] movesPerTurn = {5, 1, 1, 1};

    // Flag to ensure initialization only runs once.
    static boolean isInitialized = false;
    //</editor-fold>

    /**
     * Initializes and pre-computes the pruning table for the solver.
     * This heavy computation is run only once.
     */
    static void initialize() {
        if (isInitialized) {
            return;
        }

        // --- Define Constants ---
        final int UNVISITED_MARKER = 0xf; // 15 represents an unvisited state in a 4-bit slot.

        // --- 1. Setup ---
        // Temporary arrays to hold permutations during calculation.
        int[] currentPermutation = new int[NUM_TRACKED_PIECES];
        int[] basePermutation = new int[NUM_TRACKED_PIECES];

        // Initialize the pruning table with a value indicating "unvisited".
        pruningTable = new int[PRUNING_TABLE_SIZE];
        Arrays.fill(pruningTable, -1);

        // Set the distance of the solved state (coordinate 0) to 0.
        Utils.setPruning(pruningTable, 0, 0);
        // --- 2. Breadth-First Search (BFS) to Populate Table ---
        // This loop represents the layers of the BFS, starting from the solved state.
        for (int currentDepth = 0; currentDepth < MAX_PRUNING_DEPTH; currentDepth++) {
            int statesFoundInLayer = 0;
            // Scan all 11! possible states to find the ones at the current search depth.
            for (int currentStateCoord = 0; currentStateCoord < NUM_PERMUTATION_STATES ; currentStateCoord++) {
                if (Utils.getPruning(pruningTable, currentStateCoord) == currentDepth) {
                    // Unpack the current state's coordinate into a permutation array.
                    Utils.set11Perm(basePermutation, currentStateCoord, NUM_TRACKED_PIECES);

                    // For each state found, explore all possible next moves.
                    for (int moveType = 0; moveType < 4; moveType++) {
                        System.arraycopy(basePermutation, 0, currentPermutation, 0, NUM_TRACKED_PIECES);
                        //Map.set11Perm(arr, i);
                        for (int repetition = 0; repetition < movesPerTurn[moveType]; repetition++) {
                            applyMove(currentPermutation, moveType);

                            // Re-pack the new permutation into its integer coordinate.
                            int nextStateCoord = Utils.get11Perm(currentPermutation, NUM_TRACKED_PIECES);
                            // If this new state has not been visited yet...
                            if (Utils.getPruning(pruningTable, nextStateCoord) == UNVISITED_MARKER) {
                                // ...mark its distance as one greater than the current depth.
                                Utils.setPruning(pruningTable, nextStateCoord, currentDepth + 1);
                                statesFoundInLayer++;
                            }
                        }
                    }
                }
            }
            //Log.w("dct", d+1+"\t"+n);
        }
        isInitialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Move Physics">
    /**
     * Defines the physical permutation of pieces for each of the puzzle's moves.
     * <p>
     * This method takes a permutation array and modifies it in-place according to
     * the specified move type (U, A, B, or C).
     *
     * @param permutationArray The array representing the current piece permutation, which will be modified.
     * @param moveIndex        The index of the move to apply.
     */
    static void applyMove(int[] permutationArray, int moveIndex) {
        // --- Define Constants for Move Types ---
        final int U_MOVE = 0;
        final int A_MOVE = 1;
        final int B_MOVE = 2;
        final int C_MOVE = 3;

        switch (moveIndex) {
            case U_MOVE:	//U
                int temp = permutationArray[0];
                permutationArray[0] = permutationArray[5];
                permutationArray[5] = permutationArray[4];
                permutationArray[4] = permutationArray[3];
                permutationArray[3] = permutationArray[2];
                permutationArray[2] = permutationArray[1];
                permutationArray[1] = temp ;
                break;
            case A_MOVE:	 // A series of three swaps for the 'A' move.
                Utils.swapTwoPairs(permutationArray, 4, 10, 0, 7);
                Utils.swap(permutationArray, 5, 6);
                break;
            case B_MOVE:	// A series of three swaps for the 'B' move.
                Utils.swapTwoPairs(permutationArray, 5, 6, 3, 8);
                Utils.swap(permutationArray, 4, 7);
                break;
            case C_MOVE:	// A series of three swaps for the 'C' move.
                Utils.swapTwoPairs(permutationArray, 2, 7, 4, 9);
                Utils.swap(permutationArray, 3, 8);
                break;
        }
    }
    //</editor-fold>

}
