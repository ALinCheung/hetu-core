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
package io.prestosql.sql.planner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.SpecialForm;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.sql.tree.CoalesceExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.SymbolReference;

import javax.annotation.concurrent.Immutable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.spi.relation.SpecialForm.Form.COALESCE;
import static io.prestosql.sql.planner.Partitioning.ArgumentBinding.expressionBinding;
import static io.prestosql.sql.planner.SymbolUtils.from;
import static io.prestosql.sql.planner.SymbolUtils.toSymbolReference;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToExpression;
import static io.prestosql.sql.relational.OriginalExpressionUtils.isExpression;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@Immutable
public final class Partitioning
{
    private final PartitioningHandle handle;
    private final List<ArgumentBinding> arguments;

    private Partitioning(PartitioningHandle handle, List<ArgumentBinding> arguments)
    {
        this.handle = requireNonNull(handle, "handle is null");
        this.arguments = ImmutableList.copyOf(requireNonNull(arguments, "arguments is null"));
    }

    public static Partitioning create(PartitioningHandle handle, List<Symbol> columns)
    {
        return new Partitioning(handle, columns.stream()
                .map(SymbolUtils::toSymbolReference)
                .map(ArgumentBinding::expressionBinding)
                .collect(toImmutableList()));
    }

    public static Partitioning createWithExpressions(PartitioningHandle handle, List<Expression> expressions)
    {
        return new Partitioning(handle, expressions.stream()
                .map(ArgumentBinding::expressionBinding)
                .collect(toImmutableList()));
    }

    // Factory method for JSON serde only!
    @JsonCreator
    public static Partitioning jsonCreate(
            @JsonProperty("handle") PartitioningHandle handle,
            @JsonProperty("arguments") List<ArgumentBinding> arguments)
    {
        return new Partitioning(handle, arguments);
    }

    @JsonProperty
    public PartitioningHandle getHandle()
    {
        return handle;
    }

    @JsonProperty
    public List<ArgumentBinding> getArguments()
    {
        return arguments;
    }

    public Set<Symbol> getColumns()
    {
        return arguments.stream()
                .filter(ArgumentBinding::isVariable)
                .map(ArgumentBinding::getColumn)
                .collect(toImmutableSet());
    }

    public boolean isCompatibleWith(
            Partitioning right,
            Metadata metadata,
            Session session)
    {
        if (!handle.equals(right.handle) && !metadata.getCommonPartitioning(session, handle, right.handle).isPresent()) {
            return false;
        }

        return arguments.equals(right.arguments);
    }

    public boolean isCompatibleWith(
            Partitioning right,
            Function<Symbol, Set<Symbol>> leftToRightMappings,
            Function<Symbol, Optional<NullableValue>> leftConstantMapping,
            Function<Symbol, Optional<NullableValue>> rightConstantMapping,
            Metadata metadata,
            Session session)
    {
        if (!handle.equals(right.handle) && !metadata.getCommonPartitioning(session, handle, right.handle).isPresent()) {
            return false;
        }

        if (arguments.size() != right.arguments.size()) {
            return false;
        }

        for (int i = 0; i < arguments.size(); i++) {
            ArgumentBinding leftArgument = arguments.get(i);
            ArgumentBinding rightArgument = right.arguments.get(i);

            if (!isPartitionedWith(leftArgument, leftConstantMapping, rightArgument, rightConstantMapping, leftToRightMappings)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPartitionedWith(
            ArgumentBinding leftArgument,
            Function<Symbol, Optional<NullableValue>> leftConstantMapping,
            ArgumentBinding rightArgument,
            Function<Symbol, Optional<NullableValue>> rightConstantMapping,
            Function<Symbol, Set<Symbol>> leftToRightMappings)
    {
        if (leftArgument.isVariable()) {
            if (rightArgument.isVariable()) {
                Set<Symbol> mappedColumns = leftToRightMappings.apply(leftArgument.getColumn());
                return mappedColumns.contains(rightArgument.getColumn());
            }
            else {
                // variable == constant
                // Normally, this would be a false condition, but if we happen to have an external
                // mapping from the symbol to a constant value and that constant value matches the
                // right value, then we are co-partitioned.
                Optional<NullableValue> leftConstant = leftConstantMapping.apply(leftArgument.getColumn());
                return leftConstant.isPresent() && leftConstant.get().equals(rightArgument.getConstant());
            }
        }
        else {
            if (rightArgument.isConstant()) {
                return leftArgument.getConstant().equals(rightArgument.getConstant());
            }
            else {
                // constant == variable
                Optional<NullableValue> rightConstant = rightConstantMapping.apply(rightArgument.getColumn());
                return rightConstant.isPresent() && rightConstant.get().equals(leftArgument.getConstant());
            }
        }
    }

    public boolean isPartitionedOn(Collection<Symbol> columns, Set<Symbol> knownConstants)
    {
        for (ArgumentBinding argument : arguments) {
            // partitioned on (k_1, k_2, ..., k_n) => partitioned on (k_1, k_2, ..., k_n, k_n+1, ...)
            // can safely ignore all constant columns when comparing partition properties
            if (argument.isConstant()) {
                continue;
            }
            if (!argument.isVariable()) {
                return false;
            }
            if (!knownConstants.contains(argument.getColumn()) && !columns.contains(argument.getColumn())) {
                return false;
            }
        }
        return true;
    }

    public boolean isPartitionedOnExactly(Collection<Symbol> columns, Set<Symbol> knownConstants)
    {
        Set<Symbol> toCheck = new HashSet<>();
        for (ArgumentBinding argument : arguments) {
            // partitioned on (k_1, k_2, ..., k_n) => partitioned on (k_1, k_2, ..., k_n, k_n+1, ...)
            // can safely ignore all constant columns when comparing partition properties
            if (argument.isConstant()) {
                continue;
            }
            if (!argument.isVariable()) {
                return false;
            }
            if (knownConstants.contains(argument.getColumn())) {
                continue;
            }
            toCheck.add(argument.getColumn());
        }
        return ImmutableSet.copyOf(columns).equals(toCheck);
    }

    public boolean isEffectivelySinglePartition(Set<Symbol> knownConstants)
    {
        return isPartitionedOn(ImmutableSet.of(), knownConstants);
    }

    public boolean isRepartitionEffective(Collection<Symbol> keys, Set<Symbol> knownConstants)
    {
        Set<Symbol> keysWithoutConstants = keys.stream()
                .filter(symbol -> !knownConstants.contains(symbol))
                .collect(toImmutableSet());
        Set<Symbol> nonConstantArgs = arguments.stream()
                .filter(ArgumentBinding::isVariable)
                .map(ArgumentBinding::getColumn)
                .filter(symbol -> !knownConstants.contains(symbol))
                .collect(toImmutableSet());
        return !nonConstantArgs.equals(keysWithoutConstants);
    }

    public Partitioning translate(Function<Symbol, Symbol> translator)
    {
        return new Partitioning(handle, arguments.stream()
                .map(argument -> argument.translate(translator))
                .collect(toImmutableList()));
    }

    public Optional<Partitioning> translate(Translator translator)
    {
        ImmutableList.Builder<ArgumentBinding> newArguments = ImmutableList.builder();
        for (ArgumentBinding argument : arguments) {
            Optional<ArgumentBinding> newArgument = argument.translate(translator);
            if (!newArgument.isPresent()) {
                return Optional.empty();
            }
            newArguments.add(newArgument.get());
        }

        return Optional.of(new Partitioning(handle, newArguments.build()));
    }

    public Partitioning withAlternativePartitiongingHandle(PartitioningHandle partitiongingHandle)
    {
        return new Partitioning(partitiongingHandle, this.arguments);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(handle, arguments);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final Partitioning other = (Partitioning) obj;
        return Objects.equals(this.handle, other.handle) &&
                Objects.equals(this.arguments, other.arguments);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("handle", handle)
                .add("arguments", arguments)
                .toString();
    }

    @Immutable
    public static final class Translator
    {
        private final Function<Symbol, Optional<Symbol>> columnTranslator;
        private final Function<Symbol, Optional<NullableValue>> constantTranslator;
        private final Function<Expression, Optional<Symbol>> expressionTranslator;

        public Translator(
                Function<Symbol, Optional<Symbol>> columnTranslator,
                Function<Symbol, Optional<NullableValue>> constantTranslator,
                Function<Expression, Optional<Symbol>> expressionTranslator)
        {
            this.columnTranslator = requireNonNull(columnTranslator, "columnTranslator is null");
            this.constantTranslator = requireNonNull(constantTranslator, "constantTranslator is null");
            this.expressionTranslator = requireNonNull(expressionTranslator, "expressionTranslator is null");
        }
    }

    @Immutable
    public static final class ArgumentBinding
    {
        private final Expression expression;
        private final NullableValue constant;

        @JsonCreator
        public ArgumentBinding(
                @JsonProperty("expression") Expression expression,
                @JsonProperty("constant") NullableValue constant)
        {
            this.expression = expression;
            this.constant = constant;
            checkArgument((expression == null) != (constant == null), "Either expression or constant must be set");
        }

        public static ArgumentBinding expressionBinding(Expression expression)
        {
            return new ArgumentBinding(requireNonNull(expression, "expression is null"), null);
        }

        public static ArgumentBinding constantBinding(NullableValue constant)
        {
            return new ArgumentBinding(null, requireNonNull(constant, "constant is null"));
        }

        public boolean isConstant()
        {
            return constant != null;
        }

        public boolean isVariable()
        {
            return expression instanceof SymbolReference;
        }

        public Symbol getColumn()
        {
            verify(expression instanceof SymbolReference, "Expect the expression to be a SymbolReference");
            return from(expression);
        }

        @JsonProperty
        public Expression getExpression()
        {
            return expression;
        }

        @JsonProperty
        public NullableValue getConstant()
        {
            return constant;
        }

        public ArgumentBinding translate(Function<Symbol, Symbol> translator)
        {
            if (isConstant()) {
                return this;
            }
            return expressionBinding(toSymbolReference(translator.apply(from(expression))));
        }

        public Optional<ArgumentBinding> translate(Translator translator)
        {
            if (isConstant()) {
                return Optional.of(this);
            }

            if (!isVariable()) {
                return translator.expressionTranslator.apply(expression)
                        .map(SymbolUtils::toSymbolReference)
                        .map(ArgumentBinding::expressionBinding);
            }

            Optional<ArgumentBinding> newColumn = translator.columnTranslator.apply(SymbolUtils.from(expression))
                    .map(SymbolUtils::toSymbolReference)
                    .map(ArgumentBinding::expressionBinding);
            if (newColumn.isPresent()) {
                return newColumn;
            }
            // As a last resort, check for a constant mapping for the symbol
            // Note: this MUST be last because we want to favor the symbol representation
            // as it makes further optimizations possible.
            return translator.constantTranslator.apply(from(expression))
                    .map(ArgumentBinding::constantBinding);
        }

        @Override
        public String toString()
        {
            if (constant != null) {
                return constant.toString();
            }

            return expression.toString();
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
            ArgumentBinding that = (ArgumentBinding) o;
            return Objects.equals(expression, that.expression) &&
                    Objects.equals(constant, that.constant);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(expression, constant);
        }
    }

    public Optional<Partitioning> translateRowExpression(Map<Symbol, Partitioning.ArgumentBinding> inputToOutputMappings, Map<Symbol, RowExpression> assignments, TypeProvider types)
    {
        ImmutableList.Builder<ArgumentBinding> newArguments = ImmutableList.builder();
        for (ArgumentBinding argument : arguments) {
            if (argument.isConstant()) {
                newArguments.add(argument);
            }
            else if (argument.isVariable()) {
                Symbol symbol = SymbolUtils.from(argument.getExpression());
                if (!inputToOutputMappings.containsKey(symbol)) {
                    return Optional.empty();
                }
                newArguments.add(inputToOutputMappings.get(symbol));
            }
            else {
                checkArgument(argument.getExpression() instanceof CoalesceExpression, format("Expect argument to be COALESCE but get %s", argument.getExpression()));
                Set<Expression> coalesceArguments = ImmutableSet.copyOf(((CoalesceExpression) argument.getExpression()).getOperands());
                if (!coalesceArguments.stream().allMatch(SymbolReference.class::isInstance)) {
                    break;
                }
                // We are using the property that the result of coalesce from full outer join keys would not be null despite of the order
                // of the arguments. Thus we extract and compare the variables of the COALESCE as a set rather than compare COALESCE directly.
                Symbol translated = null;
                for (Map.Entry<Symbol, RowExpression> entry : assignments.entrySet()) {
                    if (isExpression(entry.getValue())) {
                        if (castToExpression(entry.getValue()) instanceof CoalesceExpression) {
                            Set<Expression> coalesceOperands = ImmutableSet.copyOf(((CoalesceExpression) castToExpression(entry.getValue())).getOperands());
                            if (!coalesceOperands.stream().allMatch(SymbolReference.class::isInstance)) {
                                continue;
                            }

                            if (coalesceOperands.equals(coalesceArguments)) {
                                translated = entry.getKey();
                                break;
                            }
                        }
                    }
                    else {
                        if (entry.getValue() instanceof SpecialForm && ((SpecialForm) entry.getValue()).getForm().equals(COALESCE)) {
                            Set<RowExpression> assignmentArguments = ImmutableSet.copyOf(((SpecialForm) entry.getValue()).getArguments());
                            if (!assignmentArguments.stream().allMatch(VariableReferenceExpression.class::isInstance)) {
                                continue;
                            }

                            Set<Expression> assignmentSet = assignmentArguments.stream().map(arg -> new SymbolReference(((VariableReferenceExpression) arg).getName())).collect(Collectors.toSet());
                            if (assignmentSet.equals(coalesceArguments)) {
                                translated = entry.getKey();
                                break;
                            }
                        }
                    }
                }
                if (translated == null) {
                    return Optional.empty();
                }
                newArguments.add(expressionBinding(new SymbolReference(translated.getName())));
            }
        }

        return Optional.of(new Partitioning(handle, newArguments.build()));
    }
}
