# ScalaCLI.md Spec

Turn each Scala CLI snippet from your markdown documentation into a test case
and each markdown document into a test suite.

## Usage

### Scala CLI

Create `test-snippets.scala` (use at least Java 11):

```scala
//> using scala 3.3.3
//> using jvm temurin:1.11.0.23
//> using dep "com.kubuszok::scala-cli-md-spec:0.0.3"
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
coursier launch com.kubuszok:scala-cli-md-spec_3:0.0.3 -M com.kubuszok.scalaclimdspec.testSnippets -- "$PWD/docs"
# run only tests from Section in my-markdown.md
coursier launch com.kubuszok:scala-cli-md-spec_3:0.0.3 -M com.kubuszok.scalaclimdspec.testSnippets -- --test-only="my-markdown.md#Section*" "$PWD/docs"
```

## Rules of the game

 1. each markdown is its own suite
 2. by default only Scala snipets with `//> using` are considered
    * other snippets are considered pseudocode and are ignored
 3. by default snippets are tested for the lack of errors
    * by the lack of errors we mean that Scala CLI returns `0`
    * if there is `// expected output:` followed by inline comments, snippet will be expected to succeed and
      standard output is expected to contain the content provided in these comments
    * if there is `// expected error:` followed by inline comments, snippet will be expected to fails and
      standard error is expected to contain the content provided in these comments
 4. by default each snippet is standalone Scala snippet, it will be tested in a separate directory, containing
    a single `snippet.sc` file
 5. if `--test-only` flag is used, only suites containing at least 1 matching snippet and within them only
    the matching snippets will be run and displayed (but all markdowns still need to be read to find snippets
    and match them against the pattern!)
    * multiple pieces of code can be combined into one multi-file snippet with:
      `// file: filename.scala - part of X example` syntax that would group all `X example` snippets, use
      `filename.scala` for this particular snippet, and place it within the same directory as others

## Why though

Some people would ask: if you need to make sure code in your documentation compiles, why not use something like
[mdoc](https://scalameta.org/mdoc/)?

Well, because mdoc doesn't work for my cases:

 * it requires picking a specific documentation tool whether or not its look'n'feel is what authors desires
 * it makes it difficult to have different `scalacOptions`, different Scala versions and different libraries available
   in each snippet - all setting are global, so one would have to work real hard to around these limitations
 * since it depends on settings provided when the code was build (`scalacOptions`, libraries) code might not be exactly
   reproducibe by the users - if they just copy-paste the code from the docs, they might find the code not working
   since they were not aware of some flags, libraries or compiler plugins used by authors of the example

Meanwhile, there is one perfectly suitable tool for the job - [Scala CLI](https://scala-cli.virtuslab.org/). With
its `//> using` directives it is very easyt to create a self-contained, perfectly reproducible snippet. As a matter
of the fact, it even supports running snippets in [markdown files](https://scala-cli.virtuslab.org/docs/guides/power/markdown#markdown-inputs).
It has a downside, however, because such mode considers all snippets to be defined in the same scope (same Scala version,
same libraries, same compiler options).

This library extracts snippets from markdown files, put them into tmp directory and runs as standalone snippets
allowing each snippet to be self-contained, reproducible example.
