package edu.macalester.wpsemsim.matrix;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TShortArrayList;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.hash.TIntHashSet;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public class SparseMatrixTransposer {
    final static Logger LOG = Logger.getLogger(SparseMatrixTransposer.class.getName());

    private SparseMatrixWriter writer;
    private SparseMatrix matrix;
    private int colIds[];
    private TIntIntHashMap colCounts = new TIntIntHashMap();
    private int bufferMb;
    private int numColsTransposed = 0;


    public SparseMatrixTransposer(SparseMatrix m, File f, int bufferMb) throws IOException {
        this.matrix = m;
        this.writer = new SparseMatrixWriter(f, m.getValueConf());
        this.bufferMb = bufferMb;
        this.numColsTransposed = 0;
    }

    public void transpose() throws IOException {
        countCellsPerColumn();
        while (numColsTransposed < colIds.length) {
            Map<Integer, RowAccumulator> batch = accumulateBatch();
            writeBatch(batch.values());
        }
        this.writer.finish();
    }

    private void countCellsPerColumn() throws IOException {
        for (int id : matrix.getRowIds()) {
            MatrixRow row = matrix.getRow(id);
            for (int i = 0; i < row.getNumCols(); i++) {
                colCounts.adjustOrPutValue(row.getColIndex(i), 1, 1);
            }
        }

        colIds = colCounts.keys();
        LOG.info("found " + colIds.length + " unique column ids in matrix");
        Arrays.sort(colIds);
    }

    public Map<Integer, RowAccumulator> accumulateBatch() {
        Map<Integer, RowAccumulator> transposedBatch = new LinkedHashMap<Integer, RowAccumulator>();

        // figure out which columns we are tracking
        double mbs = 0;
        TIntHashSet colIdsInBatch = new TIntHashSet();
        for (int i = numColsTransposed; i  < colIds.length; i++) {
            int colId = colIds[i];
            int colSize = colCounts.get(colId);
            double rowMbs = getSizeInMbOfRowDataStructure(colSize);
            if (mbs + rowMbs > bufferMb) {
                break;
            }
            colIdsInBatch.add(colId);
            mbs += rowMbs;
        }
        numColsTransposed += colIdsInBatch.size();
        LOG.info("processing " + colIdsInBatch.size() + " columns in batch (total=" + numColsTransposed + " of " + colCounts.size() + ")");

        for (SparseMatrixRow row : matrix) {
            int rowId = row.getRowIndex();
            for (int i = 0; i < row.getNumCols(); i++) {
                int colId = row.getColIndex(i);
                if (!colIdsInBatch.contains(colId)) {
                    continue;
                }
                short colValue = row.getPackedColValue(i);
                if (!transposedBatch.containsKey(colId)) {
                    transposedBatch.put(colId, new RowAccumulator(colId));
                }
                transposedBatch.get(colId).addCol(rowId, colValue);
            }
        }

        for (int id : transposedBatch.keySet()) {
            if (colCounts.get(id) != transposedBatch.get(id).size()) {
                throw new IllegalArgumentException("row size unexpected!");
            }
        }

        return transposedBatch;
    }

    public void writeBatch(Collection<RowAccumulator> batch) throws IOException {
        for (RowAccumulator ra: batch) {
            writer.writeRow(ra.toRow(matrix.getValueConf()));
        }
    }

    private static final int BYTES_PER_REF =
            Integer.valueOf(System.getProperty("sun.arch.data.model")) / 8;
    private static final int BYTES_PER_OBJECT = 40;     // an estimate at overhead

    private double getSizeInMbOfRowDataStructure(int numEntries) {
        return (
            // row accumulator object itself
            BYTES_PER_OBJECT + 4 + 2 * BYTES_PER_REF +
            // ids and values in accumulator
            numEntries * (4 + 2)
        ) / (1024.0 * 1024.0);
    }

    private static class RowAccumulator {
        int id;
        TIntArrayList colIds = new TIntArrayList();
        TShortArrayList colVals = new TShortArrayList();
        RowAccumulator(int id) {
            this.id = id;
        }
        SparseMatrixRow toRow(ValueConf vconf) {
            return new SparseMatrixRow(vconf, id, colIds.toArray(), colVals.toArray());
        }
        void addCol(int id, short val) {
            this.colIds.add(id);
            this.colVals.add(val);
        }
        int size() { return this.colIds.size(); }
    }

    public static int PAGE_SIZE = 1024*1024*500;    // 500MB
    public static void main(String args[]) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: java " + SparseMatrixTransposer.class.getName() + " input_path output_path buffer_in_MBs");
            System.exit(1);
        }
        SparseMatrix matrix = new SparseMatrix(new File(args[0]), false, PAGE_SIZE);
        SparseMatrixTransposer transposer = new SparseMatrixTransposer(
                        matrix,
                        new File(args[1]),
                        Integer.valueOf(args[2]));
        transposer.transpose();
    }
}
