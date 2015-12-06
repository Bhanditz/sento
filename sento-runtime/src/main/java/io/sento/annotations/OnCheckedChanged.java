package io.sento.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@ListenerClass(
    owner = "android.widget.CompoundButton",
    listener = "android.widget.CompoundButton$OnCheckedChangeListener",
    setter = "setOnCheckedChangeListener",
    callback = "onCheckedChanged"
)
public @interface OnCheckedChanged {
  public int[] value();
}
