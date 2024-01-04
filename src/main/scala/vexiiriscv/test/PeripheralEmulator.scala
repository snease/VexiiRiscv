package vexiiriscv.test

import spinal.core._
import spinal.core.sim._

abstract class PeripheralEmulator(offset : Long, mei : Bool, sei : Bool, mti : Bool = null, cd : ClockDomain = null) {
  val PUTC = 0
  val PUT_HEX = 0x8
  val CLINT_BASE = 0x10000
  val CLINT_TIME = CLINT_BASE + 0xBFF8
  val CLINT_TIMEH = CLINT_BASE + 0xBFF8 + 4
  val CLINT_CMP = CLINT_BASE + 0x4000
  val CLINT_CMPH = CLINT_BASE + 0x4000 + 4
  val MACHINE_EXTERNAL_INTERRUPT_CTRL = 0x10
  val SUPERVISOR_EXTERNAL_INTERRUPT_CTRL = 0x18
  val GETC = 0x40
  val STATS_CAPTURE_ENABLE = 0x50
  val PUT_DEC = 0x60
  val INCR_COUNTER = 0x70
  val FAILURE_ADDRESS = 0x80
  val IO_FAULT_ADDRESS = 0x0FFFFFF0
  val RANDOM = 0xA8
  var cmp = 0l

  if(mei != null) mei #= false
  if(sei != null) sei #= false
  if(mti != null) {
    mti #= false
    cd.onSamplings{
      mti #= cmp < getClintTime()
    }
  }

  def getClintTime() : Long

  def access(write : Boolean, address : Long, data : Array[Byte]) : Boolean = {
    val addressPatched = address - offset
    if(write){
      addressPatched.toInt match {
        case PUTC => print(data(0).toChar)
        case PUT_HEX => print(data.reverse.map(v => f"$v%02x").mkString(""))
        case PUT_DEC => print(f"${BigInt(data.map(_.toByte).reverse.toArray)}%d")
        case MACHINE_EXTERNAL_INTERRUPT_CTRL => mei #= data(0).toBoolean
        case SUPERVISOR_EXTERNAL_INTERRUPT_CTRL => sei #= data(0).toBoolean
        case CLINT_CMP => {
          val v = BigInt(data.map(_.toByte).reverse.toArray).toLong
          data.size match {
            case 4 => cmp = cmp & 0xFFFFFFFF00000000l | v
            case 8 => cmp = v
          }
        }
        case CLINT_CMPH => cmp = cmp & 0xFFFFFFFFl | (BigInt(data.map(_.toByte).reverse.toArray).toLong << 32)
        case IO_FAULT_ADDRESS => {
          return true
        }
        case _ => {
          println(address)
          simFailure()
        }
      }
    } else {
      def readLong(that : Long) : Unit = {
        for (i <- 0 until data.size) data(i) = (that >> i*8).toByte
      }
      for(i <- 0 until data.size) data(i) = 0
      addressPatched.toInt match {
        case IO_FAULT_ADDRESS => {
          simRandom.nextBytes(data)
          return true;
        }
        case GETC => {
          if (System.in.available() != 0) {
            data(0) = System.in.read().toByte
          } else {
            for (i <- 0 until data.size) data(i) = 0xFF.toByte
          }
        }
        case RANDOM => {
          simRandom.nextBytes(data)
        }
        case CLINT_TIME => readLong(getClintTime())
        case CLINT_TIMEH => readLong(getClintTime() >> 32)
        case _ => {
          println(address)
          simFailure()
        }
      }
    }
    false
  }

}

