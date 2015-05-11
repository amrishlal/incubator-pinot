/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.segment.creator.impl.stats;

import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeSet;

import com.linkedin.pinot.common.data.FieldSpec;
import com.linkedin.pinot.core.segment.creator.AbstractColumnStatisticsCollector;


/**
 * @author Dhaval Patel<dpatel@linkedin.com>
 * Nov 7, 2014
 */

public class FloatColumnPreIndexStatsCollector extends AbstractColumnStatisticsCollector {

  private Float min = Float.MAX_VALUE;
  private Float max = Float.MIN_VALUE;
  private final SortedSet<Float> floatSet;
  private Float[] sortedFloatList;
  private boolean hasNull = false;
  private boolean sealed = false;

  public FloatColumnPreIndexStatsCollector(FieldSpec spec) {
    super(spec);
    floatSet = new TreeSet<Float>();
  }

  @Override
  public void collect(Object entry) {
    if (entry instanceof Object[]) {
      for (final Object e : (Object[]) entry) {
        floatSet.add(((Float) e).floatValue());
      }
      if (maxNumberOfMultiValues < ((Object[]) entry).length) {
        maxNumberOfMultiValues = ((Object[]) entry).length;
      }
      updateTotalNumberOfEntries((Object[]) entry);
      return;
    }

    addressSorted(entry);
    floatSet.add(((Float) entry).floatValue());
  }

  @Override
  public Float getMinValue() throws Exception {
    if (sealed) {
      return min;
    }
    throw new IllegalAccessException("you must seal the collector first before asking for min value");
  }

  @Override
  public Float getMaxValue() throws Exception {
    if (sealed) {
      return max;
    }
    throw new IllegalAccessException("you must seal the collector first before asking for min value");
  }

  @Override
  public Object[] getUniqueValuesSet() throws Exception {
    if (sealed) {
      return sortedFloatList;
    }
    throw new IllegalAccessException("you must seal the collector first before asking for min value");
  }

  @Override
  public int getCardinality() throws Exception {
    if (sealed) {
      return floatSet.size();
    }
    throw new IllegalAccessException("you must seal the collector first before asking for min value");
  }

  @Override
  public boolean hasNull() {
    return false;
  }

  @Override
  public void seal() {
    sealed = true;
    sortedFloatList = new Float[floatSet.size()];
    floatSet.toArray(sortedFloatList);
    Arrays.sort(sortedFloatList);

    if (sortedFloatList.length == 0) {
      min = null;
      max = null;
      return;
    }

    min = sortedFloatList[0];
    if (sortedFloatList.length == 0) {
      max = sortedFloatList[0];
    } else {
      max = sortedFloatList[sortedFloatList.length - 1];
    }

  }

}
