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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.prestosql.operator.aggregation.TypedSet;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.predicate.ValueSet;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeUtils;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.PlanNodeId;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.prestosql.spi.type.TypeUtils.isFloatingPointNaN;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/**
 * This operator acts as a simple "pass-through" pipe, while saving its input pages.
 * The collected pages' value are used for creating a run-time filtering constraint (for probe-side table scan in an inner join).
 * We support only small build-side pages (which should be the case when using "broadcast" join).
 */
public class DynamicFilterSourceOperator
        implements Operator
{
    private static final int EXPECTED_BLOCK_BUILDER_SIZE = 8;

    public static class Channel
    {
        private final DynamicFilterId filterId;
        private final Type type;
        private final int index;

        public Channel(DynamicFilterId filterId, Type type, int index)
        {
            this.filterId = filterId;
            this.type = type;
            this.index = index;
        }
    }

    public static class DynamicFilterSourceOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Consumer<TupleDomain<DynamicFilterId>> dynamicPredicateConsumer;
        private final List<Channel> channels;
        private final int maxFilterPositionsCount;
        private final DataSize maxFilterSize;

        private boolean closed;

        public DynamicFilterSourceOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                Consumer<TupleDomain<DynamicFilterId>> dynamicPredicateConsumer,
                List<Channel> channels,
                int maxFilterPositionsCount,
                DataSize maxFilterSize)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.dynamicPredicateConsumer = requireNonNull(dynamicPredicateConsumer, "dynamicPredicateConsumer is null");
            this.channels = requireNonNull(channels, "channels is null");
            verify(channels.stream().map(channel -> channel.filterId).collect(toSet()).size() == channels.size(),
                    "duplicate dynamic filters are not allowed");
            verify(channels.stream().map(channel -> channel.index).collect(toSet()).size() == channels.size(),
                    "duplicate channel indices are not allowed");
            this.maxFilterPositionsCount = maxFilterPositionsCount;
            this.maxFilterSize = maxFilterSize;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            return new DynamicFilterSourceOperator(
                    driverContext.addOperatorContext(operatorId, planNodeId, DynamicFilterSourceOperator.class.getSimpleName()),
                    dynamicPredicateConsumer,
                    channels,
                    planNodeId,
                    maxFilterPositionsCount,
                    maxFilterSize);
        }

        @Override
        public void noMoreOperators()
        {
            checkState(!closed, "Factory is already closed");
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            throw new UnsupportedOperationException("duplicate() is not supported for DynamicFilterSourceOperatorFactory");
        }
    }

    private final OperatorContext context;
    private boolean finished;
    private Page current;
    private final Consumer<TupleDomain<DynamicFilterId>> dynamicPredicateConsumer;
    private final int maxFilterPositionsCount;
    private final long maxFilterSizeInBytes;

    private final List<Channel> channels;

    // May be dropped if the predicate becomes too large.
    @Nullable
    private BlockBuilder[] blockBuilders;
    @Nullable
    private TypedSet[] valueSets;

    private DynamicFilterSourceOperator(
            OperatorContext context,
            Consumer<TupleDomain<DynamicFilterId>> dynamicPredicateConsumer,
            List<Channel> channels,
            PlanNodeId planNodeId,
            int maxFilterPositionsCount,
            DataSize maxFilterSize)
    {
        this.context = requireNonNull(context, "context is null");
        this.maxFilterPositionsCount = maxFilterPositionsCount;
        this.maxFilterSizeInBytes = maxFilterSize.toBytes();

        this.dynamicPredicateConsumer = requireNonNull(dynamicPredicateConsumer, "dynamicPredicateConsumer is null");
        this.channels = requireNonNull(channels, "channels is null");

        this.blockBuilders = new BlockBuilder[channels.size()];
        this.valueSets = new TypedSet[channels.size()];
        for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
            Type type = channels.get(channelIndex).type;
            this.blockBuilders[channelIndex] = type.createBlockBuilder(null, EXPECTED_BLOCK_BUILDER_SIZE);
            this.valueSets[channelIndex] = new TypedSet(
                    type,
                    Optional.empty(),
                    blockBuilders[channelIndex],
                    EXPECTED_BLOCK_BUILDER_SIZE,
                    String.format("DynamicFilterSourceOperator_%s_%d", planNodeId, channelIndex),
                    Optional.empty() /* maxBlockMemory */);
        }
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return context;
    }

    @Override
    public boolean needsInput()
    {
        return current == null && !finished;
    }

    @Override
    public void addInput(Page page)
    {
        verify(!finished, "DynamicFilterSourceOperator: addInput() may not be called after finish()");
        current = page;
        if (valueSets == null) {
            return;  // the predicate became too large.
        }

        // TODO: we should account for the memory used for collecting build-side values using MemoryContext
        long filterSizeInBytes = 0;
        int filterPositionsCount = 0;
        // Collect only the columns which are relevant for the JOIN.
        for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
            Block block = page.getBlock(channels.get(channelIndex).index);
            TypedSet valueSet = valueSets[channelIndex];
            for (int position = 0; position < block.getPositionCount(); ++position) {
                valueSet.add(block, position);
            }
            filterSizeInBytes += valueSet.getRetainedSizeInBytes();
            filterPositionsCount += valueSet.size();
        }
        if (filterPositionsCount > maxFilterPositionsCount || filterSizeInBytes > maxFilterSizeInBytes) {
            // The whole filter (summed over all columns) contains too much values or exceeds maxFilterSizeInBytes.
            handleTooLargePredicate();
        }
    }

    private void handleTooLargePredicate()
    {
        // The resulting predicate is too large, allow all probe-side values to be read.
        dynamicPredicateConsumer.accept(TupleDomain.all());
        // Drop references to collected values.
        valueSets = null;
        blockBuilders = null;
    }

    @Override
    public Page getOutput()
    {
        Page result = current;
        current = null;
        return result;
    }

    @Override
    public void finish()
    {
        if (finished) {
            // NOTE: finish() may be called multiple times (see comment at Driver::processInternal).
            return;
        }
        finished = true;
        if (valueSets == null) {
            return; // the predicate became too large.
        }

        ImmutableMap.Builder<DynamicFilterId, Domain> domainsBuilder = new ImmutableMap.Builder<>();
        for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
            Block block = blockBuilders[channelIndex].build();
            Type type = channels.get(channelIndex).type;
            domainsBuilder.put(channels.get(channelIndex).filterId, convertToDomain(type, block));
        }
        valueSets = null;
        blockBuilders = null;
        dynamicPredicateConsumer.accept(TupleDomain.withColumnDomains(domainsBuilder.build()));
    }

    private Domain convertToDomain(Type type, Block block)
    {
        ImmutableList.Builder<Object> values = ImmutableList.builder();
        for (int position = 0; position < block.getPositionCount(); ++position) {
            Object value = TypeUtils.readNativeValue(type, block, position);
            if (value != null) {
                // join doesn't match rows with NaN values.
                if (!isFloatingPointNaN(type, value)) {
                    values.add(value);
                }
            }
        }

        // Inner and right join doesn't match rows with null key column values.
        return Domain.create(ValueSet.copyOf(type, values.build()), false);
    }

    @Override
    public boolean isFinished()
    {
        return current == null && finished;
    }
}
