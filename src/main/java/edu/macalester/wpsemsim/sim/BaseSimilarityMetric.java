package edu.macalester.wpsemsim.sim;

import edu.macalester.wpsemsim.concepts.ConceptMapper;
import edu.macalester.wpsemsim.concepts.Disambiguator;
import edu.macalester.wpsemsim.lucene.IndexHelper;
import edu.macalester.wpsemsim.matrix.SparseMatrix;
import edu.macalester.wpsemsim.matrix.SparseMatrixRow;
import edu.macalester.wpsemsim.normalize.IdentityNormalizer;
import edu.macalester.wpsemsim.normalize.Normalizer;
import edu.macalester.wpsemsim.utils.DocScoreList;
import edu.macalester.wpsemsim.utils.Function;
import edu.macalester.wpsemsim.utils.KnownSim;
import edu.macalester.wpsemsim.utils.ParallelForEach;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.set.TIntSet;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.queryparser.surround.parser.ParseException;

import java.io.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * Provides basic functionality that similarity metric implementations should extend.
 *
 * TODO: separate similarity() and mostSimilar() normalizers.
 */
public abstract class BaseSimilarityMetric implements SimilarityMetric {
    private static Logger LOG = Logger.getLogger(BaseSimilarityMetric.class.getName());

    private ConceptMapper mapper;
    private IndexHelper helper;
    private String name = this.getClass().getSimpleName();
    private Disambiguator disambiguator;
    protected int numThreads = Runtime.getRuntime().availableProcessors();
    private boolean trained = false;

    private File path;

    private Normalizer normalizer = new IdentityNormalizer();

    // turned off while training the normalizer
    private boolean useNormalizer = true;
    protected SparseMatrix mostSimilarMatrix;

    public BaseSimilarityMetric(ConceptMapper mapper, IndexHelper helper) {
        this.mapper = mapper;
        this.helper = helper;

        if (mapper == null) {
            LOG.warning("ConceptMapper is null. Will not be able to resolve phrases to concepts.");
        }
        if (helper == null) {
            LOG.warning("IndexHelper is null. Will not be able to resolve phrases to concepts.");
        }
        this.disambiguator = new Disambiguator(mapper, this, helper, 5);
    }

    public void setMostSimilarMatrix(SparseMatrix matrix) {
        this.mostSimilarMatrix = matrix;
    }

    public boolean hasCachedMostSimilar(int wpId) throws IOException {
        return mostSimilarMatrix != null && mostSimilarMatrix.getRow(wpId) != null;
    }

    public DocScoreList getCachedMostSimilar(int wpId) throws IOException {
        if (mostSimilarMatrix == null) {
            return null;
        }
        SparseMatrixRow row = mostSimilarMatrix.getRow(wpId);
        if (row == null) {
            return null;
        }
        DocScoreList dsl = new DocScoreList(row.getNumCols());
        for (int i = 0; i < row.getNumCols(); i++) {
            dsl.set(i, row.getColIndex(i), row.getColValue(i));
        }
        return dsl;
    }

    /**
     * Normalizers translate similarity scores to more meaningful values.
     * @param n
     */
    public void setNormalizer(Normalizer n){
        normalizer = n;
    }

    @Override
    public void trainSimilarity(List<KnownSim> labeled){
        trainSimilarityNormalizer(labeled);
    }

    /**
     * Trains the normalizer to support the similarity() method.
     * @param labeled
     */
    protected void trainSimilarityNormalizer(List<KnownSim> labeled) {
        useNormalizer = false;
        ParallelForEach.loop(labeled, numThreads, new Function<KnownSim>() {
            public void call(KnownSim ks) throws IOException, ParseException {
                double sim = similarity(ks.phrase1,ks.phrase2);
                if (!Double.isNaN(sim) && !Double.isInfinite(sim)){
                    synchronized (normalizer) {
                        normalizer.observe(sim, ks.similarity);
                    }
                }
            }
        });
        normalizer.observationsFinished();
        useNormalizer = true;
        trained = true;
    }

    @Override
    public void trainMostSimilar(List<KnownSim> labeled, int numResults, TIntSet validIds){
        trainMostSimilarNormalizer(labeled, numResults, validIds);
    }

    /**
     * Trains the normalizer to support the mostSimilar() method.
     * Also estimates the similarity score for articles that don't appear in top lists.
     * Note that this (probably) is an overestimate, and depends on how well the
     * distribution of scores in your gold standard matches your actual data.
     *
     * @param labeled
     */
    protected void trainMostSimilarNormalizer(List<KnownSim> labeled, final int numResults, final TIntSet validIds) {
        useNormalizer = false;
        ParallelForEach.loop(labeled, numThreads, new Function<KnownSim>() {
            public void call(KnownSim ks) throws IOException {
                ks.maybeSwap();
                Disambiguator.Match m = disambiguator.disambiguateMostSimilar(ks, numResults, validIds);
                if (m != null) {
                    DocScoreList dsl = mostSimilar(m.phraseWpId, numResults, validIds);
                    if (dsl != null) {
                        double sim = dsl.getScoreForId(m.hintWpId);
                        normalizer.observe(dsl, dsl.getIndexForId(m.hintWpId), ks.similarity);
                    }
                }
            }
        });
        normalizer.observationsFinished();
        useNormalizer = true;
        trained = true;
    }

    @Override
    public double similarity(String phrase1, String phrase2) throws IOException, ParseException {
        ensureTrained();
        if (mapper == null) {
            throw new UnsupportedOperationException("Mapper must be non-null to resolve phrases");
        }
        Disambiguator.Match m = disambiguator.disambiguateSimilarity(phrase1, phrase2);
        if (m == null) {
            return Double.NaN;
        } else {
            return similarity(m.phraseWpId, m.hintWpId);
        }
    }

    @Override
    public abstract double similarity(int wpId1, int wpId2) throws IOException;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Creates a list of similar documents. This implementation calls rawMostSimilar and normalizes the results.
     * @param wpId1
     * @param maxResults
     * @param possibleWpIds Only consider these ids
     * @return
     * @throws IOException
     */
    @Override
    public abstract DocScoreList mostSimilar(int wpId1, int maxResults, TIntSet possibleWpIds) throws IOException ;

    @Override
    public DocScoreList mostSimilar(String phrase, int maxResults, TIntSet possibleWpIds) throws IOException {
        ensureTrained();
        if (mapper == null) {
            throw new UnsupportedOperationException("Mapper must be non-null to resolve phrases");
        }
        Disambiguator.Match m = disambiguator.disambiguateMostSimilar(phrase, null, maxResults, possibleWpIds);
        if (m == null) {
            return null;
        }
        return mostSimilar(m.phraseWpId, maxResults, possibleWpIds);
    }

    /**
     * Throws an IllegalStateException if the model has not been trained.
     */
    protected void ensureTrained() {
        if (!trained) {
            throw new IllegalStateException("Model has not been trained.");
        }
    }

    @Override
    public DocScoreList mostSimilar(int wpId1, int maxResults) throws IOException {
        return mostSimilar(wpId1,  maxResults, null);
    }

    @Override
    public DocScoreList mostSimilar(String phrase, int maxResults) throws IOException {
        return mostSimilar(phrase, maxResults, null);
    }

    public IndexHelper getHelper() {
        return this.helper;
    }

    /**
     * Use the normalizer to normalize a similarity if it's available.
     * @param sim
     * @return
     */
    protected double normalize(double sim) {
        if (normalizer == null || !useNormalizer || Double.isInfinite(sim) || Double.isNaN(sim)) {
            return sim;
        } else if (!trained) {
            throw new IllegalStateException("Model has not been trained.");
        } else {
            return normalizer.normalize(sim);
        }
    }

    /**
     * Use the normalizer to normalize a list of score if possible.
     * @param dsl
     * @return
     */
    protected DocScoreList normalize(DocScoreList dsl) {
        if (normalizer == null || !useNormalizer) {
            return dsl;
        } else {
            return normalizer.normalize(dsl);
        }
    }

    /**
     * Writes the metric to a directory.
     * @param directory
     * @throws IOException
     */
    @Override
    public void write(File directory) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(new File(directory, "normalizer")));
        out.writeObject(normalizer);
        out.close();
        FileUtils.write(new File(directory, "trained"), "" + trained);
    }
    /**
     * Reads the metric from a directory.
     * @param directory
     * @throws IOException
     */
    @Override
    public void read(File directory) throws IOException {
        ObjectInputStream in = new ObjectInputStream(
                new FileInputStream(new File(directory, "normalizer")));
        try {
            this.normalizer = (Normalizer) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
        in.close();
        trained = Boolean.valueOf(
                FileUtils.readFileToString(new File(directory, "trained")));
    }

    /**
     * Sets the number of threads used when training the metric
     * @param n
     */
    public void setNumThreads(int n) {
        this.numThreads = n;
    }

    /**
     * Returns true iff the model has been trained.
     * @return
     */
    public boolean isTrained() {
        return trained;
    }

    public File getPath() {
        return path;
    }

    public void setPath(File path) {
        this.path = path;
    }

    public Normalizer getNormalizer() {
        return normalizer;
    }

    public static TDoubleArrayList readDoubles(File file) throws IOException {
        TDoubleArrayList vals = new TDoubleArrayList();
        for (String line : FileUtils.readLines(file))  {
            vals.add(Double.valueOf(line));
        }
        return vals;
    }

    public static void writeDoubles(TDoubleArrayList vals, File file) throws IOException {
        StringBuffer buff = new StringBuffer();
        for (double x : vals.toArray()) {
            buff.append(x + "\n");
        }
        FileUtils.write(file, buff.toString());
    }
}
