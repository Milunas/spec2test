package io.github.spec2test.runtime;

import java.lang.annotation.*;

/**
 * Marks a method as corresponding to a TLA+ action.
 *
 * Used by the runtime refinement checker to verify that the method's
 * precondition and postcondition match the TLA+ action's guard and effects.
 *
 * Example:
 * <pre>
 * {@literal @}TlaAction(name = "Increment", spec = "Counter")
 * public void increment() { count++; }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TlaAction {
    /** TLA+ action name */
    String name();
    /** TLA+ module name */
    String spec() default "";
}
