/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connect.plugin

import com.google.protobuf

import org.apache.spark.{SparkContext, SparkEnv, SparkException}
import org.apache.spark.connect.proto
import org.apache.spark.connect.proto.Relation
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.catalyst.expressions.{Alias, Expression}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.connect.common.InvalidPlanInput
import org.apache.spark.sql.connect.config.Connect
import org.apache.spark.sql.connect.planner.{SparkConnectPlanner, SparkConnectPlanTest}
import org.apache.spark.sql.test.SharedSparkSession

class DummyPlugin extends RelationPlugin {
  override def transform(
      relation: protobuf.Any,
      planner: SparkConnectPlanner): Option[LogicalPlan] = None
}

class DummyExpressionPlugin extends ExpressionPlugin {
  override def transform(
      relation: protobuf.Any,
      planner: SparkConnectPlanner): Option[Expression] = None
}

class DummyPluginNoTrivialCtor(id: Int) extends RelationPlugin {
  override def transform(
      relation: protobuf.Any,
      planner: SparkConnectPlanner): Option[LogicalPlan] = None
}

class DummyPluginInstantiationError extends RelationPlugin {

  throw new ArrayIndexOutOfBoundsException("Bad Plugin Error")

  override def transform(
      relation: protobuf.Any,
      planner: SparkConnectPlanner): Option[LogicalPlan] = None
}

class ExampleRelationPlugin extends RelationPlugin {
  override def transform(
      relation: protobuf.Any,
      planner: SparkConnectPlanner): Option[LogicalPlan] = {

    if (!relation.is(classOf[proto.ExamplePluginRelation])) {
      return None
    }
    val plugin = relation.unpack(classOf[proto.ExamplePluginRelation])
    Some(planner.transformRelation(plugin.getInput))
  }
}

class ExampleExpressionPlugin extends ExpressionPlugin {
  override def transform(
      relation: protobuf.Any,
      planner: SparkConnectPlanner): Option[Expression] = {
    if (!relation.is(classOf[proto.ExamplePluginExpression])) {
      return None
    }
    val exp = relation.unpack(classOf[proto.ExamplePluginExpression])
    Some(
      Alias(planner.transformExpression(exp.getChild), exp.getCustomField)(explicitMetadata =
        None))
  }
}

class ExampleCommandPlugin extends CommandPlugin {
  override def process(command: protobuf.Any, planner: SparkConnectPlanner): Option[Unit] = {
    if (!command.is(classOf[proto.ExamplePluginCommand])) {
      return None
    }
    val cmd = command.unpack(classOf[proto.ExamplePluginCommand])
    assert(planner.session != null)
    SparkContext.getActive.get.setLocalProperty("testingProperty", cmd.getCustomField)
    Some()
  }
}

class SparkConnectPluginRegistrySuite extends SharedSparkSession with SparkConnectPlanTest {

  override def beforeEach(): Unit = {
    if (SparkEnv.get.conf.contains(Connect.CONNECT_EXTENSIONS_EXPRESSION_CLASSES)) {
      SparkEnv.get.conf.remove(Connect.CONNECT_EXTENSIONS_EXPRESSION_CLASSES)
    }
    if (SparkEnv.get.conf.contains(Connect.CONNECT_EXTENSIONS_RELATION_CLASSES)) {
      SparkEnv.get.conf.remove(Connect.CONNECT_EXTENSIONS_RELATION_CLASSES)
    }
    if (SparkEnv.get.conf.contains(Connect.CONNECT_EXTENSIONS_COMMAND_CLASSES)) {
      SparkEnv.get.conf.remove(Connect.CONNECT_EXTENSIONS_COMMAND_CLASSES)
    }
    SparkConnectPluginRegistry.reset()
  }

  def withSparkConf(pairs: (String, String)*)(f: => Unit): Unit = {
    val conf = SparkEnv.get.conf
    pairs.foreach { kv => conf.set(kv._1, kv._2) }
    try f
    finally {
      pairs.foreach { kv => conf.remove(kv._1) }
    }
  }

  def buildRelation(): proto.Relation = {
    val input = Relation
      .newBuilder()
      .setExtension(
        protobuf.Any.pack(
          proto.ExamplePluginRelation
            .newBuilder()
            .setInput(
              proto.Relation
                .newBuilder()
                .setRange(proto.Range
                  .newBuilder()
                  .setStart(0)
                  .setEnd(10)
                  .setStep(1)))
            .build()))
    Relation
      .newBuilder()
      .setProject(
        proto.Project
          .newBuilder()
          .addExpressions(
            proto.Expression
              .newBuilder()
              .setExtension(
                protobuf.Any.pack(
                  proto.ExamplePluginExpression
                    .newBuilder()
                    .setChild(proto.Expression
                      .newBuilder()
                      .setUnresolvedAttribute(proto.Expression.UnresolvedAttribute
                        .newBuilder()
                        .setUnparsedIdentifier("id")))
                    .setCustomField("martin")
                    .build())))
          .setInput(input))
      .build()
  }

  test("end to end with no extensions configured") {
    assertThrows[InvalidPlanInput] {
      transform(buildRelation())
    }

  }

  test("End to end Relation plugin test") {
    withSparkConf(
      Connect.CONNECT_EXTENSIONS_RELATION_CLASSES.key ->
        "org.apache.spark.sql.connect.plugin.ExampleRelationPlugin",
      Connect.CONNECT_EXTENSIONS_EXPRESSION_CLASSES.key ->
        "org.apache.spark.sql.connect.plugin.ExampleExpressionPlugin") {
      val plan = transform(buildRelation())
      val ds = Dataset.ofRows(spark, plan)
      val result = ds.collect()
      assert(result.length == 10)
      assert(result(0).schema.fieldNames(0) == "martin")
    }
  }

  test("End to end Command test") {
    withSparkConf(
      Connect.CONNECT_EXTENSIONS_COMMAND_CLASSES.key ->
        "org.apache.spark.sql.connect.plugin.ExampleCommandPlugin") {
      spark.sparkContext.setLocalProperty("testingProperty", "notset")
      val plan = proto.Command
        .newBuilder()
        .setExtension(
          protobuf.Any.pack(
            proto.ExamplePluginCommand
              .newBuilder()
              .setCustomField("Martin")
              .build()))
        .build()

      val executeHolder = buildExecutePlanHolder(plan)
      new SparkConnectPlanner(executeHolder)
        .process(plan, new MockObserver())
      assert(spark.sparkContext.getLocalProperty("testingProperty").equals("Martin"))
    }
  }

  test("Exception handling for plugin classes") {
    withSparkConf(
      Connect.CONNECT_EXTENSIONS_RELATION_CLASSES.key ->
        "org.apache.spark.sql.connect.plugin.DummyPluginNoTrivialCtor") {
      checkError(
        exception = intercept[SparkException] {
          SparkConnectPluginRegistry.loadRelationPlugins()
        },
        errorClass = "CONNECT.PLUGIN_CTOR_MISSING",
        parameters = Map("cls" -> "org.apache.spark.sql.connect.plugin.DummyPluginNoTrivialCtor"))
    }

    withSparkConf(
      Connect.CONNECT_EXTENSIONS_RELATION_CLASSES.key ->
        "org.apache.spark.sql.connect.plugin.DummyPluginInstantiationError") {
      checkError(
        exception = intercept[SparkException] {
          SparkConnectPluginRegistry.loadRelationPlugins()
        },
        errorClass = "CONNECT.PLUGIN_RUNTIME_ERROR",
        parameters = Map("msg" -> "Bad Plugin Error"))
    }
  }

  test("Empty registries are really empty and work") {
    assert(SparkConnectPluginRegistry.loadRelationPlugins().isEmpty)
    assert(SparkConnectPluginRegistry.loadExpressionPlugins().isEmpty)
    assert(SparkConnectPluginRegistry.loadCommandPlugins().isEmpty)
  }

  test("Building builders using factory methods") {
    val x = SparkConnectPluginRegistry.relation[DummyPlugin](classOf[DummyPlugin])
    assert(x != null)
    assert(x().isInstanceOf[RelationPlugin])
    val y =
      SparkConnectPluginRegistry.expression[DummyExpressionPlugin](classOf[DummyExpressionPlugin])
    assert(y != null)
    assert(y().isInstanceOf[ExpressionPlugin])
  }

  test("Configured class not found is properly thrown") {
    withSparkConf(
      Connect.CONNECT_EXTENSIONS_EXPRESSION_CLASSES.key -> "this.class.does.not.exist") {
      assertThrows[ClassNotFoundException] {
        SparkConnectPluginRegistry.createConfiguredPlugins(
          SparkEnv.get.conf.get(Connect.CONNECT_EXTENSIONS_EXPRESSION_CLASSES))
      }
    }

    withSparkConf(
      Connect.CONNECT_EXTENSIONS_RELATION_CLASSES.key -> "this.class.does.not.exist") {
      assertThrows[ClassNotFoundException] {
        SparkConnectPluginRegistry.createConfiguredPlugins(
          SparkEnv.get.conf.get(Connect.CONNECT_EXTENSIONS_RELATION_CLASSES))
      }
    }
  }

}
