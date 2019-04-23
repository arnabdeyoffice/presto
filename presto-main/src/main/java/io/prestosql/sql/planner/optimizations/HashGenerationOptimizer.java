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
package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.FunctionRegistry;
import io.prestosql.metadata.Signature;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.sql.planner.Partitioning.ArgumentBinding;
import io.prestosql.sql.planner.PartitioningScheme;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.SymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.ApplyNode;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.DistinctLimitNode;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.GroupIdNode;
import io.prestosql.sql.planner.plan.IndexJoinNode;
import io.prestosql.sql.planner.plan.IndexJoinNode.EquiJoinClause;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.LateralJoinNode;
import io.prestosql.sql.planner.plan.MarkDistinctNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.PlanVisitor;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.RowNumberNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.planner.plan.TopNRowNumberNode;
import io.prestosql.sql.planner.plan.UnionNode;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.planner.plan.WindowNode;
import io.prestosql.sql.tree.CoalesceExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GenericLiteral;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.SymbolReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_HASH_DISTRIBUTION;
import static io.prestosql.sql.planner.plan.ChildReplacer.replaceChildren;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.planner.plan.JoinNode.Type.LEFT;
import static io.prestosql.sql.planner.plan.JoinNode.Type.RIGHT;
import static io.prestosql.type.TypeUtils.NULL_HASH_CODE;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Stream.concat;

public class HashGenerationOptimizer
        implements PlanOptimizer
{
    public static final int INITIAL_HASH_VALUE = 0;
    private static final String HASH_CODE = FunctionRegistry.mangleOperatorName("HASH_CODE");
    private static final Signature COMBINE_HASH = new Signature(
            "combine_hash",
            SCALAR,
            BIGINT.getTypeSignature(),
            ImmutableList.of(BIGINT.getTypeSignature(), BIGINT.getTypeSignature()));

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");
        if (SystemSessionProperties.isOptimizeHashGenerationEnabled(session)) {
            PlanWithProperties result = plan.accept(new Rewriter(idAllocator, symbolAllocator, types), new HashComputationSet());
            return result.getNode();
        }
        return plan;
    }

    private static class Rewriter
            extends PlanVisitor<PlanWithProperties, HashComputationSet>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final SymbolAllocator symbolAllocator;
        private final TypeProvider types;

        private Rewriter(PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator, TypeProvider types)
        {
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.symbolAllocator = requireNonNull(symbolAllocator, "symbolAllocator is null");
            this.types = requireNonNull(types, "types is null");
        }

        @Override
        protected PlanWithProperties visitPlan(PlanNode node, HashComputationSet parentPreference)
        {
            return planSimpleNodeWithProperties(node, parentPreference);
        }

        @Override
        public PlanWithProperties visitEnforceSingleRow(EnforceSingleRowNode node, HashComputationSet parentPreference)
        {
            // this plan node can only have a single input symbol, so do not add extra hash symbols
            return planSimpleNodeWithProperties(node, new HashComputationSet(), true);
        }

        @Override
        public PlanWithProperties visitApply(ApplyNode node, HashComputationSet context)
        {
            // Apply node is not supported by execution, so do not rewrite it
            // that way query will fail in sanity checkers
            return new PlanWithProperties(node, ImmutableMap.of());
        }

        @Override
        public PlanWithProperties visitLateralJoin(LateralJoinNode node, HashComputationSet context)
        {
            // Lateral join node is not supported by execution, so do not rewrite it
            // that way query will fail in sanity checkers
            return new PlanWithProperties(node, ImmutableMap.of());
        }

        @Override
        public PlanWithProperties visitAggregation(AggregationNode node, HashComputationSet parentPreference)
        {
            Optional<HashComputation> groupByHash = Optional.empty();
            if (!node.isStreamable() && !canSkipHashGeneration(node.getGroupingKeys())) {
                groupByHash = computeHash(node.getGroupingKeys());
            }

            // aggregation does not pass through preferred hash symbols
            HashComputationSet requiredHashes = new HashComputationSet(groupByHash);
            PlanWithProperties child = planAndEnforce(node.getSource(), requiredHashes, false, requiredHashes);

            Optional<Symbol> hashSymbol = groupByHash.map(child::getRequiredHashSymbol);

            return new PlanWithProperties(
                    new AggregationNode(
                            node.getId(),
                            child.getNode(),
                            node.getAggregations(),
                            node.getGroupingSets(),
                            node.getPreGroupedSymbols(),
                            node.getStep(),
                            hashSymbol,
                            node.getGroupIdSymbol()),
                    hashSymbol.isPresent() ? ImmutableMap.of(groupByHash.get(), hashSymbol.get()) : ImmutableMap.of());
        }

        private boolean canSkipHashGeneration(List<Symbol> partitionSymbols)
        {
            // HACK: bigint grouped aggregation has special operators that do not use precomputed hash, so we can skip hash generation
            return partitionSymbols.isEmpty() || (partitionSymbols.size() == 1 && types.get(Iterables.getOnlyElement(partitionSymbols)).equals(BIGINT));
        }

        @Override
        public PlanWithProperties visitGroupId(GroupIdNode node, HashComputationSet parentPreference)
        {
            // remove any hash symbols not exported by the source of this node
            return planSimpleNodeWithProperties(node, parentPreference.pruneSymbols(node.getSource().getOutputSymbols()));
        }

        @Override
        public PlanWithProperties visitDistinctLimit(DistinctLimitNode node, HashComputationSet parentPreference)
        {
            // skip hash symbol generation for single bigint
            if (canSkipHashGeneration(node.getDistinctSymbols())) {
                return planSimpleNodeWithProperties(node, parentPreference);
            }

            Optional<HashComputation> hashComputation = computeHash(node.getDistinctSymbols());
            PlanWithProperties child = planAndEnforce(
                    node.getSource(),
                    new HashComputationSet(hashComputation),
                    false,
                    parentPreference.withHashComputation(node, hashComputation));
            Symbol hashSymbol = child.getRequiredHashSymbol(hashComputation.get());

            // TODO: we need to reason about how pre-computed hashes from child relate to distinct symbols. We should be able to include any precomputed hash
            // that's functionally dependent on the distinct field in the set of distinct fields of the new node to be able to propagate it downstream.
            // Currently, such precomputed hashes will be dropped by this operation.
            return new PlanWithProperties(
                    new DistinctLimitNode(node.getId(), child.getNode(), node.getLimit(), node.isPartial(), node.getDistinctSymbols(), Optional.of(hashSymbol)),
                    ImmutableMap.of(hashComputation.get(), hashSymbol));
        }

        @Override
        public PlanWithProperties visitMarkDistinct(MarkDistinctNode node, HashComputationSet parentPreference)
        {
            // skip hash symbol generation for single bigint
            if (canSkipHashGeneration(node.getDistinctSymbols())) {
                return planSimpleNodeWithProperties(node, parentPreference);
            }

            Optional<HashComputation> hashComputation = computeHash(node.getDistinctSymbols());
            PlanWithProperties child = planAndEnforce(
                    node.getSource(),
                    new HashComputationSet(hashComputation),
                    false,
                    parentPreference.withHashComputation(node, hashComputation));
            Symbol hashSymbol = child.getRequiredHashSymbol(hashComputation.get());

            return new PlanWithProperties(
                    new MarkDistinctNode(node.getId(), child.getNode(), node.getMarkerSymbol(), node.getDistinctSymbols(), Optional.of(hashSymbol)),
                    child.getHashSymbols());
        }

        @Override
        public PlanWithProperties visitRowNumber(RowNumberNode node, HashComputationSet parentPreference)
        {
            if (node.getPartitionBy().isEmpty()) {
                return planSimpleNodeWithProperties(node, parentPreference);
            }

            Optional<HashComputation> hashComputation = computeHash(node.getPartitionBy());
            PlanWithProperties child = planAndEnforce(
                    node.getSource(),
                    new HashComputationSet(hashComputation),
                    false,
                    parentPreference.withHashComputation(node, hashComputation));
            Symbol hashSymbol = child.getRequiredHashSymbol(hashComputation.get());

            return new PlanWithProperties(
                    new RowNumberNode(
                            node.getId(),
                            child.getNode(),
                            node.getPartitionBy(),
                            node.getRowNumberSymbol(),
                            node.getMaxRowCountPerPartition(),
                            Optional.of(hashSymbol)),
                    child.getHashSymbols());
        }

        @Override
        public PlanWithProperties visitTopNRowNumber(TopNRowNumberNode node, HashComputationSet parentPreference)
        {
            if (node.getPartitionBy().isEmpty()) {
                return planSimpleNodeWithProperties(node, parentPreference);
            }

            Optional<HashComputation> hashComputation = computeHash(node.getPartitionBy());
            PlanWithProperties child = planAndEnforce(
                    node.getSource(),
                    new HashComputationSet(hashComputation),
                    false,
                    parentPreference.withHashComputation(node, hashComputation));
            Symbol hashSymbol = child.getRequiredHashSymbol(hashComputation.get());

            return new PlanWithProperties(
                    new TopNRowNumberNode(
                            node.getId(),
                            child.getNode(),
                            node.getSpecification(),
                            node.getRowNumberSymbol(),
                            node.getMaxRowCountPerPartition(),
                            node.isPartial(),
                            Optional.of(hashSymbol)),
                    child.getHashSymbols());
        }

        @Override
        public PlanWithProperties visitJoin(JoinNode node, HashComputationSet parentPreference)
        {
            List<JoinNode.EquiJoinClause> clauses = node.getCriteria();
            if (clauses.isEmpty()) {
                // join does not pass through preferred hash symbols since they take more memory and since
                // the join node filters, may take more compute
                PlanWithProperties left = planAndEnforce(node.getLeft(), new HashComputationSet(), true, new HashComputationSet());
                PlanWithProperties right = planAndEnforce(node.getRight(), new HashComputationSet(), true, new HashComputationSet());
                checkState(left.getHashSymbols().isEmpty() && right.getHashSymbols().isEmpty());
                return new PlanWithProperties(
                        replaceChildren(node, ImmutableList.of(left.getNode(), right.getNode())),
                        ImmutableMap.of());
            }

            // join does not pass through preferred hash symbols since they take more memory and since
            // the join node filters, may take more compute
            Optional<HashComputation> leftHashComputation = computeHash(Lists.transform(clauses, JoinNode.EquiJoinClause::getLeft));
            PlanWithProperties left = planAndEnforce(node.getLeft(), new HashComputationSet(leftHashComputation), true, new HashComputationSet(leftHashComputation));
            Symbol leftHashSymbol = left.getRequiredHashSymbol(leftHashComputation.get());

            Optional<HashComputation> rightHashComputation = computeHash(Lists.transform(clauses, JoinNode.EquiJoinClause::getRight));
            // drop undesired hash symbols from build to save memory
            PlanWithProperties right = planAndEnforce(node.getRight(), new HashComputationSet(rightHashComputation), true, new HashComputationSet(rightHashComputation));
            Symbol rightHashSymbol = right.getRequiredHashSymbol(rightHashComputation.get());

            // build map of all hash symbols
            // NOTE: Full outer join doesn't use hash symbols
            Map<HashComputation, Symbol> allHashSymbols = new HashMap<>();
            if (node.getType() == INNER || node.getType() == LEFT) {
                allHashSymbols.putAll(left.getHashSymbols());
            }
            if (node.getType() == INNER || node.getType() == RIGHT) {
                allHashSymbols.putAll(right.getHashSymbols());
            }

            return buildJoinNodeWithPreferredHashes(node, left, right, allHashSymbols, parentPreference, Optional.of(leftHashSymbol), Optional.of(rightHashSymbol));
        }

        private PlanWithProperties buildJoinNodeWithPreferredHashes(
                JoinNode node,
                PlanWithProperties left,
                PlanWithProperties right,
                Map<HashComputation, Symbol> allHashSymbols,
                HashComputationSet parentPreference,
                Optional<Symbol> leftHashSymbol,
                Optional<Symbol> rightHashSymbol)
        {
            // retain only hash symbols preferred by parent nodes
            Map<HashComputation, Symbol> hashSymbolsWithParentPreferences =
                    allHashSymbols.entrySet()
                            .stream()
                            .filter(entry -> parentPreference.getHashes().contains(entry.getKey()))
                            .collect(toImmutableMap(Entry::getKey, Entry::getValue));

            List<Symbol> outputSymbols = concat(left.getNode().getOutputSymbols().stream(), right.getNode().getOutputSymbols().stream())
                    .filter(symbol -> node.getOutputSymbols().contains(symbol) || hashSymbolsWithParentPreferences.values().contains(symbol))
                    .collect(toImmutableList());

            return new PlanWithProperties(
                    new JoinNode(
                            node.getId(),
                            node.getType(),
                            left.getNode(),
                            right.getNode(),
                            node.getCriteria(),
                            outputSymbols,
                            node.getFilter(),
                            leftHashSymbol,
                            rightHashSymbol,
                            node.getDistributionType(),
                            node.isSpillable()),
                    hashSymbolsWithParentPreferences);
        }

        @Override
        public PlanWithProperties visitSemiJoin(SemiJoinNode node, HashComputationSet parentPreference)
        {
            Optional<HashComputation> sourceHashComputation = computeHash(ImmutableList.of(node.getSourceJoinSymbol()));
            PlanWithProperties source = planAndEnforce(
                    node.getSource(),
                    new HashComputationSet(sourceHashComputation),
                    true,
                    new HashComputationSet(sourceHashComputation));
            Symbol sourceHashSymbol = source.getRequiredHashSymbol(sourceHashComputation.get());

            Optional<HashComputation> filterHashComputation = computeHash(ImmutableList.of(node.getFilteringSourceJoinSymbol()));
            HashComputationSet requiredHashes = new HashComputationSet(filterHashComputation);
            PlanWithProperties filteringSource = planAndEnforce(node.getFilteringSource(), requiredHashes, true, requiredHashes);
            Symbol filteringSourceHashSymbol = filteringSource.getRequiredHashSymbol(filterHashComputation.get());

            return new PlanWithProperties(
                    new SemiJoinNode(
                            node.getId(),
                            source.getNode(),
                            filteringSource.getNode(),
                            node.getSourceJoinSymbol(),
                            node.getFilteringSourceJoinSymbol(),
                            node.getSemiJoinOutput(),
                            Optional.of(sourceHashSymbol),
                            Optional.of(filteringSourceHashSymbol),
                            node.getDistributionType()),
                    source.getHashSymbols());
        }

        @Override
        public PlanWithProperties visitSpatialJoin(SpatialJoinNode node, HashComputationSet parentPreference)
        {
            PlanWithProperties left = planAndEnforce(node.getLeft(), new HashComputationSet(), true, new HashComputationSet());
            PlanWithProperties right = planAndEnforce(node.getRight(), new HashComputationSet(), true, new HashComputationSet());
            verify(left.getHashSymbols().isEmpty(), "probe side of the spatial join should not include hash symbols");
            verify(right.getHashSymbols().isEmpty(), "build side of the spatial join should not include hash symbols");
            return new PlanWithProperties(
                    replaceChildren(node, ImmutableList.of(left.getNode(), right.getNode())),
                    ImmutableMap.of());
        }

        @Override
        public PlanWithProperties visitIndexJoin(IndexJoinNode node, HashComputationSet parentPreference)
        {
            List<IndexJoinNode.EquiJoinClause> clauses = node.getCriteria();

            // join does not pass through preferred hash symbols since they take more memory and since
            // the join node filters, may take more compute
            Optional<HashComputation> probeHashComputation = computeHash(Lists.transform(clauses, IndexJoinNode.EquiJoinClause::getProbe));
            PlanWithProperties probe = planAndEnforce(
                    node.getProbeSource(),
                    new HashComputationSet(probeHashComputation),
                    true,
                    new HashComputationSet(probeHashComputation));
            Symbol probeHashSymbol = probe.getRequiredHashSymbol(probeHashComputation.get());

            Optional<HashComputation> indexHashComputation = computeHash(Lists.transform(clauses, EquiJoinClause::getIndex));
            HashComputationSet requiredHashes = new HashComputationSet(indexHashComputation);
            PlanWithProperties index = planAndEnforce(node.getIndexSource(), requiredHashes, true, requiredHashes);
            Symbol indexHashSymbol = index.getRequiredHashSymbol(indexHashComputation.get());

            // build map of all hash symbols
            Map<HashComputation, Symbol> allHashSymbols = new HashMap<>();
            if (node.getType() == IndexJoinNode.Type.INNER) {
                allHashSymbols.putAll(probe.getHashSymbols());
            }
            allHashSymbols.putAll(index.getHashSymbols());

            return new PlanWithProperties(
                    new IndexJoinNode(
                            node.getId(),
                            node.getType(),
                            probe.getNode(),
                            index.getNode(),
                            node.getCriteria(),
                            Optional.of(probeHashSymbol),
                            Optional.of(indexHashSymbol)),
                    allHashSymbols);
        }

        @Override
        public PlanWithProperties visitWindow(WindowNode node, HashComputationSet parentPreference)
        {
            if (node.getPartitionBy().isEmpty()) {
                return planSimpleNodeWithProperties(node, parentPreference, true);
            }

            Optional<HashComputation> hashComputation = computeHash(node.getPartitionBy());
            PlanWithProperties child = planAndEnforce(
                    node.getSource(),
                    new HashComputationSet(hashComputation),
                    true,
                    parentPreference.withHashComputation(node, hashComputation));

            Symbol hashSymbol = child.getRequiredHashSymbol(hashComputation.get());

            return new PlanWithProperties(
                    new WindowNode(
                            node.getId(),
                            child.getNode(),
                            node.getSpecification(),
                            node.getWindowFunctions(),
                            Optional.of(hashSymbol),
                            node.getPrePartitionedInputs(),
                            node.getPreSortedOrderPrefix()),
                    child.getHashSymbols());
        }

        @Override
        public PlanWithProperties visitExchange(ExchangeNode node, HashComputationSet parentPreference)
        {
            // remove any hash symbols not exported by this node
            HashComputationSet preference = parentPreference.pruneSymbols(node.getOutputSymbols());

            // Currently, precomputed hash values are only supported for system hash distributions without constants
            Optional<HashComputation> partitionSymbols = Optional.empty();
            PartitioningScheme partitioningScheme = node.getPartitioningScheme();
            if (partitioningScheme.getPartitioning().getHandle().equals(FIXED_HASH_DISTRIBUTION) &&
                    partitioningScheme.getPartitioning().getArguments().stream().allMatch(ArgumentBinding::isVariable)) {
                // add precomputed hash for exchange
                partitionSymbols = computeHash(partitioningScheme.getPartitioning().getArguments().stream()
                        .map(ArgumentBinding::getColumn)
                        .collect(toImmutableList()));
                preference = preference.withHashComputation(partitionSymbols);
            }

            // establish fixed ordering for hash symbols
            List<HashComputation> hashSymbolOrder = ImmutableList.copyOf(preference.getHashes());
            Map<HashComputation, Symbol> newHashSymbols = new HashMap<>();
            for (HashComputation preferredHashSymbol : hashSymbolOrder) {
                newHashSymbols.put(preferredHashSymbol, symbolAllocator.newHashSymbol());
            }

            // rewrite partition function to include new symbols (and precomputed hash
            partitioningScheme = new PartitioningScheme(
                    partitioningScheme.getPartitioning(),
                    ImmutableList.<Symbol>builder()
                            .addAll(partitioningScheme.getOutputLayout())
                            .addAll(hashSymbolOrder.stream()
                                    .map(newHashSymbols::get)
                                    .collect(toImmutableList()))
                            .build(),
                    partitionSymbols.map(newHashSymbols::get),
                    partitioningScheme.isReplicateNullsAndAny(),
                    partitioningScheme.getBucketToPartition());

            // add hash symbols to sources
            ImmutableList.Builder<List<Symbol>> newInputs = ImmutableList.builder();
            ImmutableList.Builder<PlanNode> newSources = ImmutableList.builder();
            for (int sourceId = 0; sourceId < node.getSources().size(); sourceId++) {
                PlanNode source = node.getSources().get(sourceId);
                List<Symbol> inputSymbols = node.getInputs().get(sourceId);

                Map<Symbol, Symbol> outputToInputMap = new HashMap<>();
                for (int symbolId = 0; symbolId < inputSymbols.size(); symbolId++) {
                    outputToInputMap.put(node.getOutputSymbols().get(symbolId), inputSymbols.get(symbolId));
                }
                Function<Symbol, Optional<Symbol>> outputToInputTranslator = symbol -> Optional.of(outputToInputMap.get(symbol));

                HashComputationSet sourceContext = preference.translate(outputToInputTranslator);
                PlanWithProperties child = planAndEnforce(source, sourceContext, true, sourceContext);
                newSources.add(child.getNode());

                // add hash symbols to inputs in the required order
                ImmutableList.Builder<Symbol> newInputSymbols = ImmutableList.builder();
                newInputSymbols.addAll(node.getInputs().get(sourceId));
                for (HashComputation preferredHashSymbol : hashSymbolOrder) {
                    HashComputation hashComputation = preferredHashSymbol.translate(outputToInputTranslator).get();
                    newInputSymbols.add(child.getRequiredHashSymbol(hashComputation));
                }

                newInputs.add(newInputSymbols.build());
            }

            return new PlanWithProperties(
                    new ExchangeNode(
                            node.getId(),
                            node.getType(),
                            node.getScope(),
                            partitioningScheme,
                            newSources.build(),
                            newInputs.build(),
                            node.getOrderingScheme()),
                    newHashSymbols);
        }

        @Override
        public PlanWithProperties visitUnion(UnionNode node, HashComputationSet parentPreference)
        {
            // remove any hash symbols not exported by this node
            HashComputationSet preference = parentPreference.pruneSymbols(node.getOutputSymbols());

            // create new hash symbols
            Map<HashComputation, Symbol> newHashSymbols = new HashMap<>();
            for (HashComputation preferredHashSymbol : preference.getHashes()) {
                newHashSymbols.put(preferredHashSymbol, symbolAllocator.newHashSymbol());
            }

            // add hash symbols to sources
            ImmutableListMultimap.Builder<Symbol, Symbol> newSymbolMapping = ImmutableListMultimap.builder();
            newSymbolMapping.putAll(node.getSymbolMapping());
            ImmutableList.Builder<PlanNode> newSources = ImmutableList.builder();
            for (int sourceId = 0; sourceId < node.getSources().size(); sourceId++) {
                // translate preference to input symbols
                Map<Symbol, Symbol> outputToInputMap = new HashMap<>();
                for (Symbol outputSymbol : node.getOutputSymbols()) {
                    outputToInputMap.put(outputSymbol, node.getSymbolMapping().get(outputSymbol).get(sourceId));
                }
                Function<Symbol, Optional<Symbol>> outputToInputTranslator = symbol -> Optional.of(outputToInputMap.get(symbol));

                HashComputationSet sourcePreference = preference.translate(outputToInputTranslator);
                PlanWithProperties child = planAndEnforce(node.getSources().get(sourceId), sourcePreference, true, sourcePreference);
                newSources.add(child.getNode());

                // add hash symbols to inputs
                for (Entry<HashComputation, Symbol> entry : newHashSymbols.entrySet()) {
                    HashComputation hashComputation = entry.getKey().translate(outputToInputTranslator).get();
                    newSymbolMapping.put(entry.getValue(), child.getRequiredHashSymbol(hashComputation));
                }
            }

            return new PlanWithProperties(
                    new UnionNode(
                            node.getId(),
                            newSources.build(),
                            newSymbolMapping.build(),
                            ImmutableList.copyOf(newSymbolMapping.build().keySet())),
                    newHashSymbols);
        }

        @Override
        public PlanWithProperties visitProject(ProjectNode node, HashComputationSet parentPreference)
        {
            Map<Symbol, Symbol> outputToInputMapping = computeIdentityTranslations(node.getAssignments().getMap());
            HashComputationSet sourceContext = parentPreference.translate(symbol -> Optional.ofNullable(outputToInputMapping.get(symbol)));
            PlanWithProperties child = plan(node.getSource(), sourceContext);

            // create a new project node with all assignments from the original node
            Assignments.Builder newAssignments = Assignments.builder();
            newAssignments.putAll(node.getAssignments());

            // and all hash symbols that could be translated to the source symbols
            Map<HashComputation, Symbol> allHashSymbols = new HashMap<>();
            for (HashComputation hashComputation : sourceContext.getHashes()) {
                Symbol hashSymbol = child.getHashSymbols().get(hashComputation);
                Expression hashExpression;
                if (hashSymbol == null) {
                    hashSymbol = symbolAllocator.newHashSymbol();
                    hashExpression = hashComputation.getHashExpression();
                }
                else {
                    hashExpression = hashSymbol.toSymbolReference();
                }
                newAssignments.put(hashSymbol, hashExpression);
                allHashSymbols.put(hashComputation, hashSymbol);
            }

            return new PlanWithProperties(new ProjectNode(node.getId(), child.getNode(), newAssignments.build()), allHashSymbols);
        }

        @Override
        public PlanWithProperties visitUnnest(UnnestNode node, HashComputationSet parentPreference)
        {
            PlanWithProperties child = plan(node.getSource(), parentPreference.pruneSymbols(node.getSource().getOutputSymbols()));

            // only pass through hash symbols requested by the parent
            Map<HashComputation, Symbol> hashSymbols = new HashMap<>(child.getHashSymbols());
            hashSymbols.keySet().retainAll(parentPreference.getHashes());

            return new PlanWithProperties(
                    new UnnestNode(
                            node.getId(),
                            child.getNode(),
                            ImmutableList.<Symbol>builder()
                                    .addAll(node.getReplicateSymbols())
                                    .addAll(hashSymbols.values())
                                    .build(),
                            node.getUnnestSymbols(),
                            node.getOrdinalitySymbol()),
                    hashSymbols);
        }

        private PlanWithProperties planSimpleNodeWithProperties(PlanNode node, HashComputationSet preferredHashes)
        {
            return planSimpleNodeWithProperties(node, preferredHashes, true);
        }

        private PlanWithProperties planSimpleNodeWithProperties(
                PlanNode node,
                HashComputationSet preferredHashes,
                boolean alwaysPruneExtraHashSymbols)
        {
            if (node.getSources().isEmpty()) {
                return new PlanWithProperties(node, ImmutableMap.of());
            }

            // There is not requirement to produce hash symbols and only preference for symbols
            PlanWithProperties source = planAndEnforce(Iterables.getOnlyElement(node.getSources()), new HashComputationSet(), alwaysPruneExtraHashSymbols, preferredHashes);
            PlanNode result = replaceChildren(node, ImmutableList.of(source.getNode()));

            // return only hash symbols that are passed through the new node
            Map<HashComputation, Symbol> hashSymbols = new HashMap<>(source.getHashSymbols());
            hashSymbols.values().retainAll(result.getOutputSymbols());

            return new PlanWithProperties(result, hashSymbols);
        }

        private PlanWithProperties planAndEnforce(
                PlanNode node,
                HashComputationSet requiredHashes,
                boolean pruneExtraHashSymbols,
                HashComputationSet preferredHashes)
        {
            PlanWithProperties result = plan(node, preferredHashes);

            boolean preferenceSatisfied;
            if (pruneExtraHashSymbols) {
                // Make sure that
                // (1) result has all required hashes
                // (2) any extra hashes are preferred hashes (e.g. no pruning is needed)
                Set<HashComputation> resultHashes = result.getHashSymbols().keySet();
                Set<HashComputation> requiredAndPreferredHashes = ImmutableSet.<HashComputation>builder()
                        .addAll(requiredHashes.getHashes())
                        .addAll(preferredHashes.getHashes())
                        .build();
                preferenceSatisfied = resultHashes.containsAll(requiredHashes.getHashes()) &&
                        requiredAndPreferredHashes.containsAll(resultHashes);
            }
            else {
                preferenceSatisfied = result.getHashSymbols().keySet().containsAll(requiredHashes.getHashes());
            }

            if (preferenceSatisfied) {
                return result;
            }

            return enforce(result, requiredHashes);
        }

        private PlanWithProperties enforce(PlanWithProperties planWithProperties, HashComputationSet requiredHashes)
        {
            Assignments.Builder assignments = Assignments.builder();

            Map<HashComputation, Symbol> outputHashSymbols = new HashMap<>();

            // copy through all symbols from child, except for hash symbols not needed by the parent
            Map<Symbol, HashComputation> resultHashSymbols = planWithProperties.getHashSymbols().inverse();
            for (Symbol symbol : planWithProperties.getNode().getOutputSymbols()) {
                HashComputation partitionSymbols = resultHashSymbols.get(symbol);
                if (partitionSymbols == null || requiredHashes.getHashes().contains(partitionSymbols)) {
                    assignments.put(symbol, symbol.toSymbolReference());

                    if (partitionSymbols != null) {
                        outputHashSymbols.put(partitionSymbols, symbol);
                    }
                }
            }

            // add new projections for hash symbols needed by the parent
            for (HashComputation hashComputation : requiredHashes.getHashes()) {
                if (!planWithProperties.getHashSymbols().containsKey(hashComputation)) {
                    Expression hashExpression = hashComputation.getHashExpression();
                    Symbol hashSymbol = symbolAllocator.newHashSymbol();
                    assignments.put(hashSymbol, hashExpression);
                    outputHashSymbols.put(hashComputation, hashSymbol);
                }
            }

            ProjectNode projectNode = new ProjectNode(idAllocator.getNextId(), planWithProperties.getNode(), assignments.build());
            return new PlanWithProperties(projectNode, outputHashSymbols);
        }

        private PlanWithProperties plan(PlanNode node, HashComputationSet parentPreference)
        {
            PlanWithProperties result = node.accept(this, parentPreference);
            checkState(
                    result.getNode().getOutputSymbols().containsAll(result.getHashSymbols().values()),
                    "Node %s declares hash symbols not in the output",
                    result.getNode().getClass().getSimpleName());
            return result;
        }
    }

    private static class HashComputationSet
    {
        private final Set<HashComputation> hashes;

        public HashComputationSet()
        {
            hashes = ImmutableSet.of();
        }

        public HashComputationSet(Optional<HashComputation> hash)
        {
            requireNonNull(hash, "hash is null");
            if (hash.isPresent()) {
                this.hashes = ImmutableSet.of(hash.get());
            }
            else {
                this.hashes = ImmutableSet.of();
            }
        }

        private HashComputationSet(Iterable<HashComputation> hashes)
        {
            requireNonNull(hashes, "hashes is null");
            this.hashes = ImmutableSet.copyOf(hashes);
        }

        public Set<HashComputation> getHashes()
        {
            return hashes;
        }

        public HashComputationSet pruneSymbols(List<Symbol> symbols)
        {
            Set<Symbol> uniqueSymbols = ImmutableSet.copyOf(symbols);
            return new HashComputationSet(hashes.stream()
                    .filter(hash -> hash.canComputeWith(uniqueSymbols))
                    .collect(toImmutableSet()));
        }

        public HashComputationSet translate(Function<Symbol, Optional<Symbol>> translator)
        {
            Set<HashComputation> newHashes = hashes.stream()
                    .map(hash -> hash.translate(translator))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(toImmutableSet());

            return new HashComputationSet(newHashes);
        }

        public HashComputationSet withHashComputation(PlanNode node, Optional<HashComputation> hashComputation)
        {
            return pruneSymbols(node.getOutputSymbols()).withHashComputation(hashComputation);
        }

        public HashComputationSet withHashComputation(Optional<HashComputation> hashComputation)
        {
            if (!hashComputation.isPresent()) {
                return this;
            }
            return new HashComputationSet(ImmutableSet.<HashComputation>builder()
                    .addAll(hashes)
                    .add(hashComputation.get())
                    .build());
        }
    }

    public static Optional<HashComputation> computeHash(Iterable<Symbol> fields)
    {
        requireNonNull(fields, "fields is null");
        List<Symbol> symbols = ImmutableList.copyOf(fields);
        if (symbols.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HashComputation(fields));
    }

    public static Optional<Expression> getHashExpression(List<Symbol> symbols)
    {
        if (symbols.isEmpty()) {
            return Optional.empty();
        }

        Expression result = new GenericLiteral(StandardTypes.BIGINT, String.valueOf(INITIAL_HASH_VALUE));
        for (Symbol symbol : symbols) {
            Expression hashField = new FunctionCall(
                    QualifiedName.of(HASH_CODE),
                    Optional.empty(),
                    false,
                    ImmutableList.of(new SymbolReference(symbol.getName())));

            hashField = new CoalesceExpression(hashField, new LongLiteral(String.valueOf(NULL_HASH_CODE)));

            result = new FunctionCall(QualifiedName.of("combine_hash"), ImmutableList.of(result, hashField));
        }
        return Optional.of(result);
    }

    private static class HashComputation
    {
        private final List<Symbol> fields;

        private HashComputation(Iterable<Symbol> fields)
        {
            requireNonNull(fields, "fields is null");
            this.fields = ImmutableList.copyOf(fields);
            checkArgument(!this.fields.isEmpty(), "fields can not be empty");
        }

        public List<Symbol> getFields()
        {
            return fields;
        }

        public Optional<HashComputation> translate(Function<Symbol, Optional<Symbol>> translator)
        {
            ImmutableList.Builder<Symbol> newSymbols = ImmutableList.builder();
            for (Symbol field : fields) {
                Optional<Symbol> newSymbol = translator.apply(field);
                if (!newSymbol.isPresent()) {
                    return Optional.empty();
                }
                newSymbols.add(newSymbol.get());
            }
            return computeHash(newSymbols.build());
        }

        public boolean canComputeWith(Set<Symbol> availableFields)
        {
            return availableFields.containsAll(fields);
        }

        private Expression getHashExpression()
        {
            Expression hashExpression = new GenericLiteral(StandardTypes.BIGINT, Integer.toString(INITIAL_HASH_VALUE));
            for (Symbol field : fields) {
                hashExpression = getHashFunctionCall(hashExpression, field);
            }
            return hashExpression;
        }

        private static Expression getHashFunctionCall(Expression previousHashValue, Symbol symbol)
        {
            FunctionCall functionCall = new FunctionCall(
                    QualifiedName.of(HASH_CODE),
                    Optional.empty(),
                    false,
                    ImmutableList.of(symbol.toSymbolReference()));
            List<Expression> arguments = ImmutableList.of(previousHashValue, orNullHashCode(functionCall));
            return new FunctionCall(QualifiedName.of("combine_hash"), arguments);
        }

        private static Expression orNullHashCode(Expression expression)
        {
            return new CoalesceExpression(expression, new LongLiteral(String.valueOf(NULL_HASH_CODE)));
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            HashComputation that = (HashComputation) o;
            return Objects.equals(fields, that.fields);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(fields);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("fields", fields)
                    .toString();
        }
    }

    private static class PlanWithProperties
    {
        private final PlanNode node;
        private final BiMap<HashComputation, Symbol> hashSymbols;

        public PlanWithProperties(PlanNode node, Map<HashComputation, Symbol> hashSymbols)
        {
            this.node = requireNonNull(node, "node is null");
            this.hashSymbols = ImmutableBiMap.copyOf(requireNonNull(hashSymbols, "hashSymbols is null"));
        }

        public PlanNode getNode()
        {
            return node;
        }

        public BiMap<HashComputation, Symbol> getHashSymbols()
        {
            return hashSymbols;
        }

        public Symbol getRequiredHashSymbol(HashComputation hash)
        {
            Symbol hashSymbol = hashSymbols.get(hash);
            requireNonNull(hashSymbol, () -> "No hash symbol generated for " + hash);
            return hashSymbol;
        }
    }

    private static Map<Symbol, Symbol> computeIdentityTranslations(Map<Symbol, Expression> assignments)
    {
        Map<Symbol, Symbol> outputToInput = new HashMap<>();
        for (Map.Entry<Symbol, Expression> assignment : assignments.entrySet()) {
            if (assignment.getValue() instanceof SymbolReference) {
                outputToInput.put(assignment.getKey(), Symbol.from(assignment.getValue()));
            }
        }
        return outputToInput;
    }
}