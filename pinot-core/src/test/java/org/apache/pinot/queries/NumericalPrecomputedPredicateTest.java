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
package org.apache.pinot.queries;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.common.segment.ReadMode;
import org.apache.pinot.core.common.Operator;
import org.apache.pinot.core.data.readers.GenericRowRecordReader;
import org.apache.pinot.core.indexsegment.IndexSegment;
import org.apache.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import org.apache.pinot.core.indexsegment.immutable.ImmutableSegment;
import org.apache.pinot.core.indexsegment.immutable.ImmutableSegmentLoader;
import org.apache.pinot.core.operator.blocks.IntermediateResultsBlock;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.query.request.context.predicate.Predicate;
import org.apache.pinot.core.query.request.context.utils.QueryContextConverterUtils;
import org.apache.pinot.core.segment.creator.impl.SegmentIndexCreationDriverImpl;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Test cases verifying evaluation of predicate can can be precomputed at compile time.
 */
public class NumericalPrecomputedPredicateTest extends BaseQueriesTest {
  private static final File INDEX_DIR = new File(FileUtils.getTempDirectory(), "NumericalPredicateTest");
  private static final String RAW_TABLE_NAME = "testTable";
  private static final String SEGMENT_NAME = "testSegment";
  private static final int NUM_RECORDS = 10;

  private static final String INT_COLUMN = "intColumn";
  private static final String ANOTHER_INT_COLUMN = "anotherIntColumn";
  private static final String LONG_COLUMN = "longColumn";
  private static final String FLOAT_COLUMN = "floatColumn";
  private static final String DOUBLE_COLUMN = "doubleColumn";
  private static final String STRING_COLUMN = "stringColumn";
  private static final Schema SCHEMA =
      new Schema.SchemaBuilder().addSingleValueDimension(INT_COLUMN, FieldSpec.DataType.INT)
      .addSingleValueDimension(ANOTHER_INT_COLUMN, FieldSpec.DataType.INT)
      .addSingleValueDimension(LONG_COLUMN, FieldSpec.DataType.LONG)
      .addSingleValueDimension(FLOAT_COLUMN, FieldSpec.DataType.FLOAT)
      .addSingleValueDimension(DOUBLE_COLUMN, FieldSpec.DataType.DOUBLE)
      .addSingleValueDimension(STRING_COLUMN, FieldSpec.DataType.STRING).build();
  private static final TableConfig TABLE_CONFIG =
      new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME).build();

  private IndexSegment _indexSegment;
  private List<IndexSegment> _indexSegments;

  @Override
  protected String getFilter() {
    return "";
  }

  @Override
  protected IndexSegment getIndexSegment() {
    return _indexSegment;
  }

  GenericRow createRecord(int intValue, int anotherIntValue, long longValue, float floatValue, double doubleValue, String stringValue) {
    GenericRow record = new GenericRow();
    record.putValue(INT_COLUMN, intValue);
    record.putValue(ANOTHER_INT_COLUMN, anotherIntValue);
    record.putValue(LONG_COLUMN, longValue);
    record.putValue(FLOAT_COLUMN, floatValue);
    record.putValue(DOUBLE_COLUMN, doubleValue);
    record.putValue(STRING_COLUMN, stringValue);

    return record;
  }

  @Override
  protected List<IndexSegment> getIndexSegments() {
    return _indexSegments;
  }

  @BeforeClass
  public void setUp()
      throws Exception {
    FileUtils.deleteDirectory(INDEX_DIR);

    List<GenericRow> records = new ArrayList<>(NUM_RECORDS);
    records.add(createRecord(12, 5,1609046259848l, 12.1f, 12.01d, "scrooge"));
    records.add(createRecord(1, 0,1609046249848l, 1.1f, 1.11d, "mickey"));
    records.add(createRecord(25,30, 1609046359848l, 25.1f, 25.01d, "minnie"));
    records.add(createRecord(40, 20, 1609046659848l, 40.1f, 40.11d, "donald"));
    records.add(createRecord(15, 20, 1609046219848l, 15.1f, 10.01d, "goofy"));
    records.add(createRecord(45,15, 1609046279848l, 45.1f, 45.11d, "daffy1"));
    records.add(createRecord(45,90, 1609046279848l, -45.1f, 45.11d, "daffy2"));
    records.add(createRecord(45,38, 1609046279848l, -15.1f, 45.11d, "daffy3"));
    records.add(createRecord(45,105, 1609046279848l, -2.1f, 45.11d, "daffy4"));
    records.add(createRecord(-12,105, 1609046279848l, -2.1f, 45.11d, "daffy5"));
    records.add(createRecord(Integer.MAX_VALUE, 0, Long.MAX_VALUE, 47.1f, 47.01d, "pluto"));
    records.add(createRecord(Integer.MIN_VALUE, 0, Long.MIN_VALUE, 49.1f, 49.11d, "daisy"));

    SegmentGeneratorConfig segmentGeneratorConfig = new SegmentGeneratorConfig(TABLE_CONFIG, SCHEMA);
    segmentGeneratorConfig.setTableName(RAW_TABLE_NAME);
    segmentGeneratorConfig.setSegmentName(SEGMENT_NAME);
    segmentGeneratorConfig.setOutDir(INDEX_DIR.getPath());

    SegmentIndexCreationDriverImpl driver = new SegmentIndexCreationDriverImpl();
    driver.init(segmentGeneratorConfig, new GenericRowRecordReader(records));
    driver.build();

    ImmutableSegment immutableSegment = ImmutableSegmentLoader.load(new File(INDEX_DIR, SEGMENT_NAME), ReadMode.mmap);
    _indexSegment = immutableSegment;
    _indexSegments = Arrays.asList(immutableSegment, immutableSegment);
  }

  /** Predicate precomputed as false during compile time */
  @Test
  public void testIntColumnEqualToDecimalValue() {
    // An Integer column will never have a decimal value; hence, a predicate, that compares an Integer column with a
    // decimal value will always evaluate to false. Check if we are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE intColumn = 12.1");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    Assert.assertNotNull(predicate.getPrecomputedResult());
    Assert.assertFalse(predicate.getPrecomputedResult());
    Assert.assertEquals(predicate.toString(), "intColumn = '12.1'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 0l);
  }

  /** Predicate precomputed as false during compile time */
  @Test
  public void testIntColumnNotEqualToDecimalValue() {
    // An Integer column will never have a decimal value; hence, a predicate, that compares an Integer column with a
    // decimal value will always evaluate to false. Check if we are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE intColumn != 12.1");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    Assert.assertNotNull(predicate.getPrecomputedResult());
    Assert.assertTrue(predicate.getPrecomputedResult());
    Assert.assertEquals(predicate.toString(), "intColumn != '12.1'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 12l);
  }

  @Test
  public void testLongColumnEqualToDecimalValue() {
    // A Long column will never have a decimal value; hence, a predicate, that compares a Long column with a decimal
    // value will always evaluate to false. Check if we are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE longColumn = 1609046359848.1");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    Assert.assertNotNull(predicate.getPrecomputedResult());
    Assert.assertFalse(predicate.getPrecomputedResult());
    Assert.assertEquals(predicate.toString(), "longColumn = '1.6090463598481E12'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 0l);
  }

  @Test
  public void testLongColumnNotEqualToDecimalValue() {
    // A Long column will never have a decimal value; hence, a predicate, that compares a Long column with a decimal
    // value will always evaluate to false. Check if we are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE longColumn != 1609046359848.1");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    Assert.assertNotNull(predicate.getPrecomputedResult());
    Assert.assertTrue(predicate.getPrecomputedResult());
    Assert.assertEquals(predicate.toString(), "longColumn != '1.6090463598481E12'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 12l);
  }

  @Test
  public void testFloatColumnEqualToDecimalValue() {
    // A Float column will never have a value with more than 6 decimal places; hence, a predicate, that compares a
    // Float column with a value that has more than 6 decimal places, will always evaluate to false. Check if we
    // are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE floatColumn = -45.12345678");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    Assert.assertNotNull(predicate.getPrecomputedResult());
    Assert.assertFalse(predicate.getPrecomputedResult());
    Assert.assertEquals(predicate.toString(), "floatColumn = '-45.12345678'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 0l);
  }

  @Test
  public void testFloatColumnNotEqualToDecimalValue() {
    // A Float column will never have a value with more than 6 decimal places; hence, a predicate, that compares a
    // Float column with a value that has more than 6 decimal places, will always evaluate to false. Check if we
    // are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE floatColumn != -45.12345678");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    Assert.assertNotNull(predicate.getPrecomputedResult());
    Assert.assertTrue(predicate.getPrecomputedResult());
    Assert.assertEquals(predicate.toString(), "floatColumn != '-45.12345678'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 12l);
  }

  @Test
  public void testDoubleColumnEqualToDecimalValue() {
    // A double column will never have a value with more than 16 decimal places; hence, a predicate, that compares a
    // Double column with a value that has more than 16 decimal places, will always evaluate to false. Check if we
    // are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE doubleColumn = 25.123456781234567812345678");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    //Assert.assertNotNull(predicate.getPrecomputedResult());
    //Assert.assertFalse(predicate.getPrecomputedResult());
    //Assert.assertEquals(predicate.toString(), "doubleColumn == '25.123456781234567812345678'");
    Assert.assertEquals(predicate.toString(), "doubleColumn = '25.12345678123457'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 0l);
  }

  @Test
  public void testDoubleColumnNotEqualToDecimalValue() {
    // A double column will never have a value with more than 16 decimal places; hence, a predicate, that compares a
    // Double column with a value that has more than 16 decimal places, will always evaluate to false. Check if we
    // are able to precompute the result during compile time.
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromSQL("SELECT count(*) FROM testTable WHERE doubleColumn != 25.123456781234567812345678");
    Operator operator =  PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();

    Predicate predicate = queryContext.getFilter().getPredicate();
    //Assert.assertNotNull(predicate.getPrecomputedResult());
    //Assert.assertFalse(predicate.getPrecomputedResult());
    //Assert.assertEquals(predicate.toString(), "doubleColumn == '25.123456781234567812345678'");
    Assert.assertEquals(predicate.toString(), "doubleColumn != '25.12345678123457'");

    IntermediateResultsBlock block = (IntermediateResultsBlock) operator.nextBlock();
    List<Object> result = block.getAggregationResult();
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0), 12l);
  }

  @AfterClass
  public void tearDown()
      throws IOException {
    _indexSegment.destroy();
    FileUtils.deleteDirectory(INDEX_DIR);
  }
}
