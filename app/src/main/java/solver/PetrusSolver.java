package solver;

import android.util.Log;

import static solver.Utils.turnSuffix;

/**
 * A solver for the Petrus method on a 3x3x3 Rubik's Cube.
 * <p>
 * This class contains the logic for solving a cube using the Petrus method, which is
 * broken down into distinct stages like building a 2x2x2 block, expanding to a
 * 2x2x3 block, and solving the last layer. The class uses separate sets of
 * lookup tables and solvers for these different stages.
 */
public class PetrusSolver {
    //<editor-fold desc="Constants & Class Variables">

    // --- General Constants ---
    private static final int NUM_FACES = 6;
    private static final int NUM_TURN_TYPES = 3;
    private static final int UNVISITED_STATE = -1;
    private static final int SOLVED_STATE_DISTANCE = 0;

    // --- General Solver Variables ---
    private static int[] solutionSequence = new int[10];
    private static boolean isBaseInitialized = false;
    private static boolean isStage1Initialized = false;
    private static boolean isStage2Initialized = false;

    //<editor-fold desc="Stage 1: 2x2x2 Block Tables">
    // Move tables for edge permutation and orientation during Stage 1.
    private static final int NUM_S1_EDGE_COMB = 220; // C(12,3) = 220
    private static final int NUM_S1_EDGE_PERM = 6; // 3! = 6
    private static final int NUM_S1_EDGE_ORIENT = 8; // 2^3 = 8
    private static final int NUM_S1_EDGE_PERM_STATES = NUM_S1_EDGE_COMB * NUM_S1_EDGE_PERM; // 1320
    private static final int NUM_S1_EDGE_ORIENT_STATES = NUM_S1_EDGE_COMB * NUM_S1_EDGE_ORIENT; // 1760
    private static final int NUM_S1_CORNER_STATES = 24;
    private static final int MAX_S1_SEARCH_DEPTH = 9;
    private static final int S1_PRUNING_DEPTH_PERM = 5;
    private static final int S1_PRUNING_DEPTH_ORIENT = 5;
    static short[][] moveTable_S1_EdgePerm = new short[NUM_S1_EDGE_PERM_STATES][NUM_FACES];
    static short[][] moveTable_S1_EdgeOrient = new short[NUM_S1_EDGE_ORIENT_STATES][NUM_FACES];
    private static byte[][] moveTable_S1_Corner = new byte[NUM_S1_CORNER_STATES][NUM_FACES];

    // Pruning tables for Stage 1 edges.
    private static byte[] pruningTable_S1_EdgePerm = new byte[NUM_S1_EDGE_PERM_STATES];
    private static byte[] pruningTable_S1_EdgeOrient = new byte[NUM_S1_EDGE_ORIENT_STATES];

    //</editor-fold>

    //<editor-fold desc="Stage 2: 2x2x3 Block & Last Layer Tables">
    // Move tables for the reduced edge set in Stage 2.
    private static final int NUM_S2_EDGE_COMB = 66; // C(12,2) = 66
    private static final int NUM_S2_EDGE_PERM = 2; // 2! = 6
    private static final int NUM_S2_EDGE_ORIENT = 4; // 2^2 = 8
    private static final int NUM_S2_EDGE_PERM_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_PERM; // 132
    private static final int NUM_S2_EDGE_ORIENT_STATES = NUM_S2_EDGE_COMB * NUM_S2_EDGE_ORIENT; // 264
    private static final int MAX_S2_SEARCH_DEPTH = 10;
    private static final int S2_NUM_MOVES = 3;

    private static short[][] moveTable_S2_EdgePerm = new short[NUM_S2_EDGE_PERM_STATES][NUM_FACES];
    private static short[][] moveTable_S2_EdgeOrient = new short[NUM_S2_EDGE_ORIENT_STATES][NUM_FACES];
    // Pruning table for Stage 2 edges.
    private static byte[] pruningTable_S2_Edge = new byte[NUM_S2_EDGE_COMB * NUM_S2_EDGE_PERM * NUM_S2_EDGE_ORIENT]; //528

    // The move set for Stage 2 is restricted (e.g., U, R, F).
    private static int[] STAGE_2_MOVES = {0, 3, 4};

    // Solved state coordinates for the 3 sub-cases of Stage 2.
    private static int[] SOLVED_S2_EP = {88, 42, 34};
    private static int[] SOLVED_S2_EO = {176, 84, 68};
    private static int[] SOLVED_S2_CO = {0, 15, 21};
    //</editor-fold>

    //<editor-fold desc="Formatting Data">

    private static String[] moveIdx = {"DULRBF", "FBLRDU", "DUFBLR", "DURLFB",
            "UDFBRL", "UDLRFB", "UDRLBF", "UDBFLR"};
    private static String[] blockLabels = {"ULF:", "ULB:", "URF:", "URB:", "DLF:", "DLB:", "DRF:", "DRB:"};
    //</editor-fold>
    //</editor-fold>

    //<editor-fold desc="Initialization">
    /**
     * Initializes the base move tables for a 3-edge coordinate system.
     * <p>
     * This method pre-computes the move tables for the permutation and orientation
     * of 3 edges chosen from 12. These tables are a fundamental building block
     * reused by other solver stages.
     */
    public static void initializeBaseTables() {
        // A guard to ensure this heavy computation is only run once.
        if (isBaseInitialized)  {
            return;
        }
        final int NUM_EDGES_TO_TRACK = 3;

        // --- Generate Move Tables ---
        // Iterate through every possible combination of 3 edge positions.
        int combIndex, permOrientIndex;
        for (combIndex = 0; combIndex < NUM_S1_EDGE_COMB; combIndex++)
            // Iterate through every possible orientation of those 3 edges.
            for (permOrientIndex = 0; permOrientIndex < NUM_S1_EDGE_ORIENT; permOrientIndex++)
                // For each state, calculate the result of each of the 6 face moves.
                for (int moveIndex = 0; moveIndex < NUM_FACES; moveIndex++) {
                    // Calculate the new packed coordinate after the move.
                    int newPackedCoord = calculateNewEdgeCoordinate(combIndex, permOrientIndex, 3, moveIndex);
                    if (permOrientIndex < 6) moveTable_S1_EdgePerm[combIndex * 6 + permOrientIndex][moveIndex] = (short) (newPackedCoord >> 3);
                    moveTable_S1_EdgeOrient[combIndex * 8 + permOrientIndex][moveIndex] = (short) ((newPackedCoord / 48) << 3 | newPackedCoord & 7);
                }
        isBaseInitialized = true;
    }

    /** Initializes all pre-computed lookup tables for Stage 1 (2x2x2 Block). */
    private static void initializeStage1Tables() {
        if (isStage1Initialized) return;
        initializeBaseTables();
        int i, j;
        byte[][] p = {
                { 1, 0, 3, 0, 0, 4 }, { 2, 1, 1, 5, 1, 0 }, { 3, 2, 2, 1, 6, 2 }, { 0, 3, 7, 3, 2, 3 },
                { 4, 7, 0, 4, 4, 5 }, { 5, 4, 5, 6, 5, 1 }, { 6, 5, 6, 2, 7, 6 }, { 7, 6, 4, 7, 3, 7 }
        };
        byte[][] o = {
                { 0, 0, 1, 0, 0, 2 }, { 0, 0, 0, 2, 0, 1 }, { 0, 0, 0, 1, 2, 0 }, { 0, 0, 2, 0, 1, 0 },
                { 0, 0, 2, 0, 0, 1 }, { 0, 0, 0, 1, 0, 2 }, { 0, 0, 0, 2, 1, 0 }, { 0, 0, 1, 0, 2, 0 }
        };
        for (i = 0; i < 8; i++)
            for (j = 0; j < 3; j++)
                for (int k = 0; k < 6; k++)
                    moveTable_S1_Corner[i * 3 + j][k] = (byte) (p[i][k] * 3 + (o[i][k] + j) % 3);
        for (i = 0; i < 1320; i++) pruningTable_S1_EdgePerm[i] = -1;
        pruningTable_S1_EdgePerm[17 * 6] = 0;
        Utils.populatePruningTable(pruningTable_S1_EdgePerm, 5, moveTable_S1_EdgePerm, 3);
        for (i = 0; i < 1760; i++) pruningTable_S1_EdgeOrient[i] = -1;
        pruningTable_S1_EdgeOrient[17 * 8] = 0;
        Utils.populatePruningTable(pruningTable_S1_EdgeOrient, 5, moveTable_S1_EdgeOrient, 3);
        isStage1Initialized = true;
    }

    /** Initializes all pre-computed lookup tables for Stage 2. */
    private static void initializeStage2Tables() {
        if (isStage2Initialized) return;
        int i, j;
        for (i = 0; i < 66; i++)
            for (j = 0; j < 4; j++)
                for (int k = 0; k < 6; k++) {
                    int d = calculateNewEdgeCoordinate(i, j, 2, k);
                    if (j < 2) moveTable_S2_EdgePerm[i * 2 + j][k] = (short) (d >> 3);
                    moveTable_S2_EdgeOrient[i * 4 + j][k] = (short) ((d / 16) << 2 | d & 3);
                }
        for (i = 0; i < 528; i++) pruningTable_S2_Edge[i] = -1;
        pruningTable_S2_Edge[44 * 8] = pruningTable_S2_Edge[21 * 8] = pruningTable_S2_Edge[17 * 8] = 0;
        int c = 3;
        for (int d = 0; d < 6; d++) {
            //c = 0;
            for (i = 0; i < 132; i++)
                for (j = 0; j < 4; j++)
                    if (pruningTable_S2_Edge[i * 4 + j] == d)
                        for (int l = 0; l < 3; l++) {
                            int x = i, y = j;
                            for (int m = 0; m < 3; m++) {
                                y = moveTable_S2_EdgeOrient[(x / 2) << 2 | y & 3][STAGE_2_MOVES[l]] & 3;
                                x = moveTable_S2_EdgePerm[x][STAGE_2_MOVES[l]];
                                if (pruningTable_S2_Edge[x * 4 + y] < 0) {
                                    pruningTable_S2_Edge[x * 4 + y] = (byte) (d + 1);
                                    c++;
                                }
                            }
                        }
            Log.w("dct", d+1+"\t"+c);
        }
        isStage2Initialized = true;
    }
    //</editor-fold>

    //<editor-fold desc="Public Solver Methods">
    /**
     * Public wrapper to solve the Petrus method for multiple starting blocks,
     * specified by a bitmask.
     */
    public static String solvePetrus(String scramble, int block) {
        initializeStage1Tables();
        boolean solveS2 = ((block >> 8) & 1) != 0;
        if (solveS2) initializeStage2Tables();
        StringBuilder s = new StringBuilder("\n");
        for (int i = 0; i < 8; i++) {
            if (((block >> i) & 1) != 0) s.append(petrusStage1(scramble, i, solveS2));
        }
        return s.toString();
    }
    //</editor-fold>

    //<editor-fold desc="Stage 1 Solver (2x2x2 Block)">
    /**
     * The main solver for a single orientation of Stage 1.
     */
    private static String petrusStage1(String scramble, int block, boolean solveS2) {
        String[] s = scramble.split(" ");
        int co = 12, ep = 102, eo = 136;
        for (int d = 0; d < s.length; d++)
            if (0 != s[d].length()) {
                int o = moveIdx[block].indexOf(s[d].charAt(0));
                co = moveTable_S1_Corner[co][o]; ep = moveTable_S1_EdgePerm[ep][o]; eo = moveTable_S1_EdgeOrient[eo][o];
                if (s[d].length() > 1) {
                    co = moveTable_S1_Corner[co][o]; eo = moveTable_S1_EdgeOrient[eo][o]; ep = moveTable_S1_EdgePerm[ep][o];
                    if (s[d].charAt(1) == '\'') {
                        co = moveTable_S1_Corner[co][o]; eo = moveTable_S1_EdgeOrient[eo][o]; ep = moveTable_S1_EdgePerm[ep][o];
                    }
                }
            }
        for (int d = 0; d < 9; d++) {
            //Log.w("dct", "d "+d);
            if (idaPetrusStage1(co, ep, eo, d, -1, block)) {
                StringBuilder sb = new StringBuilder("\n");
                sb.append(blockLabels[block]);
                for (int i = d; i > 0; i--)
                    sb.append(' ').append(moveIdx[block].charAt(solutionSequence[i] / 3)).append(turnSuffix[solutionSequence[i] % 3]);
                if (solveS2) sb.append(petrusSTage2(s, block, d));
                return sb.toString();
            }
        }
        return "\nerror";
    }

    /**
     * The recursive IDA* search function for Stage 1.
     */
    private static boolean idaPetrusStage1(int co, int ep, int eo, int depth, int lm, int block) {
        if (depth == 0) return co == 12 && ep == 102 && eo == 136;
        if (pruningTable_S1_EdgePerm[ep] > depth || pruningTable_S1_EdgeOrient[eo] > depth) return false;
        for (int i = 0; i < 6; i++)
            if (i != lm) {
                int w = co, y = ep, s = eo;
                for (int j = 0; j < 3; j++) {
                    w = moveTable_S1_Corner[w][i];
                    y = moveTable_S1_EdgePerm[y][i];
                    s = moveTable_S1_EdgeOrient[s][i];
                    if (idaPetrusStage1(w, y, s, depth - 1, i, block)) {
                        solutionSequence[depth] = i * 3 + j;
                        return true;
                    }
                }
            }
        return false;
    }
    //</editor-fold>

    //<editor-fold desc="Stage 2 Solver (Expand to 2x2x3, etc.)">
    /**
     * The main solver for Stage 2. Takes the solution from Stage 1 as input.
     */

    static String petrusSTage2(String[] s, int block, int d) {
        int[] co2 = new int[3], ep2 = new int[3], eo2 = new int[3];
        for (int i = 0; i < 3; i++) {
            co2[i] = SOLVED_S2_CO[i];
            ep2[i] = SOLVED_S2_EP[i];
            eo2[i] = SOLVED_S2_EO[i];
        }
        for (int i = 0; i < s.length; i++) {
            if (s[i].length() > 0) {
                int o = moveIdx[block].indexOf(s[i].charAt(0));
                for (int j = 0; j < 3; j++) {
                    co2[j] = moveTable_S1_Corner[co2[j]][o];
                    ep2[j] = moveTable_S2_EdgePerm[ep2[j]][o];
                    eo2[j] = moveTable_S2_EdgeOrient[eo2[j]][o];
                    if (s[i].length() > 1) {
                        co2[j] = moveTable_S1_Corner[co2[j]][o];
                        ep2[j] = moveTable_S2_EdgePerm[ep2[j]][o];
                        eo2[j] = moveTable_S2_EdgeOrient[eo2[j]][o];
                        if (s[i].charAt(1) == '\'') {
                            co2[j] = moveTable_S1_Corner[co2[j]][o];
                            ep2[j] = moveTable_S2_EdgePerm[ep2[j]][o];
                            eo2[j] = moveTable_S2_EdgeOrient[eo2[j]][o];
                        }
                    }
                }
            }
        }
        for (int i = d; i > 0; i--) {
            int m = solutionSequence[i] / 3, n = solutionSequence[i] % 3;
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k <= n; k++) {
                    co2[j] = moveTable_S1_Corner[co2[j]][m];
                    ep2[j] = moveTable_S2_EdgePerm[ep2[j]][m];
                    eo2[j] = moveTable_S2_EdgeOrient[eo2[j]][m];
                }
            }
        }
        for (int l = 0; l < 10; l++) {
            for (int idx = 0; idx < 3; idx++)
                if (idaPetrusStage2(co2[idx], ep2[idx], eo2[idx], l, -1, idx)) {
                    StringBuilder sb = new StringBuilder(" /");
                    for (int i = l; i > 0; i--)
                        sb.append(' ').append(moveIdx[block].charAt(solutionSequence[i] / 3)).append(turnSuffix[solutionSequence[i] % 3]);
                    return sb.toString();
                }
        }
        return " / error";
    }

    /**
     * The recursive IDA* search function for Stage 2.
     */

    private static boolean idaPetrusStage2(int co, int ep, int eo, int depth, int lm, int idx) {
        if (depth == 0) return ep == SOLVED_S2_EP[idx] && eo == SOLVED_S2_EO[idx] && co == SOLVED_S2_CO[idx];
        if (pruningTable_S2_Edge[ep << 2 | eo & 3] > depth) return false;
        for (int i = 0; i < 3; i++)
            if (i != lm) {
                int x = co, y = ep, s = eo;
                for (int j = 0; j < 3; j++) {
                    x = moveTable_S1_Corner[x][STAGE_2_MOVES[i]];
                    y = moveTable_S2_EdgePerm[y][STAGE_2_MOVES[i]];
                    s = moveTable_S2_EdgeOrient[s][STAGE_2_MOVES[i]];
                    if (idaPetrusStage2(x, y, s, depth - 1, i, idx)) {
                        solutionSequence[depth] = STAGE_2_MOVES[i] * 3 + j;
                        return true;
                    }
                }
            }
        return false;
    }

    //</editor-fold>

    //<editor-fold desc="Internal Table Generation Helper">
    /**
     * Calculates the new coordinate for a k-edge state after a given move.
     * This is the core "Unpack -> Apply Move -> Repack" engine for move table generation.
     */
    private static int calculateNewEdgeCoordinate(int c, int po, int k, int f) {
        int[] n = new int[12], s = new int[3];
        Utils.idxToPerm(s, po, k, false);
        int t, q = k;
        for (t = 0; t < 12; t++)
            if (c >= Utils.Cnk[11 - t][q]) {
                c -= Utils.Cnk[11 - t][q--];
                n[t] = s[q] << 1 | po & 1;
                po >>= 1;
            } else n[t] = -1;
        Cross.applyMoveToEdgeArray(n, f);
        c = po = 0; q = k;
        for (t = 0; t < 12; t++)
            if (n[t] >= 0) {
                c += Utils.Cnk[11 - t][q--];
                s[q] = n[t] >> 1;
                po |= (n[t] & 1) << (k - 1 - q);
            }
        int p = Utils.permToIdx(s, k, false);
        return Utils.factorial[k] * c + p << 3 | po;
    }
    //</editor-fold>








}
