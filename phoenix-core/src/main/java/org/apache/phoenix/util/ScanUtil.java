/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.util;

import static org.apache.phoenix.compile.OrderByCompiler.OrderBy.FWD_ROW_KEY_ORDER_BY;
import static org.apache.phoenix.compile.OrderByCompiler.OrderBy.REV_ROW_KEY_ORDER_BY;
import static org.apache.phoenix.coprocessor.BaseScannerRegionObserver.CUSTOM_ANNOTATIONS;
import static org.apache.phoenix.coprocessor.BaseScannerRegionObserver.SCAN_ACTUAL_START_ROW;
import static org.apache.phoenix.coprocessor.BaseScannerRegionObserver.SCAN_START_ROW_SUFFIX;
import static org.apache.phoenix.coprocessor.BaseScannerRegionObserver.SCAN_STOP_ROW_SUFFIX;
import static org.apache.phoenix.coprocessor.BaseScannerRegionObserver.isPhoenixTableTTLEnabled;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DEFAULT_PHOENIX_TTL;
import static org.apache.phoenix.query.QueryConstants.ENCODED_EMPTY_COLUMN_NAME;
import static org.apache.phoenix.schema.types.PDataType.TRUE_BYTES;
import static org.apache.phoenix.util.ByteUtil.EMPTY_BYTE_ARRAY;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.CoprocessorDescriptor;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.WritableComparator;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.compile.ScanRanges;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.coprocessor.BaseScannerRegionObserver;
import org.apache.phoenix.coprocessor.MetaDataProtocol;
import org.apache.phoenix.coprocessor.generated.PTableProtos;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.BaseQueryPlan;
import org.apache.phoenix.execute.DescVarLengthFastByteComparisons;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.filter.BooleanExpressionFilter;
import org.apache.phoenix.filter.ColumnProjectionFilter;
import org.apache.phoenix.filter.DistinctPrefixFilter;
import org.apache.phoenix.filter.EncodedQualifiersColumnProjectionFilter;
import org.apache.phoenix.filter.MultiEncodedCQKeyValueComparisonFilter;
import org.apache.phoenix.filter.PagingFilter;
import org.apache.phoenix.filter.SkipScanFilter;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.hbase.index.util.VersionUtil;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.index.PhoenixIndexCodec;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.query.KeyRange.Bound;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.IllegalDataException;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.RowKeySchema;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.schema.ValueSchema.Field;
import org.apache.phoenix.schema.transform.SystemTransformRecord;
import org.apache.phoenix.schema.transform.Transform;
import org.apache.phoenix.schema.transform.TransformMaintainer;
import org.apache.phoenix.schema.tuple.ResultTuple;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PVarbinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.collect.Iterators;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;

/**
 * 
 * Various utilities for scans
 *
 * 
 * @since 0.1
 */
public class ScanUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanUtil.class);
    public static final int[] SINGLE_COLUMN_SLOT_SPAN = new int[1];
    public static final int UNKNOWN_CLIENT_VERSION = VersionUtil.encodeVersion(4, 4, 0);

    /*
     * Max length that we fill our key when we turn an inclusive key
     * into a exclusive key.
     */
    private static final byte[] MAX_FILL_LENGTH_FOR_PREVIOUS_KEY = new byte[16];
    static {
        Arrays.fill(MAX_FILL_LENGTH_FOR_PREVIOUS_KEY, (byte)-1);
    }
    private static final byte[] ZERO_BYTE_ARRAY = new byte[1024];

    private ScanUtil() {
    }

    public static void setTenantId(Scan scan, byte[] tenantId) {
        scan.setAttribute(PhoenixRuntime.TENANT_ID_ATTRIB, tenantId);
    }

    public static void setLocalIndex(Scan scan) {
        scan.setAttribute(BaseScannerRegionObserver.LOCAL_INDEX, PDataType.TRUE_BYTES);
    }

    public static void setUncoveredGlobalIndex(Scan scan) {
        scan.setAttribute(BaseScannerRegionObserver.UNCOVERED_GLOBAL_INDEX, PDataType.TRUE_BYTES);
    }

    public static boolean isLocalIndex(Scan scan) {
        return scan.getAttribute(BaseScannerRegionObserver.LOCAL_INDEX) != null;
    }
    public static boolean isUncoveredGlobalIndex(Scan scan) {
        return scan.getAttribute(BaseScannerRegionObserver.UNCOVERED_GLOBAL_INDEX) != null;
    }

    public static boolean isLocalOrUncoveredGlobalIndex(Scan scan) {
        return isLocalIndex(scan) || isUncoveredGlobalIndex(scan);
    }

    public static boolean isNonAggregateScan(Scan scan) {
        return scan.getAttribute(BaseScannerRegionObserver.NON_AGGREGATE_QUERY) != null;
    }

    // Designates a "simple scan", i.e. a scan that does not need to be scoped
    // to a single region.
    public static boolean isSimpleScan(Scan scan) {
        return  ScanUtil.isNonAggregateScan(scan) &&
                scan.getAttribute(BaseScannerRegionObserver.TOPN) == null &&
                scan.getAttribute(BaseScannerRegionObserver.SCAN_OFFSET) == null;
    }

    // Use getTenantId and pass in column name to match against
    // in as PSchema attribute. If column name matches in 
    // KeyExpressions, set on scan as attribute
    public static ImmutableBytesPtr getTenantId(Scan scan) {
        // Create Scan with special aggregation column over which to aggregate
        byte[] tenantId = scan.getAttribute(PhoenixRuntime.TENANT_ID_ATTRIB);
        if (tenantId == null) {
            return null;
        }
        return new ImmutableBytesPtr(tenantId);
    }
    
    public static void setCustomAnnotations(Scan scan, byte[] annotations) {
    	scan.setAttribute(CUSTOM_ANNOTATIONS, annotations);
    }
    
    public static byte[] getCustomAnnotations(Scan scan) {
    	return scan.getAttribute(CUSTOM_ANNOTATIONS);
    }

    public static Scan newScan(Scan scan) {
        try {
            Scan newScan = new Scan(scan);
            // Clone the underlying family map instead of sharing it between
            // the existing and cloned Scan (which is the retarded default
            // behavior).
            TreeMap<byte [], NavigableSet<byte []>> existingMap = (TreeMap<byte[], NavigableSet<byte[]>>)scan.getFamilyMap();
            Map<byte [], NavigableSet<byte []>> clonedMap = new TreeMap<byte [], NavigableSet<byte []>>(existingMap);
            newScan.setFamilyMap(clonedMap);
            // Carry over the reversed attribute
            newScan.setReversed(scan.isReversed());
            if (scan.getReadType() == Scan.ReadType.PREAD) {
                // HBASE-25644 : Only if Scan#setSmall(boolean) is called with
                // true, readType should be set PREAD. For non-small scan,
                // setting setSmall(false) is redundant and degrades perf
                // without HBASE-25644 fix.
                newScan.setReadType(Scan.ReadType.PREAD);
            }
            return newScan;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Intersects the scan start/stop row with the startKey and stopKey
     * @param scan
     * @param startKey
     * @param stopKey
     * @return false if the Scan cannot possibly return rows and true otherwise
     */
    public static boolean intersectScanRange(Scan scan, byte[] startKey, byte[] stopKey) {
        return intersectScanRange(scan, startKey, stopKey, false);
    }

    public static boolean intersectScanRange(Scan scan, byte[] startKey, byte[] stopKey, boolean useSkipScan) {
        boolean mayHaveRows = false;
        int offset = 0;
        if (ScanUtil.isLocalIndex(scan)) {
            offset = startKey.length != 0 ? startKey.length : stopKey.length;
        }
        byte[] existingStartKey = scan.getStartRow();
        byte[] existingStopKey = scan.getStopRow();
        if (existingStartKey.length > 0) {
            if (startKey.length == 0 || Bytes.compareTo(existingStartKey, startKey) > 0) {
                startKey = existingStartKey;
            }
        } else {
            mayHaveRows = true;
        }
        if (existingStopKey.length > 0) {
            if (stopKey.length == 0 || Bytes.compareTo(existingStopKey, stopKey) < 0) {
                stopKey = existingStopKey;
            }
        } else {
            mayHaveRows = true;
        }
        scan.withStartRow(startKey);
        scan.withStopRow(stopKey);
        if (offset > 0 && useSkipScan) {
            byte[] temp = null;
            if (startKey.length != 0) {
                temp =new byte[startKey.length - offset];
                System.arraycopy(startKey, offset, temp, 0, startKey.length - offset);
                startKey = temp;
            }
            if (stopKey.length != 0) {
                temp = new byte[stopKey.length - offset];
                System.arraycopy(stopKey, offset, temp, 0, stopKey.length - offset);
                stopKey = temp;
            }
        }
        mayHaveRows = mayHaveRows || Bytes.compareTo(scan.getStartRow(), scan.getStopRow()) < 0;
        
        // If the scan is using skip scan filter, intersect and replace the filter.
        if (mayHaveRows && useSkipScan) {
            Filter filter = scan.getFilter();
            if (filter instanceof SkipScanFilter) {
                SkipScanFilter oldFilter = (SkipScanFilter)filter;
                SkipScanFilter newFilter = oldFilter.intersect(startKey, stopKey);
                if (newFilter == null) {
                    return false;
                }
                // Intersect found: replace skip scan with intersected one
                scan.setFilter(newFilter);
            } else if (filter instanceof FilterList) {
                FilterList oldList = (FilterList)filter;
                FilterList newList = new FilterList(FilterList.Operator.MUST_PASS_ALL);
                for (Filter f : oldList.getFilters()) {
                    if (f instanceof SkipScanFilter) {
                        SkipScanFilter newFilter = ((SkipScanFilter)f).intersect(startKey, stopKey);
                        if (newFilter == null) {
                            return false;
                        }
                        newList.addFilter(newFilter);
                    } else {
                        newList.addFilter(f);
                    }
                }
                scan.setFilter(newList);
           }
        }
        return mayHaveRows;
    }

    public static void andFilterAtBeginning(Scan scan, Filter andWithFilter) {
        if (andWithFilter == null) {
            return;
        }
        Filter filter = scan.getFilter();
        if (filter == null) {
            scan.setFilter(andWithFilter); 
        } else if (filter instanceof FilterList && ((FilterList)filter).getOperator() == FilterList.Operator.MUST_PASS_ALL) {
            FilterList filterList = (FilterList)filter;
            List<Filter> allFilters = new ArrayList<Filter>(filterList.getFilters().size() + 1);
            allFilters.add(andWithFilter);
            allFilters.addAll(filterList.getFilters());
            scan.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL,allFilters));
        } else {
            scan.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL,Arrays.asList(andWithFilter, filter)));
        }
    }

    public static void andFilterAtEnd(Scan scan, Filter andWithFilter) {
        if (andWithFilter == null) {
            return;
        }
        Filter filter = scan.getFilter();
        if (filter == null) {
            scan.setFilter(andWithFilter); 
        } else if (filter instanceof FilterList && ((FilterList)filter).getOperator() == FilterList.Operator.MUST_PASS_ALL) {
            FilterList filterList = (FilterList)filter;
            List<Filter> allFilters = new ArrayList<Filter>(filterList.getFilters().size() + 1);
            allFilters.addAll(filterList.getFilters());
            allFilters.add(andWithFilter);
            scan.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL,allFilters));
        } else {
            scan.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL,Arrays.asList(filter, andWithFilter)));
        }
    }
    
    public static void setQualifierRangesOnFilter(Scan scan, Pair<Integer, Integer> minMaxQualifiers) {
        Filter filter = scan.getFilter();
        if (filter != null) {
            if (filter instanceof FilterList) {
                for (Filter f : ((FilterList)filter).getFilters()) {
                    if (f instanceof MultiEncodedCQKeyValueComparisonFilter) {
                        ((MultiEncodedCQKeyValueComparisonFilter)f).setMinMaxQualifierRange(minMaxQualifiers);
                    }
                }
            } else if (filter instanceof MultiEncodedCQKeyValueComparisonFilter) {
                ((MultiEncodedCQKeyValueComparisonFilter)filter).setMinMaxQualifierRange(minMaxQualifiers);
            }
        }
    }

    public static void setTimeRange(Scan scan, long ts) {
        try {
            scan.setTimeRange(MetaDataProtocol.MIN_TABLE_TIMESTAMP, ts);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setTimeRange(Scan scan, TimeRange range) {
        try {
            scan.setTimeRange(range.getMin(), range.getMax());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
	public static void setTimeRange(Scan scan, long minStamp, long maxStamp) {
		try {
			scan.setTimeRange(minStamp, maxStamp);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

    public static byte[] getMinKey(RowKeySchema schema, List<List<KeyRange>> slots, int[] slotSpan) {
        return getKey(schema, slots, slotSpan, Bound.LOWER);
    }

    public static byte[] getMaxKey(RowKeySchema schema, List<List<KeyRange>> slots, int[] slotSpan) {
        return getKey(schema, slots, slotSpan, Bound.UPPER);
    }

    private static byte[] getKey(RowKeySchema schema, List<List<KeyRange>> slots, int[] slotSpan, Bound bound) {
        if (slots.isEmpty()) {
            return KeyRange.UNBOUND;
        }
        int[] position = new int[slots.size()];
        int maxLength = 0;
        int slotEndingFieldPos = -1;
        for (int i = 0; i < position.length; i++) {
            position[i] = bound == Bound.LOWER ? 0 : slots.get(i).size()-1;
            KeyRange range = slots.get(i).get(position[i]);
            slotEndingFieldPos = slotEndingFieldPos + slotSpan[i] + 1;
            Field field = schema.getField(slotEndingFieldPos);
            int keyLength = range.getRange(bound).length;
            if (!field.getDataType().isFixedWidth()) {
                keyLength++;
                if (range.isUnbound(bound) && !range.isInclusive(bound) && field.getSortOrder() == SortOrder.DESC) {
                    keyLength++;
                }
            }
            maxLength += keyLength;
        }
        byte[] key = new byte[maxLength];
        int length = setKey(schema, slots, slotSpan, position, bound, key, 0, 0, position.length);
        if (length == 0) {
            return KeyRange.UNBOUND;
        }
        if (length == maxLength) {
            return key;
        }
        byte[] keyCopy = new byte[length];
        System.arraycopy(key, 0, keyCopy, 0, length);
        return keyCopy;
    }

    /*
     * Set the key by appending the keyRanges inside slots at positions as specified by the position array.
     * 
     * We need to increment part of the key range, or increment the whole key at the end, depending on the
     * bound we are setting and whether the key range is inclusive or exclusive. The logic for determining
     * whether to increment or not is:
     * range/single    boundary       bound      increment
     *  range          inclusive      lower         no
     *  range          inclusive      upper         yes, at the end if occurs at any slots.
     *  range          exclusive      lower         yes
     *  range          exclusive      upper         no
     *  single         inclusive      lower         no
     *  single         inclusive      upper         yes, at the end if it is the last slots.
     */
    public static int setKey(RowKeySchema schema, List<List<KeyRange>> slots, int[] slotSpan, int[] position,
            Bound bound, byte[] key, int byteOffset, int slotStartIndex, int slotEndIndex) {
        return setKey(schema, slots, slotSpan, position, bound, key, byteOffset, slotStartIndex, slotEndIndex, slotStartIndex);
    }

    public static int setKey(RowKeySchema schema, List<List<KeyRange>> slots, int[] slotSpan, int[] position,
            Bound bound, byte[] key, int byteOffset, int slotStartIndex, int slotEndIndex, int schemaStartIndex) {
        int offset = byteOffset;
        boolean lastInclusiveUpperSingleKey = false;
        boolean anyInclusiveUpperRangeKey = false;
        boolean lastUnboundUpper = false;
        // The index used for slots should be incremented by 1,
        // but the index for the field it represents in the schema
        // should be incremented by 1 + value in the current slotSpan index
        // slotSpan stores the number of columns beyond one that the range spans
        Field field = null;
        int i = slotStartIndex, fieldIndex = ScanUtil.getRowKeyPosition(slotSpan, slotStartIndex);
        for (i = slotStartIndex; i < slotEndIndex; i++) {
            // Build up the key by appending the bound of each key range
            // from the current position of each slot. 
            KeyRange range = slots.get(i).get(position[i]);
            // Use last slot in a multi-span column to determine if fixed width
            field = schema.getField(fieldIndex + slotSpan[i]);
            boolean isFixedWidth = field.getDataType().isFixedWidth();
            /*
             * If the current slot is unbound then stop if:
             * 1) setting the upper bound. There's no value in
             *    continuing because nothing will be filtered.
             * 2) setting the lower bound when the type is fixed length
             *    for the same reason. However, if the type is variable width
             *    continue building the key because null values will be filtered
             *    since our separator byte will be appended and incremented.
             */
            lastUnboundUpper = false;
            if (range.isUnbound(bound) && (bound == Bound.UPPER || isFixedWidth)) {
                lastUnboundUpper = (bound == Bound.UPPER);
                break;
            }
            byte[] bytes = range.getRange(bound);
            System.arraycopy(bytes, 0, key, offset, bytes.length);
            offset += bytes.length;
            
            /*
             * We must add a terminator to a variable length key even for the last PK column if
             * the lower key is non inclusive or the upper key is inclusive. Otherwise, we'd be
             * incrementing the key value itself, and thus bumping it up too much.
             */
            boolean inclusiveUpper = range.isUpperInclusive() && bound == Bound.UPPER;
            boolean exclusiveLower = !range.isLowerInclusive() && bound == Bound.LOWER && range != KeyRange.EVERYTHING_RANGE;
            boolean exclusiveUpper = !range.isUpperInclusive() && bound == Bound.UPPER;
            // If we are setting the upper bound of using inclusive single key, we remember 
            // to increment the key if we exit the loop after this iteration.
            // 
            // We remember to increment the last slot if we are setting the upper bound with an
            // inclusive range key.
            //
            // We cannot combine the two flags together in case for single-inclusive key followed
            // by the range-exclusive key. In that case, we do not need to increment the end at the
            // end. But if we combine the two flag, the single inclusive key in the middle of the
            // key slots would cause the flag to become true.
            lastInclusiveUpperSingleKey = range.isSingleKey() && inclusiveUpper;
            anyInclusiveUpperRangeKey |= !range.isSingleKey() && inclusiveUpper;
            // A null or empty byte array is always represented as a zero byte
            byte sepByte = SchemaUtil.getSeparatorByte(schema.rowKeyOrderOptimizable(), bytes.length == 0, field);
            
            if ( !isFixedWidth && ( sepByte == QueryConstants.DESC_SEPARATOR_BYTE 
                                    || ( !exclusiveUpper 
                                         && (fieldIndex < schema.getMaxFields() || inclusiveUpper || exclusiveLower) ) ) ) {
                key[offset++] = sepByte;
                // Set lastInclusiveUpperSingleKey back to false if this is the last pk column
                // as we don't want to increment the QueryConstants.SEPARATOR_BYTE byte in this case.
                // To test if this is the last pk column we need to consider the span of this slot
                // and the field index to see if this slot considers the last column.
                // But if last field of rowKey is variable length and also DESC, the trailing 0xFF
                // is not removed when stored in HBASE, so for such case, we should not set
                // lastInclusiveUpperSingleKey back to false.
                if(sepByte != QueryConstants.DESC_SEPARATOR_BYTE) {
                    lastInclusiveUpperSingleKey &= (fieldIndex + slotSpan[i]) < schema.getMaxFields()-1;
                }
            }
            if (exclusiveUpper) {
                // Cannot include anything else on the key, as otherwise
                // keys that match the upper range will be included. For example WHERE k1 < 2 and k2 = 3
                // would match k1 = 2, k2 = 3 which is wrong.
                break;
            }
            // If we are setting the lower bound with an exclusive range key, we need to bump the
            // slot up for each key part. For an upper bound, we bump up an inclusive key, but
            // only after the last key part.
            if (exclusiveLower) {
                if (!ByteUtil.nextKey(key, offset)) {
                    // Special case for not being able to increment.
                    // In this case we return a negative byteOffset to
                    // remove this part from the key being formed. Since the
                    // key has overflowed, this means that we should not
                    // have an end key specified.
                    return -byteOffset;
                }
                // We're filtering on values being non null here, but we still need the 0xFF
                // terminator, since DESC keys ignore the last byte as it's expected to be 
                // the terminator. Without this, we'd ignore the separator byte that was
                // just added and incremented.
                if (!isFixedWidth && bytes.length == 0 
                    && SchemaUtil.getSeparatorByte(schema.rowKeyOrderOptimizable(), false, field) == QueryConstants.DESC_SEPARATOR_BYTE) {
                    key[offset++] = QueryConstants.DESC_SEPARATOR_BYTE;
                }
            }
            
            fieldIndex += slotSpan[i] + 1;
        }
        if (lastInclusiveUpperSingleKey || anyInclusiveUpperRangeKey || lastUnboundUpper) {
            if (!ByteUtil.nextKey(key, offset)) {
                // Special case for not being able to increment.
                // In this case we return a negative byteOffset to
                // remove this part from the key being formed. Since the
                // key has overflowed, this means that we should not
                // have an end key specified.
                return -byteOffset;
            }
        }
        // Remove trailing separator bytes, since the columns may have been added
        // after the table has data, in which case there won't be a separator
        // byte.
        if (bound == Bound.LOWER) {
            while (--i >= schemaStartIndex && offset > byteOffset && 
                    !(field=schema.getField(--fieldIndex)).getDataType().isFixedWidth() && 
                    field.getSortOrder() == SortOrder.ASC &&
                    key[offset-1] == QueryConstants.SEPARATOR_BYTE) {
                offset--;
                fieldIndex -= slotSpan[i];
            }
        }
        return offset - byteOffset;
    }
    
    public static interface BytesComparator {
        public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2);
    };

    private static final BytesComparator DESC_VAR_WIDTH_COMPARATOR = new BytesComparator() {

        @Override
        public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
            return DescVarLengthFastByteComparisons.compareTo(b1, s1, l1, b2, s2, l2);
        }
        
    };
    
    private static final BytesComparator ASC_FIXED_WIDTH_COMPARATOR = new BytesComparator() {

        @Override
        public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
            return WritableComparator.compareBytes(b1, s1, l1, b2, s2, l2);
        }
        
    };
    public static BytesComparator getComparator(boolean isFixedWidth, SortOrder sortOrder) {
        return isFixedWidth || sortOrder == SortOrder.ASC ? ASC_FIXED_WIDTH_COMPARATOR : DESC_VAR_WIDTH_COMPARATOR;
    }
    public static BytesComparator getComparator(Field field) {
        return getComparator(field.getDataType().isFixedWidth(),field.getSortOrder());
    }
    /**
     * Perform a binary lookup on the list of KeyRange for the tightest slot such that the slotBound
     * of the current slot is higher or equal than the slotBound of our range. 
     * @return  the index of the slot whose slot bound equals or are the tightest one that is 
     *          smaller than rangeBound of range, or slots.length if no bound can be found.
     */
    public static int searchClosestKeyRangeWithUpperHigherThanPtr(List<KeyRange> slots, ImmutableBytesWritable ptr, int lower, Field field) {
        int upper = slots.size() - 1;
        int mid;
        BytesComparator comparator = ScanUtil.getComparator(field.getDataType().isFixedWidth(), field.getSortOrder());
        while (lower <= upper) {
            mid = (lower + upper) / 2;
            int cmp = slots.get(mid).compareUpperToLowerBound(ptr, true, comparator);
            if (cmp < 0) {
                lower = mid + 1;
            } else if (cmp > 0) {
                upper = mid - 1;
            } else {
                return mid;
            }
        }
        mid = (lower + upper) / 2;
        if (mid == 0 && slots.get(mid).compareUpperToLowerBound(ptr, true, comparator) > 0) {
            return mid;
        } else {
            return ++mid;
        }
    }
    
    public static ScanRanges newScanRanges(List<? extends Mutation> mutations) throws SQLException {
        List<KeyRange> keys = Lists.newArrayListWithExpectedSize(mutations.size());
        for (Mutation m : mutations) {
            keys.add(PVarbinary.INSTANCE.getKeyRange(m.getRow(), SortOrder.ASC));
        }
        ScanRanges keyRanges = ScanRanges.createPointLookup(keys);
        return keyRanges;
    }

    /**
     * Converts a partially qualified KeyRange into a KeyRange with a
     * inclusive lower bound and an exclusive upper bound, widening
     * as necessary.
     */
    public static KeyRange convertToInclusiveExclusiveRange (KeyRange partialRange, RowKeySchema schema, ImmutableBytesWritable ptr) {
        // Ensure minMaxRange is lower inclusive and upper exclusive, as that's
        // what we need to intersect against for the HBase scan.
        byte[] lowerRange = partialRange.getLowerRange();
        if (!partialRange.lowerUnbound()) {
            if (!partialRange.isLowerInclusive()) {
                lowerRange = ScanUtil.nextKey(lowerRange, schema, ptr);
            }
        }
        
        byte[] upperRange = partialRange.getUpperRange();
        if (!partialRange.upperUnbound()) {
            if (partialRange.isUpperInclusive()) {
                upperRange = ScanUtil.nextKey(upperRange, schema, ptr);
            }
        }
        if (partialRange.getLowerRange() != lowerRange || partialRange.getUpperRange() != upperRange) {
            partialRange = KeyRange.getKeyRange(lowerRange, upperRange);
        }
        return partialRange;
    }
    
    private static byte[] nextKey(byte[] key, RowKeySchema schema, ImmutableBytesWritable ptr) {
        int pos = 0;
        int maxOffset = schema.iterator(key, ptr);
        while (schema.next(ptr, pos, maxOffset) != null) {
            pos++;
        }
        Field field = schema.getField(pos - 1);
        if (!field.getDataType().isFixedWidth()) {
            byte[] newLowerRange = new byte[key.length + 1];
            System.arraycopy(key, 0, newLowerRange, 0, key.length);
            newLowerRange[key.length] = SchemaUtil.getSeparatorByte(schema.rowKeyOrderOptimizable(), key.length==0, field);
            key = newLowerRange;
        } else {
            key = Arrays.copyOf(key, key.length);
        }
        ByteUtil.nextKey(key, key.length);
        return key;
    }

    public static boolean isReversed(Scan scan) {
        return scan.getAttribute(BaseScannerRegionObserver.REVERSE_SCAN) != null;
    }
    
    public static void setReversed(Scan scan) {
        scan.setAttribute(BaseScannerRegionObserver.REVERSE_SCAN, PDataType.TRUE_BYTES);
        scan.setLoadColumnFamiliesOnDemand(false);
    }

    public static void unsetReversed(Scan scan) {
        scan.setAttribute(BaseScannerRegionObserver.REVERSE_SCAN, PDataType.FALSE_BYTES);
        scan.setLoadColumnFamiliesOnDemand(true);
    }

    private static byte[] getReversedRow(byte[] startRow) {
        /*
         * Must get previous key because this is going from an inclusive start key to an exclusive stop key, and we need
         * the start key to be included. We get the previous key by decrementing the last byte by one. However, with
         * variable length data types, we need to fill with the max byte value, otherwise, if the start key is 'ab', we
         * lower it to 'aa' which would cause 'aab' to be included (which isn't correct). So we fill with a 0xFF byte to
         * prevent this. A single 0xFF would be enough for our primitive types (as that byte wouldn't occur), but for an
         * arbitrary VARBINARY key we can't know how many bytes to tack on. It's lame of HBase to force us to do this.
         */
        byte[] newStartRow = startRow;
        if (startRow.length != 0) {
            newStartRow = Arrays.copyOf(startRow, startRow.length + MAX_FILL_LENGTH_FOR_PREVIOUS_KEY.length);
            if (ByteUtil.previousKey(newStartRow, startRow.length)) {
                System.arraycopy(MAX_FILL_LENGTH_FOR_PREVIOUS_KEY, 0, newStartRow, startRow.length,
                        MAX_FILL_LENGTH_FOR_PREVIOUS_KEY.length);
            } else {
                newStartRow = HConstants.EMPTY_START_ROW;
            }
        }
        return newStartRow;
    }

    // Start/stop row must be swapped if scan is being done in reverse
    public static void setupReverseScan(Scan scan) {
        if (isReversed(scan) && !scan.isReversed()) {
            byte[] newStartRow = getReversedRow(scan.getStartRow());
            byte[] newStopRow = getReversedRow(scan.getStopRow());
            scan.withStartRow(newStopRow);
            scan.withStopRow(newStartRow);
            scan.setReversed(true);
        }
    }

    /**
     * prefix region start key to the start row/stop row suffix and set as scan boundaries.
     * @param scan
     */
    public static void setupLocalIndexScan(Scan scan) {
        byte[] prefix = scan.getStartRow().length == 0 ? new byte[scan.getStopRow().length]: scan.getStartRow();
        int prefixLength = scan.getStartRow().length == 0? scan.getStopRow().length: scan.getStartRow().length;
        if(scan.getAttribute(SCAN_START_ROW_SUFFIX)!=null) {
            scan.withStartRow(ScanRanges.prefixKey(scan.getAttribute(SCAN_START_ROW_SUFFIX), 0, prefix, prefixLength));
        }
        if(scan.getAttribute(SCAN_STOP_ROW_SUFFIX)!=null) {
            scan.withStopRow(ScanRanges.prefixKey(scan.getAttribute(SCAN_STOP_ROW_SUFFIX), 0, prefix, prefixLength));
        }
    }

    public static byte[] getActualStartRow(Scan localIndexScan, RegionInfo regionInfo) {
        return localIndexScan.getAttribute(SCAN_START_ROW_SUFFIX) == null ? localIndexScan
                .getStartRow() : ScanRanges.prefixKey(localIndexScan.getAttribute(SCAN_START_ROW_SUFFIX), 0 ,
            regionInfo.getStartKey().length == 0 ? new byte[regionInfo.getEndKey().length]
                    : regionInfo.getStartKey(),
            regionInfo.getStartKey().length == 0 ? regionInfo.getEndKey().length : regionInfo
                    .getStartKey().length);
    }

    /**
     * Set all attributes required and boundaries for local index scan.
     * @param keyOffset
     * @param regionStartKey
     * @param regionEndKey
     * @param newScan
     */
    public static void setLocalIndexAttributes(Scan newScan, int keyOffset, byte[] regionStartKey, byte[] regionEndKey, byte[] startRowSuffix, byte[] stopRowSuffix) {
        if(ScanUtil.isLocalIndex(newScan)) {
             newScan.setAttribute(SCAN_ACTUAL_START_ROW, regionStartKey);
             newScan.withStartRow(regionStartKey);
             newScan.withStopRow(regionEndKey);
             if (keyOffset > 0 ) {
                 newScan.setAttribute(SCAN_START_ROW_SUFFIX, ScanRanges.stripPrefix(startRowSuffix, keyOffset));
             } else {
                 newScan.setAttribute(SCAN_START_ROW_SUFFIX, startRowSuffix);
             }
             if (keyOffset > 0) {
                 newScan.setAttribute(SCAN_STOP_ROW_SUFFIX, ScanRanges.stripPrefix(stopRowSuffix, keyOffset));
             } else {
                 newScan.setAttribute(SCAN_STOP_ROW_SUFFIX, stopRowSuffix);
             }
         }
    }

    public static boolean isContextScan(Scan scan, StatementContext context) {
        return Bytes.compareTo(context.getScan().getStartRow(), scan.getStartRow()) == 0 && Bytes
                .compareTo(context.getScan().getStopRow(), scan.getStopRow()) == 0;
    }
    public static int getRowKeyOffset(byte[] regionStartKey, byte[] regionEndKey) {
        return regionStartKey.length > 0 ? regionStartKey.length : regionEndKey.length;
    }
    
    private static void setRowKeyOffset(Filter filter, int offset) {
        if (filter instanceof BooleanExpressionFilter) {
            BooleanExpressionFilter boolFilter = (BooleanExpressionFilter)filter;
            IndexUtil.setRowKeyExpressionOffset(boolFilter.getExpression(), offset);
        } else if (filter instanceof SkipScanFilter) {
            SkipScanFilter skipScanFilter = (SkipScanFilter)filter;
            skipScanFilter.setOffset(offset);
        } else if (filter instanceof DistinctPrefixFilter) {
            DistinctPrefixFilter prefixFilter = (DistinctPrefixFilter) filter;
            prefixFilter.setOffset(offset);
        }
    }

    public static void setRowKeyOffset(Scan scan, int offset) {
        Filter filter = scan.getFilter();
        if (filter == null) {
            return;
        }
        if (filter instanceof PagingFilter) {
            filter = ((PagingFilter) filter).getDelegateFilter();
            if (filter == null) {
                return;
            }
        }
        if (filter instanceof FilterList) {
            FilterList filterList = (FilterList)filter;
            for (Filter childFilter : filterList.getFilters()) {
                setRowKeyOffset(childFilter, offset);
            }
        } else {
            setRowKeyOffset(filter, offset);
        }
    }

    public static int[] getDefaultSlotSpans(int nSlots) {
        return new int[nSlots];
    }

    /**
     * Finds the position in the row key schema for a given position in the scan slots.
     * For example, with a slotSpan of {0, 1, 0}, the slot at index 1 spans an extra column in the row key. This means
     * that the slot at index 2 has a slot index of 2 but a row key index of 3.
     * To calculate the "adjusted position" index, we simply add up the number of extra slots spanned and offset
     * the slotPosition by that much.
     * @param slotSpan  the extra span per skip scan slot. corresponds to {@link ScanRanges#slotSpan}
     * @param slotPosition  the index of a slot in the SkipScan slots list.
     * @return  the equivalent row key position in the RowKeySchema
     */
    public static int getRowKeyPosition(int[] slotSpan, int slotPosition) {
        int offset = 0;

        for(int i = 0; i < slotPosition; i++) {
            offset += slotSpan[i];
        }

        return offset + slotPosition;
    }

    public static boolean isAnalyzeTable(Scan scan) {
        return scan.getAttribute((BaseScannerRegionObserver.ANALYZE_TABLE)) != null;
    }

    public static boolean crossesPrefixBoundary(byte[] key, byte[] prefixBytes, int prefixLength) {
        if (key.length < prefixLength) {
            return true;
        }
        if (prefixBytes.length >= prefixLength) {
            return Bytes.compareTo(prefixBytes, 0, prefixLength, key, 0, prefixLength) != 0;
        }
        return hasNonZeroLeadingBytes(key, prefixLength);
    }

    public static byte[] getPrefix(byte[] startKey, int prefixLength) {
        // If startKey is at beginning, then our prefix will be a null padded byte array
        return startKey.length >= prefixLength ? startKey : EMPTY_BYTE_ARRAY;
    }

    private static boolean hasNonZeroLeadingBytes(byte[] key, int nBytesToCheck) {
        if (nBytesToCheck > ZERO_BYTE_ARRAY.length) {
            do {
                if (Bytes.compareTo(key, nBytesToCheck - ZERO_BYTE_ARRAY.length, ZERO_BYTE_ARRAY.length, ScanUtil.ZERO_BYTE_ARRAY, 0, ScanUtil.ZERO_BYTE_ARRAY.length) != 0) {
                    return true;
                }
                nBytesToCheck -= ZERO_BYTE_ARRAY.length;
            } while (nBytesToCheck > ZERO_BYTE_ARRAY.length);
        }
        return Bytes.compareTo(key, 0, nBytesToCheck, ZERO_BYTE_ARRAY, 0, nBytesToCheck) != 0;
    }

    public static byte[] getTenantIdBytes(RowKeySchema schema, boolean isSalted, PName tenantId, boolean isMultiTenantTable, boolean isSharedIndex)
            throws SQLException {
        return isMultiTenantTable ?
                  getTenantIdBytes(schema, isSalted, tenantId, isSharedIndex)
                : tenantId.getBytes();
    }

    public static byte[] getTenantIdBytes(RowKeySchema schema, boolean isSalted, PName tenantId, boolean isSharedIndex)
            throws SQLException {
        int pkPos = (isSalted ? 1 : 0) + (isSharedIndex ? 1 : 0); 
        Field field = schema.getField(pkPos);
        PDataType dataType = field.getDataType();
        byte[] convertedValue;
        try {
            Object value = dataType.toObject(tenantId.getString());
            convertedValue = dataType.toBytes(value);
            ImmutableBytesWritable ptr = new ImmutableBytesWritable(convertedValue);
            dataType.pad(ptr, field.getMaxLength(), field.getSortOrder());
            convertedValue = ByteUtil.copyKeyBytesIfNecessary(ptr);
        } catch(IllegalDataException ex) {
            throw new SQLExceptionInfo.Builder(SQLExceptionCode.TENANTID_IS_OF_WRONG_TYPE)
                    .build().buildException();
        }
        return convertedValue;
    }

    public static Iterator<Filter> getFilterIterator(Scan scan) {
        Iterator<Filter> filterIterator;
        Filter topLevelFilter = scan.getFilter();
        if (topLevelFilter == null) {
            filterIterator = Collections.emptyIterator();
        } else if (topLevelFilter instanceof FilterList) {
            filterIterator = ((FilterList) topLevelFilter).getFilters().iterator();
        } else {
            filterIterator = Iterators.singletonIterator(topLevelFilter);
        }
        return filterIterator;
    }
    
    /**
     * Selecting underlying scanners in a round-robin fashion is possible if there is no ordering of
     * rows needed, not even row key order. Also no point doing round robin of scanners if fetch
     * size is 1.
     */
    public static boolean isRoundRobinPossible(OrderBy orderBy, StatementContext context)
            throws SQLException {
        int fetchSize = context.getStatement().getFetchSize();
        return fetchSize > 1 && !shouldRowsBeInRowKeyOrder(orderBy, context)
                && orderBy.getOrderByExpressions().isEmpty();
    }
    
    public static boolean forceRowKeyOrder(StatementContext context) {
        return context.getConnection().getQueryServices().getProps()
                .getBoolean(QueryServices.FORCE_ROW_KEY_ORDER_ATTRIB, QueryServicesOptions.DEFAULT_FORCE_ROW_KEY_ORDER);
    }
    
    public static boolean shouldRowsBeInRowKeyOrder(OrderBy orderBy, StatementContext context) {
        return forceRowKeyOrder(context) || orderBy == FWD_ROW_KEY_ORDER_BY || orderBy == REV_ROW_KEY_ORDER_BY;
    }
    
    public static TimeRange intersectTimeRange(TimeRange rowTimestampColRange, TimeRange scanTimeRange, Long scn) throws IOException, SQLException {
        long scnToUse = scn == null ? HConstants.LATEST_TIMESTAMP : scn;
        long lowerRangeToBe = 0;
        long upperRangeToBe = scnToUse;
        if (rowTimestampColRange != null) {
            long minRowTimestamp = rowTimestampColRange.getMin();
            long maxRowTimestamp = rowTimestampColRange.getMax();
            if ((lowerRangeToBe > maxRowTimestamp) || (upperRangeToBe < minRowTimestamp)) {
                return null; // degenerate
            } else {
                // there is an overlap of ranges
                lowerRangeToBe = Math.max(lowerRangeToBe, minRowTimestamp);
                upperRangeToBe = Math.min(upperRangeToBe, maxRowTimestamp);
            }
        }
        if (scanTimeRange != null) {
            long minScanTimeRange = scanTimeRange.getMin();
            long maxScanTimeRange = scanTimeRange.getMax();
            if ((lowerRangeToBe > maxScanTimeRange) || (upperRangeToBe < lowerRangeToBe)) {
                return null; // degenerate
            } else {
                // there is an overlap of ranges
                lowerRangeToBe = Math.max(lowerRangeToBe, minScanTimeRange);
                upperRangeToBe = Math.min(upperRangeToBe, maxScanTimeRange);
            }
        }
        return TimeRange.between(lowerRangeToBe, upperRangeToBe);
    }
    
    public static boolean isDefaultTimeRange(TimeRange range) {
        return range.getMin() == 0 && range.getMax() == Long.MAX_VALUE;
    }
    
    /**
     * @return true if scanners could be left open and records retrieved by simply advancing them on
     *         the server side. To make sure HBase doesn't cancel the leases and close the open
     *         scanners, we need to periodically renew leases. To look at the earliest HBase version
     *         that supports renewing leases, see
     *         {@link MetaDataProtocol#MIN_RENEW_LEASE_VERSION}
     */
    public static boolean isPacingScannersPossible(StatementContext context) {
        return context.getConnection().getQueryServices().isRenewingLeasesEnabled();
    }

    public static void addOffsetAttribute(Scan scan, Integer offset) {
        scan.setAttribute(BaseScannerRegionObserver.SCAN_OFFSET, Bytes.toBytes(offset));
    }
    
    public static final boolean canQueryBeExecutedSerially(PTable table, OrderBy orderBy, StatementContext context) {
        /*
         * If ordering by columns not on the PK axis, we can't execute a query serially because we
         * need to do a merge sort across all the scans which isn't possible with SerialIterators.
         * Similar reasoning follows for salted and local index tables when ordering rows in a row
         * key order. Serial execution is OK in other cases since SerialIterators will execute scans
         * in the correct order.
         */
        if (!orderBy.getOrderByExpressions().isEmpty()
                || ((table.getBucketNum() != null || table.getIndexType() == IndexType.LOCAL) && shouldRowsBeInRowKeyOrder(
                    orderBy, context))) {
            return false;
        }
        return true;
    }
    
    public static boolean hasDynamicColumns(PTable table) {
        for (PColumn col : table.getColumns()) {
            if (col.isDynamic()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isIndexRebuild(Scan scan) {
        return scan.getAttribute((BaseScannerRegionObserver.REBUILD_INDEXES)) != null;
    }
 
    public static int getClientVersion(Scan scan) {
        int clientVersion = UNKNOWN_CLIENT_VERSION;
        byte[] clientVersionBytes = scan.getAttribute(BaseScannerRegionObserver.CLIENT_VERSION);
        if (clientVersionBytes != null) {
            clientVersion = Bytes.toInt(clientVersionBytes);
        }
        return clientVersion;
    }
    
    public static void setClientVersion(Scan scan, int version) {
        scan.setAttribute(BaseScannerRegionObserver.CLIENT_VERSION, Bytes.toBytes(version));
    }

    public static boolean isServerSideMaskingEnabled(PhoenixConnection phoenixConnection) {
        String isServerSideMaskingSet = phoenixConnection.getClientInfo(
                QueryServices.PHOENIX_TTL_SERVER_SIDE_MASKING_ENABLED);
        return (phoenixConnection.getQueryServices()
                .getConfiguration().getBoolean(
                        QueryServices.PHOENIX_TTL_SERVER_SIDE_MASKING_ENABLED,
                        QueryServicesOptions.DEFAULT_SERVER_SIDE_MASKING_ENABLED) ||
                ((isServerSideMaskingSet != null) && (Boolean.parseBoolean(isServerSideMaskingSet))));
    }

    public static long getPhoenixTTL(Scan scan) {
        byte[] phoenixTTL = scan.getAttribute(BaseScannerRegionObserver.PHOENIX_TTL);
        if (phoenixTTL == null) {
            return DEFAULT_PHOENIX_TTL;
        }
        return Bytes.toLong(phoenixTTL);
    }

    public static boolean isMaskTTLExpiredRows(Scan scan) {
        return scan.getAttribute(BaseScannerRegionObserver.MASK_PHOENIX_TTL_EXPIRED) != null &&
                (Bytes.compareTo(scan.getAttribute(BaseScannerRegionObserver.MASK_PHOENIX_TTL_EXPIRED),
                        PDataType.TRUE_BYTES) == 0)
                && scan.getAttribute(BaseScannerRegionObserver.PHOENIX_TTL) != null;
    }

    public static boolean isDeleteTTLExpiredRows(Scan scan) {
        return scan.getAttribute(BaseScannerRegionObserver.DELETE_PHOENIX_TTL_EXPIRED) != null && (
                Bytes.compareTo(scan.getAttribute(BaseScannerRegionObserver.DELETE_PHOENIX_TTL_EXPIRED),
                        PDataType.TRUE_BYTES) == 0)
                && scan.getAttribute(BaseScannerRegionObserver.PHOENIX_TTL) != null;
    }

    public static boolean isEmptyColumn(Cell cell, byte[] emptyCF, byte[] emptyCQ) {
        return CellUtil.matchingFamily(cell, emptyCF, 0, emptyCF.length) &&
               CellUtil.matchingQualifier(cell, emptyCQ, 0, emptyCQ.length);
    }

    public static long getMaxTimestamp(List<Cell> cellList) {
        long maxTs = 0;
        long ts = 0;
        Iterator<Cell> cellIterator = cellList.iterator();
        while (cellIterator.hasNext()) {
            Cell cell = cellIterator.next();
            ts = cell.getTimestamp();
            if (ts > maxTs) {
                maxTs = ts;
            }
        }
        return maxTs;
    }

    public static boolean isTTLExpired(Cell cell, Scan scan, long nowTS) {
        long ts = cell.getTimestamp();
        long ttl = ScanUtil.getPhoenixTTL(scan);
        return ts + ttl < nowTS;
    }

    /**
     * This determines if we need to add the empty column to the scan. The empty column is
     * added only when the scan includes another column family but not the empty column family or
     * the empty column family includes at least one column.
     */
    private static boolean shouldAddEmptyColumn(Scan scan, byte[] emptyCF) {
        Map<byte[], NavigableSet<byte[]>> familyMap = scan.getFamilyMap();
        if (familyMap == null || familyMap.isEmpty()) {
            // This means that scan includes all columns. Nothing more to do.
            return false;
        }
        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : familyMap.entrySet()) {
            byte[] cf = entry.getKey();
            if (java.util.Arrays.equals(cf, emptyCF)) {
                NavigableSet<byte[]> family = entry.getValue();
                if (family != null && !family.isEmpty()) {
                    // Found the empty column family, and it is not empty. The empty column
                    // may be already included but no need to check as adding a new one will replace
                    // the old one
                    return true;
                }
                return false;
            }
        }
        // The colum family is not found and there is another column family in the scan. In this
        // we need to add the empty column
        return true;
    }

    private static void addEmptyColumnToFilter(Filter filter, byte[] emptyCF, byte[] emptyCQ) {
        if (filter instanceof EncodedQualifiersColumnProjectionFilter) {
            ((EncodedQualifiersColumnProjectionFilter) filter).
                    addTrackedColumn(ENCODED_EMPTY_COLUMN_NAME);
        } else if (filter instanceof ColumnProjectionFilter) {
            ((ColumnProjectionFilter) filter).addTrackedColumn(new ImmutableBytesPtr(emptyCF),
                    new ImmutableBytesPtr(emptyCQ));
        } else if (filter instanceof MultiEncodedCQKeyValueComparisonFilter) {
            ((MultiEncodedCQKeyValueComparisonFilter) filter).
                    setMinQualifier(ENCODED_EMPTY_COLUMN_NAME);
        }
    }

    private static void addEmptyColumnToFilterList(FilterList filterList,
            byte[] emptyCF, byte[] emptyCQ) {
        Iterator<Filter> filterIterator = filterList.getFilters().iterator();
        while (filterIterator.hasNext()) {
            Filter filter = filterIterator.next();
            if (filter instanceof FilterList) {
                addEmptyColumnToFilterList((FilterList) filter, emptyCF, emptyCQ);
            } else {
                addEmptyColumnToFilter(filter, emptyCF, emptyCQ);
            }
        }
    }

    public static void addEmptyColumnToScan(Scan scan, byte[] emptyCF, byte[] emptyCQ) {
        Filter filter = scan.getFilter();
        if (filter != null) {
            if (filter instanceof FilterList) {
                addEmptyColumnToFilterList((FilterList) filter, emptyCF, emptyCQ);
            } else {
                addEmptyColumnToFilter(filter, emptyCF, emptyCQ);
            }
        }
        if (shouldAddEmptyColumn(scan, emptyCF)) {
            scan.addColumn(emptyCF, emptyCQ);
        }
    }

    public static PTable getDataTable(PTable index, PhoenixConnection conn) throws SQLException {
        String schemaName = index.getParentSchemaName().getString();
        String tableName = index.getParentTableName().getString();
        PTable dataTable;
        try {
            dataTable = PhoenixRuntime.getTable(conn, SchemaUtil.getTableName(schemaName, tableName));
            return dataTable;
        } catch (TableNotFoundException e) {
            // This index table must be being deleted
            return null;
        }
    }
    public static void setScanAttributesForIndexReadRepair(Scan scan, PTable table,
            PhoenixConnection phoenixConnection) throws SQLException {
        boolean isTransforming = (table.getTransformingNewTable() != null);
        PTable indexTable = table;
        // Transforming index table can be repaired in regular path via globalindexchecker coproc on it.
        // phoenixConnection is closed when it is called from mappers
        if (!phoenixConnection.isClosed() && table.getType() == PTableType.TABLE && isTransforming) {
            SystemTransformRecord systemTransformRecord = Transform.getTransformRecord(indexTable.getSchemaName(), indexTable.getTableName(),
                    null, phoenixConnection.getTenantId(), phoenixConnection);
            if (systemTransformRecord == null) {
                return;
            }
            // Old table is still active, cutover didn't happen yet, so, no need to read repair
            if (!systemTransformRecord.getTransformStatus().equals(PTable.TransformStatus.COMPLETED.name())) {
                return;
            }
            byte[] oldTableBytes = systemTransformRecord.getOldMetadata();
            if (oldTableBytes == null || oldTableBytes.length == 0) {
                return;
            }
            PTable oldTable = null;
            try {
                oldTable = PTableImpl.createFromProto(PTableProtos.PTable.parseFrom(oldTableBytes));
            } catch (IOException e) {
                LOGGER.error("Cannot parse old table info for read repair for table " + table.getName());
                return;
            }
            TransformMaintainer indexMaintainer = indexTable.getTransformMaintainer(oldTable, phoenixConnection);
            // This is the path where we are reading from the newly transformed table
            if (scan.getAttribute(PhoenixIndexCodec.INDEX_PROTO_MD) == null) {
                ImmutableBytesWritable ptr = new ImmutableBytesWritable();
                TransformMaintainer.serialize(oldTable, ptr, indexTable, phoenixConnection);
                scan.setAttribute(PhoenixIndexCodec.INDEX_PROTO_MD, ByteUtil.copyKeyBytesIfNecessary(ptr));
            }
            scan.setAttribute(BaseScannerRegionObserver.CHECK_VERIFY_COLUMN, TRUE_BYTES);
            scan.setAttribute(BaseScannerRegionObserver.PHYSICAL_DATA_TABLE_NAME, oldTable.getPhysicalName().getBytes());
            byte[] emptyCF = indexMaintainer.getEmptyKeyValueFamily().copyBytesIfNecessary();
            byte[] emptyCQ = indexMaintainer.getEmptyKeyValueQualifier();
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_FAMILY_NAME, emptyCF);
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_QUALIFIER_NAME, emptyCQ);
            scan.setAttribute(BaseScannerRegionObserver.READ_REPAIR_TRANSFORMING_TABLE, TRUE_BYTES);
        } else {
            if (table.getType() != PTableType.INDEX || !IndexUtil.isGlobalIndex(indexTable)) {
                return;
            }
            if (table.isTransactional() && table.getIndexType() == IndexType.UNCOVERED_GLOBAL) {
                return;
            }
            PTable dataTable = ScanUtil.getDataTable(indexTable, phoenixConnection);
            if (dataTable == null) {
                // This index table must be being deleted. No need to set the scan attributes
                return;
            }
            // MetaDataClient modifies the index table name for view indexes if the parent view of an index has a child
            // view. This, we need to recreate a PTable object with the correct table name for the rest of this code to work
            if (indexTable.getViewIndexId() != null && indexTable.getName().getString().contains(QueryConstants.CHILD_VIEW_INDEX_NAME_SEPARATOR)) {
                int lastIndexOf = indexTable.getName().getString().lastIndexOf(QueryConstants.CHILD_VIEW_INDEX_NAME_SEPARATOR);
                String indexName = indexTable.getName().getString().substring(lastIndexOf + 1);
                indexTable = PhoenixRuntime.getTable(phoenixConnection, indexName);
            }
            if (!dataTable.getIndexes().contains(indexTable)) {
                return;
            }

            if (scan.getAttribute(PhoenixIndexCodec.INDEX_PROTO_MD) == null) {
                ImmutableBytesWritable ptr = new ImmutableBytesWritable();
                IndexMaintainer.serialize(dataTable, ptr, Collections.singletonList(indexTable), phoenixConnection);
                scan.setAttribute(PhoenixIndexCodec.INDEX_PROTO_MD, ByteUtil.copyKeyBytesIfNecessary(ptr));
            }
            if (IndexUtil.isCoveredGlobalIndex(indexTable)) {
                if (!isIndexRebuild(scan)) {
                    scan.setAttribute(BaseScannerRegionObserver.CHECK_VERIFY_COLUMN, TRUE_BYTES);
                }
            } else {
                scan.setAttribute(BaseScannerRegionObserver.UNCOVERED_GLOBAL_INDEX, TRUE_BYTES);
            }
            scan.setAttribute(BaseScannerRegionObserver.PHYSICAL_DATA_TABLE_NAME, dataTable.getPhysicalName().getBytes());
            IndexMaintainer indexMaintainer = indexTable.getIndexMaintainer(dataTable, phoenixConnection);
            byte[] emptyCF = indexMaintainer.getEmptyKeyValueFamily().copyBytesIfNecessary();
            byte[] emptyCQ = indexMaintainer.getEmptyKeyValueQualifier();
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_FAMILY_NAME, emptyCF);
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_QUALIFIER_NAME, emptyCQ);
            if (scan.getAttribute(BaseScannerRegionObserver.VIEW_CONSTANTS) == null) {
                BaseQueryPlan.serializeViewConstantsIntoScan(scan, dataTable);
            }
        }
    }

    public static void setScanAttributesForPhoenixTTL(Scan scan, PTable table,
            PhoenixConnection phoenixConnection) throws SQLException {

        // If Phoenix level TTL is not enabled OR is a system table then return.
        if (!isPhoenixTableTTLEnabled(phoenixConnection.getQueryServices().getConfiguration())) {
            if (SchemaUtil.isSystemTable(
                    SchemaUtil.getTableNameAsBytes(table.getSchemaName().getString(),
                            table.getTableName().getString()))) {
                scan.setAttribute(BaseScannerRegionObserver.IS_PHOENIX_TTL_SCAN_TABLE_SYSTEM,
                        Bytes.toBytes(true));
            }
            return;
        }

        PTable dataTable = table;
        String tableName = table.getTableName().getString();
        if ((table.getType() == PTableType.INDEX) && (table.getParentName() != null)) {
            String parentSchemaName = table.getParentSchemaName().getString();
            String parentTableName = table.getParentTableName().getString();
            // Look up the parent view as we could have inherited this index from an ancestor
            // view(V) with Index (VIndex) -> child view (V1) -> grand child view (V2)
            // the view index name will be V2#V1#VIndex
            // Since we store PHOENIX_TTL at every level, all children have the same value.
            // So looking at the child view is sufficient.
            if (tableName.contains(QueryConstants.CHILD_VIEW_INDEX_NAME_SEPARATOR)) {
                String parentViewName =
                        SchemaUtil.getSchemaNameFromFullName(tableName,
                                QueryConstants.CHILD_VIEW_INDEX_NAME_SEPARATOR);
                parentSchemaName = SchemaUtil.getSchemaNameFromFullName(parentViewName);
                parentTableName = SchemaUtil.getTableNameFromFullName(parentViewName);
            }
            try {
                dataTable = PhoenixRuntime.getTable(phoenixConnection,
                        SchemaUtil.getTableName(parentSchemaName, parentTableName));
            } catch (TableNotFoundException e) {
                // This data table does not exists anymore. No need to set the scan attributes
                return;
            }
        }
        if (dataTable.getPhoenixTTL() != 0) {
            byte[] emptyColumnFamilyName = SchemaUtil.getEmptyColumnFamily(table);
            byte[] emptyColumnName =
                    table.getEncodingScheme() == PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS ?
                            QueryConstants.EMPTY_COLUMN_BYTES :
                            table.getEncodingScheme().encode(QueryConstants.ENCODED_EMPTY_COLUMN_NAME);
            scan.setAttribute(BaseScannerRegionObserver.PHOENIX_TTL_SCAN_TABLE_NAME,
                    Bytes.toBytes(tableName));
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_FAMILY_NAME, emptyColumnFamilyName);
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_QUALIFIER_NAME, emptyColumnName);
            scan.setAttribute(BaseScannerRegionObserver.PHOENIX_TTL,
                    Bytes.toBytes(Long.valueOf(dataTable.getPhoenixTTL())));
            if (!ScanUtil.isDeleteTTLExpiredRows(scan)) {
                scan.setAttribute(BaseScannerRegionObserver.MASK_PHOENIX_TTL_EXPIRED, PDataType.TRUE_BYTES);
            }
            if (ScanUtil.isLocalIndex(scan)) {
                byte[] actualStartRow = scan.getAttribute(SCAN_ACTUAL_START_ROW) != null ?
                        scan.getAttribute(SCAN_ACTUAL_START_ROW) :
                        HConstants.EMPTY_BYTE_ARRAY;
                ScanUtil.setLocalIndexAttributes(scan, 0,
                        actualStartRow,
                        HConstants.EMPTY_BYTE_ARRAY,
                        scan.getStartRow(), scan.getStopRow());
            }
        }
    }

    private static void setScanAtrributesForChildLinkRepair(Scan scan, PTable table, PhoenixConnection phoenixConnection) {
        if (table.getName().getString().equals(PhoenixDatabaseMetaData.SYSTEM_CHILD_LINK_NAME)) {
            scan.setAttribute(BaseScannerRegionObserver.CHECK_VERIFY_COLUMN, TRUE_BYTES);
            byte[] emptyCF = SchemaUtil.getEmptyColumnFamily(table);
            byte[] emptyCQ = EncodedColumnsUtil.getEmptyKeyValueInfo(table).getFirst();
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_FAMILY_NAME, emptyCF);
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_QUALIFIER_NAME, emptyCQ);
            addEmptyColumnToScan(scan, emptyCF, emptyCQ);
        }
    }

    public static void setScanAttributesForClient(Scan scan, PTable table,
                                                  PhoenixConnection phoenixConnection) throws SQLException {
        setScanAttributesForIndexReadRepair(scan, table, phoenixConnection);
        setScanAttributesForPhoenixTTL(scan, table, phoenixConnection);
        setScanAtrributesForChildLinkRepair(scan, table, phoenixConnection);
        byte[] emptyCF = scan.getAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_FAMILY_NAME);
        byte[] emptyCQ = scan.getAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_QUALIFIER_NAME);
        if (emptyCF != null && emptyCQ != null) {
            addEmptyColumnToScan(scan, emptyCF, emptyCQ);
        } else if (!isAnalyzeTable(scan)) {
            emptyCF = SchemaUtil.getEmptyColumnFamily(table);
            emptyCQ = table.getEncodingScheme() == PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS ?
                    QueryConstants.EMPTY_COLUMN_BYTES :
                    table.getEncodingScheme().encode(QueryConstants.ENCODED_EMPTY_COLUMN_NAME);
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_FAMILY_NAME, emptyCF);
            scan.setAttribute(BaseScannerRegionObserver.EMPTY_COLUMN_QUALIFIER_NAME, emptyCQ);
            addEmptyColumnToScan(scan, emptyCF, emptyCQ);
        }
        if (phoenixConnection.getQueryServices().getProps().getBoolean(
                QueryServices.PHOENIX_SERVER_PAGING_ENABLED_ATTRIB,
                QueryServicesOptions.DEFAULT_PHOENIX_SERVER_PAGING_ENABLED)) {
            long pageSizeMs = phoenixConnection.getQueryServices().getProps()
                    .getInt(QueryServices.PHOENIX_SERVER_PAGE_SIZE_MS, -1);
            if (pageSizeMs == -1) {
                // Use the half of the HBase RPC timeout value as the server page size to make sure
                // that the HBase region server will be able to send a heartbeat message to the
                // client before the client times out.
                pageSizeMs = (long) (phoenixConnection.getQueryServices().getProps()
                        .getLong(HConstants.HBASE_RPC_TIMEOUT_KEY, HConstants.DEFAULT_HBASE_RPC_TIMEOUT) * 0.5);
            }
            scan.setAttribute(BaseScannerRegionObserver.SERVER_PAGE_SIZE_MS, Bytes.toBytes(Long.valueOf(pageSizeMs)));
        }

    }

    public static void getDummyResult(byte[] rowKey, List<Cell> result) {
        Cell keyValue =
                PhoenixKeyValueUtil.newKeyValue(rowKey, 0,
                        rowKey.length, EMPTY_BYTE_ARRAY, EMPTY_BYTE_ARRAY,
                        0, EMPTY_BYTE_ARRAY, 0, EMPTY_BYTE_ARRAY.length);
        result.add(keyValue);
    }

    public static void getDummyResult(List<Cell> result) {
        getDummyResult(EMPTY_BYTE_ARRAY, result);
    }

    public static Tuple getDummyTuple(byte[] rowKey) {
        List<Cell> result = new ArrayList<Cell>(1);
        getDummyResult(rowKey, result);
        return new ResultTuple(Result.create(result));
    }

    public static Tuple getDummyTuple() {
        List<Cell> result = new ArrayList<Cell>(1);
        getDummyResult(result);
        return new ResultTuple(Result.create(result));
    }

    public static Tuple getDummyTuple(Tuple tuple) {
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        tuple.getKey(ptr);
        return getDummyTuple(ptr.copyBytes());
    }

    public static boolean isDummy(Cell cell) {
        return CellUtil.matchingColumn(cell, EMPTY_BYTE_ARRAY, EMPTY_BYTE_ARRAY);
    }

    public static boolean isDummy(Result result) {
        if (result.rawCells().length != 1) {
            return false;
        }
        Cell cell = result.rawCells()[0];
        return isDummy(cell);
    }

    public static boolean isDummy(List<Cell> result) {
        if (result.size() != 1) {
            return false;
        }
        Cell cell = result.get(0);
        return isDummy(cell);
    }

    public static boolean isDummy(Tuple tuple) {
        if (tuple instanceof ResultTuple) {
            isDummy(((ResultTuple) tuple).getResult());
        }
        return false;
    }

    public static PagingFilter getPhoenixPagingFilter(Scan scan) {
        Filter filter = scan.getFilter();
        if (filter != null && filter instanceof PagingFilter) {
            PagingFilter pageFilter = (PagingFilter) filter;
            return pageFilter;
        }
        return null;
    }

    /**
     *
     * The server page size expressed in ms is the maximum time we want the Phoenix server code to
     * spend for each iteration of ResultScanner. For each ResultScanner#next() can be translated
     * into one or more HBase RegionScanner#next() calls by a Phoenix RegionScanner object in
     * a loop. To ensure that the total time spent by the Phoenix server code will not exceed
     * the configured page size value, SERVER_PAGE_SIZE_MS, the loop time in a Phoenix region
     * scanner is limited by 0.6 * SERVER_PAGE_SIZE_MS and each HBase RegionScanner#next() time
     * which is controlled by PagingFilter is set to 0.3 * SERVER_PAGE_SIZE_MS.
     *
     */
    private static long getPageSizeMs(Scan scan, double factor) {
        long pageSizeMs = Long.MAX_VALUE;
        byte[] pageSizeMsBytes = scan.getAttribute(BaseScannerRegionObserver.SERVER_PAGE_SIZE_MS);
        if (pageSizeMsBytes != null) {
            pageSizeMs = Bytes.toLong(pageSizeMsBytes);
            pageSizeMs = (long) (pageSizeMs * factor);
        }
        return pageSizeMs;
    }

    public static long getPageSizeMsForRegionScanner(Scan scan)  { return getPageSizeMs(scan, 0.6); }

    public static long getPageSizeMsForFilter(Scan scan) {
        return getPageSizeMs(scan, 0.3);
    }

    /**
     *  Put the attributes we want to annotate the WALs with (such as logical table name,
     *  tenant, DDL timestamp, etc) on the Scan object so that on the
     *  Ungrouped/GroupedAggregateCoprocessor side, we
     *  annotate the mutations with them, and then they get written into the WAL as part of
     *  the RegionObserver's doWALAppend hook.
     * @param table Table metadata for the target table/view of the write
     * @param scan Scan to trigger the server-side coproc
     */
    public static void setWALAnnotationAttributes(PTable table, Scan scan) {
        if (table.getTenantId() != null) {
            scan.setAttribute(MutationState.MutationMetadataType.TENANT_ID.toString(),
                    table.getTenantId().getBytes());
        }
        scan.setAttribute(MutationState.MutationMetadataType.SCHEMA_NAME.toString(),
                table.getSchemaName().getBytes());
        scan.setAttribute(MutationState.MutationMetadataType.LOGICAL_TABLE_NAME.toString(),
                table.getTableName().getBytes());
        scan.setAttribute(MutationState.MutationMetadataType.TABLE_TYPE.toString(),
                table.getType().getValue().getBytes());
        if (table.getLastDDLTimestamp() != null) {
            scan.setAttribute(MutationState.MutationMetadataType.TIMESTAMP.toString(),
                    Bytes.toBytes(table.getLastDDLTimestamp()));
        }

        if (table.isChangeDetectionEnabled()) {
            if (table.getExternalSchemaId() != null) {
                scan.setAttribute(MutationState.MutationMetadataType.EXTERNAL_SCHEMA_ID.toString(),
                    Bytes.toBytes(table.getExternalSchemaId()));
            }
        }
    }
    public static PageFilter removePageFilterFromFilterList(FilterList filterList) {
        Iterator<Filter> filterIterator = filterList.getFilters().iterator();
        while (filterIterator.hasNext()) {
            Filter filter = filterIterator.next();
            if (filter instanceof PageFilter) {
                filterIterator.remove();
                return (PageFilter) filter;
            } else if (filter instanceof FilterList) {
                PageFilter pageFilter = removePageFilterFromFilterList((FilterList) filter);
                if (pageFilter != null) {
                    return pageFilter;
                }
            }
        }
        return null;
    }

    // This method assumes that there is at most one instance of PageFilter in a scan
    public static PageFilter removePageFilter(Scan scan) {
        Filter filter = scan.getFilter();
        if (filter != null) {
            if (filter instanceof PagingFilter) {
                filter = ((PagingFilter) filter).getDelegateFilter();
                if (filter == null) {
                    return null;
                }
            }
            if (filter instanceof PageFilter) {
                scan.setFilter(null);
                return (PageFilter) filter;
            } else if (filter instanceof FilterList) {
                return removePageFilterFromFilterList((FilterList) filter);
            }
        }
        return null;
    }
    public static boolean hasCoprocessor(RegionCoprocessorEnvironment env,
            String CoprocessorClassName) {
        Collection<CoprocessorDescriptor> coprocessors =
                env.getRegion().getTableDescriptor().getCoprocessorDescriptors();
        for (CoprocessorDescriptor coprocessor:  coprocessors) {
            if (coprocessor.getClassName().equals(CoprocessorClassName)) {
                return true;
            }
        }
        return false;
    }
}
