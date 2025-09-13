package solver;

import static solver.Utils.turnSuffix;

/**
 * A solver for the 2x2x2 Rubik's Cube.
 * <p>
 * This class uses a coordinate system based on the combination of 4 facelets (stickers)
 * on a single face, out of the 24 total facelets on the cube (C(24, 4) = 10626 states).
 * It finds optimal solutions using a limited move set of {U, R, F} and pre-computed
 * lookup tables.
 */
public class Cube2Face {

    //<editor-fold desc="Constants and Class Variables">
    // --- Constants ---
    private static final int NUM_TOTAL_FACELETS = 24;
    private static final int FACELETS_PER_FACE = 4;
    private static final int NUM_STATES = 10626; // C(24, 4) ways to choose 4 from 24 total facelets
    private static final int NUM_MOVES = 3; // The solver only uses U, R, and F moves
    private static final int MAX_SOLUTION_DEPTH = 7;
    private static final int PRUNING_TABLE_MAX_DEPTH = 5;
    private static final int NUM_FACES = 6;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final String MOVE_CHARS = "URF";
    private static final int INITIAL_LAST_MOVE = -1;

    // --- Class Variables ---
    // Move table for the 4-facelet combination coordinate.
    private static short[][] faceMoveTable = new short[NUM_STATES][NUM_MOVES];

    // Pruning table storing the minimum moves to the solved state.
    private static byte[] pruningTable = new byte[NUM_STATES];

    // Array to store the found solution sequence.
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];

    // Coordinates for the 6 possible solved states (one for each target face).
    private static int[] SOLVED_STATE_COORDS = {1819, 0, 4844, 69, 494, 10625};

    // String array for formatting the output.
    private static String[] orientationLabels = {"D: ", "U: ", "L: ", "R: ", "F: ", "B: "};

    // Static initializer to generate all tables when the class is loaded.

    static {
        initializeTables();
    }
    //</editor-fold>

    //<editor-fold desc="Public Solver Methods">
    /**
     * Public wrapper method to solve the 2x2x2 cube for multiple target faces
     * specified by a bitmask.
     *
     * @param scramble The scramble string to solve from.
     * @param targetFacesBitmask A bitmask where each bit (0-5) corresponds to a target face.
     *  * (0=D, 1=U, 2=L, 3=R, 4=F, 5=B). If a bit is set, the
     * function will find a solution for that orientation.
     * @return A formatted string with all found solutions.
     */
    public static String solveForFaces(String scramble, int targetFacesBitmask) {
        StringBuilder resultBuilder = new StringBuilder("\n");
        for (int i = 0; i < NUM_FACES; i++) {

            // Check if the bit for the current orientation is set in the bitmask.
            if (((targetFacesBitmask >> i) & 1) != 0)
                resultBuilder.append(solve(scramble, i));
        }
        return resultBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Private Core Logic">

    /**
     * The main solver for a single 2x2x2 orientation.
     * <p>
     * This method applies the scramble to the corresponding solved state to find the
     * starting coordinate, then uses an iterative deepening search to find the
     * shortest solution.
     *
     * @param scramble The scramble string to solve from.
     * @param faceIndex The index (0-5) of the target face/orientation to solve for.
     * @return A formatted string with the solution, or "error" if none is found.
     */
    private static String solve(String scramble, int faceIndex) {
        // Get the coordinate for the solved state for the target orientation.
        String[] scrambleMoves = scramble.split(" ");
        int currentState = SOLVED_STATE_COORDS[faceIndex];

        // Apply the scramble to the solved state to find the starting coordinate.
        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                int moveIndex = MOVE_CHARS.indexOf(move.charAt(0));
                for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ?
                        3 : (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                    currentState = faceMoveTable[currentState][moveIndex];
                }
            }
        }

        // Use iterative deepening to find the shortest solution.
        for (int depth  = 0; depth < MAX_SOLUTION_DEPTH; depth++) {
            if (search(currentState, depth, INITIAL_LAST_MOVE)) {
                StringBuilder solutionBuilder = new StringBuilder("\n");
                solutionBuilder.append(orientationLabels[faceIndex]);
                for (int i = depth; i > 0; i--) {
                    int moveCode = solutionSequence[i];
                    int face = moveCode / NUM_MOVES;
                    int turnType = moveCode % NUM_MOVES;
                    solutionBuilder
                            .append(MOVE_CHARS.charAt(face))
                            .append(turnSuffix[turnType])
                            .append(" ");
                }
                return solutionBuilder.toString();
            }
        }

        // If no solution is found within the max depth, return an error.
        return "error";
    }

    /**
     * The recursive IDA* search function to find a solution for a given state.
     * <p>
     * This method performs a depth-first search up to a specified remaining depth.
     * It uses the pruning table to avoid exploring branches that are known to be
     * longer than the current depth limit.
     *
     * @param currentState The coordinate of the current state to search from.
     * @param depthRemaining The number of moves left to reach the solved state.
     * @param lastMove The index of the last move made, to avoid redundant sequences (e.g., U U').
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search(int currentState, int depthRemaining, int lastMove) {
        // --- Base Case: If we have no moves left, check if the state is solved. ---
        // The pruning table stores the distance to solved; a distance of 0 means solved.
        if (depthRemaining == 0) {
            return pruningTable[currentState] == 0;
        }

        // --- Heuristic Pruning ---
        // If the pruning table says the minimum distance to solve is greater than
        // the depth we have left, this path is a dead end.
        if (pruningTable[currentState] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextState = currentState;
                // Try all 3 turn types for the current face (e.g., R, R2, R').
                for (int turnType = 0; turnType < NUM_MOVES; turnType++) {
                    // Get the next state from the pre-computed move table.
                    nextState = faceMoveTable[nextState][moveIndex];
                    if (search(nextState, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_MOVES + turnType;
                        return true;
                    }
                }
            }
        }

        // If all moves have been explored from this state without success, backtrack.
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Table Generation">
    /**
     * Initializes all pre-computed lookup tables (move and pruning tables).
     * <p>
     * This method is called once to generate the data needed by the solver. It first
     * builds a move table by simulating the U, R, and F moves for every possible state.
     * It then uses this move table to build a pruning table with a Breadth-First Search.
     */
    private static void initializeTables() {
        // A temporary array to hold the 24 facelets of the 2x2x2 cube.
        int[] facelets = new int[NUM_TOTAL_FACELETS];
        // Iterate through every possible state of the 4 tracked facelets.
        for (int stateIndex  = 0; stateIndex  < NUM_STATES; stateIndex ++) {
            pruningTable[stateIndex ] = UNVISITED_STATE;

            // For each state, calculate the result of each of the 3 possible moves (U, R, F).
            for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                // Unpack the state coordinate into the facelet array.
                Utils.idxToComb(facelets, stateIndex , FACELETS_PER_FACE, NUM_TOTAL_FACELETS);

                // Apply the move by cycling the appropriate facelets.
                // These three 4-cycles correspond to a move on a 2x2x2 cube.
                switch (moveIndex) {
                    // The three 4-cycles here correspond to the movement of a 2x2x2's facelets.
                    case 0: //U
                        Utils.circle(facelets, 0,  2,  3,  1);
                        Utils.circle(facelets, 4, 20, 16,  8);
                        Utils.circle(facelets, 5, 21, 17,  9);
                        break;
                    case 1: //R
                        Utils.circle(facelets, 4,  6,  7,  5);
                        Utils.circle(facelets, 1,  9, 13, 22);
                        Utils.circle(facelets, 3, 11, 15, 20);
                        break;
                    case 2: //F
                        Utils.circle(facelets, 8, 10, 11,  9);
                        Utils.circle(facelets, 2, 19, 13,  4);
                        Utils.circle(facelets, 3, 17, 12,  6);
                        break;
                }
                // Repack the new facelet configuration into a coordinate and store it.
                faceMoveTable[stateIndex ][moveIndex] = (short) Utils.combToIdx(facelets, 4, 24);
            }
        }
        // Set the solved states' distances to 0 before populating the pruning table.
        for (int i = 0; i < NUM_FACES; i++) {
            pruningTable[SOLVED_STATE_COORDS[i]] = SOLVED_STATE_DISTANCE;
        }
        // Populate the rest of the pruning table using the completed move table.
        Utils.populatePruningTable(pruningTable, PRUNING_TABLE_MAX_DEPTH,
                faceMoveTable, NUM_MOVES);
    }
        /*
                      +---+---+
                      | 0 | 1 |
                      +---+---+
                      | 2 | 3 |
                      +---+---+
            +---+---+ +---+---+ +---+---+ +---+---+
            | 16| 17| | 8 | 9 | | 4 | 5 | | 20| 21|
            +---+---+ +---+---+ +---+---+ +---+---+
            | 18| 19| | 10| 11| | 6 | 7 | | 22| 23|
            +---+---+ +---+---+ +---+---+ +---+---+
                      +---+---+
                      | 12| 13|
                      +---+---+
                      | 14| 15|
                      +---+---+


    */
    //</editor-fold>


}
