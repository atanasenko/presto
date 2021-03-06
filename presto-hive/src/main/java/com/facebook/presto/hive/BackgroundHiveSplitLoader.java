/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.metastore.Column;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.hive.util.HiveFileIterator;
import com.facebook.presto.hive.util.ResumableTask;
import com.facebook.presto.hive.util.ResumableTasks;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.StandardErrorCode;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.common.io.CharStreams;
import io.airlift.units.DataSize;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.SymlinkTextInputFormat;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.facebook.presto.hadoop.HadoopFileStatus.isDirectory;
import static com.facebook.presto.hive.HiveBucketing.HiveBucket;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_BAD_DATA;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_BUCKET_FILES;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_PARTITION_VALUE;
import static com.facebook.presto.hive.HiveSessionProperties.getMaxInitialSplitSize;
import static com.facebook.presto.hive.HiveSessionProperties.getMaxSplitSize;
import static com.facebook.presto.hive.HiveUtil.checkCondition;
import static com.facebook.presto.hive.HiveUtil.getInputFormat;
import static com.facebook.presto.hive.HiveUtil.isSplittable;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getHiveSchema;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static org.apache.hadoop.hive.common.FileUtils.HIDDEN_FILES_PATH_FILTER;

public class BackgroundHiveSplitLoader
        implements HiveSplitLoader
{
    private static final String CORRUPT_BUCKETING = "Hive table is corrupt. It is declared as being bucketed, but the files do not match the bucketing declaration.";

    public static final CompletableFuture<?> COMPLETED_FUTURE = CompletableFuture.completedFuture(null);

    private final String connectorId;
    private final Table table;
    private final Optional<HiveBucketHandle> bucketHandle;
    private final List<HiveBucket> buckets;
    private final HdfsEnvironment hdfsEnvironment;
    private final NamenodeStats namenodeStats;
    private final DirectoryLister directoryLister;
    private final DataSize maxSplitSize;
    private final int maxPartitionBatchSize;
    private final DataSize maxInitialSplitSize;
    private final boolean recursiveDirWalkerEnabled;
    private final Executor executor;
    private final ConnectorSession session;
    private final ConcurrentLazyQueue<HivePartitionMetadata> partitions;
    private final Deque<HiveFileIterator> fileIterators = new ConcurrentLinkedDeque<>();
    private final AtomicInteger remainingInitialSplits;

    // Purpose of this lock:
    // * When write lock is acquired, except the holder, no one can do any of the following:
    // ** poll from partitions
    // ** poll from or push to fileIterators
    // ** push to hiveSplitSource
    // * When any of the above three operations is carried out, either a read lock or a write lock must be held.
    // * When a series of operations involving two or more of the above three operations are carried out, the lock
    //   must be continuously held throughout the series of operations.
    // Implications:
    // * if you hold a read lock but not a write lock, you can do any of the above three operations, but you may
    //   see a series of operations involving two or more of the operations carried out half way.
    private final ReentrantReadWriteLock taskExecutionLock = new ReentrantReadWriteLock();

    private HiveSplitSource hiveSplitSource;
    private volatile boolean stopped;

    public BackgroundHiveSplitLoader(
            String connectorId,
            Table table,
            Iterable<HivePartitionMetadata> partitions,
            Optional<HiveBucketHandle> bucketHandle,
            List<HiveBucket> buckets,
            ConnectorSession session,
            HdfsEnvironment hdfsEnvironment,
            NamenodeStats namenodeStats,
            DirectoryLister directoryLister,
            Executor executor,
            int maxPartitionBatchSize,
            int maxInitialSplits,
            boolean recursiveDirWalkerEnabled)
    {
        this.connectorId = connectorId;
        this.table = table;
        this.bucketHandle = bucketHandle;
        this.buckets = buckets;
        this.maxSplitSize = getMaxSplitSize(session);
        this.maxPartitionBatchSize = maxPartitionBatchSize;
        this.session = session;
        this.hdfsEnvironment = hdfsEnvironment;
        this.namenodeStats = namenodeStats;
        this.directoryLister = directoryLister;
        this.maxInitialSplitSize = getMaxInitialSplitSize(session);
        this.remainingInitialSplits = new AtomicInteger(maxInitialSplits);
        this.recursiveDirWalkerEnabled = recursiveDirWalkerEnabled;
        this.executor = executor;
        this.partitions = new ConcurrentLazyQueue<>(partitions);
    }

    @Override
    public void start(HiveSplitSource splitSource)
    {
        this.hiveSplitSource = splitSource;
        for (int i = 0; i < maxPartitionBatchSize; i++) {
            ResumableTasks.submit(executor, new HiveSplitLoaderTask());
        }
    }

    @Override
    public void stop()
    {
        stopped = true;
    }

    private class HiveSplitLoaderTask
            implements ResumableTask
    {
        @Override
        public TaskStatus process()
        {
            while (true) {
                if (stopped) {
                    return TaskStatus.finished();
                }
                try {
                    CompletableFuture<?> future;
                    taskExecutionLock.readLock().lock();
                    try {
                        future = loadSplits();
                    }
                    finally {
                        taskExecutionLock.readLock().unlock();
                    }
                    invokeFinishedIfNecessary();
                    if (!future.isDone()) {
                        return TaskStatus.continueOn(future);
                    }
                }
                catch (Exception e) {
                    hiveSplitSource.fail(e);
                }
            }
        }
    }

    private void invokeFinishedIfNecessary()
    {
        if (partitions.isEmpty() && fileIterators.isEmpty()) {
            taskExecutionLock.writeLock().lock();
            try {
                // the write lock guarantees that no one is operating on the partitions, fileIterators, or hiveSplitSource, or half way through doing so.
                if (partitions.isEmpty() && fileIterators.isEmpty()) {
                    // It is legal to call `finished` multiple times or after `stop` was called.
                    // Nothing bad will happen if `finished` implementation calls methods that will try to obtain a read lock because the lock is re-entrant.
                    hiveSplitSource.finished();
                }
            }
            finally {
                taskExecutionLock.writeLock().unlock();
            }
        }
    }

    private CompletableFuture<?> loadSplits()
            throws IOException
    {
        HiveFileIterator files = fileIterators.poll();
        if (files == null) {
            HivePartitionMetadata partition = partitions.poll();
            if (partition == null) {
                return COMPLETED_FUTURE;
            }
            loadPartition(partition);
            return COMPLETED_FUTURE;
        }

        while (files.hasNext() && !stopped) {
            LocatedFileStatus file = files.next();
            if (isDirectory(file)) {
                if (recursiveDirWalkerEnabled) {
                    HiveFileIterator fileIterator = new HiveFileIterator(
                            file.getPath(),
                            files.getFileSystem(),
                            files.getDirectoryLister(),
                            files.getNamenodeStats(),
                            files.getPartitionName(),
                            files.getInputFormat(),
                            files.getSchema(),
                            files.getPartitionKeys(),
                            files.getEffectivePredicate(),
                            files.getColumnCoercions());
                    fileIterators.add(fileIterator);
                }
            }
            else {
                boolean splittable = isSplittable(files.getInputFormat(), hdfsEnvironment.getFileSystem(session.getUser(), file.getPath()), file.getPath());

                CompletableFuture<?> future = hiveSplitSource.addToQueue(createHiveSplitIterator(
                        files.getPartitionName(),
                        file.getPath().toString(),
                        file.getBlockLocations(),
                        0,
                        file.getLen(),
                        files.getSchema(),
                        files.getPartitionKeys(),
                        splittable,
                        session,
                        OptionalInt.empty(),
                        files.getEffectivePredicate(),
                        files.getColumnCoercions()));
                if (!future.isDone()) {
                    fileIterators.addFirst(files);
                    return future;
                }
            }
        }

        // No need to put the iterator back, since it's either empty or we've stopped
        return COMPLETED_FUTURE;
    }

    private void loadPartition(HivePartitionMetadata partition)
            throws IOException
    {
        String partitionName = partition.getHivePartition().getPartitionId();
        Properties schema = getPartitionSchema(table, partition.getPartition());
        List<HivePartitionKey> partitionKeys = getPartitionKeys(table, partition.getPartition());
        TupleDomain<HiveColumnHandle> effectivePredicate = partition.getHivePartition().getEffectivePredicate();

        Path path = new Path(getPartitionLocation(table, partition.getPartition()));
        Configuration configuration = hdfsEnvironment.getConfiguration(path);
        InputFormat<?, ?> inputFormat = getInputFormat(configuration, schema, false);
        FileSystem fs = hdfsEnvironment.getFileSystem(session.getUser(), path);

        if (inputFormat instanceof SymlinkTextInputFormat) {
            if (bucketHandle.isPresent()) {
                throw new PrestoException(StandardErrorCode.NOT_SUPPORTED, "Bucketed table in SymlinkTextInputFormat is not yet supported");
            }

            // TODO: This should use an iterator like the HiveFileIterator
            for (Path targetPath : getTargetPathsFromSymlink(fs, path)) {
                // The input should be in TextInputFormat.
                TextInputFormat targetInputFormat = new TextInputFormat();
                // get the configuration for the target path -- it may be a different hdfs instance
                Configuration targetConfiguration = hdfsEnvironment.getConfiguration(targetPath);
                JobConf targetJob = new JobConf(targetConfiguration);
                targetJob.setInputFormat(TextInputFormat.class);
                targetInputFormat.configure(targetJob);
                FileInputFormat.setInputPaths(targetJob, targetPath);
                InputSplit[] targetSplits = targetInputFormat.getSplits(targetJob, 0);

                for (InputSplit inputSplit : targetSplits) {
                    FileSplit split = (FileSplit) inputSplit;
                    FileSystem targetFilesystem = hdfsEnvironment.getFileSystem(session.getUser(), split.getPath());
                    FileStatus file = targetFilesystem.getFileStatus(split.getPath());
                    hiveSplitSource.addToQueue(createHiveSplitIterator(
                            partitionName,
                            file.getPath().toString(),
                            targetFilesystem.getFileBlockLocations(file, split.getStart(), split.getLength()),
                            split.getStart(),
                            split.getLength(),
                            schema,
                            partitionKeys,
                            false,
                            session,
                            OptionalInt.empty(),
                            effectivePredicate,
                            partition.getColumnCoercions()));
                    if (stopped) {
                        return;
                    }
                }
            }
            return;
        }

        // If only one bucket could match: load that one file
        HiveFileIterator iterator = new HiveFileIterator(path, fs, directoryLister, namenodeStats, partitionName, inputFormat, schema, partitionKeys, effectivePredicate, partition.getColumnCoercions());
        if (!buckets.isEmpty()) {
            int bucketCount = buckets.get(0).getBucketCount();
            List<LocatedFileStatus> list = listAndSortBucketFiles(iterator, bucketCount);
            List<Iterator<HiveSplit>> iteratorList = new ArrayList<>();

            for (HiveBucket bucket : buckets) {
                int bucketNumber = bucket.getBucketNumber();
                LocatedFileStatus file = list.get(bucketNumber);
                boolean splittable = isSplittable(iterator.getInputFormat(), hdfsEnvironment.getFileSystem(session.getUser(), file.getPath()), file.getPath());

                iteratorList.add(createHiveSplitIterator(
                        iterator.getPartitionName(),
                        file.getPath().toString(),
                        file.getBlockLocations(),
                        0,
                        file.getLen(),
                        iterator.getSchema(),
                        iterator.getPartitionKeys(),
                        splittable,
                        session,
                        OptionalInt.of(bucketNumber),
                        effectivePredicate,
                        partition.getColumnCoercions()));
            }

            addToHiveSplitSourceRoundRobin(iteratorList);
            return;
        }

        // If table is bucketed: list the directory, sort, tag with bucket id
        if (bucketHandle.isPresent()) {
            // HiveFileIterator skips hidden files automatically.
            int bucketCount = bucketHandle.get().getBucketCount();
            List<LocatedFileStatus> list = listAndSortBucketFiles(iterator, bucketCount);
            List<Iterator<HiveSplit>> iteratorList = new ArrayList<>();

            for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
                LocatedFileStatus file = list.get(bucketIndex);
                boolean splittable = isSplittable(iterator.getInputFormat(), hdfsEnvironment.getFileSystem(session.getUser(), file.getPath()), file.getPath());

                iteratorList.add(createHiveSplitIterator(
                        iterator.getPartitionName(),
                        file.getPath().toString(),
                        file.getBlockLocations(),
                        0,
                        file.getLen(),
                        iterator.getSchema(),
                        iterator.getPartitionKeys(),
                        splittable,
                        session,
                        OptionalInt.of(bucketIndex),
                        iterator.getEffectivePredicate(),
                        partition.getColumnCoercions()));
            }

            addToHiveSplitSourceRoundRobin(iteratorList);
            return;
        }

        fileIterators.addLast(iterator);
    }

    private void addToHiveSplitSourceRoundRobin(List<Iterator<HiveSplit>> iteratorList)
    {
        while (true) {
            boolean done = true;
            for (Iterator<HiveSplit> hiveSplitIterator : iteratorList) {
                if (hiveSplitIterator.hasNext()) {
                    hiveSplitSource.addToQueue(hiveSplitIterator.next());
                    done = false;
                }
            }
            if (done) {
                break;
            }
        }
    }

    private static List<LocatedFileStatus> listAndSortBucketFiles(HiveFileIterator hiveFileIterator, int bucketCount)
    {
        ArrayList<LocatedFileStatus> list = new ArrayList<>(bucketCount);

        while (hiveFileIterator.hasNext()) {
            LocatedFileStatus next = hiveFileIterator.next();
            if (isDirectory(next)) {
                // Fail here to be on the safe side. This seems to be the same as what Hive does
                throw new PrestoException(HIVE_INVALID_BUCKET_FILES, format("%s Found sub-directory in bucket directory for partition: %s", CORRUPT_BUCKETING, hiveFileIterator.getPartitionName()));
            }
            list.add(next);
        }

        if (list.size() != bucketCount) {
            throw new PrestoException(HIVE_INVALID_BUCKET_FILES, format("%s The number of files in the directory (%s) does not match the declared bucket count (%s) for partition: %s", CORRUPT_BUCKETING, list.size(), bucketCount, hiveFileIterator.getPartitionName()));
        }

        // Sort FileStatus objects (instead of, e.g., fileStatus.getPath().toString). This matches org.apache.hadoop.hive.ql.metadata.Table.getSortedPaths
        list.sort(null);
        return list;
    }

    private static List<Path> getTargetPathsFromSymlink(FileSystem fileSystem, Path symlinkDir)
    {
        try {
            FileStatus[] symlinks = fileSystem.listStatus(symlinkDir, HIDDEN_FILES_PATH_FILTER);
            List<Path> targets = new ArrayList<>();

            for (FileStatus symlink : symlinks) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(fileSystem.open(symlink.getPath()), StandardCharsets.UTF_8))) {
                    CharStreams.readLines(reader).stream()
                            .map(Path::new)
                            .forEach(targets::add);
                }
            }
            return targets;
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_BAD_DATA, "Error parsing symlinks from: " + symlinkDir, e);
        }
    }

    private Iterator<HiveSplit> createHiveSplitIterator(
            String partitionName,
            String path,
            BlockLocation[] blockLocations,
            long start,
            long length,
            Properties schema,
            List<HivePartitionKey> partitionKeys,
            boolean splittable,
            ConnectorSession session,
            OptionalInt bucketNumber,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            Map<Integer, HiveType> columnCoercions)
            throws IOException
    {
        boolean forceLocalScheduling = HiveSessionProperties.isForceLocalScheduling(session);

        if (splittable) {
            PeekingIterator<BlockLocation> blockLocationIterator = Iterators.peekingIterator(Arrays.stream(blockLocations).iterator());

            return new AbstractIterator<HiveSplit>() {
                private long chunkOffset = 0;

                @Override
                protected HiveSplit computeNext()
                {
                    if (!blockLocationIterator.hasNext()) {
                        return endOfData();
                    }
                    BlockLocation blockLocation = blockLocationIterator.peek();

                    List<HostAddress> addresses;
                    try {
                        addresses = toHostAddress(blockLocation.getHosts());
                    }
                    catch (IOException e) {
                        throw Throwables.propagate(e);
                    }

                    long targetChunkSize;
                    if (remainingInitialSplits.decrementAndGet() >= 0) {
                        targetChunkSize = maxInitialSplitSize.toBytes();
                    }
                    else {
                        long maxBytes = maxSplitSize.toBytes();
                        int chunks = toIntExact((long) Math.ceil((blockLocation.getLength() - chunkOffset) * 1.0 / maxBytes));
                        targetChunkSize = (long) Math.ceil((blockLocation.getLength() - chunkOffset) * 1.0 / chunks);
                    }

                    // adjust the actual chunk size to account for the overrun when chunks are slightly bigger than necessary (see above)
                    long chunkLength = Math.min(targetChunkSize, blockLocation.getLength() - chunkOffset);

                    HiveSplit result = new HiveSplit(
                            connectorId,
                            table.getDatabaseName(),
                            table.getTableName(),
                            partitionName,
                            path,
                            blockLocation.getOffset() + chunkOffset,
                            chunkLength,
                            schema,
                            partitionKeys,
                            addresses,
                            bucketNumber,
                            forceLocalScheduling && hasRealAddress(addresses),
                            effectivePredicate,
                            columnCoercions);

                    chunkOffset += chunkLength;

                    if (chunkOffset >= blockLocation.getLength()) {
                        checkState(chunkOffset == blockLocation.getLength(), "Error splitting blocks");
                        blockLocationIterator.next();
                        chunkOffset = 0;
                    }

                    return result;
                }
            };
        }
        else {
            // not splittable, use the hosts from the first block if it exists
            List<HostAddress> addresses = ImmutableList.of();
            if (blockLocations.length > 0) {
                addresses = toHostAddress(blockLocations[0].getHosts());
            }

            return Iterators.singletonIterator(new HiveSplit(
                    connectorId,
                    table.getDatabaseName(),
                    table.getTableName(),
                    partitionName,
                    path,
                    start,
                    length,
                    schema,
                    partitionKeys,
                    addresses,
                    bucketNumber,
                    forceLocalScheduling && hasRealAddress(addresses),
                    effectivePredicate,
                    columnCoercions));
        }
    }

    private static boolean hasRealAddress(List<HostAddress> addresses)
    {
        // Hadoop FileSystem returns "localhost" as a default
        return addresses.stream().anyMatch(address -> !address.getHostText().equals("localhost"));
    }

    private static List<HostAddress> toHostAddress(String[] hosts)
    {
        ImmutableList.Builder<HostAddress> builder = ImmutableList.builder();
        for (String host : hosts) {
            builder.add(HostAddress.fromString(host));
        }
        return builder.build();
    }

    private static List<HivePartitionKey> getPartitionKeys(Table table, Optional<Partition> partition)
    {
        if (!partition.isPresent()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<HivePartitionKey> partitionKeys = ImmutableList.builder();
        List<Column> keys = table.getPartitionColumns();
        List<String> values = partition.get().getValues();
        checkCondition(keys.size() == values.size(), HIVE_INVALID_METADATA, "Expected %s partition key values, but got %s", keys.size(), values.size());
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i).getName();
            HiveType hiveType = keys.get(i).getType();
            if (!hiveType.isSupportedType()) {
                throw new PrestoException(NOT_SUPPORTED, format("Unsupported Hive type %s found in partition keys of table %s.%s", hiveType, table.getDatabaseName(), table.getTableName()));
            }
            String value = values.get(i);
            checkCondition(value != null, HIVE_INVALID_PARTITION_VALUE, "partition key value cannot be null for field: %s", name);
            partitionKeys.add(new HivePartitionKey(name, hiveType, value));
        }
        return partitionKeys.build();
    }

    private static Properties getPartitionSchema(Table table, Optional<Partition> partition)
    {
        if (!partition.isPresent()) {
            return getHiveSchema(table);
        }
        return getHiveSchema(partition.get(), table);
    }

    private static String getPartitionLocation(Table table, Optional<Partition> partition)
    {
        if (!partition.isPresent()) {
            return table.getStorage().getLocation();
        }
        return partition.get().getStorage().getLocation();
    }
}
