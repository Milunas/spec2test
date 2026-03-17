# Formal Foundations

This document presents the theoretical basis for spec2test's translation from TLA+ specifications to Java test code.

## 1. Translation Correctness

### 1.1 Refinement Mapping

Let $S = (V, I, N, \Phi)$ be a TLA+ specification where:
- $V = \{v_1, \ldots, v_n\}$ are state variables
- $I$ is the initial predicate
- $N$ is the next-state relation
- $\Phi = \{\phi_1, \ldots, \phi_k\}$ are invariants

A generated Java test class $T$ establishes a refinement mapping $r: \Sigma_J \to \Sigma_{TLA+}$ where $\Sigma_J$ is the set of Java program states and $\Sigma_{TLA+}$ is the set of TLA+ states.

**Theorem (Translation Soundness)**: If the generated test $T$ passes (no assertion failures), then the Java implementation under test *refines* $S$ for the explored state space.

Formally: For every execution trace $\sigma = s_0, s_1, \ldots, s_m$ explored by $T$:
1. $r(s_0) \models I$ (initial state satisfies Init)
2. $\forall i: (r(s_i), r(s_{i+1})) \models N \lor (r(s_i) = r(s_{i+1}))$ (each step is either a Next step or a stutter step)
3. $\forall i, \forall \phi_j \in \Phi: r(s_i) \models \phi_j$ (invariants hold at every state)

### 1.2 Bounded Completeness

spec2test provides *bounded completeness*: for a given bound $B$ on the number of action steps, the sequential bounded enumeration test explores all reachable states up to depth $B$.

**Theorem**: For a specification $S$ with finite constant domains and bound $B$, if the bounded enumeration test passes, then $\forall \sigma$ reachable in $\leq B$ steps: $\sigma \models \bigwedge \Phi$.

This is analogous to bounded model checking (Biere et al., 2003) but in the testing domain.

## 2. Action Decomposition

### 2.1 Guard-Effect Normal Form

Every TLA+ action $A(x_1, \ldots, x_p)$ is decomposed into:

$$A = G \land E$$

where:
- $G$ (guard): conjunction of conjuncts containing no primed variables
- $E$ (effect): conjunction of assignments $v_i' = e_i$

The decomposition algorithm:
1. Flatten the action body to a `ConjList`
2. Partition conjuncts by `containsPrimedRefs()`
3. Guard = conjuncts without primed refs
4. Effect = conjuncts with primed refs

**Soundness**: This decomposition is sound because TLA+ actions are conjunctions where guards and effects are independent — a guard cannot constrain a primed variable, and an effect cannot constrain an unprimed-only expression.

### 2.2 Effect Classification

Primed assignments are classified into Java update patterns:

| TLA+ Pattern | Java Translation |
|---|---|
| $v' = v + k$ | `v = v + k;` |
| $v' = v \cup S$ | `v.addAll(S);` |
| $v' = v \setminus S$ | `v.removeAll(S);` |
| $v' = \text{Append}(v, e)$ | `v.add(e);` |
| $v' = [v \text{ EXCEPT } ![k] = e]$ | `v.put(k, e);` |
| $v' = e$ (general) | `v = e;` |

## 3. Type System

### 3.1 Type Inference

The type inference algorithm uses a cascade strategy:

1. **TypeOK analysis**: Extract type constraints from $v \in T$ and $v \subseteq T$ conjuncts
2. **Init analysis**: Infer types from initial value assignments
3. **Default**: `Untyped` (generates `Object`)

The mapping from TLA+ types to Java types:

| TLA+ Type | Java Type |
|---|---|
| `Nat`, `Int` | `int` |
| `BOOLEAN` | `boolean` |
| `STRING` | `String` |
| `SUBSET T` | `Set<T>` (→ `HashSet<T>`) |
| `Seq(T)` | `List<T>` (→ `ArrayList<T>`) |
| `[D -> R]` | `Map<D, R>` (→ `HashMap<D, R>`) |
| `T1 \X T2` | `List<Object>` |
| `[f1: T1, ..., fn: Tn]` | `Map<String, Object>` |

### 3.2 Type Safety

**Proposition**: The type mapping preserves the algebraic structure of TLA+ types. Specifically:
- Set operations (union, intersection, difference, membership) are closed under the mapped Java type
- Sequence operations (Append, Head, Tail, Len) are correctly typed under the mapped Java List type
- Function operations (application, EXCEPT) are correctly typed under the mapped Java Map type

## 4. Concurrent Test Generation

### 4.1 Interleaving Coverage

The concurrent test harness generates $k$ threads, each executing random action sequences. The `CyclicBarrier` phased execution ensures:

1. All threads reach the barrier before any proceed
2. Invariants are checked at quiescence points (when all threads are at the barrier)
3. Actions are `synchronized` to prevent data races on shared state

**Theorem (Concurrent Soundness)**: If the concurrent test passes for all interleavings explored, then the implementation is safe under those specific thread schedules. The test provides a *necessary* but not *sufficient* condition for general thread safety.

### 4.2 Stress Testing

The stress test uses `CountDownLatch` to maximize contention:
- All threads start simultaneously
- Each thread executes random actions in a tight loop
- Trace recording captures the interleaving for failure replay

## 5. TLC Trace-Guided Testing

### 5.1 State Graph Coverage

Given a TLC state graph $G = (S, T, S_0)$ where:
- $S$ = set of states
- $T \subseteq S \times S$ = transitions labeled with action names
- $S_0 \subseteq S$ = initial states

spec2test generates test cases by:
1. Enumerating traces via DFS from $S_0$ up to `maxDepth`
2. Selecting traces using coverage strategies:
   - **Transition coverage**: greedy set-cover to minimize traces while covering all transitions
   - **State coverage**: cover all reachable states
   - **All**: enumerate all traces (exponential)
3. Each trace becomes a deterministic `@Test` that replays the exact action sequence

**Theorem (Trace Coverage)**: Under transition coverage strategy, the generated test suite covers every transition in $G$ with at most $|T|$ tests (worst case), typically $O(|T| / \text{avg\_trace\_length})$.

### 5.2 Intermediate State Assertions

Each trace test includes `assertState()` calls at every step, verifying that the Java implementation's state matches the TLC-computed state. This provides *point-wise refinement checking* rather than just invariant checking.

## 6. Comparison with Related Work

| Tool | Approach | TLA+ Support | Output |
|---|---|---|---|
| **FASTEST** (Cristiá & Rossi) | Z/B method test generation | None (Z notation) | Abstract test cases |
| **TLC** (Lamport) | Explicit-state model checking | Full | Counterexample traces |
| **Apalache** (Konnov et al.) | Symbolic model checking via SMT | TLA+ subset | Counterexample traces |
| **tla2lincheck** | Regex parsing → Lincheck | Limited subset | Lincheck tests |
| **spec2test** | SANY parsing → JUnit 5 | Full TLA+ | Sequential + concurrent + trace-guided tests |

spec2test is the first tool to combine:
1. Full TLA+ parsing via SANY
2. Three complementary test generation strategies
3. Runtime refinement checking with annotation-based mapping

## References

- Lamport, L. (2002). *Specifying Systems: The TLA+ Language and Tools for Hardware and Software Engineers*. Addison-Wesley.
- Biere, A., et al. (2003). Bounded model checking. *Advances in Computers*, 58:117-148.
- Konnov, I., et al. (2019). TLA+ model checking made symbolic. *OOPSLA*.
- Cristiá, M. & Rossi, G. (2013). A set solver for finite set relation algebra. *CADE*.
