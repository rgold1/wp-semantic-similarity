package edu.macalester.wpsemsim.normalize;

import edu.macalester.wpsemsim.utils.DocScoreList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

import java.text.DecimalFormat;
import java.util.logging.Logger;

public class RankAndScoreNormalizer extends BaseNormalizer {
    private static Logger LOG = Logger.getLogger(RankAndScoreNormalizer.class.getName());

    private double intercept;
    private double rankCoeff;
    private double scoreCoeff;
    private boolean logTransform = false;

    // temporary accumulators that feed into regression
    private transient TIntArrayList ranks = new TIntArrayList();
    private transient TDoubleArrayList scores = new TDoubleArrayList();
    private transient TDoubleArrayList ys = new TDoubleArrayList();

    @Override
    public void observe(DocScoreList list, int index, double y) {
        if (index >= 0) {
            double score = list.getScore(index);
            if (!Double.isNaN(score) && !Double.isInfinite(score)) {
                ranks.add(index);
                scores.add(score);
                ys.add(y);
            }
        }
        super.observe(list, index, y);
    }

    public void setLogTransform(boolean logTransform) {
        this.logTransform = logTransform;
    }

    @Override
    public void observationsFinished() {
        super.observationsFinished();
        double Y[] = ys.toArray();
        double X[][] = new double[Y.length][2];
        for (int i = 0; i < Y.length; i++) {
            X[i][0] = Math.log(1 + ranks.get(i));
            X[i][1] = logIfNecessary(scores.get(1));
        }
        OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
        regression.newSampleData(Y, X);
        double [] params = regression.estimateRegressionParameters();
        intercept = params[0];
        rankCoeff = params[1];
        scoreCoeff = params[2];
        LOG.info("trained model " + dump() + " with R-squared " + regression.calculateRSquared());
    }

    @Override
    public DocScoreList normalize(DocScoreList list) {
        DocScoreList normalized = new DocScoreList(list.numDocs());
        normalized.setMissingScore(missingMean);
        for (int i = 0; i < list.numDocs(); i++) {
            double s = logIfNecessary(list.getScore(i));
            double score = intercept + rankCoeff * Math.log(i + 1) + scoreCoeff * s;
            normalized.set(i, list.getId(i), score);
        }
        return normalized;

    }

    private double logIfNecessary(double x) {
        return logTransform ? Math.log(1 + x - min) : x;
    }

    @Override
    public double normalize(double x) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String dump() {
        DecimalFormat df = new DecimalFormat("#.###");
        return (
                df.format(rankCoeff) + "*log(1 + rank) + " +
                df.format(scoreCoeff) + "*score + " +
                df.format(intercept)
            );
    }
}
