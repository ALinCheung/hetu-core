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
package io.prestosql.spi.connector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;

public class ConnectorTableMetadata
{
    private final SchemaTableName table;
    private final Optional<String> comment;
    private final List<ColumnMetadata> columns;
    private final Optional<List<ColumnMetadata>> immutableColumns;
    private final Map<String, Object> properties;
    private final Optional<Set<String>> nonInheritableProperties;

    public ConnectorTableMetadata(SchemaTableName table, List<ColumnMetadata> columns)
    {
        this(table, columns, emptyMap());
    }

    public ConnectorTableMetadata(SchemaTableName table, List<ColumnMetadata> columns, Map<String, Object> properties)
    {
        this(table, columns, properties, Optional.empty());
    }

    public ConnectorTableMetadata(SchemaTableName table, List<ColumnMetadata> columns, Map<String, Object> properties, Optional<String> comment)
    {
        this(table, columns, properties, comment, Optional.empty(), Optional.empty());
    }

    public ConnectorTableMetadata(SchemaTableName table, List<ColumnMetadata> columns, Map<String, Object> properties, Optional<String> comment,
                                  Optional<List<ColumnMetadata>> immutableColumns, Optional<Set<String>> nonInheritableProperties)
    {
        requireNonNull(table, "table is null");
        requireNonNull(columns, "columns is null");
        requireNonNull(immutableColumns, "unupdatableColumns is null");
        requireNonNull(comment, "comment is null");

        this.table = table;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        if (immutableColumns.isPresent()) {
            this.immutableColumns = Optional.of(Collections.unmodifiableList(new ArrayList<>(immutableColumns.get())));
        }
        else {
            this.immutableColumns = Optional.empty();
        }

        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.comment = comment;
        this.nonInheritableProperties = nonInheritableProperties;
    }

    public SchemaTableName getTable()
    {
        return table;
    }

    public List<ColumnMetadata> getColumns()
    {
        return columns;
    }

    public Optional<List<ColumnMetadata>> getImmutableColumns()
    {
        return immutableColumns;
    }

    public Map<String, Object> getProperties()
    {
        return properties;
    }

    /**
     * Return the inheritable properties of the connector. If nonInheritableProperties is absent, return all properties.
     */
    public Map<String, Object> getInheritableProperties()
    {
        if (nonInheritableProperties.isPresent()) {
            return properties
                    .entrySet()
                    .stream()
                    .filter(entry -> !nonInheritableProperties.get().contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        return properties;
    }

    public Optional<String> getComment()
    {
        return comment;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("ConnectorTableMetadata{");
        sb.append("table=").append(table);
        sb.append(", columns=").append(columns);
        sb.append(", properties=").append(properties);
        if (immutableColumns.isPresent()) {
            sb.append(", unupdatablecolumns=").append(immutableColumns);
        }
        comment.ifPresent(value -> sb.append(", comment='").append(value).append("'"));
        sb.append('}');
        return sb.toString();
    }

    public ConnectorTableSchema getTableSchema()
    {
        return new ConnectorTableSchema(
                table,
                columns.stream()
                        .map(ColumnMetadata::getColumnSchema)
                        .collect(toImmutableList()));
    }
}
