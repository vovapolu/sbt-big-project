package runjava

import scala.collection.JavaConversions._
import java.lang.management.ManagementFactory
import java.lang.management.RuntimeMXBean
import java.io._


object printArgs extends App {
  val runtimeMxBean = ManagementFactory.getRuntimeMXBean
  val jvmArgs = runtimeMxBean.getInputArguments.toList

  val envArgs = System.getenv().toMap.toSeq.filter(_._1 startsWith "testing")

  val properties = System.getProperties.toMap.toSeq.filter(_._1 startsWith "testing")

  val output = new PrintWriter(new File(args(0)))
  output.write(properties.map(t => t._1 + "=" + t._2).mkString(" "))
  output.write("\n")
  output.write(jvmArgs.mkString(" "))
  output.write("\n")
  output.write(envArgs.map(t => t._1 + "=" + t._2).mkString(" "))
  output.write("\n")
  output.write(args.mkString(" "))
  output.close
}
