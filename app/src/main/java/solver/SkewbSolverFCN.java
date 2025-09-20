package solver;

import java.util.Arrays;
import java.util.Random;

/**
 * A solver for the Skewb puzzle, likely using a Fixed Center Navigation (FCN) strategy.
 * <p>
 * This class uses coordinate systems to track the state of the 6 centers and
 * 8 corners (split into subgroups). It pre-computes lookup tables to find optimal
 * solutions and can generate random-state scrambles and a visual representation.
 */
public class SkewbSolverFCN {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_CENTER_PERM_STATES = 360;
    private static final int NUM_CORNER_PERM_STATES = 36;
    private static final int NUM_CORNER_ORIENT_STATES = 2187;
    private static final int NUM_MOVES = 4;
    private static final int NUM_CENTERS = 6;
    private static final int MAX_SOLUTION_DEPTH = 12;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int NUM_TURN_TYPES = 2;
    private static final int INITIAL_LAST_MOVE = -1;

    // --- Lookup Tables ---
    // Move tables for center permutation, corner permutation, and corner orientation.
    private static short[][] moveTableCenterPerm = new short[NUM_CENTER_PERM_STATES][NUM_MOVES];
    private static short[][] moveTableCornerPerm = new short[NUM_CORNER_PERM_STATES][NUM_MOVES];
    private static short[][] moveTableCornerOrient = new short[NUM_CORNER_ORIENT_STATES][NUM_MOVES];

    // Pruning tables for centers and the combined corner state.
    private static byte[] pruningTableCenter = new byte[NUM_CENTER_PERM_STATES];
    private static byte[] pruningTableCorner = new byte[NUM_CORNER_ORIENT_STATES * NUM_CORNER_PERM_STATES];

    // --- Solver & State Variables ---
    private static Random randomGenerator = new Random();
    private static String[] MOVE_CHARS = {"R", "U", "L", "B"};
    private static String[] SUFFIXES = {"'", ""};
    private static int[] img = new int[30];

    //</editor-fold>

    //<editor-fold desc="Initialization">
    /* Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes and pre-computes all lookup tables for the Skewb solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for the
     * three coordinate systems (center perm, corner perm, corner orient) and then
     * builds the corresponding pruning tables.
     */

    private static void initializeTables() {
        // move tables
		/* center
		 * 		  0
		 *	4	1	2	3
		 * 		  5
		 */
        // A temporary array to hold piece states during calculation.
        int[] pieceState = new int[7]; // Max size needed is 7

        // =================================================================================
        // Part 1: Build Center Permutation Move Table (6 centers)
        // =================================================================================
        for (int stateIndex = 0; stateIndex < NUM_CENTER_PERM_STATES; stateIndex++)
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToPerm(pieceState, stateIndex, NUM_CENTERS, true);
                switch (moveIndex) {
                    case 0: Utils.circle(pieceState, 2, 5, 3); break;	// R-twist
                    case 1: Utils.circle(pieceState, 0, 3, 4); break;	// U-twist
                    case 2: Utils.circle(pieceState, 1, 4, 5); break;	// L-twist
                    case 3: Utils.circle(pieceState, 3, 5, 4); break;  // B-twist
                }
                moveTableCenterPerm[stateIndex][moveIndex] = (short) Utils.permToIdx(pieceState, NUM_CENTERS, true);
            }
		/* corner permutation
		 *     2-0
		 *  0	    1
		 *  	3
		 * 2-1	   2-2
		 *      2
		 */
		//corner permutation

        // =================================================================================
        // Part 2: Build Corner Permutation Move Table (combined 4-piece and 3-piece sets)
        // =================================================================================
        int[] perm4 = new int[4];
        int[] perm3 = new int[3];
        for (int perm4Index = 0; perm4Index < 12; perm4Index++)
            for (int perm3Index = 0; perm3Index < 3; perm3Index++) {
                for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                    // Unpack the two separate permutation coordinates.
                    Utils.idxToPerm(perm4, perm4Index, 4, true);
                    Utils.idxToPerm(perm3, perm3Index, 3, true);
                    switch (moveIndex) {
                        case 0: Utils.circle(perm4, 1, 2, 3); break;	// R-twist affects the 4-piece group
                        case 1: Utils.circle(perm4, 0, 1, 3); break;	// U-twist affects the 4-piece group
                        case 2: Utils.circle(perm4, 2, 0, 3); break;	// L-twist affects the 4-piece group
                        case 3: Utils.circle(perm3, 0, 2, 1); break;	// B-twist affects the 3-piece group
                    }
                    // Repack the two new coordinates into a single combined index.
                    int newPerm4Index = Utils.permToIdx(perm4, 4, true);
                    int newPerm3Index = Utils.permToIdx(perm3, 3, true);
                    moveTableCornerPerm[perm4Index * 3 + perm3Index][moveIndex] = (short) (newPerm4Index * 3 + newPerm3Index);
                }
            }
        //corner orientation
		/*
		 *		0
		 *	1		2
		 *		3
		 *	4		5
		 *		6
		 */
        // =================================================================================
        // Part 3: Build Corner Orientation Move Table (7 corners)
        // =================================================================================
        final int NUM_CORNERS_TO_ORIENT = 7;
        for (int stateIndex = 0; stateIndex < NUM_CORNER_ORIENT_STATES; stateIndex++) {
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                Utils.idxToOri(pieceState, stateIndex, NUM_CORNERS_TO_ORIENT, false);
                switch (moveIndex) {
                    case 0: // R-twist
                        Utils.circle(pieceState, 2, 6, 3);
                        pieceState[2] += 2; pieceState[3] += 2; pieceState[5]++; pieceState[6] += 2;
                        break;
                    case 1:
                        Utils.circle(pieceState, 1, 2, 3);
                        pieceState[0]++; pieceState[1] += 2; pieceState[2] += 2; pieceState[3] += 2;
                        break;
                    case 2:
                        Utils.circle(pieceState, 1, 3, 6);
                        pieceState[1] += 2; pieceState[3] += 2; pieceState[4]++; pieceState[6] += 2;
                        break;
                    case 3:
                        Utils.circle(pieceState, 0, 5, 4);
                        pieceState[0] += 2; pieceState[3]++; pieceState[4] += 2; pieceState[5] += 2;
                        break;
                }
                moveTableCornerOrient[stateIndex][moveIndex] = (short) Utils.oriToIdx(pieceState, NUM_CORNERS_TO_ORIENT, false);
            }
        }

        // distance table
        // =================================================================================
        // Part 4: Build Pruning Tables 📊
        // =================================================================================
        final int CENTER_PRUNING_DEPTH = 5;
        final int CORNER_PRUNING_DEPTH = 7;

        // --- Center Pruning Table ---
        Arrays.fill(pruningTableCenter, (byte) -1);
        pruningTableCenter[SOLVED_STATE_COORD] = 0;
        Utils.populatePruningTable(pruningTableCenter, CENTER_PRUNING_DEPTH, moveTableCenterPerm, NUM_TURN_TYPES);

        // --- Combined Corner Pruning Table ---
        Arrays.fill(pruningTableCorner, (byte) -1);
        pruningTableCorner[SOLVED_STATE_COORD] = 0;
        Utils.populatePruningTable(pruningTableCorner, CORNER_PRUNING_DEPTH, moveTableCornerOrient, moveTableCornerPerm, NUM_TURN_TYPES);
    }
    //</editor-fold>

    //<editor-fold desc="Public Scramble Generators">
    /**
     * Generates a standard random-state scramble.
     * <p>
     * This method creates a scramble by picking a random reachable state for all
     * pieces and then finding its optimal solution. The reversed solution becomes
     * the scramble. It includes logic to ensure the scramble is not trivially short.
     *
     * @return A string representing the scramble moves.
     */
    public static String generateWcaScramble() {
        // --- Define Constants ---
        final int MIN_LENGTH_FOR_RETRY = 2;
        final int MIN_LENGTH_TO_ACCEPT = 4;

        // --- 1. Pick a Valid Random Starting State ---
        int startCenterPerm;
        int startCornerPerm;
        int startCornerOrient;

        // Pick a random coordinate for the center permutation.
        startCenterPerm = randomGenerator.nextInt(NUM_CENTER_PERM_STATES);

        // Repeatedly pick random corner coordinates until a reachable state is found.
        do {
            startCornerPerm = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
            startCornerOrient = randomGenerator.nextInt(NUM_CORNER_ORIENT_STATES);
        } while (pruningTableCorner[startCornerOrient * NUM_CORNER_PERM_STATES  + startCornerPerm] < 0);

        // Array to store the solution sequence.
        int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++)

            // --- 3. Validate and Format Solution ---

            // If the solution is extremely short, discard it and start over.
            if (search(startCenterPerm, startCornerPerm, startCornerOrient, searchDepth, INITIAL_LAST_MOVE, solutionSequence)) {
                if (searchDepth < MIN_LENGTH_FOR_RETRY) {
                    return generateWcaScramble();
                }
                if (searchDepth < MIN_LENGTH_TO_ACCEPT) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode >> 1;
                    int turnType = moveCode & 1;
                    scrambleBuilder.append(MOVE_CHARS[faceIndex])
                                   .append(SUFFIXES[turnType])
                                   .append(" ");
                }
                return scrambleBuilder.toString();
            }
        // If no solution is found, return an error.
        return "error";
    }

    /**
     * Generates a WCA-compliant scramble with a minimum length.
     * <p>
     * This method ensures the generated scramble is not only valid but also meets
     * a minimum length, making it suitable for official competition-style scrambles.
     * It repeatedly calls the internal generator until a valid scramble is produced.
     *
     * @return A string representing the WCA-compliant scramble moves.
     */
    public static String scrambleWCA() {
        // Define the minimum number of moves for a valid WCA scramble for Skewb.
        final int WCA_MIN_SCRAMBLE_LENGTH = 7;

        String scrambleString;
        // Loop to ensure a valid, sufficiently long scramble is always returned.
        // This handles rare cases where the random state is too close to solved.
        do {
            scrambleString = generateWcaScramble(7);
        } while (scrambleString.equals("error"));
        return scrambleString;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Solver Logic">
    /**
     * The recursive IDA* search function.
     *
     * @param centerPerm The current center permutation coordinate.
     * @param cornerPerm The current corner permutation coordinate.
     * @param cornerOrient The current corner orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made.
     * @param sequence An array to store the solution path.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int centerPerm, int cornerPerm, int cornerOrient,
                                  int depthRemaining, int lastMove, int[] sequence) {
        // --- Base Case: If no moves are left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return centerPerm == SOLVED_STATE_COORD  &&
                    cornerOrient == SOLVED_STATE_COORD  &&
                    cornerPerm == SOLVED_STATE_COORD;
        }

        // --- Heuristic Pruning ---
        // Check both pruning tables. If either sub-problem requires more moves
        // than we have left, this entire path is a dead end.
        if (pruningTableCenter[centerPerm] > depthRemaining ||
                pruningTableCorner[cornerOrient * NUM_CORNER_PERM_STATES  + cornerPerm] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore next moves. ---
        final int RANDOM_MOVE_TRIGGER = -2;

        // --- Special Mode: Random first move for scramble generation ---
        if (lastMove == RANDOM_MOVE_TRIGGER) {
            int randomMoveCode = randomGenerator.nextInt(8); // 4 faces * 2 turn types
            int moveIndex = randomMoveCode / 2;
            int turnCount = randomMoveCode % 2;


            int nextCenterPerm = centerPerm;
            int nextCornerPerm = cornerPerm;
            int nextCornerOrient = cornerOrient;

            // Apply the random move sequence.
            for (int i = 0; i <= turnCount; i++) {
                nextCenterPerm = moveTableCenterPerm[nextCenterPerm][moveIndex];
                nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                nextCornerOrient = moveTableCornerOrient[nextCornerOrient][moveIndex];
            }
            if (search(nextCenterPerm, nextCornerPerm, nextCornerOrient, depthRemaining-1, moveIndex, sequence)) {
                sequence[depthRemaining] = moveIndex << 1 | randomMoveCode;
                return true;
            }
        } else {
            // --- Standard Mode: Exhaustive search for solving ---
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                if (moveIndex != lastMove) {
                    int nextCenterPerm = centerPerm;
                    int nextCornerPerm = cornerPerm;
                    int nextCornerOrient = cornerOrient;

                    // Try both turn types for the current face (e.g., R and R').
                    for (int turnType = 0; turnType < 2; turnType++) {
                        nextCenterPerm = moveTableCenterPerm[nextCenterPerm][moveIndex];
                        nextCornerPerm = moveTableCornerPerm[nextCornerPerm][moveIndex];
                        nextCornerOrient = moveTableCornerOrient[nextCornerOrient][moveIndex];
                        if (search(nextCenterPerm, nextCornerPerm, nextCornerOrient, depthRemaining-1, moveIndex, sequence)) {
                            sequence[depthRemaining] = moveIndex << 1 | turnType;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }


    /**
     * The internal generator for a W-CA-compliant scramble with a minimum length.
     * <p>
     * This method creates a high-quality scramble by finding a solution path from a
     * random state and ensuring it has a fixed length of 11 moves, as required
     * by WCA regulations for Skewb.
     *
     * @param minLength The minimum acceptable length for the scramble.
     * @return A string representing the WCA-compliant scramble, or "error".
     */
    private static String generateWcaScramble(int minLength) {
        // --- Define Constants ---
        final int WCA_SCRAMBLE_LENGTH = 11;
        final int RANDOM_MOVE_TRIGGER = -2;

        // --- 1. Pick a Valid Random Starting State ---
        int startCenterPerm, startCornerPerm, startCornerOrient;

        // Pick a random coordinate for the center permutation.
        startCenterPerm = randomGenerator.nextInt(NUM_CENTER_PERM_STATES);

        // Repeatedly pick random corner coordinates until a reachable state is found.
        do {
            startCornerPerm = randomGenerator.nextInt(NUM_CORNER_PERM_STATES);
            startCornerOrient = randomGenerator.nextInt(NUM_CORNER_ORIENT_STATES);
        } while (pruningTableCorner[startCornerOrient * NUM_CORNER_PERM_STATES  + startCornerPerm] < 0);

        // Array to store the solution sequence.
        int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
        // --- 2. Iterative Deepening Search ---
        // Find the shortest solution from the random state.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++)
            if (search(startCenterPerm, startCornerPerm, startCornerOrient, searchDepth, -1, solutionSequence)) {

                // --- 3. Validate and Finalize the Scramble ---

                // If the optimal solution is shorter than the required minimum, it's an invalid scramble.
                if (searchDepth < minLength) {
                    return "error";
                }
                if (searchDepth < 11) {
                    //sol = new StringBuilder();
                    search(startCenterPerm, startCornerPerm, startCornerOrient, WCA_SCRAMBLE_LENGTH, RANDOM_MOVE_TRIGGER, solutionSequence);
                }
                StringBuilder sol = new StringBuilder();
                int last = -1;
                for (int i = 1; i <= 11; i++) {
                    if (last == solutionSequence[i] / 2) return "error";
                    sol.append(MOVE_CHARS[solutionSequence[i] >> 1]).append(SUFFIXES[solutionSequence[i] & 1]).append(" ");
                    last = solutionSequence[i] >> 1;
                }
                return sol.toString();
            }
        return "error";
    }
    //</editor-fold>

    //<editor-fold desc="Visualization">
    /**
     * Generates a visual image of the puzzle state from a scramble string.
     *
     * @param scramble The scramble string to apply.
     * @return An integer array representing the colors of the 30 facelets.
     */
    public static int[] getImage(String scramble) {
        initColor();
        String[] s = scramble.split(" ");
        for (String string : s)
            if (string.length() > 0) {
                int mov = "RULB".indexOf(string.charAt(0));
                if (mov < 0) return null;
                move(mov);
                if (string.length() > 1) {
                    if (string.charAt(1) != '\'') return null;
                    move(mov);
                }
            }
        return img;
    }

    /** Initializes the facelet image array to a solved state. */

    /*
     *				0		1
     *					2
     *				3		4
     *	5		6	10		11	15		16	20		21
     *		7			12			17			22
     *	8		9	13		14	18		19	23		24
     *				25		26
     *					27
     *				28		29
     */
    private static void initColor() {
        for (int i = 0; i < 5; i++)
            for (int j = 0; j < 6; j++) img[j * 5 + i] = j;
    }

    /** Applies the physical permutation of facelets for a single move. */
    private static void move(int turn) {
        switch (turn) {
            case 0:	//R
                Utils.circle(img, 17, 27, 22);
                Utils.circle(img, 19, 29, 23);
                Utils.circle(img,  1, 14,  8);
                Utils.circle(img, 20, 18, 28);
                Utils.circle(img, 16, 26, 24);
                break;
            case 1:	//U
                Utils.circle(img,  2, 22,  7);
                Utils.circle(img,  0, 21,  5);
                Utils.circle(img,  3, 20,  8);
                Utils.circle(img, 10, 16, 28);
                Utils.circle(img,  6,  1, 24);
                break;
            case 2:	//L
                Utils.circle(img, 12,  7, 27);
                Utils.circle(img, 13,  9, 25);
                Utils.circle(img,  3, 24, 18);
                Utils.circle(img, 10,  8, 26);
                Utils.circle(img,  6, 28, 14);
                break;
            case 3:	//B
                Utils.circle(img, 22, 27,  7);
                Utils.circle(img, 24, 28,  8);
                Utils.circle(img,  0, 19, 13);
                Utils.circle(img,  5, 23, 25);
                Utils.circle(img, 21, 29,  9);
                break;
        }
    }
    //</editor-fold>


}
