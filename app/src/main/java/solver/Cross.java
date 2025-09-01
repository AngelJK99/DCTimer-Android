package solver;

import android.util.Log;

import java.util.ArrayList;
import java.util.Random;

import static solver.Utils.Cnk;
import static solver.Utils.suff;
import static solver.Utils.getPruning;
import static solver.Utils.setPruning;

/**
 * Solves the first cross of a Rubik's Cube.
 * This class provides methods to find solutions for forming the cross on any face,
 * and also for "xcross" (cross + one first layer pair).
 * It uses precomputed move tables and pruning tables for efficiency.
 */
public class Cross {
    //<editor-fold desc="Static Fields and Initializers - Core Cross">
    // Constants declaration
    private static final int N_CORNERS = 8;
    private static final int N_EDGES = 12;
    private static final int N_ORIENTATIONS_PER_EDGE = 2;
    private static final int N_ORIENTATIONS_PER_CORNER = 3;


    private static final int N_CROSS_EDGES_COMBINATIONS = 495; // C(12,4)
    private static final int N_CROSS_EDGES_PERMUTATIONS = 24; // 4!
    private static final int N_CROSS_EDGES_ORIENTATIONS = (int) Math.pow(N_ORIENTATIONS_PER_EDGE,4); // 2^4

    private static final int N_EASYCROSS_EDGES_ORIENTATIONS = N_ORIENTATIONS_PER_EDGE; // 1*1*1*2

    private static final int N_PAIR_CORNERS_COMBINATIONS = N_CORNERS;   // C(8,1)
    private static final int N_PAIR_CORNERS_PERMUTATIONS = 1;   // 1

    private static final int N_PAIR_EDGES_COMBINATIONS = N_EDGES;   // C(12,1)
    private static final int N_PAIR_EDGES_PERMUTATIONS = 1;   // 1

    private static final int N_F2L_SLOTS = 4;
    private static final int N_XCROSS_PAIR_STATES = N_PAIR_CORNERS_COMBINATIONS*N_ORIENTATIONS_PER_CORNER*
            N_PAIR_EDGES_COMBINATIONS*N_ORIENTATIONS_PER_EDGE;
    private static final int N_CROSS_EDGES = 4;


    private static final int N_MAX_TURN = 3; // 0 = clockwise, 1 = double turn, 2 = counterclockwise
    private static final int N_MAX_MOVE_AXIS = 6; // D U L R F B

    // --- Pruning and Move Tables ---
    // Edge Permutation Move Table: Stores the next edge permutation state (combination + permutation index for 4 edges)
    /** Edge Permutation Move Table: [currentState][move] -> newState */
    private static short[][] edgePermutationMoveTable = new short[N_CROSS_EDGES_COMBINATIONS * N_CROSS_EDGES_PERMUTATIONS][6];

    // Edge Orientation Move Table (for the 4 cross edges)
    /** Edge Orientation Move Table: [currentState][move] -> newState */
    private static short[][] edgeOrientationMoveTable = new short[N_CROSS_EDGES_COMBINATIONS * N_CROSS_EDGES_ORIENTATIONS][6];

    // --- Pruning Tables (Distance Heuristics) ---
    // Distance to solve edge permutation for the 4 cross edges.
    /** Edge Permutation Distance Table (Pruning Table): [state] -> minMovesToSolved */
    private static byte[] edgePermutationDistanceTable = new byte[N_CROSS_EDGES_COMBINATIONS * N_CROSS_EDGES_PERMUTATIONS];

    // Distance to solve edge orientation for the 4 cross edges.
    /** Edge Orientation Distance Table (Pruning Table): [state] -> minMovesToSolved */
    private static byte[] edgeOrientationDistanceTable = new byte[N_CROSS_EDGES_COMBINATIONS * N_CROSS_EDGES_ORIENTATIONS];
    /**
     * Edge Orientation Distance Table for EOFC (Edges Oriented for First Cross).
     * This table helps prune states where cross edges are oriented but not necessarily permuted.
     * [state] -> minMovesToCrossEdgesOriented
     */
    private static byte[] crossEdgesInSliceOrientationDistanceTable = new byte[N_CROSS_EDGES_COMBINATIONS * N_CROSS_EDGES_ORIENTATIONS];
    /**
     * Combined Edge State Distance Table (used for easyCross generation).
     * Stores distance for a combined state of permutation and orientation of 4 edges.
     * Indexing: (combinationIndex * 384) + (permutationIndex << 4) | orientationIndex
     */
    private static int[] combinedEdgeDistanceTable = new int[N_CROSS_EDGES_COMBINATIONS
            * N_CROSS_EDGES_PERMUTATIONS * N_EASYCROSS_EDGES_ORIENTATIONS];

    // --- Move Tables for XCross (Corner-Edge Pair) ---
    // Corner Permutation/Orientation Move Table for the first pair.
    /** XCross Corner Move Table: [currentState][move] -> newState */
    private static byte[][] xcrossCornerMoveTable = new byte[N_PAIR_CORNERS_COMBINATIONS *
            N_PAIR_CORNERS_PERMUTATIONS *N_ORIENTATIONS_PER_CORNER][N_MAX_MOVE_AXIS];

    // Edge Permutation/Orientation Move Table for the first pair's edge relative to the cross.
    /** XCross Pair Edge Move Table: [currentState][move] -> newState (edge relative to cross) */
    private static byte[][] xcrossEdgeMoveTable = new byte[N_PAIR_EDGES_COMBINATIONS* N_ORIENTATIONS_PER_EDGE][N_MAX_MOVE_AXIS];

    // --- Pruning Table for XCross ---
    /**
     * XCross Pair Distance Table (Pruning Table for F2L pair).
     * [f2lSlotIndex][combinedEdgeState * 24 + combinedCornerState] -> minMovesToSolvedPair
     * This table stores the minimum moves to solve a specific F2L pair (corner + edge)
     * for each of the 4 F2L slots.
     */
    private static byte[][] xcrossPairDistanceTable = new byte[N_F2L_SLOTS][N_PAIR_EDGES_COMBINATIONS*N_PAIR_EDGES_PERMUTATIONS
            *N_ORIENTATIONS_PER_EDGE*N_PAIR_CORNERS_COMBINATIONS*N_PAIR_CORNERS_PERMUTATIONS*N_ORIENTATIONS_PER_CORNER];


    // --- Solution Storage & State ---
    /** Stores the sequence of moves for the current best solution found by IDA*. */
    private static int[] solutionMoveSequence = new int[20]; // Max depth for cross/xcross
    /** List to store multiple solutions when searching (e.g., for `solveCrossf`). */
    private static ArrayList<String> solutionsList; // To store multiple solutions
    /** Flag indicating if the main pruning tables (cross) have been initialized. */
    public static boolean isInitialized = false; // Flag to ensure one-time initialization
    /** Flag indicating if the tables for `easyCross` generation have been initialized. */
    public static boolean isEasyCrossInitialized = false;

    // --- Cube Representation and Move Definitions ---
    /** Standard face color names (D, U, L, R, F, B). */
    private static String[] FACE_COLORS = {"D", "U", "L", "R", "F", "B"};
    // Defines move character mapping based on cube orientation.
    /**
     * Character representation of moves for each face, depending on current cube orientation (side)
     * and the face being turned (face).
     * `MOVE_CHAR_MAP_PER_ORIENTATION[cubeOrientation][faceTurned]`
     * Example: `MOVE_CHAR_MAP_PER_ORIENTATION[0][0]` (D face on D side) might be "UDLRFB"
     * meaning U is U, D is D etc. from solver's perspective.
     * If cube is rotated (e.g. x rotation, F becomes U), this map helps translate.
     * The first index [0] seems to represent the standard orientation (e.g. White Down, Green Front).
     */
    private static String[][] MOVE_CHAR_MAP_PER_ORIENTATION = {
            { "UDLRFB", "DURLFB", "RLUDFB", "LRDUFB", "BFLRUD", "FBLRDU" },
            { "UDLRFB", "DURLFB", "RLUDFB", "LRDUFB", "BFRLDU", "FBRLUD" },
            { "UDLRFB", "DURLFB", "RLUDFB", "LRDUFB", "BFUDRL", "FBUDLR" },
            { "UDLRFB", "DURLFB", "RLUDFB", "LRDUFB", "BFDULR", "FBDURL" },
            { "UDLRFB", "DULRBF", "RLBFUD", "LRFBUD", "BFLRUD", "FBRLUD" },
            { "UDLRFB", "DULRBF", "RLFBDU", "LRBFDU", "BFRLDU", "FBLRDU" }
    };
    // Defines the whole cube rotation string to apply to the solution to match the target cross face.
    /**
     * Maps a cube rotation from a solver's perspective (e.g., solving cross on F face)
     * to the standard orientation (e.g., D face).
     * `ROTATION_TO_STANDARD_ORIENTATION_MAP[originalReferenceFace][targetReferenceFace]`
     * Example: `ROTATION_TO_STANDARD_ORIENTATION_MAP[0][4]` (D to F) would be "x'".
     * This helps express the solution in terms of standard notation after solving on a different axis.
     */
    private static String[][] ROTATION_TO_STANDARD_ORIENTATION_MAP = {
            { "", "z2", "z'", "z", "x'", "x" },     // Solving U-face, target U, D, R, L, F, B
            { "z2", "", "z", "z'", "x", "x'" },     // Solving D-face, target D, U, L, R, F, B
            { "z", "z'", "", "z2", "y", "y'" },     // Solving R-face
            { "z'", "z", "z2", "", "y'", "y" },     // Solving L-face
            { "x", "x'", "y'", "y", "", "y2" },     // Solving F-face
            { "x'", "x", "y", "y'", "y2", "" }      // Solving B-face
    };

    /** String representations for EOFC (Edge Orientation First Cross) solution sides. */
    private static String[] EOFC_SIDE_STRINGS = {"D(FB)", "D(LR)", "U(FB)", "U(LR)",
            "L(FB)", "L(UD)", "R(FB)", "R(UD)", "F(UD)", "F(LR)", "B(UD)", "B(LR)"};
    //private static String[] turn = { "UDLRFB", "DURLFB", "RLUDFB", "LRDUFB", "BFLRUD", "FBLRDU" };

    //</editor-fold>

    //<editor-fold desc="Initialization Methods">
    // Static initializer block to ensure tables are ready when class is loaded.
    private static void initializeTables() {
        if (isInitialized)
            return;
        Log.d("CrossSolver", "Initializing pruning tables..."); // Example logging

        // --- Initialize Edge Permutation and Orientation Tables (for Cross) ---
        // epm (edgePermutationMoveTable) and eom (edgeOrientationMoveTable)
        // epd (edgePermutationDistanceTable) and eod (edgeOrientationDistanceTable)
        // eofd (crossEdgesInSliceOrientationDistanceTable)

        for (int combinationIdx = 0; combinationIdx < N_CROSS_EDGES_COMBINATIONS; combinationIdx++) {
            for (int permOrientIdx = 0; permOrientIdx < N_CROSS_EDGES_PERMUTATIONS; permOrientIdx++) {
                for (int move = 0; move < N_MAX_MOVE_AXIS; move++) {
                    // Calculate the resulting state after applying the move
                    // high bits for new combination & permutation, low bits for new orientation
                    int newPackedCoord = calculateNextEdgeStateAfterMove(combinationIdx, permOrientIdx, move);

                    // Unpack and store the new permutation coordinate.
                    int combinedPermCoord = N_CROSS_EDGES_PERMUTATIONS * combinationIdx
                            + permOrientIdx;
                    edgePermutationMoveTable[combinedPermCoord][move] = (short) (newPackedCoord >> 4);

                    // Unpack and store the new orientation coordinate (if valid).
                    if (permOrientIdx < N_CROSS_EDGES_ORIENTATIONS) { // Check if this permOrientIdx is valid for orientation part
                        int combinedOrientCoord = N_CROSS_EDGES_ORIENTATIONS * combinationIdx + permOrientIdx;
                        int newCombination = newPackedCoord / (N_CROSS_EDGES_PERMUTATIONS *
                                N_CROSS_EDGES_ORIENTATIONS);
                        int newOrientation = newPackedCoord & 15;
                        edgeOrientationMoveTable[combinedOrientCoord][move] = (short) ((newCombination << 4) | newOrientation);
                    }
                }
            }
        }
        // =================================================================================
        // Part 2: Generate Pruning Tables for the Cross 📊
        // These tables store the minimum number of moves to solve the cross from any state.
        // This allows the solver to "prune" branches that are guaranteed to be too long.
        // =================================================================================
        // --- Permutation Pruning Table ---
        for (int i = 0; i < N_CROSS_EDGES_COMBINATIONS*N_CROSS_EDGES_PERMUTATIONS; i++) edgePermutationDistanceTable[i] = -1;
        edgePermutationDistanceTable[69 * N_CROSS_EDGES_PERMUTATIONS] = 0;  // Solved state C(8,4)*24 for edges 0,1,2,3 on D face
        Utils.createPrun(edgePermutationDistanceTable, 6, edgePermutationMoveTable, 3); // Max depth 6-7 for EP

        // --- Orientation Pruning Table ---
        for (int i = 0; i < N_CROSS_EDGES_COMBINATIONS*N_CROSS_EDGES_ORIENTATIONS; i++)
            edgeOrientationDistanceTable[i] = crossEdgesInSliceOrientationDistanceTable[i] = -1;
        edgeOrientationDistanceTable[69 * N_CROSS_EDGES_ORIENTATIONS] = 0; // Solved state C(8,4)*16 for edges 0,1,2,3 oriented
        Utils.createPrun(edgeOrientationDistanceTable, 7, edgeOrientationMoveTable, 3);

        // [The eofd table is another specialized pruning table, likely for a specific sub-problem]
        for (int i = 0; i < N_CROSS_EDGES_COMBINATIONS*N_CROSS_EDGES_ORIENTATIONS; i++) crossEdgesInSliceOrientationDistanceTable[i] = -1;
        // For any combination of 4 edges, if their orientation bits are all 0, it's a solved orientation state *for that combination*.
        // This is used for EO-first approaches where edges are first put in slice, then oriented.
        for (int combinationIdx = 0; combinationIdx < N_CROSS_EDGES_COMBINATIONS; combinationIdx++) {
            crossEdgesInSliceOrientationDistanceTable[combinationIdx << 4] = 0; // combination * 16 + 0 orientation bits
        }
        Utils.createPrun(crossEdgesInSliceOrientationDistanceTable, 4, edgeOrientationMoveTable, 3);

        // =================================================================================
        // Part 3 & 4: Generate Move and Pruning Tables for X-Cross (Cross + 1st F2L pair)
        // This is a more advanced step, building tables for solving the first corner and edge
        // simultaneously with the cross.
        // =================================================================================

        // --- X-Cross Corner Move Table (`fcm`) ---
        byte[][] cornerPermutations = {
                {1, 0, 3, 0, 0, 4},
                {2, 1, 1, 5, 1, 0},
                {3, 2, 2, 1, 6, 2},
                {0, 3, 7, 3, 2, 3},
                {4, 7, 0, 4, 4, 5},
                {5, 4, 5, 6, 5, 1},
                {6, 5, 6, 2, 7, 6},
                {7, 6, 4, 7, 3, 7}
        };
        byte[][] cornerOrientationChanges = {
                {0, 0, 1, 0, 0, 2},
                {0, 0, 0, 2, 0, 1},
                {0, 0, 0, 1, 2, 0},
                {0, 0, 2, 0, 1, 0},
                {0, 0, 2, 0, 0, 1},
                {0, 0, 0, 1, 0, 2},
                {0, 0, 0, 2, 1, 0},
                {0, 0, 1, 0, 2, 0}
        };
        for (int corner = 0; corner < N_CORNERS; corner++) {
            for (int orientation = 0; orientation < N_ORIENTATIONS_PER_CORNER; orientation++) {
                for (int move = 0; move < N_MAX_MOVE_AXIS; move++) {
                    xcrossCornerMoveTable[corner * N_ORIENTATIONS_PER_CORNER + orientation][move] =
                            (byte) (cornerPermutations[corner][move] * N_ORIENTATIONS_PER_CORNER +
                                    (cornerOrientationChanges[corner][move] + orientation) % N_ORIENTATIONS_PER_CORNER
                    );
                }
            }
        }

        // --- X-Cross Edge Move Table (`fem`) ---
        byte[][] pairEdgePermutations = {
                {0,  0,  7,  0,  0,  8},
                {1,  1,  1,  9,  1,  4},
                {2,  2,  2,  5,  10, 2},
                {3,  3,  11, 3,  6,  3},
                {5,  4,  4,  4,  4,  0},
                {6,  5,  5,  1,  5,  5},
                {7,  6,  6,  6,  2,  6},
                {4,  7,  3,  7,  7,  7},
                {8,  11, 8,  8,  8,  1},
                {9,  8,  9,  2,  9,  9},
                {10, 9,  10, 10, 3,  10},
                {11, 10, 0,  11, 11, 11}
        };
        byte[][] pairEdgeOrientations = {
                {0, 0, 0, 0, 0, 1},
                {0, 0, 0, 0, 0, 1},
                {0, 0, 0, 0, 1, 0},
                {0, 0, 0, 0, 1, 0},
                {0, 0, 0, 0, 0, 1},
                {0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 1, 0},
                {0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 1},
                {0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 1, 0},
                {0, 0, 0, 0, 0, 0}
        };
        for (int edge = 0; edge < N_EDGES; edge++) {
            for (int orientation = 0; orientation < N_ORIENTATIONS_PER_EDGE; orientation++) {
                for (int move = 0; move < N_MAX_MOVE_AXIS; move++) {
                    xcrossEdgeMoveTable[edge * N_ORIENTATIONS_PER_EDGE + orientation][move] = (byte) (
                            pairEdgePermutations[edge][move] * N_ORIENTATIONS_PER_EDGE +
                                    (pairEdgeOrientations[edge][move] ^ orientation)
                    );
                }
            }
        }

        // --- X-Cross Pruning Table (`fecd`) ---
        for (int f2lSlotIndex = 0; f2lSlotIndex < N_F2L_SLOTS; f2lSlotIndex++) {
            for (int i = 0; i < N_XCROSS_PAIR_STATES; i++) xcrossPairDistanceTable[f2lSlotIndex][i] = -1;
            // Solved state for this F2L slot (corner & edge correctly placed and oriented)
            // Consistent with idaxcross: corner=(slot+4)*3, edge=slot*2
            int solvedCornerState = (f2lSlotIndex + 4) * N_ORIENTATIONS_PER_CORNER;
            int solvedPairEdgeState = f2lSlotIndex * N_ORIENTATIONS_PER_EDGE;

            // Set distance 0 for the 4 possible solved F2L pair states.
            xcrossPairDistanceTable[f2lSlotIndex][solvedPairEdgeState * N_CROSS_EDGES_PERMUTATIONS + solvedCornerState] = 0;

            // Populate the pruning table using a Breadth-First Search (BFS) pattern.
            for (int distance = 0; distance < 6; distance++) {
                int statesAtThisDistance = 0;
                for (int combinedStateIdx = 0; combinedStateIdx < N_XCROSS_PAIR_STATES; combinedStateIdx++) {
                    if (xcrossPairDistanceTable[f2lSlotIndex][combinedStateIdx] == distance) {
                        for (int moveAxis = 0; moveAxis < N_MAX_MOVE_AXIS; moveAxis++) {

                            for (int nextCombinedState = combinedStateIdx, turnCount  = 0; turnCount < N_MAX_TURN; turnCount++) {
                                // Apply move to get new state
                                int cornerState  = nextCombinedState % 24;
                                int edgeState  = nextCombinedState / 24;
                                int nextCornerState = xcrossCornerMoveTable[cornerState][moveAxis];
                                int nextEdgeState = xcrossEdgeMoveTable[edgeState][moveAxis];
                                nextCombinedState = nextEdgeState * 24 + nextCornerState;

                                // If new state is unvisited, mark its distance.
                                if (xcrossPairDistanceTable[f2lSlotIndex][nextCombinedState] == -1) {
                                    xcrossPairDistanceTable[f2lSlotIndex][nextCombinedState] = (byte) (distance + 1);
                                    statesAtThisDistance++;
                                }
                            }
                        }
                    }
                }
                Log.d("CrossInit", "XCross Slot " + f2lSlotIndex + " Dist " + (distance + 1) + ": " + statesAtThisDistance);
            }
        }
        isInitialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Cross IDA* Solving Algorithms">

    /**
     * Performs a recursive depth-limited search to solve the cross.
     * This is the core of an IDA* (Iterative Deepening A*) solver. It tries to reach
     * the solved state from the current state within a given number of moves.
     *
     * @param currentEdgePermutation The current permutation coordinate of the cross edges.
     * @param currentEdgeOrientation The current orientation coordinate of the cross edges.
     * @param depthRemaining The maximum number of moves left to solve the cross.
     * @param lastMoveAxis The face that was just turned, to avoid redundant moves (e.g., U U').
     * @return True if a solution is found within the given depth, false otherwise.
     */
    private static boolean searchForCrossSolution(int currentEdgePermutation, int currentEdgeOrientation,
                                                  int depthRemaining, int lastMoveAxis) {
        // Base Case: If we are at depth 0, check if the cross is solved.
        // The numbers 1656 and 1104 are the specific coordinate values for a solved cross.
        if (depthRemaining == 0) {
            // Solved state: EP=1656 (e.g., D-face edges {0,1,2,3} permuted), EO=1104 (oriented)
            return currentEdgePermutation == 1656 && currentEdgeOrientation == 1104;
        }

        // Heuristic Pruning: Check if it's even possible to solve in the remaining depth.
        // `epd` and `eod` are pre-calculated tables (pruning tables) that store the minimum
        // number of moves required to solve the permutation/orientation from any state.
        // If the required moves are more than the depth we have left, this path is a dead end.
        if (edgePermutationDistanceTable[currentEdgePermutation] > depthRemaining ||
                edgeOrientationDistanceTable[currentEdgeOrientation] > depthRemaining)
            return false;

        // --- Recursive Step: Explore all possible next moves ---
        // Iterate through the 6 faces of the cube (U, D, R, L, F, B).
        for (int moveAxis = 0; moveAxis < N_MAX_MOVE_AXIS; moveAxis++)
            if (moveAxis != lastMoveAxis) {
                // Create temporary variables for the next state's coordinates.
                int nextEdgePermutation = currentEdgePermutation;
                int nextEdgeOrientation = currentEdgeOrientation;

                // Iterate through the 3 possible turns for the current face (e.g., U, U2, U').
                for (int turnCount = 0; turnCount < N_MAX_TURN; turnCount++) {

                    // Get the next state's coordinates from pre-calculated move tables.
                    // `epm` and `eom` are tables where moveTable[currentState][move] = nextState.
                    nextEdgePermutation = edgePermutationMoveTable[nextEdgePermutation][moveAxis];
                    nextEdgeOrientation = edgeOrientationMoveTable[nextEdgeOrientation][moveAxis];

                    // Recursively call the function for the new state with one less depth.
                    if (searchForCrossSolution(nextEdgePermutation, nextEdgeOrientation, depthRemaining - 1, moveAxis)) {
                        // --- Solution Found ---
                        // If the recursive call returns true, it means we found a path to the solution.
                        // We record the successful move in our solution sequence array.
                        solutionMoveSequence[depthRemaining] = moveAxis * N_MAX_TURN + turnCount;
                        return true;
                    }
                }
            }

        // If we have explored all possible moves and found no solution, backtrack.
        return false;
    }

    /**
     * Recursively searches for all cross solutions up to a given depth.
     * This is the core of an IDA* solver that finds and formats all optimal solutions.
     * It explores move sequences, prunes inefficient branches, and records valid solutions.
     *
     * @param currentEdgePermutation Current edge permutation state.
     * @param currentEdgeOrientation Current edge orientation state.
     * @param depthRemaining       Depth left for search.
     * @param lastMoveAxis         Last move axis.
     * @param solvedOnFaceIdx      The face index (0-5) on which the cross is defined as solved (usually D=0).
     * @param currentPathMoves     Array to build up the solution path.
     */
    private static void searchForCrossSolution(int currentEdgePermutation, int currentEdgeOrientation, int depthRemaining,
                                               int lastMoveAxis, int solvedOnFaceIdx, int[] currentPathMoves) {
        // --- Base Case: We've reached the maximum search depth ---
        if (depthRemaining == 0) {
            // Check if the current state is the solved state.
            // The numbers 1656 and 1104 are the specific coordinate values for a solved cross.
            if (currentEdgePermutation == 1656 && currentEdgeOrientation == 1104) {
                // --- Solution Found: Format and store it ---

                StringBuilder solutionString = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][solvedOnFaceIdx]);
                int quarterTurnMove = 0; // Move count where any turn (e.g., R2) is 2 moves.

                // Reconstruct the solution string from the path taken.
                for (int i = currentPathMoves.length - 1; i > 0; i--) {
                    int moveCode = currentPathMoves[i];
                    int moveAxis = moveCode / 3;
                    int turnType = moveCode % 3; // 0=', 1=2, 2='

                    solutionString.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(moveAxis)).append(suff[moveCode % 3]);
                    quarterTurnMove += (turnType == 1) ? 2 : 1; // A '2' turn (like U2) counts as 2 quarter turns.
                }

                // Append move counts to the string (face turn metric 'f' and quarter turn metric 'q').
                solutionString.append("\t").append(currentPathMoves.length - 1).append("f, ").append(quarterTurnMove).append("q");
                solutionsList.add(solutionString.toString());
            }
            return; // End this path
        }

        // --- Heuristic Pruning ---
        // If the minimum moves to solve from here is greater than the depth we have left,
        // this path is a dead end. Stop searching it.
        if (edgePermutationDistanceTable[currentEdgePermutation] > depthRemaining ||
                edgeOrientationDistanceTable[currentEdgeOrientation] > depthRemaining) {
            return; // Prune
        }

        // --- Recursive Step: Explore all valid next moves ---
        for (int moveAxis = 0; moveAxis < N_MAX_MOVE_AXIS; moveAxis++)
            // Optimization: Prune redundant move sequences.
            // 1. Don't turn the same face twice in a row (e.g., U U').
            // 2. Don't turn an opposite face if it could have been done earlier (e.g., avoid U D U by forcing U U D).
            if (moveAxis != lastMoveAxis && !(moveAxis/2 == lastMoveAxis/2 && moveAxis < lastMoveAxis)) {
                int nextEdgePermutation = currentEdgePermutation;
                int nextEdgeOrientation = currentEdgeOrientation;

                // Try all 3 turn types for the current face (e.g., R, R2, R').
                for (int turnCount = 0; turnCount < N_MAX_TURN; turnCount++) {
                    // Get the next state from the pre-calculated move tables.
                    nextEdgePermutation = edgePermutationMoveTable[nextEdgePermutation][moveAxis];
                    nextEdgeOrientation = edgeOrientationMoveTable[nextEdgeOrientation][moveAxis];

                    // Record this move in the path.
                    currentPathMoves[depthRemaining] = moveAxis * N_MAX_TURN + turnCount;

                    // Recursively call the function for the new state with one less depth.
                    searchForCrossSolution(
                            nextEdgePermutation,
                            nextEdgeOrientation,
                            depthRemaining - 1,
                            moveAxis,
                            solvedOnFaceIdx,
                            currentPathMoves);
                }
            }
    }


    //</editor-fold>

    //<editor-fold desc="XCross IDA* Solving Algorithms">

    /**
     * IDA* search for an XCross solution (Cross + First F2L pair).
     *
     * @param currentEdgePermState      Cross Edge Permutation state.
     * @param currentEdgeOrientState    Cross Edge Orientation state.
     * @param currentCornerOrientState  XCross Corner Orientation state.
     * @param currentXPairEdgeOrientState      XCross Pair Edge Orientation state (relative to cross pieces).
     * @param f2lSlotIndex              The target F2L slot for the pair (0-3).
     * @param depthRemaining            Remaining depth for the search.
     * @param lastMoveAxis              Axis of the last move.
     * @return True if an XCross solution is found.
     */
    private static boolean searchForXCross(int currentEdgePermState, int currentEdgeOrientState,
                                           int currentCornerOrientState, int currentXPairEdgeOrientState, int f2lSlotIndex, int depthRemaining, int lastMoveAxis) {
        if (depthRemaining == 0) {
            boolean crossSolved = currentEdgePermState == 1656 && currentEdgeOrientState == 1104;
            // Solved XCross pair: corner in (slot+4)*3 state, edge in slot*2 state
            boolean pairSolved = currentCornerOrientState == (f2lSlotIndex + 4) * 3 && currentXPairEdgeOrientState == f2lSlotIndex * 2;
            return crossSolved && pairSolved;
        }

        // Pruning:
        // Cross EP and EO
        if (edgePermutationDistanceTable[currentEdgePermState] > depthRemaining ||
                edgeOrientationDistanceTable[currentEdgeOrientState] > depthRemaining) {
            return false;
        }
        // XCross Pair (Corner + Edge)
        // The pruning table index for xcrossPairDistanceTable is combinedEdgeState * 24 + combinedCornerState
        int combinedPairState = currentXPairEdgeOrientState * N_PAIR_CORNERS_COMBINATIONS *N_ORIENTATIONS_PER_CORNER
                                + currentCornerOrientState;
        if (xcrossPairDistanceTable[f2lSlotIndex][combinedPairState] > depthRemaining) {
            return false;
        }

        for (int moveAxis = 0; moveAxis < 6; moveAxis++) {
            if (moveAxis != lastMoveAxis) {
                int nextCorner = currentCornerOrientState;
                int nextEP = currentEdgePermState;
                int nextEO = currentEdgeOrientState;
                int nextPairEdge = currentXPairEdgeOrientState;
                for (int turnCount = 0; turnCount < 3; turnCount++) {
                    nextCorner = xcrossCornerMoveTable[nextCorner][moveAxis];
                    nextPairEdge = xcrossEdgeMoveTable[nextPairEdge][moveAxis];
                    nextEP = edgePermutationMoveTable[nextEP][moveAxis];
                    nextEO = edgeOrientationMoveTable[nextEO][moveAxis];

                    if (searchForXCross(nextEP, nextEO, nextCorner, nextPairEdge, f2lSlotIndex, depthRemaining - 1, moveAxis)) {
                        solutionMoveSequence[depthRemaining] = moveAxis * 3 + turnCount;
                        //sb.insert(0, " " + turn[0][i] + suff[j]);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    /**
     * IDA* search for an XCross solution (Cross + First F2L pair).
     *
     * @param currentEdgePermState      Cross Edge Permutation state.
     * @param currentEdgeOrientState    Cross Edge Orientation state.
     * @param currentCornerState        XCross Corner state (perm+orient).
     * @param currentPairEdgeState      XCross Pair Edge state (perm+orient relative to cross pieces).
     * @param f2lSlotIndex              The target F2L slot for the pair (0-3).
     * @param depthRemaining            Remaining depth for the search.
     * @param lastMoveAxis              Axis of the last move.
     * @param solvedOnFaceIdx           The face index (0-5) on which the cross is defined as solved (usually D=0).
     * @param currentPathMoves          Array to build up the solution path.
     * @return True if an XCross solution is found.
     */
    private static void searchForXCross(int currentEdgePermState, int currentEdgeOrientState,
                                        int currentCornerState, int currentPairEdgeState,
                                        int f2lSlotIndex, int depthRemaining, int lastMoveAxis, int solvedOnFaceIdx, int[] currentPathMoves) {
        if (depthRemaining == 0) {
            if (currentEdgePermState == 1656 && currentEdgeOrientState == 1104 && currentCornerState == (f2lSlotIndex + 4) * 3 && currentPairEdgeState == f2lSlotIndex * 2) {
                StringBuilder solBuilder = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][solvedOnFaceIdx]);
                int qtm = 0;
                for (int i = currentPathMoves.length - 1; i > 0; i--) {
                    int moveCode = currentPathMoves[i];
                    solBuilder.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(moveCode / 3)).append(suff[moveCode % 3]);
                    qtm += (moveCode % 3 == 1) ? 2 : 1;
                }
                solBuilder.append("\t").append(currentPathMoves.length - 1).append("f, ").append(qtm).append("q");
                solutionsList.add(solBuilder.toString());
            }
            return;
        }
        if (edgePermutationDistanceTable[currentEdgePermState] > depthRemaining ||
                edgeOrientationDistanceTable[currentEdgeOrientState] > depthRemaining ||
                xcrossPairDistanceTable[f2lSlotIndex][currentPairEdgeState * 24 + currentCornerState] > depthRemaining) {
            return;
        }
        for (int moveAxis = 0; moveAxis < 6; moveAxis++) {
            if (moveAxis != lastMoveAxis && !(moveAxis / 2 == lastMoveAxis / 2 && moveAxis < lastMoveAxis)) {
                int nextCorner = currentCornerState;
                int nextEP = currentEdgePermState;
                int nextEO = currentEdgeOrientState;
                int nextPairEdge = currentPairEdgeState;

                for (int turnCount = 0; turnCount < 3; turnCount++) {
                    nextCorner = xcrossCornerMoveTable[nextCorner][moveAxis];
                    nextPairEdge = xcrossEdgeMoveTable[nextPairEdge][moveAxis];
                    nextEP = edgePermutationMoveTable[nextEP][moveAxis];
                    nextEO = edgeOrientationMoveTable[nextEO][moveAxis];

                    currentPathMoves[depthRemaining] = moveAxis * 3 + turnCount;
                    searchForXCross(nextEP, nextEO, nextCorner, nextPairEdge, f2lSlotIndex, depthRemaining - 1, moveAxis, solvedOnFaceIdx, currentPathMoves);
                }
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="EOFC (Edges Oriented First Cross) IDA* Search Algorithms">
    /**
     * IDA* search for an EOFC (Edges Oriented for First Cross) solution.
     * @return True if an EOFC solution is found.
     */
    private static boolean searchForEofcSolution(int currentEdgePermState, int currentEdgeOrientState,
                                                 int currentCrossEdgesInSliceOrientState,
                                                 int depthRemaining, int lastMoveAxis) {
        if (depthRemaining == 0) {
            return currentEdgePermState == 1656 && currentEdgeOrientState == 1104 && (currentCrossEdgesInSliceOrientState & 15) == 0;
        }
        if (edgePermutationDistanceTable[currentEdgePermState] > depthRemaining || edgeOrientationDistanceTable[currentEdgeOrientState] > depthRemaining || crossEdgesInSliceOrientationDistanceTable[currentCrossEdgesInSliceOrientState] > depthRemaining) return false;
        for (int i = 0; i < 6; i++)
            if (i != lastMoveAxis) {
                int epx = currentEdgePermState, eox = currentEdgeOrientState, eofx = currentCrossEdgesInSliceOrientState;
                for (int j = 0; j < 3; j++) {
                    epx = edgePermutationMoveTable[epx][i]; eox = edgeOrientationMoveTable[eox][i]; eofx = edgeOrientationMoveTable[eofx][i];
                    if (searchForEofcSolution(epx, eox, eofx, depthRemaining-1, i)) {
                        solutionMoveSequence[depthRemaining] = i * 3 + j;
                        return true;
                    }
                }
            }
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Public API Methods">
    /**
     * Solves the cross on specified faces for a given scramble.
     * @param scramble The scramble string.
     * @param targetFaces A bitmask indicating which faces to solve the cross on (0-5).
     * @return A string containing the solutions for the specified faces.
     */
    public static String solveCross(String scramble, int targetFaces) {
        initializeTables();
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < 6; i++)
            if (((targetFaces >> i) & 1) != 0) {
                sb.append("\nCross(").append(FACE_COLORS[i]).append("): ");
                sb.append(cross(scramble, 0, i));
            }
        return sb.toString();
    }
    /**
     * Solves the cross on all 6 faces for a given scramble.
     * @param scramble The scramble string.
     * @return A string containing the solutions for all faces.
     */
    public static String solveCross(String scramble) {
        initializeTables();
        //String[] s = scramble.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int face = 0; face < 6; face++) {
            sb.append(FACE_COLORS[face]).append(": ");
            sb.append(cross(scramble, 0, face)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Finds all cross solutions for all 6 faces for a given scramble.
     * @param scramble The scramble string.
     * @return A string containing all found solutions for each face.
     */
    public static String solveCrossf(String scramble) {
        initializeTables();
        String[] s = scramble.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int face = 0; face < 6; face++) {
            int ep = 1656, eo = 1104;
            for (int i = 0; i < s.length; i++)
                if (s[i].length() != 0) {
                    int m = MOVE_CHAR_MAP_PER_ORIENTATION[0][face].indexOf(s[i].charAt(0));
                    eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                    if (s[i].length() > 1) {
                        eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                        if (s[i].charAt(1) == '\'') {
                            eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                        }
                    }
                }
            solutionsList = new ArrayList<>();
            for (int d = 0; d < 9; d++) {
                int[] path = new int[d + 1];
                searchForCrossSolution(ep, eo, d, -1, face, path);
                if (solutionsList.size() > 0) {
                    sb.append(FACE_COLORS[face]).append(":\n");
                    for (String sol : solutionsList) {
                        int idx = sol.indexOf('\t');
                        sb.append("  ").append(sol.substring(0, idx)).append("\n");
                    }
                    sb.append("\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * Solves the XCross on specified faces for a given scramble.
     * @param scramble The scramble string.
     * @param targetFaces A bitmask indicating which faces to solve the XCross on.
     * @return A string containing the XCross solutions.
     */
    public static String solveXcross(String scramble, int targetFaces) {
        initializeTables();
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < 6; i++)
            if (((targetFaces >> i) & 1) != 0) {
                sb.append("\nXCross(").append(FACE_COLORS[i]).append("): ");
                sb.append(xcross(scramble, i));
            }
        return sb.toString();
    }

    /**
     * Solves the XCross on all 6 faces for a given scramble.
     * @param scramble The scramble string.
     * @return A string containing the XCross solutions for all faces.
     */
    public static String solveXcross(String scramble) {
        initializeTables();
        StringBuilder sb = new StringBuilder();
        for (int face = 0; face < 6; face++) {
            sb.append(FACE_COLORS[face]).append(": ");
            sb.append(xcross(scramble, face)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Finds all XCross solutions for all 6 faces for a given scramble.
     * @param scramble The scramble string.
     * @return A string containing all found XCross solutions for each face.
     */
    public static String solveXcrossf(String scramble) {
        initializeTables();
        String[] s = scramble.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int face = 0; face < 6; face++) {
            int[] co = new int[4], feo = new int[4];
            for (int i = 0; i < 4; i++) {
                co[i] = (i + 4) * 3;
                feo[i] = i * 2;
            }
            int ep = 1656, eo = 1104;
            for (int d = 0; d < s.length; d++)
                if (s[d].length() != 0) {
                    int m = MOVE_CHAR_MAP_PER_ORIENTATION[0][face].indexOf(s[d].charAt(0));
                    for (int i = 0; i < 4; i++) {
                        co[i] = xcrossCornerMoveTable[co[i]][m];
                        feo[i] = xcrossEdgeMoveTable[feo[i]][m];
                    }
                    ep = edgePermutationMoveTable[ep][m]; eo = edgeOrientationMoveTable[eo][m];
                    if (s[d].length() > 1) {
                        for (int i = 0; i < 4; i++) {
                            co[i] = xcrossCornerMoveTable[co[i]][m];
                            feo[i] = xcrossEdgeMoveTable[feo[i]][m];
                        }
                        eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                        if (s[d].charAt(1) == '\'') {
                            for (int i = 0; i < 4; i++) {
                                co[i] = xcrossCornerMoveTable[co[i]][m];
                                feo[i] = xcrossEdgeMoveTable[feo[i]][m];
                            }
                            eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                        }
                    }
                }
            solutionsList = new ArrayList<>();
            for (int d = 0; d < 11; d++) {
                for (int slot = 0; slot < 4; slot++) {
                    int[] path = new int[d + 1];
                    searchForXCross(ep, eo, co[slot], feo[slot], slot, d, -1, face, path);
                }
                if (solutionsList.size() > 0) {
                    sb.append(FACE_COLORS[face]).append(":\n");
                    for (String sol : solutionsList) {
                        int idx = sol.indexOf('\t');
                        sb.append("  ").append(sol.substring(0, idx)).append("\n");
                    }
                    sb.append("\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * Solves EOFC for specified sides.
     * @param scramble The scramble string.
     * @param sides Bitmask for sides (0-11, relating to EOFC_SIDE_STRINGS).
     * @return String with EOFC solutions.
     */
    public static String solveEofc(String scramble, int sides) {
        initializeTables();
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < 6; i++) {
            if (((sides >> i) & 1) != 0)
                sb.append(eofc(scramble, i * 2)).append(eofc(scramble, i * 2 + 1));
        }
        return sb.toString();
    }

    /**
     * Solves EOFC for a single specified side.
     * @param scramble The scramble string.
     * @param side The specific side index (0-11) to solve for.
     * @return String with the EOFC solution.
     */
    public static String eofc(String scramble, int side) {
        String[] s = scramble.split(" ");
        int ep = 1656, eo = 1104, eof = 0;
        for (int i = 0; i < s.length; i++)
            if (s[i].length() != 0) {
                int m = EOline.moveStringPerOrientation[side].indexOf(s[i].charAt(0));
                eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m]; eof = edgeOrientationMoveTable[eof][m];
                if (s[i].length() > 1) {
                    eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m]; eof = edgeOrientationMoveTable[eof][m];
                    if (s[i].charAt(1) == '\'') {
                        eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m]; eof = edgeOrientationMoveTable[eof][m];
                    }
                }
            }
        for (int d = 0; d < 13; d++) {
            //Log.w("dct", ""+d);
            if (searchForEofcSolution(ep, eo, eof, d, -1)) {
                StringBuilder sb = new StringBuilder("\n");
                sb.append(EOFC_SIDE_STRINGS[side]).append(": ").append(EOline.cubeRotationForOrientation[side]);
                for (int i = d; i > 0; i--)
                    sb.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(solutionMoveSequence[i] / 3)).append(suff[solutionMoveSequence[i] % 3]);
                return sb.toString();
            }
        }
        return "\nerror";
    }

    /**
     * Generates a random "easy" cross scramble, meaning the cross can be solved in 'maxDepth' moves.
     * @param maxDepth The maximum number of moves allowed for the cross solution.
     * @return A 2D array representing the cube state: [0] for piece positions, [1] for orientations.
     */
    public static int[][] easyCross(int maxDepth) {
        if (!isEasyCrossInitialized) {
            initializeTables();
            long t = System.currentTimeMillis();
            for (int i = 0; i < 23760; i++) combinedEdgeDistanceTable[i] = -1;
            setPruning(combinedEdgeDistanceTable, 494 * 384, 0);
            int c = 1;
            for (int d = 0; d < 8; d++) {
                // c=0;
                for (int i = 0; i < 190080; i++)
                    if (getPruning(combinedEdgeDistanceTable, i) == d)
                        for (int m = 0; m < 6; m++) {
                            int x = i;
                            for (int n = 0; n < 3; n++) {
                                int p = edgePermutationMoveTable[x >> 4][m];
                                int o = edgeOrientationMoveTable[x / 384 << 4 | (x & 15)][m];
                                x = p << 4 | (o & 15);
                                if (getPruning(combinedEdgeDistanceTable, x) == 0xf) {
                                    setPruning(combinedEdgeDistanceTable, x, d + 1);
                                    c++;
                                }
                            }
                        }
                Log.w("dct", d+1+"\t"+c);
            }
            t = System.currentTimeMillis() - t;
            //Log.w("dct", t+"ms init");
            isEasyCrossInitialized = true;
        }
        Random r = new Random();
        int i;// = r.nextInt(190080);
        if (maxDepth == 0) i = 494 * 384;
        else do {
            i = r.nextInt(190080);
        } while (getPruning(combinedEdgeDistanceTable, i) > maxDepth);
        int comb = i / 384;
        int perm = (i >> 4) % 24;
        int ori = i & 15;
        int[] c = new int[12];
        int[] p = new int[4];
        Utils.idxToPerm(p, perm, 4, false);
        mapCrossPiecesInEdgeSlots(c, p, comb, ori, new int[] {3, 2, 1, 0});
        int[][] arr = new int[2][12];
        for (i = 0; i < 12; i++) {
            if (c[i] < 0)
                arr[0][i] = arr[1][i] = -1;
            else {
                arr[0][i] = c[i] >> 1;
                arr[1][i] = c[i] & 1;
            }
        }
        return arr;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Helper Methods - State Calculation & Application">

    /**
     * Calculates the new coordinates of a state after applying a single move.
     * <p>
     * This function is the core of building "move tables". It decodes a state
     * from its compact coordinates, simulates a move, and then re-encodes the
     * new state into a single integer representing the new coordinates.
     *
     * @param initialCombinationIdx C(12,4) Combination index of the 4 cross edges.
     * @param initialPermOrientIdx  Index (0-23) that implies both permutation of the 4 edges
     *                              AND their orientation (lower 4 bits usually).
     * @param moveIndex             The index of the move to apply (0-5, for one of the cube faces).
     * @return An integer packing the new combination, permutation, and orientation.
     */

    private static int calculateNextEdgeStateAfterMove (int initialCombinationIdx, int initialPermOrientIdx,
                                                       int moveIndex) {
        // getMV
        // --- 1. DECODING: Convert coordinates into a physical state ---

        // Temporary arrays to represent the "unfolded" state of the cube's edges.
        int[] edgeSlotArray = new int[N_EDGES];     // Represents the 12 physical edge slots.
        int [] crossPiecePermutation = new int[N_CROSS_EDGES]; // Represents the permutation of the 4 cross pieces.

        // Decode the permutation/orientation index to fill the `piecePermutation` array.
        Utils.idxToPerm(crossPiecePermutation, initialPermOrientIdx, N_CROSS_EDGES, false); // This gives a permutation of {0,1,2,3}

        // Place the 4 pieces into the 12 slots using the combination index.
        mapCrossPiecesInEdgeSlots(edgeSlotArray, crossPiecePermutation, initialCombinationIdx, initialPermOrientIdx);

        // --- 2. ACTION: Apply the move ---
        // Simulate the physical face turn on the array representing the edges.
        applyMoveToEdgeArray(edgeSlotArray, moveIndex);

        // --- 3. ENCODING: Convert the new physical state back into coordinates ---

        // Reset the coordinate variables to rebuild the new indices from scratch.
        int newCombinationIdx = 0;
        int newPermOrientIdx = 0;

        // A counter for the 4 pieces we need to find in the updated array.
        int piecesToFind = N_CROSS_EDGES;

        // Scan all 12 slots to find the new positions of the cross pieces.
        for (int edgeSlot = 0; edgeSlot < N_EDGES; edgeSlot++)
            // A slot >= 0 contains one of our pieces (empty slots are -1).
            if (edgeSlotArray[edgeSlot] >= 0) {
                // Rebuild the combination index using the new position of the pieces.
                newCombinationIdx += Cnk[N_EDGES - 1 - edgeSlot][piecesToFind--];

                // Extract the piece's identity (its place in the new permutation).
                crossPiecePermutation[piecesToFind] = edgeSlotArray[edgeSlot] >> 1;

                // Extract the orientation bit and pack it into the new orientation integer.
                newPermOrientIdx |= (edgeSlotArray[edgeSlot] & 1) << N_CROSS_EDGES - 1 - piecesToFind;
            }
        // --- 4. FINALIZATION: Pack and return the new coordinates ---

        // Convert the new permutation array (`piecePermutation`) back into a compact index.
        int newPermutationIdx = Utils.permToIdx(crossPiecePermutation, N_CROSS_EDGES, false); //permToIdx(pm);

        // Combine the three new indices (combination, permutation, orientation) into a single integer and return it.
        return (N_CROSS_EDGES_PERMUTATIONS * newCombinationIdx + newPermutationIdx) << N_CROSS_EDGES | newPermOrientIdx;
    }

     /**
     * Applies a physical turn to an array representing 12 edge slots (piece index and orientation).
     *
     * @param edgeStates Array of 12 integers. Each int is (edge_piece_id << 1 | orientation_bit).
     *                   -1 if slot is empty or not one of the tracked edges.
     * @param moveIndex  The move to apply (0:U, 1:D, 2:R, 3:L, 4:F, 5:B - internal order).
     */
    static void applyMoveToEdgeArray(int[] edgeStates, int moveIndex) {
        // edgemv
        switch (moveIndex) {
            case 0: cycleEdges(edgeStates, 0,  1, 2,  3, 0); break; // D face
            case 1: cycleEdges(edgeStates, 4,  7, 6,  5, 0); break; // U face
            case 2: cycleEdges(edgeStates, 2,  9, 6, 10, 0); break; // L face
            case 3: cycleEdges(edgeStates, 0, 11, 4,  8, 0); break; // R face
            case 4: cycleEdges(edgeStates, 1,  8, 5,  9, 1); break; // F face (flips)
            case 5: cycleEdges(edgeStates, 3, 10, 7, 11, 1); break; // B face (flips)
        }
    }
    /*                    +---+---+---+
                          |   | 7 |   |
                          +---+---+---+
                          | 4 | U | 6 |
                          +---+---+---+
                          |   | 5 |   |
                          +---+---+---+
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |   | 4 |   | |   | 5 |   | |   | 6 |   | |   | 7 |   |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |11 | L | 8 | | 8 | F | 9 | | 9 | R |10 | |10 | B |11 |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
            |   | 0 |   | |   | 1 |   | |   | 2 |   | |   | 3 |   |
            +---+---+---+ +---+---+---+ +---+---+---+ +---+---+---+
                          +---+---+---+
                          |   | 1 |   |
                          +---+---+---+
                          | 0 | D | 2 |
                          +---+---+---+
                          |   | 3 |   |
                          +---+---+---+
    */


    /**
     * Helper function to perform a 4-cycle on an array with optional orientation flip.
     * @param edgeStates The array to modify.
     * @param edge1 Index of the first piece.
     * @param edge2 Index of the second piece.
     * @param edge3 Index of the third piece.
     * @param edge4 Index of the fourth piece.
     * @param orientationFlip Value to XOR with orientation (0 = no flip, 1 = flip).
     */
    public static void cycleEdges(int[] edgeStates, int edge1, int edge2, int edge3, int edge4, int orientationFlip) {
        // ... (Logic from original circle)

        int temp = edgeStates[edge1];
        edgeStates[edge1] = edgeStates[edge4] ^ orientationFlip; // XOR with orientation flip. orientationFlip can be 0 or 1
        edgeStates[edge4] = edgeStates[edge3] ^ orientationFlip; // orientationFlip = 0 => no flip, 1 => flip
        edgeStates[edge3] = edgeStates[edge2] ^ orientationFlip;
        edgeStates[edge2] = temp ^ orientationFlip;
    }

    /**
     * Decodes a combination index to place piece identities and orientations into a slot array.
     * This function takes a compact combination index and reconstructs the physical layout of
     * pieces in their slots. It determines which of the 12 slots are occupied by the 4 chosen pieces.

     * @param edgeSlotsArray     Output array [12] to store edge states (piece_id << 1 | orient_bit).
     * @param piecePermutation Array [4] of the actual piece IDs (0-11) for the 4 chosen edges.
     * @param combinationIndex    The C(12,4) index for choosing 4 edges.
     * @param orientationBits     4 LSBs represent orientation of the 4 permutedPieceIndices.
     */
    private static void mapCrossPiecesInEdgeSlots(int[] edgeSlotsArray, int[] piecePermutation,
                                                  int combinationIndex, int orientationBits) {
        // idxToComb
        // Number of pieces we still need to place. Starts at 4 and decrements.
        int piecesToPlace = N_CROSS_EDGES;

        // Iterate through all 12 physical edge slots
        for (int edgeSlot = 0; edgeSlot < N_EDGES; edgeSlot++) {
            // Cnk is a pre-calculated table of C(n, k), or "n choose k".
            // Cnk[11 - slot][piecesToPlace] tells us how many combinations can be formed
            // using the remaining slots if we *skip* the current one.

            // --- Core Logic ---
            // We check if our combinationIndex is large enough to force us to place a piece here.
            if (combinationIndex >= Cnk[N_EDGES - 1 - edgeSlot][piecesToPlace]) {
                // If the index is greater or equal, it means our target combination is not one of
                // the combinations that can be formed by skipping this slot. Therefore,
                // a piece *must* be placed in this slot.

                // We subtract this block of "skipped" combinations from our index to narrow down
                // the search space for the subsequent pieces.
                combinationIndex -= Cnk[N_EDGES - 1 - edgeSlot][piecesToPlace];

                // Get the identity for the current piece being placed.
                // Since piecesToPlace decrements from 4, we use (piecesToPlace - 1)
                // to correctly index the piecePermutation array from 3 down to 0.
                int pieceId = piecePermutation[piecesToPlace - 1];

                // Get the orientation for this specific piece (the least significant bit).
                int orientation = orientationBits & 1;

                // Pack the piece ID and its orientation into a single integer and place it.
                // (This is equivalent to: pieceId * 2 + orientation)
                edgeSlotsArray[edgeSlot] = (pieceId << 1) | orientation;

                // Discard the orientation bit we just used by shifting all bits to the right.
                orientationBits >>= 1;

                // We have successfully placed a piece, so decrement the count of remaining pieces.
                piecesToPlace--;
            } else {
                // If the index is smaller, it means our combination can be formed using only the
                // slots that come after this one. Therefore, this slot must be empty.
                edgeSlotsArray[edgeSlot] = -1;
            }
        }
    }

    /**
     * Decodes a combination index to place mapped pieces into a slot array.
     * This version translates local piece IDs to global IDs using a mapping table
     * before placing them.
     *
     * @param edgeSlotsArray            The main array of 12 slots to be filled (-1 for empty).
     * @param localPiecePermutation     An array of the 4 local piece identities (e.g., {0,1,2,3}).
     * @param combinationIndex          The unique index representing which 4 of the 12 slots are chosen.
     * @param orientationBits           A packed integer with the orientation for each piece.
     * @param pieceIdMap                A mapping array to translate local piece IDs to global IDs.
     */
    private static void mapCrossPiecesInEdgeSlots(int[] edgeSlotsArray, int[] localPiecePermutation,
                                                  int combinationIndex, int orientationBits, int[] pieceIdMap) {
        // idxToComb
        int piecesToPlace = N_CROSS_EDGES; // Counter for remaining pieces

        // Iterate through all 12 slots to determine which are occupied.
        for (int edgeSlot = 0; edgeSlot < N_EDGES; edgeSlot++) { // Iterate through all 12 physical edge slots
            // Check if the current edgeSlotOnCube is part of the chosen C(12,k) combination
            if (combinationIndex >= Cnk[N_EDGES - 1 - edgeSlot][piecesToPlace]) {

                // This slot is occupied. Update index for subsequent choices.
                combinationIndex -= Cnk[N_EDGES - 1 - edgeSlot][piecesToPlace];

                // Get the local ID of the piece to place (e.g., 0, 1, 2, or 3).
                int localPieceId = localPiecePermutation[piecesToPlace - 1];

                // **Translate the local ID to its global ID using the map.**
                int globalPieceId = pieceIdMap[localPieceId];

                // Get the orientation for this piece.
                int orientation = orientationBits & 1;

                // Place the mapped piece's global ID and orientation in the slot.
                edgeSlotsArray[edgeSlot] = (globalPieceId << 1) | orientation;

                // Decrement counters for the next iteration.
                orientationBits >>= 1;
                piecesToPlace--;

            } else {
                // This slot is empty.
                edgeSlotsArray[edgeSlot] = -1;
            }
        }
    }

    //</editor-fold>

    //<editor-fold desc="Internal Helper Methods - Scramble Parsing ">
    private static String cross(String scramble, int side, int face) {
        String[] s = scramble.split(" ");
        int ep = 1656, eo = 1104;
        for (int i = 0; i < s.length; i++)
            if (s[i].length() != 0) {
                int m = MOVE_CHAR_MAP_PER_ORIENTATION[side][face].indexOf(s[i].charAt(0));
                eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                if (s[i].length() > 1) {
                    eo = edgeOrientationMoveTable[eo][m];
                    ep = edgePermutationMoveTable[ep][m];
                    if (s[i].charAt(1) == '\'') {
                        eo = edgeOrientationMoveTable[eo][m];
                        ep = edgePermutationMoveTable[ep][m];
                    }
                }
            }
        //sb = new StringBuilder();
        for (int d = 0; d < 9; d++) {
            if (searchForCrossSolution(ep, eo, d, -1)) {
                StringBuilder sb = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[side][face]);
                for (int i = d; i > 0; i--)
                    sb.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][side].charAt(solutionMoveSequence[i] / 3)).append(suff[solutionMoveSequence[i] % 3]);
                return sb.toString();
            }
        }
        return "error";
    }

    private static String xcross(String scramble, int face) {
        String[] s = scramble.split(" ");
        int[] co = new int[4], feo = new int[4];
        for (int i = 0; i < 4; i++) {
            co[i] = (i + 4) * 3;
            feo[i] = i * 2;
        }
        int ep = 1656, eo = 1104;
        for (int d = 0; d < s.length; d++)
            if (s[d].length() != 0) {
                int m = MOVE_CHAR_MAP_PER_ORIENTATION[0][face].indexOf(s[d].charAt(0));
                for (int i = 0; i < 4; i++) {
                    co[i] = xcrossCornerMoveTable[co[i]][m];
                    feo[i] = xcrossEdgeMoveTable[feo[i]][m];
                }
                ep = edgePermutationMoveTable[ep][m]; eo = edgeOrientationMoveTable[eo][m];
                if (s[d].length() > 1) {
                    for (int i = 0; i < 4; i++) {
                        co[i] = xcrossCornerMoveTable[co[i]][m];
                        feo[i] = xcrossEdgeMoveTable[feo[i]][m];
                    }
                    eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                    if (s[d].charAt(1) == '\'') {
                        for (int i = 0; i < 4; i++) {
                            co[i] = xcrossCornerMoveTable[co[i]][m];
                            feo[i] = xcrossEdgeMoveTable[feo[i]][m];
                        }
                        eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                    }
                }
            }
        for (int d = 0; d < 11; d++)
            for (int slot = 0; slot < 4; slot++)
                if (searchForXCross(ep, eo, co[slot], feo[slot], slot, d, -1)) {
                    StringBuilder sb = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][face]);
                    for (int i = d; i > 0; i--)
                        sb.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(solutionMoveSequence[i] / 3)).append(suff[solutionMoveSequence[i] % 3]);
                    return sb.toString();
                }
        return "error";
    }

    //</editor-fold>
}
