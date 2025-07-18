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
package io.trino.operator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import io.trino.array.LongBigArray;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.Type;
import io.trino.sql.planner.plan.PlanNodeId;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static io.trino.operator.GroupByHash.createGroupByHash;
import static io.trino.spi.type.BigintType.BIGINT;
import static java.util.Objects.requireNonNull;

public class RowNumberOperator
        implements Operator
{
    public static class RowNumberOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Optional<Integer> maxRowsPerPartition;
        private final List<Type> sourceTypes;
        private final List<Integer> outputChannels;
        private final List<Integer> partitionChannels;
        private final List<Type> partitionTypes;
        private final int expectedPositions;
        private boolean closed;
        private final FlatHashStrategyCompiler hashStrategyCompiler;

        public RowNumberOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                List<? extends Type> sourceTypes,
                List<Integer> outputChannels,
                List<Integer> partitionChannels,
                List<? extends Type> partitionTypes,
                Optional<Integer> maxRowsPerPartition,
                int expectedPositions,
                FlatHashStrategyCompiler hashStrategyCompiler)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.sourceTypes = ImmutableList.copyOf(sourceTypes);
            this.outputChannels = ImmutableList.copyOf(requireNonNull(outputChannels, "outputChannels is null"));
            this.partitionChannels = ImmutableList.copyOf(requireNonNull(partitionChannels, "partitionChannels is null"));
            this.partitionTypes = ImmutableList.copyOf(requireNonNull(partitionTypes, "partitionTypes is null"));
            this.maxRowsPerPartition = requireNonNull(maxRowsPerPartition, "maxRowsPerPartition is null");

            checkArgument(expectedPositions > 0, "expectedPositions < 0");
            this.expectedPositions = expectedPositions;
            this.hashStrategyCompiler = requireNonNull(hashStrategyCompiler, "hashStrategyCompiler is null");
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");

            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, RowNumberOperator.class.getSimpleName());
            return new RowNumberOperator(
                    operatorContext,
                    sourceTypes,
                    outputChannels,
                    partitionChannels,
                    partitionTypes,
                    maxRowsPerPartition,
                    expectedPositions,
                    hashStrategyCompiler);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new RowNumberOperatorFactory(
                    operatorId,
                    planNodeId,
                    sourceTypes,
                    outputChannels,
                    partitionChannels,
                    partitionTypes,
                    maxRowsPerPartition,
                    expectedPositions,
                    hashStrategyCompiler);
        }
    }

    private final OperatorContext operatorContext;
    private final LocalMemoryContext localUserMemoryContext;
    private boolean finishing;

    private final int[] outputChannels;
    private final List<Type> types;

    private int[] partitionIds;
    private int[] groupByChannels;
    private final Optional<GroupByHash> groupByHash;

    private Page inputPage;
    private final LongBigArray partitionRowCount;

    private final Optional<Integer> maxRowsPerPartition;
    // Only present if maxRowsPerPartition is present
    private final Optional<PageBuilder> selectedRowPageBuilder;

    // for yield when memory is not available
    private Work<int[]> unfinishedWork;

    public RowNumberOperator(
            OperatorContext operatorContext,
            List<Type> sourceTypes,
            List<Integer> outputChannels,
            List<Integer> partitionChannels,
            List<Type> partitionTypes,
            Optional<Integer> maxRowsPerPartition,
            int expectedPositions,
            FlatHashStrategyCompiler hashStrategyCompiler)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.localUserMemoryContext = operatorContext.localUserMemoryContext();
        this.outputChannels = Ints.toArray(outputChannels);
        this.types = toTypes(sourceTypes, outputChannels);

        this.maxRowsPerPartition = maxRowsPerPartition;
        if (maxRowsPerPartition.isPresent()) {
            selectedRowPageBuilder = Optional.of(new PageBuilder(types));
        }
        else {
            selectedRowPageBuilder = Optional.empty();
        }

        this.partitionRowCount = new LongBigArray(0);
        if (partitionChannels.isEmpty()) {
            this.groupByHash = Optional.empty();
        }
        else {
            this.groupByChannels = Ints.toArray(partitionChannels);
            this.groupByHash = Optional.of(createGroupByHash(
                    operatorContext.getSession(),
                    partitionTypes,
                    false,
                    expectedPositions,
                    hashStrategyCompiler,
                    this::updateMemoryReservation));
        }
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        if (isSinglePartition() && maxRowsPerPartition.isPresent()) {
            if (finishing && !hasUnfinishedInput()) {
                return true;
            }
            return partitionRowCount.get(0) == maxRowsPerPartition.get();
        }

        return finishing && !hasUnfinishedInput();
    }

    @Override
    public boolean needsInput()
    {
        if (isSinglePartition() && maxRowsPerPartition.isPresent()) {
            // Check if single partition is done
            return partitionRowCount.get(0) < maxRowsPerPartition.get() && !finishing && !hasUnfinishedInput();
        }
        return !finishing && !hasUnfinishedInput();
    }

    @Override
    public void addInput(Page page)
    {
        checkState(!finishing, "Operator is already finishing");
        requireNonNull(page, "page is null");
        checkState(!hasUnfinishedInput());
        inputPage = page;
        if (groupByHash.isPresent()) {
            unfinishedWork = groupByHash.get().getGroupIds(inputPage.getColumns(groupByChannels));
            processUnfinishedWork();
        }
        updateMemoryReservation();
    }

    @Override
    public Page getOutput()
    {
        if (unfinishedWork != null && !processUnfinishedWork()) {
            return null;
        }

        if (inputPage == null) {
            return null;
        }

        Page outputPage;
        if (maxRowsPerPartition.isPresent()) {
            outputPage = getSelectedRows();
        }
        else {
            outputPage = getRowsWithRowNumber();
        }

        inputPage = null;
        updateMemoryReservation();
        return outputPage;
    }

    private boolean hasUnfinishedInput()
    {
        return inputPage != null || unfinishedWork != null;
    }

    /**
     * Update memory usage.
     *
     * @return true if the reservation is within the limit
     */
    // TODO: update in the interface now that the new memory tracking framework is landed
    // Essentially we would love to have clean interfaces to support both pushing and pulling memory usage
    // The following implementation is a hybrid model, where the push model is going to call the pull model causing reentrancy
    private boolean updateMemoryReservation()
    {
        // Operator/driver will be blocked on memory after we call localUserMemoryContext.setBytes().
        // If memory is not available, once we return, this operator will be blocked until memory is available.
        long memorySizeInBytes = groupByHash.map(GroupByHash::getEstimatedSize).orElse(0L) + partitionRowCount.sizeOf();
        localUserMemoryContext.setBytes(memorySizeInBytes);
        // If memory is not available, inform the caller that we cannot proceed for allocation.
        return operatorContext.isWaitingForMemory().isDone();
    }

    private boolean processUnfinishedWork()
    {
        verifyNotNull(unfinishedWork);
        if (!unfinishedWork.process()) {
            return false;
        }
        partitionIds = unfinishedWork.getResult();
        partitionRowCount.ensureCapacity(groupByHash.orElseThrow().getGroupCount());
        unfinishedWork = null;
        return true;
    }

    private boolean isSinglePartition()
    {
        return groupByHash.isEmpty();
    }

    private Page getRowsWithRowNumber()
    {
        Block[] outputBlocks = new Block[inputPage.getChannelCount() + 1]; // +1 for the row number column
        for (int i = 0; i < outputChannels.length; i++) {
            outputBlocks[i] = inputPage.getBlock(outputChannels[i]);
        }

        outputBlocks[outputBlocks.length - 1] = createRowNumberBlock();

        return new Page(inputPage.getPositionCount(), outputBlocks);
    }

    private Block createRowNumberBlock()
    {
        BlockBuilder rowNumberBlock = BIGINT.createFixedSizeBlockBuilder(inputPage.getPositionCount());
        for (int currentPosition = 0; currentPosition < inputPage.getPositionCount(); currentPosition++) {
            long partitionId = getPartitionId(currentPosition);
            long nextRowCount = partitionRowCount.get(partitionId) + 1;
            BIGINT.writeLong(rowNumberBlock, nextRowCount);
            partitionRowCount.set(partitionId, nextRowCount);
        }
        return rowNumberBlock.build();
    }

    private Page getSelectedRows()
    {
        verify(selectedRowPageBuilder.isPresent());

        int rowNumberChannel = types.size() - 1;
        PageBuilder pageBuilder = selectedRowPageBuilder.get();
        verify(pageBuilder.isEmpty());
        for (int currentPosition = 0; currentPosition < inputPage.getPositionCount(); currentPosition++) {
            long partitionId = getPartitionId(currentPosition);
            long rowCount = partitionRowCount.get(partitionId);
            if (rowCount == maxRowsPerPartition.get()) {
                continue;
            }
            pageBuilder.declarePosition();
            for (int i = 0; i < outputChannels.length; i++) {
                int channel = outputChannels[i];
                Type type = types.get(i);
                type.appendTo(inputPage.getBlock(channel), currentPosition, pageBuilder.getBlockBuilder(i));
            }
            BIGINT.writeLong(pageBuilder.getBlockBuilder(rowNumberChannel), rowCount + 1);
            partitionRowCount.set(partitionId, rowCount + 1);
        }
        if (pageBuilder.isEmpty()) {
            return null;
        }

        Page page = pageBuilder.build();
        pageBuilder.reset();
        return page;
    }

    private long getPartitionId(int position)
    {
        return isSinglePartition() ? 0 : partitionIds[position];
    }

    private static List<Type> toTypes(List<? extends Type> sourceTypes, List<Integer> outputChannels)
    {
        ImmutableList.Builder<Type> types = ImmutableList.builder();
        for (int channel : outputChannels) {
            types.add(sourceTypes.get(channel));
        }
        types.add(BIGINT);
        return types.build();
    }

    @VisibleForTesting
    public int getCapacity()
    {
        return groupByHash.map(GroupByHash::getCapacity).orElse(0);
    }
}
