package solver;

import android.util.Log;

import java.util.Arrays;

import static solver.Utils.turnSuffix;


/**
 * A specialized solver for the 2x2x2 Rubik's Cube.
 * <p>
 * This class implements a solving strategy that uses a limited move set of {U, R, F}.
 * This move set intentionally leaves the Down-Back-Left (DBL) corner piece stationary,
 * acting as a fixed reference point. The solver then finds optimal solutions for the
 * remaining 7 mobile corners.
 * <p>
 * It uses two distinct coordinate systems to solve subsets of these 7 corners:
 * <ul>
 * <li>A 3-corner system (Permutation, Orientation, Combination)</li>
 * <li>A 4-corner system (Permutation, Orientation, Combination)</li>
 * </ul>
 * All lookup tables are pre-computed for maximum speed during the search phase.
 */
public class Cube2Layer {

    //<editor-fold desc="Constants and Class Variables">
    // --- Constants ---
    private static final int NUM_MOVES = 3; // Solver uses only U, R, F moves
    private static final int MAX_SOLUTION_DEPTH = 8;
    private static final int MAX_N_CORNER_DEPTH = 6;
    private static final int NUM_MOBILE_CORNERS = 7; // The DBL corner is fixed.
    private static final int NUM_FACES = 6;
    private static final String MOVE_CHARS = "URF";
    private static final int INITIAL_LAST_MOVE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int NUM_CORNER_COMBINATIONS = 35; // C(7,3) and C(7,4) are both 35
    private static final int UNVISITED_STATE = -1;


    // --- Tables for 3-Corner Sub-problem ---
    private static final int NUM_3_CORNERS_PERM = 6; // 3!
    private static final int NUM_3_CORNERS_ORIENT = 27; // 3^3
    private static final int NUM_STATES_3_CORNERS_PERM = NUM_CORNER_COMBINATIONS * NUM_3_CORNERS_PERM;    // 210 C(7,3) * 3!
    private static final int NUM_STATES_3_CORNERS_ORIENT = NUM_CORNER_COMBINATIONS * NUM_3_CORNERS_ORIENT;   // 945 C(7,3) * 3^3
    private static short[][] moveTable_3Corner_Perm = new short[NUM_STATES_3_CORNERS_PERM][NUM_MOVES];
    private static short[][] moveTable_3Corner_Orient = new short[NUM_STATES_3_CORNERS_ORIENT][NUM_MOVES];
    private static byte[] pruningTable_3Corner = new byte[NUM_CORNER_COMBINATIONS * NUM_3_CORNERS_PERM *
            NUM_3_CORNERS_ORIENT]; // 5670 NUM_STATES_3_CORNERS_PERM * 3 ^3

    // --- Tables for 4-Corner Sub-problem (C(7,4) based) ---
    private static final int NUM_4_CORNERS_PERM = 24; // 4!
    private static final int NUM_4_CORNERS_ORIENT = 81; // 3^4
    private static final int NUM_STATES_4_CORNERS_PERM = NUM_CORNER_COMBINATIONS * NUM_4_CORNERS_PERM;   // 840 C(7,4) * 4!
    private static final int NUM_STATES_4_CORNERS_ORIENT = NUM_CORNER_COMBINATIONS * NUM_4_CORNERS_ORIENT;  // 2835 C(7,4) * 3^4
    private static short[][] moveTable_4Corner_Perm = new short[NUM_STATES_4_CORNERS_PERM][NUM_MOVES];
    private static short[][] moveTable_4Corner_Orient = new short[NUM_STATES_4_CORNERS_ORIENT][NUM_MOVES];
    private static byte[] pruningTable_4Corner = new byte[NUM_CORNER_COMBINATIONS * NUM_4_CORNERS_PERM *
            NUM_4_CORNERS_ORIENT];  //68040 NUM_STATES_4_CORNERS_PERM * 3 ^4

    // --- General Solver Variables ---

    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
    private static int[][] SOLVED_STATE_COORDS_4_CORNERS = {
            {38948, 39758, 40001, 40811, 52702, 53107, 53836, 54241, 66096, 66906, 67149, 67959}, //UP 4!
            {39094, 39447, 40176, 40633, 52488, 53366, 53609, 54351, 66326, 66715, 67444, 67865}, //RIGHT
            {38880, 39742, 39985, 40743, 52718, 53055, 53784, 54257, 66148, 66974, 67217, 68011} //FRONT
    };
    private static int[] SOLVED_STATE_COORDS_3_CORNERS_PERM = {0, 816, 42, 648, 480, 84};
    private static int[] SOLVED_STATE_COORDS_3_CORNERS_ORIENT = {0, 2754, 189, 2187, 1620, 378};
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
     * @param targetFacesBitmask A bitmask where each bit (0-5) corresponds to a target face
     * (0=D, 1=U, 2=L, 3=R, 4=F, 5=B). If a bit is set, the
     * function will find a solution for that orientation.
     * @return A formatted string with all found solutions.
     */

    public static String solveFirstLayer(String scramble, int targetFacesBitmask) {
        // Use a StringBuilder to efficiently build the final multi-line output string.
        StringBuilder resultBuilder = new StringBuilder("\n");
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {

            // Check if the bit for the current orientation is set in the bitmask.
            if (((targetFacesBitmask >> faceIndex) & 1) != 0) {
                resultBuilder.append(solve(scramble, faceIndex));
                }
        }
        return resultBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Private Core Logic">

    /**
     * The main solver for a single orientation. Applies the scramble and
     * starts the iterative deepening search.
     *
     * @param scramble The scramble string to solve from.
     * @param faceIndex The index (0-5) of the target face/orientation to solve for.
     * @return A formatted string with the solution, or "\nerror" if none is found.
     */
    private static String solve(String scramble, int faceIndex) {
        String[] scrambleMoves = scramble.split(" ");

        // Select the correct solver (3-corner or 4-corner) based on the orientation.
        boolean use3CornerSolver = (faceIndex == 0 || faceIndex == 2 || faceIndex == 5);

        // Get the coordinate for the solved state for the target orientation.
        int currentPermutation = SOLVED_STATE_COORDS_3_CORNERS_PERM[faceIndex];
        int currentOrientation = SOLVED_STATE_COORDS_3_CORNERS_ORIENT[faceIndex];

        // --- 1. Apply Scramble ---
        // Apply each move of the scramble to the solved state to find the starting coordinate.
        for (String move : scrambleMoves) {
            if (move.length() > 0) {
                int moveIndex = MOVE_CHARS.indexOf(move.charAt(0));
                if (moveIndex != -1) {
                    for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ? 3 :
                            (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                        if (use3CornerSolver) {
                            currentPermutation = moveTable_3Corner_Perm[currentPermutation][moveIndex];
                            currentOrientation = moveTable_3Corner_Orient[currentOrientation][moveIndex];
                        } else {
                            currentPermutation = moveTable_4Corner_Perm[currentPermutation][moveIndex];
                            currentOrientation = moveTable_4Corner_Orient[currentOrientation][moveIndex];
                        }
                    }
                }
            }
        }
        // --- 2. Iterative Deepening Search ---
        // Use an iterative deepening loop to find the shortest possible solution.
        for (int depth = 0; depth < MAX_SOLUTION_DEPTH; depth++) {
            boolean solutionFound;
            if (use3CornerSolver) {
                solutionFound = search3Corner(
                        SOLVED_STATE_COORDS_3_CORNERS_PERM[faceIndex],
                        SOLVED_STATE_COORDS_3_CORNERS_ORIENT[faceIndex],
                        currentPermutation,
                        currentOrientation,
                        depth,
                        INITIAL_LAST_MOVE
                );
            } else {
                solutionFound = search4Corner(
                        faceIndex,
                        currentPermutation,
                        currentOrientation,
                        depth,
                        INITIAL_LAST_MOVE
                );
            }
            if (solutionFound) {
                // --- 3. Format Solution ---
                return move2str(faceIndex, depth);
            }
        }

        // If no solution is found within the max depth, return an error.
        return "\nerror";
    }

    /**
     * Recursive IDA* search for the 3-corner sub-problem.
     * <p>
     * This method searches for a path to a solved state for 3 corners from a given
     * starting state, using the tables pre-calculated for the 3-corner system.
     *
     * @param solvedPermutation The target permutation coordinate for the solved state.
     * @param solvedOrientation The target orientation coordinate for the solved state.
     * @param currentPermutation The current permutation coordinate to search from.
     * @param currentOrientation The current orientation coordinate to search from.
     * @param depthRemaining The number of moves left to reach the solved state.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search3Corner(
            int solvedPermutation,
            int solvedOrientation,
            int currentPermutation,
            int currentOrientation,
            int depthRemaining,
            int lastMove
    ) {
        // --- Base Case: If we have no moves left, check if the state is the target solved state. ---
        if (depthRemaining == 0) {
            return currentPermutation == solvedPermutation && currentOrientation == solvedOrientation;
        }
        // --- Heuristic Pruning ---
        // Check the combined pruning table for the 3-corner system.
        if (pruningTable_3Corner[currentPermutation * 27 + currentOrientation % 27] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            // Optimization: Don't turn the same face twice in a row.
            if (moveIndex != lastMove) {
                int nextPermutation = currentPermutation;
                int nextOrientation = currentOrientation;

                // Try all 3 turn types for the current face (e.g., U, U2, U').
                for (int turnType = 0; turnType < NUM_MOVES; turnType++) {
                    // Get the next state from the pre-computed move tables for the 3-corner system.
                    nextPermutation = moveTable_3Corner_Perm[nextPermutation][moveIndex];
                    nextOrientation = moveTable_3Corner_Orient[nextOrientation][moveIndex];

                    // Make the recursive call for the new state with one less depth.
                    if (search3Corner(solvedPermutation, solvedOrientation, nextPermutation, nextOrientation, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * 3 + turnType;
                        return true;
                    }
                }
            }
        }
        // If all moves have been explored from this state without success, backtrack.
        return false;
    }

    /**
     * Recursive IDA* search for the 4-corner sub-problem.
     * <p>
     * This method searches for a path to one of the 12 valid "solved layer" states.
     * It uses the pre-computed tables for the 4-corner system to prune inefficient
     * search branches.
     *
     * @param faceIndex The index of the current problem orientation (U, R, or F),
     * used to select the correct set of solved states.
     * @param currentPermutation The current permutation coordinate to search from.
     * @param currentOrientation The current orientation coordinate to search from.
     * @param depthRemaining The number of moves left to reach a solved state.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean search4Corner(int faceIndex, int currentPermutation, int currentOrientation,
                                         int depthRemaining, int lastMove) {

        // Pack the permutation and orientation into a single coordinate for table lookups.
        int combinedCoord = currentPermutation * 81 + currentOrientation % 81;

        // --- Base Case: If we have no moves left, check if the state is a valid solution. ---
        // The is4CornerSolved helper checks if the current state is in the list of 12 solved coordinates.
        if (depthRemaining == 0) {
            return isCornerSolved(faceIndex, combinedCoord);
        }

        // --- Heuristic Pruning ---
        // If the pruning table says the minimum distance to solve is greater than
        // the depth we have left, this path is a dead end.
        if (pruningTable_4Corner[combinedCoord] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextPermutation = currentPermutation;
                int nextOrientation = currentOrientation;

                // Try all 3 turn types for the current face (e.g., F, F2, F').
                for (int turnType = 0; turnType < 3; turnType++) {
                    // Get the next state from the pre-computed move tables for the 4-corner system.
                    nextPermutation = moveTable_4Corner_Perm[nextPermutation][moveIndex];
                    nextOrientation = moveTable_4Corner_Orient[nextOrientation][moveIndex];

                    // Make the recursive call for the new state with one less depth.
                    if (search4Corner(faceIndex, nextPermutation, nextOrientation, depthRemaining - 1, moveIndex)) {
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



    /**
     * Checks if a given 4-corner state is one of the valid solved states for a specific orientation.
     * <p>
     * This method first performs a quick check using the pruning table. If the state is a potential
     * solved state (distance 0), it then performs a binary search on the pre-calculated list
     * of 12 valid solved coordinates for the given orientation.
     *
     * @param faceIndex The index of the current problem orientation (1=U, 3=R, or 4=F).
     * @param combinedCoord    The packed coordinate of the current 4-corner state.
     * @return True if the state is a valid solved state, false otherwise.
     */
    private static boolean isCornerSolved(int faceIndex, int combinedCoord) {
        // Quick Exit: If the pruning table distance is not 0, it cannot be a solved state.
        if (pruningTable_4Corner[combinedCoord] != SOLVED_STATE_DISTANCE) {
            return false;
        }

        // Map the orientation index (1, 3, or 4) to the correct row index (0, 1, or 2)
        // in the SOLVED_STATE_COORDS_4_CORNERS array.
        int index = faceIndex == 1 ? 0 : (faceIndex == 3 ? 1 : 2);

        // Perform a binary search to see if the current coordinate is in our list of 12 solved states.
        // A return value >= 0 means the coordinate was found.
        return Arrays.binarySearch(SOLVED_STATE_COORDS_4_CORNERS[index], combinedCoord) >= 0;
    }

    /**
     * Formats a found solution sequence into a human-readable string.
     *
     * @param faceIndex The index of the orientation that was solved, used for labeling.
     * @param depth The length of the solution found.
     * @return A formatted string (e.g., "\nU: R U' F2 ").
     */
    private static String move2str(int faceIndex, int depth) {
        // Use a StringBuilder to efficiently build the final string.
        StringBuilder solutionBuilder = new StringBuilder("\n");

        // Prepend the orientation label (e.g., "D: ", "U: ", etc.).
        solutionBuilder.append(orientationLabels[faceIndex]);

        // Reconstruct the solution from the sequence array by reading it backwards.
        for (int i = depth; i > 0; i--) {
            int moveCode = solutionSequence[i];

            // Unpack the move code into a face index (0-2 for U, R, F)
            // and a turn type (0-2 for standard, 2, or ').
            int moveIndex = moveCode / NUM_MOVES;
            int turnType = moveCode % NUM_MOVES;

            solutionBuilder
                    .append(MOVE_CHARS.charAt(moveIndex))
                    .append(turnSuffix[turnType])
                    .append(" ");
        }
        return solutionBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Table Generation">
    /**
     * Initializes all pre-computed lookup tables (move and pruning tables).
     * <p>
     * This method is called once to generate the data needed by the solver. It first
     * builds the move tables for both the 3-corner and 4-corner systems by simulating
     * the U, R, and F moves for every possible state. It then uses these move tables
     * to build the corresponding pruning tables with a Breadth-First Search.
     */
    private static void initializeTables() {
        // =================================================================================
        // Part 1: Generate Move Tables for 3-Corner and 4-Corner Systems ⚙️
        // =================================================================================
        for (int combIndex = 0; combIndex < NUM_CORNER_COMBINATIONS; combIndex++) {
            for (int permOrientIndex = 0; permOrientIndex < NUM_3_CORNERS_ORIENT; permOrientIndex++) {
                for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                    int newCoord = getmv(combIndex, 3, permOrientIndex, moveIndex);
                    int newPermCoord = newCoord >> 7;
                    int newOrientData = newCoord & 127;


                    // Store the new orientation coordinate
                    moveTable_3Corner_Orient[combIndex * NUM_3_CORNERS_ORIENT + permOrientIndex][moveIndex] =
                            (short) (newPermCoord / NUM_3_CORNERS_PERM * NUM_3_CORNERS_ORIENT + newOrientData);

                    // Only store permutation data once to avoid redundancy
                    if (permOrientIndex < NUM_3_CORNERS_PERM) {
                        moveTable_3Corner_Perm[combIndex * NUM_3_CORNERS_PERM + permOrientIndex][moveIndex] =
                                (short) newPermCoord;
                    }
                }
            }

            // --- Build tables for the 4-corner system ---
            for (int permOrientIndex  = 0; permOrientIndex  < NUM_4_CORNERS_ORIENT; permOrientIndex ++) {
                for (int moveIndex = 0; moveIndex < NUM_MOVES; moveIndex++) {
                    int newCoord = getmv(combIndex, 4, permOrientIndex, moveIndex);
                    int newPermCoord = newCoord >> 7;
                    int newOrientData = newCoord & 127;

                    // Store the new orientation coordinate
                    moveTable_4Corner_Orient[combIndex * NUM_4_CORNERS_ORIENT + permOrientIndex][moveIndex] =
                            (short) (newPermCoord / NUM_4_CORNERS_PERM * NUM_4_CORNERS_ORIENT + newOrientData);
                    // Only store permutation data once
                    if (permOrientIndex < NUM_4_CORNERS_PERM) {
                        moveTable_4Corner_Perm[combIndex * NUM_4_CORNERS_PERM + permOrientIndex][moveIndex] =
                                (short) newPermCoord;
                    }
                }
            }
        }

        // =================================================================================
        // Part 2: Generate Pruning Tables for Both Systems 📊
        // =================================================================================

        // --- Pruning Table for the 3-corner system ---
        for (int i = 0; i < NUM_CORNER_COMBINATIONS * NUM_3_CORNERS_ORIENT * NUM_3_CORNERS_PERM; i++) {
            pruningTable_3Corner[i] = UNVISITED_STATE;
        }

        // Set distance 0 for the three solved base states.
        pruningTable_3Corner[0] = SOLVED_STATE_DISTANCE;
        pruningTable_3Corner[7 * 162] = SOLVED_STATE_DISTANCE;
        pruningTable_3Corner[14 * 162] = SOLVED_STATE_DISTANCE;

        // Populate the table using a Breadth-First Search (BFS).
        for (int depth = 0; depth < MAX_N_CORNER_DEPTH; depth++) {
            for (int permCoord = 0; permCoord < NUM_STATES_3_CORNERS_PERM; permCoord++) {
                for (int orientCoord = 0; orientCoord < NUM_3_CORNERS_ORIENT; orientCoord++) {
                    if (pruningTable_3Corner[permCoord * NUM_3_CORNERS_ORIENT + orientCoord] == depth) {
                        for (int faceIndex = 0; faceIndex < NUM_MOVES; faceIndex++) {
                            int nextPermCoord = permCoord;
                            int nextOrientCoord = orientCoord;

                            for (int turnType = 0; turnType < NUM_MOVES; turnType++) {
                                // This section contains the specific logic for this coordinate system.
                                // It unpacks the permutation coordinate to get the combination part for the orientation lookup.
                                int combCoord = nextPermCoord / NUM_3_CORNERS_PERM;
                                int unpackedOrient =  moveTable_3Corner_Orient[combCoord * NUM_3_CORNERS_ORIENT + nextOrientCoord % NUM_3_CORNERS_ORIENT][faceIndex];
                                nextOrientCoord = unpackedOrient % NUM_3_CORNERS_ORIENT ;

                                // The permutation part is looked up directly.
                                nextPermCoord = moveTable_3Corner_Perm[nextPermCoord][faceIndex];
                                int nextCombinedCoord = nextPermCoord * NUM_3_CORNERS_ORIENT + nextOrientCoord;

                                if (pruningTable_3Corner[nextCombinedCoord] < 0) {
                                    pruningTable_3Corner[nextCombinedCoord] = (byte) (depth + 1);
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Pruning Table for the 4-corner system (uses similar logic) ---
        for (int i = 0; i < NUM_CORNER_COMBINATIONS * NUM_4_CORNERS_PERM *
                NUM_4_CORNERS_ORIENT; i++) {
            pruningTable_4Corner[i] = UNVISITED_STATE;
        }
        // Set distance 0 for all 36 possible solved states.
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 12; j++)
                pruningTable_4Corner[SOLVED_STATE_COORDS_4_CORNERS[i][j]] = SOLVED_STATE_DISTANCE;


        for (int depth = 0; depth < MAX_N_CORNER_DEPTH; depth++) {
            for (int permCoord = 0; permCoord < NUM_STATES_4_CORNERS_PERM; permCoord++)  {
                for (int orientCoord = 0; orientCoord < NUM_4_CORNERS_ORIENT; orientCoord++) {

                    if (pruningTable_4Corner[permCoord * NUM_4_CORNERS_ORIENT + orientCoord] == depth) {
                        for (int faceIndex = 0; faceIndex < NUM_MOVES; faceIndex++) {
                            int nextPermCoord = permCoord;
                            int nextOrientCoord = orientCoord;
                            for (int turnType = 0; turnType < NUM_MOVES; turnType++) {
                                int combCoord = nextPermCoord / NUM_4_CORNERS_PERM;
                                int unpackedOrient = moveTable_4Corner_Orient[combCoord * NUM_4_CORNERS_ORIENT + nextOrientCoord % NUM_4_CORNERS_ORIENT][faceIndex];

                                nextOrientCoord = unpackedOrient % NUM_4_CORNERS_ORIENT;

                                nextPermCoord = moveTable_4Corner_Perm[nextPermCoord][faceIndex];

                                int nextCombinedCoord = nextPermCoord * NUM_4_CORNERS_ORIENT  + nextOrientCoord;
                                if (pruningTable_4Corner[nextCombinedCoord] < 0) {
                                    pruningTable_4Corner[nextCombinedCoord] = (byte) (depth + 1);
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Calculates the new coordinate for a k-corner state after a given move.
     * <p>
     * This is the core engine for move table generation. It unpacks the combination,
     * permutation, and orientation coordinates for a subset of the 7 mobile corners,
     * simulates a single face turn (U, R, or F), and then repacks the resulting
     * state into a new combined coordinate.
     *
     * @param combinationIndex The starting combination coordinate (which slots are occupied).
     * @param numCorners The number of corners in the subsystem (3 or 4).
     * @param packedPermOrient The starting packed coordinate for permutation and orientation.
     * @param moveIndex The face turn to apply (0=U, 1=R, 2=F).
     * @return The new packed coordinate after the move.
     */
    private static int getmv(int combinationIndex, int numCorners, int packedPermOrient, int moveIndex) {
        // --- 1. Unpack Coordinates into a Physical Representation ---

        // Create temporary arrays for the 7 mobile corner slots and for the k-piece subset.
        int[] cornerSlots = new int[NUM_MOBILE_CORNERS];
        int[] permutation = new int[4]; // Max 4 corners
        int[] orientation = new int[4]; // Max 4 corners
        // Decode the packed coordinate into separate permutation and orientation arrays.
        Utils.idxToPerm(permutation, packedPermOrient, numCorners, false);
        Utils.idxToOri(orientation, packedPermOrient, numCorners, false);
        int piecesToPlace = numCorners;
        for (int i = 0; i < NUM_MOBILE_CORNERS; i++)
            if (combinationIndex >= Utils.Cnk[NUM_MOBILE_CORNERS - i][piecesToPlace]) {
                combinationIndex -= Utils.Cnk[NUM_MOBILE_CORNERS - i][piecesToPlace--];
                // Place the piece, packing its permutation and orientation.
                cornerSlots[i] = permutation[piecesToPlace] << 3 | orientation[numCorners - 1 - piecesToPlace];
            } else {
                cornerSlots[i] = -3; // Mark the slot as empty.
            }

        // --- 2. Apply the Physical Move ---
        switch (moveIndex) {
            case 0:  // U-move
                Utils.circle(cornerSlots, 0, 1, 3, 2); break;
            case 1:  // R-move (with orientation changes)
                Utils.circle(cornerSlots, 0, 4, 5, 1, new int[] {2, 1, 2, 1});
                break;
            case 2:  // F-move (with orientation changes)
                Utils.circle(cornerSlots, 0, 2, 6, 4, new int[] {1, 2, 1, 2});
                break;
        }
        // --- 3. Repack the New State into a Single Coordinate ---

        // Reset variables to build the new coordinates.
        int newCombinationIndex = 0;
        int newPackedOrientation  = 0;
        int piecesToFind = numCorners;

        // Scan the 7 slots to find the new positions and orientations.
        for (int i = 0; i < NUM_MOBILE_CORNERS; i++) {
            if (cornerSlots[i] >= 0) { // If a piece is in this slot...
                // Rebuild the combination index.
                newCombinationIndex += Utils.Cnk[NUM_MOBILE_CORNERS - i][piecesToFind--];

                // Unpack and store the piece's new permutation ID and orientation.
                permutation[piecesToFind] = cornerSlots[i] >> 3;
                newPackedOrientation += (cornerSlots[i] & 7) % 3;
                newPackedOrientation *= 3;
            }
        }
        // The final multiplication leaves an extra trailing *3, so we divide it out.
        newPackedOrientation /= 3;

        // Convert the new permutation array back to a compact index.
        int newPermutationIndex  = Utils.permToIdx(permutation, numCorners, false);

        // Combine all new coordinates into a single integer and return it.
        return Utils.factorial[numCorners] * combinationIndex + newPermutationIndex  << 7 | newPackedOrientation;
    }

    //</editor-fold>

}
