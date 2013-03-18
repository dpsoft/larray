//--------------------------------------
//
// MemoryAllocator.scala
// Since: 2013/03/14 9:26
//
//--------------------------------------

package xerial.larray

import sun.misc.Unsafe
import xerial.core.log.Logger
import java.lang.ref.ReferenceQueue

/**
 * Accessor to the allocated memory
 * @param address
 */
case class Memory(address:Long, alloc:MemoryAllocator) {

  import UnsafeUtil.unsafe

  @inline def getByte(offset:Long) : Byte = unsafe.getByte(address + offset)
  @inline def getChar(offset:Long) : Char= unsafe.getChar(address + offset)
  @inline def getShort(offset:Long) : Short = unsafe.getShort(address + offset)
  @inline def getInt(offset:Long) : Int = unsafe.getInt(address + offset)
  @inline def getFloat(offset:Long) : Float = unsafe.getFloat(address + offset)
  @inline def getLong(offset:Long) : Long = unsafe.getLong(address + offset)
  @inline def getDouble(offset:Long) : Double = unsafe.getDouble(address + offset)

  @inline def putByte(offset:Long, v:Byte) : Unit = unsafe.putByte(address + offset, v)
  @inline def putChar(offset:Long, v:Char) : Unit = unsafe.putChar(address + offset, v)
  @inline def putShort(offset:Long, v:Short) : Unit = unsafe.putShort(address + offset, v)
  @inline def putInt(offset:Long, v:Int) : Unit = unsafe.putInt(address + offset, v)
  @inline def putFloat(offset:Long, v:Float) : Unit = unsafe.putFloat(address + offset, v)
  @inline def putLong(offset:Long, v:Long) : Unit = unsafe.putLong(address + offset, v)
  @inline def putDouble(offset:Long, v:Double) : Unit = unsafe.putDouble(address + offset, v)

  def free = alloc.release(address)
}



object MemoryAllocator {

  /**
   * Provides a default memory allocator
   */
  implicit val default : MemoryAllocator = new UnsafeAllocator

}


/**
 * Memory allocator interface
 *
 * @author Taro L. Saito
 */
trait MemoryAllocator extends Logger {


  // Register a shutdown hook to deallocate memory regions
  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    def run() {
      releaseAll
    }
  }))

  private var hasDisplayedMemoryWarning = false

  /**
   * Release all memory addresses taken by this allocator.
   * Be careful in using this method, since all the memory addresses in LArray will be invalid.
   */
  def releaseAll {
    synchronized {
      val addrSet = allocatedAddresses
      if(!addrSet.isEmpty)
        trace("Releasing allocated memory regions")
      for(addr <- addrSet) {
        warn(f"Found unreleased address:${addr}%x")
        if(!hasDisplayedMemoryWarning) {
          warn("Probably LArray.free is not called properly. You can check when this memory is allocated by setting -Dloglevel=trace in JVM option")
          hasDisplayedMemoryWarning = true
        }
        release(addr)
      }
    }
  }

  protected def allocatedAddresses : Seq[Long]


  /**
   * Allocate a memory of the specified byte length. The allocated memory must be released via [[xerial.larray.MemoryAllocator#release]]
   * as in malloc() in C/C++.
   * @param size byte length of the memory
   * @return adress of the allocated mmoery.
   */
  def allocate(size:Long) : Memory

  /**
   * Release the memory allocated by [[xerial.larray.MemoryAllocator#allocate]]
   * @param addr the memory returned by  [[xerial.larray.MemoryAllocator#allocate]]
   */
  def release(addr:Long) : Unit
}

object UnsafeUtil extends Logger {
  val unsafe = {
    val f = classOf[Unsafe].getDeclaredField("theUnsafe")
    f.setAccessible(true)
    f.get(null).asInstanceOf[Unsafe]
  }

  val byteArrayOffset = unsafe.arrayBaseOffset(classOf[Array[Byte]]).toLong
  val objectArrayOffset = unsafe.arrayBaseOffset(classOf[Array[AnyRef]]).toLong
  val objectArrayScale = unsafe.arrayIndexScale(classOf[Array[AnyRef]]).toLong
  val addressBandWidth = System.getProperty("sun.arch.data.model", "64").toInt
  private val addressFactor = if(addressBandWidth == 64) 8L else 1L
  val addressSize = unsafe.addressSize()

  /**
   * @param obj
   * @return
   *
   */
  @deprecated(message="Deprecated because this method does not return correct object addresses in some platform", since="0.1")
  def getObjectAddr(obj:AnyRef) : Long = {
    trace(f"address factor:$addressFactor%d, addressSize:$addressSize, objectArrayOffset:$objectArrayOffset, objectArrayScale:$objectArrayScale")

    val o = new Array[AnyRef](1)
    o(0) = obj
    objectArrayScale match {
      case 4 => (unsafe.getInt(o, objectArrayOffset) & 0xFFFFFFFFL) * addressFactor
      case 8 => (unsafe.getLong(o, objectArrayOffset) & 0xFFFFFFFFFFFFFFFFL) * addressFactor
    }
  }
}


/**
 * Allocate memory using [[sun.misc.Unsafe]]. OpenJDK (and probably Oracle JDK) implements allocateMemory and freeMemory functions using malloc() and free() in C.
 */
class UnsafeAllocator extends MemoryAllocator with Logger {

  private val phantomReferences = collection.mutable.Map[Long, MemoryReference]()
  private val queue = new ReferenceQueue[Memory]

  {
    // Recieves garbage-collected Memory
    val worker = new Thread(new Runnable {
      def run() {
        while(true) {
          try {
            val ref = queue.remove.asInstanceOf[MemoryReference]
            trace(f"GC collected memory ${ref.address}%x")
            release(ref.address)
          }
          catch {
            case e: Exception => warn(e)
          }
        }
      }
    })
    worker.setDaemon(true)
    debug("Started memory collector")
    worker.start
  }


  import UnsafeUtil.unsafe

  protected def allocatedAddresses : Seq[Long] = synchronized { Seq() ++ phantomReferences.values.map(_.address) } // take a copy of the set

  def allocate(size: Long): Memory = {
    synchronized {
      val m = Memory(unsafe.allocateMemory(size), this)
      trace(f"allocated memory: ${m.address}%x")
      val ref = new MemoryReference(m, queue)
      phantomReferences += m.address -> ref
      m
    }
  }

  def release(addr:Long) : Unit = {
    synchronized {
      if(phantomReferences.contains(addr)) {
        trace(f"release memory: ${addr}%x")
        val ref = phantomReferences(addr)
        unsafe.freeMemory(addr)
        phantomReferences.remove(addr)
      }
    }
  }
}

