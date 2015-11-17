package io.sento.sample.annotations

import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import io.sento.annotations.ListenerBinding

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@ListenerBinding(
    owner = TextView::class,
    listener = OnTextChangeWatcher::class,
    setter = "addTextChangedListener"
)
public annotation class OnTextChange(vararg val value: Int)

public abstract class OnTextChangeWatcher : TextWatcher {
  override fun afterTextChanged(editable: Editable?) {
    // nothing to do
  }

  override fun beforeTextChanged(editable: CharSequence?, start: Int, count: Int, after: Int) {
    // nothing to do
  }
}