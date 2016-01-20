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

package org.elasticsearch.cluster.routing;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Holds additional information as to why the shard is in unassigned state.
 */
public class UnassignedInfo implements ToXContent, Writeable<UnassignedInfo> {

    public static final FormatDateTimeFormatter DATE_TIME_FORMATTER = Joda.forPattern("dateOptionalTime");

    public static final String INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING = "index.unassigned.node_left.delayed_timeout";
    private static final TimeValue DEFAULT_DELAYED_NODE_LEFT_TIMEOUT = TimeValue.timeValueMinutes(1);

    /**
     * Reason why the shard is in unassigned state.
     * <p/>
     * Note, ordering of the enum is important, make sure to add new values
     * at the end and handle version serialization properly.
     */
    public enum Reason {
        /**
         * Unassigned as a result of an API creation of an index.
         */
        INDEX_CREATED,
        /**
         * Unassigned as a result of a full cluster recovery.
         */
        CLUSTER_RECOVERED,
        /**
         * Unassigned as a result of opening a closed index.
         */
        INDEX_REOPENED,
        /**
         * Unassigned as a result of importing a dangling index.
         */
        DANGLING_INDEX_IMPORTED,
        /**
         * Unassigned as a result of restoring into a new index.
         */
        NEW_INDEX_RESTORED,
        /**
         * Unassigned as a result of restoring into a closed index.
         */
        EXISTING_INDEX_RESTORED,
        /**
         * Unassigned as a result of explicit addition of a replica.
         */
        REPLICA_ADDED,
        /**
         * Unassigned as a result of a failed allocation of the shard.
         */
        ALLOCATION_FAILED,
        /**
         * Unassigned as a result of the node hosting it leaving the cluster.
         */
        NODE_LEFT,
        /**
         * Unassigned as a result of explicit cancel reroute command.
         */
        REROUTE_CANCELLED,
        /**
         * When a shard moves from started back to initializing, for example, during shadow replica
         */
        REINITIALIZED,
        /**
         * A better replica location is identified and causes the existing replica allocation to be cancelled.
         */
        REALLOCATED_REPLICA;
    }

    private final Reason reason;
    private final long timestamp;
    private final String message;
    private final Throwable failure;

    public UnassignedInfo(Reason reason, String message) {
        this(reason, System.currentTimeMillis(), message, null);
    }

    public UnassignedInfo(Reason reason, @Nullable String message, @Nullable Throwable failure) {
        this(reason, System.currentTimeMillis(), message, failure);
    }

    private UnassignedInfo(Reason reason, long timestamp, String message, Throwable failure) {
        this.reason = reason;
        this.timestamp = timestamp;
        this.message = message;
        this.failure = failure;
        assert !(message == null && failure != null) : "provide a message if a failure exception is provided";
    }

    UnassignedInfo(StreamInput in) throws IOException {
        this.reason = Reason.values()[(int) in.readByte()];
        this.timestamp = in.readLong();
        this.message = in.readOptionalString();
        this.failure = in.readThrowable();
    }

    public void writeTo(StreamOutput out) throws IOException {
        out.writeByte((byte) reason.ordinal());
        out.writeLong(timestamp);
        out.writeOptionalString(message);
        out.writeThrowable(failure);
    }

    public UnassignedInfo readFrom(StreamInput in) throws IOException {
        return new UnassignedInfo(in);
    }

    /**
     * The reason why the shard is unassigned.
     */
    public Reason getReason() {
        return this.reason;
    }

    /**
     * The timestamp in milliseconds since epoch. Note, we use timestamp here since
     * we want to make sure its preserved across node serializations. Extra care need
     * to be made if its used to calculate diff (handle negative values) in case of
     * time drift.
     */
    public long getTimestampInMillis() {
        return this.timestamp;
    }

    /**
     * Returns optional details explaining the reasons.
     */
    @Nullable
    public String getMessage() {
        return this.message;
    }

    /**
     * Returns additional failure exception details if exists.
     */
    @Nullable
    public Throwable getFailure() {
        return failure;
    }

    /**
     * Builds a string representation of the message and the failure if exists.
     */
    @Nullable
    public String getDetails() {
        if (message == null) {
            return null;
        }
        return message + (failure == null ? "" : ", failure " + ExceptionsHelper.detailedMessage(failure));
    }

    /**
     * The allocation delay value associated with the index (defaulting to node settings if not set).
     */
    public long getAllocationDelayTimeoutSetting(Settings settings, Settings indexSettings) {
        if (reason != Reason.NODE_LEFT) {
            return 0;
        }
        TimeValue delayTimeout = indexSettings.getAsTime(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, settings.getAsTime(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, DEFAULT_DELAYED_NODE_LEFT_TIMEOUT));
        return Math.max(0l, delayTimeout.millis());
    }

    /**
     * The time in millisecond until this unassigned shard can be reassigned.
     */
    public long getDelayAllocationExpirationIn(long unassignedShardsAllocatedTimestamp, Settings settings, Settings indexSettings) {
        long delayTimeout = getAllocationDelayTimeoutSetting(settings, indexSettings);
        if (delayTimeout == 0) {
            return 0;
        }
        long delta = unassignedShardsAllocatedTimestamp - timestamp;
        // account for time drift, treat it as no timeout
        if (delta < 0) {
            return 0;
        }
        return delayTimeout - delta;
    }


    /**
     * Returns the number of shards that are unassigned and currently being delayed.
     */
    public static int getNumberOfDelayedUnassigned(long unassignedShardsAllocatedTimestamp, Settings settings, ClusterState state) {
        int count = 0;
        for (ShardRouting shard : state.routingTable().shardsWithState(ShardRoutingState.UNASSIGNED)) {
            if (shard.primary() == false) {
                IndexMetaData indexMetaData = state.metaData().index(shard.getIndex());
                long delay = shard.unassignedInfo().getDelayAllocationExpirationIn(unassignedShardsAllocatedTimestamp, settings, indexMetaData.getSettings());
                if (delay > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Finds the smallest delay expiration setting of an unassigned shard. Returns 0 if there are none.
     */
    public static long findSmallestDelayedAllocationSetting(Settings settings, ClusterState state) {
        long nextDelaySetting = Long.MAX_VALUE;
        for (ShardRouting shard : state.routingTable().shardsWithState(ShardRoutingState.UNASSIGNED)) {
            if (shard.primary() == false) {
                IndexMetaData indexMetaData = state.metaData().index(shard.getIndex());
                long delayTimeoutSetting = shard.unassignedInfo().getAllocationDelayTimeoutSetting(settings, indexMetaData.getSettings());
                if (delayTimeoutSetting > 0 && delayTimeoutSetting < nextDelaySetting) {
                    nextDelaySetting = delayTimeoutSetting;
                }
            }
        }
        return nextDelaySetting == Long.MAX_VALUE ? 0l : nextDelaySetting;
    }


    /**
     * Finds the next (closest) delay expiration of an unassigned shard. Returns 0 if there are none.
     */
    public static long findNextDelayedAllocationIn(long unassignedShardsAllocatedTimestamp, Settings settings, ClusterState state) {
        long nextDelay = Long.MAX_VALUE;
        for (ShardRouting shard : state.routingTable().shardsWithState(ShardRoutingState.UNASSIGNED)) {
            if (shard.primary() == false) {
                IndexMetaData indexMetaData = state.metaData().index(shard.getIndex());
                long nextShardDelay = shard.unassignedInfo().getDelayAllocationExpirationIn(unassignedShardsAllocatedTimestamp, settings, indexMetaData.getSettings());
                if (nextShardDelay > 0 && nextShardDelay < nextDelay) {
                    nextDelay = nextShardDelay;
                }
            }
        }
        return nextDelay == Long.MAX_VALUE ? 0l : nextDelay;
    }

    public String shortSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[reason=").append(reason).append("]");
        sb.append(", at[").append(DATE_TIME_FORMATTER.printer().print(timestamp)).append("]");
        String details = getDetails();
        if (details != null) {
            sb.append(", details[").append(details).append("]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "unassigned_info[" + shortSummary() + "]";
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject("unassigned_info");
        builder.field("reason", reason);
        builder.field("at", DATE_TIME_FORMATTER.printer().print(timestamp));
        String details = getDetails();
        if (details != null) {
            builder.field("details", details);
        }
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UnassignedInfo that = (UnassignedInfo) o;

        if (timestamp != that.timestamp) return false;
        if (reason != that.reason) return false;
        if (message != null ? !message.equals(that.message) : that.message != null) return false;
        return !(failure != null ? !failure.equals(that.failure) : that.failure != null);

    }

    @Override
    public int hashCode() {
        int result = reason != null ? reason.hashCode() : 0;
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        result = 31 * result + (message != null ? message.hashCode() : 0);
        result = 31 * result + (failure != null ? failure.hashCode() : 0);
        return result;
    }
}
