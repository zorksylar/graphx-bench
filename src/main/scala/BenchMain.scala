/**
 * Created by z on 12/10/15.
 */

package org.zork.graphx

import java.util

import akka.actor.FSM.Timer
import org.apache.spark.{Logging, SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.graphx.{Edge, GraphLoader, GraphXUtils, PartitionStrategy}
import org.apache.spark.graphx.lib._
import org.apache.spark.graphx.PartitionStrategy._
import org.apache.spark.storage.StorageLevel
import org.zork.graphx.SGD.Conf

import scala.collection.mutable
import org.zork.graphx.Timer

object BenchMain extends Logging {


  def main(args : Array[String]) : Unit = {
    if (args.length < 3) {
      System.err.println(
        "Usage : BenchMain <app> <file> " +
          "--numEPart=<num_edge_partition> " +
          "--numIter=<num_iterations> " +
          "--partStrategy=<partitioin strategy> " +
          " [other options]"
      )
      System.err.println("Supported apps : \n" +
        "PageRank TrustRank SVDPP SGD ");
      System.exit(1)
    }

    // resolve input
    val app = args(0)
    val fname = args(1)
    val optionsList = args.drop(2).map { arg =>
      arg.dropWhile(_ == '-').split('=') match {
        case Array(opt, v) => (opt -> v)
        case _ => throw new IllegalArgumentException("Invalid argument: " + arg)
      }
    }

    val options = mutable.Map(optionsList: _*)

    val conf = new SparkConf()
    GraphXUtils.registerKryoClasses(conf)
    val numEPart = options.remove("numEPart").map(_.toInt).getOrElse {
      println("Set the number of edge partitions using --numEPart.")
      sys.exit(1)
    }
    val partitionStrategy: Option[PartitionStrategy] = options.remove("partStrategy")
      .map(PartitionStrategy.fromString(_))
    val edgeStorageLevel = options.remove("edgeStorageLevel")
      .map(StorageLevel.fromString(_)).getOrElse(StorageLevel.MEMORY_ONLY)
    val vertexStorageLevel = options.remove("vertexStorageLevel")
      .map(StorageLevel.fromString(_)).getOrElse(StorageLevel.MEMORY_ONLY)


    val numIter = options.remove("numIter").map(_.toInt).getOrElse {
      println("Set the number of iterations to run using --numIter")
      sys.exit(1)
    }

    val outFname = options.remove("output").getOrElse("")

    app match {
      case "pagerank" =>

        options.foreach {
          case (opt, _) => throw new IllegalArgumentException("Invalid option: " + opt)
        }

        println("========================================")
        println("                 PageRank               ")
        println("========================================")

        val sc = new SparkContext(conf.setAppName("PageRank(" + fname + ")"))

        val unpartitionedGraph = GraphLoader.edgeListFile(sc, fname,
          numEdgePartitions =  numEPart,
          edgeStorageLevel = edgeStorageLevel,
          vertexStorageLevel = vertexStorageLevel).cache()
        var pre_start = System.currentTimeMillis()
        val graph = partitionStrategy.foldLeft(unpartitionedGraph)(_.partitionBy(_))
        graph.vertices.count() // materialize
        var pre_end = System.currentTimeMillis()

        unpartitionedGraph.unpersist()

        println("GraphX: Preprocessing cost " + (pre_end - pre_start))

        println("GRAPHX: Number of vertices " + graph.vertices.count)
        println("GRAPHX: Number of edges " + graph.edges.count)


        // val pr = PageRank.run(graph, numIter).vertices
        //val time_ms = PageRankPregel.run(graph, numIter)
        val time_ms = PageRankUnCache.run(graph, numIter)
        //val time_ms = PageRankPregelConverge.run(graph, numIter)

        println("GRAPHX: PageRank CONF::Iteration " + numIter + ".")
        println("GRAPHX: PageRank TIMING::Total " + time_ms + " ms.")


        sc.stop()

      case "trustrank" =>

        options.foreach {
          case (opt, _) => throw new IllegalArgumentException("Invalid option: " + opt)
        }

        println("========================================")
        println("                 TrustRank              ")
        println("========================================")

        val sc = new SparkContext(conf.setAppName("TrustRank(" + fname + ")"))

        val unpartitionedGraph = GraphLoader.edgeListFile(sc, fname,
          numEdgePartitions =  numEPart,
          edgeStorageLevel = edgeStorageLevel,
          vertexStorageLevel = vertexStorageLevel).cache()
        val graph = partitionStrategy.foldLeft(unpartitionedGraph)(_.partitionBy(_))
        unpartitionedGraph.unpersist()

        println("GRAPHX: Number of vertices " + graph.vertices.count)
        println("GRAPHX: Number of edges " + graph.edges.count)


        val cost = TrustRank.run(graph, numIter)

        println("GRAPHX: TrustRank CONF::Iteration " + numIter + ".")
        println("GRAPHX: TrustRank TIMING::Total " + cost + " ms.")

        sc.stop()

      case "svdpp" =>

        val d = options.remove("d").map(_.toInt).getOrElse {
          println("Set the number latent dimention of SVDPP using --d")
          sys.exit(1)
        }

        options.foreach {
          case (opt, _) => throw new IllegalArgumentException("Invalid option: " + opt)
        }

        println("========================================")
        println("                 SVDPP                  ")
        println("========================================")

        val sc = new SparkContext(conf.setAppName("SVDPP(" + fname + ")"))

        val edges = sc.textFile(fname).map { line =>
          val fields = line.split("\t")
          Edge(fields(0).toLong * 2, fields(1).toLong *2 +1, fields(2).toDouble)
        }
        val svdpp_conf = new SVDPPBench.Conf(d , numIter, 0.0, 5.0, 1e-40, 1e-40, 1e-40, 1e-40)

        val cost = SVDPPBench.run(edges, svdpp_conf)

        println("GRAPHX: SVDPP CONF::Iteration " + numIter + ".")
        println("GRAPHX: SVDPP TIMING::Total " + cost + " ms.")
        sc.stop()

      case "sgd" =>
        val d = options.remove("d").map(_.toInt).getOrElse {
          println("Set the number latent dimention of SGD using --d")
          sys.exit(1)
        }

        options.foreach {
          case (opt, _) => throw new IllegalArgumentException("Invalid option: " + opt)
        }

        println("========================================")
        println("                 SGD                    ")
        println("========================================")

        val sc = new SparkContext(conf.setAppName("SGD(" + fname + ")"))

        val edges = sc.textFile(fname).map{ line =>
          val fields = line.split("\t")
          Edge(fields(0).toLong * 2, fields(1).toLong *2 + 1, fields(2).toDouble)
        }

        val sgd_conf = new SGD.Conf(d, numIter, 0.0, 5.0, 0.001, 0.001)

        val cost = SGD.run(edges, sgd_conf)

        println("GRAPHX: SGD CONF::Iteration " + numIter + ".")
        println("GRAPHX: SGD TIMING::Total " + cost + " ms.")
        sc.stop()

      case "als" =>
        val d = options.remove("d").map(_.toInt).getOrElse {
          println("Set the number latent dimention of ALS using --d")
          sys.exit(1)
        }

        options.foreach {
          case (opt, _) => throw new IllegalArgumentException("Invalid option: " + opt)
        }

        println("========================================")
        println("                 ALS                    ")
        println("========================================")

        val sc = new SparkContext(conf.setAppName("ALS(" + fname + ")"))

        val edges = sc.textFile(fname).map{ line =>
          val fields = line.split("\t")
          Edge(fields(0).toLong * 2, fields(1).toLong *2 + 1, fields(2).toDouble)
        }

        val als_conf = new ALS.Conf(d, numIter, 0.0, 5.0, 0.01)

        val cost = ALS.run(edges, als_conf)

        println("GRAPHX: ALS CONF::Iteration " + numIter + ".")
        println("GRAPHX: ALS TIMING::Total " + cost + " ms.")
        sc.stop()

      case _ =>
        println("Invalid app.")

    }
  }
}
