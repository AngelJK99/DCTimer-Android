package solver;

import java.util.Random;

import static solver.Utils.turnSuffixInverse;

/**
 * A solver and visualizer for a Domino Cube (3x3x2 cuboid).
 * <p>
 * This class uses coordinate systems for the permutation of the 8 corners and
 * 8 edges. It pre-computes lookup tables to find optimal solutions using the
 * puzzle's specific move set. It also includes methods to generate scrambles
 * and a visual representation of the cube's state.
 */
public class DominoSolver {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_PERMUTATION_STATES = 40320; // 8!
    private static final int NUM_MOVES = 5;                  // U, L2, R2, F2, B2
    private static final int MAX_SOLUTION_DEPTH = 20;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int NUM_TOTAL_FACELETS = 42;
    private static final int INITIAL_LAST_MOVE = -1;

    // --- Lookup Tables ---
    // Move tables for corner and edge permutations..
    private static char[][] moveTableCornerPerm = new char[NUM_PERMUTATION_STATES][NUM_MOVES];
    private static char[][] moveTableEdgePerm = new char[NUM_PERMUTATION_STATES][NUM_MOVES];

    // Pruning tables for corner and edge permutations.
    private static byte[] pruningTableCorner = new byte[NUM_PERMUTATION_STATES];
    private static byte[] pruningTableEdge = new byte[NUM_PERMUTATION_STATES];

    // --- Solver & State Variables ---
    // Defines the number of repetitions for each move type (U is quarter turn, others are half turns).
    private static byte[] TURNS_PER_MOVE = {3, 1, 1, 1, 1};
    private static String[] MOVE_CHARS = {"U", "L", "R", "F", "B"};
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
    private static int[] faceletImage = new int[NUM_TOTAL_FACELETS];
    private static boolean isInitialized = false;
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /** Static initializer to generate all tables when the class is loaded. */
    static {
        initialize();
    }
    /**
     * Initializes and pre-computes all lookup tables for the Domino Cube solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for the
     * corner and edge permutations, and then builds the corresponding pruning tables
     * using a Breadth-First Search.
     */
    private static void initialize() {
        // A guard to ensure this computation is only run once.
        if (isInitialized){
            return;
        }
        long t = System.currentTimeMillis();

        // --- Define Constants ---
        final int NUM_PIECES_TO_TRACK = 8;
        final int CORNER_PRUNING_DEPTH = 13;
        final int EDGE_PRUNING_DEPTH = 11;
        final int NUM_QUARTER_TURN_MOVES = 3; // U, U2, U'

        // Temporary arrays to hold permutations during calculation.
        int[] currentPermutation = new int[NUM_PIECES_TO_TRACK];
        int[] basePermutation = new int[NUM_PIECES_TO_TRACK];

        // =================================================================================
        // Part 1: Build Move Tables ⚙️
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_PERMUTATION_STATES; stateIndex++) {
            // Unpack the coordinate into a base permutation.
            Utils.set8Perm(basePermutation, NUM_PIECES_TO_TRACK, stateIndex);

            for (int moveIndex = 0; moveIndex < 5; moveIndex++) {
                // --- Corner Permutation Move Table ---
                System.arraycopy(basePermutation, 0, currentPermutation, 0, 8);
                switch (moveIndex) {
                    case 0: Utils.circle(currentPermutation, 0, 3, 2, 1); break;	//U
                    case 1: Utils.swapTwoPairs(currentPermutation, 0, 7, 3, 4); break;	//L
                    case 2: Utils.swapTwoPairs(currentPermutation, 1, 6, 2, 5); break;	//R
                    case 3: Utils.swapTwoPairs(currentPermutation, 3, 6, 2, 7); break;	//F
                    case 4: Utils.swapTwoPairs(currentPermutation, 0, 5, 1, 4); break;	//B
                }
                moveTableCornerPerm[stateIndex][moveIndex] = (char) Utils.get8Perm(currentPermutation, 8);
                System.arraycopy(basePermutation, 0, currentPermutation, 0, 8);
                switch (moveIndex) {
                    case 0: Utils.circle(currentPermutation, 0, 3, 2, 1); break;	//U
                    case 1: Utils.swap(currentPermutation, 3, 7); break;	//L
                    case 2: Utils.swap(currentPermutation, 1, 5); break;	//R
                    case 3: Utils.swap(currentPermutation, 2, 6); break;	//F
                    case 4: Utils.swap(currentPermutation, 0, 4); break;	//B
                }
                moveTableEdgePerm[stateIndex][moveIndex] = (char) Utils.get8Perm(currentPermutation, 8);
            }
        }

        // =================================================================================
        // Part 2: Build Pruning Tables 📊
        // =================================================================================

        // Initialize both pruning tables with an "unvisited" marker.
        for (int i = 1; i < NUM_PERMUTATION_STATES; i++)  {
            pruningTableCorner[i] = pruningTableEdge[i] = UNVISITED_STATE;
        }
        // The solved state (coordinate 0) has a distance of 0.
        pruningTableCorner[0] = pruningTableEdge[0] = SOLVED_STATE_DISTANCE;

        // Populate the tables using a Breadth-First Search.
        Utils.populatePruningTable(pruningTableCorner, CORNER_PRUNING_DEPTH, moveTableCornerPerm, NUM_QUARTER_TURN_MOVES);
        Utils.populatePruningTable(pruningTableEdge, EDGE_PRUNING_DEPTH, moveTableEdgePerm, NUM_QUARTER_TURN_MOVES);
        t = System.currentTimeMillis() - t;
        //Log.w("dct", "init " + t + "ms");
        isInitialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public API">
    /**
     * Generates a random-state scramble for the Domino Cube.
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

        // Ensure the solver's lookup tables are initialized.
        initialize();

        Random randomGenerator = new Random();

        // --- 1. Pick a Random Starting State ---
        int cp = randomGenerator.nextInt(NUM_PERMUTATION_STATES);
        int ep = randomGenerator.nextInt(NUM_PERMUTATION_STATES);

        // --- 2. Iterative Deepening Search ---
        // Search for the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(cp, ep, searchDepth, INITIAL_LAST_MOVE)) {
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
                    scrambleBuilder.append(MOVE_CHARS[faceIndex])
                                   .append(turnSuffixInverse[turnType])
                                   .append(' ');
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
     * Domino Cube and returns an array representing the final colors of the 42 facelets.
     *
     * @param scramble The scramble string to apply (e.g., "U R2 D'").
     * @return An integer array representing the colors of the 42 facelets.
     */
    public static int[] image(String scramble) {
        // --- Define Constants ---
        final String ALL_MOVE_CHARS = "UDLRFB";
        // On a Domino cube, only U and D are quarter turns.
        final int NUM_QUARTER_TURN_MOVES = 2;

        // Reset the internal facelet model to the solved state
        initializeFaceletImage();
        String[] scrambleMoves = scramble.split(" ");
        // Apply each move from the scramble string.
        for (String scrambleMove : scrambleMoves) {
            if (!scrambleMove.isEmpty()) {
                // Determine the index of the move (0=U, 1=D, 2=L, 3=R, 4=F, 5=B).
                int mov = ALL_MOVE_CHARS.indexOf(scrambleMove.charAt(0));

                // Apply the move once.
                applyImageMove(mov);


                // Handle suffixes for 180-degree or counter-clockwise turns.
                // This logic correctly applies only to U and D moves.
                if (scrambleMove.length() > 1 && mov < 2) {
                    // Apply the move a second time for a '2' suffix.
                    applyImageMove(mov);
                    // Apply it a third time for a "'" suffix (3 quarter turns = 1 counter-clockwise).
                    if (scrambleMove.charAt(1) == '\'') {
                        applyImageMove(mov);
                    }
                }
            }
        }
        // Return the final state of the facelet color array.
        return faceletImage;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The recursive IDA* search function.
     *
     * @param cornerPerm The current corner permutation coordinate.
     * @param edgePerm   The current edge permutation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastFace   The index of the last face turned.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int cornerPerm, int edgePerm, int depthRemaining, int lastFace) {
        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_STATE_COORD && edgePerm == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // If either the corner or edge sub-problem requires more moves than we have
        // left, this entire path is a dead end.
        if (pruningTableCorner[cornerPerm] > depthRemaining || pruningTableEdge[edgePerm] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastFace) {

                int nextCornerPerm = cornerPerm;
                int nextEdgePerm = edgePerm;

                // This loop handles the different turn types. For the U move, it tries U, U2, U'.
                // For all other moves (L2, R2, etc.), it only performs one turn.
                for (int turn = 0; turn < TURNS_PER_MOVE[moveIndex]; turn++) {
                    // Get the next state for both coordinates from their respective move tables.
                    nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                    nextEdgePerm = moveTableEdgePerm[nextEdgePerm][moveIndex];

                    // Make the recursive call for the new state.
                    if (search(nextCornerPerm, nextEdgePerm, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        // This packs the face and turn type into a single code.
                        int turnType = (moveIndex < 1) ? turn : 1; // 1 means a '2' suffix for half-turns
                        solutionSequence[depthRemaining] = moveIndex * 3 + turnType;
                        //sb.append(turn[i]+(i<1?suff[k]:"2")+" ");
                        return true;
                    }
                }
            }
        }
        return false;
    }


    //<editor-fold desc="Internal Visualization Logic">
    /** Initializes the facelet image array to a solved state. */
    private static void initializeFaceletImage() {
        faceletImage = new int[] {
                     3, 3, 3,
                     3, 3, 3,
                     3, 3, 3,
            5, 5, 5, 4, 4, 4, 2, 2, 2, 1, 1, 1,
            5, 5, 5, 4, 4, 4, 2, 2, 2, 1, 1, 1,
                     0, 0, 0,
                     0, 0, 0,
                     0, 0, 0
        };
    }

    /**
     * Applies the physical permutation of facelets for a single move to the image array.
     *
     * @param moveIndex The index of the move to apply (0=U, 1=D, 2=L, 3=R, 4=F, 5=B).
     */
    private static void applyImageMove(int moveIndex) {
        // --- Define Constants for Move Types ---
        final int U_MOVE = 0;
        final int D_MOVE = 1;
        final int L_MOVE = 2;
        final int R_MOVE = 3;
        final int F_MOVE = 4;
        final int B_MOVE = 5;

        switch (moveIndex) {
            case U_MOVE: // U-move (quarter turn)
                Utils.circle(faceletImage,  0,  6,  8,  2);
                Utils.circle(faceletImage,  1,  3,  7,  5);
                Utils.circle(faceletImage,  9, 12, 15, 18);
                Utils.circle(faceletImage, 10, 13, 16, 19);
                Utils.circle(faceletImage, 11, 14, 17, 20);
                break;
            case D_MOVE: // D-move (quarter turn)
                Utils.circle(faceletImage, 33, 39, 41, 35);
                Utils.circle(faceletImage, 34, 36, 40, 38);
                Utils.circle(faceletImage, 30, 27, 24, 21);
                Utils.circle(faceletImage, 31, 28, 25, 22);
                Utils.circle(faceletImage, 32, 29, 26, 23);
                break;
            case L_MOVE: // L2-move (half turn)
                Utils.swapTwoPairs(faceletImage,  9, 23, 11, 21);
                Utils.swapTwoPairs(faceletImage, 10, 22,  3, 36);
                Utils.swapTwoPairs(faceletImage,  0, 33,  6, 39);
                Utils.swapTwoPairs(faceletImage, 20, 24, 32, 12);
                break;
            case R_MOVE: // R2-move (half turn)
                Utils.swapTwoPairs(faceletImage, 15, 29, 17, 27);
                Utils.swapTwoPairs(faceletImage, 16, 28,  5, 38);
                Utils.swapTwoPairs(faceletImage,  8, 41,  2, 35);
                Utils.swapTwoPairs(faceletImage, 14, 30, 26, 18);
                break;
            case F_MOVE: // F2-move (half turn)
                Utils.swapTwoPairs(faceletImage, 12, 26, 14, 24);
                Utils.swapTwoPairs(faceletImage, 13, 25,  7, 34);
                Utils.swapTwoPairs(faceletImage,  6, 35,  8, 33);
                Utils.swapTwoPairs(faceletImage, 11, 27, 15, 23);
                break;
            case B_MOVE: // B2-move (half turn)
                Utils.swapTwoPairs(faceletImage, 18, 32, 20, 30);
                Utils.swapTwoPairs(faceletImage, 19, 31,  1, 40);
                Utils.swapTwoPairs(faceletImage,  2, 39,  0, 41);
                Utils.swapTwoPairs(faceletImage, 17, 21, 29,  9);
                break;
        }
    }
    //</editor-fold>


}
