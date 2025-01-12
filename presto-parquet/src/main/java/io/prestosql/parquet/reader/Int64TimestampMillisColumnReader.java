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
package io.prestosql.parquet.reader;

import io.prestosql.parquet.RichColumnDescriptor;
import io.prestosql.spi.TrinoException;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.LongTimestamp;
import io.prestosql.spi.type.TimestampType;
import io.prestosql.spi.type.TimestampWithTimeZoneType;
import io.prestosql.spi.type.Type;

import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.prestosql.spi.type.TimeZoneKey.UTC_KEY;
import static io.prestosql.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static java.lang.String.format;

public class Int64TimestampMillisColumnReader
        extends PrimitiveColumnReader
{
    public Int64TimestampMillisColumnReader(RichColumnDescriptor field)
    {
        super(field);
    }

    @Override
    protected void readValue(BlockBuilder blockBuilder, Type type)
    {
        long epochMillis = valuesReader.readLong();
        if (type instanceof TimestampWithTimeZoneType) {
            type.writeLong(blockBuilder, packDateTimeWithZone(epochMillis, UTC_KEY));
        }
        else if (type instanceof TimestampType) {
            long epochMicros = epochMillis * MICROSECONDS_PER_MILLISECOND;
            if (((TimestampType) type).isShort()) {
                type.writeLong(blockBuilder, epochMicros);
            }
            else {
                type.writeObject(blockBuilder, new LongTimestamp(epochMicros, 0));
            }
        }
        else if (type == BIGINT) {
            type.writeLong(blockBuilder, epochMillis);
        }
        else {
            throw new TrinoException(NOT_SUPPORTED, format("Unsupported Trino column type (%s) for Parquet column (%s)", type, columnDescriptor));
        }
    }

    @Override
    protected void skipValue()
    {}
}
