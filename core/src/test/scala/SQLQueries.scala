import org.scalatest._
import scala.sys.process._
import java.io._

class SQLQueries extends FunSuite {
  val confDir = new File("../test/squall/confs/local")

  def runQuery(conf: String): Int = {
    val log = File.createTempFile("squall_", ".log"); // prefix and suffix
    val out = (Process(s"./squall_local.sh SQL $conf", new File("../bin")) #> log)!

    if (out != 0)
      println("Error: test '" + conf.split('/').last + "' failed. Error log in " + log.getAbsolutePath());

    out
  }

  for(confFile <- confDir.listFiles()) {
    test(confFile.getName()) {
      assertResult(0) {
        runQuery(confFile.getAbsolutePath())
      }
    }
  }

}

