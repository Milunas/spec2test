package io.github.spec2test.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Runtime refinement checker — verifies that an implementation conforms
 * to its TLA+ specification by checking invariants after every action.
 *
 * This is a runtime companion to the generated test code. It can be
 * injected into production code (in test mode) to continuously verify
 * that TLA+ invariants hold after every annotated action.
 *
 * Usage:
 * <pre>
 * var checker = new SpecChecker(myService);
 * checker.beforeAction("Reserve");  // Snapshot pre-state
 * myService.reserve(userId);        // Execute action
 * checker.afterAction("Reserve");   // Verify invariants
 * </pre>
 *
 * Or with automatic hooking:
 * <pre>
 * SpecChecker.wrap(myService);  // All @TlaAction methods get invariant checks
 * </pre>
 */
public class SpecChecker {

    private final Object target;
    private final List<Field> variableFields;
    private final List<Method> invariantMethods;
    private final Map<String, Method> actionMethods;
    private Map<String, Object> preState;

    public SpecChecker(Object target) {
        this.target = target;
        this.variableFields = new ArrayList<>();
        this.invariantMethods = new ArrayList<>();
        this.actionMethods = new HashMap<>();

        // Discover annotated fields and methods via reflection
        for (Field f : target.getClass().getDeclaredFields()) {
            if (f.isAnnotationPresent(TlaVariable.class)) {
                f.setAccessible(true);
                variableFields.add(f);
            }
        }
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(TlaInvariant.class)) {
                m.setAccessible(true);
                invariantMethods.add(m);
            }
            if (m.isAnnotationPresent(TlaAction.class)) {
                m.setAccessible(true);
                TlaAction ann = m.getAnnotation(TlaAction.class);
                actionMethods.put(ann.name(), m);
            }
        }
    }

    /**
     * Capture the pre-action state snapshot.
     */
    public void beforeAction(String actionName) {
        preState = captureState();
    }

    /**
     * After an action executes, verify all invariants hold.
     *
     * @throws InvariantViolationException if any invariant is violated
     */
    public void afterAction(String actionName) {
        Map<String, Object> postState = captureState();
        checkInvariants(actionName, postState);
    }

    /**
     * Capture current values of all @TlaVariable fields.
     */
    public Map<String, Object> captureState() {
        var state = new LinkedHashMap<String, Object>();
        for (Field f : variableFields) {
            try {
                TlaVariable ann = f.getAnnotation(TlaVariable.class);
                state.put(ann.name(), Spec2TestRuntime.deepCopy(f.get(target)));
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot read @TlaVariable field: " + f.getName(), e);
            }
        }
        return state;
    }

    /**
     * Check all @TlaInvariant methods on the target object.
     */
    public void checkInvariants(String actionName, Map<String, Object> state) {
        for (Method m : invariantMethods) {
            try {
                Object result = m.invoke(target);
                if (result instanceof Boolean b && !b) {
                    TlaInvariant ann = m.getAnnotation(TlaInvariant.class);
                    throw new InvariantViolationException(
                        ann.name(), actionName, state, preState
                    );
                }
            } catch (InvariantViolationException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Error checking invariant: " + m.getName(), e);
            }
        }
    }

    /**
     * Check all invariants using the current state.
     */
    public void checkInvariants() {
        checkInvariants("<direct>", captureState());
    }

    /**
     * Get the list of discovered TLA+ variable names.
     */
    public List<String> getVariableNames() {
        return variableFields.stream()
            .map(f -> f.getAnnotation(TlaVariable.class).name())
            .toList();
    }

    /**
     * Get the list of discovered TLA+ invariant names.
     */
    public List<String> getInvariantNames() {
        return invariantMethods.stream()
            .map(m -> m.getAnnotation(TlaInvariant.class).name())
            .toList();
    }

    /**
     * Get the list of discovered TLA+ action names.
     */
    public List<String> getActionNames() {
        return new ArrayList<>(actionMethods.keySet());
    }

    /**
     * Exception thrown when a TLA+ invariant is violated at runtime.
     */
    public static class InvariantViolationException extends RuntimeException {
        private final String invariantName;
        private final String actionName;
        private final Map<String, Object> postState;
        private final Map<String, Object> preState;

        public InvariantViolationException(
            String invariantName,
            String actionName,
            Map<String, Object> postState,
            Map<String, Object> preState
        ) {
            super(String.format(
                "TLA+ invariant '%s' violated after action '%s'%nPre-state:  %s%nPost-state: %s",
                invariantName, actionName, preState, postState
            ));
            this.invariantName = invariantName;
            this.actionName = actionName;
            this.postState = postState;
            this.preState = preState;
        }

        public String getInvariantName() { return invariantName; }
        public String getActionName() { return actionName; }
        public Map<String, Object> getPostState() { return postState; }
        public Map<String, Object> getPreState() { return preState; }
    }
}
