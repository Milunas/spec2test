# spec2test

**A TLA+ Specification-to-Java Test Generator**

Automatically generates JUnit 5 test suites from TLA+ formal specifications, providing specification-to-implementation conformance testing with three test generation strategies.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          TLA+ Specification                        │
│                         (.tla + .cfg files)                        │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  spec2test-parser         SANY (official TLA+ parser)              │
│  ─────────────                                                     │
│  SanyTlaParser.kt         SANY AST → TlaExpr tree conversion      │
│                           Full TLA+ language support               │
│                           Type inference (TypeOK → Init → Untyped) │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  spec2test-ir             Typed Intermediate Representation        │
│  ───────────                                                       │
│  TlaModule                Root: variables, constants, operators     │
│  TlaExpr (sealed)         ~30 AST node types (full expression tree)│
│  TlaType (sealed)         Parameterized types (Set, Seq, Function) │
│  TlaOper (enum)           ~50 built-in operators                   │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
              ┌───────────┼───────────┐
              ▼           ▼           ▼
┌──────────────┐ ┌────────────┐ ┌──────────────┐
│  Sequential  │ │ Concurrent │ │ Trace-Guided │
│  Test Gen    │ │ Harness    │ │ (TLC Traces) │
│              │ │            │ │              │
│  Random walk │ │ CyclicBar- │ │ State graph  │
│  + bounded   │ │ rier phased│ │ DFS + cover- │
│  enumeration │ │ + stress   │ │ age strategy │
└──────┬───────┘ └─────┬──────┘ └──────┬───────┘
       │               │              │
       └───────────┬───┘──────────────┘
                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Generated Java/JUnit 5 Test Code                                  │
│  ─────────────────────────────────                                 │
│  - State variables as fields                                       │
│  - @BeforeEach init from Init predicate                            │
│  - Action methods with guard→effect→invariant pattern              │
│  - Invariant assertions embedded after each action                 │
│  - Random walk + bounded enumeration (sequential)                  │
│  - Synchronized + barrier-based phased execution (concurrent)      │
└─────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | Purpose |
|--------|---------|
| `spec2test-ir` | Core IR types: `TlaModule`, `TlaExpr`, `TlaType`, `TlaOper` |
| `spec2test-parser` | SANY-based parser: TLA+ → IR conversion |
| `spec2test-generator` | Three code generators: sequential, concurrent, trace-guided |
| `spec2test-runtime` | Java runtime library for generated tests (set/sequence operations, refinement checker) |
| `spec2test-gradle` | Gradle plugin for build integration |

## Quick Start

### Build

```bash
./gradlew build
```

### Preview Generated Tests From A Single Spec

If you do not have a project and just want to see what Java tests would be generated from a `.tla` file, run the built-in CLI from this repository:

```bash
./gradlew -q :spec2test-cli:run --args='--spec examples/reservation/Reservation.tla --stdout'
```

That prints the generated Java test classes directly to stdout.

If you want files instead of stdout:

```bash
./gradlew :spec2test-cli:run --args='--spec /absolute/path/to/YourSpec.tla --out build/generated/spec2test-preview'
```

You can also choose the generation mode:

```bash
./gradlew :spec2test-cli:run --args='--spec /absolute/path/to/YourSpec.tla --mode CONCURRENT --stdout'
```

Supported preview modes are `SEQUENTIAL` and `CONCURRENT`.

`TRACE_GUIDED` is not available from a `.tla` file alone because it requires a TLC state graph. That path is available through the lower-level `TlcTraceGenerator` API, not the simple preview CLI.

### Use In An Existing Gradle Project

You do not need a separate wrapper project. Apply the plugin in your existing Java or Kotlin/JVM build.

If you are using the local `spec2test` checkout before publishing it, add this to `settings.gradle.kts` in the existing project:

```kotlin
pluginManagement {
    includeBuild("/<path>/spec2test")
}
```

Then add this to `build.gradle.kts` in that existing project:

```kotlin
plugins {
    java
    id("io.github.spec2test")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

spec2test {
    tlaSourceDir.set(layout.projectDirectory.dir("src/main/tla"))
    outputDir.set(layout.buildDirectory.dir("generated/spec2test"))
    packageName.set("com.example.generated")
    mode.set("SEQUENTIAL")
}

tasks.test {
    useJUnitPlatform()
}
```

Put your `.tla` files under `src/main/tla`, then run:

```bash
./gradlew generateSpec2Tests test
```

Notes:
- `generateSpec2Tests` is available only in a build where the plugin is applied.
- Running `./gradlew generateSpec2Tests` in the `spec2test` repository itself fails unless this repository is acting as the consumer build.
- If you keep the defaults, only `src/main/tla` is required; the other settings are optional.

### Programmatic Usage

```kotlin
import io.github.spec2test.parser.SanyTlaParser
import io.github.spec2test.generator.JavaTestGenerator

val parser = SanyTlaParser()
val result = parser.parse(File("Counter.tla"))
val module = result.module ?: error("Parse failed: ${result.errors}")

val generator = JavaTestGenerator()
val config = JavaTestGenerator.Config(
    packageName = "com.example.generated",
    mode = JavaTestGenerator.GenerationMode.SEQUENTIAL
)
val tests = generator.generate(module, config)

tests.forEach { test ->
    File("${test.className}.java").writeText(test.code)
}
```

### Gradle Plugin

```kotlin
plugins {
    id("io.github.spec2test")
}

spec2test {
    tlaSourceDir = file("src/main/tla")
    outputDir = file("src/test/java/generated")
    packageName = "com.example.generated"
    mode = "SEQUENTIAL" // SEQUENTIAL | CONCURRENT | TRACE_GUIDED
}
```

Then run:
```bash
./gradlew generateSpec2Tests
```

## Test Generation Modes

### Sequential
Random walk testing + bounded state enumeration. Each action method checks guards, applies effects, and verifies all invariants.

### Concurrent
Barrier-based phased execution with `CyclicBarrier` + stress testing with `CountDownLatch`. Actions synchronized on a shared lock. Trace recording for failure diagnosis.

### Trace-Guided (TLC)
Reads TLC model checker state graphs. Selects traces via coverage strategies (transition coverage, state coverage). Each trace becomes a deterministic `@Test` that replays the exact sequence of actions with intermediate state assertions.

## Key Design Decisions

**Full AST preservation**: Unlike simpler approaches that pre-classify effects (e.g., "this is a set-add"), spec2test preserves the complete `TlaExpr` tree and defers classification to the generator phase. This enables correct handling of complex expressions that span multiple effect categories.

**SANY parsing**: Uses the official TLA+ parser (Lamport's SANY via `tla2tools.jar`) rather than a custom parser. This guarantees correct parsing of the full TLA+ language including operator precedence, module imports, and syntactic sugar.

**Type inference cascade**: TypeOK → Init → Untyped. The parser first looks at `TypeOK` for explicit type constraints (`x \in Nat`, `s \in SUBSET T`), falls back to Init assignments, and defaults to `Untyped` for variables it cannot infer.

## Requirements

- JDK 21+
- Gradle 8.10+
