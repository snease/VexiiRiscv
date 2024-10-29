package vexiiriscv.soc.micro

import spinal.core._
import vexiiriscv.ParamSimple

// This class will carry all the parameter of the SoC
class MicroSocParam {
  var ramBytes = 16 KiB
  val vexii = new ParamSimple()
  var demoPeripheral = Option.empty[PeripheralDemoParam]
  val socCtrl = new SocCtrlParam()

  // Provide some sane default
  vexii.fetchForkAt = 1
  vexii.lsuPmaAt = 1
  vexii.lsuForkAt = 1
  vexii.relaxedBranch = true
  socCtrl.withJtagTap = true
  legalize()

  // This is a command line parser utility, so you can customize the SoC using command line arguments to feed parameters
  def addOptions(parser: scopt.OptionParser[Unit]): Unit = {
    import parser._
    opt[Int]("ram-bytes") action { (v, c) => ramBytes = v }
    opt[Int]("demo-peripheral") action { (v, c) => demoPeripheral = Some(new PeripheralDemoParam(
      ledWidth = v
    ))}

    socCtrl.addOptions(parser)
    vexii.addOptions(parser)
  }

  // After modifying the attributes of this class, you need to call the legalize function to check / fix it is fine.
  def legalize(): Unit = {
    vexii.privParam.withDebug = socCtrl.withDebug
  }
}