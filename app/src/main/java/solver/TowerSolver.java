package solver;

import java.util.Random;

import static solver.Utils.turnSuffixInverse;

/**
 * A solver and visualizer for a Tower Cube (2x2x3 cuboid).
 * <p>
 * This class uses coordinate systems for the permutation of the 8 corners and
 * 3 equatorial edges. It pre-computes lookup tables to find optimal solutions
 * using only U, D, R2, and F2 moves. It also includes methods to generate a
 * visual representation of the cube's state.
 */
public class TowerSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    public static final int NUM_CORNER_PERM_STATES = 40320; // 8!
    private static final int NUM_EDGE_PERM_STATES = 6;       // 3!
    private static final int NUM_MOVES = 4;                  // U, R2, F2, D
    private static final int MAX_SOLUTION_DEPTH = 20;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_COORD = 0;

    private static final int INITIAL_LAST_MOVE = -1;

    // --- Lookup Tables ---

    // Move tables for corner and edge permutations.
    static char[][] moveTableCornerPerm = new char[NUM_CORNER_PERM_STATES][NUM_MOVES];
    static short[][] moveTableEdgePerm = new short[NUM_EDGE_PERM_STATES][NUM_MOVES];

    // Pruning tables for corner and edge permutations.
    static byte[] pruningTableCorner = new byte[NUM_CORNER_PERM_STATES];
    static byte[] PRUNING_TABLE_EDGE = {0, 1, 1, 2, 2, 3};
    //private static StringBuilder sb;

    // --- Solver & State Variables ---
    // Array to store the found solution sequence.
    private static int[] solutionSequence = new int[20];
    // Defines the number of repetitions for each move type (U/D are quarter turns, R/F are half turns).
    private static byte[] TURNS_PER_MOVE = {3, 1, 1, 3};
    private static String[] MOVE_CHARS = {"U", "R", "F", "D"};

    // Array for the visual representation of the 32 facelets.
    private static int[] SOLVED_FACELET_IMAGE = new int[32];
    // Flag to ensure initialization only runs once.
    private static boolean isInitialized = false;
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /**
     * Initializes and pre-computes all lookup tables for the solver.
     * This heavy computation is run only once.
     */
    public static void initialize() {
        if (isInitialized) {
            return;
        }

        // --- Define Constants ---
        final int NUM_CORNERS = 8;
        final int NUM_EQUATORIAL_EDGES = 3;
        final int CORNER_PRUNING_DEPTH = 13;

        // A temporary array to hold permutations during calculation.
        int[] permutationArray = new int[NUM_CORNERS]; // Max size needed is 8

        /*	0	1
		 *	3	2
		 *
		 *	4	5
		 *	7	6
		 */

        // =================================================================================
        // Part 1: Corner Permutation Tables (8 corners)
        // =================================================================================

        // --- Build Corner Permutation Move Table ---
        for (int stateIndex = 0; stateIndex < NUM_CORNER_PERM_STATES; stateIndex ++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.set8Perm(permutationArray, NUM_CORNERS, stateIndex);
                switch (moveIndex) {
                    case 0: Utils.circle(permutationArray, 0, 3, 2, 1); break; // U-move (quarter turn)
                    case 1: Utils.swapTwoPairs(permutationArray, 1, 5, 2, 6); break; // R2-move (half turn)
                    case 2: Utils.swapTwoPairs(permutationArray, 2, 4, 3, 5); break; // F2-move (half turn)
                    case 3: Utils.circle(permutationArray, 4, 7, 6, 5); break;	// D-move (quarter turn)
                }
                // Repack the new permutation into a coordinate and store it.
                moveTableCornerPerm[stateIndex][moveIndex] = (char) Utils.get8Perm(permutationArray, NUM_CORNERS);
            }
        }

        // --- Build Corner Permutation Pruning Table ---
        for (int i = 1; i < NUM_CORNER_PERM_STATES; i++) {
            pruningTableCorner[i] = -1;
        }
        pruningTableCorner[SOLVED_STATE_COORD] =  0; // The solved state is at distance 0.
        Utils.populatePruningTable(pruningTableCorner, CORNER_PRUNING_DEPTH, moveTableCornerPerm, 3);
		/*	-	0
		 *	2	1
		 */
        // =================================================================================
        // Part 2: Edge Permutation Move Table (3 equatorial edges)
        // =================================================================================

        for (int stateIndex = 0; stateIndex < 6; stateIndex++) {
            for (int moveIndex = 0; moveIndex < 4; moveIndex++) {
                Utils.idxToPerm(permutationArray, stateIndex, NUM_EQUATORIAL_EDGES, false);
                // U and D moves do not affect these 3 edges.
                // R2 and F2 moves swap a pair of them.
                switch (moveIndex) {
                    case 1: Utils.swap(permutationArray, 0, 1); break;	// R2-move
                    case 2: Utils.swap(permutationArray, 1, 2); break;	// F2-move
                }
                moveTableEdgePerm[stateIndex][moveIndex] = (short) Utils.permToIdx(permutationArray, NUM_EQUATORIAL_EDGES, false);
            }
        }
        isInitialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public API">
    /**
     * Generates a random-state scramble for the Tower Cube.
     * <p>
     * This method creates a scramble by picking a random state (corner and edge
     * permutation) and then finding its optimal solution. The reversed solution is
     * returned as the scramble. It includes logic to ensure the scramble is not
     * trivially short.
     *
     * @return A string representing the scramble moves.
     */
    public static String scramble() {
        // --- Define Constants ---
        final int MIN_LENGTH_FOR_RETRY = 2;   // If shorter than this, generate a new random state.
        final int MIN_LENGTH_TO_ACCEPT = 4;   // If shorter than this, keep searching for a longer solution.

        // Ensure the solver's lookup tables are initialized.init();
        Random randomGenerator = new Random();

        // --- 1. Pick a Random Starting State ---
        int startCornerPermCoord = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
        int startEdgePermCoord = randomGenerator.nextInt(NUM_EDGE_PERM_STATES);

        // --- 2. Iterative Deepening Search ---
        // Search for the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(startCornerPermCoord, startEdgePermCoord, searchDepth, INITIAL_LAST_MOVE)) {
                // --- 3. Validate and Format Solution ---

                // If the solution is extremely short, discard it and start over.
                if (searchDepth < MIN_LENGTH_FOR_RETRY) {
                    return scramble();
                }

                // If the solution is still short, continue the search to find a
                // longer (non-optimal) but more interesting scramble.
                if (searchDepth < MIN_LENGTH_TO_ACCEPT) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode / 3;
                    int turnType = moveCode % 3;
                    scrambleBuilder
                            .append(MOVE_CHARS[faceIndex])
                            .append(turnSuffixInverse[turnType])
                            .append(" ");
                }
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
     * Tower Cube and returns an array representing the final colors of the 32 facelets.
     *
     * @param scramble The scramble string to apply (e.g., "U R2 D'").
     * @return An integer array representing the colors of the 32 facelets.
     */
    public static int[] getImageForScramble(String scramble) {
        // Reset the internal facelet model to the solved state.
        initializeColorImage();
        // Apply each move from the scramble string.
        for (String moveString : scramble.split(" ")) {
            if (!moveString.isEmpty()) {
                // Determine the index of the move (0=U, 1=R, 2=F, 3=D).
                int moveIndex = "UFRD".indexOf(moveString.charAt(0));

                // Apply the move once.
                applyImageMove(moveIndex);

                // Handle suffixes for 180-degree or counter-clockwise turns.
                // This check correctly identifies U/D as quarter turns that can have suffixes,
                // while R/F are half-turns (R2/F2) that do not.
                if (moveString.length() > 1 && TURNS_PER_MOVE[moveIndex] != 1) {
                    // Apply the move a second time for a '2' suffix.
                    applyImageMove(moveIndex);
                    // Apply it a third time for a "'" suffix (3 quarter turns = 1 counter-clockwise).
                    if (moveString.charAt(1) == '\'') {
                        applyImageMove(moveIndex);
                    }
                }
            }
            }
        // Return the final state of the facelet color array.
        return SOLVED_FACELET_IMAGE;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The recursive IDA* search function for the Tower Cube solver.
     * <p>
     * This method performs a depth-first search, using two separate pruning tables
     * (one for corners, one for edges) to efficiently find a solution path.
     *
     * @param cornerPerm     The current corner permutation coordinate.
     * @param edgePerm       The current edge permutation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastFace       The index of the last face turned.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int cornerPerm, int edgePerm, int depthRemaining, int lastFace) {
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_STATE_COORD  && edgePerm == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // If either the corner or edge sub-problem requires more moves than we have
        // left, this entire path is a dead end.
        if (pruningTableCorner[cornerPerm] > depthRemaining || PRUNING_TABLE_EDGE[edgePerm] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastFace) {

                int nextCornerPerm = cornerPerm;
                int nextEdgePerm = edgePerm;

                // This loop handles the different turn types.
                // For U/D moves, it tries U, U2, U'. For R/F moves, it only does R2, F2.
                for (int turn = 0; turn < TURNS_PER_MOVE[moveIndex]; turn++) {
                    // Get the next state for both coordinates from their respective move tables.
                    nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                    //if (faces[i] == 1) y = cpm[y][i];
                    nextEdgePerm = moveTableEdgePerm[nextEdgePerm][moveIndex];
                    // Make the recursive call for the new state.
                    if (search(nextCornerPerm, nextEdgePerm, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        // This packs the face and turn type into a single code.
                        int turnType = (TURNS_PER_MOVE[moveIndex] == 1) ? 1 : turn; // 1 means a '2' suffix
                        solutionSequence[depthRemaining] = moveIndex * 3 + turnType;
                        //sb.append(turn[i]).append(faces[i] == 1 ? "2" : suff[k]).append(' ');
                        return true;
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Visualization Logic">
    /** A constant array representing the color indices of a solved Tower Cube's 32 facelets. */

    private static void initializeColorImage() {
        SOLVED_FACELET_IMAGE = new int[] {
                      3, 3,
                      3, 3,
                5, 5, 4, 4, 2, 2, 1, 1,
                5, 5, 4, 4, 2, 2, 1, 1,
                5, 5, 4, 4, 2, 2, 1, 1,
                      0, 0,
                      0, 0
        };
    }

    /**
     * Applies the physical permutation of facelets for a single move.
     *
     * @param moveIndex The index of the move to apply (0=U, 1=R, 2=F, 3=D).
     */
    private static void applyImageMove(int moveIndex) {
        switch (moveIndex) {
            case 0:	//U
                Utils.circle(SOLVED_FACELET_IMAGE,  0,  2,  3,  1);
                Utils.circle(SOLVED_FACELET_IMAGE,  5,  7,  9, 11);
                Utils.circle(SOLVED_FACELET_IMAGE,  4,  6,  8, 10);
                break;
            case 1:	//R
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE,  1, 29,  3, 31);
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE,  8, 25,  9, 24);
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE, 16, 17, 15, 18);
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE,  7, 26, 23, 10);
                break;
            case 2:	//F
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE,  2, 29,  3, 28);
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE,  6, 23,  7, 22);
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE, 14, 15, 13, 16);
                Utils.swapTwoPairs(SOLVED_FACELET_IMAGE,  5, 24, 21,  8);
                break;
            case 3:	//D
                Utils.circle(SOLVED_FACELET_IMAGE, 28, 30, 31, 29);
                Utils.circle(SOLVED_FACELET_IMAGE, 27, 25, 23, 21);
                Utils.circle(SOLVED_FACELET_IMAGE, 26, 24, 22, 20);
                break;
        }
    }
    //</editor-fold>


}
