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
package com.facebook.presto.orc;

import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.spi.type.CharType;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.SqlDate;
import com.facebook.presto.spi.type.SqlDecimal;
import com.facebook.presto.spi.type.SqlTimestamp;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.VarbinaryType;
import com.facebook.presto.spi.type.VarcharType;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.SmallintType.SMALLINT;
import static com.facebook.presto.spi.type.StandardTypes.ARRAY;
import static com.facebook.presto.spi.type.StandardTypes.MAP;
import static com.facebook.presto.spi.type.StandardTypes.ROW;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public final class TestingOrcPredicate
{
    private static final int ORC_ROW_GROUP_SIZE = 10_000;

    private TestingOrcPredicate()
    {
    }

    public static OrcPredicate createOrcPredicate(Type type, Iterable<?> values, boolean noFileStats)
    {
        List<Object> expectedValues = newArrayList(values);
        if (BOOLEAN.equals(type)) {
            return new BooleanOrcPredicate(expectedValues, noFileStats);
        }
        if (TINYINT.equals(type) || SMALLINT.equals(type) || INTEGER.equals(type) || BIGINT.equals(type)) {
            return new LongOrcPredicate(
                    expectedValues.stream()
                            .map(value -> value == null ? null : ((Number) value).longValue())
                            .collect(toList()),
                    noFileStats);
        }
        if (TIMESTAMP.equals(type)) {
            return new LongOrcPredicate(
                    expectedValues.stream()
                            .map(value -> value == null ? null : ((SqlTimestamp) value).getMillisUtc())
                            .collect(toList()),
                    noFileStats);
        }
        if (DATE.equals(type)) {
            return new DateOrcPredicate(
                    expectedValues.stream()
                            .map(value -> value == null ? null : (long) ((SqlDate) value).getDays())
                            .collect(toList()),
                    noFileStats);
        }
        if (REAL.equals(type) || DOUBLE.equals(type)) {
            return new DoubleOrcPredicate(
                    expectedValues.stream()
                            .map(value -> value == null ? null : ((Number) value).doubleValue())
                            .collect(toList()),
                    noFileStats);
        }
        if (type instanceof VarbinaryType) {
            // binary does not have stats
            return new BasicOrcPredicate<>(expectedValues, Object.class, noFileStats);
        }
        if (type instanceof VarcharType) {
            return new StringOrcPredicate(expectedValues, noFileStats);
        }
        if (type instanceof CharType) {
            return new CharOrcPredicate(expectedValues, noFileStats);
        }
        if (type instanceof DecimalType) {
            return new DecimalOrcPredicate(expectedValues, noFileStats);
        }

        String baseType = type.getTypeSignature().getBase();
        if (ARRAY.equals(baseType) || MAP.equals(baseType) || ROW.equals(baseType)) {
            return new BasicOrcPredicate<>(expectedValues, Object.class, noFileStats);
        }
        throw new IllegalArgumentException("Unsupported type " + type);
    }

    public static class BasicOrcPredicate<T>
            implements OrcPredicate
    {
        private final List<T> expectedValues;
        private final boolean noFileStats;

        public BasicOrcPredicate(Iterable<?> expectedValues, Class<T> type, boolean noFileStats)
        {
            List<T> values = new ArrayList<>();
            for (Object expectedValue : expectedValues) {
                values.add(type.cast(expectedValue));
            }
            this.expectedValues = Collections.unmodifiableList(values);
            this.noFileStats = noFileStats;
        }

        @Override
        public boolean matches(long numberOfRows, Map<Integer, ColumnStatistics> statisticsByColumnIndex)
        {
            ColumnStatistics columnStatistics = statisticsByColumnIndex.get(0);

            // todo enable file stats when DWRF team verifies that the stats are correct
            // assertTrue(columnStatistics.hasNumberOfValues());
            if (noFileStats && numberOfRows == expectedValues.size()) {
                assertNull(columnStatistics);
                return true;
            }

            if (numberOfRows == expectedValues.size()) {
                // whole file
                assertChunkStats(expectedValues, columnStatistics);
            }
            else if (numberOfRows == ORC_ROW_GROUP_SIZE) {
                // middle section
                boolean foundMatch = false;

                int length;
                for (int offset = 0; offset < expectedValues.size(); offset += length) {
                    length = Math.min(ORC_ROW_GROUP_SIZE, expectedValues.size() - offset);
                    if (chunkMatchesStats(expectedValues.subList(offset, offset + length), columnStatistics)) {
                        foundMatch = true;
                        break;
                    }
                }
                assertTrue(foundMatch);
            }
            else if (numberOfRows == expectedValues.size() % ORC_ROW_GROUP_SIZE) {
                // tail section
                List<T> chunk = expectedValues.subList((int) (expectedValues.size() - numberOfRows), expectedValues.size());
                assertChunkStats(chunk, columnStatistics);
            }
            else {
                fail("Unexpected number of rows: " + numberOfRows);
            }
            return true;
        }

        private void assertChunkStats(List<T> chunk, ColumnStatistics columnStatistics)
        {
            assertTrue(chunkMatchesStats(chunk, columnStatistics));
        }

        protected boolean chunkMatchesStats(List<T> chunk, ColumnStatistics columnStatistics)
        {
            // verify non null count
            if (columnStatistics.getNumberOfValues() != Iterables.size(filter(chunk, notNull()))) {
                return false;
            }

            return true;
        }
    }

    public static class BooleanOrcPredicate
            extends BasicOrcPredicate<Boolean>
    {
        public BooleanOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, Boolean.class, noFileStats);
        }

        @Override
        protected boolean chunkMatchesStats(List<Boolean> chunk, ColumnStatistics columnStatistics)
        {
            assertNull(columnStatistics.getIntegerStatistics());
            assertNull(columnStatistics.getDoubleStatistics());
            assertNull(columnStatistics.getStringStatistics());
            assertNull(columnStatistics.getDateStatistics());

            // check basic statistics
            if (!super.chunkMatchesStats(chunk, columnStatistics)) {
                return false;
            }

            // statistics can be missing for any reason
            if (columnStatistics.getBooleanStatistics() != null) {
                if (columnStatistics.getBooleanStatistics().getTrueValueCount() != Iterables.size(filter(chunk, equalTo(Boolean.TRUE)))) {
                    return false;
                }
            }
            return true;
        }
    }

    public static class DoubleOrcPredicate
            extends BasicOrcPredicate<Double>
    {
        public DoubleOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, Double.class, noFileStats);
        }

        @Override
        protected boolean chunkMatchesStats(List<Double> chunk, ColumnStatistics columnStatistics)
        {
            assertNull(columnStatistics.getBooleanStatistics());
            assertNull(columnStatistics.getIntegerStatistics());
            assertNull(columnStatistics.getStringStatistics());
            assertNull(columnStatistics.getDateStatistics());

            // check basic statistics
            if (!super.chunkMatchesStats(chunk, columnStatistics)) {
                return false;
            }

            // statistics can be missing for any reason
            if (columnStatistics.getDoubleStatistics() != null) {
                // verify min
                if (Math.abs(columnStatistics.getDoubleStatistics().getMin() - Ordering.natural().nullsLast().min(chunk)) > 0.001) {
                    return false;
                }

                // verify max
                if (Math.abs(columnStatistics.getDoubleStatistics().getMax() - Ordering.natural().nullsFirst().max(chunk)) > 0.001) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class DecimalOrcPredicate
            extends BasicOrcPredicate<SqlDecimal>
    {
        public DecimalOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, SqlDecimal.class, noFileStats);
        }
    }

    public static class LongOrcPredicate
            extends BasicOrcPredicate<Long>
    {
        public LongOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, Long.class, noFileStats);
        }

        @Override
        protected boolean chunkMatchesStats(List<Long> chunk, ColumnStatistics columnStatistics)
        {
            assertNull(columnStatistics.getBooleanStatistics());
            assertNull(columnStatistics.getDoubleStatistics());
            assertNull(columnStatistics.getStringStatistics());
            assertNull(columnStatistics.getDateStatistics());

            // check basic statistics
            if (!super.chunkMatchesStats(chunk, columnStatistics)) {
                return false;
            }

            // statistics can be missing for any reason
            if (columnStatistics.getIntegerStatistics() != null) {
                // verify min
                if (!columnStatistics.getIntegerStatistics().getMin().equals(Ordering.natural().nullsLast().min(chunk))) {
                    return false;
                }

                // verify max
                if (!columnStatistics.getIntegerStatistics().getMax().equals(Ordering.natural().nullsFirst().max(chunk))) {
                    return false;
                }
            }

            return true;
        }
    }

    public static class StringOrcPredicate
            extends BasicOrcPredicate<String>
    {
        public StringOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, String.class, noFileStats);
        }

        @Override
        protected boolean chunkMatchesStats(List<String> chunk, ColumnStatistics columnStatistics)
        {
            assertNull(columnStatistics.getBooleanStatistics());
            assertNull(columnStatistics.getIntegerStatistics());
            assertNull(columnStatistics.getDoubleStatistics());
            assertNull(columnStatistics.getDateStatistics());

            // check basic statistics
            if (!super.chunkMatchesStats(chunk, columnStatistics)) {
                return false;
            }

            List<Slice> slices = chunk.stream()
                    .filter(Objects::nonNull)
                    .map(Slices::utf8Slice)
                    .collect(toList());

            // statistics can be missing for any reason
            if (columnStatistics.getStringStatistics() != null) {
                // verify min
                Slice chunkMin = Ordering.natural().nullsLast().min(slices);
                if (columnStatistics.getStringStatistics().getMin().compareTo(chunkMin) > 0) {
                    return false;
                }

                // verify max
                Slice chunkMax = Ordering.natural().nullsFirst().max(slices);
                if (columnStatistics.getStringStatistics().getMax().compareTo(chunkMax) < 0) {
                    return false;
                }
            }

            return true;
        }
    }

    public static class CharOrcPredicate
            extends BasicOrcPredicate<String>
    {
        public CharOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, String.class, noFileStats);
        }

        @Override
        protected boolean chunkMatchesStats(List<String> chunk, ColumnStatistics columnStatistics)
        {
            assertNull(columnStatistics.getBooleanStatistics());
            assertNull(columnStatistics.getIntegerStatistics());
            assertNull(columnStatistics.getDoubleStatistics());
            assertNull(columnStatistics.getDateStatistics());

            // check basic statistics
            if (!super.chunkMatchesStats(chunk, columnStatistics)) {
                return false;
            }

            List<String> strings = chunk.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .collect(toList());

            // statistics can be missing for any reason
            if (columnStatistics.getStringStatistics() != null) {
                // verify min
                String chunkMin = Ordering.natural().nullsLast().min(strings);
                if (columnStatistics.getStringStatistics().getMin().toStringUtf8().trim().compareTo(chunkMin) > 0) {
                    return false;
                }

                // verify max
                String chunkMax = Ordering.natural().nullsFirst().max(strings);
                if (columnStatistics.getStringStatistics().getMax().toStringUtf8().trim().compareTo(chunkMax) < 0) {
                    return false;
                }
            }

            return true;
        }
    }

    public static class DateOrcPredicate
            extends BasicOrcPredicate<Long>
    {
        public DateOrcPredicate(Iterable<?> expectedValues, boolean noFileStats)
        {
            super(expectedValues, Long.class, noFileStats);
        }

        @Override
        protected boolean chunkMatchesStats(List<Long> chunk, ColumnStatistics columnStatistics)
        {
            assertNull(columnStatistics.getBooleanStatistics());
            assertNull(columnStatistics.getIntegerStatistics());
            assertNull(columnStatistics.getDoubleStatistics());
            assertNull(columnStatistics.getStringStatistics());

            // check basic statistics
            if (!super.chunkMatchesStats(chunk, columnStatistics)) {
                return false;
            }

            // statistics can be missing for any reason
            if (columnStatistics.getDateStatistics() != null) {
                // verify min
                Long min = columnStatistics.getDateStatistics().getMin().longValue();
                if (!min.equals(Ordering.natural().nullsLast().min(chunk))) {
                    return false;
                }

                // verify max
                Long statMax = columnStatistics.getDateStatistics().getMax().longValue();
                Long chunkMax = Ordering.natural().nullsFirst().max(chunk);
                if (!statMax.equals(chunkMax)) {
                    return false;
                }
            }

            return true;
        }
    }
}
