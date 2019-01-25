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
package org.apache.pinot.core.operator.filter;

import org.apache.pinot.core.common.DataSource;
import org.apache.pinot.core.operator.blocks.FilterBlock;
import org.apache.pinot.core.operator.filter.predicate.PredicateEvaluator;

public class ScanBasedMatchesFilterOperator  extends BaseFilterOperator {

  public ScanBasedMatchesFilterOperator(PredicateEvaluator predicateEvaluator,
      DataSource dataSource, int startDocId, int endDocId) {
    // TODO Auto-generated constructor stub
  }

  @Override
  protected FilterBlock getNextBlock() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public String getOperatorName() {
    // TODO Auto-generated method stub
    return null;
  }

}
