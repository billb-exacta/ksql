/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.streams;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.NullPointerTester.Visibility;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.codegen.ExpressionMetadata;
import io.confluent.ksql.execution.expression.tree.DereferenceExpression;
import io.confluent.ksql.execution.expression.tree.LongLiteral;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.util.StructKeyUtil;
import io.confluent.ksql.logging.processing.ProcessingLogConfig;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.logging.processing.RecordProcessingError;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlStruct;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GroupByParamsFactoryTest {

  private static final ColumnName COL3 = ColumnName.of("COL3");
  private static final SqlStruct COL3_TYPE = SqlTypes.struct()
      .field("someField", SqlTypes.BIGINT)
      .build();

  private static final LogicalSchema SOURCE_SCHEMA = LogicalSchema.builder()
      .valueColumn(ColumnName.of("v0"), SqlTypes.DOUBLE)
      .valueColumn(ColumnName.of("KSQL_COL_0"), SqlTypes.DOUBLE)
      .valueColumn(COL3, COL3_TYPE)
      .build();

  @Mock
  private ExpressionMetadata groupBy0;
  @Mock
  private ExpressionMetadata groupBy1;
  @Mock
  private GenericRow value;
  @Mock
  private ProcessingLogger logger;
  @Captor
  private ArgumentCaptor<Function<ProcessingLogConfig, SchemaAndValue>> msgCaptor;

  private GroupByParams singleParams;
  private GroupByParams multiParams;

  @Before
  public void setUp() {
    when(groupBy0.getExpression()).thenReturn(new LongLiteral(0));
    when(groupBy0.getExpressionType()).thenReturn(SqlTypes.INTEGER);

    singleParams = GroupByParamsFactory
        .build(SOURCE_SCHEMA, ImmutableList.of(groupBy0), logger);

    multiParams = GroupByParamsFactory
        .build(SOURCE_SCHEMA, ImmutableList.of(groupBy0, groupBy1), logger);

    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(0);
    when(groupBy1.evaluate(any(), any(), any(), any())).thenReturn(0L);
  }

  @SuppressWarnings("UnstableApiUsage")
  @Test
  public void shouldThrowOnNullParam() {
    new NullPointerTester()
        .setDefault(List.class, ImmutableList.of(groupBy0))
        .setDefault(LogicalSchema.class, SOURCE_SCHEMA)
        .setDefault(SqlType.class, SqlTypes.BIGINT)
        .testStaticMethods(GroupByParamsFactory.class, Visibility.PACKAGE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldThrowOnEmptyParam() {
    GroupByParamsFactory
        .build(SOURCE_SCHEMA, Collections.emptyList(), logger);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldInvokeSingleEvaluatorsWithCorrectParams() {
    // When:
    singleParams.getMapper().apply(value);

    // Then:
    final ArgumentCaptor<Supplier<String>> errorMsgCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(groupBy0).evaluate(eq(value), any(), eq(logger), errorMsgCaptor.capture());

    assertThat(errorMsgCaptor.getValue().get(), is(
        "Error calculating group-by column with index 0. The source row will be excluded from the table."
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldInvokeMultipleEvaluatorsWithCorrectParams() {
    // When:
    multiParams.getMapper().apply(value);

    // Then:
    final ArgumentCaptor<Supplier<String>> errorMsgCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(groupBy0).evaluate(eq(value), any(), eq(logger), errorMsgCaptor.capture());
    verify(groupBy1).evaluate(eq(value), any(), eq(logger), errorMsgCaptor.capture());

    final List<String> errorMsgs = errorMsgCaptor.getAllValues().stream()
        .map(Supplier::get)
        .collect(Collectors.toList());

    assertThat(errorMsgs, contains(
        "Error calculating group-by column with index 0. The source row will be excluded from the table.",
        "Error calculating group-by column with index 1. The source row will be excluded from the table."
    ));
  }

  @Test
  public void shouldGenerateSingleExpressionGroupByKey() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(10);

    // When:
    final Struct result = singleParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(structKey(ColumnName.of("KSQL_COL_1"), 10)));
  }

  @Test
  public void shouldGenerateMultiExpressionGroupByKey() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(99);
    when(groupBy1.evaluate(any(), any(), any(), any())).thenReturn(-100L);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(structKey(ColumnName.of("KSQL_COL_1"), "99|+|-100")));
  }

  @Test
  public void shouldReturnNullIfSingleExpressionResolvesToNull() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    final Struct result = singleParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(nullValue()));
  }

  @Test
  public void shouldLogProcessingErrorIfSingleExpressionResolvesToNull() {
    // Given
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    singleParams.getMapper().apply(value);

    // Then:
    verify(logger).error(
        RecordProcessingError.recordProcessingError(
            "Group-by column with index 0 resolved to null. "
                + "The source row will be excluded from the table.",
            value
        )
    );
  }

  @Test
  public void shouldReturnNullIfAnyMultiExpressionResolvesToNull() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(nullValue()));
  }

  @Test
  public void shouldLogProcessingErrorIfAnyMultiExpressionResolvesToNull() {
    // Given
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    multiParams.getMapper().apply(value);

    // Then:
    verify(logger).error(
        RecordProcessingError.recordProcessingError(
            "Group-by column with index 0 resolved to null. "
                + "The source row will be excluded from the table.",
            value
        )
    );
  }

  @Test
  public void shouldUseNullInGroupByIfOneExpressionFailsOrReturnsNullInMulti() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(nullValue()));
  }

  @Test
  public void shouldSetKeyNameFromSingleGroupByColumnName() {
    // When:
    when(groupBy0.getExpression())
        .thenReturn(new UnqualifiedColumnReferenceExp(ColumnName.of("Bob")));

    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0));

    // Then:
    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("Bob"), SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldSetKeyNameFromSingleGroupByFieldName() {
    // When:
    when(groupBy0.getExpression()).thenReturn(new DereferenceExpression(
        Optional.empty(),
        new UnqualifiedColumnReferenceExp(COL3),
        "someField"
    ));

    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0));

    // Then:
    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("someField"), SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldGenerateKeyNameFromSingleGroupByOtherExpressionType() {
    // When:
    when(groupBy0.getExpression())
        .thenReturn(new LongLiteral(1));

    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0));

    // Then:
    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("KSQL_COL_1"), SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldGenerateKeyNameForMultiGroupBys() {
    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0, groupBy1));

    // Then:
    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("KSQL_COL_1"), SqlTypes.STRING)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  private static Struct structKey(final ColumnName keyColName, final String keyValue) {
    return StructKeyUtil
        .keyBuilder(keyColName, SqlTypes.STRING)
        .build(keyValue, 0);
  }

  private static Struct structKey(final ColumnName keyColName, final int keyValue) {
    return StructKeyUtil
        .keyBuilder(keyColName, SqlTypes.INTEGER)
        .build(keyValue, 0);
  }
}
