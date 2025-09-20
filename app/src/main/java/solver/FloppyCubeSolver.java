package solver;

import android.util.Log;

import java.util.Random;

/**
 * A solver and visualizer for the Floppy Cube (1x3x3 cuboid).
 * <p>
 * This class uses coordinate systems for the permutation of the 4 corners and
 * the orientation (flip) of the 4 edges. It pre-computes a single combined
 * pruning table to find optimal solutions using only 180-degree moves.
 */
public class FloppyCubeSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_CORNER_PERM_STATES = 24;  // 4!
    private static final int NUM_EDGE_FLIP_STATES = 16;    // 2^4
    private static final int NUM_MOVES = 4;                // U2, R2, D2, L2
    private static final int MAX_SOLUTION_DEPTH = 10;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int NUM_FACELETS = 30;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int NUM_CORNERS_TO_TRACK = 4;

    // --- Lookup Tables & State ---
    // A combined pruning table for corner permutation and edge orientation.
    private static byte[][] pruningTable = new byte[NUM_CORNER_PERM_STATES][NUM_EDGE_FLIP_STATES];
    // Array to store the found solution sequence.
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
    // Array for the visual representation of the facelets.
    private static int[] faceletImage = new int[NUM_FACELETS];
    // --- General Variables ---

    private static String[] MOVE_CHARS = {"U", "R", "D", "L"};
    private static boolean isInitialized = false;
    private static int[] permutationArray = new int[4];


    //</editor-fold>


    //<editor-fold desc="Initialization">
    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initialize();
    }


    /**
     * Initializes and pre-computes the pruning table for the Floppy Cube solver.
     * <p>
     * This heavy computation is run only once. It uses a Breadth-First Search (BFS)
     * to populate a combined pruning table, storing the distance from every possible
     * state to the solved state.
     */
    private static void initialize() {
        for (int i = 0; i < NUM_CORNER_PERM_STATES; i++) {
            for (int j = 0; j < NUM_EDGE_FLIP_STATES; j++) {
                pruningTable[i][j] = UNVISITED_STATE;
            }
        }
        // Set the distance of the solved state (perm=0, flip=0) to 0.
        pruningTable[SOLVED_STATE_COORD][SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;

        int statesFound = 1;

        // --- 2. Breadth-First Search (BFS) to Populate Table ---
        // This loop represents the layers of the BFS, starting from the solved state.
        for (int currentDepth = 0; currentDepth < MAX_SOLUTION_DEPTH; currentDepth++) {
            // Scan all possible states to find the ones at the current search depth.
            for (int permCoord = 0; permCoord < NUM_CORNER_PERM_STATES; permCoord++) {
                for (int flipCoord = 0; flipCoord < NUM_EDGE_FLIP_STATES; flipCoord++) {
                    if (pruningTable[permCoord][flipCoord] == currentDepth) {

                        // For each state found, explore all 4 possible next moves (U2, R2, D2, L2).
                        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                            // Calculate the coordinates of the resulting state.
                            int nextPermCoord = calculateNewPermutationCoord(permCoord, moveIndex);
                            int nextFlipCoord = calculateNewFlipCoord(flipCoord, moveIndex);

                            // If this new state has not been visited yet...
                            if (pruningTable[nextPermCoord][nextFlipCoord] == UNVISITED_STATE) {
                                // ...mark its distance as one greater than the current depth.
                                pruningTable[nextPermCoord][nextFlipCoord] = (byte) (currentDepth + 1);
                                statesFound++;
                            }
                        }
                    }
                }
            }
            Log.w("dct", currentDepth + 1 + "\t" + statesFound);
        }
    }
    //</editor-fold>

    //<editor-fold desc="Public API">
    /**
     * Generates a random-state scramble for the Floppy Cube.
     * <p>
     * This method creates a scramble by picking a random state that is guaranteed
     * to be at least a certain number of moves away from solved, and then finding
     * its optimal solution. The reversed solution is returned as the scramble string.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramble() {
        // --- Define Constants ---
        final int MIN_SCRAMBLE_DISTANCE = 2; // Ensures the scramble is not trivially easy.
        final int INITIAL_SEARCH_DEPTH = 3;

        Random randomGenerator = new Random();

        // --- 1. Pick a Sufficiently Complex Random State ---
        int startCornerPermCoord, startEdgeFlipCoord;
        // Repeatedly pick a random state until we find one whose distance from solved
        // is at least the minimum required distance.
        do {
            startCornerPermCoord = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
            startEdgeFlipCoord = randomGenerator.nextInt(NUM_EDGE_FLIP_STATES);
        } while (pruningTable[startCornerPermCoord][startEdgeFlipCoord] < MIN_SCRAMBLE_DISTANCE);

        // --- 2. Iterative Deepening Search ---
        // Search for the shortest solution from the random state.
        for (int searchDepth = INITIAL_SEARCH_DEPTH; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(startCornerPermCoord, startEdgeFlipCoord, searchDepth, INITIAL_LAST_MOVE)) {

                // --- 3. Format Solution ---
                // If a solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++)
                    // All moves on a Floppy Cube are half-turns (180 degrees).
                    scrambleBuilder.append(MOVE_CHARS[solutionSequence[i]]).append("2 ");
                return scrambleBuilder.toString();
            }
        }
        // If no solution is found (should not happen in practice), return an error.
        return "error";
    }

    /**
     * Generates a visual image of the cube state from a scramble string.
     * <p>
     * This method applies a sequence of moves to a solved-state model of the
     * Floppy Cube and returns an array representing the final colors of the 30 facelets.
     *
     * @param scramble The scramble string to apply (e.g., "U2 R2 D2").
     * @return An integer array representing the colors of the 30 facelets.
     */
    public static int[] getImage(String scramble) {
        // --- Define Constant ---
        final String MOVE_CHARS_INPUT = "URDL";

        // Reset the internal facelet model to the solved state.
        initializeColorImage();
        String[] scrambleMoves = scramble.split(" ");
        for (String moveString : scrambleMoves) {
            if (!moveString.isEmpty()) {
                int moveIndex = MOVE_CHARS_INPUT.indexOf(moveString.charAt(0));
                applyImageMove(moveIndex);
            }
        }
        // Return the final state of the facelet color array.
        return faceletImage;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">

    /**
     * The recursive IDA* search function for the Floppy Cube solver.
     *
     * @param cornerPermCoord The current corner permutation coordinate.
     * @param edgeFlipCoord   The current edge flip coordinate.
     * @param depthRemaining  The number of moves left in the current search path.
     * @param lastMove        The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int cornerPermCoord, int edgeFlipCoord, int depthRemaining, int lastMove) {
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return cornerPermCoord == 0 && edgeFlipCoord == 0;
        }

        // --- Heuristic Pruning ---
        // If the pruning table says the minimum distance to solve is greater than
        // the depth we have left, this path is a dead end.
        if (pruningTable[cornerPermCoord][edgeFlipCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                // Get the next state for both coordinates by calling the helper functions.
                int nextPermCoord = calculateNewPermutationCoord(cornerPermCoord, moveIndex);
                int nextFlipCoord = calculateNewFlipCoord(edgeFlipCoord, moveIndex);

                // Make the recursive call for the new state.
                if (search(nextPermCoord, nextFlipCoord, depthRemaining - 1, moveIndex)) {
                    // --- Solution Found! ---
                    // Record the successful move in the solution sequence array.
                    solutionSequence[depthRemaining] = moveIndex;
                    //sb.insert(0, turn[i] + " ");
                    return true;
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helpers">

    /**
     * Calculates the new corner permutation coordinate after a half-turn.
     * <p>
     * This helper method is used to build the corner permutation move table. It decodes
     * a permutation coordinate, applies the physical swap of a 180-degree move,
     * and then re-encodes the array back into a new coordinate.
     *
     * @param permCoord The starting corner permutation coordinate (0-23).
     * @param moveIndex The index of the move to apply (0=U2, 1=R2, 2=D2, 3=L2).
     * @return The new corner permutation coordinate after the move.
     */
    private static int calculateNewPermutationCoord(int permCoord, int moveIndex) {


        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array representing the permutation.
        Utils.idxToPerm(permutationArray, permCoord, NUM_CORNERS_TO_TRACK, false);
        switch (moveIndex) {
            case 0: Utils.swap(permutationArray, 0, 1); break; // U2-move
            case 1: Utils.swap(permutationArray, 1, 2); break; // R2-move
            case 2: Utils.swap(permutationArray, 2, 3); break; // D2-move
            case 3: Utils.swap(permutationArray, 0, 3); break; // L2-move
        }
        // --- 3. Repack Coordinate ---
        // Convert the modified permutation array back into its integer coordinate.
        return Utils.permToIdx(FloppyCubeSolver.permutationArray, NUM_CORNERS_TO_TRACK, false);
    }

    /**
     * Calculates the new edge flip coordinate after a half-turn.
     * <p>
     * This helper method is used to build the edge flip move table. A 180-degree
     * turn on a Floppy Cube only affects the orientation of the single edge pair
     * being moved, so this function simply toggles that edge's flip state.
     *
     * @param flipCoord The starting edge flip coordinate (0-15).
     * @param moveIndex The index of the move being applied (0-3), which corresponds
     * to the edge being flipped.
     * @return The new edge flip coordinate after the move.
     */
    private static int calculateNewFlipCoord(int flipCoord, int moveIndex) {
        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array of 0s and 1s.
        Utils.idxToFlip(permutationArray, flipCoord, 4, false);

        // --- 2. Apply Move ---
        // A half-turn on a Floppy Cube flips the orientation of the edge being moved.
        // We simulate this by toggling the bit (0 becomes 1, 1 becomes 0).

        permutationArray[moveIndex] = 1 - permutationArray[moveIndex];
        // --- 3. Repack Coordinate ---
        // Convert the modified flip array back into its integer coordinate.
        return Utils.flipToIdx(permutationArray, 4, false);
    }
    //</editor-fold>


    //<editor-fold desc="Internal Visualization Logic">
    /**
     * Initializes or resets the facelet image array to the solved state.
     * <p>
     * This method copies a pre-defined constant array representing the solved
     * state into the main facelet image variable.
     */
    private static void initializeColorImage() {
        faceletImage = new int[] {
                   3, 3, 3,
                5, 4, 4, 4, 2, 1, 1, 1,
                5, 4, 4, 4, 2, 1, 1, 1,
                5, 4, 4, 4, 2, 1, 1, 1,
                   0, 0, 0
        };
    }

    /**
     * Applies the physical permutation of facelets for a single half-turn.
     *
     * @param moveIndex The index of the move to apply (0=U2, 1=R2, 2=D2, 3=L2).
     */
    private static void applyImageMove(int moveIndex) {
        // --- Define Constants for Move Types ---
        final int U_MOVE = 0;
        final int R_MOVE = 1;
        final int D_MOVE = 2;
        final int L_MOVE = 3;

        switch (moveIndex) {
            case U_MOVE: // U2 move
                Utils.swapTwoPairs(faceletImage,  0,  2,  3,  7);
                Utils.swapTwoPairs(faceletImage,  4,  8,  6, 10);
                Utils.swap(faceletImage,  5,  9); break;
            case R_MOVE:  // R2 move
                Utils.swapTwoPairs(faceletImage,  7, 23,  2, 29);
                Utils.swapTwoPairs(faceletImage,  6, 24, 22,  8);
                Utils.swap(faceletImage, 14, 16); break;
            case D_MOVE: // D2 move
                Utils.swapTwoPairs(faceletImage, 27, 29, 19, 23);
                Utils.swapTwoPairs(faceletImage, 20, 24, 22, 26);
                Utils.swap(faceletImage, 21, 25); break;
            case L_MOVE: // L2 move
                Utils.swapTwoPairs(faceletImage,  3, 19,  0, 27);
                Utils.swapTwoPairs(faceletImage,  4, 26, 20, 10);
                Utils.swap(faceletImage, 12, 18); break;
        }
    }
    //</editor-fold>

}
