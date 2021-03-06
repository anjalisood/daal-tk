/**
 *  Copyright (c) 2016 Intel Corporation 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.trustedanalytics.daaltk.models.regression.linear_regression

import com.intel.daal.algorithms.linear_regression.Model
import com.intel.daal.algorithms.linear_regression.training._
import com.intel.daal.services.DaalContext
import org.trustedanalytics.sparktk.frame.internal.rdd.FrameRdd
import org.apache.spark.rdd.RDD
import org.trustedanalytics.daaltk.DaalUtils.withDaalContext
import org.trustedanalytics.daaltk.DistributedAlgorithm
import org.trustedanalytics.daaltk.models.tables.DistributedLabeledTable
import org.trustedanalytics.daaltk.models.tables.DaalConversionImplicits._
import com.intel.daal.algorithms.ModelSerializer
import scala.util.{ Failure, Success, Try }

/**
 * Train Intel DAAL linear regression model using QR decomposition
 *
 * @param frameRdd Input frame
 * @param observationColumns Feature columns
 * @param valueColumn Dependent variable column
 * @param fitIntercept Boolean flag for whether to fit an intercept term
 */
case class LinearTrainAlgorithm(frameRdd: FrameRdd,
                                observationColumns: Seq[String],
                                valueColumn: String,
                                fitIntercept: Boolean = true) extends DistributedAlgorithm[PartialResult, TrainingResult] {
  private val trainTables = DistributedLabeledTable.createTable(frameRdd, observationColumns, List(valueColumn))

  /**
   * Train Intel DAAL linear regression model using QR decomposition
   * @return Trained linear regression model
   */
  def train(): LinearRegressionModelData = {
    withDaalContext { context =>
      //train model
      val partialResultsRdd = computePartialResults()
      val trainingResult = mergePartialResults(context, partialResultsRdd)
      val trainedModel = trainingResult.get(TrainingResultId.model)

      // serialize model and return results
      val (weights, intercept) = getWeightsAndIntercept(trainedModel)
      val serializedModel = serializeTrainedModel(trainedModel)
      LinearRegressionModelData(serializedModel, weights, intercept)
    }.elseError("Could not train linear regression model")
  }

  /**
   * Compute partial results for linear regression model using QR decomposition
   *
   * @return Partial result of training
   */
  override def computePartialResults(): RDD[PartialResult] = {

    val linearModelsRdd = trainTables.rdd.map(table => {
      withDaalContext { context =>
        val featureTable = table.features
        val labelTable = table.labels
        val linearRegressionTraining = new TrainingDistributedStep1Local(context, classOf[java.lang.Double], TrainingMethod.qrDense)
        linearRegressionTraining.input.set(TrainingInputId.data, featureTable.getUnpackedTable(context))
        linearRegressionTraining.input.set(TrainingInputId.dependentVariable, labelTable.getUnpackedTable(context))
        linearRegressionTraining.parameter.setInterceptFlag(fitIntercept)

        val lrResult = linearRegressionTraining.compute()
        lrResult.pack()
        lrResult
      }.elseError("Could not compute partial results for linear regression")
    })
    linearModelsRdd
  }

  /**
   * Merge partial results to generate final training result that contains linear regression model
   *
   * @param context DAAL Context
   * @param rdd RDD of partial results
   * @return Final result of algorithm
   */
  override def mergePartialResults(context: DaalContext, rdd: RDD[PartialResult]): TrainingResult = {
    val linearRegressionTraining = new TrainingDistributedStep2Master(context, classOf[java.lang.Double], TrainingMethod.qrDense)

    /* Build and retrieve final linear model */
    val linearModelsArray = rdd.collect()
    linearModelsArray.foreach { partialModel =>
      partialModel.unpack(context)
      linearRegressionTraining.input.add(MasterInputId.partialModels, partialModel)
    }

    linearRegressionTraining.compute()
    val trainingResult = linearRegressionTraining.finalizeCompute()
    trainingResult
  }

  /**
   * Get model weights and intercept from trained linear regression model
   * @param trainedModel Trained model
   * @return Tuple of Weights array and model intercept
   */
  private def getWeightsAndIntercept(trainedModel: Model): (Array[Double], Double) = {
    val betas = trainedModel.getBeta
    val betaArray = betas.toDoubleArray()

    fitIntercept match {
      case true => (betaArray.tail, betaArray.head)
      case _ => (betaArray, 0d)
    }
  }

  /**
   * Serialize trained model to byte array
   * @param trainedModel Trained model
   * @return Serialized model
   */
  private def serializeTrainedModel(trainedModel: Model): List[Byte] = {
    val serializedModel = Try(ModelSerializer.serializeQrModel(trainedModel).toList) match {
      case Success(sModel) => sModel
      case Failure(ex) => {
        println(s"Unable to serialize DAAL model : ${ex.getMessage}")
        throw new scala.RuntimeException(s"Unable to serialize DAAL model : ${ex.getMessage}")
      }
    }
    serializedModel
  }
}

/**
 * DAAL linear regression model train data
 *
 * @param serializedModel Serialized DAAL linear regression model
 * @param weights Weights of the trained model
 * @param intercept Intercept of the trained model
 */
case class LinearRegressionModelData(serializedModel: List[Byte], weights: Seq[Double], intercept: Double)