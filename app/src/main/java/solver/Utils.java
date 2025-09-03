package solver;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Utils {
    public static int[][] Cnk = new int[25][25];
    public static String[] turn = {"U", "D", "L", "R", "F", "B"};
    public static String[] suff = {"", "2", "'"};
    public static String[] suffInv = {"'", "2", ""};
    static {
        for (int i = 0; i < 25; i++) {
            Cnk[i][0] = 1;
            for (int j = Cnk[i][i] = 1; j < i; j++)
                Cnk[i][j] = Cnk[i - 1][j - 1] + Cnk[i - 1][j];
        }
    }

    public static int getPruning(int[] table, int index) {
        return table[index >> 3] >> ((index & 7) << 2) & 15;
    }

    /**
     * Sets a pruning table distance value for a given state index.
     * <p>
     * This function uses bitwise operations to store a 4-bit distance value (0-15)
     * into a packed integer array. Each integer in the table holds 8 such values,
     * significantly reducing memory usage.
     *
     * @param pruningTable The packed array storing the distance data.
     * @param stateIndex The unique index of the puzzle state (e.g., a specific cross pattern).
     * @param distance The distance (number of moves from solved) to store for this state.
     */
    public static void setPruning(int[] pruningTable, int stateIndex, int distance) {
        // --- 1. Find the correct integer in the array ---
        // Each integer stores 8 states, so we divide the index by 8.
        // (stateIndex >> 3) is a fast way to do integer division by 8.
        int arrayIndex = stateIndex >> 3;

        // --- 2. Find the correct 4-bit slot within that integer ---
        // (stateIndex & 7) gets the remainder when dividing by 8 (a value from 0 to 7),
        // which tells us which of the 8 slots to use.
        // We multiply by 4 because each slot is 4 bits wide.
        int bitShift = (stateIndex & 7) << 2;

        // --- 3. Set the 4-bit value ---
        // The XOR (^) operation is used to set the value. First, we clear the 4-bit slot
        // by XORing it with 15 (binary 1111), and then we set the new value by XORing
        // it with the desired distance. The expression (15 ^ distance) combines
        // this into a single operation.
        pruningTable[arrayIndex] ^= (15 ^ distance) << bitShift ;
    }

    public static int getBit(int[] arr, int idx) {
        return arr[idx >> 5] & (1 << (idx & 0x1f));
    }

    public static void setBit(int[] arr, int idx) {
        arr[idx >> 5] |= 1 << (idx & 0x1f);
    }

    public static int[] fact = {1, 1, 2, 6, 24, 120, 720, 5040, 40320, 362880, 3628800, 39916800, 479001600};
    public static void set8Perm(int[] arr, int len, int idx) {
        int val = 0x76543210;
        for (int i = 0; i < len - 1; i++) {
            int p = fact[len - 1 - i];
            int v = idx / p;
            idx -= v * p;
            v <<= 2;
            arr[i] = (val >> v) & 0x7;
            int m = (1 << v) - 1;
            val = (val & m) + ((val >> 4) & ~m);
        }
        arr[len - 1] = val & 0x7;
    }

    public static int get8Perm(int[] arr, int len) {
        int idx = 0;
        int val = 0x76543210;
        for (int i = 0; i < len - 1; i++) {
            int v = arr[i] << 2;
            idx = (len - i) * idx + ((val >> v) & 0x7);
            val -= 0x11111110 << v;
        }
        return idx;
    }

    static void set11Perm(int[] arr, int idx, int len) {
        long val = 0xa9876543210L;
        for (int i = 0; i < len - 1; i++) {
            int p = fact[len - 1 - i];
            int v = idx / p;
            idx -= v * p;
            v <<= 2;
            arr[i] = (int) ((val >> v) & 0xf);
            long m = (1L << v) - 1;
            val = (val & m) + ((val >> 4) & ~m);
        }
        arr[len - 1] = (int) (val & 0xf);
    }

    static int get11Perm(int[] arr, int len) {
        int idx = 0;
        long val = 0xa9876543210L;
        for (int i = 0; i < len - 1; i++) {
            int v = arr[i] << 2;
            idx = (int) ((len - i) * idx + ((val >> v) & 0xf));
            val -= 0x11111111110L << v;
        }
        return idx;
    }

    public static void circle(int[] arr, int a, int b, int c, int d) {
        int temp = arr[a]; arr[a] = arr[b]; arr[b] = arr[c]; arr[c] = arr[d]; arr[d] = temp;
    }

    public static void circle(int[] arr, int a, int b, int c, int d, int[] ori) {
        int temp = arr[a];
        arr[a] = arr[b] + ori[0];
        arr[b] = arr[c] + ori[1];
        arr[c] = arr[d] + ori[2];
        arr[d] = temp + ori[3];
    }

    public static void swap(int[] arr, int a, int b, int c, int d) {
        int temp = arr[a]; arr[a] = arr[b]; arr[b] = temp;
        temp = arr[c]; arr[c] = arr[d]; arr[d] = temp;
    }

    public static void swap(int[] arr, int a, int b) {
        int temp = arr[a]; arr[a] = arr[b]; arr[b] = temp;
    }

    public static void circle(int[] arr, int a, int b, int c) {
        int temp = arr[a]; arr[a] = arr[b]; arr[b] = arr[c]; arr[c] = temp;
    }

    // permutation
    public static int permToIdx(int[] permutation, int length, boolean even) {
        int index = 0;
        int end = even ? length - 2 : length - 1;
        for (int i = 0; i < end; i++) {
            index *= length - i;
            for (int j = i + 1; j < length; j++)
                if (permutation[i] > permutation[j]) index++;
        }
        return index;
    }

    /**
     * Decodes a unique integer index into a specific permutation.
     * This method converts an index from a factorial number system (factoradic)
     * into a standard permutation array {p_0, p_1, ..., p_{n-1}}.
     *
     * @param permutation The output array to be filled with the generated permutation.
     * @param index       The unique integer representing the permutation (from 0 to n!-1).
     * @param length      The number of elements in the permutation (n).
     * @param even        If true, ensures the generated permutation has an even parity.
     * This is crucial for puzzles like the Rubik's Cube where only
     * even permutations are reachable from a solved state.
     */
    public static void idxToPerm(int[] permutation, int index, int length, boolean even) {
        // This variable will be used to track the parity of the permutation.
        // The parity of a permutation is the same as the parity of the sum of its
        // factoradic digits (Lehmer code).
        int sum = 0;
        // --- INITIALIZATION ---
        // Handle the placement of the last one or two elements based on the parity requirement.
        if (even) {
            // If an even permutation is required, we fix the last two elements temporarily.
            // We will generate a permutation for the first (n-2) elements and then
            // adjust these last two if needed to fix the overall parity.
            permutation[length - 1] = 1;
            permutation[length - 2] = 0;
        } else {
            // For any permutation (even or odd), we can start by placing 0 at the end.
            // This value will be adjusted by the inner loop later.
            permutation[length - 1] = 0;
        }
        // Determine the starting point of the loop. If parity is fixed, we only need
        // to compute the permutation for the first (n-2) elements. Otherwise, (n-1).
        int start = even ? length - 3 : length - 2;

        // --- MAIN GENERATION LOOP ---
        // Iterate backwards from the second-to-last (or third-to-last) element down to the first.
        // This process is equivalent to converting a number to its factoradic representation.
        for (int i = start; i >= 0; i--) {
            // Calculate the next "digit" in the factoradic number system.
            // For each position 'i', there are (length - i) choices for the element.
            permutation[i] = index % (length - i);

            // Track the sum of these digits to determine the permutation's parity.
            sum += permutation[i];

            // Update the index for the next iteration (equivalent to an integer division
            // to get the next digit in a different number base).
            index /= length - i;

            // --- COLLISION AVOIDANCE STEP ---
            // The value permutation[i] is just a temporary digit (part of the Lehmer code).
            // This inner loop converts it into the final, unique permutation value by
            // ensuring no numbers are repeated. It "makes room" for the new value by
            // incrementing any values to its right that are greater than or equal to it.

            for (int j = i + 1; j < length; j++)
                if (permutation[j] >= permutation[i]) permutation[j]++;
        }
        // --- PARITY CORRECTION STEP ---
        // This block only executes if an even permutation was requested.
        if (even && sum % 2 != 0) {
            // If the sum of the factoradic digits is odd, the generated permutation is also odd.
            // To make it even, we must perform one swap. A single swap of any two elements
            // always flips the parity of a permutation. Here, we conveniently swap the
            // last two elements we set aside at the beginning.
            swap(permutation, length - 1, length - 2);
        }
    }

    // flip
    public static int flipToIdx(int[] flip, int length, boolean zeroSum) {
        int idx = 0;
        if (zeroSum) length--;
        for (int i = 0; i < length; i++) {
            idx = idx << 1 | flip[i];
        }
        return idx;
    }

    public static void idxToFlip(int[] flip, int index, int length, boolean zeroSum) {
        int sum = 0;
        if (zeroSum) length--;
        for (int i = length - 1; i >= 0; i--) {
            sum ^= flip[i] = index & 1;
            index >>= 1;
        }
        if (zeroSum) flip[length] = sum;
    }

    // orientation
    public static int oriToIdx(int[] orientation, int length, boolean zeroSum) {
        int index = 0;
        if (zeroSum) length--;
        for (int i = 0; i < length; i++)
            index = 3 * index + (orientation[i] % 3);
        return index;
    }

    public static void idxToOri(int[] orientation, int index, int length, boolean zeroSum) {
        int sum = 0;
        int start = zeroSum ? length - 2 : length - 1;
        for (int i = start; i >= 0; i--) {
            orientation[i] = index % 3;
            index /= 3;
            sum += orientation[i];
        }
        if (zeroSum)
            orientation[length - 1] = 3 - sum % 3;
    }

    // combinations
    public static int combToIdx(int[] comb, int k, int n) {
        int idx = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (comb[i] != 0) {
                idx += Cnk[i][k--];
            }
        }
        return idx;
    }

    public static void idxToComb(int[] comb, int index, int k, int n) {
        for (int i = n - 1; i >= 0; i--) {
            if (index >= Cnk[i][k]) {
                index -= Cnk[i][k--];
                comb[i] = 1;
            } else comb[i] = 0;
        }
    }

    public static boolean permutationSign(int[] permutation) {
        int nInversions = 0;
        for (int i = 0; i < permutation.length; i++) {
            for (int j = i + 1; j < permutation.length; j++) {
                if (permutation[i] > permutation[j]) {
                    nInversions++;
                }
            }
        }
        return nInversions % 2 == 0;
    }

    //pruning table
    public static void createPrun(byte[] prunTable, int depth, short[][] moveTable, int times) {
        int total = prunTable.length;
        int moves = moveTable[0].length;
        int c = 1;
        for (int d = 0; d < depth; d++) {
            for (int i = 0; i < total; i++)
                if (prunTable[i] == d)
                    for (int j = 0; j < moves; j++) {
                        int next = i;
                        for (int k = 0; k < times; k++) {
                            next = moveTable[next][j];
                            if (prunTable[next] < 0) {
                                prunTable[next] = (byte) (d + 1);
                                c++;
                            }
                        }
                    }
            //Log.w("dct", d + 1 + "\t" + c);
        }
    }

    public static void createPrun(byte[] prunTable, int depth, short[][] moveTable1, short[][] moveTable2, int times) {
        //int total = prunTable.length;
        int moves1 = moveTable1.length;
        int moves2 = moveTable2.length;
        int moves = moveTable1[0].length;
        int c = 1;
        for (int d = 0; d < depth; d++) {
            for (int i = 0; i < moves1; i++)
                for (int j = 0; j < moves2; j++)
                    if (prunTable[i * moves2 + j] == d)
                        for (int k = 0; k < moves; k++) {
                            int x = i, y = j;
                            for (int l = 0; l < times; l++) {
                                x = moveTable1[x][k];
                                y = moveTable2[y][k];
                                if (prunTable[x * moves2 + y] < 0) {
                                    prunTable[x * moves2 + y] = (byte) (d + 1);
                                    c++;
                                }
                            }
                        }
            //Log.w("dct", d + 1 + "\t" + c);
        }
    }

    public static void createPrun(byte[] prunTable, int depth, char[][] moveTable, int times) {
        int total = prunTable.length;
        int moves = moveTable[0].length;
        int c = 1;
        for (int d = 0; d < depth; d++) {
            for (int i = 0; i < total; i++)
                if (prunTable[i] == d)
                    for (int j = 0; j < moves; j++) {
                        int next = i;
                        for (int k = 0; k < times; k++) {
                            next = moveTable[next][j];
                            if (prunTable[next] < 0) {
                                prunTable[next] = (byte) (d + 1);
                                c++;
                            }
                        }
                    }
            //Log.w("dct", d + 1 + "\t" + c);
        }
    }

    //facelet
    static void fillFacelet(byte[][] facelet, char[] f, int[] perm, int[] ori, char[] ts, int pcs) {
        for (int c = 0; c < facelet.length; c++) {
            int o = facelet[c].length;
            for (int n = 0; n < o; n++)
                f[facelet[c][(n + ori[c]) % o]] = ts[facelet[perm[c]][n] / pcs];
        }
    }

    //data storage
    public static void read(char[] arr, InputStream in) throws IOException {
        int len = arr.length;
        byte[] buf = new byte[len * 2];
        in.read(buf);
        for (int i = 0; i < len; i++) {
            arr[i] = (char) ((buf[i * 2] & 0xff) | ((buf[i * 2 + 1] << 8) & 0xff00));
        }
    }

    public static void read(int[] arr, InputStream in) throws IOException {
        int len = arr.length;
        byte[] buf = new byte[len * 4];
        in.read(buf);
        for (int i = 0; i < len; i++) {
            arr[i] = buf[i * 4] & 0xff | (buf[i * 4 + 1] << 8) & 0xff00 | (buf[i * 4 + 2] << 16) & 0xff0000 | (buf[i * 4 + 3] << 24) & 0xff000000;
        }
    }

    public static void read(char[][] arr, InputStream in) throws IOException {
        int len = arr[0].length;
        byte[] buf = new byte[len * 2];
        for (int i = 0, leng = arr.length; i < leng; i++) {
            in.read(buf);
            for (int j = 0; j < len; j++) {
                arr[i][j] = (char) (buf[j * 2] & 0xff | (buf[j * 2 + 1] << 8) & 0xff00);
            }
        }
    }

    public static void read(short[][] arr, InputStream in) throws IOException {
        int len = arr.length;
        byte[] buf = new byte[len * 2];
        for (int i = 0; i < 6; i++) {
            in.read(buf);
            for (int j = 0; j < len; j++) {
                arr[j][i] = (short) (buf[j * 2] & 0xff | (buf[j * 2 + 1] << 8) & 0xff00);
            }
        }
    }

    public static void read(int[] arr, int s, InputStream in) throws IOException {
        int len = arr.length / s;
        byte[] buf = new byte[s * 4];
        int start = 0;
        for (int i = 0; i < len; i++) {
            in.read(buf);
            for (int j = 0; j < s; j++) {
                arr[start++] = (buf[j * 4] << 24) & 0xff000000 | (buf[j * 4 + 1] << 16) & 0xff0000 | (buf[j * 4 + 2] << 8) & 0xff00 | (buf[j * 4 + 3]) & 0xff;
            }
        }
    }

    public static void read(int[][] data, int h, int w, InputStream is) throws Exception {
        byte[] buf = new byte[w * 4];
        for (int i = 0; i < h; i++) {
            is.read(buf);
            for (int j = 0; j < w; j++) {
                data[i][j] = (buf[j * 4]) & 0xff | (buf[j * 4 + 1] << 8) & 0xff00 | (buf[j * 4 + 2] << 16) & 0xff0000 | (buf[j * 4 + 3] << 24) & 0xff000000;
            }
        }
    }

    public static void write(char[] arr, OutputStream out) throws IOException {
        int len = arr.length;
        byte[] buf = new byte[len * 2];
        int idx = 0;
        for (int i = 0; i < len; i++) {
            buf[idx++] = (byte) (arr[i] & 0xff);
            buf[idx++] = (byte) ((arr[i] >>> 8) & 0xff);
        }
        out.write(buf);
    }

    public static void write(int[] arr, OutputStream out) throws IOException {
        int len = arr.length;
        byte[] buf = new byte[len * 4];
        int idx = 0;
        for (int i = 0; i < len; i++) {
            buf[idx++] = (byte) (arr[i] & 0xff);
            buf[idx++] = (byte) ((arr[i] >>> 8) & 0xff);
            buf[idx++] = (byte) ((arr[i] >>> 16) & 0xff);
            buf[idx++] = (byte) ((arr[i] >>> 24) & 0xff);
        }
        out.write(buf);
    }

    public static void write(char[][] arr, OutputStream out) throws IOException {
        int len = arr[0].length;
        byte[] buf = new byte[len * 2];
        for (int i = 0, leng = arr.length; i < leng; i++) {
            int idx = 0;
            for (int j = 0; j < len; j++) {
                buf[idx++] = (byte) (arr[i][j] & 0xff);
                buf[idx++] = (byte) ((arr[i][j]>>>8) & 0xff);
            }
            out.write(buf);
        }
    }

    public static void write(short[][] arr, OutputStream out) throws IOException {
        int len = arr.length;
        byte[] buf = new byte[len * 2];
        for (int i = 0; i < 6; i++) {
            int idx = 0;
            for (int j = 0; j < len; j++) {
                buf[idx++] = (byte) (arr[j][i] & 0xff);
                buf[idx++] = (byte) ((arr[j][i] >>> 8) & 0xff);
            }
            out.write(buf);
        }
    }

    public static void write(int[] arr, int s, OutputStream out) throws Exception {
        int len = arr.length / s;
        byte[] buf = new byte[s * 4];
        int start = 0;
        for (int i = 0; i < len; i++) {
            int idx = 0;
            for (int j = 0; j < s; j++) {
                buf[idx++] = (byte) ((arr[start] >>> 24) & 0xff);
                buf[idx++] = (byte) ((arr[start] >>> 16) & 0xff);
                buf[idx++] = (byte) ((arr[start] >>> 8) & 0xff);
                buf[idx++] = (byte) (arr[start] & 0xff);
                start++;
            }
            out.write(buf);
        }
    }

    public static void write(int[][] data, int h, int w, OutputStream os) throws Exception {
        byte[] buf = new byte[w * 4];
        for (int i = 0; i < h; i++) {
            int idx = 0;
            for (int j = 0; j < w; j++) {
                buf[idx++] = (byte) (data[i][j] & 0xff);
                buf[idx++] = (byte) ((data[i][j] >>> 8) & 0xff);
                buf[idx++] = (byte) ((data[i][j] >>> 16) & 0xff);
                buf[idx++] = (byte) ((data[i][j] >>> 24) & 0xff);
            }
            os.write(buf);
        }
    }
}
