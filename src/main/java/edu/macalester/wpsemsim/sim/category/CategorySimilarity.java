package edu.macalester.wpsemsim.sim.category;

import edu.macalester.wpsemsim.concepts.ConceptMapper;
import edu.macalester.wpsemsim.lucene.IndexHelper;
import edu.macalester.wpsemsim.sim.BaseSimilarityMetric;
import edu.macalester.wpsemsim.utils.DocScoreList;
import gnu.trove.set.TIntSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;

import java.io.IOException;
import java.util.logging.Logger;

public class CategorySimilarity extends BaseSimilarityMetric {
    private static final Logger LOG = Logger.getLogger(CategorySimilarity.class.getName());

    private CategoryGraph graph;
    private IndexHelper helper;
    private DirectoryReader reader;

    public CategorySimilarity(CategoryGraph graph, IndexHelper helper) {
        this(null, graph, helper);
    }

    public CategorySimilarity(ConceptMapper mapper, CategoryGraph graph, IndexHelper helper) {
        super(mapper, helper);
        this.helper = helper;
        this.reader = helper.getReader();
        this.graph = graph;
        setName("category-similarity");
    }

    public double distanceToScore(double distance) {
        return distanceToScore(graph, distance);
    }

    public static double distanceToScore(CategoryGraph graph, double distance) {
        distance = Math.max(distance, graph.minCost);
        assert(graph.minCost < 1.0);    // if this isn't true, direction is flipped.
        return  (Math.log(distance) / Math.log(graph.minCost));
    }

    @Override
    public DocScoreList mostSimilar(int wpId, int maxResults, TIntSet possibleWpIds) throws IOException {
        if (hasCachedMostSimilar(wpId)) {
            return getCachedMostSimilar(wpId, maxResults, possibleWpIds);
        }
        int luceneId = helper.wpIdToLuceneId(wpId);
        if (luceneId < 0) {
            LOG.info("unknown wpId: " + wpId);
            return new DocScoreList(0);
        }
        Document doc = reader.document(luceneId);
        CategoryBfs bfs = new CategoryBfs(graph, doc, maxResults, possibleWpIds);
        while (bfs.hasMoreResults()) {
            bfs.step();
        }
        DocScoreList results = new DocScoreList(bfs.getPageDistances().size());
        int i = 0;
        for (int pageId: bfs.getPageDistances().keys()) {
            results.set(i++, pageId, distanceToScore(bfs.getPageDistances().get(pageId)));
        }
        return normalize(results);
    }


    @Override
    public double similarity(int wpId1, int wpId2) throws IOException {
        if (wpId1 == wpId2) { return normalize(distanceToScore(0.0)); }     // hack

        int id1 = helper.wpIdToLuceneId(wpId1);
        int id2 = helper.wpIdToLuceneId(wpId2);
        if (id1 < 0) {
            LOG.finest("unknown wpId: " + wpId1);
            return normalize(0.0);
        }
        if (id2 < 0) {
            LOG.finest("unknown wpId: " + wpId2);
            return normalize(0.0);
        }
        Document d1 = graph.reader.document(id1);
        Document d2 = graph.reader.document(id2);

        CategoryBfs bfs1 = new CategoryBfs(graph, d1, Integer.MAX_VALUE, null);
        CategoryBfs bfs2 = new CategoryBfs(graph, d2, Integer.MAX_VALUE, null);
        bfs1.setAddPages(false);
        bfs1.setExploreChildren(false);
        bfs2.setAddPages(false);
        bfs2.setExploreChildren(false);

        double shortestDistance = Double.POSITIVE_INFINITY;
        double maxDist1 = 0;
        double maxDist2 = 0;

        while ((bfs1.hasMoreResults() || bfs2.hasMoreResults())
        &&     (maxDist1 + maxDist2 < shortestDistance)) {
            // Search from d1
            while (bfs1.hasMoreResults() && (maxDist1 <= maxDist2 || !bfs2.hasMoreResults())) {
                CategoryBfs.BfsVisited visited = bfs1.step();
                for (int catId : visited.cats.keys()) {
                    if (bfs2.hasCategoryDistance(catId)) {
                        double d = bfs1.getCategoryDistance(catId)
                                + bfs2.getCategoryDistance(catId)
                                - graph.catCosts[catId];    // counted twice
                        shortestDistance = Math.min(d, shortestDistance);
                    }
                }
                maxDist1 = Math.max(maxDist1, visited.maxCatDistance());
            }

            // Search from d2
            while (bfs2.hasMoreResults() && (maxDist2 <= maxDist1 || !bfs1.hasMoreResults())) {
                CategoryBfs.BfsVisited visited = bfs2.step();
                for (int catId : visited.cats.keys()) {
                    if (bfs1.hasCategoryDistance(catId)) {
                        double d = bfs1.getCategoryDistance(catId) +
                                bfs2.getCategoryDistance(catId) + 0
                                - graph.catCosts[catId];    // counted twice;
                        shortestDistance = Math.min(d, shortestDistance);
                    }
                }
                maxDist2 = Math.max(maxDist2, visited.maxCatDistance());
            }
        }

        return normalize(distanceToScore(shortestDistance));
    }
}
