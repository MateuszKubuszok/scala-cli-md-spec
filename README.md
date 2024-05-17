# ScalaCLI.md Spec

Turn each Scala CLI snippet from your markdown documentation into a test case and each markdown document into a test suite.

## Usage

### Scala CLI

Create `test-snippets.scala` (use at least Java 11!):

```scala
//> using scala 3.3.3
//> using jvm temurin:1.11.0.23
//> using dep "com.kubuszok::scala-cli-md-spec:0.1.1"
import com.kubuszok.scalaclimdspec.*
@main def run(args: String*): Unit = testSnippets(args.toArray) { cfg =>
  new Runner.Default(cfg) // or provide your own :)
}
```

then run it with [Scala CLI](https://scala-cli.virtuslab.org/):

```bash
# run all tests
scala-cli run test-snippets.scala -- "$PWD/docs"
# run only tests from Section in my-markdown.md
scala-cli run test-snippets.scala -- --test-only="my-markdown.md#Section*" "$PWD/docs"
```

To see how one can customize the code to e.g. inject variables or use the newest library version
in an arbitrary markdown documentation generator see [Chimney's example](https://github.com/scalalandio/chimney/blob/29cd5048bee3b66c2d4d3d81dc17e0c0d5a4a128/scripts/test-snippets.scala).

### Coursier

If you are not providing any modification, you can run it straight from the [Coursier](https://get-coursier.io):

```bash
# run all tests
coursier launch com.kubuszok:scala-cli-md-spec_3:0.1.1 -M com.kubuszok.scalaclimdspec.testSnippets -- "$PWD/docs"
# run only tests from Section in my-markdown.md
coursier launch com.kubuszok:scala-cli-md-spec_3:0.1.1 -M com.kubuszok.scalaclimdspec.testSnippets -- --test-only="my-markdown.md#Section*" "$PWD/docs"
```

## Rules of the game

 1. each markdown is its own test suite
 2. by default only Scala (and Java) snipets containing `//> using` are considered tests
    * other snippets are considered pseudocode and are ignored

      ```scala
      // will be tested
      //> using scala 3.3.3
      println("yolo")
      ```

      ```scala
      // will NOT be tested
      println("yolo")
      ```

 3. by default snippets are tested for the lack of errors
    * by the lack of errors we mean that Scala CLI returns `0`

      ```scala
      // should pass
      //> using scala 3.3.3
      println("yolo")
      ```

      ```scala
      // thou shall NOT pass!
      //> using scala 3.3.3
      throw Exception("yolo")
      ```

    * if there is `// expected output:` followed by inline comments in the immediate next lines,
      the snippet will be expected to succeed and its standard **output** will be expected to contain the content provided in these comments

      ```scala
      // should pass
      //> using scala 3.3.3
      println("yolo")
      // expected output:
      // yolo
      ```
      
      ```scala
      // thou shall NOT pass!
      //> using scala 3.3.3
      println("yolo")
      // expected output:
      // eee macarena!
      ```

    * if there is `// expected error:` followed by inline comments in the immediate next lines,
      the snippet will be expected to fail and its standard **error** will be expected to contain the content provided in these comments

      ```scala
      // should pass
      //> using scala 3.3.3
      throw Exception("yolo")
      // expected error:
      // yolo
      ```

      ```scala
      // should pass
      //> using scala 3.3.3
      summon[String]
      // expected error:
      // No given instance of type String was found
      ```
      
      ```scala
      // thou shall NOT pass!
      //> using scala 3.3.3
      println("yolo")
      // expected error:
      // yolo
      ```

 4. by default each snippet is a standalone Scala snippet, it will be tested in a separate directory, containing a single `snippet.sc` file
    * multiple pieces of code can be combined into one multi-file snippet with:
      `// file: [filename] - part of [example name used for grouping]` syntax - e.g. `// file: filename.scala - part of X example` would group all `X example` snippets in the same directory,
      and use `filename.scala` as a filename for this particular piece of code

      ```scala
      // file: model.scala - part of multi-file
      case class Model(a: Int)
      ```

      ```scala
      // file: example.sc - part of multi-file
      println(Model(10))
      // expected output:
      // Model(10)
      ```

      With multi-file `//> using` is not required to consider the code as a Scala CLI test. Remember that to make it work, like with normal Scala CLI app,
      there should be either _exactly one `.sc` file_ **or** only `.scala` files with _exactly one explicitly defined `main`_.

    * if at least one file in a multi-file snippet has a name ending with `.test.scala`, then `scala-cli test [dirname]` will be used unstead of `scala-cli run [dirname]`
      (useful for e.g. defining macros in the compile scope and showing them in the test scope since Scala CLI is NOT multi modular and you cannot demonstrate macros in another way)

      ```scala
      // file: macro.scala - part of macro example
      // using scala 3.3.3

      object MyMacro:
        inline def apply[A](a: A): Unit = ${ applyImpl[A]('a) }

        import scala.quoted.*
        def applyImpl[A: Type](a: Expr[A])(using Quotes): Expr[Unit] = '{ () }
      ```

      ```scala
      // file: macro.test.scala - part of macro example
      //> using test.dep org.scalameta::munit::1.0.0-RC1

      class MacroSpec extends munit.FunSuite {
        test("Macro(a) should do thing") {
          assert(MyMacro("wololo") == ())
        }
      }
      ```

    * Java snippets should not only use `java` in markdown, but also define `// file: filename.java - part of ...`

      ```java
      // file: MyEnum.java - part of java enum example
      enum MyEnum {
        ONE, TWO;
      }
      ```

      ```scala
      // file: snippet.sc - part of java enum example
      println(MyEnum.values())
      ```

 4. if `--test-only` flag is used, only suites containing at least 1 matching snippet and, within them, only
    the matching snippets will be run and displayed (but all markdowns still need to be read to find snippets
    and match them against the pattern!)

    ```bash
    # quotes around * are needed in shell

    # test all snippets
    scala-cli run test-snippets.scala -- --test-only '*' "$PWD/docs"
    # test all snippets in my-markdown.md
    scala-cli run test-snippets.scala -- --test-only 'my-markdown.md#*' "$PWD/docs"
    # test all snippets in my-markdown.md, in section name starting with My Section
    scala-cli run test-snippets.scala -- --test-only 'my-markdown.md#My section*' "$PWD/docs"
    ```

## Why though

Some people would ask: if you need to make sure code in your documentation compiles, why not use something like [mdoc](https://scalameta.org/mdoc/)?

Well, because mdoc doesn't work for my cases:

 * it requires picking a specific documentation tool whether or not its look'n'feel is what authors desires
 * it makes it difficult to have different `scalacOptions`, different Scala versions and different libraries available
   in each snippet - all settings are global, so one would have to work really hard around these limitations
 * since it depends on settings provided when the code was build (`scalacOptions`, libraries) code might not be exactly
   reproducibe by the users - if they just copy-paste the code from the docs, they might find the code not working
   since they were not aware of some flags, libraries or compiler plugins used by authors of the example

Meanwhile, there is one perfectly suitable tool for the job - [Scala CLI](https://scala-cli.virtuslab.org/). With
its `//> using` directives it is very easy to create a self-contained, perfectly reproducible snippet. As a matter
of the fact, it even supports running snippets in [markdown files](https://scala-cli.virtuslab.org/docs/guides/power/markdown#markdown-inputs).
Its markdown support has a downside, however, because this mode considers all snippets to be defined in the same scope (same Scala version,
same libraries, same compiler options - different `//> using` classes are appended and may override conflicting options).

This library extracts snippets from markdown files, put them into separate `/tmp` subdirectories, and runs as standalone snippets
- allowing each snippet to be self-contained, reproducible example.
