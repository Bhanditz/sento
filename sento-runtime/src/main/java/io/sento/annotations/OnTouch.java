package io.sento.annotations;

import android.view.View;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@ListenerBinding(
    owner = View.class,
    listener = View.OnTouchListener.class,
    setter = "setOnTouchListener",
    callback = "onTouch"
)
public @interface OnTouch {
  public int[] value();
}
