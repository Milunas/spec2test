package io.github.spec2test.runtime;

import java.lang.annotation.*;

/**
 * Marks a method as a TLA+ invariant checker.
 *
 * The annotated method should return boolean: true if invariant holds.
 *
 * Example:
 * <pre>
 * {@literal @}TlaInvariant(name = "NonNegative", spec = "Counter")
 * public boolean nonNegative() { return count >= 0; }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TlaInvariant {
    /** TLA+ invariant name */
    String name();
    /** TLA+ module name */
    String spec() default "";
    /** TLA+ formula (for documentation) */
    String formula() default "";
}
