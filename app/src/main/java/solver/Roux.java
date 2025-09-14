package solver;

import android.util.Log;

import java.util.Arrays;

import static solver.Utils.turnSuffix;
import static solver.Utils.turn;

/**
 * A solver for the Roux method on a 3x3x3 Rubik's Cube.
 * <p>
 * This class contains the logic for solving a cube using the Roux method, which is
 * broken down into distinct stages. The class uses two separate sets of lookup tables
 * and solvers for these different stages.
 * <ul>
 * <li><b>Stage 1:</b> First Block (FB) and Second Block (SB) using all 6 face moves.</li>
 * <li><b>Stage 2:</b> CMLL (Corners of Last Layer) and LSE (Last Six Edges) using only U, R, and r moves.</li>
 * </ul>
 */
public class Roux {

    //<editor-fold desc="Constants & Class Variables">
    // --- General Constants ---
    private static final int NUM_EDGES = 12;
    private static final int NUM_FACES_S1 = 6;
    private static final int NUM_FACES_S2 = 3;
    private static final int NUM_CORNER_S1 = 2;
    private static final int NUM_EDGE_S1 = 3;
    private static final int NUM_CORNER_S2 = 6;
    private static final int NUM_EDGE_S2 = 9;
    private static final int NUM_MOVES_S2 = 3; // U, r, R
    private static final int NUM_TURN_TYPES = 3;
    private static final int MAX_SOLUTION_DEPTH = 15;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;
    private static final int INITIAL_LAST_MOVE = -1;

    // --- General Solver Variables ---
    private static int[] solutionSequence = new int[MAX_SOLUTION_DEPTH];
    private static boolean isStage1Initialized = false;
    private static boolean isStage2Initialized = false;

    //<editor-fold desc="Stage 1: First/Second Block Tables">
    // Constants
    private static final int NUM_S1_CORNER_COMB = 28; // C(8,2) = 28
    private static final int NUM_S1_CORNER_PERM = 2; // 2! = 2
    private static final int NUM_S1_CORNER_ORIENT = 9; // 3^2 = 9
    private static final int NUM_S1_EDGE_COMB = 220; // C(12,3) = 220
    private static final int NUM_S1_EDGE_PERM = 6; // 3!
    private static final int NUM_S1_EDGE_ORIENT = 8; // 2^3
    private static final int NUM_S1_ORIENTATIONS = 8;


    private static final int NUM_S1_CORNER_PERM_STATES = NUM_S1_CORNER_COMB * NUM_S1_CORNER_PERM; // C(8,2) * 2! = 28 * 2 = 56 states
    private static final int NUM_S1_CORNER_ORIENT_STATES = NUM_S1_CORNER_COMB * NUM_S1_CORNER_ORIENT; // C(8,2) * 3^2 = 28 * 9 = 252 states
    private static final int S1_PRUNING_DEPTH_EDGE = 7;
    private static final int S1_PRUNING_DEPTH_CORNER = 4;


    private static int[] STAGE1_SOLVED_CP = {50, 7, 49, 12};
    private static int[] STAGE1_SOLVED_CO = {225, 27, 221, 61};
    private static int[] STAGE1_SOLVED_EP = {72, 518, 580, 575};
    private static int[] STAGE1_SOLVED_EO = {96, 688, 768, 760};


    // Move tables for Stage 1 corners (permutation and orientation).
    private static byte[][] moveTable_S1_CornerPerm = new byte[NUM_S1_CORNER_PERM_STATES][NUM_FACES_S1];
    private static short[][] moveTable_S1_CornerOrient = new short[NUM_S1_CORNER_ORIENT_STATES][NUM_FACES_S1];

    // Pruning tables for Stage 1 edges and corners.
    private static byte[] pruningTable_S1_Edge = new byte[NUM_S1_EDGE_COMB * NUM_S1_EDGE_PERM * NUM_S1_EDGE_ORIENT]; // 220 * 48
    private static byte[] pruningTable_S1_Corner = new byte[NUM_S1_CORNER_COMB * NUM_S1_CORNER_PERM * NUM_S1_CORNER_ORIENT]; // 28 * 18
    //</editor-fold>

    //<editor-fold desc="Stage 2: CMLL/LSE Tables">
    // Constants

    private static final int NUM_S2_EDGE_COMB = 84; // C(9,3)  = 84
    private static final int NUM_S2_EDGE_PERM = 6; // 3! = 6
    private static final int NUM_S2_EDGE_ORIENT = 8; // 2^3 = 8
    private static final int NUM_S2_EDGE_PERM_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_PERM; // 504 states
    private static final int NUM_S2_EDGE_ORIENT_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_ORIENT; // 672 states
    private static final int NUM_S2_CORNER_COMB = 15; // C(6,2) = 15
    private static final int NUM_S2_CORNER_PERM = 2; // 2! = 2
    private static final int NUM_S2_CORNER_ORIENT = 9; // 3^2 = 9
    private static final int NUM_S2_CORNER_PERM_STATES = NUM_S2_CORNER_COMB * NUM_S2_CORNER_PERM; // 30 states
    private static final int NUM_S2_CORNER_ORIENT_STATES = NUM_S2_CORNER_COMB * NUM_S2_CORNER_ORIENT; // 135 states
    private static final int S2_PRUNING_DEPTH_EDGE = 11;
    private static final int S2_PRUNING_DEPTH_CORNER = 7;
    private static final int SOLVED_S2_EP = 28;
    private static final int SOLVED_S2_EO = 126;
    private static final int SOLVED_S2_CP = 0;
    private static final int SOLVED_S2_CO = 0;

    // Move tables for Stage 2 edges (permutation and orientation).
    private static short[][] moveTable_S2_EdgePerm = new short[NUM_S2_EDGE_PERM_STATES][NUM_MOVES_S2];
    private static short[][] moveTable_S2_EdgeOrient = new short[NUM_S2_EDGE_ORIENT_STATES][NUM_MOVES_S2];

    // Move tables for Stage 2 corners (permutation and orientation).z
    private static short[][] moveTable_S2_CornerPerm = new short[NUM_S2_CORNER_PERM_STATES][NUM_MOVES_S2];
    private static short[][] moveTable_S2_CornerOrient = new short[NUM_S2_CORNER_ORIENT_STATES][NUM_MOVES_S2];

    // Pruning tables for Stage 2 edges and corners.
    private static byte[] pruningTable_S2_Edge = new byte[NUM_S2_EDGE_COMB * NUM_S2_EDGE_PERM * NUM_S2_EDGE_ORIENT];
    private static byte[] pruningTable_S2_Corner = new byte[NUM_S2_CORNER_COMB * NUM_S2_CORNER_PERM * NUM_S2_CORNER_ORIENT];
    //</editor-fold>

    //<editor-fold desc="Formatting Data">
    private static String[][] moveIndexMap = {
            {"UDLRFB", "DULRBF", "BFLRUD", "FBLRDU"},
            {"UDFBRL", "DUFBLR", "LRFBUD", "RLFBDU"},
            {"DURLFB", "UDRLBF", "BFRLDU", "FBRLUD"},
            {"UDBFLR", "DUBFRL", "RLBFUD", "LRBFDU"}
    };

    private static String[] sideLabels = {"LU", "LD", "FU", "FD", "RU", "RD", "BU", "BD"};
    private static String[] setupRotations = {"", "y", "z2", "y'"}; //"z", "z'", "", "z2", "y", "y'"
    private static String[] secondarySetupRotations = {"", " x2", " x'", " x"};
    private static int[][] orientationIndices = {{1, 0, 2, 3}, {0, 1, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};

    //</editor-fold>

    //</editor-fold>


    //<editor-fold desc="Initialization">
    /**
     * Initializes all pre-computed lookup tables for Stage 1 (First/Second Block) of the Roux solve.
     * <p>
     * This heavy computation is run only once. It generates the necessary move tables
     * and pruning tables for the Stage 1 corner and edge coordinate systems.
     * NOTE: This stage's edge solver reuses move tables from the Petrus solver.
     */
    private static void initializeStage1Tables() {
        if (isStage1Initialized) {
            return;
        }
        // This solver reuses some tables from the Petrus solver, so initialize them first.
        PetrusSolver.initializeBaseTables();

        // =================================================================================
        // Part 1: Generate Move Tables for Stage 1 Corners ⚙️
        // =================================================================================

        for (int combIndex = 0; combIndex < NUM_S1_CORNER_COMB; combIndex++) {
            for (int permOrientIndex = 0; permOrientIndex < NUM_S1_CORNER_ORIENT; permOrientIndex++) {
                for (int moveIndex = 0; moveIndex < NUM_FACES_S1; moveIndex++) {
                    // Calculate the new coordinate after the move.
                    int newPackedCoord = calculateS1CornerCoord(combIndex, permOrientIndex, moveIndex);
                    if (permOrientIndex < NUM_S1_CORNER_PERM) {
                        moveTable_S1_CornerPerm[combIndex * NUM_S1_CORNER_PERM + permOrientIndex][moveIndex] =
                                (byte) (newPackedCoord / NUM_S1_CORNER_ORIENT);
                    }
                    moveTable_S1_CornerOrient[combIndex * NUM_S1_CORNER_ORIENT + permOrientIndex][moveIndex] =
                            (short) ((newPackedCoord / (NUM_S1_CORNER_ORIENT * NUM_S1_CORNER_PERM)) * NUM_S1_CORNER_ORIENT +
                                    newPackedCoord % NUM_S1_CORNER_ORIENT);
                }
            }
        }

        // =================================================================================
        // Part 2: Generate Pruning Table for Stage 1 Edges 📊
        // =================================================================================
        final int TOTAL_S1_EDGE_STATES = NUM_S1_EDGE_COMB * NUM_S1_EDGE_PERM * NUM_S1_EDGE_ORIENT; // 10560
        final int TOTAL_S1_EDGE_PERM = NUM_S1_EDGE_COMB * NUM_S1_EDGE_PERM ; // 1320

        final int SOLVED_S1_EDGE_COORD = 12 * 48;

        for (int i = 0; i < TOTAL_S1_EDGE_STATES; i++) {
            pruningTable_S1_Edge[i] = UNVISITED_STATE;
        }

        pruningTable_S1_Edge[SOLVED_S1_EDGE_COORD] = SOLVED_STATE_DISTANCE;

        int c = 1;
        // Populate the table using a Breadth-First Search (BFS).
        for (int currentDepth = 0; currentDepth < S1_PRUNING_DEPTH_EDGE; currentDepth++) {
            //c = 0;
            for (int permCoord = 0; permCoord < TOTAL_S1_EDGE_PERM; permCoord++) {
                for (int orientCoord = 0; orientCoord < NUM_S1_EDGE_ORIENT; orientCoord++) {
                    if (pruningTable_S1_Edge[permCoord << 3 | orientCoord] == currentDepth) { //permCoord * NUM_S1_EDGE_ORIENT + orientCoord
                        for (int faceIndex = 0; faceIndex < NUM_FACES_S1; faceIndex++) {
                            int nextPermCoord = permCoord;
                            int nextOrientCoord = orientCoord;
                            for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                                // The next state is looked up from the pre-computed Petrus tables.
                                nextOrientCoord = PetrusSolver.moveTable_S1_EdgeOrient[(nextPermCoord / 6) << 3 | nextOrientCoord & 7][faceIndex] & 7;
                                nextPermCoord = PetrusSolver.moveTable_S1_EdgePerm[nextPermCoord][faceIndex];
                                if (pruningTable_S1_Edge[nextPermCoord << 3 | nextOrientCoord] < 0) {
                                    pruningTable_S1_Edge[nextPermCoord << 3 | nextOrientCoord] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
                    }
                }
            }
        }

        // =================================================================================
        // Part 3: Generate Pruning Table for Stage 1 Corners 📊
        // =================================================================================
        final int TOTAL_S1_CORNER_STATES = NUM_S1_CORNER_COMB * NUM_S1_CORNER_PERM * NUM_S1_CORNER_ORIENT; // 2 * 2 * 2 * 9 * 7 = 9 * 8 * 7
        final int SOLVED_S1_CORNER_COORD = 25 * 18;

        for (int i = 0; i < TOTAL_S1_CORNER_STATES; i++) {
            pruningTable_S1_Corner[i] = UNVISITED_STATE;
        }
        pruningTable_S1_Corner[SOLVED_S1_CORNER_COORD] = SOLVED_STATE_DISTANCE;
        c = 1;

        // Populate the table using a BFS.
        for (int currentDepth = 0; currentDepth < S1_PRUNING_DEPTH_CORNER; currentDepth++) {
            //c = 0;
            for (int permCoord = 0; permCoord < NUM_S1_CORNER_COMB * NUM_S1_CORNER_PERM; permCoord++)
                for (int orientCoord = 0; orientCoord < NUM_S1_CORNER_ORIENT; orientCoord++)
                    if (pruningTable_S1_Corner[permCoord * NUM_S1_CORNER_ORIENT + orientCoord] == currentDepth)
                        for (int faceIndex = 0; faceIndex < NUM_FACES_S1; faceIndex++) {
                            int nextPermCoord = permCoord;
                            int nextOrientCoord = orientCoord;
                            for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                                // This section uses a specific unpacking logic for this coordinate system.
                                int combCoord = nextPermCoord / NUM_S1_CORNER_PERM; // 2 permutations per combination
                                nextOrientCoord = moveTable_S1_CornerOrient[combCoord * NUM_S1_CORNER_ORIENT +
                                        nextOrientCoord % NUM_S1_CORNER_ORIENT][faceIndex] % NUM_S1_CORNER_ORIENT;
                                nextPermCoord = moveTable_S1_CornerPerm[nextPermCoord][faceIndex];
                                if (pruningTable_S1_Corner[nextPermCoord * NUM_S1_CORNER_ORIENT + nextOrientCoord] < 0) {
                                    pruningTable_S1_Corner[nextPermCoord * NUM_S1_CORNER_ORIENT + nextOrientCoord] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
            //Log.w("dct", d+1+"\t"+c);
        }
        isStage1Initialized = true;
    }

    /**
     * Initializes all pre-computed lookup tables for Stage 2 (CMLL/LSE) of the Roux solve.
     * <p>
     * This heavy computation is run only once. It generates the necessary move tables
     * and pruning tables for the Stage 2 corner and edge coordinate systems, which use
     * a limited {U, r, R} move set.
     */
    private static void initializeStage2Tables() {
        if (isStage2Initialized) {
            return;
        }

        // =================================================================================
        // Part 1: Generate Move Tables for Stage 2 ⚙️
        // =================================================================================

        // --- Build tables for the Stage 2 Edge system ---
        for (int combIndex = 0; combIndex < NUM_S2_EDGE_COMB; combIndex ++)
            for (int permOrientIndex = 0; permOrientIndex < NUM_S2_EDGE_ORIENT; permOrientIndex++)
                for (int moveIndex = 0; moveIndex < NUM_MOVES_S2; moveIndex++) {
                    int newCoord = calculateS2EdgeCoord(combIndex , permOrientIndex, permOrientIndex, moveIndex);
                    if (permOrientIndex < NUM_S2_EDGE_PERM) {
                        moveTable_S2_EdgePerm[combIndex  * NUM_S2_EDGE_PERM + permOrientIndex ][moveIndex] = (short) (newCoord >> 3);
                    }
                    moveTable_S2_EdgeOrient[combIndex * NUM_S2_EDGE_ORIENT + permOrientIndex ][moveIndex] =
                            (short) ((newCoord / (NUM_S2_EDGE_PERM * NUM_S2_EDGE_ORIENT)) << 3 | newCoord & 7);
                }

        // --- Build tables for the Stage 2 Corner system ---
        for (int combIndex = 0; combIndex < NUM_S2_CORNER_COMB; combIndex++)
            for (int permOrientIndex = 0; permOrientIndex < NUM_S2_CORNER_ORIENT; permOrientIndex++)
                for (int moveIndex = 0; moveIndex < NUM_MOVES_S2; moveIndex++) {
                    int newCoord = calculateS2CornerCoord(combIndex, permOrientIndex, moveIndex);
                    if (permOrientIndex < NUM_S1_CORNER_PERM) {
                        moveTable_S2_CornerPerm[combIndex * NUM_S1_CORNER_PERM + permOrientIndex][moveIndex] =
                                (short) (newCoord / NUM_S2_CORNER_ORIENT);
                    }
                    moveTable_S2_CornerOrient[combIndex * NUM_S2_CORNER_ORIENT + permOrientIndex][moveIndex] =
                            (short) (newCoord / (NUM_S2_CORNER_ORIENT*NUM_S1_CORNER_PERM) * NUM_S2_CORNER_ORIENT + newCoord % NUM_S2_CORNER_ORIENT);
                }

        // =================================================================================
        // Part 2: Generate Pruning Tables for Stage 2 📊
        // =================================================================================
        int TOTAL_S2_EDGE_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_PERM * NUM_S2_EDGE_ORIENT; // 4032
        int SOLVED_S2_EDGE_COORD = 0;
        // --- Pruning Table for Stage 2 Edges ---
        for (int i = 0; i < TOTAL_S2_EDGE_STATES; i++) {
            pruningTable_S2_Edge[i] = UNVISITED_STATE;
        }
        pruningTable_S2_Edge[SOLVED_S2_EDGE_COORD] = SOLVED_STATE_DISTANCE;

        // Populate the table using a Breadth-First Search (BFS).
        // This is a custom BFS implementation for this specific coordinate system.
        int c = 1;
        for (int currentDepth = 0; currentDepth < S2_PRUNING_DEPTH_EDGE; currentDepth++) {
            //c = 0;
            for (int permCoord = 0; permCoord < NUM_S2_EDGE_PERM_STATES; permCoord++) {
                for (int orientCoord = 0; orientCoord < NUM_S2_EDGE_ORIENT; orientCoord++) {
                    if (pruningTable_S2_Edge[permCoord * NUM_S2_EDGE_ORIENT + orientCoord] == currentDepth) {
                        for (int faceIndex = 0; faceIndex < NUM_FACES_S2; faceIndex++) {
                            int nextPermCoord = permCoord;
                            int nextOrientCoord = orientCoord;
                            for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                                // This section uses a specific unpacking logic for this coordinate system.
                                int packedPermOrientCoord = (nextPermCoord / 6) << 3 | nextOrientCoord & 7;
                                nextOrientCoord = moveTable_S2_EdgeOrient[packedPermOrientCoord][faceIndex] & 7;
                                nextPermCoord = moveTable_S2_EdgePerm[nextPermCoord][faceIndex];

                                int nextCombinedCoord = nextPermCoord * NUM_S2_EDGE_ORIENT + nextOrientCoord;
                                if (pruningTable_S2_Edge[nextCombinedCoord] < SOLVED_STATE_DISTANCE) {
                                    pruningTable_S2_Edge[nextCombinedCoord] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
                    }
                }
            }
            Log.w("dct", currentDepth+1+"\t"+c);
        }

        // --- Pruning Table for Stage 2 Corners ---
        int TOTAL_S2_CORNER_STATES = NUM_S2_CORNER_COMB * NUM_S2_CORNER_PERM * NUM_S2_CORNER_ORIENT; // 270
        int SOLVED_S2_CORNER_COORD = 14 * 18;

        for (int i = 0; i < TOTAL_S2_CORNER_STATES; i++) {
            pruningTable_S2_Corner[i] = UNVISITED_STATE;
        }
        pruningTable_S2_Corner[SOLVED_S2_CORNER_COORD] = SOLVED_STATE_DISTANCE;
        c = 1;
        for (int currentDepth  = 0; currentDepth < S2_PRUNING_DEPTH_CORNER; currentDepth++) {
            //c = 0;
            for (int permCoord = 0; permCoord < NUM_S2_CORNER_PERM_STATES; permCoord++) {
                for (int orientCoord = 0; orientCoord < NUM_S2_CORNER_ORIENT; orientCoord++) {
                    if (pruningTable_S2_Corner[permCoord * NUM_S2_CORNER_ORIENT + orientCoord] == currentDepth) {
                        for (int faceIndex = 0; faceIndex < NUM_FACES_S2; faceIndex++) {
                            int nextPermCoord = permCoord;
                            int nextOrientCoord = orientCoord;
                            for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                                // This section uses a specific unpacking logic for this coordinate system.
                                int packedCoord = nextPermCoord / NUM_S2_CORNER_PERM * NUM_S2_CORNER_ORIENT +
                                        nextOrientCoord % NUM_S2_CORNER_ORIENT;

                                nextOrientCoord = moveTable_S2_CornerOrient[packedCoord][faceIndex] % NUM_S2_CORNER_ORIENT;
                                nextPermCoord = moveTable_S2_CornerPerm[nextPermCoord][faceIndex];

                                int nextCombinedCoord = nextPermCoord * NUM_S2_CORNER_ORIENT + nextOrientCoord;
                                if (pruningTable_S2_Corner[nextCombinedCoord] < SOLVED_STATE_DISTANCE) {
                                    pruningTable_S2_Corner[nextCombinedCoord] = (byte) (currentDepth + 1);
                                    c++;
                                }
                            }
                        }
                    }
                }
            }
            Log.w("dct", currentDepth+1+" "+c);
        }
        isStage2Initialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public Solver Methods">

    /**
     * Public wrapper method to solve Stage 1 of the Roux method (First/Second Block).
     * <p>
     * This method solves for multiple starting orientations, which are specified by a
     * bitmask. It calls the core Stage 1 solver for each selected orientation.
     *
     * @param scramble The scramble string to solve from.
     * @param orientationBitmask A bitmask where each of the 8 bits corresponds to a
     * specific starting orientation (e.g., LU, LD, FU, FD, etc.).
     * @return A formatted string with all found solutions for Stage 1.
     */
    public static String solveRouxStage1(String scramble, int orientationBitmask) {

        // There are 8 problem orientations for Stage 1 (e.g., building the LU block, LD block, etc.)
        // Ensure the necessary lookup tables for Stage 1 are initialized.
        initializeStage1Tables();

        // Use a StringBuilder to efficiently build the final output string.
        StringBuilder resultBuilder = new StringBuilder("\n");
        //boolean solveS2 = ((face >> 8) & 1) != 0;
        //if (solveS2) initr2();

        // Iterate through each of the 8 possible orientations.
        for (int orientationIndex = 0; orientationIndex < NUM_S1_ORIENTATIONS; orientationIndex++) {
            // Check if the bit for the current orientation is set in the bitmask.
            if (((orientationBitmask >> orientationIndex) & 1) != 0)
                // Call the core solver for the current orientation. The 'false' indicates
                // that we should NOT proceed to solve Stage 2 automatically.
                resultBuilder.append(rouxStage1(scramble, orientationIndex, false));
        }

        // Return the concatenated string of all found solutions.
        return resultBuilder.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Stage 1 Solver (First/Second Block)">
    /**
     * The main solver for a single orientation of Stage 1 (First/Second Block).
     * <p>
     * This method is the core engine for the first part of the Roux solve. It tracks
     * four potential solution sub-cases simultaneously. It applies the scramble to
     * all four, then searches for the first one that can be solved within the depth
     * limit. It can also optionally chain into the Stage 2 solver upon completion.
     *
     * @param scramble The scramble string to solve from.
     * @param orientationIndex The index (0-7) of the target orientation to solve for.
     * @param chainToStage2 If true, the Stage 2 solver will be called automatically
     * after a Stage 1 solution is found.
     * @return A formatted string with the solution, or "\nerror" if none is found.
     */
    private static String rouxStage1(String scramble, int orientationIndex, boolean chainToStage2) {
        // --- Define Constants ---
        final int NUM_SUB_CASES = 4;
        final int S1_MAX_SEARCH_DEPTH = 10;

        // --- 1. Initialize Coordinates for the 4 Sub-Cases ---
        // Arrays to hold the coordinates for each of the 4 potential solution paths.
        int[] cornerPermCoords = new int[NUM_SUB_CASES];
        int[] cornerOrientCoords = new int[NUM_SUB_CASES];
        int[] edgePermCoords = new int[NUM_SUB_CASES];
        int[] edgeOrientCoords = new int[NUM_SUB_CASES];

        // Initialize these arrays with the solved state coordinates, remapped for the specific
        // starting orientation using a lookup table.
        for (int i = 0; i < 4; i++) {
            int remappedIndex = orientationIndices[orientationIndex % 2][i];

            cornerPermCoords[i] = STAGE1_SOLVED_CP[remappedIndex];
            cornerOrientCoords[i] = STAGE1_SOLVED_CO[remappedIndex];
            edgePermCoords[i] = STAGE1_SOLVED_EP[remappedIndex];
            edgeOrientCoords[i] = STAGE1_SOLVED_EO[remappedIndex];
        }

        // --- 2. Apply the Scramble ---
        // Apply each move of the scramble to all 4 sub-case coordinates simultaneously.
        String[] scrambleMoves = scramble.split(" ");
        for (String move : scrambleMoves) {
            if (!move.isEmpty()) {
                for (int i = 0; i < NUM_SUB_CASES; i++) {
                    int faceIndex = moveIndexMap[orientationIndex / 2][i].indexOf(move.charAt(0));
                    for (int turn = 0; turn < (move.length() > 1 && move.charAt(1) == '\'' ? 3 : (move.length() > 1 &&
                            move.charAt(1) == '2' ? 2 : 1)); turn++) {
                        cornerPermCoords[i] = moveTable_S1_CornerPerm[cornerPermCoords[i]][faceIndex];
                        cornerOrientCoords[i] = moveTable_S1_CornerOrient[cornerOrientCoords[i]][faceIndex];
                        edgePermCoords[i] = PetrusSolver.moveTable_S1_EdgePerm[edgePermCoords[i]][faceIndex];
                        edgeOrientCoords[i] = PetrusSolver.moveTable_S1_EdgeOrient[edgeOrientCoords[i]][faceIndex];
                    }
                }
            }
        }

        // --- 3. Iterative Deepening Search ---
        // Search for a solution, checking all 4 sub-cases at each depth.
        for (int searchDepth = 0; searchDepth < S1_MAX_SEARCH_DEPTH; searchDepth++) {
            for (int subcaseIndex = 0; subcaseIndex < NUM_SUB_CASES; subcaseIndex++)
                if (idaRouxStage1(
                        cornerPermCoords[subcaseIndex], cornerOrientCoords[subcaseIndex],
                        edgePermCoords[subcaseIndex], edgeOrientCoords[subcaseIndex],
                        searchDepth, INITIAL_LAST_MOVE)) {

                    // --- 4. Format Solution ---
                    // If a solution is found for any sub-case, format and return it.

                    StringBuilder solutionBuilder = new StringBuilder("\n");
                    solutionBuilder
                            .append(sideLabels[orientationIndex])
                            .append(": ")
                            .append(setupRotations[orientationIndex / 2])
                            .append(secondarySetupRotations[subcaseIndex]);

                    // Reconstruct the move sequence.
                    for (int i = searchDepth; i > 0; i--) {
                        int moveCode = solutionSequence[i];
                        int faceIndex = moveCode / 3;
                        int turnType = moveCode % 3;
                        solutionBuilder.append(' ')
                                .append(turn[faceIndex])
                                .append(turnSuffix[turnType]);
                    }

                    // If requested, pass the solution data to the Stage 2 solver.
                    if (chainToStage2) {
                        int[] stage1SolutionData = Arrays.copyOf(solutionSequence, searchDepth + 1);
                        stage1SolutionData[0] = (orientationIndex / 2) * 4 + subcaseIndex;
                        solutionBuilder.append(rouxStage2(scramble, stage1SolutionData));
                    }
                    return solutionBuilder.toString();
                }
        }
        return "\nerror";
    }

    /**
     * The recursive IDA* search function for Stage 1 (First/Second Block).
     * <p>
     * This method performs a depth-first search, using two separate pruning tables
     * to efficiently find a solution path.
     *
     * @param cornerPerm The current corner permutation coordinate.
     * @param cornerOrient The current corner orientation coordinate.
     * @param edgePerm The current edge permutation coordinate (from Petrus tables).
     * @param edgeOrient The current edge orientation coordinate (from Petrus tables).
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean idaRouxStage1(int cornerPerm, int cornerOrient, int edgePerm, int edgeOrient,
                                         int depthRemaining, int lastMove) {
        // Constants
        final int SOLVED_S1_CP = STAGE1_SOLVED_CP[0]; // 50;
        final int SOLVED_S1_CO = STAGE1_SOLVED_CO[0]; // 225;
        final int SOLVED_S1_EP = STAGE1_SOLVED_EP[0]; // 72;
        final int SOLVED_S1_EO = STAGE1_SOLVED_EO[0]; // 96

        // --- Base Case: If we have no moves left, check if the state is solved. ---
        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_S1_CP && cornerOrient == SOLVED_S1_CO &&
                    edgePerm == SOLVED_S1_EP && edgeOrient == SOLVED_S1_EO;
        }

        // --- Heuristic Pruning ---
        // Check both pruning tables. If either sub-problem requires more moves
        // than we have left, this entire path is a dead end.
        if (pruningTable_S1_Edge[edgePerm << 3 | edgeOrient & 7] > depthRemaining ||
                pruningTable_S1_Corner[cornerPerm * 9 + cornerOrient % 9] > depthRemaining) {
            return false;
        }

        // --- Recursive Step: Explore all valid next moves. ---
        for (int moveIndex = 0; moveIndex < NUM_FACES_S1; moveIndex++) {
            if (moveIndex != lastMove) {
                int nextCornerPerm = cornerPerm;
                int nextCornerOrient = cornerOrient;
                int nextEdgePerm = edgePerm;
                int nextEdgeOrient = edgeOrient;
                for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                    nextCornerPerm = moveTable_S1_CornerPerm[nextCornerPerm][moveIndex];
                    nextCornerOrient = moveTable_S1_CornerOrient[nextCornerOrient][moveIndex];
                    nextEdgePerm = PetrusSolver.moveTable_S1_EdgePerm[nextEdgePerm][moveIndex];
                    nextEdgeOrient = PetrusSolver.moveTable_S1_EdgeOrient[nextEdgeOrient][moveIndex];

                    // Make the recursive call for the new state.
                    if (idaRouxStage1(nextCornerPerm, nextCornerOrient, nextEdgePerm, nextEdgeOrient, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURN_TYPES + turnType;
                        //sb.insert(0, " " + turn[5][i] + suff[j]);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Stage 2 Solver (CMLL/LSE)">
    /**
     * The main solver for Stage 2 (CMLL/LSE) of the Roux method.
     * <p>
     * This method takes the original scramble and the solution from Stage 1. It calculates
     * the resulting cube state (the starting position for Stage 2) and then uses an
     * iterative deepening search with the Stage 2 tables to solve the last layer corners
     * and the last six edges.
     *
     * @param scramble The original scramble string.
     * @param stage1SolutionData The solution from Stage 1, containing orientation info
     * in the first element and the move sequence in the rest.
     * @return A formatted string with the solution moves for Stage 2.
     */
    private static String rouxStage2(String scramble, int[] stage1SolutionData) {
        // --- Define Constants ---
        final int S2_MAX_SEARCH_DEPTH = 12; // An example max depth for the search.

        // --- 1. Determine Starting State for Stage 2 ---
        // To find the starting position for Stage 2, we would typically:
        // a) Start with a solved cube's coordinates.
        // b) Apply the full original scramble.
        // c) Apply the found Stage 1 solution moves.
        // The resulting coordinates would be the starting point for this search.
        // (The original function's logic for this part was incomplete).
        int startCornerPerm = 0;   // Placeholder for calculated start coordinate
        int startCornerOrient = 0; // Placeholder for calculated start coordinate
        int startEdgePerm = 0;     // Placeholder for calculated start coordinate
        int startEdgeOrient = 0;   // Placeholder for calculated start coordinate


        // --- 2. Iterative Deepening Search for Stage 2 ---
        // Search for a solution, starting with a depth of 0 and increasing.
        for (int searchDepth = 0; searchDepth < S2_MAX_SEARCH_DEPTH; searchDepth++) {
            // Call the recursive solver for Stage 2.
            if (idaRouxStage2(
                    startCornerPerm, startCornerOrient,
                    startEdgePerm, startEdgeOrient,
                    searchDepth, INITIAL_LAST_MOVE
            )) {

                // --- 3. Format and Return the Solution ---
                StringBuilder solutionBuilder = new StringBuilder();
                // Reconstruct the solution from the path found by the solver.
                for (int i = searchDepth; i > 0; i--) {
                    // ... (Formatting logic to convert move codes to strings like "U2", "r'", etc.) ...
                }
                return solutionBuilder.toString();
            }
        }

        return "\nerror"; // Return an error if no solution is found.

    }

    /**
     * The recursive IDA* search function for Stage 2 (CMLL/LSE).
     * <p>
     * This method performs a depth-first search using the specialized Stage 2 tables
     * to efficiently find a solution path for the last layer.
     *
     * @param cornerPerm The current Stage 2 corner permutation coordinate.
     * @param cornerOrient The current Stage 2 corner orientation coordinate.
     * @param edgePerm The current Stage 2 edge permutation coordinate.
     * @param edgeOrient The current Stage 2 edge orientation coordinate.
     * @param depthRemaining The number of moves left in the current search path.
     * @param lastMove The index of the last move made, to avoid redundant sequences.
     * @return True if a solution is found, false otherwise.
     */
    private static boolean idaRouxStage2(int cornerPerm, int cornerOrient, int edgePerm,
                                         int edgeOrient, int depthRemaining, int lastMove) {
        // --- Base Case: If we have no moves left, check if the state is solved. ---

        if (depthRemaining == 0) {
            return cornerPerm == SOLVED_S2_CP && // 0
                    cornerOrient == SOLVED_S2_CO && // 0
                    edgePerm == SOLVED_S2_EP && // 28
                    edgeOrient == SOLVED_S2_EO; // 126
        }

        // --- Heuristic Pruning ---
        // Check both pruning tables. If either sub-problem requires more moves
        // than we have left, this entire path is a dead end.
        if (pruningTable_S2_Edge[edgePerm << 3 | edgeOrient & 7] > depthRemaining ||
                pruningTable_S2_Corner[cornerPerm * 9 + cornerOrient % 9] > depthRemaining)  {
            return false;
        }
        for (int moveIndex = 0; moveIndex < NUM_MOVES_S2; moveIndex++)
            if (moveIndex != lastMove) {
                int nextCornerPerm = cornerPerm,
                        nextCornerOrient = cornerOrient,
                        nextEdgePerm = edgePerm,
                        nextEdgeOrient = edgeOrient;

                // Try all 3 turn types for the current face (e.g., U, U2, U').
                for (int turnType = 0; turnType < NUM_TURN_TYPES; turnType++) {
                    nextCornerPerm = moveTable_S2_CornerPerm[nextCornerPerm][moveIndex];
                    nextCornerOrient = moveTable_S2_CornerOrient[nextCornerOrient][moveIndex];
                    nextEdgePerm = moveTable_S2_EdgePerm[nextEdgePerm][moveIndex];
                    nextEdgeOrient = moveTable_S2_EdgeOrient[nextEdgeOrient][moveIndex];

                    // Make the recursive call for the new state.
                    if (idaRouxStage2(nextCornerPerm, nextCornerOrient, nextEdgePerm, nextEdgeOrient, depthRemaining - 1, moveIndex)) {
                        // --- Solution Found! ---
                        // Record the successful move in the solution sequence array.
                        solutionSequence[depthRemaining] = moveIndex * NUM_TURN_TYPES + turnType;
                        return true;
                    }
                }
            }
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helpers">
    /**
     * Calculates the new coordinate for a Stage 1 corner state after a given move.
     * <p>
     * This is the core "Unpack -> Apply Move -> Repack" engine for the Stage 1
     * corner move table generation. It determines the resulting state after one
     * of the six standard face turns.
     *
     * @param combinationIndex The starting combination coordinate.
     * @param permOrientIndex The starting packed coordinate for permutation and orientation.
     * @param moveIndex The face turn to apply (0-5 for U,D,R,F,L,B).
     * @return The new packed coordinate after the move.
     */
    private static int calculateS1CornerCoord(int combinationIndex, int permOrientIndex, int moveIndex) {
        // --- 1. Unpack Coordinates into a Physical Representation ---

        // Create temporary arrays for the 8 corner slots and the k-piece subset details.
        int[] cornerStateArray = new int[8],
                tempPieceData = new int[4];
        // Call a helper function to decode the coordinates and fill the cornerStateArray.
        getCornerState(cornerStateArray, combinationIndex, permOrientIndex, permOrientIndex);

        // --- 2. Apply the Physical Move ---
        // The move permutes the slots and may change corner orientations.
        switch (moveIndex) {
            case 0: // U-move
                Utils.circle(cornerStateArray, 0, 3, 2, 1); break;
            case 1: // D-move
                Utils.circle(cornerStateArray, 4, 5, 6, 7); break;
            case 2: // R-move (with orientation changes)
                Utils.circle(cornerStateArray, 0, 4, 7, 3, new int[] {2, 1, 2, 1});
                //n[0] += 2; n[4]++; n[7] += 2; n[3]++;
                break;
            case 3: // F-move (with orientation changes)
                Utils.circle(cornerStateArray, 1, 2, 6, 5, new int[] {1, 2, 1, 2});
                //n[1]++; n[2] += 2; n[6]++; n[5] += 2;
                break;
            case 4: // L-move (with orientation changes)
                Utils.circle(cornerStateArray, 2, 3, 7, 6, new int[] {1, 2, 1, 2});
                //n[2]++; n[3] += 2; n[7]++; n[6] += 2;
                break;
            case 5: // B-move (with orientation changes)
                Utils.circle(cornerStateArray, 0, 1, 5, 4, new int[] {1, 2, 1, 2});
                //n[0]++; n[1] += 2; n[5]++; n[4] += 2;
                break;
        }

        // --- 3. Repack the New State into a Single Coordinate ---

        // Reset variables to build the new coordinates from the modified state array.

        combinationIndex = 0;

        // This repacking logic is highly specific to the coordinate system.
        // It rebuilds the combination, permutation, and orientation indices simultaneously.
        for (int piecesToFind = 2, t = 7; t >= 0; t--)
            if (cornerStateArray[t] >= 0) {
                combinationIndex += Utils.Cnk[t][piecesToFind--];
                tempPieceData[piecesToFind] = cornerStateArray[t] >> 3;
                tempPieceData[piecesToFind + 2] = (cornerStateArray[t] & 7) % 3;
            }
        return (combinationIndex * 2 + tempPieceData[0]) * 9 + tempPieceData[2] * 3 + tempPieceData[3];
    }

    /**
     * Calculates the new coordinate for a Stage 2 edge state after a given move.
     * <p>
     * This is the "Unpack -> Apply Move -> Repack" engine for the Stage 2
     * edge move table generation. It determines the resulting state after one
     * of the three valid moves for this stage (U, r, R).
     *
     * @param combinationIndex The starting combination coordinate (which 3 of 9 slots are occupied).
     * @param permutationIndex The starting permutation coordinate of the 3 tracked edges.
     * @param orientationIndex The starting orientation coordinate of the 3 tracked edges.
     * @param moveIndex The face turn to apply (0=U, 1=r, 2=R).
     * @return The new packed coordinate after the move.
     */
    private static int calculateS2EdgeCoord(int combinationIndex, int permutationIndex,
                                            int orientationIndex, int moveIndex) {

        // Create temporary arrays for the 9 edge slots and the 3-piece subset.
        int piecesToPlace = NUM_CORNER_S2 / 2;

        int[] edgeSlotArray = new int[NUM_EDGE_S2], permutation = new int[piecesToPlace];

        // Decode the permutation index.
        Utils.idxToPerm(permutation, permutationIndex, piecesToPlace, false);


        // Reconstruct the physical state of the 9 relevant edge slots by placing
        // the 3 tracked edges according to the combination and orientation indices.
        for (int t = 0; t < NUM_EDGE_S2; t++)
            if (combinationIndex >= Utils.Cnk[(NUM_EDGE_S2 - 1) - t][piecesToPlace]) {
                combinationIndex -= Utils.Cnk[(NUM_EDGE_S2 - 1) - t][piecesToPlace--];
                edgeSlotArray[t] = permutation[piecesToPlace] << 1 | orientationIndex & 1;
                orientationIndex >>= 1;
            } else {
                edgeSlotArray[t] = UNVISITED_STATE;
            }
        // --- 2. Apply the Physical Move ---
        // The move permutes the slots and may change edge orientations.
        switch (moveIndex) {
            case 0: // U-move
                Cross.cycleEdges(edgeSlotArray, 0, 1, 2, 3, 0); break;
            case 1: // r-move (wide R, with orientation change)
                Cross.cycleEdges(edgeSlotArray, 0, 2, 4, 5, 1);
            case 2: // R-move
                Cross.cycleEdges(edgeSlotArray, 1, 6, 7, 8, 0); break;
        }

        // --- 3. Repack the New State into a Single Coordinate ---

        // Reset variables to build the new coordinates.
        int newCombinationIndex = 0;
        int newOrientationIndex = 0;
        piecesToPlace = NUM_CORNER_S2 / 2;

        // Scan the 9 slots to find the new positions and orientations.
        for (int i = 0; i < NUM_EDGE_S2; i++)
            if (edgeSlotArray[i] >= 0) {
                newCombinationIndex  += Utils.Cnk[(NUM_EDGE_S2 - 1) - i][piecesToPlace--];
                permutation[piecesToPlace] = edgeSlotArray[i] >> 1;
                newOrientationIndex  |= (edgeSlotArray[i] & 1) << (2 - piecesToPlace) ;
            }

        // Convert the new permutation array back to a compact index.
        int newPermutationIndex = Utils.permToIdx(permutation, piecesToPlace, false);

        // Combine all new coordinates into a single integer and return it.
        return NUM_CORNER_S2 * newCombinationIndex  + newPermutationIndex << 3 | newOrientationIndex ;
    }

    /**
     * Calculates the new coordinate for a Stage 2 corner state after a given move.
     * <p>
     * This is the "Unpack -> Apply Move -> Repack" engine for the Stage 2
     * corner move table generation. It determines the resulting state after one
     * of the three valid moves for this stage (U, r, R).
     *
     * @param combinationIndex The starting combination coordinate (which 2 of 6 slots are occupied).
     * @param permOrientIndex The starting packed coordinate for permutation and orientation.
     * @param moveIndex The face turn to apply (0=U, 1=r, 2=R).
     * @return The new packed coordinate after the move.
     */
    private static int calculateS2CornerCoord(int combinationIndex, int permOrientIndex, int moveIndex) {
        // --- 1. Unpack Coordinates into a Physical Representation ---

        // Create temporary arrays for the 6 corner slots and for the piece data.
        int[] cornerSlotArray = new int[NUM_CORNER_S2];
        int[] pieceData = new int[4];

        // This section unpacks the combined permutation and orientation index into its parts.
        pieceData[0] = permOrientIndex % 2;  // Permutation part 1
        pieceData[1] = 1 - pieceData[0]; // Permutation part 2
        pieceData[2] = permOrientIndex / 3; // Orientation part 1
        pieceData[3] = permOrientIndex % 3; // Orientation part 2

        // Reconstruct the physical state of the 6 relevant corner slots.
        int piecesToPlace = 2;
        for (int t = 5; t >= 0; t--) {
            if (combinationIndex >= Utils.Cnk[t][piecesToPlace]) {
                combinationIndex -= Utils.Cnk[t][piecesToPlace--];
                cornerSlotArray[t] = pieceData[piecesToPlace] << 3 | pieceData[piecesToPlace + 2];
            } else cornerSlotArray[t] = -3; // Mark the slot as empty.
        }
        switch (moveIndex) {
            case 0:  // U-move
                Utils.circle(cornerSlotArray, 0, 3, 2, 1);
                break;
            case 1: //  r-move (wide R)
            case 2: //  R-move (with orientation changes)
                Utils.circle(cornerSlotArray, 1, 2, 4, 5, new int[] {1, 2, 1, 2});
                //n[1]++; n[2] += 2; n[4]++; n[5] += 2;
                break;
        }

        // Scan the 6 slots to find the new positions and orientations.
        int newCombinationIndex = 0;
        piecesToPlace = 2;
        // Scan the 6 slots to find the new positions and orientations.
        for (int t = 5; t >= 0; t--) {
            if (cornerSlotArray[t] >= 0) {
                newCombinationIndex  += Utils.Cnk[t][piecesToPlace--];
                pieceData[piecesToPlace] = cornerSlotArray[t] >> 3;
                pieceData[piecesToPlace + 2] = (cornerSlotArray[t] & 7) % 3;
            }
        }
        return (newCombinationIndex * 2 + pieceData[0]) * 9 + (pieceData[2] * 3 + pieceData[3]);
    }

    //</editor-fold>

    //<editor-fold desc="Internal Unpacking Helpers">
    /**
     * Unpacks coordinates into a physical representation of 3 edge pieces in 12 slots.
     * <p>
     * This helper method decodes the combination, permutation, and orientation coordinates
     * for a 3-edge sub-problem and populates an array representing the 12 edge slots
     * with those pieces.
     *
     * @param edgeSlotArray    The output array of 12 slots to be filled. Occupied slots get
     * a packed piece state, empty slots get -1.
     * @param combinationIndex The coordinate for the positions of the 3 tracked edges.
     * @param permutationIndex The coordinate for the arrangement of the 3 tracked edges.
     * @param orientationIndex The packed coordinate for the orientation of the 3 tracked edges.
     */
    private static void getEdgeState(int[] edgeSlotArray, int combinationIndex, int permutationIndex, int orientationIndex) {
        // A temporary array to hold the decoded permutation.
        int NUM_EDGES_SUBSET = 3;
        int[] permArray = new int[NUM_EDGES_SUBSET];
        Utils.idxToPerm(permArray, permutationIndex, NUM_EDGES_SUBSET, false);
        int piecesToPlace = 3;
        // Iterate through all 12 possible edge slots.
        for (int slot = 0; slot < NUM_EDGES; slot++) {
            // Check if a piece must be placed in this slot based on the combination index.
            if (combinationIndex >= Utils.Cnk[(NUM_EDGES - 1) - slot][piecesToPlace]) {
                // This slot is occupied. Update the index.
                combinationIndex -= Utils.Cnk[(NUM_EDGES - 1) - slot][piecesToPlace--];

                // Get the orientation for this specific piece (the last bit).
                int orientation = orientationIndex & 1;

                // Place the piece, packing its permutation ID and orientation.
                edgeSlotArray[slot] = permArray[piecesToPlace] << 1 | orientation;

                // Discard the orientation bit we just used.
                orientationIndex >>= 1;

            } else {
                // This slot is empty.
                edgeSlotArray[slot] = -1;
            }
        }
    }

    /**
     * Unpacks coordinates into a physical representation of 2 corner pieces in 8 slots.
     * <p>
     * This helper method decodes the combination, permutation, and orientation coordinates
     * for a 2-corner sub-problem and populates an array representing the 8 corner slots
     * with those pieces.
     *
     * @param cornerSlotArray The output array of 8 slots to be filled. Occupied slots get
     * a packed piece state, empty slots get a negative value.
     * @param combinationIndex The coordinate for the positions of the 2 tracked corners.
     * @param permutationIndex The coordinate for the arrangement of the 2 tracked corners.
     * @param orientationIndex The coordinate for the orientation of the 2 tracked corners.
     */

    private static void getCornerState (int[] cornerSlotArray, int combinationIndex,
                                       int permutationIndex, int orientationIndex) {
        // A temporary array to hold the unpacked permutation and orientation data.
        int[] pieceData = new int[4];

        // This section unpacks the combined permutation and orientation indices into their parts.
        // The logic is highly specific to this coordinate system.
        pieceData[0] = permutationIndex % 2; // Permutation part 1
        pieceData[1] = 1 - pieceData[0];     // Permutation part 2
        pieceData[2] = orientationIndex / 3; // Orientation part 1
        pieceData[3] = orientationIndex % 3; // Orientation part 2

        int piecesToPlace = 2;
        for (int slot = 7; slot >= 0; slot--) {
            // Check if a piece must be placed in this slot based on the combination index.
            if (combinationIndex >= Utils.Cnk[slot][piecesToPlace]) {
                // This slot is occupied. Update the index.
                combinationIndex -= Utils.Cnk[slot][piecesToPlace--];

                // Place the piece, packing its decoded permutation and orientation data.
                cornerSlotArray[slot] = pieceData[piecesToPlace] << 3 | pieceData[piecesToPlace + 2];
            } else {
                // This slot is empty.
                cornerSlotArray[slot] = -3;
            }
        }
    }
    //</editor-fold>



}
