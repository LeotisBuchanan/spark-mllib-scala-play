package actors

import actors.BatchTrainer.BatchTrainerModel
import actors.OnlineTrainer.OnlineTrainerModel
import actors.StatisticsServer.TrainerType.TrainerType
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import features.TfIdf
import org.apache.spark.SparkContext
import org.apache.spark.mllib.evaluation.{BinaryClassificationMetrics, MulticlassMetrics}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import twitter.Tweet
import util.EnumUtils

object StatisticsServer {

  def props(sparkContext: SparkContext) = Props(new StatisticsServer(sparkContext))

  object TrainerType extends Enumeration {
    type TrainerType = TrainerType.Value
    val Batch, Online = Value

    implicit val reads: Reads[TrainerType] = EnumUtils.enumReads(TrainerType)
    implicit val writes: Writes[TrainerType] = EnumUtils.enumWrites
  }

  case class Statistics(trainer: TrainerType, roc: Double, accuracy: Double)

  object Statistics {
    implicit val formatter = Json.format[Statistics]
  }

}

class StatisticsServer(sparkContext: SparkContext) extends Actor with ActorLogging {

  import StatisticsServer._

  val sqlContext = new SQLContext(sparkContext)
  var clients = Set.empty[ActorRef]
  var corpus: RDD[Tweet] = sparkContext.emptyRDD[Tweet]
  var dfCorpus: Option[DataFrame] = None

  import sqlContext.implicits._

  override def receive = LoggingReceive {

    case m: BatchTrainerModel => testBatchModel(m)

    case m: OnlineTrainerModel => testOnlineModel(m)

    case c: RDD[Tweet] =>
      corpus = c

      dfCorpus = Some(c.map(t => {
        (t.tokens.toSeq, t.sentiment)
      }).toDF("tokens", "label"))

    case msg: JsValue => sendMessage(msg)

    case Subscribe =>
      context.watch(sender)
      clients += sender

    case Unsubscribe =>
      context.unwatch(sender)
      clients -= sender
  }

  private def testOnlineModel(onlineTrainerModel: OnlineTrainerModel) = {
    onlineTrainerModel.model.map(model => {
      log.debug("Test online trainer model")
      val tfIdf = TfIdf(corpus)
      val scoreAndLabels = corpus map { tweet => (model.predict(tfIdf.tfIdf(tweet.tokens)), tweet.sentiment) }
      val total: Double = scoreAndLabels.count()
      val metrics = new BinaryClassificationMetrics(scoreAndLabels)
      val correct: Double = scoreAndLabels.filter { case ((score, label)) => score == label }.count()
      val accuracy = correct / total

      val statistics = new Statistics(TrainerType.Online, metrics.areaUnderROC(), accuracy)

      log.info(s"Current model: ${model.toString()}")
      log.info(s"Area under the ROC curve: ${metrics.areaUnderROC()}")
      log.info(s"Accuracy: $accuracy ($correct of $total)")
      val mc = new MulticlassMetrics(scoreAndLabels)
      log.info(s"Precision: ${mc.precision}")
      log.info(s"Recall: ${mc.recall}")
      log.info(s"F-Measure: ${mc.fMeasure}")

      sendMessage(Json.toJson(statistics))
    }) getOrElse {
      log.info(s"No online trainer model found.")
    }
  }

  private def sendMessage(msg: JsValue) = {
    clients.foreach { c =>
      c ! msg
    }
  }

  private def testBatchModel(batchTrainerModel: BatchTrainerModel) = {
    (for {
      model <- batchTrainerModel.model
      dfCorpus <- dfCorpus } yield {
      log.debug("Test batch trainer model")
      var total = 0.0
      var correct = 0.0

      model
        .transform(dfCorpus)
        .select("tokens", "label", "probability", "prediction")
        .collect()
        .foreach { case Row(tokens, label, prob, prediction) =>
        if (label == prediction) correct += 1
        total += 1
      }

      val accuracy = correct / total
      log.info(s"Batch accuracy: ${accuracy}")

      sendMessage(Json.toJson(new Statistics(TrainerType.Batch, 0.0, accuracy)))
    }) getOrElse {
      log.info("No batch trainer model or dfCorpus found.")
    }
  }

}
