//--------------------------------------
//
// RawArrayTest.scala
// Since: 2013/12/03 12:02 PM
//
//--------------------------------------

package xerial.larray.core

import org.scalatest._
import xerial.core.io.Resource
import xerial.core.util.Timer
import xerial.core.log.Logger
import java.nio.ByteBuffer

trait LArraySpec extends WordSpec with ShouldMatchers with MustMatchers with GivenWhenThen with OptionValues with Resource with Timer with Logger
with BeforeAndAfterAll with BeforeAndAfter with BeforeAndAfterEach {
}

/**
 * @author Taro L. Saito
 */
class RawArrayTest extends LArraySpec {
  "RawArray" should {

    "allocate memory" in {
      val m = new RawArray(1000)
      m.putInt(0, 0)
      m.putInt(4, 1)
      m.putInt(8, 130)

      m.release()
    }


    "allocate concurrently" in {

      val N = 1000
      def range = (0 until N).par
      val R = 1
      val S = 1024 * 1024

      time("concurrent allocation", repeat=3) {
        block("without zero-filling", repeat=R) {
          for(i <- range) yield {
            new RawArray(S)
          }
        }

        block("with zero-filling", repeat=R) {
          for(i <- range) yield {
            val m = new RawArray(S)
            m.clear()
            m
          }
        }

        block("java array", repeat=R) {
          for(i <- range) yield {
            new Array[Byte](S)
          }
        }

        block("byte buffer", repeat=R) {
          for(i <- range) yield {
            ByteBuffer.allocate(S)
          }
        }

        block("direct byte buffer", repeat=R) {
          for(i <- range) yield {
            ByteBuffer.allocateDirect(S)
          }
        }

      }
    }

  }
}