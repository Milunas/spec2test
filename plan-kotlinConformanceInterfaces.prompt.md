## Plan: Generate Java Conformance Interfaces from TLA+ Specs

**TL;DR**: Add a new `CONFORMANCE` generation mode that produces a **Java interface** (`{Module}Spec.java`) and an **abstract Java conformance test** (`Abstract{Module}ConformanceTest.java`) for each TLA+ spec. The developer implements the interface to bridge their production code, extends the abstract test with a one-liner factory method, and gets spec-driven random-walk conformance testing for free. Existing self-contained Java test generation stays unchanged.

### Example — what the developer experience looks like

For `Counter.tla`, the tool generates:

```java
// Generated: CounterSpec.java
public interface CounterSpec {
    void increment();
    void decrement();
    void read();
    int getCount();
    List<String> getHistory();
}
```

```java
// Generated: AbstractCounterConformanceTest.java
public abstract class AbstractCounterConformanceTest {
    protected abstract CounterSpec createSubject();
    protected int getMaxValue() { return 5; }

    // @BeforeEach: creates subject, asserts Init state
    // checkInvariants(): NonNegative, BoundedAbove on subject state
    // Guard functions: canIncrement(), canDecrement() — read subject state
    // @RepeatedTest randomWalkConformanceTest(): randomly calls enabled actions, checks invariants
}
```

The developer writes:
```java
public class MyCounterConformanceTest extends AbstractCounterConformanceTest {
    @Override
    protected CounterSpec createSubject() {
        return new MyCounterAdapter(new MyCounter());
    }
}
```

---

### Steps

**Phase 1 — Java Expression Translator Enhancement** *(no dependencies)*

1. Extend `JavaExprTranslator` with a configurable variable reference prefix (`"subject.get"` / getter style vs plain field access) to support conformance mode where state is read through the interface's getter methods.

**Phase 2 — Conformance Generator** *depends on Phase 1*

2. Create `spec2test-generator/.../JavaConformanceGenerator.kt` with two outputs per module:

3. **Interface file** (`{Module}Spec.java`):
   - One `void actionName(params...)` per TLA+ action (parameterized actions get typed params)
   - One getter `Type getVarName()` per TLA+ variable — read-only state observation
   - Javadoc linking each member to TLA+ source location

4. **Abstract test file** (`Abstract{Module}ConformanceTest.java`):
   - `protected abstract {Module}Spec createSubject()`
   - TLA+ `CONSTANTS` → `protected` getter methods with sensible defaults (developer overrides)
   - `@BeforeEach`: creates subject, asserts Init state matches spec
   - Private guard methods: `boolean canActionName()` — checks guard on `subject.getX()` state
   - `checkInvariants()` — invariant expressions evaluated on `subject.getX()` state
   - `@RepeatedTest randomWalkConformanceTest()` — random walk calling enabled actions through the interface
   - No bounded enumeration (requires state snapshot/restore — future enhancement)

**Phase 3 — CLI & Gradle Wiring** *depends on Phase 2*

5. Add `CONFORMANCE` to `GenerationMode` in `JavaTestGenerator.kt`
6. Update `Spec2TestCli.kt` — accept `--mode CONFORMANCE`, invoke `JavaConformanceGenerator`, output `.java` files
7. Update `Spec2TestPlugin.kt` — route `CONFORMANCE` mode to the new generator

**Phase 4 — Tests** *parallel with Phase 3*

8. Unit tests for `JavaExprTranslator` getter-prefix mode — expression translations with `subject.getCount()` style references
9. Unit tests for `JavaConformanceGenerator` — Counter.tla and Reservation.tla generate correct interface signatures, abstract test structure
10. CLI integration test — `--mode CONFORMANCE --spec examples/counter/Counter.tla` outputs `.java` files

**Phase 5 - Documentation & Examples**

---

### Relevant Files

- `spec2test-generator/src/main/kotlin/io/github/spec2test/generator/JavaTestGenerator.kt` — reuse `decomposeAction`, `extractInitAssignments`, `guessConstantDefault`
- `spec2test-generator/src/main/kotlin/io/github/spec2test/generator/JavaExprTranslator.kt` — reference for expression translation patterns
- `spec2test-ir/src/main/kotlin/io/github/spec2test/ir/Spec.kt` — IR types (`TlaModule.actions`, `.invariants`, `.init`, `.constants`, `.variables`)
- `spec2test-runtime/src/main/java/io/github/spec2test/runtime/Spec2TestRuntime.java` — complex set/map helpers still referenced by generated code
- `examples/counter/Counter.tla` — simple spec (2 vars, 3 actions, 2 invariants)
- `examples/reservation/Reservation.tla` — complex spec (parameterized actions, function types, quantified invariants, 3 constants)

### Verification

1. Unit test `JavaExprTranslator`: getter-prefix mode expression translations (`subject.getCount()` style)
2. Unit test generator: Counter.tla → output contains `interface CounterSpec`, correct method signatures, correct getter return types
3. Unit test generator: Reservation.tla → `void reserve(int user, int resource)`, overridable constant getters, quantified invariants
4. CLI integration: `--mode CONFORMANCE` writes `.java` files
5. Manual inspection of generated Java files for compilability

### Decisions

- **Void actions** — actions return `void`. The test knows the guard from the spec and only calls enabled actions.
- **Guards on SUT state** — the test reads `subject.getCount()` to decide which actions are callable, verifying the real implementation maintains valid transitions.
- **Constants as overridable getters** — `protected int getMaxValue() { return 5; }` — developer can override; defaults supplied from spec analysis.
- **No bounded enumeration** — would require state snapshot/restore on the SUT. Random walk provides good coverage; this can be added later via an optional `Restorable` interface.
- **Existing modes preserved** — `SEQUENTIAL`, `CONCURRENT`, `TRACE_GUIDED` are unchanged. `CONFORMANCE` is purely additive.
- **Java target** — plain Java interfaces consumable from both Java and Kotlin projects.

### Further Considerations

1. **Runtime dependency**: Guards/invariants reuse `Spec2TestRuntime` helpers for complex set operations (`powerSet`, `cartesianProduct` etc.) and use standard Java streams for simple operations.
2. **Action failure semantics**: When a developer's implementation throws on a valid action call, how should the test surface that? Recommendation: let it propagate as a test failure with diagnostic context.
3. **Generics for model-value constants**: Should `ReservationSpec` be generic over user/resource types? Recommendation: start with concrete `int` types; generics as future enhancement.
