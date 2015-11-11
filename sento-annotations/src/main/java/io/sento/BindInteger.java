package io.sento;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
@ResourceBindings(
    @ResourceBinding(
        type = "int",
        getter = "getInteger"
    )
)
public @interface BindInteger {
  public int value();
}
