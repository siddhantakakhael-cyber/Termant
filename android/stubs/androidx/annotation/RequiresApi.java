package androidx.annotation;

/** Compile-time stand-in for the AndroidX annotation, so the build needs no Maven dependencies. */
public @interface RequiresApi {
    int value() default 1;

    int api() default 1;
}
