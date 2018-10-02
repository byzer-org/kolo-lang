package org.apache.spark.util

import java.io._
import java.util.concurrent.atomic.AtomicReference

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.sql.types.DataType
import org.apache.spark.util.ExternalCommandRunner.{MonitorThread, log, logBuilder}
import org.apache.spark.util.ObjPickle.pickle
import streaming.log.Logging

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.collection.JavaConverters._

class PythonProjectExecuteRunner(taskDirectory: String,
                                 envVars: Map[String, String] = Map(),
                                 recordLog: Any => Any = (msg: Any) => msg,
                                 logCallback: (String) => Unit = (msg: String) => {},
                                 separateWorkingDir: Boolean = true,
                                 bufferSize: Int = 1024,
                                 encoding: String = "utf-8") extends Logging {
  def run(command: Seq[String],
          params: Any,
          schema: DataType,
          scriptContent: String,
          scriptName: String,
          validateData: Array[Array[Byte]] = Array()

         ) = {
    val errorBuffer = ArrayBuffer[String]()
    val pb = new ProcessBuilder(command.asJava)
    // Add the environmental variables to the process.
    val currentEnvVars = pb.environment()
    envVars.foreach { case (variable, value) => currentEnvVars.put(variable, value) }
    logCallback(envVars.map(f => s"env:\n${f._1}:${f._2}").mkString("\n"))
    // When spark.worker.separated.working.directory option is turned on, each
    // task will be run in separate directory. This should be resolve file
    // access conflict issue
    var workInTaskDirectory = false
    if (separateWorkingDir) {
      val currentDir = new File(".")
      log.debug("currentDir = " + currentDir.getAbsolutePath())
      val taskDirFile = new File(taskDirectory)
      taskDirFile.mkdirs()

      try {
        // Need to add symlinks to jars, files, and directories.  On Yarn we could have
        // directories and other files not known to the SparkContext that were added via the
        // Hadoop distributed cache.  We also don't want to symlink to the /tasks directories we
        // are creating here.
        //        for (file <- currentDir.list(tasksDirFilter)) {
        //          val fileWithDir = new File(currentDir, file)
        //          Utils.symlink(new File(fileWithDir.getAbsolutePath()),
        //            new File(taskDirectory + File.separator + fileWithDir.getName()))
        //        }
        pb.directory(taskDirFile)
        workInTaskDirectory = true
      } catch {
        case e: Exception => logCallback("Unable to setup task working directory: " + e.getMessage +
          " (" + taskDirectory + ")")
      }
    }

    def pickleFile(name: String, fileName: String, value: Any) = {
      val fileTemp = new File(taskDirectory + "/" + fileName + ".pickle")
      currentEnvVars.put(name, fileTemp.getPath)
      val pythonTempFile = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fileTemp)))
      pickle(value, pythonTempFile)
    }
    //使用pickle 把数据写到work目录，之后python程序会读取。首先是保存配置信息。
    pickleFile("pickleFile", "python_temp", params)
    pickleFile("validateFile", "validate_table", validateData)

    def saveFile(scriptName: String, scriptContent: String) = {
      val scriptFile = new File(taskDirectory + s"/${scriptName}")
      val fw = new FileWriter(scriptFile)
      try {
        fw.write(scriptContent)
      } finally {
        fw.close()
      }
    }

    if (scriptName != null && !scriptName.isEmpty) {
      saveFile(scriptName, scriptContent)
    }

    def savePythonFile(name: String) = {
      val msg_queue = Source.fromInputStream(ExternalCommandRunner.getClass.getResourceAsStream("/python/" + name)).
        getLines().mkString("\n")
      saveFile(name, msg_queue)
    }

    savePythonFile("msg_queue.py")
    savePythonFile("mlsql.py")
    savePythonFile("python_fun.py")

    val env = SparkEnv.get
    val proc = pb.start()

    new MonitorThread(env, proc, TaskContext.get(), taskDirectory, command.mkString(" ")).start()

    val childThreadException = new AtomicReference[Throwable](null)
    // Start a thread to print the process's stderr to ours
    new Thread(s"stderr reader for $command") {
      override def run(): Unit = {
        val err = proc.getErrorStream

        try {
          val errorLog = logBuilder(Source.fromInputStream(err)(encoding).getLines())
          logCallback(errorLog)
        } catch {
          case t: Throwable =>
            childThreadException.set(t)
        } finally {
          err.close()
        }
      }
    }.start()

    // Start a thread to feed the process input from our parent's iterator
    new Thread(s"stdin writer for $command") {
      override def run(): Unit = {
        val out = new PrintWriter(new BufferedWriter(
          new OutputStreamWriter(proc.getOutputStream, encoding), bufferSize))
        try {
          // scalastyle:off println
          // out.println()
          // scalastyle:on println
        } catch {
          case t: Throwable => childThreadException.set(t)
        } finally {
          out.close()
        }
      }
    }.start()

    // Return an iterator that read lines from the process's stdout
    val lines = Source.fromInputStream(proc.getInputStream)(encoding).getLines
    new Iterator[String] {
      def next(): String = {
        if (!hasNext()) {
          throw new NoSuchElementException()
        }
        val line = lines.next()
        line
      }

      def hasNext(): Boolean = {
        val result = if (lines.hasNext) {
          true
        } else {
          val exitStatus = try {
            proc.waitFor()
          }
          catch {
            case e: InterruptedException =>
              0
          }
          cleanup()
          if (exitStatus != 0) {
            val msg = s"Subprocess exited with status $exitStatus. " +
              s"Command ran: " + command.mkString(" ")
            errorBuffer += msg
            logCallback(errorBuffer.mkString("\t"))
            recordLog(errorBuffer.toIterator)
            throw new IllegalStateException(msg)
          }
          false
        }
        propagateChildException()
        result
      }

      def getWorker: Process = {
        proc
      }

      private def cleanup(): Unit = {
        // cleanup task working directory if used
        if (workInTaskDirectory) {
          scala.util.control.Exception.ignoring(classOf[IOException]) {
            Utils.deleteRecursively(new File(taskDirectory))
          }
          log.debug(s"Removed task working directory $taskDirectory")
        }
      }

      private def propagateChildException(): Unit = {
        val t = childThreadException.get()
        if (t != null) {
          val commandRan = command.mkString(" ")
          val msg = s"Caught exception while running pipe() operator. Command ran: $commandRan. " +
            s"Exception: ${t.getMessage}"
          errorBuffer += msg
          logCallback(errorBuffer.mkString("\t"))
          proc.destroy()
          cleanup()
          recordLog(errorBuffer.toIterator)
          throw t
        }
      }
    }
  }
}
