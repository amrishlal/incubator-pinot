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
package org.apache.pinot.core.query.optimizer.statement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.pinot.common.function.scalar.ArithmeticFunctions;
import org.apache.pinot.common.function.scalar.DateTimeFunctions;
import org.apache.pinot.common.request.Expression;
import org.apache.pinot.common.request.ExpressionType;
import org.apache.pinot.common.request.Function;
import org.apache.pinot.common.request.Identifier;
import org.apache.pinot.common.request.Literal;
import org.apache.pinot.common.request.PinotQuery;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.common.utils.request.RequestUtils;
import org.apache.pinot.pql.parsers.pql2.ast.FilterKind;
import org.apache.pinot.pql.parsers.pql2.ast.FloatingPointLiteralAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.IntegerLiteralAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.LiteralAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.StringLiteralAstNode;
import org.apache.pinot.segment.spi.AggregationFunctionType;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.utils.Pair;


/**
 * This class will rewrite a query that has json path expressions into a query that uses JSON_EXTRACT_SCALAR and
 * JSON_MATCH functions.
 *
 * Example 1:
 *   From : SELECT jsonColumn.name.first
 *             FROM testTable
 *            WHERE jsonColumn.name.first IS NOT NULL
 *   TO   : SELECT JSON_EXTRACT_SCALAR(jsonColumn, '$.name.first', 'STRING', 'null') AS jsonColum.name.first
 *             FROM testTable
 *            WHERE JSON_MATCH('"$.name.first" IS NOT NULL')
 *
 * Output datatype of any json path expression is 'STRING'. However, if json path expression appears as an argument to
 * a numerical function, then output of json path expression is set to 'DOUBLE' as shown in the example below.
 *
 * Example 2:
 *   From:   SELECT MIN(jsonColumn.id - 5)
 *             FROM testTable
 *            WHERE jsonColumn.id IS NOT NULL
 *   To:     SELECT MIN(MINUS(JSON_EXTRACT_SCALAR(jsonColumn, '$.id', 'DOUBLE', Double.NEGATIVE_INFINITY),5)) AS min(minus(jsonColum.id, '5'))
 *             FROM testTable
 *            WHERE JSON_MATCH('"$.id" IS NOT NULL')
 *
 * Example 3:
 *   From:  SELECT jsonColumn.id, count(*)
 *             FROM testTable
 *            WHERE jsonColumn.name.first = 'Daffy' OR jsonColumn.id = 101
 *         GROUP BY jsonColumn.id
 *   To:    SELECT JSON_EXTRACT_SCALAR(jsonColumn, '$.id', 'STRING', 'null') AS jsonColumn.id, count(*)
 *             FROM testTable
 *            WHERE JSON_MATCH('"$.name.first" = ''Daffy''') OR JSON_MATCH('"$.id" = 101')
 *         GROUP BY JSON_EXTRACT_SCALAR(jsonColumn, '$.id', 'STRING', 'null');
 *
 * Example 4:
 *   From: SELECT jsonColumn.name.last, count(*)
 *            FROM testTable
 *        GROUP BY jsonColumn.name.last
 *          HAVING jsonColumn.name.last = 'mouse'
 *     To: SELECT JSON_EXTRACT_SCALAR(jsonColumn, '$.name.last', 'STRING', 'null') AS jsonColumn.name.last, count(*)
 *               FROM testTable
 *           GROUP BY JSON_EXTRACT_SCALAR(jsonColumn, '$.name.last', 'STRING', 'null')
 *             HAVING JSON_EXTRACT_SCALAR(jsonColumn, '$.name.last', 'STRING', 'null') = 'mouse'
 *
 * Notes:
 * 1) In a filter expression, if json path appears on the left-hand side, the right-hand side must be a literal. In
 *    future this can be changed to have any expression on the right-hand side by implementing a function that would
 *    convert any {@link Expression} into SQL fragment that can be used in JSON_MATCH. Currently only literals are
 *    converted into SQL fragments {see @link #getLiteralSQL} function.
 * 2) In WHERE clause each json path expression will be replaced with a JSON_MATCH function. If there are multiple
 *    json path expressions, they will be replaced by multiple JSON_MATCH functions. We currently don't fold multiple
 *    JSON_MATCH functions into a single JSON_MATCH_FUNCTION.
 */
public class JsonStatementOptimizer implements StatementOptimizer {

  /**
   * Maintain a list of numerical functions that requiring json path expression to output numerical values. This allows
   * us to implicitly convert the output of json path expression to DOUBLE. TODO: There are better ways of doing this
   * if we were to move to a new storage (currently STRING) for JSON or functions were to pre-declare their input
   * data types.
   */
  private static Set<String> numericalFunctions = getNumericalFunctionList();

  /**
   * A list of functions that require json path expression to output LONG value. This allows us to implicitly convert
   * the output of json path expression to LONG.
   */
  private static Set<String> datetimeFunctions = getDateTimeFunctionList();

  @Override
  public void optimize(PinotQuery query, @Nullable Schema schema) {
    // In SELECT clause, replace JSON path expressions with JSON_EXTRACT_SCALAR function with an alias.
    List<Expression> expressions = query.getSelectList();
    for (Expression expression : expressions) {
      Pair<String, Boolean> result = optimizeJsonIdentifier(expression, schema, DataSchema.ColumnDataType.STRING, true);
      if (expression.getType() == ExpressionType.FUNCTION && !expression.getFunctionCall().getOperator().equals("AS")
          && result.getSecond()) {
        // Since this is not an AS function (user-specified alias) and the function or its arguments contain json path
        // expression, set an alias for the expression after replacing json path expression with JSON_EXTRACT_SCALAR
        // function.
        Function aliasFunction = getAliasFunction(result.getFirst(), expression.getFunctionCall());
        expression.setFunctionCall(aliasFunction);
      }
    }

    // In WHERE clause, replace JSON path expressions with JSON_MATCH function.
    Expression filter = query.getFilterExpression();
    if (filter != null) {
      optimizeJsonPredicate(filter, schema);
    }

    // In GROUP BY clause, replace JSON path expressions with JSON_EXTRACT_SCALAR function without an alias.
    expressions = query.getGroupByList();
    if (expressions != null) {
      for (Expression expression : expressions) {
        optimizeJsonIdentifier(expression, schema, DataSchema.ColumnDataType.STRING, false);
      }
    }

    // In ORDER BY clause, replace JSON path expression with JSON_EXTRACT_SCALAR. This expression must match the
    // corresponding SELECT list expression except for the alias.
    expressions = query.getOrderByList();
    if (expressions != null) {
      for (Expression expression : expressions) {
        optimizeJsonIdentifier(expression, schema, DataSchema.ColumnDataType.STRING, false);
      }
    }

    // In HAVING clause, replace JSON path expressions with JSON_EXTRACT_SCALAR. This expression must match the
    // corresponding SELECT list expression except for the alias.
    Expression expression = query.getHavingExpression();
    if (expression != null) {
      optimizeJsonIdentifier(expression, schema, DataSchema.ColumnDataType.STRING, false);
    }
  }

  /**
   * Replace an json path expression with an aliased JSON_EXTRACT_SCALAR function.
   * @return A {@link Pair} of values where the first value is alias for the input expression and second
   * value indicates whether json path expression was found (true) or not (false) in the expression.
   */
  private static Pair<String, Boolean> optimizeJsonIdentifier(Expression expression, Schema schema, DataSchema.ColumnDataType dataType,
      boolean hasColumnAlias) {
    switch (expression.getType()) {
      case LITERAL:
        return new Pair<>(getLiteralSQL(expression.getLiteral(), true), false);
      case IDENTIFIER: {
        String[] parts = getIdentifierParts(expression.getIdentifier());
        boolean hasJsonPathExpression = false;
        String alias = expression.getIdentifier().getName();
        if (parts.length > 1 && isValidJSONColumn(parts[0], schema)) {
          // replace <column-name>.<json-path> with json_extract_scalar(<column-name>, '<json-path>', 'STRING', <JSON-null-value>)
          Function jsonExtractScalarFunction = getJsonExtractFunction(parts, dataType);
          expression.setIdentifier(null);
          expression.setType(ExpressionType.FUNCTION);
          expression.setFunctionCall(jsonExtractScalarFunction);
          hasJsonPathExpression = true;
        }
        return new Pair<>(alias, hasJsonPathExpression);
      }
      case FUNCTION: {
        Function function = expression.getFunctionCall();
        List<Expression> operands = function.getOperands();

        boolean hasJsonPathExpression = false;
        StringBuffer alias = new StringBuffer();
        if (function.getOperator().toUpperCase().equals("AS")) {
          // We don't need to compute an alias for AS function since AS function defines its own alias.
          hasJsonPathExpression = optimizeJsonIdentifier(operands.get(0), schema, dataType, false).getSecond();
          alias.append(function.getOperands().get(1).getIdentifier().getName());
        } else {
          // For all functions besides AS function, process the operands and compute the alias that will be used if
          // the function contains a json path expression.
          alias.append(function.getOperator().toLowerCase(Locale.ROOT)).append("(");
          dataType = DataSchema.ColumnDataType.STRING;
          if (numericalFunctions.contains(function.getOperator().toUpperCase(Locale.ROOT))) {
            dataType = DataSchema.ColumnDataType.DOUBLE;
          } else if (datetimeFunctions.contains(function.getOperator().toUpperCase(Locale.ROOT))) {
            dataType = DataSchema.ColumnDataType.LONG;
          }

          for (int i = 0; i < operands.size(); ++i) {
            // recursively check to see if there is a <json-column>.<json-path> identifier in this expression.
            Pair<String, Boolean> operandResult = optimizeJsonIdentifier(operands.get(i), schema, dataType, false);
            hasJsonPathExpression |= operandResult.getSecond();
            if (i > 0) {
              alias.append(",");
            }
            alias.append(operandResult.getFirst());
          }
          alias.append(")");
        }

        return new Pair<>(alias.toString(), hasJsonPathExpression);
      }
    }

    return new Pair<>("", false);
  }

  /**
   * Example:
   *   Input:
   *     alias   : "jsoncolumn.x.y.z",
   *     function: JSON_EXTRACT_SCALAR('jsoncolumn', 'x.y.z', 'STRING', 'null')
   *   Output: AS(JSON_EXTRACT_SCALAR('jsoncolumn', 'x.y.z', 'STRING', 'null'), 'jsoncolumn.x.y.z')
   *
   * @return a Function with "AS" operator that wraps another function.
   */
  private static Function getAliasFunction(String alias, Function function) {
    Function aliasFunction = new Function("AS");

    List<Expression> operands = new ArrayList<>();
    Expression expression = new Expression(ExpressionType.FUNCTION);
    expression.setFunctionCall(function);
    operands.add(expression);
    operands.add(RequestUtils.createIdentifierExpression(alias));
    aliasFunction.setOperands(operands);

    return aliasFunction;
  }

  /**
   * Example:
   * Input : ["jsoncolumn", "x","y","z[2]"]
   * Output: JSON_EXTRACT_SCALAR('jsoncolumn','$.x.y.z[2]','STRING','null')
   *
   * @param parts All the subparts of a fully qualified identifier (json path expression).
   * @param dataType Output datatype of JSON_EXTRACT_SCALAR function.
   * @return a Function with JSON_EXTRACT_SCALAR operator created using parts of fully qualified identifier name.
   */
  private static Function getJsonExtractFunction(String[] parts, DataSchema.ColumnDataType dataType) {
    Function jsonExtractScalarFunction = new Function("JSON_EXTRACT_SCALAR");
    List<Expression> operands = new ArrayList<>();
    operands.add(RequestUtils.createIdentifierExpression(parts[0]));
    operands.add(RequestUtils.createLiteralExpression(new StringLiteralAstNode(getJsonPath(parts, false))));
    operands
        .add(RequestUtils.createLiteralExpression(new StringLiteralAstNode(dataType.toString())));

    LiteralAstNode defaultValue;
    switch (dataType) {
      case INT:
        defaultValue = new IntegerLiteralAstNode(FieldSpec.DEFAULT_DIMENSION_NULL_VALUE_OF_INT);
        break;
      case LONG:
        defaultValue = new IntegerLiteralAstNode(FieldSpec.DEFAULT_DIMENSION_NULL_VALUE_OF_LONG);
        break;
      case FLOAT:
        defaultValue = new FloatingPointLiteralAstNode(FieldSpec.DEFAULT_DIMENSION_NULL_VALUE_OF_FLOAT);
        break;
      case DOUBLE:
        defaultValue = new FloatingPointLiteralAstNode(FieldSpec.DEFAULT_DIMENSION_NULL_VALUE_OF_DOUBLE);
        break;
      case STRING:
      default:
        defaultValue = new StringLiteralAstNode(FieldSpec.DEFAULT_DIMENSION_NULL_VALUE_OF_STRING);
    }

    operands.add(RequestUtils.createLiteralExpression(defaultValue));
    jsonExtractScalarFunction.setOperands(operands);
    return jsonExtractScalarFunction;
  }

  /**
   * Example 1:
   * Input : "jsonColumn.name.first = 'daffy'"
   * Output: "JSON_MATCH(jsonColumn, '\"$.name.first\" = ''daffy''').
   *
   * Example 2:
   * Input : "jsonColumn.id = 4"
   * Output: "JSON_MATCH(jsonColumn, '\"$.id\" = 4')
   */
  private static void optimizeJsonPredicate(Expression expression, Schema schema) {
    if (expression.getType() == ExpressionType.FUNCTION) {
      Function function = expression.getFunctionCall();
      String operator = function.getOperator();
      FilterKind kind = FilterKind.valueOf(operator);
      List<Expression> operands = function.getOperands();
      switch (kind) {
        case AND:
        case OR: {
          operands.forEach(operand -> optimizeJsonPredicate(operand, schema));
          break;
        }
        case EQUALS:
        case NOT_EQUALS:
        case GREATER_THAN:
        case GREATER_THAN_OR_EQUAL:
        case LESS_THAN:
        case LESS_THAN_OR_EQUAL: {
          Expression left = operands.get(0);
          Expression right = operands.get(1);
          if (left.getType() == ExpressionType.IDENTIFIER && right.getType() == ExpressionType.LITERAL) {
            String[] parts = getIdentifierParts(left.getIdentifier());
            if (parts.length > 1 && isValidJSONColumn(parts[0], schema)) {
              Function jsonMatchFunction = new Function("JSON_MATCH");

              List<Expression> jsonMatchFunctionOperands = new ArrayList<>();
              jsonMatchFunctionOperands.add(RequestUtils.createIdentifierExpression(parts[0]));
              jsonMatchFunctionOperands.add(RequestUtils.createLiteralExpression(new StringLiteralAstNode(
                  getJsonPath(parts, true) + getOperatorSQL(kind) + getLiteralSQL(right.getLiteral(), false))));
              jsonMatchFunction.setOperands(jsonMatchFunctionOperands);

              expression.setFunctionCall(jsonMatchFunction);
            }
          }
          break;
        }
        case IS_NULL:
        case IS_NOT_NULL: {
          Expression operand = operands.get(0);
          if (operand.getType() == ExpressionType.IDENTIFIER) {
            String[] parts = getIdentifierParts(operand.getIdentifier());
            if (parts.length > 1 && isValidJSONColumn(parts[0], schema)) {
              Function jsonMatchFunction = new Function("JSON_MATCH");

              List<Expression> jsonMatchFunctionOperands = new ArrayList<>();
              jsonMatchFunctionOperands.add(RequestUtils.createIdentifierExpression(parts[0]));
              jsonMatchFunctionOperands.add(RequestUtils.createLiteralExpression(
                  new StringLiteralAstNode(getJsonPath(parts, true) + getOperatorSQL(kind))));
              jsonMatchFunction.setOperands(jsonMatchFunctionOperands);

              expression.setFunctionCall(jsonMatchFunction);
            }
          }
          break;
        }
      }
    }
  }

  /**
   *  @return A string array containing all the parts of an identifier. An identifier may have one or more parts that
   *  are joined together using <DOT>. For example the identifier "testTable.jsonColumn.name.first" consists up of
   *  "testTable" (name of table), "jsonColumn" (name of column), "name" (json path), and "first" (json path). The last
   *  two parts when joined together (name.first) represent a JSON path expression.
   */
  private static String[] getIdentifierParts(Identifier identifier) {
    return identifier.getName().split("\\.");
  }

  /**
   * Builds a json path expression when given identifier parts. For example,given [jsonColumn, name, first], this
   * function will return "$.name.first" as json path expression.
   * @param parts identifier parts
   * @param applyDoubleQuote delimit json path with double quotes if true; otherwise, don't delimit json path.
   * @return JSON path expression associated with the given identifier parts.
   */
  private static String getJsonPath(String[] parts, boolean applyDoubleQuote) {
    StringBuilder builder = new StringBuilder();
    if (applyDoubleQuote) {
      builder.append("\"");
    }

    builder.append("$");
    for (int i = 1; i < parts.length; i++) {
      builder.append(".").append(parts[i]);
    }

    if (applyDoubleQuote) {
      builder.append("\"");
    }

    return builder.toString();
  }

  /** @return true if specified column has column datatype of JSON; otherwise, return false */
  private static boolean isValidJSONColumn(String columnName, Schema schema) {
    return schema.hasColumn(columnName) && schema.getFieldSpecFor(columnName).getDataType()
        .equals(FieldSpec.DataType.JSON);
  }

  /** @return symbolic representation of function operator delimited by spaces. */
  private static String getOperatorSQL(FilterKind kind) {
    switch (kind) {
      case EQUALS:
        return " = ";
      case NOT_EQUALS:
        return " != ";
      case GREATER_THAN:
        return " > ";
      case GREATER_THAN_OR_EQUAL:
        return " >= ";
      case LESS_THAN:
        return " < ";
      case LESS_THAN_OR_EQUAL:
        return " <= ";
      case IN:
        return " IN ";
      case NOT_IN:
        return " NOT IN ";
      case IS_NULL:
        return " IS NULL";
      case IS_NOT_NULL:
        return " IS NOT NULL";
    }
    return " ";
  }

  /**
   * @param literal {@link Literal} to convert to a {@link String}.
   * @param aliasing When true, generate string for use in an alias; otherwise, generate SQL string representation.
   * @return Literal value converted into either an alias name or an SQL string. BYTE, STRING, and BINARY values are
   * delimited by quotes in SQL and everything is delimited by quotes for use in alias.
   * */
  private static String getLiteralSQL(Literal literal, boolean aliasing) {
    StringBuffer result = new StringBuffer();
    result.append(aliasing ? "'" : "");
    switch (literal.getSetField()) {
      case BOOL_VALUE:
        result.append(String.valueOf(literal.getBinaryValue()));
        break;
      case BYTE_VALUE:
        result.append(aliasing ? String.valueOf(literal.getByteValue()) : "'" + String.valueOf(literal.getByteValue()) + "'");
        break;
      case SHORT_VALUE:
        result.append(aliasing ? String.valueOf(literal.getShortValue()) : "'" + String.valueOf(literal.getShortValue()) + "'");
        break;
      case INT_VALUE:
        result.append(String.valueOf(literal.getIntValue()));
        break;
      case LONG_VALUE:
        result.append(String.valueOf(literal.getLongValue()));
        break;
      case DOUBLE_VALUE:
        result.append(String.valueOf(literal.getDoubleValue()));
        break;
      case STRING_VALUE:
        result.append("'" + literal.getStringValue() + "'");
        break;
      case BINARY_VALUE:
        result.append(aliasing ? String.valueOf(literal.getBinaryValue()) : "'" + String.valueOf(literal.getBinaryValue()) + "'");
        break;
    }

    result.append(aliasing ? "'" : "");
    return result.toString();
  }

  /** List of function that require input to be in a number. */
  public static Set<String> getNumericalFunctionList() {
    Set<String> set = new HashSet<>();
    // Include all ArithmeticFunctions functions
    Method[] methods = ArithmeticFunctions.class.getDeclaredMethods();
    for (Method method : methods) {
      set.add(method.getName().toUpperCase(Locale.ROOT));
    }

    // Include all aggregation functions
    AggregationFunctionType[] aggs = AggregationFunctionType.values();
    for (AggregationFunctionType agg : aggs) {
      set.add(agg.getName().toUpperCase(Locale.ROOT));
    }

    return set;
  }

  /** List of DateTime functions which require input to be of long type. */
  public static Set<String> getDateTimeFunctionList() {
    Set<String> set = new HashSet<>();
    Method[] methods = DateTimeFunctions.class.getDeclaredMethods();
    for (Method method : methods) {
      set.add(method.getName().toUpperCase(Locale.ROOT));
    }

    return set;
  }
}