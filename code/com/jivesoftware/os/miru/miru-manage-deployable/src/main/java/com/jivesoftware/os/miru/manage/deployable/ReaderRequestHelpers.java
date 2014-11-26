package com.jivesoftware.os.miru.manage.deployable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jivesoftware.os.jive.utils.http.client.HttpClientConfiguration;
import com.jivesoftware.os.jive.utils.http.client.HttpClientFactory;
import com.jivesoftware.os.jive.utils.http.client.HttpClientFactoryProvider;
import com.jivesoftware.os.jive.utils.http.client.rest.RequestHelper;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.cluster.MiruClusterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;

/**
 *
 */
public class ReaderRequestHelpers {

    private final MiruClusterRegistry clusterRegistry;
    private final ObjectMapper objectMapper;

    private final Random random = new Random();
    private final ConcurrentMap<MiruHost, RequestHelper> hostToHelper = Maps.newConcurrentMap();
    private final HttpClientFactory httpClientFactory = new HttpClientFactoryProvider()
        .createHttpClientFactory(Collections.<HttpClientConfiguration>emptyList());

    public ReaderRequestHelpers(MiruClusterRegistry clusterRegistry, ObjectMapper objectMapper) {
        this.clusterRegistry = clusterRegistry;
        this.objectMapper = objectMapper;
    }

    public List<RequestHelper> get(Optional<MiruHost> excludingHost) throws Exception {
        List<MiruHost> hosts = Lists.newArrayList();
        for (MiruClusterRegistry.HostHeartbeat heartbeat : clusterRegistry.getAllHosts()) {
            hosts.add(heartbeat.host);
        }
        if (excludingHost.isPresent()) {
            hosts.remove(excludingHost.get());
        }

        if (hosts.isEmpty()) {
            throw new RuntimeException("No hosts to complete request");
        }

        List<RequestHelper> requestHelpers = Lists.newArrayListWithCapacity(hosts.size());
        for (MiruHost host : hosts) {
            RequestHelper requestHelper = hostToHelper.get(host);
            if (requestHelper == null) {
                RequestHelper existing = hostToHelper.putIfAbsent(host,
                    new RequestHelper(httpClientFactory.createClient(host.getLogicalName(), host.getPort()), objectMapper));
                if (existing != null) {
                    requestHelper = existing;
                }
            }
            requestHelpers.add(requestHelper);
        }

        Collections.shuffle(requestHelpers, random);
        return requestHelpers;
    }
}
