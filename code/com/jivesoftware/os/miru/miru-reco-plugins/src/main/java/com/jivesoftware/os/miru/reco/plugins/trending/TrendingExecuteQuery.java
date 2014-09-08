package com.jivesoftware.os.miru.reco.plugins.trending;

import com.google.common.base.Optional;
import com.jivesoftware.os.jive.utils.http.client.rest.RequestHelper;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.query.ExecuteMiruFilter;
import com.jivesoftware.os.miru.query.ExecuteQuery;
import com.jivesoftware.os.miru.query.MiruBitmaps;
import com.jivesoftware.os.miru.query.MiruBitmapsDebug;
import com.jivesoftware.os.miru.query.MiruQueryHandle;
import com.jivesoftware.os.miru.query.MiruQueryStream;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class TrendingExecuteQuery implements ExecuteQuery<TrendingResult, TrendingReport> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final Trending trending;
    private final TrendingQuery query;
    private final MiruBitmapsDebug bitmapsDebug = new MiruBitmapsDebug();

    public TrendingExecuteQuery(Trending trending,
            TrendingQuery query) {
        this.trending = trending;
        this.query = query;
    }

    @Override
    public <BM> TrendingResult executeLocal(MiruQueryHandle<BM> handle, Optional<TrendingReport> report) throws Exception {
        MiruQueryStream<BM> stream = handle.getQueryStream();
        MiruBitmaps<BM> bitmaps = handle.getBitmaps();

        // Start building up list of bitmap operations to run
        List<BM> ands = new ArrayList<>();

        // 1) Execute the combined filter above on the given stream, add the bitmap
        ExecuteMiruFilter<BM> executeMiruFilter = new ExecuteMiruFilter<>(bitmaps, stream.schema, stream.fieldIndex, stream.executorService,
                query.constraintsFilter, Optional.<BM>absent(), -1);
        ands.add(executeMiruFilter.call());

        // 2) Add in the authz check if we have it
        if (query.authzExpression.isPresent()) {
            ands.add(stream.authzIndex.getCompositeAuthz(query.authzExpression.get()));
        }

        // 3) Mask out anything that hasn't made it into the activityIndex yet, orToSourceSize that has been removed from the index
        ands.add(bitmaps.buildIndexMask(stream.activityIndex.lastId(), Optional.of(stream.removalIndex.getIndex())));

        // AND it all together and return the results
        BM answer = bitmaps.create();
        bitmapsDebug.debug(LOG, bitmaps, "ands", ands);
        bitmaps.and(answer, ands);

        return trending.trending(bitmaps, stream, query, report, answer);
    }

    @Override
    public TrendingResult executeRemote(RequestHelper requestHelper, MiruPartitionId partitionId, Optional<TrendingResult> lastResult) throws Exception {
        return new TrendingRemotePartitionReader(requestHelper).scoreTrending(partitionId, query, lastResult);
    }

    @Override
    public Optional<TrendingReport> createReport(Optional<TrendingResult> result) {
        Optional<TrendingReport> report = Optional.absent();
        if (result.isPresent()) {
            report = Optional.of(new TrendingReport(
                    result.get().collectedDistincts,
                    result.get().aggregateTerms));
        }
        return report;
    }
}
