/**
 * Copyright 2015, deepsense.io
 *
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

package io.deepsense.workflowexecutor.executor

import akka.actor.{ActorSystem, Props}
import com.rabbitmq.client.ConnectionFactory
import com.thenewmotion.akka.rabbitmq.ConnectionActor
import com.typesafe.config.ConfigFactory
import org.apache.spark.SparkContext

import io.deepsense.deeplang.doperables.ReportLevel
import io.deepsense.deeplang.doperables.ReportLevel._
import io.deepsense.models.json.graph.GraphJsonProtocol.GraphReader
import io.deepsense.workflowexecutor.communication.mq.MQCommunication
import io.deepsense.workflowexecutor.communication.mq.serialization.json.{ProtocolJsonDeserializer, ProtocolJsonSerializer}
import io.deepsense.workflowexecutor.rabbitmq._
import io.deepsense.workflowexecutor.session.storage.DataFrameStorageImpl
import io.deepsense.workflowexecutor.{ExecutionDispatcherActor, StatusLoggingActor, WorkflowManagerClientActor}

/**
 * SessionExecutor waits for user instructions in an infinite loop.
 */
case class SessionExecutor(
    reportLevel: ReportLevel,
    messageQueueHost: String,
    pythonExecutorPath: String)
  extends Executor {

  private val config = ConfigFactory.load
  val graphReader = new GraphReader(createDOperationsCatalog())

  /**
   * WARNING: Performs an infinite loop.
   */
  def execute(): Unit = {
    logger.debug("SessionExecutor starts")
    val sparkContext = createSparkContext()
    val dOperableCatalog = createDOperableCatalog()
    val dataFrameStorage = new DataFrameStorageImpl

    implicit val system = ActorSystem()
    val statusLogger = system.actorOf(Props[StatusLoggingActor], "status-logger")
    val workflowManagerClientActor = system.actorOf(
      WorkflowManagerClientActor.props(
        config.getString("workflow-manager.local.address"),
        config.getString("workflow-manager.workflows.path"),
        config.getString("workflow-manager.reports.path"),
        graphReader))

    val pythonExecutionCaretaker =
      new PythonExecutionCaretaker(pythonExecutorPath, sparkContext, dataFrameStorage)
    pythonExecutionCaretaker.start()

    val executionDispatcher = system.actorOf(ExecutionDispatcherActor.props(
      sparkContext,
      dOperableCatalog,
      dataFrameStorage,
      pythonExecutionCaretaker,
      ReportLevel.HIGH,
      statusLogger,
      workflowManagerClientActor,
      config.getInt("workflow-manager.timeout")), "workflows")

    val factory = new ConnectionFactory()
    factory.setHost(messageQueueHost)

    val connection = system.actorOf(
      ConnectionActor.props(factory),
      MQCommunication.mqActorSystemName)

    val messageDeserializer = ProtocolJsonDeserializer(graphReader)
    val messageSerializer = ProtocolJsonSerializer(graphReader)
    val communicationFactory =
      MQCommunicationFactory(system, connection, messageSerializer, messageDeserializer)

    val seahorseSubscriberActor = system.actorOf(
      SeahorseTopicSubscriber.props(executionDispatcher, communicationFactory),
      MQCommunication.Actor.Subscriber.seahorse)
    communicationFactory.createCommunicationChannel(
      MQCommunication.Topic.seahorse,
      seahorseSubscriberActor,
      MQCommunication.Actor.Publisher.seahorse)

    val kernelSubscriberActor = system.actorOf(
      NotebookKernelTopicSubscriber.props(
        MQCommunication.Actor.Publisher.kernel,
        pythonExecutionCaretaker.gatewayListeningPort _),
      MQCommunication.Actor.Subscriber.kernel)
    communicationFactory.createCommunicationChannel(
      MQCommunication.Topic.kernel,
      kernelSubscriberActor,
      MQCommunication.Actor.Publisher.kernel)

    system.awaitTermination()
    cleanup(sparkContext, pythonExecutionCaretaker)
    logger.debug("SessionExecutor ends")
  }

  private def cleanup(
      sparkContext: SparkContext,
      pythonExecutionCaretaker: PythonExecutionCaretaker): Unit = {
    logger.debug("Cleaning up...")
    pythonExecutionCaretaker.stop()
    sparkContext.stop()
    logger.debug("Spark terminated!")
  }
}
