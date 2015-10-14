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

package io.deepsense.deeplang.doperables.machinelearning.randomforest.classification

import org.apache.spark.mllib.tree.{RandomForest => SparkRandomForest}

import io.deepsense.commons.types.ColumnType
import io.deepsense.deeplang._
import io.deepsense.deeplang.doperables.ColumnTypesPredicates.Predicate
import io.deepsense.deeplang.doperables._
import io.deepsense.deeplang.doperables.dataframe.DataFrame
import io.deepsense.deeplang.doperables.machinelearning.randomforest.RandomForestParameters
import io.deepsense.deeplang.inference.{InferContext, InferenceWarnings}

case class UntrainedRandomForestClassification(
    modelParameters: RandomForestParameters)
  extends RandomForestClassifier
  with Trainable
  with CategoricalFeaturesExtractor {

  def this() = this(null)

  override protected def runTraining: RunTraining = runClassificationTrainingWithLabeledPoints

  override protected def actualTraining: TrainScorable = (trainParameters) => {
    val trainedModel =
      SparkRandomForest.trainClassifier(
        trainParameters.labeledPoints,
        trainParameters.numberOfClasses.get,
        extractCategoricalFeatures(trainParameters.dataFrame, trainParameters.features),
        modelParameters.numTrees,
        modelParameters.featureSubsetStrategy,
        modelParameters.impurity,
        modelParameters.maxDepth,
        modelParameters.maxBins)

    TrainedRandomForestClassification(
      modelParameters,
      trainParameters.numberOfClasses.get,
      trainedModel,
      trainParameters.features,
      trainParameters.target)
  }

  override protected def actualInference(
      context: InferContext)(
      parameters: TrainableParameters)(
      dataFrame: DKnowledge[DataFrame]): (DKnowledge[Scorable], InferenceWarnings) =
    (DKnowledge(new TrainedRandomForestClassification()), InferenceWarnings.empty)

  override def toInferrable: DOperable = new UntrainedRandomForestClassification()

  override def report(executionContext: ExecutionContext): Report = {
    DOperableReporter("Untrained Random Forest Classification")
      .withParameters(
        description = "",
        ("Num trees", ColumnType.numeric, modelParameters.numTrees.toString),
        ("Feature subset strategy", ColumnType.string, modelParameters.featureSubsetStrategy),
        ("Impurity", ColumnType.string, modelParameters.impurity),
        ("Max depth", ColumnType.numeric, modelParameters.maxDepth.toString),
        ("Max bins", ColumnType.numeric, modelParameters.maxBins.toString)
      )
      .report
  }

  override def save(context: ExecutionContext)(path: String): Unit = ???

  override protected def labelPredicate: Predicate =
    ColumnTypesPredicates.isNumericBooleanCategorical

  override protected def featurePredicate: Predicate =
    ColumnTypesPredicates.isNumericOrNonTrivialCategorical
}
