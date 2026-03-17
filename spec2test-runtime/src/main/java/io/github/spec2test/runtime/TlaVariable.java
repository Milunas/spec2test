package io.github.spec2test.runtime;

import java.lang.annotation.*;

/**
 * Marks a field as corresponding to a TLA+ state variable.
 *
 * Used by the runtime refinement checker to capture implementation state
 * snapshots and compare them against TLA+ spec-predicted states.
 *
 * Example:
 * <pre>
 * {@literal @}TlaVariable(name = "count", spec = "Counter")
 * private int count;
 * </pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TlaVariable {
    /** TLA+ variable name */
    String name();
    /** TLA+ module name */
    String spec() default "";
}
