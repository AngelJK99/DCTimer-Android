package solver;

import java.util.Random;

/**
 * A solver and visualizer for the Pyraminx puzzle.
 * <p>
 * This class uses coordinate systems for the permutation and orientation of the
 * 6 main edges and the orientation of the 4 corners. It pre-computes lookup
 * tables to find optimal solutions for the main body of the puzzle. It also
 * includes methods for generating scrambles and a visual representation.
 */
public class Pyraminx {
    //<editor-fold desc="Constants & Class Variables">
    // --- Constants ---
    private static final int NUM_EDGE_PERM_STATES = 360;    // (6! / 2) Part of C(10,4)*4!/2
    private static final int NUM_CORNER_TWIST_STATES = 81;  // 3^4
    private static final int NUM_EDGE_FLIP_STATES = 32;     // 2^5
    private static final int NUM_COMBINED_TWIST_FLIP_STATES = 2592; // 81 * 32
    private static final int NUM_TRACKED_EDGES = 6;
    private static final int NUM_TRACKED_CORNERS = 4;

    private static final int NUM_MOVES = 4;                 // L, R, B, U
    private static final int NUM_TURN_TYPES = 2;
    private static final int MAX_SOLUTION_DEPTH = 12;
    private static final int SOLVED_STATE_COORD = 0;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int UNVISITED_STATE = -1;
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int NUM_TIPS = 4;
    private static final int NUM_TOTAL_FACELETS = 91;


    // --- Lookup Tables ---
    // Pruning tables for edge permutation and the combined twist/flip state.
    private static byte[] pruningTableEdgePerm = new byte[NUM_EDGE_PERM_STATES];	// pruning table for edge permutation
    private static byte[] pruningTableTwistFlip = new byte[NUM_COMBINED_TWIST_FLIP_STATES ];	// pruning table for edge orientation+twist

    // Move tables for the various coordinate systems.
    private static short[][] moveTableEdgePerm = new short[NUM_EDGE_PERM_STATES ][NUM_MOVES ];	// transition table for edge permutation
    private static short[][] moveTableCornerTwist = new short[NUM_CORNER_TWIST_STATES][NUM_MOVES ];	// transition table for corner orientation
    private static short[][] moveTableEdgeFlip = new short[NUM_EDGE_FLIP_STATES ][NUM_MOVES ];	// transition table for edge orientation

    // --- Solver & State Variables ---
    private static String[] MOVE_CHARS = {"L", "R", "B", "U"};
    private static String[] SUFFIXES = {"'", ""};
    private static String[] TIP_CHARS = {"l", "r", "b", "u"};
    //private static int[] seq = new int[12];
    private static int[] faceletImage = new int[NUM_TOTAL_FACELETS];
    private static Random randomGenerator = new Random();
    private static final int[] SOLVED_COLOR_MAP = new int[] {
            1, 1, 1, 1, 1, 0, 2, 0, 3, 3, 3, 3, 3,
            0, 1, 1, 1, 0, 2, 2, 2, 0, 3, 3, 3, 0,
            0, 0, 1, 0, 2, 2, 2, 2, 2, 0, 3, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 4, 4, 4, 4, 4, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 4, 4, 4, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0
    };

    //</editor-fold>

    //<editor-fold desc="Initialization">
    // Static initializer to generate all tables when the class is loaded. */
    static {
        initializeTables();
    }

    /**
     * Initializes and pre-computes all lookup tables for the Pyraminx solver.
     * <p>
     * This heavy computation is run only once. It generates the move tables for
     * the three coordinate systems (edge perm, corner twist, edge flip) and then
     * uses them to generate the corresponding pruning tables.
     */
    private static void initializeTables() {
        int c, q, l, moveIndex, stateIndex, r;
        //calculate solving arrays
        //first permutation
        //initialise arrays

        // =================================================================================
        // Part 1: Generate Move Tables ⚙️
        // =================================================================================

        // --- Build Edge Permutation Move Table ---
        for (stateIndex = 0; stateIndex < NUM_EDGE_PERM_STATES; stateIndex++) {
            for (moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                moveTableEdgePerm[stateIndex][moveIndex] = (short) calculateNewEdgePermCoord(stateIndex, moveIndex);
            }
        }

        // --- Build Corner Twist and Edge Flip Move Tables ---
        for (stateIndex = 0; stateIndex < NUM_CORNER_TWIST_STATES; stateIndex++) {
            for (moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                moveTableCornerTwist[stateIndex][moveIndex] = (short) calculateNewCornerTwistCoord(stateIndex, moveIndex);
                // The edge flip table is smaller, so we build it in the same loop.
                if (stateIndex < NUM_EDGE_FLIP_STATES)
                    moveTableEdgeFlip[stateIndex][moveIndex] = (short) calculateNewEdgeFlipCoord(stateIndex, moveIndex);
            }
        }

        // =================================================================================
        // Part 2: Generate Pruning Tables 📊
        // =================================================================================

        // --- Build Edge Permutation Pruning Table ---
        final int EDGE_PERM_PRUNING_DEPTH = 5;

        for (int i = 0; i < NUM_EDGE_PERM_STATES; i++) {
            pruningTableEdgePerm[i] = UNVISITED_STATE; // -1 means unvisited
        }
        pruningTableEdgePerm[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE; // Solved state has distance 0.
        Utils.populatePruningTable(pruningTableEdgePerm, EDGE_PERM_PRUNING_DEPTH, moveTableEdgePerm, NUM_TURN_TYPES);

        // --- Build Combined Twist/Flip Pruning Table ---
        final int TWIST_FLIP_PRUNING_DEPTH = 7;
        for (int i = 0; i < NUM_COMBINED_TWIST_FLIP_STATES; i++) {
            pruningTableTwistFlip[i] = -1;
        }
        pruningTableTwistFlip[SOLVED_STATE_COORD] = SOLVED_STATE_DISTANCE;
        // This table combines two coordinate systems (corner twist and edge flip).
        Utils.populatePruningTable(pruningTableTwistFlip, TWIST_FLIP_PRUNING_DEPTH, moveTableCornerTwist, moveTableEdgeFlip, NUM_TURN_TYPES);
    }
    //</editor-fold>

    //<editor-fold desc="Public Scramble Generators">
    /**
     * Generates a standard random-state scramble.
     * <p>
     * This method creates a scramble by picking a random state for the main puzzle pieces
     * (excluding the tips) and then solving it. The reversed solution becomes the scramble.
     *
     * @return A string representing the scramble moves, including random tip turns.
     */
    public static String scramble() {
        String scrambleString;

        // This loop ensures that a valid scramble is always returned, retrying if the
        // internal solver fails for a particular random state.
        do {
            // Pick a random coordinate for the combined corner twist and edge flip state.
            int randomTwistFlipCoord = randomGenerator.nextInt(NUM_COMBINED_TWIST_FLIP_STATES);

            // Pick a random coordinate for the edge permutation state.
            int randomPermCoord = randomGenerator.nextInt(NUM_EDGE_PERM_STATES);

            // Call the internal solver with the random coordinates to generate the scramble.
            scrambleString = scramble(randomPermCoord, randomTwistFlipCoord);
        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a WCA-compliant scramble with a minimum length.
     * <p>
     * This method ensures the generated scramble is not only valid but also meets
     * a minimum length, making it suitable for official competition-style scrambles.
     * It repeatedly calls the internal generator until a valid scramble is produced.
     *
     * @return A string representing the WCA compliant scramble moves.
     */
    public static String scrambleWCA() {
        // Define the minimum number of moves for a valid WCA scramble for the Pyraminx.
        final int WCA_MIN_SCRAMBLE_LENGTH = 6;
        String scrambleString;

        // Loop to ensure a valid, sufficiently long scramble is always returned.
        // This handles rare cases where the random state is too close to solved.
        do {
            scrambleString = scramble(WCA_MIN_SCRAMBLE_LENGTH);
        } while (scrambleString.equals("error"));

        return scrambleString;
    }

    /**
     * Generates a scramble for the "Last 4 Edges" (L4E) training case.
     * <p>
     * This method directly constructs a random state where the corners and two
     * edges are solved, leaving a random L4E case. It then solves this state
     * to produce a valid scramble for training these specific algorithms.
     *
     * @return A string representing the scramble moves for an L4E case.
     */
    public static String scrambleL4E() {
        String scrambleString;

        // Loop to ensure a valid, solvable scramble is always returned.
        do {
            // --- 1. Construct a Random L4E State ---

            // Temporary arrays to build the permutation and flip states.
            int[] permutationArray = {0, 1, 2, 3, 4, 5}; // Start with a solved-like state
            int[] flipArray = new int[NUM_TRACKED_EDGES];

            // Generate a random permutation for 4 of the 6 edges.
            Utils.idxToPerm(permutationArray, randomGenerator.nextInt(12), 4, true);

            // Generate a random flip state for 4 of the 6 edges.
            Utils.idxToFlip(flipArray, randomGenerator.nextInt(8), 4, true);

            // --- 2. Convert the State to Coordinates ---

            // Convert the constructed permutation and flip arrays into their integer coordinates.
            int permCoord = Utils.permToIdx(permutationArray, NUM_TRACKED_EDGES, true);
            int flipCoord = Utils.flipToIdx(flipArray, NUM_TRACKED_EDGES, true);

            // Combine the flip coordinate with a random corner twist coordinate to get the final
            // twist/flip coordinate. 864 is the number of states for the 3 other corners.
            int twistFlipCoord = randomGenerator.nextInt(3) * 864 + flipCoord;

            // --- 3. Solve from that State to Create the Scramble ---
            scrambleString = scramble(permCoord, twistFlipCoord);

        } while (scrambleString.equals("error"));
        return scrambleString;
    }
    //</editor-fold>


    //<editor-fold desc="Internal Solver Logic">
    /**
     * The main internal solver that finds and formats a scramble from a given coordinate state.
     * <p>
     * This method orchestrates the solving process. It uses an iterative deepening search
     * to find an optimal solution, validates that the solution is not trivially short,
     * and then formats it into a full scramble string including random tip turns.
     *
     * @param startPermCoord The starting edge permutation coordinate.
     * @param startTwistFlipCoord The starting combined coordinate for corner twists and edge flips.
     * @return A string of moves representing the scramble, or "error" if none is found.
     */
    private static String scramble(int startPermCoord, int startTwistFlipCoord) {
        // --- Define Constants ---
        final int MIN_LENGTH_FOR_ERROR = 2;
        final int MIN_LENGTH_TO_ACCEPT = 5; // Ignore optimal solutions shorter than this.

        // Array to store the solution sequence found by the recursive search
        int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

        // --- Iterative Deepening Search ---
        // Search for a solution, starting with a depth of 0 and increasing.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++)
            if (search(startPermCoord, startTwistFlipCoord, searchDepth, INITIAL_LAST_MOVE, solutionSequence)) {

                // --- Solution Found: Validate and Format ---
                if (searchDepth < MIN_LENGTH_FOR_ERROR) {
                    return "error";
                }
                // If the solution is valid but still short, continue searching for a longer one.
                if (searchDepth < MIN_LENGTH_TO_ACCEPT) {
                    continue;
                }

                // If a suitable solution is found, format it into a scramble string.
                StringBuilder scrambleBuilder = new StringBuilder();
                for (int i = 1; i <= searchDepth; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode >> 1;  // Get the face (L,R,B,U)
                    int turnType = moveCode & 1;   // Get the suffix (', default)
                    scrambleBuilder.append(MOVE_CHARS[faceIndex])
                                   .append(SUFFIXES[turnType])
                                   .append(" ");
                }

                // 2. Append random turns for the 4 trivial tips.
                for (int i = 0; i < NUM_TIPS; i++) {
                    int randomTipTurn = randomGenerator.nextInt(3);
                    if (randomTipTurn < 2)
                        scrambleBuilder.append(TIP_CHARS[i])
                                       .append(SUFFIXES[randomTipTurn])
                                       .append(" ");
                }
                return scrambleBuilder.toString();
            }
        return "error";
    }

    /**
     * The core generator for a WCA-compliant scramble with a minimum length.
     * <p>
     * This method creates a high-quality scramble by finding an 11-move solution
     * path from a random state and appending random tip turns. It includes checks
     * to ensure the final scramble meets length and quality requirements.
     *
     * @param minLength The minimum total length for the scramble (body + tips).
     * @return A string representing the WCA-compliant scramble, or "error".
     */
    private static String scramble(int minLength) {
        // --- Define Constants ---
        final int NUM_TIPS = 4;
        final int WCA_BODY_LENGTH = 11;
        final int RANDOM_MOVE_TRIGGER = -2; // Special value for the recursive search

        // --- 1. Generate Random Tip Turns ---
        int tipTurnCount = 0;
        int[] tipTurns = new int[NUM_TIPS];
        // Randomly decide the turn for each of the 4 tips (', default, or none).
        for (int i = 0; i < NUM_TIPS ; i++) {
            tipTurns[i] = randomGenerator.nextInt(3);
            if (tipTurns[i] < 2) {  // A turn of ' or default counts towards the length.
                tipTurnCount++;
            }
        }

        // --- 2. Find a Solvable Random State for the Main Body ---
        int startTwistFlipCoord = randomGenerator.nextInt(NUM_COMBINED_TWIST_FLIP_STATES);
        int startPermCoord = randomGenerator.nextInt(NUM_EDGE_PERM_STATES);
        int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

        // --- 3. Find an 11-Move Solution Path ---
        // Use an iterative deepening search to find the shortest path first.
        for (int searchDepth = 0; searchDepth < MAX_SOLUTION_DEPTH; searchDepth++) {
            if (search(startPermCoord, startTwistFlipCoord, searchDepth, INITIAL_LAST_MOVE, solutionSequence)) {
                // --- 4. Validate and Format the Scramble ---

                // Check if the total length (optimal body + tips) meets the minimum requirement.
                if (searchDepth + tipTurnCount < minLength) return "error";

                // If the optimal solution is shorter than the target, re-run the search
                // to find a valid 11-move sequence (for consistency and complexity).
                if (searchDepth < WCA_BODY_LENGTH) {
                    //sol = new StringBuilder();
                    search(startPermCoord, startTwistFlipCoord, WCA_BODY_LENGTH, RANDOM_MOVE_TRIGGER, solutionSequence);
                }
                StringBuilder scrambleBuilder = new StringBuilder();
                int lastFace = -1;

                // 4a. Build the 11-move main body of the scramble.
                for (int i = 1; i <= WCA_BODY_LENGTH; i++) {
                    int moveCode = solutionSequence[i];
                    int faceIndex = moveCode >> 1;

                    // WCA Quality Check: Reject scrambles with redundant consecutive moves (e.g., L L').
                    if (lastFace == faceIndex) {
                        return "error";
                    }
                    scrambleBuilder.append(MOVE_CHARS[solutionSequence[i] >> 1])
                                   .append(SUFFIXES[solutionSequence[i] & 1])
                                   .append(" ");
                    lastFace = faceIndex;
                }

                // 4b. Append the pre-determined tip turns.
                for (int i = 0; i < NUM_TIPS; i++) {
                    if (tipTurns[i] < 2) { // 0=' or 1=default
                        scrambleBuilder.append(TIP_CHARS[i])
                                       .append(SUFFIXES[tipTurns[i]])
                                        .append(' ');
                    }
                }
                return scrambleBuilder.toString();
            }
        }
        return "error"; // Should not be reached.
    }



    /**
     * The recursive IDA* search function for the Pyraminx solver.
     * <p>
     * This method performs a depth-first search up to a specified depth. It uses
     * pruning tables for both edge permutation and the combined twist/flip state.
     * It also includes a special mode for generating random scrambles.
     *
     * @param permCoord The current edge permutation coordinate.
     * @param twistFlipCoord The current combined coordinate for corner twists and edge flips.
     * @param depthRemaining The number of moves left to reach the solved state.
     * @param lastMove The index of the last move made. A special value of -2
     * triggers a randomized first move for scramble generation.
     * @param sequence An array to store the solution path.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int permCoord, int twistFlipCoord, int depthRemaining, int lastMove, int[] sequence) {
        //searches for solution, from position p|t, in l moves exactly. last move was lm, current depth=d
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return permCoord == 0 && twistFlipCoord == 0;
        }

        // --- Heuristic Pruning ---
        // If either sub-problem requires more moves than we have left, this path is a dead end.
        if (pruningTableEdgePerm[permCoord] > depthRemaining || pruningTableTwistFlip[twistFlipCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore next moves. ---
        final int RANDOM_MOVE_TRIGGER = -2;

        if (lastMove == RANDOM_MOVE_TRIGGER) {
            int randomMoveCode = randomGenerator.nextInt(8); // 4 faces * 2 turn types
            int moveIndex = randomMoveCode / 2;
            int turnCount = randomMoveCode %= 2;

            int nextPermCoord = permCoord;
            int nextTwistFlipCoord = twistFlipCoord;

            // Apply the random move sequence.
            for (int i = 0; i <= turnCount; i++) {
                // Unpack, lookup new state for both coordinates, and repack.
                int cornerTwist = nextTwistFlipCoord >> 5;
                int edgeFlip = nextTwistFlipCoord & 31;
                nextPermCoord = moveTableEdgePerm[nextPermCoord][moveIndex];
                nextTwistFlipCoord = moveTableCornerTwist[cornerTwist][moveIndex] << 5 | moveTableEdgeFlip[edgeFlip][moveIndex];
            }

            // Continue the search from the new random state.
            if (search(nextPermCoord, nextTwistFlipCoord, depthRemaining - 1, moveIndex, sequence)) {
                sequence[depthRemaining] = moveIndex << 1 | randomMoveCode;
                return true;
            }
        } else {
            // --- Standard Mode: Exhaustive search for solving ---
            for (int moveIndex = 0; moveIndex < 4; moveIndex++) {
                if (moveIndex != lastMove) {
                    int nextPermCoord = permCoord;
                    int nextTwistFlipCoord = twistFlipCoord;

                    // Try both turn types for the current face (e.g., L and L').
                    for (int turnType  = 0; turnType < 2; turnType++) {
                        // Get the next state from the pre-computed move tables.
                        int cornerTwist = nextTwistFlipCoord >> 5;
                        int edgeFlip = nextTwistFlipCoord & 31;
                        nextPermCoord = moveTableEdgePerm[nextPermCoord][moveIndex];
                        nextTwistFlipCoord = moveTableCornerTwist[cornerTwist][moveIndex] << 5 | moveTableEdgeFlip[edgeFlip][moveIndex];

                        // Make the recursive call for the new state.
                        if (search(nextPermCoord, nextTwistFlipCoord, depthRemaining - 1, moveIndex, sequence)) {
                            sequence[depthRemaining] = moveIndex << 1 | turnType;
                            return true;
                        }
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helpers">
    /**
     * Calculates the new edge permutation coordinate after one move.
     * <p>
     * This helper method is used to build the edge permutation move table. It decodes
     * a permutation coordinate, applies a physical move to the resulting array,
     * and then re-encodes the array back into a new coordinate.
     *
     * @param permCoord The starting edge permutation coordinate (0-359).
     * @param moveIndex The index of the move to apply (0=L, 1=R, 2=B, 3=U).
     * @return The new edge permutation coordinate after the move.
     */
    private static int calculateNewEdgePermCoord(int permCoord, int moveIndex) {
        //given position p<360 and move m<4, return new position number
        //convert number into array

        // A temporary array to hold the state of the 6 main edges.
        int[] permutationArray = new int[NUM_TRACKED_EDGES];

        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array representing the permutation.
        Utils.idxToPerm(permutationArray, permCoord, NUM_TRACKED_EDGES, true);

        // --- 2. Apply Move ---
        // Perform the physical 3-cycle move on the permutation array.
        if (moveIndex == 0) {
            Utils.circle(permutationArray, 1, 5, 2);  //L
        } else if (moveIndex == 1) {
            Utils.circle(permutationArray, 0, 2, 4);  //R
        } else if (moveIndex == 2) {
            Utils.circle(permutationArray, 3, 4, 5);  //B
        } else if (moveIndex == 3) {
            Utils.circle(permutationArray, 0, 3, 1);  //U
        }
        // --- 3. Repack Coordinate ---
        // Convert the modified permutation array back into its integer coordinate.
        return(Utils.permToIdx(permutationArray, NUM_TRACKED_EDGES, true));
    }

    /**
     * Calculates the new edge flip coordinate after one move.
     * <p>
     * This helper method is used to build the edge flip move table. It decodes
     * a flip coordinate, applies the physical move (which includes a permutation
     * and orientation change), and then re-encodes the array back into a new coordinate.
     *
     * @param flipCoord The starting edge flip coordinate (0-31).
     * @param moveIndex The index of the move to apply (0=L, 1=R, 2=B, 3=U).
     * @return The new edge flip coordinate after the move.
     */
    private static int calculateNewEdgeFlipCoord(int flipCoord, int moveIndex) {
        //given orientation p<32 and move m<4, return new position number
        //convert number into array;

        // A temporary array to hold the flip state of the 6 main edges.
        int[] flipArray = new int[NUM_TRACKED_EDGES];

        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array representing the flips.
        Utils.idxToFlip(flipArray, flipCoord, NUM_TRACKED_EDGES, true);

        // --- 2. Apply Move ---
        // Perform the physical move on the flip array. Each move consists of a
        // 3-cycle permutation and flips the orientation of two of the moving edges.
        switch (moveIndex) {
            case 0:	// L-move
                Utils.circle(flipArray, 1, 5, 2);
                flipArray[2] ^= 1; // Flip the edges at slots 2 and 5.
                flipArray[5] ^= 1;
                break;
            case 1:	// R-move
                Utils.circle(flipArray, 0, 2, 4);
                flipArray[0] ^= 1; // Flip the edges at slots 0 and 2.
                flipArray[2] ^= 1;
                break;
            case 2:	// B-move
                Utils.circle(flipArray, 3, 4, 5);
                flipArray[3] ^= 1;  // Flip the edges at slots 3 and 4.
                flipArray[4] ^= 1;
                break;
            case 3:	// U-move
                Utils.circle(flipArray, 0, 3, 1);
                flipArray[1] ^= 1;  // Flip the edges at slots 1 and 3.
                flipArray[3] ^= 1;
                break;
        }
        // --- 3. Repack Coordinate ---
        // Convert the modified flip array back into its integer coordinate.
        return Utils.flipToIdx(flipArray, NUM_TRACKED_EDGES, true);
    }

    /**
     * Calculates the new corner twist coordinate after one move.
     * <p>
     * This helper method is used to build the corner twist move table. It decodes
     * a twist coordinate, applies the physical orientation change caused by a move,
     * and then re-encodes the array back into a new coordinate.
     *
     * @param twistCoord The starting corner twist coordinate (0-80).
     * @param moveIndex The index of the move to apply (0=L, 1=R, 2=B, 3=U).
     * @return The new corner twist coordinate after the move.
     */
    private static int calculateNewCornerTwistCoord(int twistCoord, int moveIndex) {
        //given orientation p<81 and move m<4, return new position number
        //convert number into array;

        // A temporary array to hold the twist state of the 4 main corners.
        int[] orientationArray = new int[NUM_TRACKED_CORNERS];

        // --- 1. Unpack Coordinate ---
        // Convert the integer coordinate into an array representing the twists.
        Utils.idxToOri(orientationArray, twistCoord, NUM_TRACKED_CORNERS, false);

        // --- 2. Apply Move ---
        // On a Pyraminx, a face turn only affects the twist of the corresponding corner.
        // The value is incremented modulo 3.
        switch (moveIndex) {
            case 0:	// L-move affects the L corner (index 1).
                orientationArray[1] = (orientationArray[1] + 1) % 3;
                break;
            case 1: // R-move affects the R corner (index 2).
                orientationArray[2] = (orientationArray[2] + 1) % 3;
                break;
            case 2:	 // B-move affects the B corner (index 3).
                orientationArray[3] = (orientationArray[3] + 1) % 3;
                break;
            case 3: // U-move affects the U corner (index 0).
                orientationArray[0] = (orientationArray[0] + 1) % 3;
                break;
        }

        // --- 3. Repack Coordinate ---
        // Convert the modified twist array back into its integer coordinate.
        return(Utils.oriToIdx(orientationArray, 4, false));
    }
    //</editor-fold>

    //<editor-fold desc="Internal Visualization Logic">
    /**
     * Generates a visual image of the Pyraminx state from a scramble string.
     * <p>
     * This method applies a sequence of moves to a solved-state model of the
     * Pyraminx and returns an array representing the final colors of the 91 facelets.
     *
     * @param scramble The scramble string to apply (e.g., "L R' B u").
     * @return An integer array representing the 0-based colors of the 91 facelets.
     */
    public static int[] image(String scramble) {
        // --- Define Constants ---
        final String ALL_MOVE_CHARS = "LRBUlrbu";

        // --- 1. Initialize State ---
        // Create a mutable copy of the solved state color map.
        int[] colorMap = SOLVED_COLOR_MAP.clone();

        String[] scrambleMoves = scramble.split(" ");

        // --- 2. Apply Scramble ---
        // Apply each move from the scramble string to the color map.

        for (String move  : scrambleMoves) {
            if (move.length() > 0) {
                // Determine the move type (0-7 for L,R,B,U,l,r,b,u).
                int moveTypeIndex = ALL_MOVE_CHARS.indexOf(move.charAt(0));
                // The length of the move string determines the direction (1 for default, 2 for ').
                int turnDirection = move.length();

                // Apply the physical move to the color map.
                applyImageMove(moveTypeIndex, turnDirection);
            }
        }

        // --- 3. Format Final Image ---
        // Copy the results from the temporary map to the final output array,
        // converting from 1-based colors to 0-based colors.
        int d = 0;
        for (int i = 0; i < NUM_TOTAL_FACELETS; i++) {
            faceletImage[i++] = SOLVED_COLOR_MAP[i] - 1;
        }

        return faceletImage;
    }

    /**
     * Applies the physical permutation of facelets for a single move or tip turn.
     *
     * @param moveType    The index representing the move to apply (0-3 for main moves,
     * 4-7 for tip turns).
     * @param turnDirection The direction of the turn (e.g., 1 for clockwise, 2 for counter-clockwise).
     */
    private static void applyImageMove(int moveType, int turnDirection) {
        // --- Define Constants for Move Types ---
        final int L_MOVE = 0, R_MOVE = 1, B_MOVE = 2, U_MOVE = 3;
        final int L_TIP = 4, R_TIP = 5, B_TIP = 6, U_TIP = 7;

        switch (moveType) {
            case L_MOVE: // L
                rotate3(14, 58, 18, turnDirection);
                rotate3(15, 57, 31, turnDirection);
                rotate3(16, 70, 32, turnDirection);
            case L_TIP: // l
                rotate3(30, 28, 56, turnDirection);
                break;
            case R_MOVE: // R
                rotate3(32, 72, 22, turnDirection);
                rotate3(33, 59, 23, turnDirection);
                rotate3(20, 58, 24, turnDirection);
            case R_TIP: // r
                rotate3(34, 60, 36, turnDirection);
                break;
            case B_MOVE: // B
                rotate3(14, 10, 72, turnDirection);
                rotate3( 1, 11, 71, turnDirection);
                rotate3( 2, 24, 70, turnDirection);
            case B_TIP: // b
                rotate3( 0, 12, 84, turnDirection);
                break;
            case U_MOVE: // U
                rotate3( 2, 18, 22, turnDirection);
                rotate3( 3, 19,  9, turnDirection);
                rotate3(16, 20, 10, turnDirection);
            case U_TIP: // u
                rotate3( 4,  6,  8, turnDirection);
                break;
        }
    }

    /**
     * Helper method to perform a 3-element cycle on the facelet color map.
     * <p>
     * This function is used by the visualization engine to simulate the movement of
     * a single trio of facelets, either clockwise or counter-clockwise.
     *
     * @param idx1          The index of the first facelet in the cycle.
     * @param idx2          The index of the second facelet in the cycle.
     * @param idx3          The index of the third facelet in the cycle.
     * @param turnDirection An integer indicating the direction (2 for counter-clockwise,
     * otherwise clockwise).
     */
    private static void rotate3(int idx1, int idx2, int idx3, int turnDirection) {
        // --- Define Constant ---
        final int COUNTER_CLOCKWISE_TURN = 2;

        // The turn direction value corresponds to the length of the move string
        // (e.g., the length of "L'" is 2).
        if (turnDirection == COUNTER_CLOCKWISE_TURN) {
            // Perform a counter-clockwise cycle: idx1 <- idx3 <- idx2 <- idx1
            Utils.circle(SOLVED_COLOR_MAP, idx3, idx2, idx1);
        } else {
            // Perform a clockwise cycle: idx1 <- idx2 <- idx3 <- idx1
            Utils.circle(SOLVED_COLOR_MAP, idx1, idx2, idx3);
        }
    }
    //</editor-fold>


}
