package io.mironov.sento.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@ListenerClass(
    owner = "android.view.View",
    listener = "android.view.View$OnClickListener",
    setter = "setOnClickListener",
    callback = "onClick"
)
public @interface OnClick {
  public int[] value();
}
