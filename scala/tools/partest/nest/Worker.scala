/* NEST (New Scala Test)
 * Copyright 2007-2010 LAMP/EPFL
 * @author Philipp Haller
 */

// $Id$

package scala.tools.partest
package nest

import java.io._
import java.net.{ URLClassLoader, URL }
import java.util.{ Timer, TimerTask }

import scala.util.Properties.{ isWin }
import scala.tools.nsc.{ ObjectRunner, Settings, CompilerCommand, Global }
import scala.tools.nsc.io.{ AbstractFile, PlainFile, Path, Directory, File => SFile }
import scala.tools.nsc.reporters.ConsoleReporter
import scala.tools.nsc.util.{ ClassPath, FakePos, ScalaClassLoader, stackTraceString }
import ClassPath.{ join, split }

import scala.actors.{ Actor, Exit, TIMEOUT }
import scala.actors.Actor._
import scala.tools.scalap.scalax.rules.scalasig.ByteCode
import scala.collection.{ mutable, immutable }
import scala.tools.nsc.interactive.{ BuildManager, RefinedBuildManager }
import scala.sys.process._

case class RunTests(kind: String, files: List[File])
case class Results(results: Map[String, Int], logs: List[LogFile], outdirs: List[File])

case class LogContext(file: LogFile, writers: Option[(StringWriter, PrintWriter)])

abstract class TestResult {
  def file: File
}
case class Result(override val file: File, context: LogContext) extends TestResult
case class Timeout(override val file: File) extends TestResult

class LogFile(parent: File, child: String) extends File(parent, child) {
  var toDelete = false
}

class ScalaCheckFileManager(val origmanager: FileManager) extends FileManager {
  def testRootDir: Directory = origmanager.testRootDir
  def testRootPath: String = origmanager.testRootPath

  var JAVACMD: String = origmanager.JAVACMD
  var JAVAC_CMD: String = origmanager.JAVAC_CMD

  var CLASSPATH: String = join(origmanager.CLASSPATH, PathSettings.scalaCheck.path)
  var LATEST_LIB: String = origmanager.LATEST_LIB
}

object Output {
  def init {
    System.setOut(outRedirect)
    System.setErr(errRedirect)
  }

  import scala.util.DynamicVariable
  private def out = java.lang.System.out
  private def err = java.lang.System.err
  private val redirVar = new DynamicVariable[Option[PrintStream]](None)

  class Redirecter(stream: PrintStream) extends PrintStream(new OutputStream {
    def write(b: Int) = withStream(_ write b)

    private def withStream(f: PrintStream => Unit) = f(redirVar.value getOrElse stream)

    override def write(b: Array[Byte]) = withStream(_ write b)
    override def write(b: Array[Byte], off: Int, len: Int) = withStream(_.write(b, off, len))
    override def flush = withStream(_.flush)
    override def close = withStream(_.close)
  })

  object outRedirect extends Redirecter(out)

  object errRedirect extends Redirecter(err)

  // this supports thread-safe nested output redirects
  def withRedirected[T](newstream: PrintStream)(func: => T): T = {
    // note down old redirect destination
    // this may be None in which case outRedirect and errRedirect print to stdout and stderr
    val saved = redirVar.value
    // set new redirecter
    // this one will redirect both out and err to newstream
    redirVar.value = Some(newstream)

    try func
    finally redirVar.value = saved
  }
}


class Worker(val fileManager: FileManager, params: TestRunParams) extends Actor {
  import fileManager._

  val scalaCheckFileManager = new ScalaCheckFileManager(fileManager)
  var reporter: ConsoleReporter = _
  val timer = new Timer

  val javacCmd = if ((fileManager.JAVAC_CMD.indexOf("${env.JAVA_HOME}") != -1) ||
                     fileManager.JAVAC_CMD.equals("/bin/javac") ||
                     fileManager.JAVAC_CMD.equals("\\bin\\javac")) "javac"
                 else
                   fileManager.JAVAC_CMD

  private var currentTimerTask: KickableTimerTask = _

  def cancelTimerTask() = if (currentTimerTask != null) currentTimerTask.cancel()
  def updateTimerTask(body: => Unit) = {
    cancelTimerTask()
    currentTimerTask = new KickableTimerTask(body)
    timer.schedule(currentTimerTask, fileManager.oneTestTimeout)
  }

  class KickableTimerTask(body: => Unit) extends TimerTask {
    def run() = body
    def kick() = {
      cancel()
      body
    }
  }

  /** Formerly deeper inside, these next few things are now promoted outside so
   *  I can see what they're doing when the world comes to a premature stop.
   */
  private val filesRemaining = new mutable.HashSet[File]
  private def addFilesRemaining(xs: Traversable[File]) = synchronized { filesRemaining ++= xs }
  private var currentTestFile: File = _
  private var currentFileStart: Long = System.currentTimeMillis

  def currentFileElapsed = (System.currentTimeMillis - currentFileStart) / 1000
  def forceTimeout() = {
    println("Let's see what them threads are doing before I kill that test.")
    sys.allThreads foreach { t =>
      println(t)
      t.getStackTrace foreach println
      println("")
    }
    currentTimerTask.kick()
  }

  /** This does something about absolute paths and file separator
   *  chars before diffing.
   */
  private def replaceSlashes(dir: File, s: String): String = {
    val path = dir.getAbsolutePath+File.separator
    val line = s indexOf path match {
      case -1   => s
      case idx  => (s take idx) + (s drop idx + path.length)
    }
    line.replace('\\', '/')
  }

  private def currentFileString = {
    "Current test file is: %s\n  Started: %s (%s seconds ago)\n  Current time: %s".format(
      currentTestFile,
      new java.util.Date(currentFileStart),
      currentFileElapsed,
      new java.util.Date()
    )
  }
  private def getNextFile(): File = synchronized {
    if (filesRemaining.isEmpty) null
    else {
      currentTestFile = filesRemaining.head
      filesRemaining -= currentTestFile
      currentFileStart = System.currentTimeMillis
      currentTestFile
    }
  }
  // maps canonical file names to the test result (0: OK, 1: FAILED, 2: TIMOUT)
  private val status = new mutable.HashMap[String, Int]
  private def updateStatus(key: String, num: Int) = synchronized {
    status(key) = num
  }
  override def toString = (
    ">> Partest Worker in state " + getState + ":\n" +
    currentFileString + "\n" +
    "There are " + filesRemaining.size + " files remaining:\n" +
    filesRemaining.toList.sortBy(_.toString).map("  " + _ + "\n").mkString("") +
    "\nstatus hashmap contains " + status.size + " entries:\n" +
    status.toList.map(x => "  " + x._1 + " -> " + x._2).sorted.mkString("\n") + "\n"
  )

  def workerError(msg: String): Unit = reporter.error(
    FakePos("scalac"),
    msg + "\n  scalac -help  gives more information"
  )

  def act() {
    react {
      case RunTests(kind, files) =>
        //NestUI.normal("received "+files.length+" to test")
        val master = sender
        runTests(kind, files) { results =>
          master ! Results(results, createdLogFiles, createdOutputDirs)
        }
    }
  }

  def printInfoStart(file: File, printer: PrintWriter) {
    NestUI.outline("testing: ", printer)
    val filesdir = file.getAbsoluteFile.getParentFile.getParentFile
    val testdir = filesdir.getParentFile
    val totalWidth = 56
    val name = {
      // 1. try with [...]/files/run/test.scala
      val name = file.getAbsolutePath drop testdir.getAbsolutePath.length
      if (name.length <= totalWidth) name
      // 2. try with [...]/run/test.scala
      else file.getAbsolutePath drop filesdir.getAbsolutePath.length
    }
    NestUI.normal("[...]%s%s".format(name, " " * (totalWidth - name.length)), printer)
  }

  def printInfoEnd(success: Boolean, printer: PrintWriter) {
    NestUI.normal("[", printer)
    if (success) NestUI.success("  OK  ", printer)
    else NestUI.failure("FAILED", printer)
    NestUI.normal("]\n", printer)
  }

  def printInfoTimeout(printer: PrintWriter) {
    NestUI.normal("[", printer)
    NestUI.failure("TIMOUT", printer)
    NestUI.normal("]\n", printer)
  }

  var log = ""
  var createdLogFiles: List[LogFile] = Nil
  var createdOutputDirs: List[File] = Nil

  def createLogFile(file: File, kind: String): LogFile = {
    val logFile = fileManager.getLogFile(file, kind)
    createdLogFiles ::= logFile
    logFile
  }

  def createOutputDir(dir: File, fileBase: String, kind: String): File = {
    val outDir = Path(dir) / Directory("%s-%s.obj".format(fileBase, kind))
    outDir.createDirectory()
    createdOutputDirs ::= outDir.jfile
    outDir.jfile
  }

  def javac(outDir: File, files: List[File], output: File): Boolean = {
    // compile using command-line javac compiler
    val cmd = "%s -d %s -classpath %s %s".format(
      javacCmd,
      outDir.getAbsolutePath,
      join(outDir.toString, CLASSPATH),
      files mkString " "
    )
    def fail(msg: String) = {
      SFile(output) appendAll msg
      false
    }
    try runCommand(cmd, output) match {
      case 0    => true
      case code => fail("javac failed with exit code " + code + "\n" + cmd + "\n")
    }
    catch exHandler(output, "javac command '" + cmd + "' failed:\n")
  }

  /** Runs command redirecting standard out and
   *  error out to output file.
   */
  def runCommand(command: String, output: File): Int = {
    NestUI.verbose("running command:\n"+command)
    (command #> output !)
  }

  def execTest(outDir: File, logFile: File, fileBase: String) {
    // check whether there is a ".javaopts" file
    val argsFile  = new File(logFile.getParentFile, fileBase + ".javaopts")
    val argString = file2String(argsFile)
    if (argString != "")
      NestUI.verbose("Found javaopts file '%s', using options: '%s'".format(argsFile, argString))

    // Note! As this currently functions, JAVA_OPTS must precede argString
    // because when an option is repeated to java only the last one wins.
    // That means until now all the .javaopts files were being ignored because
    // they all attempt to change options which are also defined in
    // partest.java_opts, leading to debug output like:
    //
    // debug: Found javaopts file 'files/shootout/message.scala-2.javaopts', using options: '-Xss32k'
    // debug: java -Xss32k -Xss2m -Xms256M -Xmx1024M -classpath [...]
    val extras = if (isPartestDebug) List("-Dpartest.debug=true") else Nil
    val propertyOptions = List(
      "-Djava.library.path="+logFile.getParentFile.getAbsolutePath,
      "-Dpartest.output="+outDir.getAbsolutePath,
      "-Dpartest.lib="+LATEST_LIB,
      "-Dpartest.cwd="+outDir.getParent,
      "-Djavacmd="+JAVACMD,
      "-Djavaccmd="+javacCmd,
      "-Duser.language=en -Duser.country=US"
    ) ++ extras

    val cmd = (
      List(
        JAVACMD,
        JAVA_OPTS,
        argString,
        "-classpath " + join(outDir.toString, CLASSPATH)
      ) ++ propertyOptions ++ List(
        "scala.tools.nsc.MainGenericRunner",
        "-usejavacp",
        "Test",
        "jvm"
      )
    ) mkString " "

    runCommand(cmd, logFile)

    if (fileManager.showLog)
      // produce log as string in `log`
      log = SFile(logFile).slurp()
  }

  def getCheckFile(dir: File, fileBase: String, kind: String) = {
    def chkFile(s: String) = Directory(dir) / "%s%s.check".format(fileBase, s)
    val checkFile = if (chkFile("").isFile) chkFile("") else chkFile("-" + kind)

    Some(checkFile) filter (_.canRead)
  }

  def existsCheckFile(dir: File, fileBase: String, kind: String) =
    getCheckFile(dir, fileBase, kind).isDefined

  def compareOutput(dir: File, fileBase: String, kind: String, logFile: File): String =
    // if check file exists, compare with log file
    getCheckFile(dir, fileBase, kind) match {
      case Some(f)  =>
        val diff = fileManager.compareFiles(logFile, f.jfile)
        if (diff != "" && fileManager.updateCheck) {
          NestUI.verbose("output differs from log file: updating checkfile\n")
          f.toFile writeAll file2String(logFile)
          ""
        }
        else diff
      case _        => file2String(logFile)
    }

  def file2String(f: File) =
    try SFile(f).slurp()
    catch { case _: FileNotFoundException => "" }

  def isJava(f: File) = SFile(f) hasExtension "java"
  def isScala(f: File) = SFile(f) hasExtension "scala"
  def isJavaOrScala(f: File) = isJava(f) || isScala(f)

  def outputLogFile(logFile: File) {
    val lines = SFile(logFile).lines
    if (lines.nonEmpty) {
      NestUI.normal("Log file '" + logFile + "': \n")
      lines foreach (x => NestUI.normal(x + "\n"))
    }
  }
  def exHandler(logFile: File): PartialFunction[Throwable, Boolean] = exHandler(logFile, "")
  def exHandler(logFile: File, msg: String): PartialFunction[Throwable, Boolean] = {
    case e: Exception =>
      SFile(logFile).writeAll(msg, stackTraceString(e))
      outputLogFile(logFile) // if running the test threw an exception, output log file
      false
  }

  /** Runs a list of tests.
   *
   * @param kind  The test kind (pos, neg, run, etc.)
   * @param files The list of test files
   */
  def runTests(kind: String, files: List[File])(topcont: Map[String, Int] => Unit) {
    val compileMgr = new CompileManager(fileManager)
    if (kind == "scalacheck") fileManager.CLASSPATH += File.pathSeparator + PathSettings.scalaCheck

    // You don't default "succeeded" to true.
    var succeeded = false
    var errors = 0
    var diff = ""
    var log = ""

    def initNextTest() = {
      val swr = new StringWriter
      val wr  = new PrintWriter(swr)
      diff    = ""
      log     = ""

      ((swr, wr))
    }

    def fail(what: Any) = {
      NestUI.verbose("scalac: compilation of "+what+" failed\n")
      false
    }
    def diffCheck(latestDiff: String) = {
      diff = latestDiff
      succeeded = diff == ""
      succeeded
    }

    def timed[T](body: => T): (T, Long) = {
      val t1 = System.currentTimeMillis
      val result = body
      val t2 = System.currentTimeMillis

      (result, t2 - t1)
    }

    /** 1. Creates log file and output directory.
     *  2. Runs script function, providing log file and output directory as arguments.
     */
    def runInContext(file: File, kind: String, script: (File, File) => Boolean): LogContext = {
      // When option "--failed" is provided, execute test only if log file is present
      // (which means it failed before)
      val logFile = createLogFile(file, kind)

      if (fileManager.failed && !logFile.canRead)
        LogContext(logFile, None)
      else {
        val (swr, wr) = initNextTest()
        printInfoStart(file, wr)

        val fileBase: String = basename(file.getName)
        NestUI.verbose(this+" running test "+fileBase)
        val dir = file.getParentFile
        val outDir = createOutputDir(dir, fileBase, kind)
        NestUI.verbose("output directory: "+outDir)

        // run test-specific code
        succeeded = try {
          if (isPartestDebug) {
            val (result, millis) = timed(script(logFile, outDir))
            fileManager.recordTestTiming(file.getPath, millis)
            result
          }
          else script(logFile, outDir)
        }
        catch exHandler(logFile)

        LogContext(logFile, Some((swr, wr)))
      }
    }

    def compileFilesIn(dir: File, kind: String, logFile: File, outDir: File): Boolean = {
      val testFiles = dir.listFiles.toList filter isJavaOrScala

      def isInGroup(f: File, num: Int) = SFile(f).stripExtension endsWith ("_" + num)
      val groups = (0 to 9).toList map (num => testFiles filter (f => isInGroup(f, num)))
      val noGroupSuffix = testFiles filterNot (groups.flatten contains)

      def compileGroup(g: List[File]): Boolean = {
        val (scalaFiles, javaFiles) = g partition isScala
        val allFiles = javaFiles ++ scalaFiles

        // scala+java, then java, then scala
        (scalaFiles.isEmpty || compileMgr.shouldCompile(outDir, allFiles, kind, logFile) || fail(g)) && {
          (javaFiles.isEmpty || javac(outDir, javaFiles, logFile)) && {
            (scalaFiles.isEmpty || compileMgr.shouldCompile(outDir, scalaFiles, kind, logFile) || fail(scalaFiles))
          }
        }
      }

      (noGroupSuffix.isEmpty || compileGroup(noGroupSuffix)) && (groups forall compileGroup)
    }

    def failCompileFilesIn(dir: File, kind: String, logFile: File, outDir: File): Boolean = {
      val testFiles   = dir.listFiles.toList
      val sourceFiles = testFiles filter isJavaOrScala

      sourceFiles.isEmpty || compileMgr.shouldFailCompile(outDir, sourceFiles, kind, logFile) || fail(testFiles filter isScala)
    }

    def runTestCommon(file: File, kind: String, expectFailure: Boolean)(
      onSuccess: (File, File) => Boolean,
      onFail: (File, File) => Unit = (_, _) => ()): LogContext =
    {
      runInContext(file, kind, (logFile: File, outDir: File) => {
        val result =
          if (file.isDirectory) {
            if (expectFailure) failCompileFilesIn(file, kind, logFile, outDir)
            else compileFilesIn(file, kind, logFile, outDir)
          }
          else {
            if (expectFailure) compileMgr.shouldFailCompile(List(file), kind, logFile)
            else compileMgr.shouldCompile(List(file), kind, logFile)
          }

        if (result) onSuccess(logFile, outDir)
        else { onFail(logFile, outDir) ; false }
      })
    }

    def runJvmTest(file: File, kind: String): LogContext =
      runTestCommon(file, kind, expectFailure = false)((logFile, outDir) => {
        val fileBase = basename(file.getName)
        val dir      = file.getParentFile

        execTest(outDir, logFile, fileBase)
        diffCheck(compareOutput(dir, fileBase, kind, logFile))
      })

    def processSingleFile(file: File): LogContext = kind match {
      case "scalacheck" =>
        val succFn: (File, File) => Boolean = { (logFile, outDir) =>
          NestUI.verbose("compilation of "+file+" succeeded\n")

          val outURL    = outDir.getCanonicalFile.toURI.toURL
          val logWriter = new PrintStream(new FileOutputStream(logFile))

          Output.withRedirected(logWriter) {
            // this classloader is test specific: its parent contains library classes and others
            ScalaClassLoader.fromURLs(List(outURL), params.scalaCheckParentClassLoader).run("Test", Nil)
          }

          NestUI.verbose(file2String(logFile))
          // obviously this must be improved upon
          val lines = SFile(logFile).lines map (_.trim) filterNot (_ == "") toBuffer;
          if (lines forall (x => !x.startsWith("!"))) {
            NestUI.verbose("test for '" + file + "' success: " + succeeded)
            true
          }
          else {
            NestUI.normal("ScalaCheck test failed. Output:\n")
            lines foreach (x => NestUI.normal(x + "\n"))
            false
          }
        }
        runTestCommon(file, kind, expectFailure = false)(
          succFn,
          (logFile, outDir) => outputLogFile(logFile)
        )

      case "pos" =>
        runTestCommon(file, kind, expectFailure = false)(
          (logFile, outDir) => true,
          (_, _) => ()
        )

      case "neg" =>
        runTestCommon(file, kind, expectFailure = true)((logFile, outDir) => {
          // compare log file to check file
          val fileBase = basename(file.getName)
          val dir      = file.getParentFile

          diffCheck(
            // diff is contents of logFile
            if (!existsCheckFile(dir, fileBase, kind)) file2String(logFile)
            else compareOutput(dir, fileBase, kind, logFile)
          )
        })

      case "run" | "jvm" =>
        runJvmTest(file, kind)

      case "buildmanager" =>
        val logFile = createLogFile(file, kind)
        if (!fileManager.failed || logFile.canRead) {
          val (swr, wr) = initNextTest()
          printInfoStart(file, wr)
          val (outDir, testFile, changesDir, fileBase) = (
            if (!file.isDirectory)
              (null, null, null, null)
            else {
              val fileBase: String = basename(file.getName)
              NestUI.verbose(this+" running test "+fileBase)
              val outDir = createOutputDir(file, fileBase, kind)
              if (!outDir.exists) outDir.mkdir()
              val testFile = new File(file, fileBase + ".test")
              val changesDir = new File(file, fileBase + ".changes")

              if (changesDir.isFile || !testFile.isFile) {
                // if changes exists then it has to be a dir
                if (!testFile.isFile) NestUI.verbose("invalid build manager test file")
                if (changesDir.isFile) NestUI.verbose("invalid build manager changes directory")
                (null, null, null, null)
              }
              else {
                copyTestFiles(file, outDir)
                NestUI.verbose("outDir:  "+outDir)
                NestUI.verbose("logFile: "+logFile)
                (outDir, testFile, changesDir, fileBase)
              }
            }
          )

          if (outDir != null) {
            // Pre-conditions satisfied
            try {
              val sourcepath = outDir.getAbsolutePath+File.separator

              // configure input/output files
              val logWriter = new PrintStream(new FileOutputStream(logFile))
              val testReader = new BufferedReader(new FileReader(testFile))
              val logConsoleWriter = new PrintWriter(logWriter)

              // create proper settings for the compiler
              val settings = new Settings(workerError)
              settings.outdir.value = outDir.getCanonicalFile.getAbsolutePath
              settings.sourcepath.value = sourcepath
              settings.classpath.value = fileManager.CLASSPATH
              settings.Ybuildmanagerdebug.value = true

              // simulate Build Manager loop
              val prompt = "builder > "
              reporter = new ConsoleReporter(settings, scala.Console.in, logConsoleWriter)
              val bM: BuildManager =
                  new RefinedBuildManager(settings) {
                    override protected def newCompiler(settings: Settings) =
                        new BuilderGlobal(settings, reporter)
                  }

              val testCompile: String => Boolean = { line =>
                NestUI.verbose("compiling " + line)
                val args = (line split ' ').toList
                val command = new CompilerCommand(args, settings)
                command.ok && {
                  bM.update(filesToSet(settings.sourcepath.value, command.files), Set.empty)
                  !reporter.hasErrors
                }
              }

              val updateFiles = (line: String) => {
                NestUI.verbose("updating " + line)
                val res =
                  ((line split ' ').toList).forall(u => {
                    (u split "=>").toList match {
                        case origFileName::(newFileName::Nil) =>
                          val newFile = new File(changesDir, newFileName)
                          if (newFile.isFile) {
                            val v = overwriteFileWith(new File(outDir, origFileName), newFile)
                            if (!v)
                              NestUI.verbose("'update' operation on " + u + " failed")
                            v
                          } else {
                            NestUI.verbose("File " + newFile + " is invalid")
                            false
                          }
                        case a =>
                          NestUI.verbose("Other =: " + a)
                          false
                    }
                  })
                NestUI.verbose("updating " + (if (res) "succeeded" else "failed"))
                res
              }

              def loop(): Boolean = {
                testReader.readLine() match {
                  case null | ""    =>
                    NestUI.verbose("finished")
                    true
                  case s if s startsWith ">>update "  =>
                    updateFiles(s stripPrefix ">>update ") && loop()
                  case s if s startsWith ">>compile " =>
                    val files = s stripPrefix ">>compile "
                    logWriter.println(prompt + files)
                    // In the end, it can finish with an error
                    if (testCompile(files)) loop()
                    else {
                      val t = testReader.readLine()
                      (t == null) || (t == "")
                    }
                  case s =>
                    NestUI.verbose("wrong command in test file: " + s)
                    false
                }
              }

              Output.withRedirected(logWriter) {
                try loop()
                finally testReader.close()
              }
              fileManager.mapFile(logFile, "tmp", file, _.replace(sourcepath, "").
                      replaceAll(java.util.regex.Matcher.quoteReplacement("\\"), "/"))

              diffCheck(compareOutput(file, fileBase, kind, logFile))
            }
            LogContext(logFile, Some((swr, wr)))
          } else
            LogContext(logFile, None)
        } else
          LogContext(logFile, None)

      case "res" => {
          // simulate resident compiler loop
          val prompt = "\nnsc> "

          // when option "--failed" is provided
          // execute test only if log file is present
          // (which means it failed before)

          //val (logFileOut, logFileErr) = createLogFiles(file, kind)
          val logFile = createLogFile(file, kind)
          if (!fileManager.failed || logFile.canRead) {
            val (swr, wr) = initNextTest()
            printInfoStart(file, wr)

            val fileBase: String = basename(file.getName)
            NestUI.verbose(this+" running test "+fileBase)
            val dir = file.getParentFile
            val outDir = createOutputDir(dir, fileBase, kind)
            if (!outDir.exists) outDir.mkdir()
            val resFile = new File(dir, fileBase + ".res")
            NestUI.verbose("outDir:  "+outDir)
            NestUI.verbose("logFile: "+logFile)
            //NestUI.verbose("logFileErr: "+logFileErr)
            NestUI.verbose("resFile: "+resFile)

            // run compiler in resident mode
            // $SCALAC -d "$os_dstbase".obj -Xresident -sourcepath . "$@"
            val sourcedir  = logFile.getParentFile.getCanonicalFile
            val sourcepath = sourcedir.getAbsolutePath+File.separator
            NestUI.verbose("sourcepath: "+sourcepath)

            val argString =
              "-d "+outDir.getCanonicalFile.getAbsolutePath+
              " -Xresident"+
              " -sourcepath "+sourcepath
            val argList = argString split ' ' toList

            // configure input/output files
            val logOut    = new FileOutputStream(logFile)
            val logWriter = new PrintStream(logOut)
            val resReader = new BufferedReader(new FileReader(resFile))
            val logConsoleWriter = new PrintWriter(new OutputStreamWriter(logOut))

            // create compiler
            val settings = new Settings(workerError)
            settings.sourcepath.value = sourcepath
            settings.classpath.value = fileManager.CLASSPATH
            reporter = new ConsoleReporter(settings, scala.Console.in, logConsoleWriter)
            val command = new CompilerCommand(argList, settings)
            object compiler extends Global(command.settings, reporter)

            val resCompile = (line: String) => {
              NestUI.verbose("compiling "+line)
              val cmdArgs = (line split ' ').toList map (fs => new File(dir, fs).getAbsolutePath)
              NestUI.verbose("cmdArgs: "+cmdArgs)
              val sett = new Settings(workerError)
              sett.sourcepath.value = sourcepath
              val command = new CompilerCommand(cmdArgs, sett)
              command.ok && {
                (new compiler.Run) compile command.files
                !reporter.hasErrors
              }
            }

            def loop(action: String => Boolean): Boolean = {
              logWriter.print(prompt)
              resReader.readLine() match {
                case null | ""  => logWriter.flush() ; true
                case line       => action(line) && loop(action)
              }
            }

            Output.withRedirected(logWriter) {
              try loop(resCompile)
              finally resReader.close()
            }
            fileManager.mapFile(logFile, "tmp", dir, replaceSlashes(dir, _))
            diffCheck(compareOutput(dir, fileBase, kind, logFile))
            LogContext(logFile, Some((swr, wr)))
          } else
            LogContext(logFile, None)
        }

      case "shootout" => {
          // when option "--failed" is provided
          // execute test only if log file is present
          // (which means it failed before)
          val logFile = createLogFile(file, kind)
          if (!fileManager.failed || logFile.canRead) {
            val (swr, wr) = initNextTest()
            printInfoStart(file, wr)

            val fileBase: String = basename(file.getName)
            NestUI.verbose(this+" running test "+fileBase)
            val dir = file.getParentFile
            val outDir = createOutputDir(dir, fileBase, kind)
            if (!outDir.exists) outDir.mkdir()

            // 2. define file {outDir}/test.scala that contains code to compile/run
            val testFile = new File(outDir, "test.scala")
            NestUI.verbose("outDir:   "+outDir)
            NestUI.verbose("logFile:  "+logFile)
            NestUI.verbose("testFile: "+testFile)

            // 3. cat {test}.scala.runner {test}.scala > testFile
            val runnerFile = new File(dir, fileBase+".scala.runner")
            val bodyFile   = new File(dir, fileBase+".scala")
            SFile(testFile).writeAll(
              file2String(runnerFile),
              file2String(bodyFile)
            )

            // 4. compile testFile
            val ok = compileMgr.shouldCompile(List(testFile), kind, logFile)
            NestUI.verbose("compilation of " + testFile + (if (ok) "succeeded" else "failed"))
            if (ok) {
              execTest(outDir, logFile, fileBase)
              NestUI.verbose(this+" finished running "+fileBase)
              diffCheck(compareOutput(dir, fileBase, kind, logFile))
            }

            LogContext(logFile, Some((swr, wr)))
          }
          else
            LogContext(logFile, None)
        }

      case "scalap" =>
        runInContext(file, kind, (logFile: File, outDir: File) => {
          val sourceDir = file.getParentFile
          val sourceDirName = sourceDir.getName

          // 1. Find file with result text
          val results = sourceDir.listFiles(new FilenameFilter {
            def accept(dir: File, name: String) = name == "result.test"
          })

          if (results.length != 1) {
            NestUI.verbose("Result file not found in directory " + sourceDirName + " \n")
            false
          }
          else {
            val resFile = results(0)
            // 2. Compile source file
            if (!compileMgr.shouldCompile(outDir, List(file), kind, logFile)) {
              NestUI.verbose("compilerMgr failed to compile %s to %s".format(file, outDir))
              false
            }
            else {
              // 3. Decompile file and compare results
              val isPackageObject = sourceDir.getName.startsWith("package")
              val className = sourceDirName.capitalize + (if (!isPackageObject) "" else ".package")
              val url = outDir.toURI.toURL
              val loader = new URLClassLoader(Array(url), getClass.getClassLoader)
              val clazz = loader.loadClass(className)

              val byteCode = ByteCode.forClass(clazz)
              val result = scala.tools.scalap.Main.decompileScala(byteCode.bytes, isPackageObject)

              SFile(logFile) writeAll result
              diffCheck(fileManager.compareFiles(logFile, resFile))
            }
          }
        })

      case "script" => {
          // when option "--failed" is provided
          // execute test only if log file is present
          // (which means it failed before)
          val logFile = createLogFile(file, kind)
          if (!fileManager.failed || logFile.canRead) {
            val (swr, wr) = initNextTest()
            printInfoStart(file, wr)

            val fileBase: String = basename(file.getName)
            NestUI.verbose(this+" running test "+fileBase)

            // check whether there is an args file
            val argsFile = new File(file.getParentFile, fileBase+".args")
            NestUI.verbose("argsFile: "+argsFile)
            val argString = file2String(argsFile)

            try {
              val cmdString =
                if (isWin) {
                  val batchFile = new File(file.getParentFile, fileBase+".bat")
                  NestUI.verbose("batchFile: "+batchFile)
                  batchFile.getAbsolutePath
                }
                else file.getAbsolutePath

              succeeded = ((cmdString+argString) #> logFile !) == 0
              diffCheck(compareOutput(file.getParentFile, fileBase, kind, logFile))
            }
            catch { // *catch-all*
              case e: Exception =>
                NestUI.verbose("caught "+e)
                succeeded = false
            }

            LogContext(logFile, Some((swr, wr)))
          } else
            LogContext(logFile, None)
      }
    }

    def reportAll(results: Map[String, Int], cont: Map[String, Int] => Unit) {
      timer.cancel()
      cont(results)
    }

    object TestState {
      val Ok = 0
      val Fail = 1
      val Timeout = 2
    }

    def reportResult(state: Int, logFile: Option[LogFile], writers: Option[(StringWriter, PrintWriter)]) {
      val isGood    = state == TestState.Ok
      val isFail    = state == TestState.Fail
      val isTimeout = state == TestState.Timeout

      if (!isGood) {
        errors += 1
        NestUI.verbose("incremented errors: "+errors)
      }

      // delete log file only if test was successful
      if (isGood && !isPartestDebug)
        logFile foreach (_.toDelete = true)

      writers foreach { case (swr, wr) =>
        if (swr == null || wr == null || fileManager == null || logFile.exists(_ == null)) {
          NestUI.normal("Something is wrong, why are you sending nulls here?")
          NestUI.normal(List(swr, wr, fileManager, logFile) mkString " ")
        }
        else {
          if (isTimeout) printInfoTimeout(wr)
          else printInfoEnd(isGood, wr)
          wr.flush()
          swr.flush()
          NestUI.normal(swr.toString)
          if (isFail && fileManager.showDiff && diff != "")
            NestUI.normal(diff)
          if (isFail && fileManager.showLog)
            logFile foreach showLog
        }
      }
    }

    if (files.isEmpty) reportAll(Map(), topcont)
    else addFilesRemaining(files)

    var done = false

    Actor.loopWhile(!done) {
      val parent = self

      actor {
        val testFile = getNextFile()
        if (testFile == null) done = true
        else {
          updateTimerTask(parent ! Timeout(testFile))

          val context =
            try processSingleFile(testFile)
            catch {
              case t: Throwable =>
                NestUI.shout("Caught something while invoking processSingleFile(%s)".format(testFile))
                t.printStackTrace
                NestUI.normal("There were " + filesRemaining.size + " files remaining: " + filesRemaining.mkString(", "))
                LogContext(null, None)
            }
          parent ! Result(testFile, context)
        }
      }

      react {
        case res: TestResult =>
          val path = res.file.getCanonicalPath
          if (status contains path) {
            // ignore message
            NestUI.debug("Why are we receiving duplicate messages? Received: " + res + "\nPath is " + path)
          }
          else res match {
            case Timeout(_) =>
              updateStatus(path, TestState.Timeout)
              val swr = new StringWriter
              val wr = new PrintWriter(swr)
              printInfoStart(res.file, wr)
              reportResult(TestState.Timeout, None, Some((swr, wr)))
            case Result(_, logs) =>
              val state = if (succeeded) TestState.Ok else TestState.Fail
              updateStatus(path, state)
              reportResult(
                state,
                Option(logs) map (_.file),
                Option(logs) flatMap (_.writers)
              )
          }
          if (filesRemaining.isEmpty) {
            cancelTimerTask()
            reportAll(status.toMap, topcont)
          }
      }
    }
  }

  private def filesToSet(pre: String, fs: List[String]): Set[AbstractFile] =
    fs flatMap (s => Option(AbstractFile getFile (pre + s))) toSet

  private def copyTestFiles(testDir: File, destDir: File) {
    val invalidExts = List("changes", "svn", "obj")
    testDir.listFiles.toList filter (
            f => (isJavaOrScala(f) && f.isFile) ||
                 (f.isDirectory && !(invalidExts.contains(SFile(f).extension)))) foreach
      { f => fileManager.copyFile(f, destDir) }
  }

  def showLog(logFile: File) {
    file2String(logFile) match {
      case "" if logFile.canRead  => ()
      case ""                     => NestUI.failure("Couldn't open log file: " + logFile + "\n")
      case s                      => NestUI.normal(s)
    }
  }
}
