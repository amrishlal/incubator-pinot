/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.segment.creator;

import java.util.HashMap;
import java.util.Map;
import org.apache.pinot.core.common.DataSource;
import org.apache.pinot.core.indexsegment.mutable.IntermediateSegment;
import org.apache.pinot.core.realtime.converter.stats.MutableColumnStatistics;


public class IntermediateSegmentStatsContainer implements SegmentPreIndexStatsContainer {
  private final IntermediateSegment _intermediateSegment;
  private final Map<String, ColumnStatistics> _columnStatisticsMap = new HashMap<>();

  public IntermediateSegmentStatsContainer(IntermediateSegment intermediateSegment) {
    _intermediateSegment = intermediateSegment;

    // Create all column statistics
    for (String columnName : intermediateSegment.getPhysicalColumnNames()) {
      DataSource dataSource = intermediateSegment.getDataSource(columnName);
      // Always use dictionary for intermediate segment stats
      _columnStatisticsMap.put(columnName, new MutableColumnStatistics(dataSource, null));
    }
  }

  @Override
  public ColumnStatistics getColumnProfileFor(String column) {
    return _columnStatisticsMap.get(column);
  }

  @Override
  public int getTotalDocCount() {
    return _intermediateSegment.getNumDocsIndexed();
  }
}