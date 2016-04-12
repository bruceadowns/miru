package com.jivesoftware.os.miru.stream.plugins.strut;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.MinMaxPriorityQueue;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruQueryServiceException;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.TimeAndVersion;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruTermId;
import com.jivesoftware.os.miru.api.field.MiruFieldType;
import com.jivesoftware.os.miru.api.query.filter.MiruAuthzExpression;
import com.jivesoftware.os.miru.api.query.filter.MiruFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruValue;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmapsDebug;
import com.jivesoftware.os.miru.plugin.cache.MiruPluginCacheProvider;
import com.jivesoftware.os.miru.plugin.context.MiruRequestContext;
import com.jivesoftware.os.miru.plugin.index.MiruActivityIndex;
import com.jivesoftware.os.miru.plugin.index.MiruFieldIndex;
import com.jivesoftware.os.miru.plugin.index.MiruTermComposer;
import com.jivesoftware.os.miru.plugin.solution.MiruAggregateUtil;
import com.jivesoftware.os.miru.plugin.solution.MiruPartitionResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruRemotePartition;
import com.jivesoftware.os.miru.plugin.solution.MiruRequest;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestHandle;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLog;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import com.jivesoftware.os.miru.plugin.solution.MiruTimeRange;
import com.jivesoftware.os.miru.plugin.solution.Question;
import com.jivesoftware.os.miru.stream.plugins.catwalk.CatwalkQuery;
import com.jivesoftware.os.miru.stream.plugins.strut.Strut.Scored;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * @author jonathan
 */
public class StrutQuestion implements Question<StrutQuery, StrutAnswer, StrutReport> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final ExecutorService asyncRescorers;
    private final Strut strut;
    private final MiruRequest<StrutQuery> request;
    private final MiruRemotePartition<StrutQuery, StrutAnswer, StrutReport> remotePartition;
    private final MiruBitmapsDebug bitmapsDebug = new MiruBitmapsDebug();
    private final MiruAggregateUtil aggregateUtil = new MiruAggregateUtil();

    public StrutQuestion(ExecutorService asyncRescorers,
        Strut strut,
        MiruRequest<StrutQuery> request,
        MiruRemotePartition<StrutQuery, StrutAnswer, StrutReport> remotePartition) {
        this.asyncRescorers = asyncRescorers;
        this.strut = strut;
        this.request = request;
        this.remotePartition = remotePartition;
    }

    @Override
    public <BM extends IBM, IBM> MiruPartitionResponse<StrutAnswer> askLocal(MiruRequestHandle<BM, IBM, ?> handle,
        Optional<StrutReport> report)
        throws Exception {

        StackBuffer stackBuffer = new StackBuffer();
        MiruSolutionLog solutionLog = new MiruSolutionLog(request.logLevel);
        MiruRequestContext<BM, IBM, ?> context = handle.getRequestContext();
        MiruBitmaps<BM, IBM> bitmaps = handle.getBitmaps();

        MiruTimeRange timeRange = request.query.timeRange;
        if (!context.getTimeIndex().intersects(timeRange)) {
            solutionLog.log(MiruSolutionLogLevel.WARN, "No time index intersection. Partition {}: {} doesn't intersect with {}",
                handle.getCoord().partitionId, context.getTimeIndex(), timeRange);
            StrutAnswer answer = strut.composeAnswer(context, request, Collections.emptyList());
            return new MiruPartitionResponse<>(answer, solutionLog.asList());
        }

        int activityIndexLastId = context.getActivityIndex().lastId(stackBuffer);

        List<IBM> ands = new ArrayList<>();
        ands.add(bitmaps.buildIndexMask(activityIndexLastId, context.getRemovalIndex().getIndex(stackBuffer)));
        MiruSchema schema = context.getSchema();
        MiruTermComposer termComposer = context.getTermComposer();

        if (!MiruFilter.NO_FILTER.equals(request.query.constraintFilter)) {
            BM constrained = aggregateUtil.filter("strutGather",
                bitmaps, schema, termComposer,
                context.getFieldIndexProvider(),
                request.query.constraintFilter,
                solutionLog,
                null,
                activityIndexLastId,
                -1,
                stackBuffer);
            ands.add(constrained);
        }

        if (!MiruAuthzExpression.NOT_PROVIDED.equals(request.authzExpression)) {
            ands.add(context.getAuthzIndex().getCompositeAuthz(request.authzExpression, stackBuffer));
        }

        if (!MiruTimeRange.ALL_TIME.equals(request.query.timeRange)) {
            ands.add(bitmaps.buildTimeRangeMask(context.getTimeIndex(), timeRange.smallestTimestamp, timeRange.largestTimestamp, stackBuffer));
        }

        ands.add(bitmaps.buildIndexMask(activityIndexLastId, context.getRemovalIndex().getIndex(stackBuffer)));

        bitmapsDebug.debug(solutionLog, bitmaps, "ands", ands);
        BM eligible = bitmaps.and(ands);

        int pivotFieldId = schema.getFieldId(request.query.constraintField);

        MiruFieldIndex<BM, IBM> primaryIndex = context.getFieldIndexProvider().getFieldIndex(MiruFieldType.primary);

        MinMaxPriorityQueue<Scored> scored = MinMaxPriorityQueue
            .expectedSize(request.query.desiredNumberOfResults)
            .maximumSize(request.query.desiredNumberOfResults)
            .create();

        MiruPluginCacheProvider.CacheKeyValues cacheStores = handle.getRequestContext()
            .getCacheProvider()
            .get("strut-" + request.query.catwalkId, 8, false);

        StrutModelScorer modelScorer = new StrutModelScorer(); // Ahh

        long start = System.currentTimeMillis();
        List<LastIdAndTermId> lastIdAndTermIds = Lists.newArrayList();
        //TODO config batch size
        aggregateUtil.gather("strut", bitmaps, context, eligible, pivotFieldId, 100, true, solutionLog, (lastId, termId) -> {
            lastIdAndTermIds.add(new LastIdAndTermId(lastId, termId));
            return true;
        }, stackBuffer);

        solutionLog.log(MiruSolutionLogLevel.INFO, "Strut accumulated {} terms in {} ms", lastIdAndTermIds.size(), System.currentTimeMillis() - start);
        start = System.currentTimeMillis();

        long totalTimeFetchingLastId = 0;
        long totalTimeFetchingScores = 0;
        long totalTimeRescores = 0;

        List<LastIdAndTermId> asyncRescore = Lists.newArrayList();
        if (request.query.usePartitionModelCache) {
            long fetchScoresStart = System.currentTimeMillis();

            int[] scoredToLastIds = new int[lastIdAndTermIds.size()];
            MiruTermId[] nullableMiruTermIds = new MiruTermId[lastIdAndTermIds.size()];
            MiruTermId[] miruTermIds = new MiruTermId[lastIdAndTermIds.size()];

            Arrays.fill(miruTermIds, null);
            for (int i = 0; i < lastIdAndTermIds.size(); i++) {
                miruTermIds[i] = lastIdAndTermIds.get(i).termId;
            }

            System.arraycopy(miruTermIds, 0, nullableMiruTermIds, 0, lastIdAndTermIds.size());
            Arrays.fill(scoredToLastIds, -1);

            long fetchLastIdsStart = System.currentTimeMillis();
            primaryIndex.multiGetLastIds("strut", pivotFieldId, nullableMiruTermIds, scoredToLastIds, stackBuffer);
            totalTimeFetchingLastId += (System.currentTimeMillis() - fetchLastIdsStart);

            modelScorer.score(
                request.query.modelId,
                miruTermIds,
                cacheStores,
                (termIndex, score, scoredToLastId) -> {
                    if (Float.isNaN(score) || scoredToLastId < scoredToLastIds[termIndex]) {
                        asyncRescore.add(lastIdAndTermIds.get(termIndex));
                    }
                    scored.add(new Scored(lastIdAndTermIds.get(termIndex).lastId,
                        miruTermIds[termIndex],
                        scoredToLastIds[termIndex],
                        Float.isNaN(score) ? 0f : score,
                        -1,
                        null));
                    return true;
                },
                stackBuffer);
            totalTimeFetchingScores += (System.currentTimeMillis() - fetchScoresStart);
        } else {
            int batchSize = 100; //TODO config batch size
            BM[] answers = bitmaps.createArrayOf(batchSize);
            BM[] constrainFeature = buildConstrainFeatures(solutionLog, bitmaps, context, schema, termComposer, stackBuffer, activityIndexLastId);
            long rescoreStart = System.currentTimeMillis();
            for (List<LastIdAndTermId> batch : Lists.partition(lastIdAndTermIds, answers.length)) {
                List<Scored> rescored = rescore(handle, batch, pivotFieldId, constrainFeature, modelScorer, cacheStores, solutionLog);
                scored.addAll(rescored);
            }
            totalTimeRescores += (System.currentTimeMillis() - rescoreStart);
        }

        if (!asyncRescore.isEmpty()) {
            asyncRescorers.submit(() -> {
                try {
                    BM[] asyncConstrainFeature = buildConstrainFeatures(solutionLog, bitmaps, context, schema, termComposer, stackBuffer, activityIndexLastId);
                    MiruSolutionLog asyncSolutionLog = new MiruSolutionLog(MiruSolutionLogLevel.NONE);
                    rescore(handle, asyncRescore, pivotFieldId, asyncConstrainFeature, modelScorer, cacheStores, asyncSolutionLog);
                } catch (Exception x) {
                    LOG.warn("Failed while trying to rescore.", x);
                }
            });
        }

        int[] gatherFieldIds = null;
        if (request.query.gatherTermsForFields != null && request.query.gatherTermsForFields.length > 0) {
            gatherFieldIds = new int[request.query.gatherTermsForFields.length];
            for (int i = 0; i < gatherFieldIds.length; i++) {
                gatherFieldIds[i] = schema.getFieldId(request.query.gatherTermsForFields[i]);
                Preconditions.checkArgument(schema.getFieldDefinition(gatherFieldIds[i]).type.hasFeature(MiruFieldDefinition.Feature.stored),
                    "You can only gather stored fields");
            }
        }

        MiruActivityIndex activityIndex = context.getActivityIndex();
        MiruFieldDefinition pivotFieldDefinition = schema.getFieldDefinition(pivotFieldId);
        List<HotOrNot> hotOrNots = new ArrayList<>(request.query.desiredNumberOfResults);

        Scored[] s = scored.toArray(new Scored[0]);
        int[] scoredLastIds = new int[s.length];
        for (int j = 0; j < s.length; j++) {
            scoredLastIds[j] = s[j].lastId;
        }

        long gatherStart = System.currentTimeMillis();
        MiruValue[][][] gatherScoredValues = null;
        if (gatherFieldIds != null) {
            gatherScoredValues = new MiruValue[scoredLastIds.length][gatherFieldIds.length][];
            int[] consumeLastIds = new int[scoredLastIds.length];
            for (int j = 0; j < gatherFieldIds.length; j++) {
                System.arraycopy(scoredLastIds, 0, consumeLastIds, 0, scoredLastIds.length);
                MiruTermId[][] termIds = activityIndex.getAll("strut", consumeLastIds, gatherFieldIds[j], stackBuffer);
                for (int k = 0; k < termIds.length; k++) {
                    if (termIds[k] != null) {
                        gatherScoredValues[k][j] = new MiruValue[termIds[k].length];
                        for (int l = 0; l < termIds[k].length; l++) {
                            gatherScoredValues[k][j][l] = new MiruValue(termComposer.decompose(schema,
                                schema.getFieldDefinition(gatherFieldIds[j]),
                                stackBuffer,
                                termIds[k][l]));
                        }
                    }
                }
            }
        }
        long totalTimeGather = System.currentTimeMillis() - gatherStart;

        long timeAndVersionStart = System.currentTimeMillis();
        int[] consumeLastIds = new int[scoredLastIds.length];
        System.arraycopy(scoredLastIds, 0, consumeLastIds, 0, scoredLastIds.length);
        TimeAndVersion[] timeAndVersions = activityIndex.getAllTimeAndVersions("strut", consumeLastIds, stackBuffer);
        long totalTimeAndVersion = System.currentTimeMillis() - timeAndVersionStart;

        for (int j = 0; j < s.length; j++) {
            if (timeAndVersions[j] != null) {
                String[] decomposed = termComposer.decompose(schema, pivotFieldDefinition, stackBuffer, s[j].term);
                hotOrNots.add(new HotOrNot(new MiruValue(decomposed),
                    gatherScoredValues != null ? gatherScoredValues[j] : null,
                    s[j].score,
                    s[j].termCount,
                    s[j].features,
                    timeAndVersions[j].timestamp));
            } else {
                LOG.warn("Failed to get timestamp for {}", scoredLastIds[j]);
            }
        }

        solutionLog.log(MiruSolutionLogLevel.INFO, "Strut your stuff for {} terms took" +
                " lastIds {} ms," +
                " cached {} ms," +
                " rescore {} ms," +
                " gather {} ms," +
                " timeAndVersion {} ms," +
                " total {} ms",
            lastIdAndTermIds.size(),
            totalTimeFetchingLastId,
            totalTimeFetchingScores,
            totalTimeRescores,
            totalTimeGather,
            totalTimeAndVersion,
            System.currentTimeMillis() - start);

        solutionLog.log(MiruSolutionLogLevel.INFO, "Strut found {} terms", hotOrNots.size());

        return new MiruPartitionResponse<>(strut.composeAnswer(context, request, hotOrNots), solutionLog.asList());
    }

    private <BM extends IBM, IBM> List<Scored> rescore(
        MiruRequestHandle<BM, IBM, ?> handle,
        List<LastIdAndTermId> score,
        int pivotFieldId,
        BM[] constrainFeature,
        StrutModelScorer modelScorer,
        MiruPluginCacheProvider.CacheKeyValues cacheStores,
        MiruSolutionLog solutionLog) throws Exception {

        long startStrut = System.currentTimeMillis();
        MiruBitmaps<BM, IBM> bitmaps = handle.getBitmaps();
        MiruRequestContext<BM, IBM, ?> context = handle.getRequestContext();
        MiruFieldIndex<BM, IBM> primaryIndex = context.getFieldIndexProvider().getFieldIndex(MiruFieldType.primary);

        StackBuffer stackBuffer = new StackBuffer();

        BM[][] answers = bitmaps.createMultiArrayOf(score.size(), constrainFeature.length);
        int[] scoredToLastIds = new int[score.size()];
        Arrays.fill(scoredToLastIds, -1);
        List<Scored> results = Lists.newArrayList();
        List<Scored> updates = Lists.newArrayList();

        strut.yourStuff("strut",
            handle.getCoord(),
            bitmaps,
            context,
            request,
            (streamBitmaps) -> {
                LastIdAndTermId[] rescoreMiruTermIds = score.toArray(new LastIdAndTermId[0]);
                MiruTermId[] miruTermIds = new MiruTermId[rescoreMiruTermIds.length];
                for (int i = 0; i < rescoreMiruTermIds.length; i++) {
                    miruTermIds[i] = rescoreMiruTermIds[i].termId;
                }
                bitmaps.multiTx(
                    (tx, stackBuffer1) -> primaryIndex.multiTxIndex("strut", pivotFieldId, miruTermIds, -1, stackBuffer1, tx),
                    (index, lastId, bitmap) -> {
                        for (int i = 0; i < constrainFeature.length; i++) {
                            if (constrainFeature[i] != null) {
                                answers[index][i] = bitmaps.and(Arrays.asList(bitmap, constrainFeature[i]));
                            } else {
                                answers[index][i] = bitmap;
                            }
                        }
                        scoredToLastIds[index] = lastId;
                    },
                    stackBuffer);

                for (int i = 0; i < rescoreMiruTermIds.length; i++) {
                    if (!streamBitmaps.stream(i, rescoreMiruTermIds[i].lastId, rescoreMiruTermIds[i].termId, scoredToLastIds[i], answers[i])) {
                        return false;
                    }
                }
                return true;
            },
            (streamIndex, hotness, cacheable) -> {
                results.add(hotness);
                if (cacheable) {
                    updates.add(hotness);
                }
                return true;
            },
            solutionLog);
        solutionLog.log(MiruSolutionLogLevel.INFO, "Strut rescore took {} ms", System.currentTimeMillis() - startStrut);

        if (!updates.isEmpty()) {
            long startOfUpdates = System.currentTimeMillis();
            modelScorer.commit(request.query.modelId, cacheStores, updates, stackBuffer);
            LOG.info("Strut score updates {} features in {} ms", updates.size(), System.currentTimeMillis() - startOfUpdates);
        }
        return results;
    }

    private <BM extends IBM, IBM> BM[] buildConstrainFeatures(MiruSolutionLog solutionLog,
        MiruBitmaps<BM, IBM> bitmaps,
        MiruRequestContext<BM, IBM, ?> context,
        MiruSchema schema, MiruTermComposer termComposer, StackBuffer stackBuffer, int activityIndexLastId) throws Exception {
        CatwalkQuery.CatwalkFeature[] features = request.query.catwalkQuery.features;
        BM[] constrainFeature = bitmaps.createArrayOf(features.length);
        for (int i = 0; i < features.length; i++) {
            constrainFeature[i] = aggregateUtil.filter("strutCatwalk",
                bitmaps,
                schema,
                termComposer,
                context.getFieldIndexProvider(),
                request.query.catwalkQuery.features[i].featureFilter,
                solutionLog,
                null,
                activityIndexLastId,
                -1,
                stackBuffer);
        }

        if (!MiruFilter.NO_FILTER.equals(request.query.featureFilter)) {
            BM strutFeature = aggregateUtil.filter("strutFeature",
                bitmaps,
                schema,
                termComposer,
                context.getFieldIndexProvider(),
                request.query.featureFilter,
                solutionLog,
                null,
                activityIndexLastId,
                -1,
                stackBuffer);
            for (int i = 0; i < features.length; i++) {
                bitmaps.inPlaceAnd(constrainFeature[i], strutFeature);
            }
        }
        return constrainFeature;
    }

    private static class LastIdAndTermId {

        private final int lastId;
        private final MiruTermId termId;

        public LastIdAndTermId(int lastId, MiruTermId termId) {
            this.lastId = lastId;
            this.termId = termId;
        }
    }

    @Override
    public MiruPartitionResponse<StrutAnswer> askRemote(MiruHost host,
        MiruPartitionId partitionId,
        Optional<StrutReport> report) throws MiruQueryServiceException {
        return remotePartition.askRemote(host, partitionId, request, report);
    }

    @Override
    public Optional<StrutReport> createReport(Optional<StrutAnswer> answer) {
        return answer.transform(input -> new StrutReport());
    }
}
