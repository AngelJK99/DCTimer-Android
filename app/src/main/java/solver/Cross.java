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
    private static final int NUM_CORNERS = 8;
    private static final int NUM_EDGES = 12;
    private static final int NUM_ORIENTATIONS_PER_EDGE = 2;
    private static final int NUM_ORIENTATIONS_PER_CORNER = 3;


    private static final int NUM_CROSS_EDGES_COMBINATIONS = 495; // C(12,4)
    private static final int NUM_CROSS_EDGES_PERMUTATIONS = 24; // 4!
    private static final int NUM_CROSS_EDGES_ORIENTATIONS = (int) Math.pow(NUM_ORIENTATIONS_PER_EDGE,4); // 2^4

    private static final int NUM_EASYCROSS_EDGES_ORIENTATIONS = NUM_ORIENTATIONS_PER_EDGE; // 1*1*1*2

    private static final int NUM_PAIR_CORNERS_COMBINATIONS = NUM_CORNERS;   // C(8,1)
    private static final int NUM_PAIR_CORNERS_PERMUTATIONS = 1;   // 1

    private static final int NUM_PAIR_EDGES_COMBINATIONS = NUM_EDGES;   // C(12,1)
    private static final int NUM_PAIR_EDGES_PERMUTATIONS = 1;   // 1

    private static final int NUM_F2L_SLOTS = 4;
    private static final int NUM_XCROSS_PAIR_STATES = NUM_PAIR_CORNERS_COMBINATIONS * NUM_ORIENTATIONS_PER_CORNER *
            NUM_PAIR_EDGES_COMBINATIONS * NUM_ORIENTATIONS_PER_EDGE; // 576
    private static final int NUM_CROSS_EDGES = 4;


    private static final int NUM_MAX_TURN = 3; // 0 = clockwise, 1 = double turn, 2 = counterclockwise
    private static final int NUM_FACES = 6; // D U L R F B

    private static final int SOLVED_CROSS_PERMUTATION = 1656; // Coordinate for the 4 cross edges being in their correct slots.
    private static final int SOLVED_CROSS_ORIENTATION = 1104; // Coordinate for the 4 cross edges being correctly oriented.
    private static final int ALL_EDGES_ORIENTED_FLAG = 0; // The orientation bits in this coordinate must be zero.
    private static final int INITIAL_LAST_FACE = -1; // A sentinel value for the first recursive call

    // --- Pruning and Move Tables ---
    // Edge Permutation Move Table: Stores the next edge permutation state (combination + permutation index for 4 edges)
    /** Edge Permutation Move Table: [currentState][move] -> newState */
    private static short[][] crossEdgePermutationMoveTable = new short[NUM_CROSS_EDGES_COMBINATIONS *
            NUM_CROSS_EDGES_PERMUTATIONS][NUM_FACES]; // 11 880 X 6

    // Edge Orientation Move Table (for the 4 cross edges)
    /** Edge Orientation Move Table: [currentState][move] -> newState */
    private static short[][] crossEdgeOrientationMoveTable = new short[NUM_CROSS_EDGES_COMBINATIONS *
            NUM_CROSS_EDGES_ORIENTATIONS][NUM_FACES]; // 7920 x 6

    // --- Pruning Tables (Distance Heuristics) ---
    // Distance to solve edge permutation for the 4 cross edges.
    /** Edge Permutation Distance Table (Pruning Table): [state] -> minMovesToSolved */
    private static byte[] crossEdgePermutationDistanceTable = new byte[NUM_CROSS_EDGES_COMBINATIONS *
            NUM_CROSS_EDGES_PERMUTATIONS]; //  11880

    // Distance to solve edge orientation for the 4 cross edges.
    /** Edge Orientation Distance Table (Pruning Table): [state] -> minMovesToSolved */
    private static byte[] crossEdgeOrientationDistanceTable = new byte[NUM_CROSS_EDGES_COMBINATIONS *
            NUM_CROSS_EDGES_ORIENTATIONS]; // 7920
    /**
     * Edge Orientation Distance Table for EOFC (Edges Oriented for First Cross).
     * This table helps prune states where cross edges are oriented but not necessarily permuted.
     * [state] -> minMovesToCrossEdgesOriented
     */
    private static byte[] crossEdgesOrientFlipDistanceTable = new byte[NUM_CROSS_EDGES_COMBINATIONS *
            NUM_CROSS_EDGES_ORIENTATIONS]; // 7920
    /**
     * Combined Edge State Distance Table (used for easyCross generation).
     * Stores distance for a combined state of permutation and orientation of 4 edges.
     * Indexing: (combinationIndex * 384) + (permutationIndex << 4) | orientationIndex
     */
    private static int[] combinedEdgeDistanceTable = new int[NUM_CROSS_EDGES_COMBINATIONS
            * NUM_CROSS_EDGES_PERMUTATIONS * NUM_EASYCROSS_EDGES_ORIENTATIONS]; // 23 760

    // --- Move Tables for XCross (Corner-Edge Pair) ---
    // Corner Permutation/Orientation Move Table for the first pair.
    /** XCross Corner Move Table: [currentState][move] -> newState */
    private static byte[][] xcrossCornerMoveTable = new byte[NUM_PAIR_CORNERS_COMBINATIONS *
            NUM_PAIR_CORNERS_PERMUTATIONS * NUM_ORIENTATIONS_PER_CORNER][NUM_FACES]; // 24 x 6

    // Edge Permutation/Orientation Move Table for the first pair's edge relative to the cross.
    /** XCross Pair Edge Move Table: [currentState][move] -> newState (edge relative to cross) */
    private static byte[][] xcrossEdgeMoveTable = new byte[NUM_PAIR_EDGES_COMBINATIONS *
            NUM_PAIR_EDGES_PERMUTATIONS * NUM_ORIENTATIONS_PER_EDGE][NUM_FACES];  // 24 x 6

    // --- Pruning Table for XCross ---
    /**
     * XCross Pair Distance Table (Pruning Table for F2L pair).
     * [f2lSlotIndex][combinedEdgeState * 24 + combinedCornerState] -> minMovesToSolvedPair
     * This table stores the minimum moves to solve a specific F2L pair (corner + edge)
     * for each of the 4 F2L slots.
     */
    private static byte[][] xcrossPairDistanceTable = new byte[NUM_F2L_SLOTS][NUM_PAIR_EDGES_COMBINATIONS *
            NUM_PAIR_EDGES_PERMUTATIONS * NUM_ORIENTATIONS_PER_EDGE * NUM_PAIR_CORNERS_COMBINATIONS *
            NUM_PAIR_CORNERS_PERMUTATIONS * NUM_ORIENTATIONS_PER_CORNER]; // 4 x 576


    // --- Solution Storage & State ---
    /** Stores the sequence of moves for the current best solution found by IDA*. */
    private static int[] solutionMoveSequence = new int[20]; // Max depth for cross/xcross
    /** List to store multiple solutions when searching (e.g., for `solveCrossf`). */
    private static ArrayList<String> solutions; // To store multiple solutions
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
            { "UDLRFB", "DURLFB", "RLUDFB", "LRDUFB", "BFLRUD", "FBLRDU" }, // Solving D-face, target D, U, L, R, F, B
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
     * Example: `ROTATION_TO_STANDARD_ORIENTATION_MAP[0][4]` (solving on D to solving on F in D position) would be "x'".
     */
    private static String[][] ROTATION_TO_STANDARD_ORIENTATION_MAP = {
            { "", "z2", "z'", "z", "x'", "x" },     // Solving D-face, target D, U, L, R, F, B
            { "z2", "", "z", "z'", "x", "x'" },     // Solving U-face, target D, U, L, R, F, B
            { "z", "z'", "", "z2", "y", "y'" },     // Solving L-face, target D, U, L, R, F, B
            { "z'", "z", "z2", "", "y'", "y" },     // Solving R-face, target D, U, L, R, F, B
            { "x", "x'", "y'", "y", "", "y2" },     // Solving F-face, target D, U, L, R, F, B
            { "x'", "x", "y", "y'", "y2", "" }      // Solving B-face, target D, U, L, R, F, B
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

        for (int combinationIdx = 0; combinationIdx < NUM_CROSS_EDGES_COMBINATIONS; combinationIdx++) {
            for (int permOrientIdx = 0; permOrientIdx < NUM_CROSS_EDGES_PERMUTATIONS; permOrientIdx++) {
                for (int move = 0; move < NUM_FACES; move++) {
                    // Calculate the resulting state after applying the move
                    // high bits for new combination & permutation, low bits for new orientation
                    int newPackedCoord = calculateNextEdgeStateAfterMove(combinationIdx, permOrientIdx, move);

                    // Unpack and store the new permutation coordinate.
                    int combinedPermCoord = NUM_CROSS_EDGES_PERMUTATIONS * combinationIdx
                            + permOrientIdx;
                    crossEdgePermutationMoveTable[combinedPermCoord][move] = (short) (newPackedCoord >> 4);

                    // Unpack and store the new orientation coordinate (if valid).
                    if (permOrientIdx < NUM_CROSS_EDGES_ORIENTATIONS) { // Check if this permOrientIdx is valid for orientation part
                        int combinedOrientCoord = NUM_CROSS_EDGES_ORIENTATIONS * combinationIdx + permOrientIdx;
                        int newCombination = newPackedCoord / (NUM_CROSS_EDGES_PERMUTATIONS *
                                NUM_CROSS_EDGES_ORIENTATIONS);
                        int newOrientation = newPackedCoord & 15;
                        crossEdgeOrientationMoveTable[combinedOrientCoord][move] = (short) ((newCombination << 4) | newOrientation);
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
        for (int i = 0; i < NUM_CROSS_EDGES_COMBINATIONS * NUM_CROSS_EDGES_PERMUTATIONS; i++) {
            crossEdgePermutationDistanceTable[i] = -1;
        }
        crossEdgePermutationDistanceTable[SOLVED_CROSS_PERMUTATION] = 0;  // Solved state for cross edges
        Utils.createPrun(crossEdgePermutationDistanceTable, 6, crossEdgePermutationMoveTable, 3); // Max depth 6-7 for EP

        // --- Orientation Pruning Table ---
        for (int i = 0; i < NUM_CROSS_EDGES_COMBINATIONS * NUM_CROSS_EDGES_ORIENTATIONS; i++) {
            crossEdgeOrientationDistanceTable[i] = crossEdgesOrientFlipDistanceTable[i] = -1;
        }
        crossEdgeOrientationDistanceTable[SOLVED_CROSS_ORIENTATION] = 0; // Solved state 69 * 16
        Utils.createPrun(crossEdgeOrientationDistanceTable, 7, crossEdgeOrientationMoveTable, 3);

        // For any combination of 4 edges, if their orientation bits are all 0, it's a solved orientation state *for that combination*.
        // This is used for EO-first approaches where edges are first put in slice, then oriented.
        for (int combinationIdx = 0; combinationIdx < NUM_CROSS_EDGES_COMBINATIONS; combinationIdx++) {
            crossEdgesOrientFlipDistanceTable[combinationIdx << 4] = 0; // combination * 16 + 0 orientation bits
        }
        Utils.createPrun(crossEdgesOrientFlipDistanceTable, 4, crossEdgeOrientationMoveTable, 3);

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
        for (int corner = 0; corner < NUM_CORNERS; corner++) {
            for (int orientation = 0; orientation < NUM_ORIENTATIONS_PER_CORNER; orientation++) {
                for (int move = 0; move < NUM_FACES; move++) {
                    xcrossCornerMoveTable[corner * NUM_ORIENTATIONS_PER_CORNER + orientation][move] =
                            (byte) (cornerPermutations[corner][move] * NUM_ORIENTATIONS_PER_CORNER +
                                    (cornerOrientationChanges[corner][move] + orientation) % NUM_ORIENTATIONS_PER_CORNER
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
        for (int edge = 0; edge < NUM_EDGES; edge++) {
            for (int orientation = 0; orientation < NUM_ORIENTATIONS_PER_EDGE; orientation++) {
                for (int move = 0; move < NUM_FACES; move++) {
                    xcrossEdgeMoveTable[edge * NUM_ORIENTATIONS_PER_EDGE + orientation][move] = (byte) (
                            pairEdgePermutations[edge][move] * NUM_ORIENTATIONS_PER_EDGE +
                                    (pairEdgeOrientations[edge][move] ^ orientation)
                    );
                }
            }
        }

        // --- X-Cross Pruning Table (`fecd`) ---
        for (int f2lSlotIndex = 0; f2lSlotIndex < NUM_F2L_SLOTS; f2lSlotIndex++) {
            for (int i = 0; i < NUM_XCROSS_PAIR_STATES; i++)
                xcrossPairDistanceTable[f2lSlotIndex][i] = -1;

            // Solved state for this F2L slot (corner & edge correctly placed and oriented)
            // Consistent with idaxcross: corner=(slot+4)*3, edge=slot*2
            int solvedCornerState = (f2lSlotIndex + NUM_F2L_SLOTS) * NUM_ORIENTATIONS_PER_CORNER;
            int solvedPairEdgeState = f2lSlotIndex * NUM_ORIENTATIONS_PER_EDGE;

            // Set distance 0 for the 4 possible solved F2L pair states.
            xcrossPairDistanceTable[f2lSlotIndex][solvedPairEdgeState * NUM_CROSS_EDGES_PERMUTATIONS + solvedCornerState] = 0;

            // Populate the pruning table using a Breadth-First Search (BFS) pattern.
            for (int distance = 0; distance < 6; distance++) {
                int statesAtThisDistance = 0;
                for (int combinedStateIdx = 0; combinedStateIdx < NUM_XCROSS_PAIR_STATES; combinedStateIdx++) {
                    if (xcrossPairDistanceTable[f2lSlotIndex][combinedStateIdx] == distance) {
                        for (int moveAxis = 0; moveAxis < NUM_FACES; moveAxis++) {

                            for (int nextCombinedState = combinedStateIdx, turnCount = 0; turnCount < NUM_MAX_TURN; turnCount++) {
                                // Apply move to get new state
                                int cornerState  = nextCombinedState % NUM_CROSS_EDGES_PERMUTATIONS;
                                int edgeState  = nextCombinedState / NUM_CROSS_EDGES_PERMUTATIONS;
                                int nextCornerState = xcrossCornerMoveTable[cornerState][moveAxis];
                                int nextEdgeState = xcrossEdgeMoveTable[edgeState][moveAxis];
                                nextCombinedState = nextEdgeState * NUM_CROSS_EDGES_PERMUTATIONS + nextCornerState;

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
            return currentEdgePermutation == SOLVED_CROSS_PERMUTATION &&
                    currentEdgeOrientation == SOLVED_CROSS_ORIENTATION;
        }

        // Heuristic Pruning: Check if it's even possible to solve in the remaining depth.
        // `epd` and `eod` are pre-calculated tables (pruning tables) that store the minimum
        // number of moves required to solve the permutation/orientation from any state.
        // If the required moves are more than the depth we have left, this path is a dead end.
        if (crossEdgePermutationDistanceTable[currentEdgePermutation] > depthRemaining ||
                crossEdgeOrientationDistanceTable[currentEdgeOrientation] > depthRemaining)
            return false;

        // --- Recursive Step: Explore all possible next moves ---
        // Iterate through the 6 faces of the cube (U, D, R, L, F, B).
        for (int moveAxis = 0; moveAxis < NUM_FACES; moveAxis++)
            if (moveAxis != lastMoveAxis) {
                // Create temporary variables for the next state's coordinates.
                int nextEdgePermutation = currentEdgePermutation;
                int nextEdgeOrientation = currentEdgeOrientation;

                // Iterate through the 3 possible turns for the current face (e.g., U, U2, U').
                for (int turnCount = 0; turnCount < NUM_MAX_TURN; turnCount++) {

                    // Get the next state's coordinates from pre-calculated move tables.
                    // `epm` and `eom` are tables where moveTable[currentState][move] = nextState.
                    nextEdgePermutation = crossEdgePermutationMoveTable[nextEdgePermutation][moveAxis];
                    nextEdgeOrientation = crossEdgeOrientationMoveTable[nextEdgeOrientation][moveAxis];

                    // Recursively call the function for the new state with one less depth.
                    if (searchForCrossSolution(
                            nextEdgePermutation,
                            nextEdgeOrientation,
                            depthRemaining - 1,
                            moveAxis
                    )) {
                        // --- Solution Found ---
                        // If the recursive call returns true, it means we found a path to the solution.
                        // We record the successful move in our solution sequence array.
                        solutionMoveSequence[depthRemaining] = moveAxis * NUM_MAX_TURN + turnCount;
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
            if (currentEdgePermutation == SOLVED_CROSS_PERMUTATION &&
                    currentEdgeOrientation == SOLVED_CROSS_ORIENTATION) {

                // --- Solution Found: Format and store it ---
                StringBuilder solutionString = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][solvedOnFaceIdx]);
                int quarterTurnMove = 0; // Move count where any turn (e.g., R2) is 2 moves.

                // Reconstruct the solution string from the path taken.
                for (int i = currentPathMoves.length - 1; i > 0; i--) {
                    int moveCode = currentPathMoves[i];
                    int moveAxis = moveCode / 3;
                    int turnType = moveCode % 3; // 0=', 1=2, 2='

                    solutionString.append(' ')
                            .append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(moveAxis))
                            .append(suff[moveCode % 3]);
                    quarterTurnMove += (turnType == 1) ? 2 : 1; // A '2' turn (like U2) counts as 2 quarter turns.
                }

                // Append move counts to the string (face turn metric 'f' and quarter turn metric 'q').
                solutionString.append("\t")
                        .append(currentPathMoves.length - 1)
                        .append("f, ")
                        .append(quarterTurnMove)
                        .append("q");
                solutions.add(solutionString.toString());
            }
            return; // End this path
        }

        // --- Heuristic Pruning ---
        // If the minimum moves to solve from here is greater than the depth we have left,
        // this path is a dead end. Stop searching it.
        if (crossEdgePermutationDistanceTable[currentEdgePermutation] > depthRemaining ||
                crossEdgeOrientationDistanceTable[currentEdgeOrientation] > depthRemaining) {
            return; // Prune
        }

        // --- Recursive Step: Explore all valid next moves ---
        for (int moveAxis = 0; moveAxis < NUM_FACES; moveAxis++)
            // Optimization: Prune redundant move sequences.
            // 1. Don't turn the same face twice in a row (e.g., U U').
            // 2. Don't turn an opposite face if it could have been done earlier (e.g., avoid U D U by forcing U U D).
            if (moveAxis != lastMoveAxis && !(moveAxis/2 == lastMoveAxis/2 && moveAxis < lastMoveAxis)) {
                int nextEdgePermutation = currentEdgePermutation;
                int nextEdgeOrientation = currentEdgeOrientation;

                // Try all 3 turn types for the current face (e.g., R, R2, R').
                for (int turnCount = 0; turnCount < NUM_MAX_TURN; turnCount++) {
                    // Get the next state from the pre-calculated move tables.
                    nextEdgePermutation = crossEdgePermutationMoveTable[nextEdgePermutation][moveAxis];
                    nextEdgeOrientation = crossEdgeOrientationMoveTable[nextEdgeOrientation][moveAxis];

                    // Record this move in the path.
                    currentPathMoves[depthRemaining] = moveAxis * NUM_MAX_TURN + turnCount;

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
     * Recursively searches for an X-Cross solution using an IDA* (Iterative Deepening A*) algorithm.
     * An X-Cross consists of solving the cross and the first F2L pair at the same time.
     *
     * @param crossEdgePermutation The current coordinate for the permutation of the 4 cross edges.
     * @param crossEdgeOrientation The current coordinate for the orientation of the 4 cross edges.
     * @param f2lCornerState The current coordinate for the state (pos+orient) of the F2L corner.
     * @param f2lEdgeState The current coordinate for the state (pos+orient) of the F2L edge.
     * @param targetF2lSlot The target slot for the F2L pair being solved (e.g., 0 for FR, 1 for FL, etc.).
     * @param depthRemaining The maximum number of moves left to find a solution.
     * @param lastMoveAxis The face that was just turned, used to avoid redundant moves (e.g., R R').
     * @return True if a solution is found within the given depth, false otherwise.
     */
    private static boolean searchForXCross(int crossEdgePermutation, int crossEdgeOrientation,
                                           int f2lCornerState, int f2lEdgeState, int targetF2lSlot,
                                           int depthRemaining, int lastMoveAxis) {
        // --- Base Case: If we've reached the maximum depth, check if the X-Cross is solved. ---
        if (depthRemaining == 0) {
            // The numbers 1656 and 1104 are the coordinates for a solved cross.
            // The other expressions check if the F2L corner and edge are in their solved state
            // for the specified target slot.
            boolean crossSolved = crossEdgePermutation == SOLVED_CROSS_PERMUTATION &&
                    crossEdgeOrientation == SOLVED_CROSS_ORIENTATION;
            // Solved XCross pair: corner in (slot+4)*3 state, edge in slot*2 state
            boolean pairSolved = f2lCornerState == (targetF2lSlot + NUM_F2L_SLOTS) *
                    NUM_ORIENTATIONS_PER_CORNER && f2lEdgeState == targetF2lSlot * NUM_ORIENTATIONS_PER_EDGE;
            return crossSolved && pairSolved;
        }

        // --- Heuristic Pruning: Check if a solution is possible within the remaining depth. ---
        // Pruning tables store the minimum moves required to solve each sub-problem.
        // If any sub-problem requires more moves than we have left, this search path is a dead end.
        if (crossEdgePermutationDistanceTable[crossEdgePermutation] > depthRemaining ||
                crossEdgeOrientationDistanceTable[crossEdgeOrientation] > depthRemaining) {
            return false;
        }
        int combinedPairState = f2lEdgeState * NUM_PAIR_CORNERS_COMBINATIONS * NUM_ORIENTATIONS_PER_CORNER
                                + f2lCornerState;
        if (xcrossPairDistanceTable[targetF2lSlot][combinedPairState] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid subsequent moves. ---
        // Iterate through the 6 faces of the cube (D, U, L, R, F, B).
        for (int moveAxis = 0; moveAxis < NUM_FACES; moveAxis++) {
            if (moveAxis != lastMoveAxis) {
                int nextCornerState = f2lCornerState;
                int nextCrossPerm = crossEdgePermutation;
                int nextCrossOrient = crossEdgeOrientation;
                int nextEdgeState = f2lEdgeState;

                // Iterate through the 3 possible turns for the current face (e.g., R, R2, R').
                for (int turnCount = 0; turnCount < NUM_MAX_TURN; turnCount++) {
                    // Use pre-calculated move tables to find the next state's coordinates instantly.
                    nextCornerState  = xcrossCornerMoveTable[nextCornerState ][moveAxis];
                    nextEdgeState = xcrossEdgeMoveTable[nextEdgeState][moveAxis];
                    nextCrossPerm = crossEdgePermutationMoveTable[nextCrossPerm][moveAxis];
                    nextCrossOrient = crossEdgeOrientationMoveTable[nextCrossOrient][moveAxis];

                    // Make the recursive call for the new state with one less move available.
                    if (searchForXCross(
                            nextCrossPerm,
                            nextCrossOrient,
                            nextCornerState,
                            nextEdgeState,
                            targetF2lSlot,
                            depthRemaining - 1,
                            moveAxis
                    )) {
                        solutionMoveSequence[depthRemaining] = moveAxis * NUM_MAX_TURN + turnCount;
                        return true;
                    }
                }
            }
        }
        // If all moves have been explored from this state and no solution was found, backtrack.
        return false;
    }
    /**
     * Recursively finds all X-Cross solutions up to a given depth and stores them.
     * This IDA* search explores all valid move sequences, prunes inefficient branches,
     * and formats any found solutions into a human-readable string.
     *
     * @param crossEdgePermutation The current coordinate for the permutation of the 4 cross edges.
     * @param crossEdgeOrientation The current coordinate for the orientation of the 4 cross edges.
     * @param f2lCornerState The current coordinate for the state (pos+orient) of the F2L corner.
     * @param f2lEdgeState The current coordinate for the state (pos+orient) of the F2L edge.
     * @param targetF2lSlot The target slot for the F2L pair being solved (e.g., 0 for FR).
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMoveAxis The face that was just turned, used to avoid redundant move sequences.
     * @param cubeOrientation The initial orientation of the cube, to correctly format the solution string.
     * @param currentPathMoves An array used to record the sequence of moves leading to this state.
     */
    private static void searchForXCross(int crossEdgePermutation, int crossEdgeOrientation,
                                        int f2lCornerState, int f2lEdgeState,
                                        int targetF2lSlot, int depthRemaining, int lastMoveAxis,
                                        int cubeOrientation, int[] currentPathMoves) {
        // --- Base Case: We've reached the maximum search depth. ---
        if (depthRemaining == 0) {
            // Check if the current state is the solved X-Cross state.
            if (crossEdgePermutation == 1656 && crossEdgeOrientation == 1104 && f2lCornerState == (targetF2lSlot + 4) * 3 && f2lEdgeState == targetF2lSlot * 2) {

                // --- Solution Found: Format and store it. ---
                StringBuilder solutionString = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][cubeOrientation]);
                int quarterTurnMetric = 0; // Move count where a 180-degree turn counts as 2 moves.

                // Reconstruct the solution string by reading the path backwards.
                for (int i = currentPathMoves.length - 1; i > 0; i--) {
                    int moveCode = currentPathMoves[i];
                    int faceIndex = moveCode / 3;
                    int turnType = moveCode % 3; // 0=', 1=2, 2='

                    solutionString.append(' ')
                            .append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(faceIndex))
                            .append(suff[turnType]);
                    quarterTurnMetric += (turnType == 1) ? 2 : 1; // R2 counts as 2 quarter turns.
                }

                // Append the move counts in Face Turn Metric (f) and Quarter Turn Metric (q).
                solutionString.append("\t").append(currentPathMoves.length - 1).append("f, ").append(quarterTurnMetric).append("q");
                solutions.add(solutionString.toString());
            }
            return; // End this search path.
        }

        // --- Heuristic Pruning: Check if a solution is still possible. ---
        // If any sub-problem requires more moves than we have left, this path is a dead end.

        if (crossEdgePermutationDistanceTable[crossEdgePermutation] > depthRemaining ||
                crossEdgeOrientationDistanceTable[crossEdgeOrientation] > depthRemaining ||
                xcrossPairDistanceTable[targetF2lSlot][f2lEdgeState * 24 + f2lCornerState] > depthRemaining) {
            return;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveAxis = 0; moveAxis < NUM_FACES; moveAxis++) {
            if (moveAxis != lastMoveAxis && !(moveAxis / 2 == lastMoveAxis / 2 && moveAxis < lastMoveAxis)) {

                int nextCornerState = f2lCornerState;
                int nextCrossPerm = crossEdgePermutation;
                int nextCrossOrient = crossEdgeOrientation;
                int nextEdgeState = f2lEdgeState;

                // Try all 3 turn types for the current face (e.g., R, R2, R').
                for (int turnCount = 0; turnCount < NUM_MAX_TURN; turnCount++) {
                    // Get the next state's coordinates from pre-calculated move tables.
                    nextCornerState = xcrossCornerMoveTable[nextCornerState][moveAxis];
                    nextEdgeState = xcrossEdgeMoveTable[nextEdgeState][moveAxis];
                    nextCrossPerm = crossEdgePermutationMoveTable[nextCrossPerm][moveAxis];
                    nextCrossOrient = crossEdgeOrientationMoveTable[nextCrossOrient][moveAxis];

                    // Record this move in the path.
                    currentPathMoves[depthRemaining] = moveAxis * NUM_MAX_TURN + turnCount;

                    // Make the recursive call for the new state.
                    searchForXCross(nextCrossPerm, nextCrossOrient, nextCornerState, nextEdgeState, targetF2lSlot, depthRemaining - 1, moveAxis, cubeOrientation, currentPathMoves);
                }
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="EOFC (Edges Oriented First Cross) IDA* Search Algorithms">
    /**
     * Recursively searches for an "EOCross" solution using the IDA* algorithm.
     * The goal state, EOCross, is achieved when all 12 edges are correctly oriented
     * AND the 4 cross pieces are solved in their correct positions.
     *
     * @param crossEdgePermutation The current coordinate for the permutation of the 4 cross edges.
     * @param crossEdgeOrientation The current coordinate for the orientation of the 4 cross edges.
     * @param allEdgesOrientation A coordinate representing the orientation state of all 12 edges.
     * @param depthRemaining The maximum number of moves left in the current search path.
     * @param lastFaceTurned The face that was just turned, used for pruning redundant moves.
     * @return True if the EOCross state is reached within the given depth, false otherwise.
     */

    private static boolean searchForEOCross(int crossEdgePermutation, int crossEdgeOrientation,
                                            int allEdgesOrientation, int depthRemaining,
                                            int lastFaceTurned) {

        // --- Base Case: If we've reached the maximum depth, check if the state is solved. ---
        if (depthRemaining == 0) {
            // The "& 15" (or & 0xF) isolates the last 4 bits, which represent the orientation/flip part.
            return crossEdgePermutation == SOLVED_CROSS_PERMUTATION &&
                   crossEdgeOrientation == SOLVED_CROSS_ORIENTATION &&
                   (allEdgesOrientation & 15) == ALL_EDGES_ORIENTED_FLAG;
        }
        // --- Heuristic Pruning: Check if a solution is still possible. ---
        // If any sub-problem requires more moves than we have left, this path is a dead end.
        if (crossEdgePermutationDistanceTable[crossEdgePermutation] > depthRemaining ||
                crossEdgeOrientationDistanceTable[crossEdgeOrientation] > depthRemaining ||
                crossEdgesOrientFlipDistanceTable[allEdgesOrientation] > depthRemaining) return false;

        // --- Recursive Step: Explore all valid subsequent moves. ---
        for (int currentFace = 0; currentFace < NUM_FACES; currentFace++)
            if (currentFace != lastFaceTurned) {
                // Create temporary variables to hold the coordinates of the next state.
                int nextPermutation = crossEdgePermutation;
                int nextOrientation = crossEdgeOrientation;
                int nextFlipCombination = allEdgesOrientation;

                // Iterate through the 3 turn types for the current face (e.g., U, U2, U').
                for (int turnType = 0; turnType < NUM_MAX_TURN; turnType++) {
                    // Use pre-calculated move tables to find the next state's coordinates.
                    nextPermutation = crossEdgePermutationMoveTable[nextPermutation][currentFace];
                    nextOrientation = crossEdgeOrientationMoveTable[nextOrientation][currentFace];
                    // The two orientation coordinates use the same move table logic.
                    nextFlipCombination = crossEdgeOrientationMoveTable[nextFlipCombination][currentFace];

                    if (searchForEOCross(nextPermutation, nextOrientation, nextFlipCombination,
                            depthRemaining - 1, currentFace)) {
                        // --- Solution Found! ---
                        // If the recursive call returns true, a path to the solution has been found.
                        // Record the successful move in the solution sequence array.
                        solutionMoveSequence[depthRemaining] = currentFace * NUM_MAX_TURN + turnType;
                        return true;
                    }
                }
            }
        // If all moves have been explored from this state and no solution was found, backtrack.
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Public API Methods">
    /**
     * Solves the cross for one or more specified faces from a given scramble.
     * <p>
     * This function acts as a wrapper. It iterates through the faces selected in the
     * bitmask and calls the core cross solver for each one, then aggregates the
     * results into a single string.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @param targetFaceBitmask A bitmask integer where each bit corresponds to a face
     * (0=D, 1=U, 2=L, 3=R, 4=F, 5=B). If a bit is set, the
     * function will find the cross solution for that face's color.
     * @return A formatted string containing the solutions for all requested faces.
     */
    public static String solveCross(String scramble, int targetFaceBitmask) {
        // Ensure all necessary lookup tables are pre-computed before solving.
        initializeTables();

        // Use a StringBuilder to efficiently build the final output string.
        StringBuilder solutionBuilder = new StringBuilder("\n");

        // Iterate through each of the 6 possible faces for the cross.
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {

            // --- Bitmask Check ---
            // Check if the bit for the current face is set in the input bitmask.
            // This determines if the user wants to solve for this cross color.
            // (bitmask >> faceIndex) shifts the target bit to the rightmost position.
            // (& 1) isolates it to see if it's a 1.
            if (((targetFaceBitmask >> faceIndex) & 1) != 0) {

                // Append a header for the solution (e.g., "Cross(U): ").
                solutionBuilder.append("\nCross(").append(FACE_COLORS[faceIndex]).append("): ");

                // Call the core cross-solving function for the current scramble and face.
                solutionBuilder.append(cross(scramble, 0, faceIndex));
            }
        }

        // Return the concatenated string of all found solutions.
        return solutionBuilder.toString();
    }
    /**
     * Solves the cross for all six possible colors from a given scramble.
     * <p>
     * This is a convenience wrapper method that calls the core cross solver for each
     * of the 6 faces (D, U, L, R, F, B) and aggregates the results into a single
     * formatted string.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @return A formatted string containing the optimal cross solution for every color,
     * each on a new line.
     */
    public static String solveCross(String scramble) {
        // Ensure all necessary lookup tables (move tables, pruning tables) are pre-computed.
        initializeTables();

        // Use a StringBuilder to efficiently build the final multi-line output string.
        StringBuilder allSolutionsBuilder = new StringBuilder();

        // Iterate through each of the 6 possible faces to solve the cross for.
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {
            // Append a header for the current color's solution (e.g., "U: ").
            allSolutionsBuilder.append(FACE_COLORS[faceIndex]).append(": ");

            // Call the core cross-solving function for the current scramble and face,
            // and append its result followed by a newline.
            allSolutionsBuilder.append(cross(scramble, 0, faceIndex)).append("\n");
        }

        // Return the concatenated string of all six solutions.
        return allSolutionsBuilder.toString();
    }

    /**
     * Finds all optimal (shortest) cross solutions for all six colors from a given scramble.
     * <p>
     * This function iterates through each of the 6 possible cross colors. For each color,
     * it applies the scramble to determine the starting state and then uses an IDA* search
     * to find all solutions at the shortest possible length.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @return A formatted string containing all optimal solutions for every cross color.
     */
    public static String solveCrossAllSolutions(String scramble) {
        // --- Define Constants ---
        final int MAX_CROSS_DEPTH = 9; // The cross is always solvable in 8 moves or less.

        // Ensure all lookup tables are pre-computed before solving.
        initializeTables();

        String[] scrambleMoves = scramble.split(" ");
        StringBuilder allSolutionsBuilder = new StringBuilder();

        // --- Main Loop: Iterate through each of the 6 possible cross colors ---
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {

            // --- 1. Apply Scramble ---
            // For each potential cross color, start with a solved state and apply the scramble
            // to get the initial coordinates for that specific orientation.
            int edgePermutation = SOLVED_CROSS_PERMUTATION;
            int edgeOrientation = SOLVED_CROSS_ORIENTATION;

            for (String move : scrambleMoves) {
                if (!move.isEmpty()) {
                    int currentMoveFaceIndex = MOVE_CHAR_MAP_PER_ORIENTATION[0][faceIndex].indexOf(move.charAt(0));

                    // Apply the move 1, 2, or 3 times depending on the suffix ('', '2', or ''').
                    for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ? 3 :
                            (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                        edgeOrientation = crossEdgeOrientationMoveTable[edgeOrientation][currentMoveFaceIndex];
                        edgePermutation = crossEdgePermutationMoveTable[edgePermutation][currentMoveFaceIndex];
                    }
                }
            }

            // --- 2. Iterative Deepening Search ---
            // This list will store all solutions found at the first (optimal) depth.
            solutions = new ArrayList<>();
            for (int searchDepth  = 0; searchDepth  < MAX_CROSS_DEPTH; searchDepth ++) {
                int[] currentPath = new int[searchDepth  + 1];

                // Call the recursive solver that finds ALL solutions for the given depth.
                searchForCrossSolution(edgePermutation, edgeOrientation, searchDepth , INITIAL_LAST_FACE, faceIndex, currentPath);

                // --- 3. Format and Store Optimal Solutions ---
                // If solutions were found at this depth, they are guaranteed to be optimal.
                // We format them, then break the loop to stop searching deeper.
                if (!solutions.isEmpty()) {
                    allSolutionsBuilder.append(FACE_COLORS[faceIndex]).append(":\n");
                    for (String solutionString : solutions) {
                        int tabIndex = solutionString.indexOf('\t');
                        // Append the solution moves, trimming the move count info at the end.
                        allSolutionsBuilder.append("  ").append(solutionString.substring(0, tabIndex)).append("\n");
                    }
                    allSolutionsBuilder.append("\n");
                    break; // Exit the depth loop to ensure we only keep the shortest solutions.
                }
            }
        }
        return allSolutionsBuilder.toString();
    }

    /**
     * Solves the X-Cross for one or more specified cube orientations from a given scramble.
     * <p>
     * This function acts as a wrapper. It iterates through the orientations selected
     * in the bitmask and calls the core X-Cross solver for each one, then
     * aggregates the results into a single formatted string. An X-Cross solves the
     * cross and the first F2L pair simultaneously.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @param targetFacesBitmask A bitmask integer where each bit corresponds to a starting orientation
     * (0=D, 1=U, 2=L, 3=R, 4=F, 5=B). If a bit is set, the
     * function will find the X-Cross solution for that orientation.
     * @return A formatted string containing the solutions for all requested orientations.
     */
    public static String solveXcross(String scramble, int targetFacesBitmask) {
        // Ensure all necessary lookup tables (move tables, pruning tables) are pre-computed.
        initializeTables();

        // Use a StringBuilder to efficiently build the final output string.
        StringBuilder solutionBuilder  = new StringBuilder("\n");

        // Iterate through each of the 6 possible orientations (one for each face).
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++)

            // --- Bitmask Check ---
            // Check if the bit for the current orientation is set in the input bitmask.
            // This determines if the user wants to solve for this specific case.
            // (bitmask >> index) shifts the target bit to the rightmost position.
            // (& 1) isolates it to check if it's a 1.
            if (((targetFacesBitmask >> faceIndex) & 1) != 0) {

                // Append a header for the solution (e.g., "XCross(U): ").
                solutionBuilder .append("\nXCross(").append(FACE_COLORS[faceIndex]).append("): ");

                // Call the core X-Cross solving function for the current scramble and orientation.
                solutionBuilder .append(xcross(scramble, faceIndex));
            }
        // Return the concatenated string of all found solutions.
        return solutionBuilder .toString();
    }

    /**
     * Solves the X-Cross for all six possible orientations from a given scramble.
     * <p>
     * This is a convenience wrapper method that calls the core X-Cross solver for each
     * of the 6 faces (U, D, L, R, F, B) and aggregates the results into a single
     * formatted string. An X-Cross solves the cross and the first F2L pair simultaneously.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @return A formatted string containing the optimal X-Cross solution for every
     * orientation, each on a new line.
     */
    public static String solveXcross(String scramble) {
        // Ensure all necessary lookup tables are pre-computed.
        initializeTables();

        // Use a StringBuilder to efficiently build the final multi-line output string.
        StringBuilder allSolutionBuilder = new StringBuilder();
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {
            // Append a header for the current orientation's solution (e.g., "U: ").
            allSolutionBuilder.append(FACE_COLORS[faceIndex]).append(": ");

            // Call the core X-Cross solving function and append its result.
            allSolutionBuilder.append(xcross(scramble, faceIndex)).append("\n");
        }

        // Return the concatenated string of all six solutions.
        return allSolutionBuilder.toString();
    }

    /**
     * Finds all optimal (shortest) X-Cross solutions for all six orientations from a given scramble.
     * <p>
     * This function iterates through each of the 6 possible orientations. For each one,
     * it simulates the scramble to determine the starting state of the cross and all four
     * F2L pairs. It then uses an IDA* search to find all solutions at the shortest
     * possible length that solve the cross plus any one of the four F2L pairs.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @return A formatted string containing all optimal solutions for every orientation.
     */
    public static String solveXcrossAllSolutions(String scramble) {
        // --- Define Constants ---
        final int MAX_XCROSS_DEPTH = 11;

        // Ensure all lookup tables are pre-computed.
        initializeTables();

        String[] scrambleMoves = scramble.split(" ");
        StringBuilder allSolutionsBuilder = new StringBuilder();

        // --- Main Loop: Iterate through each of the 6 possible orientations ---
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {

            // --- 1. Initialize and Apply Scramble ---
            // For each orientation, start with a solved state and apply the scramble.
            int[] f2lCornerStates = new int[NUM_F2L_SLOTS];
            int[] f2lEdgeStates = new int[NUM_F2L_SLOTS];

            // Initialize each of the 4 F2L pairs to their solved state coordinates.
            for (int i = 0; i < NUM_F2L_SLOTS; i++) {
                f2lCornerStates[i] = (i + NUM_F2L_SLOTS) * NUM_ORIENTATIONS_PER_CORNER;
                f2lEdgeStates[i] = i * NUM_ORIENTATIONS_PER_EDGE;
            }
            int crossEdgePermutation = SOLVED_CROSS_PERMUTATION;
            int crossEdgeOrientation = SOLVED_CROSS_ORIENTATION;

            // Apply each move of the scramble to the cross and all 4 F2L pair coordinates.
            for (String move : scrambleMoves) {
                if (move.length() > 0) {
                    int face = MOVE_CHAR_MAP_PER_ORIENTATION[0][faceIndex].indexOf(move.charAt(0));
                    for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ? 3 : (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                        for (int pair = 0; pair < NUM_F2L_SLOTS; pair++) {
                            f2lCornerStates[pair] = xcrossCornerMoveTable[f2lCornerStates[pair]][face];
                            f2lEdgeStates[pair] = xcrossEdgeMoveTable[f2lEdgeStates[pair]][face];
                        }
                        crossEdgePermutation = crossEdgePermutationMoveTable[crossEdgePermutation][face];
                        crossEdgeOrientation = crossEdgeOrientationMoveTable[crossEdgeOrientation][face];
                    }
                }
            }

            // --- 2. Iterative Deepening Search ---
            // This list will store all solutions found at the optimal depth for the current orientation.
            solutions = new ArrayList<>();
            for (int searchDepth = 0; searchDepth < MAX_XCROSS_DEPTH; searchDepth++) {
                // For the current depth, try to solve the X-Cross for each of the 4 possible F2L slots.
                for (int targetSlot = 0; targetSlot < NUM_F2L_SLOTS; targetSlot++) {
                    int[] currentPath = new int[searchDepth + 1];
                    searchForXCross(crossEdgePermutation, crossEdgeOrientation,
                            f2lCornerStates[targetSlot], f2lEdgeStates[targetSlot],
                            targetSlot, searchDepth, INITIAL_LAST_FACE, faceIndex, currentPath);
                }
                // --- 3. Format and Store Optimal Solutions ---
                // If solutions were found at this depth, they are optimal. Format them and stop searching deeper.
                if (solutions.size() > 0) {
                    allSolutionsBuilder.append(FACE_COLORS[faceIndex]).append(":\n");
                    for (String solutionString : solutions) {
                        int idx = solutionString.indexOf('\t');
                        // Append the solution, trimming the move count info.
                        allSolutionsBuilder.append("  ").append(solutionString.substring(0, idx)).append("\n");
                    }
                    allSolutionsBuilder.append("\n");
                    break; // Exit the depth loop to keep only the shortest solutions.
                }
            }
        }
        return allSolutionsBuilder.toString();
    }

    /**
     * Solves the EOCross sub-problem for one or more orientations, checking both possible axes.
     * <p>
     * This function acts as a wrapper. It iterates through the orientations selected in the
     * bitmask. For each selected orientation (e.g., Down face), it calls the core solver twice
     * to find the solution for both possible line-pair axes (e.g., the Front-Back axis
     * and the Left-Right axis).
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @param targetFacesBitmask A bitmask integer where each bit corresponds to a face/orientation.
     * If a bit is set, the function will solve for that orientation.
     * @return A formatted string containing the solutions for all requested orientations and axes.
     */

    public static String solveEOCross(String scramble, int targetFacesBitmask) {
        // Ensure all necessary lookup tables are pre-computed.
        initializeTables();

        // Use a StringBuilder to efficiently build the final output string.
        StringBuilder solutionBuilder = new StringBuilder("\n");

        // Iterate through each of the 6 possible orientations (one for each face).
        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {

            // --- Bitmask Check ---
            // Check if the bit for the current orientation is set in the input bitmask.
            if (((targetFacesBitmask >> faceIndex) & 1) != 0) {
                // --- Solve for Both Axes ---
                // For each selected face, there are two possible EOLine axes (e.g., on the Down
                // face, the line can be along the Front-Back axis or the Left-Right axis).
                // We call the core solver twice to find the solution for both cases.
                // The problem ID is calculated as orientation * 2 and orientation * 2 + 1.
                solutionBuilder.append(eocross(scramble, faceIndex * NUM_ORIENTATIONS_PER_EDGE));
                solutionBuilder.append(eocross(scramble, faceIndex * NUM_ORIENTATIONS_PER_EDGE + 1));
            }
        }

        // Return the concatenated string of all found solutions.
        return solutionBuilder.toString();
    }


    /**
     * Generates a random cross scramble solvable within a specified number of moves.
     * <p>
     * On the first call, this function pre-computes a pruning table for all cross states
     * using a Breadth-First Search (BFS). On subsequent calls, it uses this table to
     * efficiently find a random state that meets the specified depth requirement.
     * The final state is returned as a 2D array containing piece positions and orientations.
     *
     * @param maxDepth The maximum number of moves the generated cross scramble should be from solved.
     * @return A 2D array where [0][slot] is the piece ID and [1][slot] is its orientation.
     *
     */
    public static int[][] generateEasyCross(int maxDepth) {

        // --- Part 1: Initialize Pruning Table (if not already done) ---
        // This heavy computation runs only once.
        // --- Define Constants for table generation ---
        final int TOTAL_CROSS_STATES = NUM_CROSS_EDGES_COMBINATIONS * NUM_CROSS_EDGES_PERMUTATIONS
                * NUM_CROSS_EDGES_ORIENTATIONS;
        // The table is packed, storing 4 distance values per integer.
        final int DISTANCE_TABLE_SIZE = NUM_CROSS_EDGES_COMBINATIONS * NUM_CROSS_EDGES_PERMUTATIONS
                * NUM_EASYCROSS_EDGES_ORIENTATIONS;
        final int SOLVED_STATE_COORD = (NUM_CROSS_EDGES_COMBINATIONS - 1) * NUM_CROSS_EDGES_PERMUTATIONS
                * NUM_CROSS_EDGES_ORIENTATIONS;
        final int MAX_SOLUTION_DEPTH = 8; // Max depth to compute for the table.

        if (!isEasyCrossInitialized) {

            initializeTables();

            // Initialize the table with an "unvisited" marker.
            for (int i = 0; i < DISTANCE_TABLE_SIZE; i++) combinedEdgeDistanceTable[i] = -1;

            // Set the distance of the solved state to 0.
            setPruning(combinedEdgeDistanceTable, SOLVED_STATE_COORD, 0);

            // Use a Breadth-First Search (BFS) to populate the pruning table layer by layer.
            for (int currentDepth = 0; currentDepth < MAX_SOLUTION_DEPTH; currentDepth++) {
                for (int currentStateCoord = 0; currentStateCoord < TOTAL_CROSS_STATES; currentStateCoord++) {
                    // If the current state is at the depth we are searching...
                    if (getPruning(combinedEdgeDistanceTable, currentStateCoord) == currentDepth)   {
                        // ...explore all possible moves from this state.
                        for (int faceIndex = 0; faceIndex < NUM_FACES; faceIndex++) {
                            int tempStateCoord = currentStateCoord;
                            for (int turnType = 0; turnType < NUM_MAX_TURN; turnType++) {
                                // Apply the move using the pre-computed move tables.
                                int permCoord = tempStateCoord >> 4;
                                int orientCoord = tempStateCoord / (NUM_CROSS_EDGES_PERMUTATIONS
                                        * NUM_CROSS_EDGES_ORIENTATIONS) << 4 | (tempStateCoord & 15);
                                int newPermCoord = crossEdgePermutationMoveTable[permCoord][faceIndex];
                                int newOrientCoord = crossEdgeOrientationMoveTable[orientCoord][faceIndex];

                                tempStateCoord = newPermCoord << 4 | (newOrientCoord & 15);
                                if (getPruning(combinedEdgeDistanceTable, tempStateCoord) == 0xf) {
                                    setPruning(combinedEdgeDistanceTable, tempStateCoord, currentDepth + 1);

                                }
                            }
                        }
                    }
                }

            }
            isEasyCrossInitialized = true;
        }

        // --- Part 2: Generate and Unpack the Scramble ---
        Random randomGenerator = new Random();
        int randomStateCoord;

        // Find a random state that matches the requested depth.
        if (maxDepth == 0) {
            randomStateCoord = SOLVED_STATE_COORD;
        } else {
            // Repeatedly pick a random state until we find one whose distance from solved
            // is less than or equal to the maximum allowed depth.
            do {
                randomStateCoord = randomGenerator.nextInt(TOTAL_CROSS_STATES);
            } while (getPruning(combinedEdgeDistanceTable, randomStateCoord) > maxDepth);
        }

        // Unpack the final chosen coordinate into its combination, permutation, and orientation parts.
        int combinationIndex = randomStateCoord / (NUM_CROSS_EDGES_PERMUTATIONS * NUM_CROSS_EDGES_ORIENTATIONS);
        int permutationIndex = (randomStateCoord >> 4) % NUM_CROSS_EDGES_PERMUTATIONS;
        int orientationIndex = randomStateCoord & 15;

        // Convert the numeric coordinates into a physical representation of the cube.
        int[] pieceStateArray = new int[NUM_EDGES];
        int[] permutationArray = new int[NUM_CROSS_EDGES];
        Utils.idxToPerm(permutationArray, permutationIndex, NUM_CROSS_EDGES, false);

        // Note: The map {3, 2, 1, 0} defines which edge pieces are used for the cross.
        mapCrossPiecesInEdgeSlots(pieceStateArray, permutationArray, combinationIndex, orientationIndex, new int[] {3, 2, 1, 0});

        // Final step: Unpack the piece state (ID+orientation) into a 2D array for easy use.
        int[][] unpackedScrambleState = new int[2][NUM_EDGES];

        for (int edge = 0; edge < NUM_EDGES; edge++) {
            if (pieceStateArray[edge] < 0)
                unpackedScrambleState[0][edge] = unpackedScrambleState[1][edge] = -1;
            else {
                unpackedScrambleState[0][edge] = pieceStateArray[edge] >> 1;
                unpackedScrambleState[1][edge] = pieceStateArray[edge] & 1;
            }
        }
        return unpackedScrambleState;
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
        int[] edgeSlotArray = new int[NUM_EDGES];     // Represents the 12 physical edge slots.
        int [] crossPiecePermutation = new int[NUM_CROSS_EDGES]; // Represents the permutation of the 4 cross pieces.

        // Decode the permutation/orientation index to fill the `piecePermutation` array.
        Utils.idxToPerm(crossPiecePermutation, initialPermOrientIdx, NUM_CROSS_EDGES, false); // This gives a permutation of {0,1,2,3}

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
        int piecesToFind = NUM_CROSS_EDGES;

        // Scan all 12 slots to find the new positions of the cross pieces.
        for (int edgeSlot = 0; edgeSlot < NUM_EDGES; edgeSlot++)
            // A slot >= 0 contains one of our pieces (empty slots are -1).
            if (edgeSlotArray[edgeSlot] >= 0) {
                // Rebuild the combination index using the new position of the pieces.
                newCombinationIdx += Cnk[NUM_EDGES - 1 - edgeSlot][piecesToFind--];

                // Extract the piece's identity (its place in the new permutation).
                crossPiecePermutation[piecesToFind] = edgeSlotArray[edgeSlot] >> 1;

                // Extract the orientation bit and pack it into the new orientation integer.
                newPermOrientIdx |= (edgeSlotArray[edgeSlot] & 1) << NUM_CROSS_EDGES - 1 - piecesToFind;
            }
        // --- 4. FINALIZATION: Pack and return the new coordinates ---

        // Convert the new permutation array (`piecePermutation`) back into a compact index.
        int newPermutationIdx = Utils.permToIdx(crossPiecePermutation, NUM_CROSS_EDGES, false); //permToIdx(pm);

        // Combine the three new indices (combination, permutation, orientation) into a single integer and return it.
        return (NUM_CROSS_EDGES_PERMUTATIONS * newCombinationIdx + newPermutationIdx) << NUM_CROSS_EDGES | newPermOrientIdx;
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
        int piecesToPlace = NUM_CROSS_EDGES;

        // Iterate through all 12 physical edge slots
        for (int edgeSlot = 0; edgeSlot < NUM_EDGES; edgeSlot++) {
            // Cnk is a pre-calculated table of C(n, k), or "n choose k".
            // Cnk[11 - slot][piecesToPlace] tells us how many combinations can be formed
            // using the remaining slots if we *skip* the current one.

            // --- Core Logic ---
            // We check if our combinationIndex is large enough to force us to place a piece here.
            if (combinationIndex >= Cnk[NUM_EDGES - 1 - edgeSlot][piecesToPlace]) {
                // If the index is greater or equal, it means our target combination is not one of
                // the combinations that can be formed by skipping this slot. Therefore,
                // a piece *must* be placed in this slot.

                // We subtract this block of "skipped" combinations from our index to narrow down
                // the search space for the subsequent pieces.
                combinationIndex -= Cnk[NUM_EDGES - 1 - edgeSlot][piecesToPlace];

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
        int piecesToPlace = NUM_CROSS_EDGES; // Counter for remaining pieces

        // Iterate through all 12 slots to determine which are occupied.
        for (int edgeSlot = 0; edgeSlot < NUM_EDGES; edgeSlot++) { // Iterate through all 12 physical edge slots
            // Check if the current edgeSlotOnCube is part of the chosen C(12,k) combination
            if (combinationIndex >= Cnk[NUM_EDGES - 1 - edgeSlot][piecesToPlace]) {

                // This slot is occupied. Update index for subsequent choices.
                combinationIndex -= Cnk[NUM_EDGES - 1 - edgeSlot][piecesToPlace];

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
    /**
     * Finds the first optimal (shortest) solution for the cross on a specific face.
     * <p>
     * This function orchestrates the cross solving process for a single, specified
     * orientation. It applies the given scramble to a solved state to determine the
     * starting coordinates, then uses an IDA* search to find the shortest move
     * sequence to solve the cross.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @param solverOrientation An index representing the cube's orientation from the solver's perspective.
     * @param crossFace The target face (color) for the cross solution (0=D, 1=U, etc.).
     * @return A formatted string containing the setup rotations and solution moves,
     * or "error" if no solution is found.
     */
    private static String cross(String scramble, int solverOrientation, int crossFace) {
        // --- Define Constants ---
        final int MAX_CROSS_DEPTH = 9; // The cross is always solvable in 8 moves or less.

        // --- 1. Apply Scramble ---
        // Start with the coordinates of a solved cross...
        int edgePermutation = SOLVED_CROSS_PERMUTATION;
        int edgeOrientation = SOLVED_CROSS_ORIENTATION;

        // ...then parse the scramble string and apply each move to find the starting state.
        String[] scrambleMoves = scramble.split(" ");

        for (String move : scrambleMoves) {
            if (move.length() > 0) {
                // Determine the face index for the current move based on the cube's orientation.
                int faceIndexToApply = MOVE_CHAR_MAP_PER_ORIENTATION[solverOrientation][crossFace].indexOf(move.charAt(0));

                // Apply the move 1, 2, or 3 times depending on the suffix ('', '2', or ''').
                for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ?
                        3 : (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                    edgeOrientation = crossEdgeOrientationMoveTable[edgeOrientation][faceIndexToApply];
                    edgePermutation = crossEdgePermutationMoveTable[edgePermutation][faceIndexToApply];
                }
            }
        }

        // --- 2. Iterative Deepening Search ---
        // Search for a solution, starting with a depth of 0 and increasing.
        // The first solution found is guaranteed to be one of the shortest.
        for (int searchDepth = 0; searchDepth < MAX_CROSS_DEPTH; searchDepth++) {
            // Call the recursive IDA* solver for the current depth.
            if (searchForCrossSolution(
                    edgePermutation,
                    edgeOrientation,
                    searchDepth,
                    -1)
            ) {

                // --- 3. Format and Return the Solution ---
                // Prepend any necessary setup rotation moves.
                StringBuilder solutionBuilder = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[solverOrientation][crossFace]);
                for (int i = searchDepth; i > 0; i--) {
                    int moveCode = solutionMoveSequence[i];
                    int face = moveCode / 3;
                    int turnType = moveCode % 3;
                    solutionBuilder.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][solverOrientation].charAt(face))
                            .append(suff[turnType]);
                }
                return solutionBuilder.toString();
            }
        }

        // If no solution is found within the maximum search depth, return an error.
        return "error";
    }

    /**
     * Finds the first optimal X-Cross solution from a given scramble.
     * <p>
     * This function orchestrates the X-Cross solving process. It tracks the state
     * of the cross and all four potential F2L pairs, applies the scramble, and then
     * uses an iterative deepening search to find the shortest move sequence that solves
     * the cross plus any one of the four F2L pairs.
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @param targetFace An index representing the initial orientation of the cube.
     * @return A formatted string containing the setup rotation and the solution moves,
     * or "error" if no solution is found within the depth limit.
     */
    private static String xcross(String scramble, int targetFace) {
        // --- Define Constants ---
        final int MAX_XCROSS_DEPTH = 11;

        // --- 1. Initialize Coordinates ---
        // Arrays to hold the coordinates for each of the 4 possible F2L pairs.
        int[] f2lCornerStates = new int[NUM_F2L_SLOTS];
        int[] f2lEdgeStates = new int[NUM_F2L_SLOTS];

        // Initialize each F2L pair to its solved state. The solver will apply the
        // scramble to these coordinates to find their starting positions.
        for (int i = 0; i < NUM_F2L_SLOTS; i++) {
            f2lCornerStates[i] = (i + NUM_F2L_SLOTS) * NUM_ORIENTATIONS_PER_CORNER;
            f2lEdgeStates[i] = i * NUM_ORIENTATIONS_PER_EDGE;
        }

        // Initialize cross coordinates to the solved state.
        int crossEdgePermutation = SOLVED_CROSS_PERMUTATION;
        int crossEdgeOrientation = SOLVED_CROSS_ORIENTATION;


        // --- 2. Apply the Scramble ---
        // Parse the scramble and apply each move to the cross and all 4 F2L pairs.
        String[] scrambleMoves = scramble.split(" ");

        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                int faceIndex = MOVE_CHAR_MAP_PER_ORIENTATION[0][targetFace].indexOf(move.charAt(0));
                // Apply the move 1, 2, or 3 times depending on the suffix ('', '2', or ''').
                for (int turnCount = 0; turnCount < (move.length() > 1 && move.charAt(1) == '\'' ?
                        3 : (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); turnCount++) {
                    // Update coordinates for all 4 F2L pairs.
                    for (int i = 0; i < NUM_F2L_SLOTS; i++) {
                        f2lCornerStates[i] = xcrossCornerMoveTable[f2lCornerStates[i]][faceIndex];
                        f2lEdgeStates[i] = xcrossEdgeMoveTable[f2lEdgeStates[i]][faceIndex];
                    }
                    // Update coordinates for the cross.
                    crossEdgePermutation = crossEdgePermutationMoveTable[crossEdgePermutation][faceIndex];
                    crossEdgeOrientation = crossEdgeOrientationMoveTable[crossEdgeOrientation][faceIndex];
                }
            }
        }

        // --- 3. Iterative Deepening Search ---
        // Search for a solution, starting with depth 0 and increasing.
        for (int searchDepth = 0; searchDepth < MAX_XCROSS_DEPTH; searchDepth++) {
            // For each depth, check if any of the 4 F2L slots can be solved.
            for (int targetF2lSlot = 0; targetF2lSlot < NUM_F2L_SLOTS; targetF2lSlot++) {
                // Call the recursive IDA* solver for the current F2L pair.
                if (searchForXCross(
                        crossEdgePermutation,
                        crossEdgeOrientation,
                        f2lCornerStates[targetF2lSlot],
                        f2lEdgeStates[targetF2lSlot],
                        targetF2lSlot,
                        searchDepth,
                        INITIAL_LAST_FACE
                )) {
                    // --- 4. Format and Return the First Solution Found ---
                    StringBuilder solutionBuilder = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][targetFace]);
                    // Reconstruct the solution from the path found by the solver.
                    for (int i = searchDepth; i > 0; i--) {
                        int moveCode = solutionMoveSequence[i];
                        int face = moveCode / 3;
                        int turnType = moveCode % 3;
                        solutionBuilder.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(face))
                                .append(suff[turnType]);
                    }
                    return solutionBuilder.toString();
                }
            }
        }
        // If no solution is found within the maximum search depth, return an error.
        return "error";
    }

    /**
     * Finds the optimal solution for an "EOCross" state from a given scramble.
     * An EOCross is a sub-goal where all 12 edges are correctly oriented, and the 4
     * cross pieces are solved in their correct positions.
     * This function orchestrates the solving process by:
     *  <ol>
     *  <li>Applying the scramble to a solved state to get the initial coordinates.</li>
     *  <li>Using an iterative deepening loop to call the recursive solver.</li>
     *  <li>Formatting the first and shortest solution found into a readable string.</li>
     *  </ol>
     *
     * @param scramble The scramble string to solve from (e.g., "R U' F2").
     * @param crossTargetFace The face on which the cross for the EOCross should be built. The solver's
     * core logic assumes the cross is on the Down face, so a setup
     * rotation is applied first if the target is not Down.
     * @return A formatted string containing the setup rotation and the solution moves.

     */
    public static String eocross (String scramble, int crossTargetFace) {
        // --- Define Constants for the Solved State ---

        final int MAX_SEARCH_DEPTH = 13;

        // --- 1. Apply the Scramble ---
        // Start with the coordinates of a solved cube.
        int crossEdgePermutation = SOLVED_CROSS_PERMUTATION;
        int crossEdgeOrientation = SOLVED_CROSS_ORIENTATION ;
        int allEdgesOrientation = ALL_EDGES_ORIENTED_FLAG;

        // Parse the scramble string and apply each move to the coordinates
        // to find the starting state of the puzzle.
        String[] scrambleMoves = scramble.split(" ");
        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                // Determine the face index for the current move.
                int faceIndex = EOline.moveStringPerOrientation[crossTargetFace].indexOf(move.charAt(0));

                // Apply the move for each turn type (' = 3 , 2 = 2, or standard = 1)
                for (int i = 0; i < (move.length() > 1 && move.charAt(1) == '\'' ? 3 :
                        (move.length() > 1 && move.charAt(1) == '2' ? 2 : 1)); i++) {
                    crossEdgeOrientation = crossEdgeOrientationMoveTable[crossEdgeOrientation][faceIndex];
                    crossEdgePermutation = crossEdgePermutationMoveTable[crossEdgePermutation][faceIndex];
                    allEdgesOrientation = crossEdgeOrientationMoveTable[allEdgesOrientation][faceIndex];
                }
            }
        }
        // --- 2. Iterative Deepening Search for the solution ---
        // Search for a solution, starting with a depth of 0 and increasing.
        // The first solution found is guaranteed to be one of the shortest.
        for (int searchDepth  = 0; searchDepth  < MAX_SEARCH_DEPTH; searchDepth ++) {
            //Log.w("dct", ""+d);
            // Call the recursive EOCross solver for the current depth.
            if (searchForEOCross(crossEdgePermutation, crossEdgeOrientation, allEdgesOrientation, searchDepth , -1)) {

                // --- 3. Format and Return the Solution ---
                StringBuilder solutionBuilder = new StringBuilder("\n");

                // Prepend the problem description (e.g., "D(FB)") and the necessary setup rotation.
                // The setup rotation brings the target face to the Down position for the solver.
                solutionBuilder.append(EOFC_SIDE_STRINGS[crossTargetFace])
                        .append(": ")
                        .append(EOline.setupRotations[crossTargetFace]);

                // Reconstruct the core solution moves from the path found by the solver.
                for (int i = searchDepth; i > 0; i--) {
                    int moveCode = solutionMoveSequence[i];
                    int face = moveCode / NUM_MAX_TURN;
                    int turnType = moveCode % NUM_MAX_TURN;
                    solutionBuilder.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(face))
                            .append(suff[turnType]);
                }
                return solutionBuilder.toString();
            }
        }

        // If no solution is found within the maximum search depth, return an error.
        return "\nerror";
    }

    //</editor-fold>
}
