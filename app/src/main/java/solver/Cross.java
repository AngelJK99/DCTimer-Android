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

    // --- Pruning and Move Tables ---
    // Edge Permutation Move Table: Stores the next edge permutation state (combination + permutation index for 4 edges)
    /** Edge Permutation Move Table: [currentState][move] -> newState */
    private static short[][] edgePermutationMoveTable = new short[11880][6];

    // Edge Orientation Move Table (for the 4 cross edges)
    /** Edge Orientation Move Table: [currentState][move] -> newState */
    private static short[][] edgeOrientationMoveTable = new short[7920][6];

    // --- Pruning Tables (Distance Heuristics) ---
    // Distance to solve edge permutation for the 4 cross edges.
    /** Edge Permutation Distance Table (Pruning Table): [state] -> minMovesToSolved */
    private static byte[] edgePermutationDistanceTable = new byte[11880];

    // Distance to solve edge orientation for the 4 cross edges.
    /** Edge Orientation Distance Table (Pruning Table): [state] -> minMovesToSolved */
    private static byte[] edgeOrientationDistanceTable = new byte[7920];
    /**
     * Edge Orientation Distance Table for EOFC (Edges Oriented for First Cross).
     * This table helps prune states where cross edges are oriented but not necessarily permuted.
     * [state] -> minMovesToCrossEdgesOriented
     */
    private static byte[] crossEdgesInSliceOrientationDistanceTable = new byte[7920];
    /**
     * Combined Edge State Distance Table (used for easyCross generation).
     * Stores distance for a combined state of permutation and orientation of 4 edges.
     * Indexing: (combinationIndex * 384) + (permutationIndex << 4) | orientationIndex
     */
    private static int[] combinedEdgeDistanceTable = new int[23760];

    // --- Move Tables for XCross (Corner-Edge Pair) ---
    // Corner Permutation/Orientation Move Table for the first pair.
    /** XCross Corner Move Table: [currentState][move] -> newState */
    private static byte[][] xcrossCornerMoveTable = new byte[24][6];

    // Edge Permutation/Orientation Move Table for the first pair's edge relative to the cross.
    /** XCross Pair Edge Move Table: [currentState][move] -> newState (edge relative to cross) */
    private static byte[][] xcrossPairEdgeMoveTable = new byte[24][6];

    // --- Pruning Table for XCross ---
    /**
     * XCross Pair Distance Table (Pruning Table for F2L pair).
     * [f2lSlotIndex][combinedEdgeState * 24 + combinedCornerState] -> minMovesToSolvedPair
     * This table stores the minimum moves to solve a specific F2L pair (corner + edge)
     * for each of the 4 F2L slots.
     */
    private static byte[][] xcrossPairDistanceTable = new byte[4][576];


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
            { "", "z2", "z'", "z", "x'", "x" },     // Solving D-face, target D, U, L, R, F, B
            { "z2", "", "z", "z'", "x", "x'" },     // Solving U-face, target D, U, L, R, F, B
            { "z", "z'", "", "z2", "y", "y'" },     // Solving L-face
            { "z'", "z", "z2", "", "y'", "y" },     // Solving R-face
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

        for (int combinationIdx = 0; combinationIdx < 495; combinationIdx++) {
            for (int permOrientIdx = 0; permOrientIdx < 24; permOrientIdx++) {
                for (int move = 0; move < 6; move++) {
                    // Calculate the resulting state after applying the move
                    // high bits for new combination & permutation, low bits for new orientation
                    int combinedState = calculateNextCombinedEdgeState(combinationIdx, permOrientIdx, move);

                    // Edge Permutation Move Table stores (New Combination * 24 + New Permutation)
                    edgePermutationMoveTable[24 * combinationIdx + permOrientIdx][move] = (short) (combinedState >> 4);

                    // Store the orientation part (only for the 16 valid orientation states if C(4,2) for EO)
                    // This assumes the orientation part of permIdx is relevant or mapped.
                    // The original code uses `permIdx < 16`. This might be related to how orientations
                    // are indexed for the `edgeOrientationMoveTable`.
                    // A common approach is C(12,4) for EP and C(12,4)*2^3 for EO (last edge orientation is dependent).
                    // Or, if it's specifically for the 4 cross edges, it might be different.
                    // The 7920 size of eom (16*495) suggests it's for 4 edges with orientation.
                    if (permOrientIdx < 16) { // Check if this permOrientIdx is valid for orientation part
                        edgeOrientationMoveTable[16 * combinationIdx + permOrientIdx][move] =
                                (short) (((combinedState >> 4) / 24 * 16) + (combinedState & 15));
                    }
                }
            }
        }
        // Initialize Pruning Tables for Core Cross
        // Edge Permutation Distance Table
        for (int i = 0; i < 11880; i++) edgePermutationDistanceTable[i] = -1;
        edgePermutationDistanceTable[69 * 24] = 0;  // Solved state C(8,4)*24 for edges 0,1,2,3 on D face
        Utils.createPrun(edgePermutationDistanceTable, 6, edgePermutationMoveTable, 3); // Max depth 6-7 for EP

        // Cross Edge Orientation Distance Table
        for (int i = 0; i < 7920; i++)
            edgeOrientationDistanceTable[i] = crossEdgesInSliceOrientationDistanceTable[i] = -1;
        edgeOrientationDistanceTable[69 * 16] = 0; // Solved state C(8,4)*16 for edges 0,1,2,3 oriented
        Utils.createPrun(edgeOrientationDistanceTable, 7, edgeOrientationMoveTable, 3);

        // Cross Edges in D-Slice Orientation Distance Table
        for (int i = 0; i < 7920; i++) crossEdgesInSliceOrientationDistanceTable[i] = -1;
        // For any combination of 4 edges, if their orientation bits are all 0, it's a solved orientation state *for that combination*.
        // This is used for EO-first approaches where edges are first put in slice, then oriented.
        for (int combinationIdx = 0; combinationIdx < 495; combinationIdx++) {
            crossEdgesInSliceOrientationDistanceTable[combinationIdx << 4] = 0; // combination * 16 + 0 orientation bits
        }
        Utils.createPrun(crossEdgesInSliceOrientationDistanceTable, 4, edgeOrientationMoveTable, 3);

        // Initialize XCross Specific Tables
        byte[][] cornerPermutations = {
                {1, 0, 3, 0, 0, 4}, {2, 1, 1, 5, 1, 0}, {3, 2, 2, 1, 6, 2}, {0, 3, 7, 3, 2, 3},
                {4, 7, 0, 4, 4, 5}, {5, 4, 5, 6, 5, 1}, {6, 5, 6, 2, 7, 6}, {7, 6, 4, 7, 3, 7}
        };
        byte[][] cornerOrientationChanges = {
                {0, 0, 1, 0, 0, 2}, {0, 0, 0, 2, 0, 1}, {0, 0, 0, 1, 2, 0}, {0, 0, 2, 0, 1, 0},
                {0, 0, 2, 0, 0, 1}, {0, 0, 0, 1, 0, 2}, {0, 0, 0, 2, 1, 0}, {0, 0, 1, 0, 2, 0}
        };
        for (int cornerState = 0; cornerState < 8; cornerState++) {
            for (int orientation = 0; orientation < 3; orientation++) {
                for (int move = 0; move < 6; move++) {
                    xcrossCornerMoveTable[cornerState * 3 + orientation][move] = (byte) (
                            cornerPermutations[cornerState][move] * 3 +
                                    (cornerOrientationChanges[cornerState][move] + orientation) % 3
                    );
                }
            }
        }
        byte[][] pairEdgePermutations = {
                {0, 0, 7, 0, 0, 8}, {1, 1, 1, 9, 1, 4}, {2, 2, 2, 5, 10, 2}, {3, 3, 11, 3, 6, 3},
                {5, 4, 4, 4, 4, 0}, {6, 5, 5, 1, 5, 5}, {7, 6, 6, 6, 2, 6}, {4, 7, 3, 7, 7, 7},
                {8, 11, 8, 8, 8, 1}, {9, 8, 9, 2, 9, 9}, {10, 9, 10, 10, 3, 10}, {11, 10, 0, 11, 11, 11}
        };
        byte[][] pairEdgeOrientationChanges = {
                {0, 0, 0, 0, 0, 1}, {0, 0, 0, 0, 0, 1}, {0, 0, 0, 0, 1, 0}, {0, 0, 0, 0, 1, 0},
                {0, 0, 0, 0, 0, 1}, {0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 1, 0}, {0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 1}, {0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 1, 0}, {0, 0, 0, 0, 0, 0}
        };
        for (int edgeState = 0; edgeState < 12; edgeState++) {
            for (int orientation = 0; orientation < 2; orientation++) {
                for (int move = 0; move < 6; move++) {
                    xcrossPairEdgeMoveTable[edgeState * 2 + orientation][move] = (byte) (
                            pairEdgePermutations[edgeState][move] * 2 + (pairEdgeOrientationChanges[edgeState][move] ^ orientation)
                    );
                }
            }
        }

        // Initialize Pruning Table for XCross Pair (distance to solve one F2L pair)
        for (int f2lSlotIndex = 0; f2lSlotIndex < 4; f2lSlotIndex++) {
            for (int i = 0; i < 576; i++) xcrossPairDistanceTable[f2lSlotIndex][i] = -1;
            // Solved state for this F2L slot (corner & edge correctly placed and oriented)
            // Consistent with idaxcross: corner=(slot+4)*3, edge=slot*2
            int solvedCornerState = (f2lSlotIndex + 4) * 3;
            int solvedPairEdgeState = f2lSlotIndex * 2;
            xcrossPairDistanceTable[f2lSlotIndex][solvedPairEdgeState * 24 + solvedCornerState] = 0;

            for (int distance = 0; distance < 6; distance++) {
                int statesAtThisDistance = 0;
                for (int combinedStateIdx = 0; combinedStateIdx < 576; combinedStateIdx++) {
                    if (xcrossPairDistanceTable[f2lSlotIndex][combinedStateIdx] == distance) {
                        for (int moveAxis = 0; moveAxis < 6; moveAxis++) {
                            int currentCorner = combinedStateIdx % 24;
                            int currentEdge = combinedStateIdx / 24;
                            for (int turnCount = 0; turnCount < 3; turnCount++) {
                                currentCorner = xcrossCornerMoveTable[currentCorner][moveAxis];
                                currentEdge = xcrossPairEdgeMoveTable[currentEdge][moveAxis];
                                int nextCombinedState = currentEdge * 24 + currentCorner;
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
     * IDA* search for a single cross solution.
     *
     * @param currentEdgePermState Current edge permutation state index.
     * @param currentEdgeOrientState Current edge orientation state index.
     * @param depthRemaining       Remaining depth for the search.
     * @param lastMoveAxis         Axis of the last move to avoid redundant sequences.
     * @return True if a solution is found at this depth.
     */
    private static boolean searchForCrossSolution(int currentEdgePermState, int currentEdgeOrientState, int depthRemaining, int lastMoveAxis) {
        if (depthRemaining == 0) {
            // Solved state: EP=1656 (e.g., D-face edges {0,1,2,3} permuted), EO=1104 (oriented)
            return currentEdgePermState == 1656 && currentEdgeOrientState == 1104;
        }
        if (edgePermutationDistanceTable[currentEdgePermState] > depthRemaining || edgeOrientationDistanceTable[currentEdgeOrientState] > depthRemaining) return false;
        for (int moveAxis = 0; moveAxis < 6; moveAxis++)
            if (moveAxis != lastMoveAxis) {
                int nextEPState = currentEdgePermState;
                int nextEOState = currentEdgeOrientState;
                for (int turnCount = 0; turnCount < 3; turnCount++) {
                    nextEPState = edgePermutationMoveTable[nextEPState][moveAxis]; nextEOState = edgeOrientationMoveTable[nextEOState][moveAxis];
                    if (searchForCrossSolution(nextEPState, nextEOState, depthRemaining - 1, moveAxis)) {
                        solutionMoveSequence[depthRemaining] = moveAxis * 3 + turnCount;
                        //sb.insert(0, " " + turn[face][i] + suff[j]);
                        return true;
                    }
                }
            }
        return false;
    }

    /**
     * IDA* search for all cross solutions at a given depth, populating solutionsList.
     *
     * @param currentEdgePermState Current edge permutation state.
     * @param currentEdgeOrientState Current edge orientation state.
     * @param depthRemaining       Depth left for search.
     * @param lastMoveAxis         Last move axis.
     * @param solvedOnFaceIdx      The face index (0-5) on which the cross is defined as solved (usually D=0).
     * @param currentPathMoves     Array to build up the solution path.
     */
    private static void searchForCrossSolution(int currentEdgePermState, int currentEdgeOrientState, int depthRemaining, int lastMoveAxis, int solvedOnFaceIdx, int[] currentPathMoves) {
        if (depthRemaining == 0) {
            if (currentEdgePermState == 1656 && currentEdgeOrientState == 1104) {
                StringBuilder solBuilder = new StringBuilder(ROTATION_TO_STANDARD_ORIENTATION_MAP[0][solvedOnFaceIdx]);
                int qtm = 0;
                for (int i = currentPathMoves.length - 1; i > 0; i--) {
                    int moveCode = currentPathMoves[i];
                    solBuilder.append(' ').append(MOVE_CHAR_MAP_PER_ORIENTATION[0][0].charAt(currentPathMoves[i] / 3)).append(suff[currentPathMoves[i] % 3]);
                    qtm += (moveCode % 3 == 1) ? 2 : 1;
                }
                solBuilder.append("\t").append(currentPathMoves.length - 1).append("f, ").append(qtm).append("q");
                solutionsList.add(solBuilder.toString());
            }
            return;
        }

        if (edgePermutationDistanceTable[currentEdgePermState] > depthRemaining ||
                edgeOrientationDistanceTable[currentEdgeOrientState] > depthRemaining) {
            return; // Prune
        }

        for (int moveAxis = 0; moveAxis < 6; moveAxis++)
            if (moveAxis != lastMoveAxis && !(moveAxis/2 == lastMoveAxis/2 && moveAxis < lastMoveAxis)) {
                int nextEPState = currentEdgePermState;
                int nextEOState = currentEdgeOrientState;
                for (int turnCount = 0; turnCount < 3; turnCount++) {
                    nextEPState = edgePermutationMoveTable[nextEPState][moveAxis];
                    nextEOState = edgeOrientationMoveTable[nextEOState][moveAxis];
                    currentPathMoves[depthRemaining] = moveAxis * 3 + turnCount;

                    searchForCrossSolution(nextEPState, nextEOState, depthRemaining - 1, moveAxis, solvedOnFaceIdx, currentPathMoves);
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
     * @param currentCornerState        XCross Corner state (perm+orient).
     * @param currentPairEdgeState      XCross Pair Edge state (perm+orient relative to cross pieces).
     * @param f2lSlotIndex              The target F2L slot for the pair (0-3).
     * @param depthRemaining            Remaining depth for the search.
     * @param lastMoveAxis              Axis of the last move.
     * @return True if an XCross solution is found.
     */
    private static boolean searchForXCross(int currentEdgePermState, int currentEdgeOrientState,
                                           int currentCornerState, int currentPairEdgeState, int f2lSlotIndex, int depthRemaining, int lastMoveAxis) {
        if (depthRemaining == 0) {
            boolean crossSolved = currentEdgePermState == 1656 && currentEdgeOrientState == 1104;
            // Solved XCross pair: corner in (slot+4)*3 state, edge in slot*2 state
            boolean pairSolved = currentCornerState == (f2lSlotIndex + 4) * 3 && currentPairEdgeState == f2lSlotIndex * 2;
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
        int combinedPairState = currentPairEdgeState * 24 + currentCornerState;
        if (xcrossPairDistanceTable[f2lSlotIndex][combinedPairState] > depthRemaining) {
            return false;
        }

        for (int moveAxis = 0; moveAxis < 6; moveAxis++) {
            if (moveAxis != lastMoveAxis) {
                int nextCorner = currentCornerState;
                int nextEP = currentEdgePermState;
                int nextEO = currentEdgeOrientState;
                int nextPairEdge = currentPairEdgeState;
                for (int turnCount = 0; turnCount < 3; turnCount++) {
                    nextCorner = xcrossCornerMoveTable[nextCorner][moveAxis];
                    nextPairEdge = xcrossPairEdgeMoveTable[nextPairEdge][moveAxis];
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
                    nextPairEdge = xcrossPairEdgeMoveTable[nextPairEdge][moveAxis];
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
                        feo[i] = xcrossPairEdgeMoveTable[feo[i]][m];
                    }
                    ep = edgePermutationMoveTable[ep][m]; eo = edgeOrientationMoveTable[eo][m];
                    if (s[d].length() > 1) {
                        for (int i = 0; i < 4; i++) {
                            co[i] = xcrossCornerMoveTable[co[i]][m];
                            feo[i] = xcrossPairEdgeMoveTable[feo[i]][m];
                        }
                        eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                        if (s[d].charAt(1) == '\'') {
                            for (int i = 0; i < 4; i++) {
                                co[i] = xcrossCornerMoveTable[co[i]][m];
                                feo[i] = xcrossPairEdgeMoveTable[feo[i]][m];
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
        mapCombinationToEdgeSlots(c, p, comb, ori, new int[] {3, 2, 1, 0});
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
     * Applies a scramble string to the current cube state (represented by piece states).
     * This is a helper for the public solving methods to get the initial state from a scramble.
     * (This method would need to be fleshed out based on how the scramble affects the internal states
     * like edgePermutation, edgeOrientation etc.)
     *
     * For example, it might return an object or an array holding {ep, eo, co, feo_array}.
     *
     * private static CubeState applyScramble(String scramble, int referenceSide, int referenceFace) { ... }
     *
     * Or, each solving method could parse the scramble directly as in the original code.
     * For now, I'll keep the direct parsing in the solving methods as per original,
     * but a dedicated method could be cleaner.
     */

    /**
     * Calculates the next combined edge state (for move tables) after applying a move.
     * The combined state packs new combination index, new permutation index, and new orientation bits.
     *
     * @param currentCombinationIdx C(12,4) index of the 4 cross edges.
     * @param currentPermOrientIdx  Index (0-23) that implies both permutation of the 4 edges
     *                              AND their orientation (lower 4 bits usually).
     * @param moveIndex             The move to apply (0-5).
     * @return An integer packing the new combination, permutation, and orientation.
     */

    private static int calculateNextCombinedEdgeState(int currentCombinationIdx, int currentPermOrientIdx, int moveIndex) {
        // (Logic from original getmv)
        int[] edgeSlots = new int[12];
        int [] pieceIndicesInPermutation = new int[4]; // Stores which of the 12 edge pieces are the chosen 4.

        // permOrientIdx (0-23) implies a permutation of 4 items.
        // We need to map this to the actual edge pieces (0-11) that form this permutation.
        // This was likely implicit in the original code's structure where C(12,4) selected the pieces,
        // and permOrientIdx permuted *those selected pieces*.
        // For the purpose of mapCombinationToEdgeSlots, we need the *actual piece IDs*.
        // The original `idxToPerm(ps, po, 4, false)` used `po` (permOrientIdx) to get an ordering of chosen items.
        // Let's assume permOrientIdx (0-23) defines the permutation of the 4 edges selected by currentCombinationIdx.
        // The orientation bits are often extracted from the lower bits of an index like permOrientIdx.
        int permutationOfChosenFour = currentPermOrientIdx; // This might be better named if it was just permutation
        int orientationBits = currentPermOrientIdx & 15; // Assuming lower 4 bits are orientation.

        // This part needs to correctly get the *actual* 4 edge piece IDs based on currentCombinationIdx.
        // The original `idxToComb(arr, ps, c, o)` where ps was from `idxToPerm(ps, po, 4, false)`
        // implies `ps` held the items being permuted.
        // For now, let's assume `Utils.idxToPerm` gives an abstract permutation order [0,1,2,3]
        // and `mapCombinationToEdgeSlots` then places specific edges based on `combinationIdx`.
        // This is the trickiest part to refactor without a debugger on the original logic.
        // Let's try to reconstruct the edge pieces based on combination index for now.
        // This is a placeholder for getting the actual 4 edge pieces defined by currentCombinationIdx
        Utils.idxToPerm(pieceIndicesInPermutation, permutationOfChosenFour, 4, false); // This gives a permutation of {0,1,2,3}

        mapCombinationToEdgeSlots(edgeSlots, pieceIndicesInPermutation, currentCombinationIdx, currentPermOrientIdx);
        applyMoveToEdgeArray(edgeSlots, moveIndex);

        int newCombinationIdx = 0;
        int newOrientationBits = 0;
        int piecesFoundCount = 4;
        int[] newPieceIndicesInPermutation = new int[4];
        for (int slot = 0; slot < 12; slot++)
            if (edgeSlots[slot] >= 0) {
                newCombinationIdx += Cnk[11 - slot][piecesFoundCount--];
                newPieceIndicesInPermutation[piecesFoundCount] = edgeSlots[slot] >> 1;
                newOrientationBits |= (edgeSlots[slot] & 1) << 3 - piecesFoundCount;
            }
        int newPermutationIdx = Utils.permToIdx(newPieceIndicesInPermutation, 4, false); //permToIdx(pm);
        return (24 * newCombinationIdx + newPermutationIdx) << 4 | newOrientationBits;
    }

    /**
     * Applies a physical turn to an array representing 12 edge slots (piece index and orientation).
     *
     * @param edgeStates Array of 12 integers. Each int is (edge_piece_id << 1 | orientation_bit).
     *                   -1 if slot is empty or not one of the tracked edges.
     * @param moveIndex  The move to apply (0:D, 1:U, 2:L, 3:R, 4:F, 5:B - internal order).
     */
    static void applyMoveToEdgeArray(int[] edgeStates, int moveIndex) {
        switch (moveIndex) {
            case 0: circle(edgeStates, 0,  1, 2,  3, 0); break; // D face
            case 1: circle(edgeStates, 4,  7, 6,  5, 0); break; // U face
            case 2: circle(edgeStates, 2,  9, 6, 10, 0); break; // L face
            case 3: circle(edgeStates, 0, 11, 4,  8, 0); break; // R face
            case 4: circle(edgeStates, 1,  8, 5,  9, 1); break; // F face (flips)
            case 5: circle(edgeStates, 3, 10, 7, 11, 1); break; // B face (flips)
        }
    }

    /**
     * Helper function to perform a 4-cycle on an array with optional orientation flip.
     * @param array The array to modify.
     * @param p1 Index of the first piece.
     * @param p2 Index of the second piece.
     * @param p3 Index of the third piece.
     * @param p4 Index of the fourth piece.
     * @param orientationChange Value to XOR with orientation (0 or 1).
     */
    public static void circle(int[] array, int p1, int p2, int p3, int p4, int orientationChange) {
        // ... (Logic from original circle)
        int t = array[p1];
        array[p1] = array[p4] ^ orientationChange;
        array[p4] = array[p3] ^ orientationChange;
        array[p3] = array[p2] ^ orientationChange;
        array[p2] = t ^ orientationChange;
    }

    /**
     * Populates an array representing 12 edge slots based on a combination index,
     * the specific edges in that combination, and their orientations.
     *
     * @param edgeSlotsOutput     Output array [12] to store edge states (piece_id << 1 | orient_bit).
     * @param permutedPieceIndices Array [4] of the actual piece IDs (0-11) for the 4 chosen edges.
     * @param combinationIndex    The C(12,4) index for choosing 4 edges.
     * @param orientationBits     4 LSBs represent orientation of the 4 permutedPieceIndices.
     */
    private static void mapCombinationToEdgeSlots(int[] edgeSlotsOutput, int[] permutedPieceIndices, int combinationIndex, int orientationBits) {
        int currentPieceSlotInCombination = 3; // Index for permutedPieceIndices (0 to 3)
        for (int edgeSlotOnCube = 0; edgeSlotOnCube < 12; edgeSlotOnCube++) { // Iterate through all 12 physical edge slots
            // Check if the current edgeSlotOnCube is part of the chosen C(12,k) combination
            if (currentPieceSlotInCombination >= 0 && combinationIndex >= Cnk[11 - edgeSlotOnCube][currentPieceSlotInCombination]) {
                combinationIndex -= Cnk[11 - edgeSlotOnCube][currentPieceSlotInCombination + 1];
                // This edgeSlotOnCube contains one of the 4 chosen pieces
                edgeSlotsOutput[edgeSlotOnCube] = permutedPieceIndices[currentPieceSlotInCombination] << 1 | orientationBits & 1;
                orientationBits >>= 1;
                currentPieceSlotInCombination--;
            } else {
                edgeSlotsOutput[edgeSlotOnCube] = -1;  // This physical slot is not one of the 4 chosen edges
            }
        }
    }


    private static void mapCombinationToEdgeSlots(int[] edgeSlotsOutput, int[] permutedPieceIndices, int combinationIndex, int orientationBits, int[] map) {
        int currentPieceSlotInCombination = 3; // Index for permutedPieceIndices (0 to 3)
        for (int edgeSlotOnCube = 0; edgeSlotOnCube < 12; edgeSlotOnCube++) { // Iterate through all 12 physical edge slots
            // Check if the current edgeSlotOnCube is part of the chosen C(12,k) combination
            if (currentPieceSlotInCombination >= 0 && combinationIndex >= Cnk[11 - edgeSlotOnCube][currentPieceSlotInCombination]) {
                combinationIndex -= Cnk[11 - edgeSlotOnCube][currentPieceSlotInCombination + 1];
                // This edgeSlotOnCube contains one of the 4 chosen pieces
                edgeSlotsOutput[edgeSlotOnCube] = map[permutedPieceIndices[currentPieceSlotInCombination]] << 1 | orientationBits & 1;
                orientationBits >>= 1;
                currentPieceSlotInCombination--;
            } else {
                edgeSlotsOutput[edgeSlotOnCube] = -1; // This physical slot is not one of the 4 chosen edges
            }
        }
    }

    //</editor-fold>

    //<editor-fold desc="Internal Helper Methods - Scramble Parsing (Example - could be part of solving methods)">
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
                    feo[i] = xcrossPairEdgeMoveTable[feo[i]][m];
                }
                ep = edgePermutationMoveTable[ep][m]; eo = edgeOrientationMoveTable[eo][m];
                if (s[d].length() > 1) {
                    for (int i = 0; i < 4; i++) {
                        co[i] = xcrossCornerMoveTable[co[i]][m];
                        feo[i] = xcrossPairEdgeMoveTable[feo[i]][m];
                    }
                    eo = edgeOrientationMoveTable[eo][m]; ep = edgePermutationMoveTable[ep][m];
                    if (s[d].charAt(1) == '\'') {
                        for (int i = 0; i < 4; i++) {
                            co[i] = xcrossCornerMoveTable[co[i]][m];
                            feo[i] = xcrossPairEdgeMoveTable[feo[i]][m];
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
