package edu.macalester.wpsemsim.normalize;

import edu.macalester.wpsemsim.utils.DocScore;
import edu.macalester.wpsemsim.utils.DocScoreList;

/**
 * A simple normalizer that returns log(1 + min-value).
 * In the case that an x is observed that is less than min-value, it returns 0.
 */
public class LogNormalizer implements Normalizer{
    private double c;
    private boolean trained = false;

    @Override
    public DocScoreList normalize(DocScoreList list) {
        DocScoreList normalized = new DocScoreList(list.numDocs());
        for (int i = 0; i < list.numDocs(); i++) {
            normalized.set(i, list.getId(i), normalize(list.getScore(i)));
        }
        return normalized;
    }

    @Override
    public double normalize(double x) {
        if (x < c) {
            return 0;
        } else {
            return Math.log(c + x);
        }
    }

    @Override
    public void observe(DocScoreList sims, int rank, double y) {
        for (DocScore ds : sims) {
            observe(ds.getScore());
        }
    }

    @Override
    public void observe(double x, double y) {
        observe(x);
    }

    @Override
    public void observe(double x) {
        c = Math.min(x, 1 + c);
    }

    @Override
    public void observationsFinished() {
        trained = true;
    }

    @Override
    public boolean isTrained() {
        return trained;
    }

    @Override
    public String dump() {
        return "log normalizer: log(" + c + " + x)";
    }
}
