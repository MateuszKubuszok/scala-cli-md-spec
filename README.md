# ScalaCLI.md Spec

Turn each Scala CLI snippet from your markdown documentation into a test case
and each markdown document into a test suite.

## Usage

```scala
//> using scala 3.3.3
//> using dep "com.kubuszok::scala-cli-md-spec:0.0.1"
import com.kubuszok.scalaclimdspec
@main def testSnippets(args: String*): Unit = scalaclimdspec.testSnippets(args.toArray) { cfg =>
  new scalaclimdspec.Runner.Default(cfg) // or provide your own :)
}
```

```bash
# run all tests
scala-cli run test-snippets.scala -- "$PWD/docs"
# run only tests from Section in my-markdown.md
scala-cli run test-snippets.scala -- --test-only="my-markdown.md#Section*" "$PWD/docs"
```

## Rules

 1. each markdown is its own suite
 2. by default only Scala snipets with `//> using` are considered
 3. snippets are tested for lack of errors
 4. unless there is `// expected error:` followed by inline comments with the error show in Scala CLI in the error stream
