package com.jivesoftware.os.miru.cluster.rcvs;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jivesoftware.os.jive.utils.ordered.id.ConstantWriterIdProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProviderImpl;
import com.jivesoftware.os.miru.api.MiruBackingStorage;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionCoordInfo;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.api.MiruTopologyStatus;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.marshall.MiruVoidByte;
import com.jivesoftware.os.miru.api.topology.MiruReplicaHosts;
import com.jivesoftware.os.miru.cluster.MiruClusterRegistry;
import com.jivesoftware.os.miru.cluster.MiruRegistryClusterClient;
import com.jivesoftware.os.miru.cluster.MiruReplicaSet;
import com.jivesoftware.os.miru.cluster.MiruTenantConfigFields;
import com.jivesoftware.os.miru.cluster.client.MiruReplicaSetDirector;
import com.jivesoftware.os.rcvs.api.timestamper.CurrentTimestamper;
import com.jivesoftware.os.rcvs.api.timestamper.Timestamper;
import com.jivesoftware.os.rcvs.inmemory.InMemoryRowColumnValueStore;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class MiruRCVSClusterRegistryTest {

    private final int numReplicas = 3;
    private final MiruTenantId tenantId = new MiruTenantId(new byte[] { 1, 2, 3, 4 });
    private final MiruPartitionId partitionId = MiruPartitionId.of(0);

    private Timestamper timestamper = new CurrentTimestamper();
    private MiruReplicaSetDirector replicaSetDirector;
    private MiruRCVSClusterRegistry registry;

    @BeforeMethod
    public void setUp() throws Exception {
        registry = new MiruRCVSClusterRegistry(
            timestamper,
            new InMemoryRowColumnValueStore<MiruVoidByte, MiruHost, MiruHostsColumnKey, MiruHostsColumnValue>(),
            new InMemoryRowColumnValueStore<MiruVoidByte, MiruHost, MiruTenantId, MiruVoidByte>(),
            new InMemoryRowColumnValueStore<MiruVoidByte, MiruHost, MiruTenantId, MiruVoidByte>(),
            new InMemoryRowColumnValueStore<MiruTenantId, MiruHost, MiruPartitionId, MiruVoidByte>(),
            new InMemoryRowColumnValueStore<MiruTenantId, MiruPartitionId, Long, MiruHost>(),
            new InMemoryRowColumnValueStore<MiruVoidByte, MiruTenantId, MiruTopologyColumnKey, MiruTopologyColumnValue>(),
            new InMemoryRowColumnValueStore<MiruVoidByte, MiruTenantId, MiruTenantConfigFields, Long>(),
            new InMemoryRowColumnValueStore<MiruVoidByte, MiruTenantId, MiruSchemaColumnKey, MiruSchema>(),
            numReplicas,
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(1));

        MiruRegistryClusterClient clusterClient = new MiruRegistryClusterClient(registry);

        replicaSetDirector = new MiruReplicaSetDirector(new OrderIdProviderImpl(new ConstantWriterIdProvider(1)), clusterClient);
    }

    @Test
    public void testUpdateAndGetTopology() throws Exception {
        MiruHost[] hosts = addHosts(1);

        Set<MiruHost> electedHosts = replicaSetDirector.electToReplicaSetForTenantPartition(tenantId,
            partitionId,
            new MiruReplicaHosts(false, Sets.<MiruHost>newHashSet(), numReplicas),
            timestamper.get() - TimeUnit.HOURS.toMillis(1));
        assertEquals(electedHosts.size(), 1);

        MiruPartitionCoord coord = new MiruPartitionCoord(tenantId, partitionId, hosts[0]);
        registry.updateTopologies(hosts[0], Arrays.asList(
            new MiruClusterRegistry.TopologyUpdate(coord,
                Optional.of(new MiruPartitionCoordInfo(MiruPartitionState.online, MiruBackingStorage.disk)),
                Optional.of(timestamper.get()))));

        List<MiruTopologyStatus> topologyStatusForTenantHost = registry.getTopologyStatusForTenantHost(tenantId, hosts[0]);
        List<MiruTopologyStatus> onlineStatus = Lists.newArrayList();
        for (MiruTopologyStatus status : topologyStatusForTenantHost) {
            if (status.partition.info.state == MiruPartitionState.online) {
                onlineStatus.add(status);
            }
        }
        assertEquals(onlineStatus.size(), 1);

        MiruTopologyStatus status = onlineStatus.get(0);
        assertEquals(status.partition.coord, coord);
        assertEquals(status.partition.info.storage, MiruBackingStorage.disk);
    }

    @Test
    public void testRefreshAndGetTopology() throws Exception {
        MiruHost[] hosts = addHosts(1);

        Set<MiruHost> electedHosts = replicaSetDirector.electToReplicaSetForTenantPartition(tenantId,
            partitionId,
            new MiruReplicaHosts(false, Sets.<MiruHost>newHashSet(), numReplicas),
            timestamper.get() - TimeUnit.HOURS.toMillis(1));
        assertEquals(electedHosts.size(), 1);

        MiruPartitionCoord coord = new MiruPartitionCoord(tenantId, partitionId, hosts[0]);
        registry.updateTopologies(hosts[0], Arrays.asList(
            new MiruClusterRegistry.TopologyUpdate(coord, Optional.<MiruPartitionCoordInfo>absent(), Optional.of(timestamper.get()))));

        List<MiruTopologyStatus> topologyStatusForTenantHost = registry.getTopologyStatusForTenantHost(tenantId, hosts[0]);
        List<MiruTopologyStatus> offlineStatus = Lists.newArrayList();
        for (MiruTopologyStatus status : topologyStatusForTenantHost) {
            if (status.partition.info.state == MiruPartitionState.offline) {
                offlineStatus.add(status);
            }
        }

        assertEquals(offlineStatus.size(), 1);

        MiruTopologyStatus status = offlineStatus.get(0);
        assertEquals(status.partition.coord, coord);
        assertEquals(status.partition.info.storage, MiruBackingStorage.memory);
    }

    @Test
    public void testElectAndMoveReplica() throws Exception {
        MiruHost[] hosts = addHosts(4);

        Set<MiruHost> electedHosts = replicaSetDirector.electToReplicaSetForTenantPartition(tenantId,
            partitionId,
            new MiruReplicaHosts(false, Sets.<MiruHost>newHashSet(), numReplicas),
            timestamper.get() - TimeUnit.HOURS.toMillis(1));

        MiruReplicaSet replicaSet = registry.getReplicaSets(tenantId, Arrays.asList(partitionId)).get(partitionId);

        assertEquals(replicaSet.getHostsWithReplica(), electedHosts);

        Set<MiruHost> unelectedHosts = Sets.newHashSet(hosts);
        unelectedHosts.removeAll(electedHosts);

        assertEquals(unelectedHosts.size(), 1);

        MiruHost fromHost = electedHosts.iterator().next();
        MiruHost toHost = unelectedHosts.iterator().next();

        replicaSetDirector.moveReplica(tenantId, partitionId, Optional.of(fromHost), toHost);

        replicaSet = registry.getReplicaSets(tenantId, Arrays.asList(partitionId)).get(partitionId);

        assertEquals(replicaSet.getHostsWithReplica().size(), numReplicas);
        assertFalse(replicaSet.getHostsWithReplica().contains(fromHost));
        assertTrue(replicaSet.getHostsWithReplica().contains(toHost));
    }

    @Test
    public void testSchemaProvider() throws Exception {
        MiruTenantId tenantId1 = new MiruTenantId("tenant1".getBytes());
        MiruSchema schema1 = new MiruSchema.Builder("test1", 1)
            .setFieldDefinitions(new MiruFieldDefinition[] {
                new MiruFieldDefinition(0, "a", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE),
                new MiruFieldDefinition(1, "b", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE)
            })
            .build();
        MiruTenantId tenantId2 = new MiruTenantId("tenant2".getBytes());
        MiruSchema schema2 = new MiruSchema.Builder("test2", 2)
            .setFieldDefinitions(new MiruFieldDefinition[] {
                new MiruFieldDefinition(0, "c", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE),
                new MiruFieldDefinition(1, "d", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE)
            })
            .build();

        InMemoryRowColumnValueStore<MiruVoidByte, MiruTenantId, MiruSchemaColumnKey, MiruSchema> schemaRegistry = new InMemoryRowColumnValueStore<>();

        registry.registerSchema(tenantId1, schema1);
        registry.registerSchema(tenantId2, schema2);

        assertEquals(registry.getSchema(tenantId1).getName(), "test1");
        assertEquals(registry.getSchema(tenantId1).getVersion(), 1L);
        assertEquals(registry.getSchema(tenantId1).fieldCount(), 2);
        assertEquals(registry.getSchema(tenantId1).getFieldDefinition(0).name, "a");
        assertEquals(registry.getSchema(tenantId1).getFieldDefinition(1).name, "b");

        assertEquals(registry.getSchema(tenantId2).getName(), "test2");
        assertEquals(registry.getSchema(tenantId2).getVersion(), 2L);
        assertEquals(registry.getSchema(tenantId2).fieldCount(), 2);
        assertEquals(registry.getSchema(tenantId2).getFieldDefinition(0).name, "c");
        assertEquals(registry.getSchema(tenantId2).getFieldDefinition(1).name, "d");
    }

    @Test
    public void testSchemaVersions() throws Exception {
        MiruTenantId tenantId1 = new MiruTenantId("tenant1".getBytes());
        MiruSchema schema1 = new MiruSchema.Builder("test1", 1)
            .setFieldDefinitions(new MiruFieldDefinition[] {
                new MiruFieldDefinition(0, "a", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE),
                new MiruFieldDefinition(1, "b", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE)
            })
            .build();
        MiruSchema schema2 = new MiruSchema.Builder("test1", 2)
            .setFieldDefinitions(new MiruFieldDefinition[] {
                new MiruFieldDefinition(0, "c", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE),
                new MiruFieldDefinition(1, "d", MiruFieldDefinition.Type.singleTerm, MiruFieldDefinition.Prefix.NONE)
            })
            .build();

        InMemoryRowColumnValueStore<MiruVoidByte, MiruTenantId, MiruSchemaColumnKey, MiruSchema> schemaRegistry = new InMemoryRowColumnValueStore<>();

        registry.registerSchema(tenantId1, schema1);

        assertEquals(registry.getSchema(tenantId1).getName(), "test1");
        assertEquals(registry.getSchema(tenantId1).getVersion(), 1L);
        assertEquals(registry.getSchema(tenantId1).fieldCount(), 2);
        assertEquals(registry.getSchema(tenantId1).getFieldDefinition(0).name, "a");
        assertEquals(registry.getSchema(tenantId1).getFieldDefinition(1).name, "b");

        registry.registerSchema(tenantId1, schema2);

        assertEquals(registry.getSchema(tenantId1).getName(), "test1");
        assertEquals(registry.getSchema(tenantId1).getVersion(), 2L);
        assertEquals(registry.getSchema(tenantId1).fieldCount(), 2);
        assertEquals(registry.getSchema(tenantId1).getFieldDefinition(0).name, "c");
        assertEquals(registry.getSchema(tenantId1).getFieldDefinition(1).name, "d");
    }

    private MiruHost[] addHosts(int numHosts) throws Exception {
        MiruHost[] hosts = new MiruHost[numHosts];
        for (int i = 0; i < numHosts; i++) {
            hosts[i] = new MiruHost("localhost", 49_600 + i);
            registry.sendHeartbeatForHost(hosts[i]);
        }
        return hosts;
    }
}
