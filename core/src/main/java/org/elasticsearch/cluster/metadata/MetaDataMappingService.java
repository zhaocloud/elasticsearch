/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingClusterStateUpdateRequest;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProcessedClusterStateUpdateTask;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.mapper.MergeResult;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.InvalidTypeNameException;
import org.elasticsearch.percolator.PercolatorService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Maps.newHashMap;
/**
 * Service responsible for submitting mapping changes
 */
public class MetaDataMappingService extends AbstractComponent {

    private final ClusterService clusterService;
    private final IndicesService indicesService;

    // the mutex protect all the refreshOrUpdate variables!
    private final Object refreshOrUpdateMutex = new Object();
    private final List<MappingTask> refreshOrUpdateQueue = new ArrayList<>();
    private long refreshOrUpdateInsertOrder;
    private long refreshOrUpdateProcessedInsertOrder;

    @Inject
    public MetaDataMappingService(Settings settings, ClusterService clusterService, IndicesService indicesService) {
        super(settings);
        this.clusterService = clusterService;
        this.indicesService = indicesService;
    }

    static class MappingTask {
        final String index;
        final String indexUUID;

        MappingTask(String index, final String indexUUID) {
            this.index = index;
            this.indexUUID = indexUUID;
        }
    }

    static class RefreshTask extends MappingTask {
        final String[] types;

        RefreshTask(String index, final String indexUUID, String[] types) {
            super(index, indexUUID);
            this.types = types;
        }
    }

    static class UpdateTask extends MappingTask {
        final String type;
        final CompressedXContent mappingSource;
        final String nodeId; // null fr unknown
        final ActionListener<ClusterStateUpdateResponse> listener;

        UpdateTask(String index, String indexUUID, String type, CompressedXContent mappingSource, String nodeId, ActionListener<ClusterStateUpdateResponse> listener) {
            super(index, indexUUID);
            this.type = type;
            this.mappingSource = mappingSource;
            this.nodeId = nodeId;
            this.listener = listener;
        }
    }

    /**
     * Batch method to apply all the queued refresh or update operations. The idea is to try and batch as much
     * as possible so we won't create the same index all the time for example for the updates on the same mapping
     * and generate a single cluster change event out of all of those.
     */
    Tuple<ClusterState, List<MappingTask>> executeRefreshOrUpdate(final ClusterState currentState, final long insertionOrder) throws Exception {
        final List<MappingTask> allTasks = new ArrayList<>();

        synchronized (refreshOrUpdateMutex) {
            if (refreshOrUpdateQueue.isEmpty()) {
                return Tuple.tuple(currentState, allTasks);
            }

            // we already processed this task in a bulk manner in a previous cluster event, simply ignore
            // it so we will let other tasks get in and processed ones, we will handle the queued ones
            // later on in a subsequent cluster state event
            if (insertionOrder < refreshOrUpdateProcessedInsertOrder) {
                return Tuple.tuple(currentState, allTasks);
            }

            allTasks.addAll(refreshOrUpdateQueue);
            refreshOrUpdateQueue.clear();

            refreshOrUpdateProcessedInsertOrder = refreshOrUpdateInsertOrder;
        }

        if (allTasks.isEmpty()) {
            return Tuple.tuple(currentState, allTasks);
        }

        // break down to tasks per index, so we can optimize the on demand index service creation
        // to only happen for the duration of a single index processing of its respective events
        Map<String, List<MappingTask>> tasksPerIndex = Maps.newHashMap();
        for (MappingTask task : allTasks) {
            if (task.index == null) {
                logger.debug("ignoring a mapping task of type [{}] with a null index.", task);
            }
            List<MappingTask> indexTasks = tasksPerIndex.get(task.index);
            if (indexTasks == null) {
                indexTasks = new ArrayList<>();
                tasksPerIndex.put(task.index, indexTasks);
            }
            indexTasks.add(task);
        }

        boolean dirty = false;
        MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());

        for (Map.Entry<String, List<MappingTask>> entry : tasksPerIndex.entrySet()) {
            String index = entry.getKey();
            IndexMetaData indexMetaData = mdBuilder.get(index);
            if (indexMetaData == null) {
                // index got deleted on us, ignore...
                logger.debug("[{}] ignoring tasks - index meta data doesn't exist", index);
                continue;
            }
            // the tasks lists to iterate over, filled with the list of mapping tasks, trying to keep
            // the latest (based on order) update mapping one per node
            List<MappingTask> allIndexTasks = entry.getValue();
            List<MappingTask> tasks = new ArrayList<>();
            for (MappingTask task : allIndexTasks) {
                if (!indexMetaData.isSameUUID(task.indexUUID)) {
                    logger.debug("[{}] ignoring task [{}] - index meta data doesn't match task uuid", index, task);
                    continue;
                }
                tasks.add(task);
            }

            // construct the actual index if needed, and make sure the relevant mappings are there
            boolean removeIndex = false;
            IndexService indexService = indicesService.indexService(index);
            if (indexService == null) {
                // we need to create the index here, and add the current mapping to it, so we can merge
                indexService = indicesService.createIndex(indexMetaData.getIndex(), indexMetaData.getSettings(), currentState.nodes().localNode().id());
                removeIndex = true;
                Set<String> typesToIntroduce = Sets.newHashSet();
                for (MappingTask task : tasks) {
                    if (task instanceof UpdateTask) {
                        typesToIntroduce.add(((UpdateTask) task).type);
                    } else if (task instanceof RefreshTask) {
                        Collections.addAll(typesToIntroduce, ((RefreshTask) task).types);
                    }
                }
                for (String type : typesToIntroduce) {
                    // only add the current relevant mapping (if exists)
                    if (indexMetaData.getMappings().containsKey(type)) {
                        // don't apply the default mapping, it has been applied when the mapping was created
                        indexService.mapperService().merge(type, indexMetaData.getMappings().get(type).source(), false, true);
                    }
                }
            }

            IndexMetaData.Builder builder = IndexMetaData.builder(indexMetaData);
            try {
                boolean indexDirty = processIndexMappingTasks(tasks, indexService, builder);
                if (indexDirty) {
                    mdBuilder.put(builder);
                    dirty = true;
                }
            } finally {
                if (removeIndex) {
                    indicesService.removeIndex(index, "created for mapping processing");
                }
            }
        }

        if (!dirty) {
            return Tuple.tuple(currentState, allTasks);
        }
        return Tuple.tuple(ClusterState.builder(currentState).metaData(mdBuilder).build(), allTasks);
    }

    private boolean processIndexMappingTasks(List<MappingTask> tasks, IndexService indexService, IndexMetaData.Builder builder) {
        boolean dirty = false;
        String index = indexService.index().name();
        // keep track of what we already refreshed, no need to refresh it again...
        Set<String> processedRefreshes = Sets.newHashSet();
        for (MappingTask task : tasks) {
            if (task instanceof RefreshTask) {
                RefreshTask refreshTask = (RefreshTask) task;
                try {
                    List<String> updatedTypes = new ArrayList<>();
                    for (String type : refreshTask.types) {
                        if (processedRefreshes.contains(type)) {
                            continue;
                        }
                        DocumentMapper mapper = indexService.mapperService().documentMapper(type);
                        if (mapper == null) {
                            continue;
                        }
                        if (!mapper.mappingSource().equals(builder.mapping(type).source())) {
                            updatedTypes.add(type);
                            builder.putMapping(new MappingMetaData(mapper));
                        }
                        processedRefreshes.add(type);
                    }

                    if (updatedTypes.isEmpty()) {
                        continue;
                    }

                    logger.warn("[{}] re-syncing mappings with cluster state for types [{}]", index, updatedTypes);
                    dirty = true;
                } catch (Throwable t) {
                    logger.warn("[{}] failed to refresh-mapping in cluster state, types [{}]", index, refreshTask.types);
                }
            } else if (task instanceof UpdateTask) {
                UpdateTask updateTask = (UpdateTask) task;
                try {
                    String type = updateTask.type;
                    CompressedXContent mappingSource = updateTask.mappingSource;

                    MappingMetaData mappingMetaData = builder.mapping(type);
                    if (mappingMetaData != null && mappingMetaData.source().equals(mappingSource)) {
                        logger.debug("[{}] update_mapping [{}] ignoring mapping update task as its source is equal to ours", index, updateTask.type);
                        continue;
                    }

                    DocumentMapper updatedMapper = indexService.mapperService().merge(type, mappingSource, false, true);
                    processedRefreshes.add(type);

                    // if we end up with the same mapping as the original once, ignore
                    if (mappingMetaData != null && mappingMetaData.source().equals(updatedMapper.mappingSource())) {
                        logger.debug("[{}] update_mapping [{}] ignoring mapping update task as it results in the same source as what we have", index, updateTask.type);
                        continue;
                    }

                    // build the updated mapping source
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}] update_mapping [{}] (dynamic) with source [{}]", index, type, updatedMapper.mappingSource());
                    } else if (logger.isInfoEnabled()) {
                        logger.info("[{}] update_mapping [{}] (dynamic)", index, type);
                    }

                    builder.putMapping(new MappingMetaData(updatedMapper));
                    dirty = true;
                } catch (Throwable t) {
                    logger.warn("[{}] failed to update-mapping in cluster state, type [{}]", index, updateTask.type);
                }
            } else {
                logger.warn("illegal state, got wrong mapping task type [{}]", task);
            }
        }
        return dirty;
    }

    /**
     * Refreshes mappings if they are not the same between original and parsed version
     */
    public void refreshMapping(final String index, final String indexUUID, final String... types) {
        final long insertOrder;
        synchronized (refreshOrUpdateMutex) {
            insertOrder = ++refreshOrUpdateInsertOrder;
            refreshOrUpdateQueue.add(new RefreshTask(index, indexUUID, types));
        }
        clusterService.submitStateUpdateTask("refresh-mapping [" + index + "][" + Arrays.toString(types) + "]", Priority.HIGH, new ProcessedClusterStateUpdateTask() {
            private volatile List<MappingTask> allTasks;

            @Override
            public void onFailure(String source, Throwable t) {
                logger.warn("failure during [{}]", t, source);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                Tuple<ClusterState, List<MappingTask>> tuple = executeRefreshOrUpdate(currentState, insertOrder);
                this.allTasks = tuple.v2();
                return tuple.v1();
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (allTasks == null) {
                    return;
                }
                for (Object task : allTasks) {
                    if (task instanceof UpdateTask) {
                        UpdateTask uTask = (UpdateTask) task;
                        ClusterStateUpdateResponse response = new ClusterStateUpdateResponse(true);
                        uTask.listener.onResponse(response);
                    }
                }
            }
        });
    }

    public void putMapping(final PutMappingClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {

        clusterService.submitStateUpdateTask("put-mapping [" + request.type() + "]", Priority.HIGH, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {

            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(final ClusterState currentState) throws Exception {
                List<String> indicesToClose = new ArrayList<>();
                try {
                    for (String index : request.indices()) {
                        if (!currentState.metaData().hasIndex(index)) {
                            throw new IndexNotFoundException(index);
                        }
                    }

                    // pre create indices here and add mappings to them so we can merge the mappings here if needed
                    for (String index : request.indices()) {
                        if (indicesService.hasIndex(index)) {
                            continue;
                        }
                        final IndexMetaData indexMetaData = currentState.metaData().index(index);
                        IndexService indexService = indicesService.createIndex(indexMetaData.getIndex(), indexMetaData.getSettings(), clusterService.localNode().id());
                        indicesToClose.add(indexMetaData.getIndex());
                        // make sure to add custom default mapping if exists
                        if (indexMetaData.getMappings().containsKey(MapperService.DEFAULT_MAPPING)) {
                            indexService.mapperService().merge(MapperService.DEFAULT_MAPPING, indexMetaData.getMappings().get(MapperService.DEFAULT_MAPPING).source(), false, request.updateAllTypes());
                        }
                        // only add the current relevant mapping (if exists)
                        if (indexMetaData.getMappings().containsKey(request.type())) {
                            indexService.mapperService().merge(request.type(), indexMetaData.getMappings().get(request.type()).source(), false, request.updateAllTypes());
                        }
                    }

                    Map<String, DocumentMapper> newMappers = newHashMap();
                    Map<String, DocumentMapper> existingMappers = newHashMap();
                    for (String index : request.indices()) {
                        IndexService indexService = indicesService.indexServiceSafe(index);
                        // try and parse it (no need to add it here) so we can bail early in case of parsing exception
                        DocumentMapper newMapper;
                        DocumentMapper existingMapper = indexService.mapperService().documentMapper(request.type());
                        if (MapperService.DEFAULT_MAPPING.equals(request.type())) {
                            // _default_ types do not go through merging, but we do test the new settings. Also don't apply the old default
                            newMapper = indexService.mapperService().parse(request.type(), new CompressedXContent(request.source()), false);
                        } else {
                            newMapper = indexService.mapperService().parse(request.type(), new CompressedXContent(request.source()), existingMapper == null);
                            if (existingMapper != null) {
                                // first, simulate
                                MergeResult mergeResult = existingMapper.merge(newMapper.mapping(), true, request.updateAllTypes());
                                // if we have conflicts, throw an exception
                                if (mergeResult.hasConflicts()) {
                                    throw new MergeMappingException(mergeResult.buildConflicts());
                                }
                            } else {
                                // TODO: can we find a better place for this validation?
                                // The reason this validation is here is that the mapper service doesn't learn about
                                // new types all at once , which can create a false error.

                                // For example in MapperService we can't distinguish between a create index api call
                                // and a put mapping api call, so we don't which type did exist before.
                                // Also the order of the mappings may be backwards.
                                if (Version.indexCreated(indexService.indexSettings()).onOrAfter(Version.V_2_0_0_beta1) && newMapper.parentFieldMapper().active()) {
                                    IndexMetaData indexMetaData = currentState.metaData().index(index);
                                    for (ObjectCursor<MappingMetaData> mapping : indexMetaData.getMappings().values()) {
                                        if (newMapper.parentFieldMapper().type().equals(mapping.value.type())) {
                                            throw new IllegalArgumentException("can't add a _parent field that points to an already existing type");
                                        }
                                    }
                                }
                            }
                        }


                        newMappers.put(index, newMapper);
                        if (existingMapper != null) {
                            existingMappers.put(index, existingMapper);
                        }
                    }

                    String mappingType = request.type();
                    if (mappingType == null) {
                        mappingType = newMappers.values().iterator().next().type();
                    } else if (!mappingType.equals(newMappers.values().iterator().next().type())) {
                        throw new InvalidTypeNameException("Type name provided does not match type name within mapping definition");
                    }
                    if (!MapperService.DEFAULT_MAPPING.equals(mappingType) && !PercolatorService.TYPE_NAME.equals(mappingType) && mappingType.charAt(0) == '_') {
                        throw new InvalidTypeNameException("Document mapping type name can't start with '_'");
                    }

                    final Map<String, MappingMetaData> mappings = newHashMap();
                    for (Map.Entry<String, DocumentMapper> entry : newMappers.entrySet()) {
                        String index = entry.getKey();
                        // do the actual merge here on the master, and update the mapping source
                        DocumentMapper newMapper = entry.getValue();
                        IndexService indexService = indicesService.indexService(index);
                        if (indexService == null) {
                            continue;
                        }

                        CompressedXContent existingSource = null;
                        if (existingMappers.containsKey(entry.getKey())) {
                            existingSource = existingMappers.get(entry.getKey()).mappingSource();
                        }
                        DocumentMapper mergedMapper = indexService.mapperService().merge(newMapper.type(), newMapper.mappingSource(), false, request.updateAllTypes());
                        CompressedXContent updatedSource = mergedMapper.mappingSource();

                        if (existingSource != null) {
                            if (existingSource.equals(updatedSource)) {
                                // same source, no changes, ignore it
                            } else {
                                // use the merged mapping source
                                mappings.put(index, new MappingMetaData(mergedMapper));
                                if (logger.isDebugEnabled()) {
                                    logger.debug("[{}] update_mapping [{}] with source [{}]", index, mergedMapper.type(), updatedSource);
                                } else if (logger.isInfoEnabled()) {
                                    logger.info("[{}] update_mapping [{}]", index, mergedMapper.type());
                                }
                            }
                        } else {
                            mappings.put(index, new MappingMetaData(mergedMapper));
                            if (logger.isDebugEnabled()) {
                                logger.debug("[{}] create_mapping [{}] with source [{}]", index, newMapper.type(), updatedSource);
                            } else if (logger.isInfoEnabled()) {
                                logger.info("[{}] create_mapping [{}]", index, newMapper.type());
                            }
                        }
                    }

                    if (mappings.isEmpty()) {
                        // no changes, return
                        return currentState;
                    }

                    MetaData.Builder builder = MetaData.builder(currentState.metaData());
                    for (String indexName : request.indices()) {
                        IndexMetaData indexMetaData = currentState.metaData().index(indexName);
                        if (indexMetaData == null) {
                            throw new IndexNotFoundException(indexName);
                        }
                        MappingMetaData mappingMd = mappings.get(indexName);
                        if (mappingMd != null) {
                            builder.put(IndexMetaData.builder(indexMetaData).putMapping(mappingMd));
                        }
                    }

                    return ClusterState.builder(currentState).metaData(builder).build();
                } finally {
                    for (String index : indicesToClose) {
                        indicesService.removeIndex(index, "created for mapping processing");
                    }
                }
            }
        });
    }
}
