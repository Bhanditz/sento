package io.sento.sample.annotations

import android.text.Editable
import android.text.TextWatcher
import io.sento.ListenerBinding

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@ListenerBinding(
    owner = "android.widget.TextView",
    listener = "io.sento.sample.annotations.SimpleTextWatcher",
    setter = "addTextChangedListener"
)
public annotation class OnTextChange(vararg val value: Int)

public abstract class SimpleTextWatcher : TextWatcher {
  // FIXME: https://github.com/nsk-mironov/sento/issues/28
  override abstract fun onTextChanged(sequence: CharSequence?, start: Int, before: Int, count: Int)

  override fun afterTextChanged(editable: Editable?) {
    // nothing to do
  }

  override fun beforeTextChanged(editable: CharSequence?, start: Int, count: Int, after: Int) {
    // nothing to do
  }
}
