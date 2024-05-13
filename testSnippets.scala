package com.kubuszok.scalaclimdspec

import java.io.File
import java.nio.file.{Files, Path}
import scala.Console.{GREEN, MAGENTA, RED, RESET, YELLOW}
import scala.collection.immutable.ListMap
import scala.util.Using
import scala.util.matching.Regex

// reporting utils

extension (s: StringContext)
  def hl(args: Any*): String = s"$MAGENTA${s.s(args*)}$RESET"
  def red(args: Any*): String = s"$RED${s.s(args*)}$RESET"
  def green(args: Any*): String = s"$GREEN${s.s(args*)}$RESET"
  def yellow(args: Any*): String = s"$YELLOW${s.s(args*)}$RESET"

// execution  utils

/** Result of a sunchronous command run,
  *
  * @param exitCode code returned by the command
  * @param out standard output printed by the command (ANSI coloring stripped)
  * @param err standard error printed by the command (ANSI coloring stripped)
  * @param outErr standard output AND error printed by the command (ANSI coloring stripped, preserves the order in which program wrote to both streams)
  */
case class RunResult(
    exitCode: Int,
    out: String,
    err: String,
    outErr: String
)
object RunResult {

  import java.io.{ByteArrayOutputStream, FilterOutputStream, OutputStream}
  import scala.sys.process.*

  class Broadcast(out1: OutputStream, out2: OutputStream) extends OutputStream {

    def write(b: Int): Unit = {
      out1.write(b)
      out2.write(b)
    }
  }

  class FilterANSIConsole(out: OutputStream) extends FilterOutputStream(out) {
    private enum State:
      case NoAnsi
      case Esc
      case LeftBracket
      case Digit

    private var state = State.NoAnsi

    override def write(b: Int): Unit = state match {
      case State.NoAnsi if b == 0x1b             => state = State.Esc
      case State.NoAnsi                          => super.write(b)
      case State.Esc if b.toChar == '['          => state = State.LeftBracket
      case State.LeftBracket if b.toChar.isDigit => state = State.Digit
      case State.Digit if b.toChar.isDigit       => ()
      case State.Digit if b.toChar == 'm'        => state = State.NoAnsi
      case _                                     => throw new AssertionError(s"Unexpected character: $b (${b.toChar})")
    }
  }

  extension (os: OutputStream)
    def &&(os2: OutputStream): OutputStream = Broadcast(os, os2)
    def noAnsiConsole: OutputStream = FilterANSIConsole(os)

  def apply(str: String*): RunResult =
    Using.Manager { use =>
      val sb = new StringBuffer
      val out = use(ByteArrayOutputStream())
      val err = use(ByteArrayOutputStream())
      val outErr = use(ByteArrayOutputStream())
      val exitValue: Int = str
        .run(
          BasicIO(withIn = false, buffer = sb, log = None)
            .withOutput(in => in.transferTo((out && outErr).noAnsiConsole && sys.process.stdout))
            .withError(in => in.transferTo((err && outErr).noAnsiConsole && sys.process.stderr))
        )
        .exitValue()
      RunResult(exitValue.toInt, out.toString, err.toString, outErr.toString)
    }.get
}

// models

/** Represents the read markdown file.
  *
  * @param name the name of the original file
  * @param content raw content of the file 
  */
case class Markdown(name: Markdown.Name, content: List[String]) {

  def extractAll: List[Snippet] = Snippet.extractAll(this)
}
object Markdown {

  /** Smart name of markdown file.
   * 
   * @param simpleName name without extension
   * @param fileName name with extension
   */
  case class Name(simpleName: String, fileName: String)

  def readFromFile(markdownFile: File): Option[Markdown] =
    Option(markdownFile.getName())
      .filter(fileName => fileName.toLowerCase().endsWith(".md") ||fileName.endsWith(".markdown"))
      .flatMap { fileName =>
        Using(io.Source.fromFile(markdownFile)) { src =>
          val simpleName = if fileName.toLowerCase.endsWith(".md") then fileName.substring(0, fileName.length() - ".md".length())
                           else fileName.substring(0, fileName.length() - ".markdown".length())
          Markdown(Name(simpleName, fileName), src.getLines().toList)
        }.toOption
      }

  def readFromDir(dir: File): List[Markdown] =
    Option(dir.listFiles()).toList.flatMap(_.sortBy(_.getName())).flatMap { file =>
      if file.isDirectory() then readFromDir(file) else readFromFile(file).toList
    }
}

/** Represents the runnable Scala CLI snippet - both standalone and make of multiple files.
  * 
  * @param locations where each piece of code started
  * @param content one or more pieces of code that could written to a single directory and run with Scala CLI
  */
case class Snippet(locations: List[Snippet.Location], content: Snippet.Content) {

  /** Where the first piece of code started */
  val location: Snippet.Location = locations.head
  export location.{dirName, stableName, hint}

  def combine(another: Snippet): Snippet =
    Snippet(locations ++ another.locations, content.combine(another.content))
}
object Snippet {

  def apply(location: Snippet.Location, content: Snippet.Content): Snippet = apply(List(location), content)

  /** Location of the piece of code in the markdown file.
    * 
    * @param markdown in which file code was found
    * @param lineNo in which line number code started
    * @param section in which section code was found
    * @param ordinal which piece of code in this section it was
    */
  case class Location(markdown: Markdown.Name, lineNo: Int, section: String, ordinal: Int) {
    lazy val dirName: String =
      (if section.isEmpty then s"${markdown.simpleName}_$ordinal" else s"${markdown.simpleName}_${section}_$ordinal")
        .replaceAll(" +", "-")
        .replaceAll("[^A-Za-z0-9_-]+", "")
    lazy val stableName: String =
      if section.isEmpty then s"${markdown.fileName}[$ordinal]" else s"${markdown.fileName}#$section[$ordinal]"
    lazy val hint: String = s"${markdown.fileName}:$lineNo"
  }
  object Location {
    given Ordering[Location] = Ordering.by[Location, String](_.markdown.fileName).orElseBy(_.lineNo)
  }

  /** Content of Scala CLI snippet. */
  enum Content:
    /** Content of a single file.
      *
      * @param content content to write into .sc/.scala file
      */
    case Single(content: String)
    /** Mapping between provided file names and their content.
      * 
      * @param files mapping between file name and its content
      */
    case Multiple(files: ListMap[String, Content.Single])

    def fileToContentMap: ListMap[String, Content.Single] = this match {
      case single @ Single(_) => ListMap("snippet.sc" -> single)
      case Multiple(files)    => files
    }

    def combine(another: Snippet.Content): Snippet.Content = (this, another) match
      case (Single(content1), Single(content2)) => Single(s"$content1\n\n$content2")
      case _ =>
        val files1 = fileToContentMap
        val files2 = another.fileToContentMap
        val files = ListMap.from((files1.keys ++ files2.keys).toList.distinct.map[(String, Snippet.Content.Single)] { fileName =>
          (files1.get(fileName), files2.get(fileName)) match
            // snippets with the same fileName are merged into 1 file
            case (Some(content1: Snippet.Content.Single), Some(content2: Snippet.Content.Single)) =>
              fileName -> content1.combine(content2).asInstanceOf[Single]
            case (Some(content), None) => fileName -> content
            case (None, Some(content)) => fileName -> content
            case (None, None) => ??? // should never happen
        })
        Multiple(files)
    

  def extractAll(markdown: Markdown): List[Snippet] = {
    def mkLocation(section: String, lineNo: Int = 1, ordinal: Int = 0): Location =
      Location(markdown.name, lineNo, section, ordinal)

    extension (location: Location)
      def next(lineNo: Int): Location = location.copy(lineNo = lineNo, ordinal = location.ordinal + 1)
      def toSnippet(content: String): Snippet = Snippet(location, Content.Single(content))

    enum Mode:
      case Reading(indent: Int, content: Vector[String])
      case Awaiting

    import Mode.*


    val start = raw"(\s*)```(scala|java)(.*)".r
    val end = raw"(\s*)```\s*".r
    val sectionName = "#+(.+)".r

    def loop(remainingContent: List[(String, Int)], location: Location, mode: Mode, result: Vector[Snippet]): List[Snippet] =
      (remainingContent, mode) match {
        // ``` terminates snippet reading
        case ((end(_), _) :: lines, Reading(indent, content)) =>
          loop(
            lines,
            location,
            Awaiting,
            result :+ location.toSnippet(content.mkString("\n"))
          )
        // ``` not reached, we're stil lreading snippet
        case ((line, _) :: lines, Reading(indent, content)) =>
          loop(
            lines,
            location,
            Reading(indent, content :+ (if line.length() > indent then line.substring(indent) else line)),
            result
          )
        // ```scala found, we're reading snippet starting from the next line
        case ((start(indent, _, _), lineNo) :: lines, Awaiting) =>
          loop(lines, location.next(lineNo), Reading(indent.length(), Vector.empty), result)
        // # section name
        case ((sectionName(section), lineNo) :: lines, Awaiting) =>
          loop(lines, mkLocation(section.trim(), lineNo), Awaiting, result)
        // not reading snippet. skipping over this line
        case ((line, lineNo) :: lines, Awaiting) =>
          loop(lines, location, Awaiting, result)
        // end of document reached, all snippets found
        case (Nil, _) => result.toList
      }

    loop(markdown.content.zipWithIndex.map { case (l, no) => l -> (no + 1) }, mkLocation(""), Awaiting, Vector.empty)
  }
}

/** Interface allowing customizations to the test behavior. */
trait Runner:

  /** Directory where markdown files would be sought. */
  def docsDir: File
  /** Directory where snippets should be written. */
  def tmpDir: File
  /** Filter passed to --test-only parameter. */
  def filter: Option[String]

  private lazy val filterPattern = filter.map(f => Regex.quote(f).replaceAll("[*]", raw"\\E.*\\Q").replaceAll(raw"\\Q\\E", "").r)

  extension (snippet: Snippet)
    /** Writes content of each file that the snippet is make of to the disc.
      * 
      * @return name of the directory .sc/.scala files were written to
      */
    def save(): File = {
      val snippetDir = File(s"${tmpDir.getPath()}/${snippet.dirName}")
      snippetDir.mkdirs()
      snippet.content.fileToContentMap.foreach { case (fileName, Snippet.Content.Single(content)) =>
        val file = File(s"${snippetDir.getPath()}/$fileName")
        file.getParentFile().mkdirs() // in case file was named `packagename/file.scala` or similar
        Files.writeString(file.toPath(), content)
      }
      snippetDir
    }

    /** Runs Scala CLI, assumes that files were written before using [[save()]] command.
      * 
      * @return the result of the Scala CLI run
      */
    def run(): RunResult =
      snippet.content match
        case Snippet.Content.Multiple(files) if files.keys.exists(_.endsWith(".test.scala")) =>
          // run test only if there is at least 1 .test.scala snippet
          RunResult("scala-cli", "test", File(s"${tmpDir.getPath()}/${snippet.dirName}").getPath())
        case _ =>
          RunResult("scala-cli", "run", File(s"${tmpDir.getPath()}/${snippet.dirName}").getPath())

    /** Called after [[Snippet]] creation, allows adjusting its content e.g. interpolating templates or adding directoves. */
    def adjusted: Snippet

    /** Whether the [[Snippet]] should be: tested for succedd, errors or ignored. */
    def howToRun: Runner.Strategy

    /** Whether this [[Snippet]] should be run according to the --test-only filter (can still be ignored by the strategy!). */
    def isTested: Boolean = filterPattern.forall(_.matches(snippet.stableName))

  extension (snippets: List[Snippet])
    /** Called after all [[Snippet]]s creation, allows adjusting whole suite e.g. grouping single snippets into multiple files snippets. */
    def adjusted: List[Snippet]

object Runner:
  enum Strategy:
    case ExpectSuccess(outputs: List[String])
    case ExpectErrors(errors: List[String])
    case Ignore(cause: String)

  private val outputStart = raw"\s*// expected output:\s*".r
  private val errorStart = raw"\s*// expected error:\s*".r
  private val comment = raw"\s*// (.+)".r
  private def extractMsg(content: String, msgStart: Regex): List[String] = {
    enum State:
      case ReadingErrMsg(current: Vector[String])
      case Skipping
    content
      .split("\n")
      .foldLeft((State.Skipping: State) -> Vector.empty[String]) {
        case ((State.ReadingErrMsg(currentMsg), allMsgs), comment(content)) => State.ReadingErrMsg(currentMsg :+ content) -> allMsgs
        case ((State.ReadingErrMsg(currentMsg), allMsgs), _)                => State.Skipping -> (allMsgs :+ currentMsg.mkString("\n"))
        case ((State.Skipping, allMsgs), msgStart())                        => State.ReadingErrMsg(Vector.empty) -> allMsgs
        case ((State.Skipping, allMsgs), _)                                 => State.Skipping -> allMsgs
      }
      .match {
        case (State.ReadingErrMsg(currentErrorMsg), allErrMsgs) => allErrMsgs :+ (currentErrorMsg.mkString("\n"))
        case (State.Skipping, allErrorMsgs)                     => allErrorMsgs
      }
      .toList
  }
  def extractOutputs(content: String): List[String] = extractMsg(content, outputStart)
  def extractErrors(content: String): List[String] = extractMsg(content, errorStart)

  private val multipleFileHeader = raw"\s*// file: (.+) - part of (.+)".r

  /** The default implementation, that could be extended or delegated to.
    *
    * It:
    *  - does not modify single snippet
    *  - aggregates snippets by "// file: [file name] - part of [example name]"
    *  - ignores snippets which do not contains a single "// using"
    *  - ignores snippets containing libraryDependencies (to not run sbt examples)
    *  - tests for errors if there is any "// expected error:""
    *  - othersie tests for success, testing if output contains values under "// expected output:"
    * 
    * @param docsDir directory where markdown files would be sought
    * @param tmpDir directory where snippets should be written
    * @param filter filter passed to --test-only parameter
    */
  class Default(val docsDir: File, val tmpDir: File, val filter: Option[String]) extends Runner:
    /** Auxilary constructor populating values from [[TestConfig]] from parsed arguments.
      *
      * @param cfg config provided by parsing arguments
      */
    def this(cfg: TestConfig) = this(cfg.docsDir, cfg.tmpDir, cfg.filter)

    extension (snippet: Snippet)
      def adjusted: Snippet = snippet
      def howToRun: Strategy =
        snippet.content.fileToContentMap.view.values.map { case Snippet.Content.Single(content) =>
          // for simplicity: we're assuming that each actual example should have //> using with some config
          // OR should be a part of multiple-file snippet
          if !content.contains("//> using") && multipleFileHeader.findFirstIn(content).isEmpty then Strategy.Ignore("pseudocode")
          // for simplicity: we're assuming that only sbt examples have libraryDependencies
          else if content.contains("libraryDependencies") then Strategy.Ignore("sbt example")
          // for simplicity: we're assuming that errors are defined in inline comments starting with '// expected error:'
          else if content.contains("// expected error:") then Strategy.ExpectErrors(extractErrors(content))
          else Strategy.ExpectSuccess(extractOutputs(content))
        }.reduce {
          // if there is any Ignored, then the first reason wins
          case (Strategy.Ignore(reason), _) => Strategy.Ignore(reason)
          case (_, Strategy.Ignore(reason)) => Strategy.Ignore(reason)
          // if there is any ExpectErrors, then errors are aggregates, outputs are discarded
          case (Strategy.ExpectErrors(errors1), Strategy.ExpectErrors(errors2)) => Strategy.ExpectErrors(errors1 ++ errors2)
          case (Strategy.ExpectErrors(errors), _) => Strategy.ExpectErrors(errors)
          case (_, Strategy.ExpectErrors(errors)) => Strategy.ExpectErrors(errors)
          // if all ExpectSuccess, aggregate outputs
          case (Strategy.ExpectSuccess(outputs1), Strategy.ExpectSuccess(outputs2)) => Strategy.ExpectSuccess(outputs1 ++ outputs2)
        }
    extension (snippets: List[Snippet])
      def adjusted: List[Snippet] =
        val multiFileExamples = snippets.flatMap {
          case snippet @ Snippet(_, Snippet.Content.Single(content)) =>
            multipleFileHeader.findFirstMatchIn(content).collect {
              case Regex.Groups(fileName, exampleName) => exampleName -> (fileName, snippet) 
            }
          case snippet => None
        }.groupMapReduce(_._1) {
          case (_, (fileName, snippet @ Snippet(locations, Snippet.Content.Single(content)))) =>
            // add // file.md:lineNo at the beginning of each file multiple file snippet
            Snippet(locations, Snippet.Content.Multiple(ListMap(fileName -> Snippet.Content.Single(s"// ${snippet.hint}\n$content"))))
          case (_, (_, snippet)) => snippet
        }(_.combine(_))
        .flatMap {
          case (_, snippet) => snippet.locations.map(_ -> snippet)
        }.toMap

        snippets.flatMap { snippet =>
          multiFileExamples.get(snippet.location) match {
            case Some(found) =>
              // replace the first component with merged snippet, discard the rest
              if found.location == snippet.location then List(found) else Nil
            case _ =>
              // keep individual snippets as they are
              List(snippet)
          }
        }.sortBy(_.location)

/** Represents a test suite created out of a single markdown file.
  * 
  * @param name name of the suite (markdown name without extension)
  * @param snippets snippets that make this suite
  */
case class Suite(name: String, snippets: List[Snippet]) {

  def run(using Runner): Suite.Result =
    if snippets.exists(_.isTested) then {
      println(hl"$name" + ":")
      val (failed, successfulOrIgnored) = snippets.filter(_.isTested).partitionMap { snippet =>
        println()
        import snippet.{hint, stableName}
        def previewSnippet = snippet.content match
          case Snippet.Content.Single(content) => content
          case Snippet.Content.Multiple(files) => files.values.map(_.content).mkString("\n")
        snippet.howToRun match
          case Runner.Strategy.ExpectSuccess(outputs) =>
            val snippetDir = snippet.save()
            println(hl"Snippet $stableName ($hint) saved in $snippetDir, testing" + ":\n" + previewSnippet)
            val RunResult(exitCode, out, _, _) = snippet.run()
            val sanitized =
              out.replaceAll(raw"snippet\.this\.", "").replaceAll(raw"snippet\.", "").replaceAll(raw"\[error\] ", "")
            lazy val unmatched = outputs.filterNot(output => sanitized.contains(output.trim))
            if exitCode != 0 then
              println(red"Snippet $stableName ($hint) failed")
              Left(snippet)
            else if unmatched.nonEmpty then
              println(red"Snippet $stableName ($hint) shoule have produced outputs:" + "\n" + unmatched.mkString("\n"))
              Left(snippet)
            else
              println(green"Snippet $stableName ($hint) succeeded")
              Right(None)
          case Runner.Strategy.ExpectErrors(errors) =>
            val snippetDir = snippet.save()
            println(hl"Snippet $stableName ($hint) saved in $snippetDir, testing" + ":\n" + previewSnippet)
            val RunResult(exitCode, _, err, _) = snippet.run()
            val sanitized =
              err.replaceAll(raw"snippet\.this\.", "").replaceAll(raw"snippet\.", "").replaceAll(raw"\[error\] ", "")
            lazy val unmatched = errors.filterNot(error => sanitized.contains(error.trim))
            if exitCode == 0 then
              println(red"Snippet $stableName ($hint) should have produced error(s)")
              Left(snippet)
            else if unmatched.nonEmpty then
              println(red"Snippet $stableName ($hint) shoule have produced errors:" + "\n" + unmatched.mkString("\n"))
              println(red"got:" + "\n" + sanitized)
              Left(snippet)
            else
              println(green"Snippet $stableName ($hint) failed as expected")
              Right(None)
          case Runner.Strategy.Ignore(cause) =>
            println(yellow"Snippet $stableName ($hint) was ignored ($cause)")
            Right(Some(snippet))
      }
      val ignored = successfulOrIgnored.collect { case Some(snippet) => snippet }
      val succeed = snippets.filterNot(failed.contains).filterNot(ignored.contains)
      if failed.nonEmpty then {
        println(red"Results: ${succeed.size} succeed, ${ignored.length} ignored, ${failed.length} failed - some snippets failed:")
        failed.foreach(s => println(red"  ${s.stableName} (${s.hint})}"))
        println()
      } else {
        println(green"Results: ${succeed.size} succeed, ${ignored.length} ignored, all snippets succeeded")
        println()
      }
      Suite.Result(suiteName = name, succeed = succeed, failed = failed, ignored = ignored)
    } else Suite.Result(suiteName = name, succeed = List.empty, failed = List.empty, ignored = List.empty)
}
object Suite {
  /** Result of the suite execution (or skipping).
    * 
    * @param suiteName the name of the suite these results are for
    * @param succeed which snippets succeed
    * @param failed which snippets failed
    * @param ignored which snippets were ignored
    */
  case class Result(suiteName: String, succeed: List[Snippet], failed: List[Snippet], ignored: List[Snippet])
}

/** Config read from Array[String] provided in main.
  * 
  * @param docsDir directory where markdown files would be sought
  * @param tmpDir directory where snippets should be written
  * @param filter filter passed to --test-only parameter
  * @param extra values provided with --extra k=v parameters, can be used by users in their [[Runner]]s
  */
case class TestConfig(
    docsDir: File,
    tmpDir: File,
    filter: Option[String],
    extra: Map[String, String]
)
object TestConfig {
  import com.monovore.decline.*

  val defn = Command("test-snippets", "Turn Scala snippets in Markdown files into test suites", helpFlag = true) {
    import cats.data.{Validated, ValidatedNel}
    import cats.implicits.*

    given Argument[(String, String)] with
      def read(string: String): ValidatedNel[String, (String, String)] =
        string.split("=").toList match
          case key :: value :: Nil => Validated.valid(key -> value)
          case _                   => Validated.invalidNel(s"Expected pair, got: $string")
      def defaultMetavar: String = "<key>=<value>"

    (
      Opts.argument[Path](metavar = "docs"),
      Opts.argument[Path](metavar = "tmp").orNone,
      Opts.option[String](long = "test-only", short = "f", help = "Run only tests matching filter").orNone,
      Opts.options[(String, String)](long = "extra", help = "").orNone
    ).mapN { (docs, tmpOpt, filter, extras) =>
      TestConfig(
        docsDir = docs.toFile,
        tmpDir = tmpOpt.map(_.toFile).getOrElse(Files.createTempDirectory(s"docs-snippets").toFile()),
        filter = filter,
        extra = extras.map(_.toList.toMap).getOrElse(Map.empty)
      )
    }
  }

  def parse(args: Array[String]): Either[Help, TestConfig] = defn.parse(args = args, env = sys.env)
}

// program

/** Run tests using [[Runner]]. */
val testSnippetsWithRunner: Runner ?=> Unit = {
  println(hl"Testing with docs in ${summon[Runner].docsDir}, snippets extracted to: tmp=${summon[Runner].tmpDir}")
  println(hl"Started reading from ${summon[Runner].docsDir.getAbsolutePath()}")
  println()
  val markdowns = Markdown.readFromDir(summon[Runner].docsDir)
  println(hl"Read files: ${markdowns.map(_.name.fileName)}")
  println()
  val suites = markdowns.map { markdown =>
    Suite(markdown.name.simpleName, markdown.extractAll.map(_.adjusted).adjusted)
  }
  val (failed, succeed) = suites.map(_.run).partition(_.failed.nonEmpty)
  println()
  if failed.nonEmpty then {
    println(red"Failed suites:")
    failed.foreach(r => println(red"  ${r.suiteName}"))
    println(red"Fix them or add to ignored list")
    sys.exit(1)
  } else {
    println(green"All snippets run succesfully!")
  }
}

/** Parses arguments, creates [[Runner]] and uses it to run tests.
  * 
  * @param args arguments provided in main
  * @param f user-provided constructor of [[Runner]]
  */
def testSnippets(args: Array[String])(f: TestConfig => Runner = Runner.Default(_)): Unit =
  TestConfig.parse(args) match {
    case Right(cfg) => testSnippetsWithRunner(using f(cfg))
    case Left(help) => println(help); sys.exit(1)
  }

/** Version of [[testSnippets]] that could be used OOTB. */
@main def testSnippets(args: String*): Unit = testSnippets(args.toArray)()
