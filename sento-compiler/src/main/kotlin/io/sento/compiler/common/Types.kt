package io.sento.compiler.common

import io.sento.Binding
import io.sento.Finder
import org.objectweb.asm.Type

internal object Types {
  public val TYPE_OBJECT = Type.getType(Any::class.java)

  public val TYPE_VIEW = Type.getObjectType("android/view/View")
  public val TYPE_RESOURCES = Type.getObjectType("android/content/res/Resources")

  public val TYPE_BINDING = Type.getType(Binding::class.java)
  public val TYPE_FINDER = Type.getType(Finder::class.java)
}
